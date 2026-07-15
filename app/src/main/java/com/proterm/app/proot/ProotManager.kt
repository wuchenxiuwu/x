package com.proterm.app.proot

import android.content.Context
import android.os.Build
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * 负责 proot 二进制与容器 rootfs 的本地落地，并启动容器会话。
 *
 * 流程：
 *  1. 首次运行从 assets 解压对应 ABI 的 proot 可执行文件到 filesDir，chmod 0755；
 *  2. 首次运行从 assets 解压 rootfs.tar.gz（Alpine 最小根文件系统）到 filesDir/rootfs；
 *  3. 通过 JNI（ProotBridge）启动 proot，将 PTY master fd 返回给上层。
 */
object ProotManager {

    private const val PROOT_DIR = "proot"
    private const val ROOTFS_DIR = "rootfs"
    private const val ROOTFS_ASSET = "rootfs.tar.gz"

    /** 取得（必要时解压）proot 可执行文件 */
    @Throws(IOException::class)
    fun prootExecutable(ctx: Context): File {
        val dir = File(ctx.filesDir, PROOT_DIR)
        val exe = File(dir, "proot")
        if (!exe.exists()) {
            val abi = pickAbi()
            val assetPath = "$PROOT_DIR/$abi/proot"
            ctx.assets.open(assetPath).use { input ->
                dir.mkdirs()
                FileOutputStream(exe).use { out -> input.copyTo(out) }
            }
            exe.setExecutable(true, false)
        }
        return exe
    }

    /** 取得（必要时解压）容器根文件系统目录 */
    @Throws(IOException::class)
    fun rootfsDir(ctx: Context): File {
        val dir = File(ctx.filesDir, ROOTFS_DIR)
        val hasBase = File(dir, "bin").exists() || File(dir, "usr").exists()
        if (!hasBase) {
            dir.mkdirs()
            ctx.assets.open(ROOTFS_ASSET).use { raw ->
                GZIPInputStream(raw).use { gzip ->
                    TarArchiveInputStream(gzip).use { tar ->
                        var entry = tar.nextEntry
                        while (entry != null) {
                            val f = File(dir, entry.name)
                            if (entry.isDirectory) {
                                f.mkdirs()
                            } else {
                                f.parentFile?.mkdirs()
                                FileOutputStream(f).use { out -> tar.copyTo(out) }
                                // 还原可执行位（取权限字低 9 位中的 owner-exec）
                                val ownerExec = (entry.mode and 0b001_000_000) != 0
                                f.setExecutable(ownerExec, false)
                                f.setReadable(true, false)
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
            }
        }
        return dir
    }

    /** 启动一个容器会话，返回 PTY master fd（<=0 表示失败） */
    fun startSession(ctx: Context, cmd: String = "/bin/sh"): Int {
        return try {
            val proot = prootExecutable(ctx).absolutePath
            val rootfs = rootfsDir(ctx).absolutePath
            // patch 点：默认 rootfs 环境变量（见 external/proot/README.md 改造点 2）
            System.setProperty("PROOT_DEFAULT_ROOTFS", rootfs)
            ProotBridge.startProot(proot, rootfs, cmd)
        } catch (e: Exception) {
            android.util.Log.e("ProTerm", "startSession failed", e)
            -1
        }
    }

    /** 优先选择已交叉编译的 ABI */
    private fun pickAbi(): String {
        val supported = Build.SUPPORTED_ABIS.toList()
        return supported.firstOrNull { it in setOf("arm64-v8a", "armeabi-v7a", "x86_64") }
            ?: "arm64-v8a"
    }
}
