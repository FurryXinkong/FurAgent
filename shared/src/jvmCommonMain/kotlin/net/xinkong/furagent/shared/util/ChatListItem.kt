package net.xinkong.furagent.shared.util

import net.xinkong.furagent.shared.util.DateFormats
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

    /**
     * 连续工具调用 / 返回的折叠组。
     *
     * 用于「一轮 assistant 响应里没有中间文字回复」的场景：
     * 只有连续的工具调用，最后跟一条总结文字。
     */
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

    /**
     * 工作过程折叠组。
     *
     * 用于「一轮 assistant 响应里夹着中间文字回复」的场景：
     * 组内只保留工具调用与深度思考（思考条目在组内默认折叠）；中间文字回复
     * 与总结文字全部平铺在组外——每条 AI 消息都像最后一条那样独立展示。
     *
     * 例：
     * ```
     * U: 帮我完成 xxx
     * ┌─ 工作过程 · 2 次工具调用 ─┐   ← WorkProcess（默认展开）
     * │  toolcall / toolcall     │
     * │  （深度思考在此折叠）      │
     * └──────────────────────────┘
     * AI: 我先做 A                  ← Single（中间回复，组外）
     * AI: 我已完成所有任务           ← Single（最后一条文字，组外）
     * ```
     */
    data class WorkProcess(
        val groupKey: String,
        val items: List<ChatMessage>,
        val toolCallCount: Int,
        val toolResultCount: Int,
        /** 桌面端使用（工具耗时总和）；Android 端不读，恒为 0 */
        val durationMs: Long = 0L
    ) : ChatListItem {
        override val key: String get() = groupKey
    }

    /** 日期分隔线，跨天对话时插入 */
    data class DateSeparator(val displayText: String) : ChatListItem {
        override val key: String get() = "date_$displayText"
    }
}

/**
 * 把消息列表折叠成聊天列表项。
 *
 * 分组规则（按「轮次」划分，一轮 = 一条 user 消息之后到下一条 user 消息之前）：
 *
 * 1. user / system_note 消息 → 独立 [ChatListItem.Single]
 * 2. 一轮 assistant 响应：
 *    - 若中间夹着文字回复（工具调用之间出现 text）→ 除最后一条 text 外的
 *      所有内容折叠成 [ChatListItem.WorkProcess]，最后一条 text 独立成项
 *    - 若没有中间文字回复（只有连续工具调用 + 最后一条 text）→ 沿用
 *      [ChatListItem.ToolsGroup] 折叠连续工具调用，text 独立成项
 */
fun buildChatItems(messages: List<ChatMessage>): List<ChatListItem> {
    val out = mutableListOf<ChatListItem>()
    var i = 0
    var lastDayKey = ""
    while (i < messages.size) {
        val msg = messages[i]
        // 日期分隔线：跨天时插入
        val dayKey = DateFormats.dateFormatter()
            .format(java.util.Date(msg.timestamp))
        if (dayKey != lastDayKey) {
            lastDayKey = dayKey
            out.add(ChatListItem.DateSeparator(formatChatDate(msg.timestamp)))
        }
        // user / system_note 直接成项
        // agent_interject（定时任务 AI 主动说话的写回消息）同样是轮次边界：
        // 断开它与前一轮的合并，避免"前一轮的总结文字被当成中间回复吞进工作过程"
        if (msg.role == "user" || msg.type == "system_note" || msg.type == "agent_interject") {
            out.add(ChatListItem.Single(msg))
            i++
            continue
        }
        // 收集本轮 assistant 消息（直到下一个 user / system_note / agent_interject）
        val turn = mutableListOf<ChatMessage>()
        while (i < messages.size &&
            messages[i].role != "user" &&
            messages[i].type != "system_note" &&
            messages[i].type != "agent_interject"
        ) {
            turn.add(messages[i])
            i++
        }
        if (turn.isEmpty()) continue

        // 【体验修复】子会话返回（SUB_SESSION:）原先被单独提出来成卡，泡在聊天流里，
        // 现在留在折叠组内，由 UI 渲染成一行可点击入口（ChatPage WorkProcessItemList）。

        // 【工作过程】本轮除「最后一条文字」外的全部内容（思考 + 工具调用）
        // 都归入同一个工作过程面板，思考在组内折叠。
        // 这样一轮任务 = 一个面板，不会出现「思考」和「工作过程」交替平铺的割裂感。
        val lastTextIdx = turn.indexOfLast { it.type == "text" }
        val lastText = if (lastTextIdx >= 0) turn[lastTextIdx] else null
        var process = if (lastTextIdx >= 0) {
            turn.filterIndexed { i, _ -> i != lastTextIdx }
        } else turn

        // 【思考归位】最后一条文字身上也可能挂着 reasoning（没工具调用、
        // 只有一脸盲干的回复就是这种）。以前它被流派平铺在聊天流里，
        // 与工作过程里的思考长两个样。现在一律收进工作过程面板。
        if (lastText != null && lastText.reasoning.isNotBlank() &&
            process.none { it.type == "reasoning" }
        ) {
            process = process + ChatMessage(
                role = "assistant",
                content = lastText.reasoning,
                type = "reasoning",
                timestamp = lastText.timestamp
            )
        }

        if (process.isNotEmpty()) {
            val hasMiddleText = process.any { it.type == "text" }
            val hasReasoning = process.any { it.type == "reasoning" }
            if (hasMiddleText || hasReasoning) {
                // 工作过程只留思考 + 工具调用；中间文字回复平铺在组外
                val inGroup = process.filter { it.type != "text" }
                val outsideTexts = process.filter { it.type == "text" }
                if (inGroup.isNotEmpty()) out.add(makeWorkProcess(inGroup))
                outsideTexts.forEach { out.add(ChatListItem.Single(it)) }
            } else {
                appendWithToolsGroup(out, process)
            }
        }
        if (lastText != null) out.add(ChatListItem.Single(lastText))
    }
    return out
}

