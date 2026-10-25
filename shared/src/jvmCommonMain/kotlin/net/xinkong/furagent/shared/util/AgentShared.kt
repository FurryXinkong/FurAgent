package net.xinkong.furagent.shared.util

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.xinkong.furagent.shared.ThinkingLevel
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 桌面端 DesktopAgent 与手机端 Agent.kt 共用的 Agent 循环纯逻辑。
 *
 * 【为什么抽这里】
 * 这两端原本各自维护一份几乎逐字相同的实现（SSE 解析、applyDisableThinking、
 * toolSignature、token 估算、tool_search 结果解析、历史截断起点计算）。
 * 任何一处改动都要同步两处，极易漂移。统一到 shared/jvmCommonMain 后，
 * 两端只保留平台差异（回调签名、日志器、工具注册表）。
 *
 * 【不抽的部分】
 * - buildHistoryMessages：两端消息模型（DesktopMessage / ChatMessage）不同，
 *   且桌面端有 degradeToolResult 降级、手机端没有，差异较大，各自保留。
 * - 工具执行分发：两端 ToolRegistry / DesktopTools 接口不同，各自保留。
 */

// 思考开关（按 baseUrl 识别厂商）

/**
 * 按模型能力强制关闭思考。
 *
 * 仅当 supportsDisableThinking 为 true 时才加参数（由调用方判断后调用）。
 * 各厂商字段名不同，这里按 baseUrl 关键字分派。
 */
fun applyDisableThinking(body: JSONObject, baseUrl: String) {
    val url = baseUrl.lowercase(Locale.ROOT)
    try {
        when {
            url.contains("deepseek") -> body.put("thinking", JSONObject().put("type", "disabled"))
            url.contains("dashscope") -> body.put("enable_thinking", false)
            url.contains("moonshot") -> body.put("thinking", JSONObject().put("type", "disabled"))
            url.contains("bigmodel") || url.contains("z.ai") ->
                body.put("thinking", JSONObject().put("type", "disabled"))
            url.contains("openai") -> body.put("reasoning_effort", "none")
            url.contains("localhost") || url.contains("11434") -> body.put("think", false)
            else -> {
                body.put("thinking", JSONObject().put("type", "disabled"))
                body.put("enable_thinking", false)
                body.put("reasoning_effort", "none")
            }
        }
    } catch (_: Exception) { }
}

/**
 * 按模型能力主动开启思考，并指定强度。
 *
 * 与 applyDisableThinking 对称：各厂商字段名不同，按 baseUrl 关键字分派。
 * 仅当 level != OFF 时由调用方调用。
 *
 * 【强度降级】
 * 各厂商支持程度不同，不支持的档位降级到最接近的可用值：
 *  - DeepSeek：low / high / max 全支持
 *  - OpenAI：low / medium / high（无 max，MAX 降为 high）
 *  - 通义/智谱/Moonshot/Ollama：只有开关，无强度（忽略 level）
 *
 * @param level 思考强度，OFF 时不应调用本函数
 */
fun applyEnableThinking(body: JSONObject, baseUrl: String, level: ThinkingLevel = ThinkingLevel.HIGH) {
    val url = baseUrl.lowercase(Locale.ROOT)
    // 统一映射到各厂商的 effort 取值
    val effort = when (level) {
        ThinkingLevel.OFF -> "none"
        ThinkingLevel.LOW -> "low"
        ThinkingLevel.HIGH -> "high"
        ThinkingLevel.MAX -> "max"
    }
    try {
        when {
            url.contains("deepseek") -> {
                body.put("thinking", JSONObject().put("type", "enabled"))
                body.put("reasoning_effort", effort)
            }
            url.contains("dashscope") -> body.put("enable_thinking", true)
            url.contains("moonshot") -> body.put("thinking", JSONObject().put("type", "enabled"))
            url.contains("bigmodel") || url.contains("z.ai") ->
                body.put("thinking", JSONObject().put("type", "enabled"))
            url.contains("openai") -> {
                // OpenAI 无 max，降为 high
                val openaiEffort = if (level == ThinkingLevel.MAX) "high" else effort
                body.put("reasoning_effort", openaiEffort)
            }
            url.contains("localhost") || url.contains("11434") -> body.put("think", true)
            else -> {
                body.put("thinking", JSONObject().put("type", "enabled"))
                body.put("enable_thinking", true)
                body.put("reasoning_effort", effort)
            }
        }
    } catch (_: Exception) { }
}

// tool_search 结果解析

