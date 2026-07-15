/*
 * proot_bridge.c
 *
 * JNI 桥接层：在 native 侧完成"创建 PTY -> fork -> 子进程接管 PTY -> execl 启动 proot"
 * 的关键动作，并把 PTY master 文件描述符返回给 Kotlin 层做流式读写。
 *
 * 设计要点：
 *  - 不使用 openpty（部分 NDK 版本 bionic 未提供），改用传统 /dev/ptmx + grantpt/unlockpt/ptsname。
 *  - fork 之后、execl 之前绝不调用任何会加锁/分配堆的 bionic 或 Java 函数，
 *    仅做 setsid / ioctl TIOCSCTTY / dup2 / setenv / execl，保证 fork 安全。
 *  - proot 本身是一个独立的 tracer 进程，必须以"独立可执行文件"方式 execl，
 *    因此 proot 二进制由构建脚本交叉编译后放入 assets，再由 Kotlin 解压到 filesDir。
 */

#include <jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <pthread.h>
#include <android/log.h>

#define TAG "ProTerm-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/*
 * 创建一对 PTY。成功返回 0 并通过出参写出 master/slave fd。
 */
static int create_pty(int *master_fd, int *slave_fd) {
    int m = open("/dev/ptmx", O_RDWR);
    if (m < 0) {
        LOGE("open /dev/ptmx failed: %s", strerror(errno));
        return -1;
    }
    if (grantpt(m) < 0) {
        LOGE("grantpt failed: %s", strerror(errno));
        close(m);
        return -1;
    }
    if (unlockpt(m) < 0) {
        LOGE("unlockpt failed: %s", strerror(errno));
        close(m);
        return -1;
    }
    char *slave_name = ptsname(m);
    if (slave_name == NULL) {
        LOGE("ptsname failed: %s", strerror(errno));
        close(m);
        return -1;
    }
    int s = open(slave_name, O_RDWR);
    if (s < 0) {
        LOGE("open slave %s failed: %s", slave_name, strerror(errno));
        close(m);
        return -1;
    }
    *master_fd = m;
    *slave_fd = s;
    return 0;
}

/*
 * 回收线程：proot 退出后 waitpid 避免僵尸进程。
 * 不挂 Java 回调——上层 TerminalSession 读 EOF 时已通过 onExit 通知 Kotlin，
 * 这里只负责“清尸”，保持 native 层自包含、不反向依赖 JVM。
 */
static void *reaper_thread(void *arg) {
    pid_t pid = *(pid_t *)arg;
    free(arg);
    int status = 0;
    if (waitpid(pid, &status, 0) > 0) {
        if (WIFEXITED(status))
            LOGI("proot pid=%d exited, code=%d", (int)pid, WEXITSTATUS(status));
        else if (WIFSIGNALED(status))
            LOGI("proot pid=%d killed by signal %d", (int)pid, WTERMSIG(status));
    }
    return NULL;
}

static void spawn_reaper(pid_t pid) {
    pid_t *p = (pid_t *)malloc(sizeof(pid_t));
    if (p == NULL) return;
    *p = pid;
    pthread_t t;
    if (pthread_create(&t, NULL, reaper_thread, p) == 0) {
        pthread_detach(t);
    } else {
        free(p);
    }
}

/*
 * JNI 入口：启动一个 proot 会话。
 * 返回 PTY master fd（>0 成功，<=0 失败）。
 */
