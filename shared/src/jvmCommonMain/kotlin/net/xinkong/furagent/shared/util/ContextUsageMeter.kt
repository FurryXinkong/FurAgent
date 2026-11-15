package net.xinkong.furagent.shared.util

import net.xinkong.furagent.shared.ContextUsage
import net.xinkong.furagent.shared.ContextUsageStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 按「本轮请求实际会发出去的内容」统计上下文用量，口径对齐 WorkBuddy：
 *
 *  - 系统提示词：messages 里第一条 role=system
 *  - 工具及子智能体：常规工具定义（function schema）
 *  - 对话消息：其余 user / assistant / tool 消息
 *  - 连接器及 MCP：name 以 mcp__ 开头的工具定义
 *  - 技能：pytool_ 前缀与 skill 工具定义
 *
 * 双端共用：Android 与桌面都按 OpenAI 结构组 messages/tools，Anthropic 由
 * AnthropicCompat 转换，统计在转换前做，口径一致。
 */
object ContextUsageMeter {

    fun measure(
        messages: JSONArray,
        tools: JSONArray,
        budgetK: Int,
        sessionId: String
    ): ContextUsage {
        var system = 0
        var conversation = 0

        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val tokens = messageTokens(m)
            if (m.optString("role") == "system") system += tokens else conversation += tokens
        }

        var toolsTokens = 0
        var connectorTokens = 0
        var skillTokens = 0
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val name = toolName(t)
            val size = TokenEstimator.estimate(t.toString())
            when {
                name.startsWith("mcp__") -> connectorTokens += size
                name.startsWith("pytool_") || name == "skill" || name == "pytool" -> skillTokens += size
                else -> toolsTokens += size
            }
        }

        val compaction = ContextUsageStore.lastCompaction.value
        return ContextUsage(
            systemTokens = system,
            toolsTokens = toolsTokens,
            messagesTokens = conversation,
            connectorTokens = connectorTokens,
            skillTokens = skillTokens,
            budgetK = budgetK,
            compacted = compaction.compacted,
            compactedChars = compaction.savedChars,
            sessionId = sessionId
        )
    }

    private fun messageTokens(m: JSONObject): Int {
        var total = 0
        when (val c = m.opt("content")) {
            is String -> total += TokenEstimator.estimate(c)
            is JSONArray -> {
                for (j in 0 until c.length()) {
                    val item = c.optJSONObject(j) ?: continue
                    when (item.optString("type", "")) {
                        "text" -> total += TokenEstimator.estimate(item.optString("text", ""))
                        "image_url" -> total += TokenEstimator.PER_IMAGE_TOKENS
                    }
                }
            }
        }
        // tool_calls 的 name + arguments 也要算：并行调十个工具时这部分不小。
        m.optJSONArray("tool_calls")?.let { tcs ->
            for (j in 0 until tcs.length()) {
                val fn = tcs.optJSONObject(j)?.optJSONObject("function") ?: continue
                total += TokenEstimator.estimate(fn.optString("name", ""))
                total += TokenEstimator.estimate(fn.optString("arguments", ""))
            }
        }
        if (m.has("tool_call_id")) total += 8
        return total
    }

    /** 兼容 OpenAI（function.name）与简化结构（name）。 */
    private fun toolName(t: JSONObject): String =
        t.optJSONObject("function")?.optString("name") ?: t.optString("name")
}
