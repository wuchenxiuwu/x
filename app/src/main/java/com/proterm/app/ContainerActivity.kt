package com.proterm.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.proterm.app.databinding.ActivityContainerBinding
import com.proterm.app.databinding.DialogCreateContainerBinding
import com.proterm.app.proot.ProotManager
import com.proterm.app.terminal.TerminalEmulator
import com.proterm.app.terminal.TerminalSession
import org.json.JSONArray
import org.json.JSONObject

/**
 * 容器管理首页 — Material 3 Expressive 设计。
 *
 * 功能：
 *  - 启动时申请存储权限
 *  - 支持多容器：每个容器独立 rootfs，proot 全局复用
 *  - FAB 弹出创建容器弹窗：输入名称 + 选择 proot + 选择 rootfs
 *  - proot 已存在时自动跳过选择，仅需导入 rootfs
 */
class ContainerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContainerBinding
    private val adapter = ContainerAdapter()

    /** 各容器的活跃会话 */
    private val sessions = mutableMapOf<String, TerminalSession>()
    private val fds = mutableMapOf<String, Int>()

    private val prefs by lazy { getSharedPreferences("proterm_containers", MODE_PRIVATE) }
    private var containers = mutableListOf<ContainerInfo>()

    // ── 权限 ───────────────────────────────────────────────

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { !it }) {
            showSnackbar(getString(R.string.permission_denied))
        }
    }

    private fun checkAndRequestPermission() {
        val permissions = when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
            Build.VERSION.SDK_INT >= 23 -> arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
            else -> emptyArray()
        }

        val needRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needRequest.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.permission_title)
                .setMessage(R.string.permission_message)
                .setPositiveButton(R.string.dialog_yes) { _, _ ->
                    permissionLauncher.launch(needRequest.toTypedArray())
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    // ── 文件选择器 ─────────────────────────────────────────

    private var pendingProotAction: ((Uri) -> Unit)? = null
    private var pendingRootfsAction: ((Uri) -> Unit)? = null

    private val prootPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            pendingProotAction?.invoke(it)
        }
    }

    private val rootfsPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            pendingRootfsAction?.invoke(it)
        }
    }

    // ── 生命周期 ───────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContainerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAndRequestPermission()
        setupRecyclerView()
        setupFab()
        loadAndShowContainers()
    }

    // ── 初始化 ─────────────────────────────────────────────

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
            if (container.status == ContainerStatus.RUNNING && fds[container.id] != null) {
                openTerminal(container.id)
            } else {
                showSnackbar("请先开启容器")
            }
        }

        adapter.onCardClick = { container ->
            if (container.status == ContainerStatus.RUNNING && fds[container.id] != null) {
                openTerminal(container.id)
            }
        }
    }

    private fun setupFab() {
        binding.fabAdd.setOnClickListener {
            showCreateContainerDialog()
        }
    }

    // ── 容器数据持久化 ─────────────────────────────────────

    private fun loadContainers(): List<ContainerInfo> {
        val jsonStr = prefs.getString("containers", "[]") ?: "[]"
        val json = JSONArray(jsonStr)
        val list = mutableListOf<ContainerInfo>()
        for (i in 0 until json.length()) {
            val obj = json.getJSONObject(i)
            list.add(ContainerInfo(
                id = obj.getString("id"),
                name = obj.getString("name"),
                image = obj.getString("image"),
                shell = obj.getString("shell"),
                status = ContainerStatus.STOPPED,
                rootfsName = obj.optString("rootfsName", obj.getString("image"))
            ))
        }
        return list
    }

    private fun saveContainers(list: List<ContainerInfo>) {
        val json = JSONArray()
        for (c in list) {
            val obj = JSONObject()
            obj.put("id", c.id)
            obj.put("name", c.name)
            obj.put("image", c.image)
            obj.put("shell", c.shell)
            obj.put("rootfsName", c.rootfsName)
            json.put(obj)
        }
        prefs.edit().putString("containers", json.toString()).apply()
    }

    private fun loadAndShowContainers() {
        containers = loadContainers().toMutableList()
        if (containers.isEmpty()) {
            showEmptyState()
        } else {
            showContainerList()
        }
    }

    // ── UI 刷新 ────────────────────────────────────────────

    private fun showContainerList() {
        binding.emptyState.visibility = View.GONE
        binding.containerRecycler.visibility = View.VISIBLE
        adapter.submitList(containers.toList())
    }

    private fun showEmptyState() {
        binding.containerRecycler.visibility = View.GONE
        binding.emptyState.visibility = View.VISIBLE
    }

    // ── 创建容器弹窗 ───────────────────────────────────────

    private fun showCreateContainerDialog() {
        val dialogBinding = DialogCreateContainerBinding.inflate(LayoutInflater.from(this))
        val hasProot = ProotManager.hasProot(this)

        // 如果 proot 已存在，按钮显示"已就绪"并禁用
        if (hasProot) {
            dialogBinding.btnDialogProot.text = getString(R.string.btn_select_proot_exist)
            dialogBinding.btnDialogProot.isEnabled = false
        }

        var tempProotUri: Uri? = null
        var tempRootfsUri: Uri? = null

        dialogBinding.btnDialogProot.setOnClickListener {
            pendingProotAction = { uri ->
                tempProotUri = uri
                dialogBinding.btnDialogProot.text = getString(R.string.btn_select_proot_exist)
                dialogBinding.tvDialogStatus.visibility = View.GONE
            }
            prootPicker.launch(arrayOf("*/*"))
        }

        dialogBinding.btnDialogRootfs.setOnClickListener {
            pendingRootfsAction = { uri ->
                tempRootfsUri = uri
                dialogBinding.btnDialogRootfs.text = getString(R.string.btn_select_rootfs_done)
                dialogBinding.tvDialogStatus.visibility = View.GONE
            }
            rootfsPicker.launch(arrayOf("application/gzip", "application/x-gzip", "*/*"))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_create_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.btn_create_done, null)
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> }
            .create()

        dialog.setOnShowListener {
            val btnDone = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btnDone.setOnClickListener {
                val name = dialogBinding.etContainerName.text?.toString()?.trim() ?: ""

                when {
                    name.isEmpty() -> {
                        dialogBinding.tvDialogStatus.text = getString(R.string.create_error_name)
                        dialogBinding.tvDialogStatus.visibility = View.VISIBLE
                    }
                    !hasProot && tempProotUri == null -> {
                        dialogBinding.tvDialogStatus.text = getString(R.string.create_error_proot)
                        dialogBinding.tvDialogStatus.visibility = View.VISIBLE
                    }
                    tempRootfsUri == null -> {
                        dialogBinding.tvDialogStatus.text = getString(R.string.create_error_rootfs)
                        dialogBinding.tvDialogStatus.visibility = View.VISIBLE
                    }
                    else -> {
                        createContainer(name, tempProotUri, tempRootfsUri!!)
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun createContainer(name: String, prootUri: Uri?, rootfsUri: Uri) {
        val containerId = System.currentTimeMillis().toString()

        Thread {
            try {
                // 1. 导入 proot（如果尚未导入）
                if (prootUri != null && !ProotManager.hasProot(this)) {
                    ProotManager.importProot(this, prootUri)
                }

                // 2. 导入 rootfs 到容器目录
                runOnUiThread { showSnackbar("正在解压 rootfs…") }
                ProotManager.importRootfs(this, rootfsUri, containerId)

                // 3. 保存容器配置
                val container = ContainerInfo(
                    id = containerId,
                    name = name,
                    image = "custom-rootfs",
                    shell = "/bin/sh",
                    status = ContainerStatus.STOPPED,
                    rootfsName = rootfsUri.lastPathSegment ?: "custom-rootfs"
                )
                containers.add(container)
                saveContainers(containers)

                runOnUiThread {
                    showSnackbar(getString(R.string.create_success))
                    showContainerList()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showSnackbar(getString(R.string.import_error, e.message))
                }
            }
        }.start()
    }

    // ── 容器管理 ───────────────────────────────────────────

    private fun startContainer(container: ContainerInfo) {
        if (!ProotManager.hasProot(this)) {
            showSnackbar(getString(R.string.create_error_proot))
            return
        }
        if (!ProotManager.hasRootfs(this, container.id)) {
            showSnackbar("容器 rootfs 缺失，请重新创建")
            return
        }

        updateContainerStatus(container.id, ContainerStatus.CREATING)

        Thread {
            try {
                val rootfs = ProotManager.rootfsDir(this, container.id).absolutePath
                val fd = ProotManager.startSession(this, container.shell, rootfs)
                runOnUiThread {
                    if (fd > 0) {
                        fds[container.id] = fd
                        val emulator = TerminalEmulator(cols = 80, rows = 24) {}
                        sessions[container.id] = TerminalSession(
                            masterFd = fd,
                            emulator = emulator,
                            onExit = {
                                runOnUiThread {
                                    sessions.remove(container.id)
                                    fds.remove(container.id)
                                    updateContainerStatus(container.id, ContainerStatus.STOPPED)
                                    showSnackbar("${container.name} 已退出")
                                }
                            }
                        )
                        updateContainerStatus(container.id, ContainerStatus.RUNNING)
                        showSnackbar("${container.name} 已启动")
                    } else {
                        updateContainerStatus(container.id, ContainerStatus.STOPPED)
                        showSnackbar("${container.name} 启动失败")
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
            .setMessage("${container.name}：${getString(R.string.confirm_stop_message)}")
            .setPositiveButton(R.string.dialog_yes) { _, _ ->
                sessions[container.id]?.close()
                sessions.remove(container.id)
                fds.remove(container.id)
                updateContainerStatus(container.id, ContainerStatus.STOPPED)
                showSnackbar("${container.name} 已关闭")
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun updateContainerStatus(id: String, status: ContainerStatus) {
        val idx = containers.indexOfFirst { it.id == id }
        if (idx >= 0) {
            containers[idx] = containers[idx].copy(status = status)
            adapter.submitList(containers.toList())
        }
    }

    private fun openTerminal(containerId: String) {
        val fd = fds[containerId] ?: return
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("fd", fd)
        }
        startActivity(intent)
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        super.onDestroy()
    }
}
