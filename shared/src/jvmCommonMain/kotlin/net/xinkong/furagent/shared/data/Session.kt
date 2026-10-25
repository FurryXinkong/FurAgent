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
    val messages: List<ChatMessage>,
    /** 母会话 id：子会话派生时记录，删除母会话时级联删除子会话；null=非子会话 */
    val parentId: String? = null,
    /**
     * 会话专用提示词：非空时注入该会话的系统提示词（追加在全局指令之后）。
     * null=未携带（persist 等场景重造对象时保留磁盘原值）；""=显式清空。
     */
    val systemPrompt: String? = null
)

/**
 * 会话索引条目（不含 messages，用于列表页）。
 */
data class SessionMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    /** 母会话 id：删除级联用；null=非子会话 */
    val parentId: String? = null
)
