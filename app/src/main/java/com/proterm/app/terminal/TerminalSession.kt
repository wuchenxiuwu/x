package com.proterm.app.terminal

import android.os.ParcelFileDescriptor
import com.proterm.app.proot.ProotBridge
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * 包裹一个 proot 会话的 PTY master fd：
 *  - 后台读线程把容器输出的【原始字节】喂给 [TerminalEmulator] 做 VT100 解析；
 *  - [write] / [writeByte] 向容器发送字节（命令、控制字符）；
 *  - [resize] 通过 JNI 设置 PTY 窗口尺寸，并同步更新本地 emulator 缓冲；
 *  - 会话结束通过 [onExit] 通知。
 */
class TerminalSession(
    masterFd: Int,
    private val emulator: TerminalEmulator,
    private val onExit: (Int) -> Unit
) {
    private val masterFdVal = masterFd
    private val pfd = if (masterFd > 0) ParcelFileDescriptor.fromFd(masterFd) else null
    private val input = if (pfd != null) FileInputStream(pfd.fileDescriptor) else null
    private val output = if (pfd != null) FileOutputStream(pfd.fileDescriptor) else null

    private val reader = Thread({ readLoop() }, "proterm-reader").apply { if (masterFd > 0) start() }

    private fun readLoop() {
        val buf = ByteArray(8192)
        val inp = input ?: return
        try {
            var n: Int
            while (inp.read(buf).also { n = it } > 0) {
                emulator.append(buf, 0, n)
            }
        } catch (_: Exception) {
            // 流关闭 / 会话结束
        } finally {
            onExit(0)
        }
    }

    /** 发送一段文本（UTF-8） */
    fun write(text: String) {
        output?.write(text.toByteArray(StandardCharsets.UTF_8))
        output?.flush()
    }

    /** 发送单个字节（如 0x03 = Ctrl-C，0x1a = Ctrl-Z，0x7f = DEL） */
    fun writeByte(b: Int) {
        output?.write(b)
        output?.flush()
    }

    /** 窗口尺寸变化：通知内核 PTY 并同步本地 emulator */
    fun resize(cols: Int, rows: Int) {
        if (masterFdVal > 0) {
            ProotBridge.resizePty(masterFdVal, cols, rows)
        }
        emulator.resize(cols, rows)
    }

    fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { pfd?.close() }
    }
}
