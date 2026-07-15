package com.proterm.app.proot

/**
 * JNI 桥接对象：在 native 层创建 PTY 并 fork/exec proot，
 * 返回 PTY master 文件描述符给 Kotlin 侧做流式读写。
 *
 * 对应 native 实现：app/src/main/jni/proot_bridge.c
 */
object ProotBridge {
    init {
        System.loadLibrary("proot_bridge")
    }

    /**
     * 启动一个 proot 容器会话。
     *
     * @param prootPath proot 可执行文件绝对路径（需已 chmod 0755）
     * @param rootfs    已解压的容器根文件系统目录绝对路径
     * @param cmd       容器内初始执行命令，例如 "/bin/sh"
     * @return PTY master 文件描述符（>0 成功，<=0 失败）
     */
    @JvmStatic
    external fun startProot(prootPath: String, rootfs: String, cmd: String): Int

    /**
     * 设置 PTY 窗口尺寸（TIOCSWINSZ）。
     *
     * @param fd  [startProot] 返回的 PTY master fd
     * @param cols 列数
     * @param rows 行数
     */
    @JvmStatic
    external fun resizePty(fd: Int, cols: Int, rows: Int)
}
