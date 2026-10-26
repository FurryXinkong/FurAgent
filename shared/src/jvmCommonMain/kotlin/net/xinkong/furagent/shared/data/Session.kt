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
    val systemPrompt: String? = null,
    /**
     * 【P0-4】标题是否被用户手动锁定（重命名后为 true）。
     *
     * persist 自动生成标题时会检查此字段：true 则保留用户设的标题，
     * 不再用 generateTitle 覆盖。三态语义与 systemPrompt 一致：
     *   - null  = 未携带（save 内部读磁盘原值兜底）
     *   - false = 明确未锁
     *   - true  = 已锁，persist 保留原标题
     */
    val titleLocked: Boolean? = null
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
