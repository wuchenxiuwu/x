package com.proterm.app

/**
 * 容器数据模型
 * @param rootfsName rootfs 来源标识，用于显示（如 alpine-minirootfs）
 */
data class ContainerInfo(
    val id: String,
    val name: String,
    val image: String,
    val shell: String,
    val status: ContainerStatus,
    val rootfsName: String = image
)

enum class ContainerStatus {
    RUNNING,    // 运行中
    STOPPED,    // 已停止
    CREATING    // 创建中
}