/** 把一轮 assistant 消息按「连续工具调用折叠成 ToolsGroup」的方式追加到 out。 */
private fun appendWithToolsGroup(out: MutableList<ChatListItem>, turn: List<ChatMessage>) {
    val pending = mutableListOf<ChatMessage>()

    fun flush() {
        if (pending.isEmpty()) return
        out.add(makeToolsGroup(pending))
        pending.clear()
    }

    for (msg in turn) {
        when (msg.type) {
            "tool_call", "tool_result" -> pending.add(msg)
            else -> {
                flush()
                out.add(ChatListItem.Single(msg))
            }
        }
    }
    flush()
}

private fun makeToolsGroup(items: List<ChatMessage>): ChatListItem.ToolsGroup {
    val callCount = items.count { it.type == "tool_call" }
    val resultCount = items.count { it.type == "tool_result" }
    val totalDuration = items.sumOf { if (it.type == "tool_result") it.durationMs else 0L }
    return ChatListItem.ToolsGroup(
        groupKey = "grp_${items.first().id}",
        items = items.toList(),
        toolCallCount = callCount,
        toolResultCount = resultCount,
        durationMs = totalDuration
    )
}

private fun formatChatDate(timestamp: Long): String {
    val sdf = DateFormats.dateFormatter()
    val now = System.currentTimeMillis()
    val today = sdf.format(java.util.Date(now))
    val yesterday = sdf.format(java.util.Date(now - 86_400_000L))
    val dateStr = sdf.format(java.util.Date(timestamp))
    return when (dateStr) {
        today -> "今天"
        yesterday -> "昨天"
        else -> java.text.SimpleDateFormat("M月d日", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
    }
}

private fun makeWorkProcess(items: List<ChatMessage>): ChatListItem.WorkProcess {
    val callCount = items.count { it.type == "tool_call" }
    val resultCount = items.count { it.type == "tool_result" }
    val totalDuration = items.sumOf { if (it.type == "tool_result") it.durationMs else 0L }
    return ChatListItem.WorkProcess(
        groupKey = "wp_${items.first().id}",
        items = items.toList(),
        toolCallCount = callCount,
        toolResultCount = resultCount,
        durationMs = totalDuration
    )
}

// 工作过程条目配对（双端 UI 共用，避免各自内联实现）

/**
 * 工作过程渲染条目：tool_call 与配对的 tool_result 合并成一条，
 * 其余消息（reasoning / text / 孤儿 tool_result）独立成条。
 *
 * @property main 条目主消息
 * @property result 与 [main] 配对的 tool_result（仅 tool_call 条目可能非空）
 * @property status 工具执行状态：true 成功 / false 失败 / null 非工具或结果未返回
 */
data class WorkProcessEntry(
    val main: ChatMessage,
    val result: ChatMessage?,
    val status: Boolean?
)

/**
 * 把工作过程组的原始消息列表配对成渲染条目序列。
 *
 * - tool_call 按 toolCallId 找到对应 tool_result 合并，并预计算失败状态；
 * - 已配对的 tool_result 不再单独出现；
 * - 一次遍历内完成，复杂度 O(n)。
 */
fun buildWorkProcessEntries(items: List<ChatMessage>): List<WorkProcessEntry> {
    val out = mutableListOf<WorkProcessEntry>()
    val consumed = mutableSetOf<String>()
    items.forEach { msg ->
        when (msg.type) {
            "tool_call" -> {
                val result = items.firstOrNull {
                    it.type == "tool_result" && it.toolCallId == msg.toolCallId
                }
                if (result != null) consumed.add(msg.toolCallId)
                val status = when {
                    result == null -> null
                    isToolFailure(result.content) -> false
                    else -> true
                }
                out.add(WorkProcessEntry(msg, result, status))
            }
            "tool_result" -> {
                if (msg.toolCallId in consumed) return@forEach
                consumed.add(msg.toolCallId)
                out.add(WorkProcessEntry(msg, null, null))
            }
            else -> out.add(WorkProcessEntry(msg, null, null))
        }
    }
    return out
}

/**
 * 并行批次渲染段：一段连续、parallelGroup 相同的条目。
 *
 * 串行条目自成一段（[parallelGroup] 为空，[isParallel] 为 false）；
 * 并行批次则把同批的多个工具调用收成一段，UI 在它前面画
 * 「并行 N 个」的标题，告诉用户这几个是真的一起跑的。
 */
data class WorkProcessRun(
    val parallelGroup: String,
    val entries: List<WorkProcessEntry>,
    val isParallel: Boolean
) {
    val key: String
        get() = if (isParallel) "run_p_$parallelGroup" else "run_s_${entries.first().main.id}"
}

/**
 * 把配对后的条目聚成渲染段：连续同 [ChatMessage.parallelGroup]（且非空）
 * 的条目归入同一段，其余各自一段。
 */
fun buildWorkProcessRuns(entries: List<WorkProcessEntry>): List<WorkProcessRun> {
    val out = mutableListOf<WorkProcessRun>()
    var i = 0
    while (i < entries.size) {
        val g = entries[i].main.parallelGroup
        if (g.isBlank()) {
            out.add(WorkProcessRun("", listOf(entries[i]), false))
            i++
            continue
        }
        val batch = mutableListOf(entries[i])
        var j = i + 1
        while (j < entries.size && entries[j].main.parallelGroup == g) {
            batch.add(entries[j])
            j++
        }
        out.add(WorkProcessRun(g, batch, true))
        i = j
    }
    return out
}
