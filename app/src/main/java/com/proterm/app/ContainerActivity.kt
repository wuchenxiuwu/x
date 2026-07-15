package com.proterm.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.proterm.app.databinding.ActivityContainerBinding
import com.proterm.app.terminal.TerminalEmulator
import com.proterm.app.terminal.TerminalSession

/**
 * 容器管理首页 — Material 3 Expressive 设计。
 *
 * 功能：
 *  - 显示容器列表（当前仅支持一个 Alpine Linux 容器）
 *  - 开启/关闭容器
 *  - 点击进入终端页面
 *  - 首次启动自动创建默认容器
 */
class ContainerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContainerBinding
    private val adapter = ContainerAdapter()

    /** 当前活跃的会话，null 表示容器未启动 */
    private var activeSession: TerminalSession? = null
    private var activeFd: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContainerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupFab()
        refreshContainerList()
    }

    private fun setupRecyclerView() {
        binding.containerRecycler.layoutManager = LinearLayoutManager(this)
        binding.containerRecycler.adapter = adapter

        adapter.onToggleClick = { container ->
            when (container.status) {
                ContainerStatus.STOPPED -> startContainer(container)
                ContainerStatus.RUNNING -> stopContainer(container)
                else -> { /* creating, ignore */ }
            }
        }

        adapter.onTerminalClick = { container ->
            if (container.status == ContainerStatus.RUNNING && activeFd > 0) {
                openTerminal()
            } else {
                showSnackbar("请先开启容器")
            }
        }

        adapter.onCardClick = { container ->
            if (container.status == ContainerStatus.RUNNING && activeFd > 0) {
                openTerminal()
            }
        }
    }

    private fun setupFab() {
        binding.fabAdd.setOnClickListener {
            // 当前版本仅支持单容器，点击提示
            showSnackbar("当前仅支持单个 Alpine 容器")
        }
    }

    private fun refreshContainerList() {
        val containers = loadContainers()
        adapter.submitList(containers)

        binding.emptyState.visibility =
            if (containers.isEmpty()) View.VISIBLE else View.GONE
        binding.containerRecycler.visibility =
            if (containers.isEmpty()) View.GONE else View.VISIBLE
    }

    /** 从 SharedPreferences 加载容器配置（当前只有一个默认容器） */
    private fun loadContainers(): List<ContainerInfo> {
        val prefs = getSharedPreferences("proterm_containers", MODE_PRIVATE)
        val hasContainer = prefs.getBoolean("has_default", false)

        return if (hasContainer) {
            val isRunning = activeSession != null
            listOf(
                ContainerInfo(
                    id = "default",
                    name = getString(R.string.default_container_name),
                    image = getString(R.string.default_container_image),
                    shell = getString(R.string.default_container_shell),
                    status = if (isRunning) ContainerStatus.RUNNING else ContainerStatus.STOPPED
                )
            )
        } else {
            // 首次启动，自动创建默认容器
            createDefaultContainer()
            loadContainers()
        }
    }

    private fun createDefaultContainer() {
        getSharedPreferences("proterm_containers", MODE_PRIVATE)
            .edit()
            .putBoolean("has_default", true)
            .apply()
        showSnackbar("默认容器已创建")
    }

    private fun startContainer(container: ContainerInfo) {
        // 更新 UI 为 "创建中"
        updateContainerStatus(container.id, ContainerStatus.CREATING)

        Thread {
            try {
                val manager = com.proterm.app.proot.ProotManager()
                val fd = manager.startSession(this, container.shell)
                runOnUiThread {
                    if (fd > 0) {
                        activeFd = fd
                        val emulator = TerminalEmulator(cols = 80, rows = 24) {}
                        activeSession = TerminalSession(
                            masterFd = fd,
                            emulator = emulator,
                            onExit = {
                                runOnUiThread {
                                    activeSession = null
                                    activeFd = -1
                                    refreshContainerList()
                                    showSnackbar("容器已退出")
                                }
                            }
                        )
                        updateContainerStatus(container.id, ContainerStatus.RUNNING)
                        showSnackbar("容器已启动")
                    } else {
                        updateContainerStatus(container.id, ContainerStatus.STOPPED)
                        showSnackbar("启动失败，请检查 assets 中的 proot 和 rootfs")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateContainerStatus(container.id, ContainerStatus.STOPPED)
                    showSnackbar("启动失败: ${e.message}")
                }
            }
        }.start()
    }

    private fun stopContainer(container: ContainerInfo) {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_stop_title)
            .setMessage(R.string.confirm_stop_message)
            .setPositiveButton(R.string.dialog_yes) { _, _ ->
                activeSession?.close()
                activeSession = null
                activeFd = -1
                updateContainerStatus(container.id, ContainerStatus.STOPPED)
                showSnackbar("容器已关闭")
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun updateContainerStatus(id: String, status: ContainerStatus) {
        val newList = adapter.currentList.map {
            if (it.id == id) it.copy(status = status) else it
        }
        adapter.submitList(newList)
    }

    private fun openTerminal() {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("fd", activeFd)
        }
        startActivity(intent)
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        activeSession?.close()
        activeSession = null
        super.onDestroy()
    }
}
