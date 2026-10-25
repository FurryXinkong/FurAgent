package net.xinkong.furagent.shared.util

/**
 * 子会话结果卡片的解析（双端共用）。
 *
 * 几乎逐字相同的 SUB_SESSION 文本解析逻辑，现统一到这里。
 *
 * 消息格式（tool_result 的 content）：
 * ```
 * SUB_SESSION:sub_1234_abcd
 * 标题：xxx
 * 消息数：12
 * 子会话结果摘要：
 * ...摘要正文...
 * ```
 */
data class SubSessionInfo(
    val id: String,
    val title: String,
    val messageCount: String,
    val summary: String
) {
    companion object {
        const val PREFIX = "SUB_SESSION:"

        /** 判断一条 tool_result 是否是子会话返回。 */
        fun isSubSession(content: String): Boolean = content.startsWith(PREFIX)

        /**
         * 解析子会话返回文本。不是子会话格式（或 id 为空）时返回 null。
         */
        fun parse(content: String): SubSessionInfo? {
            if (!content.startsWith(PREFIX)) return null
            val lines = content.lines()
            val id = lines.getOrNull(0)?.removePrefix(PREFIX)?.trim().orEmpty()
            if (id.isBlank()) return null
            val title = lines.firstOrNull { it.startsWith("标题：") }
                ?.removePrefix("标题：")?.trim() ?: "子会话"
            val msgCount = lines.firstOrNull { it.startsWith("消息数：") }
                ?.removePrefix("消息数：")?.trim().orEmpty()
            val summary = content.substringAfter("子会话结果摘要：", "").trim()
            return SubSessionInfo(id, title, msgCount, summary)
        }
    }
}
