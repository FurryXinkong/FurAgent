package net.xinkong.furagent.shared.data

/**
 * 一次会话（双端共用）。
 *
 * 【冗余清理 Batch 9】Android 的 Session 和桌面的 DesktopSession 字段完全一致，
 * 只是类名不同（Desktop 前缀没有信息量）。现合并到 shared。
 */
data class Session(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessage>
)

/**
 * 会话索引条目（不含 messages，用于列表页）。
 */
data class SessionMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int
)
