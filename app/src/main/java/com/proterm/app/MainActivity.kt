package com.proterm.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.proterm.app.databinding.ActivityMainBinding
import com.proterm.app.proot.ProotBridge
import com.proterm.app.terminal.TerminalEmulator
import com.proterm.app.terminal.TerminalSession
import com.proterm.app.terminal.gl.GLTerminalView

/**
 * 终端页面：接收 ContainerActivity 传来的 PTY master fd，
 * 渲染 OpenGL ES 终端界面。
 *
 * 主题使用 Theme.ProTerm.Terminal（深色，适配终端体验）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var session: TerminalSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val terminalView = binding.terminal as GLTerminalView

        // 从 intent 获取 fd（由 ContainerActivity 启动时传入）
        val fd = intent.getIntExtra("fd", -1)

        val emulator = TerminalEmulator(cols = 80, rows = 24) {}

        if (fd <= 0) {
            emulator.append(
                "错误：未收到有效的 PTY fd\n请从容器管理页启动容器后再进入终端\n"
                    .toByteArray()
            )
            val dummy = object : TerminalSession(0, emulator, {}) {
                override fun write(text: String) {}
                override fun writeByte(b: Int) {}
                override fun resize(cols: Int, rows: Int) {}
            }
            terminalView.setup(emulator, dummy)
            return
        }

        session = TerminalSession(
            masterFd = fd,
            emulator = emulator,
            onExit = {
                runOnUiThread {
                    emulator.append("\n\n[会话已结束，按返回键回到容器管理页]\n".toByteArray())
                }
            }
        )
        terminalView.setup(emulator, session!!)
    }

    override fun onPause() {
        binding.terminal.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.terminal.onResume()
    }

    override fun onDestroy() {
        // 注意：不要在这里 close session，因为 fd 归 ContainerActivity 管理
        session = null
        super.onDestroy()
    }
}