/**
 * 从 tool_search 返回文本里解析出工具名列表。
 *
 * 返回格式（见 ToolCatalog.renderSearchResult）：
 *   找到 N 个工具：
 *   [{"name":"xxx",...},{"name":"yyy",...}]
 *
 * 从第一个 '[' 开始取，解析成 JSONArray，读每个元素的 name。
 */
fun parseToolNamesFromSearchResult(text: String): List<String> {
    val idx = text.indexOf('[')
    if (idx < 0) return emptyList()
    return try {
        val arr = JSONArray(text.substring(idx))
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optString("name", "")
            if (n.isNotBlank()) out.add(n)
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

// 失败重试签名

/**
 * 工具调用签名，用于"同一工具同一参数连续失败 N 次"计数。
 *
 * 【失败去重】签名只取"定位性"参数，忽略翻页/数量类参数。
 * 原因：模型重试时常常只改 limit/offset/max_results 这类无关参数，
 * 若把它们算进签名，每次签名都不同，失败计数永远清零，
 * 导致"同一工具反复失败"无法累计到 3 次中断，白白烧 token。
 */
fun toolSignature(name: String, args: JSONObject): String {
    val ignored = setOf("limit", "offset", "max_results", "max_length", "length", "timeout")
    val sortedKeys = args.keys().asSequence()
        .filter { it !in ignored }
        .sorted()
        .toList()
    val sb = StringBuilder(name)
    for (k in sortedKeys) {
        sb.append("|").append(k).append("=").append(args.optString(k, ""))
    }
    return sb.toString()
}

// Token 估算

/** 估算 messages 数组的 prompt token 数（含图片按 PER_IMAGE_TOKENS 计）。 */
fun estimateMessagesTokens(messages: JSONArray): Int {
    val sb = StringBuilder()
    var imageCount = 0
    for (i in 0 until messages.length()) {
        val m = messages.optJSONObject(i) ?: continue
        when (val c = m.opt("content")) {
            is String -> sb.append(c)
            is JSONArray -> {
                for (j in 0 until c.length()) {
                    val item = c.optJSONObject(j) ?: continue
                    when (item.optString("type", "")) {
                        "text" -> sb.append(item.optString("text", ""))
                        "image_url" -> imageCount++
                    }
                }
            }
        }
        m.optJSONArray("tool_calls")?.let { tcs ->
            for (j in 0 until tcs.length()) {
                val fn = tcs.optJSONObject(j)?.optJSONObject("function") ?: continue
                sb.append(fn.optString("name", ""))
                sb.append(fn.optString("arguments", ""))
            }
        }
    }
    return TokenEstimator.estimate(sb.toString()) +
            imageCount * TokenEstimator.PER_IMAGE_TOKENS
}

/** 估算本轮 completion token 数（正文 + 所有 tool_call 的 name/arguments）。 */
fun estimateCompletionTokens(
    content: CharSequence,
    toolCalls: Map<Int, ToolCallAccumulator>
): Int {
    val sb = StringBuilder(content)
    toolCalls.values.forEach { acc ->
        sb.append(acc.name)
        sb.append(acc.arguments)
    }
    return TokenEstimator.estimate(sb.toString())
}

// 流式 tool_call 累加器

/**
 * SSE 流式响应里，一个 tool_call 会分多个 chunk 到达。
 * 按 index 聚合 id / name / arguments。
 */
class ToolCallAccumulator {
    var id: String = ""
    var name: String = ""
    val arguments = StringBuilder()
}

// SSE 解析

/**
 * 解析 OpenAI 兼容的 SSE 流。
 *
 * 逐行读 `data:` 行，解析 delta：
 *   - content 非空 → onContent(content)
 *   - tool_calls → 按 index 聚合进返回的 Map
 *
 * 每行解析前检查协程取消（用户点"停止"后立即中断）。
 * 单行 JSON 解析失败不中断整个流，交给 onParseError 记录后跳过。
 *
 * 用 readLine 回调而非直接吃 BufferedSource，避免 shared 层耦合 okio 类型，
 * 两端各自传 `{ source.readUtf8Line() }` 即可。
 *
 * @return 聚合后的 tool_calls（按 index 排序由调用方处理）
 */
suspend fun parseSseStream(
    readLine: () -> String?,
    onContent: (String) -> Unit,
    onReasoning: (String) -> Unit = {},
    onParseError: (String, String) -> Unit = { _, _ -> }
): MutableMap<Int, ToolCallAccumulator> {
    val toolCalls = mutableMapOf<Int, ToolCallAccumulator>()
    while (true) {
        currentCoroutineContext().ensureActive()
        val line = readLine() ?: break
        if (!line.startsWith("data:")) continue
        val data = line.substring(5).trim()
        if (data.isEmpty() || data == "[DONE]") continue
        val chunk = try {
            JSONObject(data)
        } catch (e: Exception) {
            onParseError(e.javaClass.simpleName, data)
            continue
        }
        val choices = chunk.optJSONArray("choices") ?: continue
        if (choices.length() == 0) continue
        val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: continue

        // 【坑】org.json 的 optString(key, default) 在字段存在但值为 JSON null 时，
        // 返回的是字符串 "null" 而不是 default（内部是 object.toString()）。
        // DeepSeek 官方 API 的流式 chunk 会显式返回 content: null / reasoning_content: null，
        // 直接 optString 会把 "null" 拼进正文和思考面板，表现为满屏 nullnullnull。
        // 火山引擎等兼容层直接省略字段，所以不会触发。
        // 修法：先 isNull() 判断，再取字符串。
        val content = if (delta.isNull("content")) "" else delta.optString("content", "")
        if (content.isNotEmpty()) onContent(content)

        // 【深度思考】兼容三种字段名：reasoning_content（DeepSeek 系）/ reasoning（部分中转）/ thinking。
        // 只用于实时展示，不持久化、不回传。
        val reasoning = when {
            !delta.isNull("reasoning_content") -> delta.optString("reasoning_content", "")
            !delta.isNull("reasoning") -> delta.optString("reasoning", "")
            !delta.isNull("thinking") -> delta.optString("thinking", "")
            else -> ""
        }
        if (reasoning.isNotEmpty()) onReasoning(reasoning)

        delta.optJSONArray("tool_calls")?.let { tcs ->
            for (i in 0 until tcs.length()) {
                val tc = tcs.optJSONObject(i) ?: continue
                val idx = tc.optInt("index", 0)
                val acc = toolCalls.getOrPut(idx) { ToolCallAccumulator() }
                tc.optString("id", "").takeIf { it.isNotEmpty() && it != "null" }?.let { acc.id = it }
                tc.optJSONObject("function")?.let { fn ->
                    fn.optString("name", "").takeIf { it.isNotEmpty() && it != "null" }?.let { acc.name = it }
                    fn.optString("arguments", "").takeIf { it.isNotEmpty() && it != "null" }?.let { acc.arguments.append(it) }
                }
            }
        }
    }
    return toolCalls
}

// 历史截断起点

/**
 * 计算历史截断的起始下标（按"完整轮次"截断，绝不从一轮中间切开）。
 *
 * 两端消息模型不同，故用 role/type 提取器解耦：
 *   - roleOf(msg) 返回 "user" / "assistant" / "tool"
 *   - typeOf(msg) 返回 "text" / "tool_call" / "tool_result" / "system_note"
 *
 * 规则：
 *   1. 保留最近 maxTurns 个 user 轮次
 *   2. 至少保留 minKeep 条消息（防止极端情况清空上下文）
 *   3. 起点对齐到 user 消息边界
 *
 * @return 起始下标（0 表示不截断）
 */
fun <T> computeTruncateStart(
    history: List<T>,
    maxTurns: Int,
    minKeep: Int,
    roleOf: (T) -> String,
    typeOf: (T) -> String
): Int {
    if (history.isEmpty()) return 0
    if (maxTurns <= 0) return 0

    val userIndices = history.indices.filter { i ->
        val m = history[i]
        roleOf(m) == "user" && typeOf(m) != "tool_call" && typeOf(m) != "tool_result"
    }

    var startIdx = 0
    if (userIndices.size > maxTurns) {
        startIdx = userIndices[userIndices.size - maxTurns]
    }

    val minStart = (history.size - minKeep).coerceAtLeast(0)
    if (startIdx > minStart) startIdx = minStart

    startIdx = alignToTurnBoundary(userIndices, startIdx, history.size)

    return if (startIdx <= 0) 0 else startIdx
}

/** 把截断起点对齐到最近的 user 消息边界。 */
private fun alignToTurnBoundary(userIndices: List<Int>, startIdx: Int, size: Int): Int {
    if (startIdx <= 0 || startIdx >= size) return startIdx.coerceAtLeast(0)
    if (startIdx in userIndices) return startIdx
    userIndices.lastOrNull { it < startIdx }?.let { return it }
    userIndices.firstOrNull { it > startIdx }?.let { return it }
    return 0
}

// Token 预算：工具过程降级

/** 工具过程预算裁剪的结果。 */
data class ToolBudgetResult<T>(
    /** 应当保留（发给模型）的消息 */
    val kept: List<T>,
    /** 被裁掉的工具条目数量（工具调用 + 工具结果） */
    val droppedToolEntries: Int,
    /** 裁剪前估算 token */
    val tokensBefore: Int,
    /** 裁剪后估算 token */
    val tokensAfter: Int
) {
    val trimmed: Boolean get() = droppedToolEntries > 0
}

/**
 * 按 token 预算裁剪历史里的「工具过程」。
 *
 * 【为什么需要】
 * 只按轮次数截断时，用户消息很少、工具结果很大的会话会失控：
 * 一个轮次里连着读十个大文件，光工具输出就能吃掉几万 token，
 * 而 maxTurns 完全拦不住。结果是“没聊几句就把上下文塞满了”。
 *
 * 【规则】
 * 估算整段历史的 token。若超出 [budgetTokens]，则**从最旧的一段工具过程开始丢**，
 * 直到落回预算内或工具过程丢完：
 *   - 用户消息（human）和助手文字回复**不丢**：它们决定“之前聊了什么、改了什么”，
 *     是保留上下文意义的核心，代价也小。
 *   - 工具调用与工具结果（tool_call / tool_result / system_note）按「批次」整批丢，
 *     避免留下有 tool_call 无 result 的残缺配对（那会让模型以为工具失败）。
 *   - 「批次」= 连续的一段工具条目。并行工具的 call 和错序到达的 result
 *     自然会被归到同一批（它们之间没有人类/助手消息隔着）。
 *   - 从旧到新丢，越新的工具过程越可能是当前正在推进的任务，必须留着。
 *
 * 被丢掉的位置会插入一条极短的提示，告诉模型“这里发生过 N 次工具调用，
 * 细节为节省上下文已省略”，而不是留一个莫名其妙的空洞。
 *
 * @param entryTokens 逐条估算每条消息的 token（由调用方提供，因为两端消息模型不同）
 * @param isToolEntry 判断是否属于“可裁的工具过程”
 * @param isTurnStart   判断是否是人类消息（用于不跨越它去丢东西）
 * @param makeElidedNote 生成“已省略 N 次工具调用”的占位消息
 */
fun <T> trimToolProcessByBudget(
    history: List<T>,
    budgetTokens: Int,
    entryTokens: (T) -> Int,
    isToolEntry: (T) -> Boolean,
    makeElidedNote: (droppedCount: Int) -> T
): ToolBudgetResult<T> {
    val perEntry = history.map(entryTokens)
    var total = perEntry.sum()
    val tokensBefore = total

    if (budgetTokens <= 0 || total <= budgetTokens) {
        return ToolBudgetResult(history, 0, tokensBefore, total)
    }

    // 1) 把工具条目切成批次：连续的工具条目算一批
    data class Batch(val from: Int, val to: Int, val tokens: Int)
    val batches = mutableListOf<Batch>()
    var i = 0
    while (i < history.size) {
        if (!isToolEntry(history[i])) {
            i++
            continue
        }
        val from = i
        var sum = 0
        while (i < history.size && isToolEntry(history[i])) {
            sum += perEntry[i]
            i++
        }
        batches.add(Batch(from, i - 1, sum))
    }

    // 2) 从最旧的批次开始丢，直到回落到预算内
    val dropped = BooleanArray(history.size)
    var droppedToolEntries = 0
    var droppedCount = 0
    for (b in batches) {
        if (total <= budgetTokens) break
        // 整批丢。丢完工具过程还不够低于预算时，剩下的只能靠轮次截断，这里不越权。
        for (k in b.from..b.to) {
            dropped[k] = true
            droppedToolEntries++
        }
        droppedCount++
        total -= b.tokens
    }

    if (droppedToolEntries == 0) {
        return ToolBudgetResult(history, 0, tokensBefore, total)
    }

    // 3) 重建：被丢的连续区间合并成一条「已省略」提示
    val kept = mutableListOf<T>()
    i = 0
    while (i < history.size) {
        if (!dropped[i]) {
            kept.add(history[i])
            i++
            continue
        }
        var j = i
        while (j < history.size && dropped[j]) j++
        kept.add(makeElidedNote(j - i))
        i = j
    }

    return ToolBudgetResult(kept, droppedToolEntries, tokensBefore, total)
}
