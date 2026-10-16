package net.xinkong.furagent.shared.util

import net.xinkong.furagent.shared.data.ChatMessage

/**
 * 聊天列表项（双端共用）。
 *
 * 【冗余清理 Batch 10】Android 的 ChatUI.kt 和桌面的 ChatPage.kt 各有一份
 * 逐字相同的 ChatListItem 定义和 buildChatItems 函数。现统一到这里。
 *
 * 两端的差异只有一处：桌面的 ToolsGroup 有 durationMs 字段（工具耗时总和），
 * Android 没有。这里取并集，durationMs 默认 0L。
 */
sealed interface ChatListItem {
    val key: String

    data class Single(val msg: ChatMessage) : ChatListItem {
        override val key: String get() = msg.id
    }

    data class ToolsGroup(
        val groupKey: String,
        val items: List<ChatMessage>,
        val toolCallCount: Int,
        val toolResultCount: Int,
        /** 桌面端使用（工具耗时总和）；Android 端不读，恒为 0 */
        val durationMs: Long = 0L
    ) : ChatListItem {
        override val key: String get() = groupKey
    }
}

/**
 * 把连续的工具调用 / 返回消息折叠成 ToolsGroup。
 *
 * 非工具消息（text / system_note）作为 Single 独立成项。
 * 连续的 tool_call / tool_result 会被合并到同一个 ToolsGroup 里，
 * 直到遇到非工具消息。
 */
fun buildChatItems(messages: List<ChatMessage>): List<ChatListItem> {
    val out = mutableListOf<ChatListItem>()
    val pending = mutableListOf<ChatMessage>()

    fun flush() {
        if (pending.isEmpty()) return
        val callCount = pending.count { it.type == "tool_call" }
        val resultCount = pending.count { it.type == "tool_result" }
        val totalDuration = pending.sumOf {
            if (it.type == "tool_result") it.durationMs else 0L
        }
        val key = "grp_${pending.first().id}"
        out.add(
            ChatListItem.ToolsGroup(
                groupKey = key,
                items = pending.toList(),
                toolCallCount = callCount,
                toolResultCount = resultCount,
                durationMs = totalDuration
            )
        )
        pending.clear()
    }

    for (msg in messages) {
        when (msg.type) {
            "tool_call", "tool_result" -> pending.add(msg)
            else -> {
                flush()
                out.add(ChatListItem.Single(msg))
            }
        }
    }
    flush()
    return out
}
