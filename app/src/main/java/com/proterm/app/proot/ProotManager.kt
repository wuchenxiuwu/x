package com.proterm.app.proot

import android.content.Context
import android.net.Uri
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * 负责管理 proot 二进制与容器 rootfs。
 *
 * 设计：
 *  - proot 二进制全局共享一份（filesDir/proot/proot）
 *  - 每个容器有独立的 rootfs 目录（filesDir/containers/<containerId>/rootfs）
 *  - 首次创建容器时导入 proot，后续容器复用
 */
object ProotManager {

    private const val PROOT_DIR = "proot"
    private const val CONTAINERS_DIR = "containers"

    // ── proot 状态 ────────────────────────────────────────

    /** proot 二进制是否已导入 */
    fun hasProot(ctx: Context): Boolean {
        val exe = File(ctx.filesDir, "$PROOT_DIR/proot")
        return exe.exists() && exe.canExecute()
    }

    /** 取得 proot 可执行文件 */
    @Throws(IOException::class)
    fun prootExecutable(ctx: Context): File {
        val exe = File(ctx.filesDir, "$PROOT_DIR/proot")
        if (!exe.exists()) throw IOException("proot 未导入")
        return exe
    }

    /** 导入 proot 可执行文件（全局共享） */
    @Throws(IOException::class)
    fun importProot(ctx: Context, sourceUri: Uri) {
        val dir = File(ctx.filesDir, PROOT_DIR)
        dir.mkdirs()
        val exe = File(dir, "proot")

        ctx.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(exe).use { output -> input.copyTo(output) }
        } ?: throw IOException("无法打开选择的文件")

        if (!exe.setExecutable(true, false)) {
            exe.delete()
            throw IOException("无法设置可执行权限")
        }

        // ELF 魔数校验
        val magic = exe.inputStream().use { it.readNBytes(4) }
        if (!(magic.contentEquals(byteArrayOf(0x7F, 0x45, 0x4C, 0x46)))) {
            exe.delete()
            throw IOException("选择的文件不是有效的 ELF 可执行文件")
        }
    }

    // ── rootfs 管理（按容器隔离）──────────────────────────

    /** 获取指定容器的 rootfs 目录 */
    fun rootfsDir(ctx: Context, containerId: String): File {
        return File(ctx.filesDir, "$CONTAINERS_DIR/$containerId/rootfs")
    }

    /** 检查指定容器的 rootfs 是否完整 */
    fun hasRootfs(ctx: Context, containerId: String): Boolean {
        val dir = rootfsDir(ctx, containerId)
        return File(dir, "bin/sh").exists() && File(dir, "usr").exists()
    }

    /** 导入 rootfs 到指定容器目录 */
    @Throws(IOException::class)
    fun importRootfs(ctx: Context, sourceUri: Uri, containerId: String) {
        val dir = rootfsDir(ctx, containerId)
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()

        ctx.contentResolver.openInputStream(sourceUri)?.use { raw ->
            GZIPInputStream(raw).use { gzip ->
                TarArchiveInputStream(gzip).use { tar ->
                    var entry = tar.nextEntry
                    var count = 0
                    while (entry != null) {
                        val f = File(dir, entry.name)
                        if (entry.isDirectory) {
                            f.mkdirs()
                        } else {
                            f.parentFile?.mkdirs()
                            FileOutputStream(f).use { out -> tar.copyTo(out) }
                            val ownerExec = (entry.mode and 0b001_000_000) != 0
                            f.setExecutable(ownerExec, false)
                            f.setReadable(true, false)
                        }
                        count++
                        entry = tar.nextEntry
                    }
                    if (count == 0) throw IOException("tar 归档为空")
                }
            }
        } ?: throw IOException("无法打开选择的文件")

        if (!File(dir, "bin/sh").exists() || !File(dir, "usr").exists()) {
            dir.deleteRecursively()
            throw IOException("rootfs 不完整")
        }
    }

    // ── 容器启动 ──────────────────────────────────────────

    /** 启动指定容器的会话 */
    fun startSession(ctx: Context, cmd: String, rootfsPath: String): Int {
        return try {
            val proot = prootExecutable(ctx).absolutePath
            ProotBridge.startProot(proot, rootfsPath, cmd)
        } catch (e: Exception) {
            android.util.Log.e("ProTerm", "startSession failed", e)
            -1
        }
    }
}