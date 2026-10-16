package net.xinkong.furagent.shared.tools

/**
 * 工具元数据。
 *
 * owner 用于区分归属：
 *   "common"  通用工具（Android + Desktop 都能用）
 *   "android" Android 专有
 *   "desktop" Desktop 专有
 */
data class ToolSpec(
    val name: String,
    val displayName: String,
    val description: String,
    val paramsSchema: String,
    val owner: String = "common"
)