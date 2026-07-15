package com.proterm.app

/**
 * 容器数据模型
 */
data class ContainerInfo(
    val id: String,
    val name: String,
    val image: String,
    val shell: String,
    val status: ContainerStatus
)

enum class ContainerStatus {
    RUNNING,    // 运行中
    STOPPED,    // 已停止
    CREATING    // 创建中
}