JNIEXPORT jint JNICALL
Java_com_proterm_app_proot_ProotBridge_startProot(
        JNIEnv *env, jclass clazz,
        jstring proot_path_, jstring rootfs_, jstring cmd_) {
    (void)clazz;

    const char *proot_path = (*env)->GetStringUTFChars(env, proot_path_, NULL);
    const char *rootfs     = (*env)->GetStringUTFChars(env, rootfs_, NULL);
    const char *cmd        = (*env)->GetStringUTFChars(env, cmd_, NULL);

    if (proot_path == NULL || rootfs == NULL || cmd == NULL) {
        LOGE("null argument");
        goto cleanup_fail;
    }

    int master = -1, slave = -1;
    if (create_pty(&master, &slave) < 0) {
        goto cleanup_fail;
    }

    /*
     * exec 错误管道：子进程 execl 失败时把 errno 写回父进程。
     * 写端设 FD_CLOEXEC——execl 成功时随新进程映像自动关闭，父进程 read 读到 EOF，
     * 据此区分“正常启动”与“exec 失败”，杜绝返回一个挂着却已死的 master fd。
     *
     * 注意：Android API 24 (minSdk) 没有 pipe2，用 pipe + fcntl 替代。
     */
    int err_pipe[2];
    if (pipe(err_pipe) < 0) {
        LOGE("pipe failed: %s", strerror(errno));
        close(master);
        close(slave);
        goto cleanup_fail;
    }
    fcntl(err_pipe[0], F_SETFD, FD_CLOEXEC);
    fcntl(err_pipe[1], F_SETFD, FD_CLOEXEC);

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
        close(err_pipe[0]);
        close(err_pipe[1]);
        close(master);
        close(slave);
        goto cleanup_fail;
    }

    if (pid == 0) {
        /* ---- 子进程：成为会话首进程并接管 PTY ---- */
        close(err_pipe[0]);              /* 子进程只写错误端 */
        setsid();
        if (ioctl(slave, TIOCSCTTY, 0) < 0) {
            LOGE("TIOCSCTTY failed: %s", strerror(errno));
            /* 非致命：setsid 已建立会话，PTY 仍可通过 dup2 的 std fd 工作 */
        }
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        close(master);

        setenv("TERM", "xterm-256color", 1);
        setenv("HOME", "/root", 1);
        setenv("PATH", "/usr/local/bin:/usr/bin:/bin", 1);

        /*
         * 以 rootfs 作为 / 启动目标命令。
         * -b 绑定宿主机 /dev /proc /sys，使容器内设备与伪文件系统可用。
         * -w 设定初始工作目录。
         * 如需伪装 root（某些内核裁剪 seccomp 的机型）可追加 "-0"。
         */
        execl(proot_path, "proot",
              "-r", rootfs,
              "-b", "/dev",
              "-b", "/proc",
              "-b", "/sys",
              "-w", "/root",
              cmd,
              (char *)NULL);

        /* 若执行到这里说明 execl 失败：把 errno 回传父进程后退出 */
        int e = errno;
        write(err_pipe[1], &e, sizeof(int));
        _exit(127);
    }

    /* ---- 父进程：关写端与 slave，读子进程 exec 结果 ---- */
    close(err_pipe[1]);
    close(slave);

    int child_errno = 0;
    ssize_t n = read(err_pipe[0], &child_errno, sizeof(int));
    close(err_pipe[0]);
    if (n > 0) {
        LOGE("child failed to exec proot (errno=%d): %s", child_errno, strerror(child_errno));
        close(master);
        (*env)->ReleaseStringUTFChars(env, proot_path_, proot_path);
        (*env)->ReleaseStringUTFChars(env, rootfs_, rootfs);
        (*env)->ReleaseStringUTFChars(env, cmd_, cmd);
        return -1;
    }

    LOGI("proot launched pid=%d master_fd=%d", (int)pid, master);
    spawn_reaper(pid);
    (*env)->ReleaseStringUTFChars(env, proot_path_, proot_path);
    (*env)->ReleaseStringUTFChars(env, rootfs_, rootfs);
    (*env)->ReleaseStringUTFChars(env, cmd_, cmd);
    return master;

cleanup_fail:
    if (proot_path) (*env)->ReleaseStringUTFChars(env, proot_path_, proot_path);
    if (rootfs)     (*env)->ReleaseStringUTFChars(env, rootfs_, rootfs);
    if (cmd)        (*env)->ReleaseStringUTFChars(env, cmd_, cmd);
    return -1;
}

/*
 * 设置 PTY 窗口尺寸（TIOCSWINSZ），让容器内程序感知 cols/rows。
 * 在终端 View 尺寸变化或初始创建后调用。
 */
JNIEXPORT void JNICALL
Java_com_proterm_app_proot_ProotBridge_resizePty(
        JNIEnv *env, jclass clazz,
        jint fd, jint cols, jint rows) {
    (void)env;
    (void)clazz;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short)cols;
    ws.ws_row = (unsigned short)rows;
    if (ioctl((int)fd, TIOCSWINSZ, &ws) < 0) {
        LOGE("TIOCSWINSZ failed: %s", strerror(errno));
    }
}
