package net.xinkong.furagent.shared.data

import java.util.*

/**
 * 会话中的一条消息（双端共用）。
 *
 * 【冗余清理 Batch 8】Android 的 ChatMessage 和桌面的 DesktopMessage 字段几乎一致，
 * 只有两处差异：
 *   - Android 有 isStreaming（UI 层流式标记，不序列化到磁盘）
 *   - 桌面有 durationMs（工具执行耗时，序列化时 durationMs>0 才写）
 *
 * 现合并到 shared，字段取并集。两端各留 typealias 让既有 import 继续有效，
 * 下一批再清理 typealias。
 *
 * 【缓存关键】toolCallId 必须保留 API 返回的**原始 tool_call_id**，
 * 不能每次重建历史时重算。否则跨请求 tool_call_id 不一致，
 * DeepSeek/OpenAI 兼容接口的前缀缓存会从这条 assistant 消息开始全部失效。
 * 后端（AgentRunner / Agent / DesktopAgent）会把这个字段原样送回 messages 数组。
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val type: String = "text",
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * 工具调用 ID。仅 type == "tool_call" / "tool_result" 时有意义。
     */
    val toolCallId: String = "",
    val interrupted: Boolean = false,
    val imagePaths: List<String> = emptyList(),
    /**
     * 工具执行耗时（毫秒），仅 tool_result 类型会填。
     * 桌面端使用；Android 端始终为 0（不序列化）。
     */
    val durationMs: Long = 0L,
    /**
     * 流式标记。仅 Android 端 UI 层使用（不序列化到磁盘）。
     * 桌面端的流式状态由 UI 层的独立参数传递，不用此字段。
     */
    val isStreaming: Boolean = false,
    /**
     * 模型返回的深度思考内容（reasoning_content / reasoning / thinking）。
     *
     * 【重要】只用于本地展示与持久化，**绝不回传给 AI**：
     * buildHistoryMessages 构建 API 请求时不读此字段。
     * 仅 assistant 消息会填。
     */
    val reasoning: String = "",
    /**
     * 并行批次标记。同一轮里并行执行的一批工具调用/返回共享同一个值。
     *
     * 【为什么需要】UI 上原来分不出哪些工具是并行跑的：
     * 一批并发执行的工具，结果按完成顺序陆续到达，看起来和串行一模一样。
     * 有了这个标记，工作过程里可以把同批条目收进一个「并行 N」分组里展示。
     *
     * 空字符串表示串行（不在并行批次里）。仅 tool_call / tool_result 会填。
     */
    val parallelGroup: String = ""
)
