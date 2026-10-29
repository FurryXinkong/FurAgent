package net.xinkong.furagent.shared.util

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/**
 * Anthropic Messages API 兼容层。
 *
 * 与 OpenAI Chat Completions 的差异：
 *
 * | 维度 | OpenAI | Anthropic |
 * |---|---|---|
 * | 端点 | /chat/completions | /v1/messages |
 * | 鉴权 | Authorization: Bearer | x-api-key + anthropic-version |
 * | system | messages 里 role=system | 顶层 system 字段 |
 * | 工具定义 | tools[].function.{name,description,parameters} | tools[].{name,description,input_schema} |
 * | 工具调用 | assistant.tool_calls | assistant.content[].type=tool_use |
 * | 工具结果 | role=tool + tool_call_id | user.content[].type=tool_result + tool_use_id |
 * | 图片 | content[].type=image_url | content[].type=image + source.base64 |
 * | 流式 | choices[0].delta | content_block_delta / message_delta |
 * | 结束 | finish_reason | stop_reason |
 *
 * 这里只做「转换」，不碰网络。调用方拿到转换后的 body 直接 POST。
 */
object AnthropicCompat {

    /** Anthropic 要求的 API 版本头。 */
    const val API_VERSION = "2023-06-01"

    /**
     * 把 OpenAI 风格的 tools 数组转成 Anthropic 的 tools 数组。
     *
     * OpenAI: {"type":"function","function":{"name":..,"description":..,"parameters":..}}
     * Anthropic: {"name":..,"description":..,"input_schema":..}
     */
    fun convertTools(openAiTools: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until openAiTools.length()) {
            val t = openAiTools.optJSONObject(i) ?: continue
            val fn = t.optJSONObject("function") ?: continue
            out.put(JSONObject().apply {
                put("name", fn.optString("name", ""))
                val desc = fn.optString("description", "")
                if (desc.isNotBlank()) put("description", desc)
                put("input_schema", fn.optJSONObject("parameters") ?: JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                })
            })
        }
        return out
    }

    /**
     * 把 OpenAI 风格的 messages 数组转成 Anthropic 的 messages + system。
     *
     * 关键差异：
     *  - system 消息要抽出来放顶层
     *  - assistant 的 tool_calls 要变成 content[].type=tool_use
     *  - role=tool 的消息要变成 role=user + content[].type=tool_result
     *  - 连续的 tool_result 必须合并进同一条 user 消息（Anthropic 要求）
     *
     * @return Pair(messages, systemPrompt)
     */
    fun convertMessages(openAiMessages: JSONArray): Pair<JSONArray, String> {
        val out = JSONArray()
        val systemParts = mutableListOf<String>()

        // 待合并的 tool_result 块（Anthropic 要求同一轮的多个 tool_result 在一条 user 消息里）
        // 注意：JSONArray 没有 clear()，落盘后必须重新赋值一个新数组，
        // 否则后续 put 会污染已经放进 out 的那条消息。
        var pendingToolResults = JSONArray()

        fun flushPending() {
            if (pendingToolResults.length() == 0) return
            out.put(JSONObject().apply {
                put("role", "user")
                put("content", pendingToolResults)
            })
            pendingToolResults = JSONArray()
        }

        for (i in 0 until openAiMessages.length()) {
            val m = openAiMessages.optJSONObject(i) ?: continue
            val role = m.optString("role", "")

            when (role) {
                "system" -> {
                    val c = m.optString("content", "")
                    if (c.isNotBlank()) systemParts.add(c)
                }

                "user" -> {
                    // 先落盘之前攒的 tool_result
                    flushPending()
                    out.put(JSONObject().apply {
                        put("role", "user")
                        put("content", convertUserContent(m))
                    })
                }

                "assistant" -> {
                    flushPending()
                    val blocks = convertAssistantContent(m)
                    // Anthropic 严格要求 user/assistant 交替。
                    // 上游历史里常见「assistant 正文」和「assistant tool_calls」是两条独立消息，
                    // 直接转出来就是连续两条 assistant，会 400。
                    // 这里检测到上一条也是 assistant 时，把新块合并进去。
                    val prev = if (out.length() > 0) out.optJSONObject(out.length() - 1) else null
                    if (prev != null && prev.optString("role", "") == "assistant") {
                        val prevContent = prev.optJSONArray("content")
                        if (prevContent != null) {
                            for (k in 0 until blocks.length()) {
                                val b = blocks.optJSONObject(k) ?: continue
                                prevContent.put(b)
                            }
                        }
                    } else {
                        out.put(JSONObject().apply {
                            put("role", "assistant")
                            put("content", blocks)
                        })
                    }
                }

                "tool" -> {
                    // OpenAI 的 tool 消息 → Anthropic 的 user + tool_result 块
                    val callId = m.optString("tool_call_id", "")
                    val content = m.optString("content", "")
                    pendingToolResults.put(JSONObject().apply {
                        put("type", "tool_result")
                        put("tool_use_id", callId)
                        put("content", content)
                        // 工具失败时显式标记，模型不用从文字里猜
                        if (isToolFailureText(content)) put("is_error", true)
                    })
                }
            }
        }

        // 收尾：还有没落盘的 tool_result
        flushPending()

        return out to systemParts.joinToString("\n\n")
    }

    /**
     * 判断工具结果文本是否表示失败。
     *
     * 与 AgentShared 的 isToolFailure 保持同一口径：
     * 以「失败」开头，或包含常见错误前缀。
     */
    private fun isToolFailureText(text: String): Boolean {
        val t = text.trimStart()
        if (t.startsWith("失败")) return true
        if (t.startsWith("执行异常")) return true
        if (t.startsWith("错误")) return true
        return false
    }
    /** 转换 user 消息的 content（支持纯文本和图文混合）。 */
    private fun convertUserContent(m: JSONObject): Any {
        val content = m.opt("content")
        // 纯字符串直接返回
        if (content is String) return content
        // 数组形式：OpenAI 的 image_url → Anthropic 的 image
        if (content is JSONArray) {
            val out = JSONArray()
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                when (part.optString("type", "")) {
                    "text" -> out.put(JSONObject().apply {
                        put("type", "text")
                        put("text", part.optString("text", ""))
                    })
                    "image_url" -> {
                        val url = part.optJSONObject("image_url")?.optString("url", "") ?: ""
                        // data:image/png;base64,xxxx
                        if (url.startsWith("data:")) {
                            val meta = url.substringAfter("data:").substringBefore(",")
                            val b64 = url.substringAfter(",", "")
                            val mediaType = meta.substringBefore(";").ifBlank { "image/png" }
                            out.put(JSONObject().apply {
                                put("type", "image")
                                put("source", JSONObject().apply {
                                    put("type", "base64")
                                    put("media_type", mediaType)
                                    put("data", b64)
                                })
                            })
                        }
                    }
                }
            }
            return out
        }
        return ""
    }

    /** 转换 assistant 消息的 content（文本 + tool_calls → tool_use 块）。 */
    private fun convertAssistantContent(m: JSONObject): JSONArray {
        val out = JSONArray()
        val text = m.optString("content", "")
        if (text.isNotBlank()) {
            out.put(JSONObject().apply {
                put("type", "text")
                put("text", text)
            })
        }
        m.optJSONArray("tool_calls")?.let { tcs ->
            for (i in 0 until tcs.length()) {
                val tc = tcs.optJSONObject(i) ?: continue
                val fn = tc.optJSONObject("function") ?: continue
                val argsStr = fn.optString("arguments", "{}")
                val input = try {
                    JSONObject(argsStr.ifBlank { "{}" })
                } catch (_: Exception) { JSONObject() }
                out.put(JSONObject().apply {
                    put("type", "tool_use")
                    put("id", tc.optString("id", ""))
                    put("name", fn.optString("name", ""))
                    put("input", input)
                })
            }
        }
        // Anthropic 要求 content 非空
        if (out.length() == 0) {
            out.put(JSONObject().apply {
                put("type", "text")
                put("text", "")
            })
        }
        return out
    }

    /**
     * 构造 Anthropic 请求体。
     *
     * @param model 模型名
     * @param openAiMessages OpenAI 风格的消息数组（内部会转换）
     * @param openAiTools OpenAI 风格的工具数组（内部会转换）
     * @param temperature 采样温度
     * @param maxTokens 最大输出 token（Anthropic 必填）
     * @param stream 是否流式
     */
    fun buildRequestBody(
        model: String,
        openAiMessages: JSONArray,
        openAiTools: JSONArray,
        temperature: Double,
        maxTokens: Int = 8192,
        stream: Boolean = true
    ): JSONObject {
        val (messages, system) = convertMessages(openAiMessages)
        return JSONObject().apply {
            put("model", model)
            put("messages", messages)
            if (system.isNotBlank()) put("system", system)
            if (openAiTools.length() > 0) put("tools", convertTools(openAiTools))
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put("stream", stream)
        }
    }

    /**
     * 解析 Anthropic 的 SSE 流。
     *
     * 事件类型：
     *   message_start        → 忽略（含 usage）
     *   content_block_start  → type=tool_use 时记录 id/name；type=text 时忽略
     *   content_block_delta  → delta.type=text_delta → onContent
     *                          delta.type=input_json_delta → 累积到对应 tool 的 arguments
     *                          delta.type=thinking_delta → onReasoning
     *   content_block_stop   → 忽略
     *   message_delta        → stop_reason
     *   message_stop         → 结束
     *
     * @return 聚合后的 tool_calls（index → accumulator）
     */
    suspend fun parseSseStream(
        readLine: () -> String?,
        onContent: (String) -> Unit,
        onReasoning: (String) -> Unit = {},
        onParseError: (String, String) -> Unit = { _, _ -> }
    ): MutableMap<Int, ToolCallAccumulator> {
        val toolCalls = mutableMapOf<Int, ToolCallAccumulator>()
        // Anthropic 用 content block index，需要映射到我们的 tool 序号
        val blockToTool = mutableMapOf<Int, Int>()
        var nextToolIdx = 0

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

            when (chunk.optString("type", "")) {
                "content_block_start" -> {
                    val idx = chunk.optInt("index", 0)
                    val block = chunk.optJSONObject("content_block") ?: continue
                    if (block.optString("type", "") == "tool_use") {
                        val toolIdx = nextToolIdx++
                        blockToTool[idx] = toolIdx
                        val acc = toolCalls.getOrPut(toolIdx) { ToolCallAccumulator() }
                        acc.id = block.optString("id", "")
                        acc.name = block.optString("name", "")
                        // input 可能已经带了初始值（通常是空对象）
                        val input = block.optJSONObject("input")
                        if (input != null && input.length() > 0) {
                            acc.arguments.append(input.toString())
                        }
                    }
                }

                "content_block_delta" -> {
                    val idx = chunk.optInt("index", 0)
                    val delta = chunk.optJSONObject("delta") ?: continue
                    when (delta.optString("type", "")) {
                        "text_delta" -> {
                            val t = delta.optString("text", "")
                            if (t.isNotEmpty()) onContent(t)
                        }
                        "thinking_delta" -> {
                            val t = delta.optString("thinking", "")
                            if (t.isNotEmpty()) onReasoning(t)
                        }
                        "input_json_delta" -> {
                            val toolIdx = blockToTool[idx] ?: continue
                            val acc = toolCalls.getOrPut(toolIdx) { ToolCallAccumulator() }
                            val partial = delta.optString("partial_json", "")
                            if (partial.isNotEmpty()) acc.arguments.append(partial)
                        }
                    }
                }

                "message_delta", "message_stop", "message_start", "content_block_stop", "ping" -> {
                    // 不需要处理
                }
            }
        }
        return toolCalls
    }

    /**
     * 从 Anthropic 非流式响应里提取文本和工具调用。
     * 用于探测/测试场景。
     */
    fun parseNonStreamResponse(json: String): Pair<String, List<Triple<String, String, String>>> {
        val text = StringBuilder()
        val tools = mutableListOf<Triple<String, String, String>>()
        try {
            val root = JSONObject(json)
            val content = root.optJSONArray("content") ?: return "" to emptyList()
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                when (block.optString("type", "")) {
                    "text" -> text.append(block.optString("text", ""))
                    "tool_use" -> tools.add(
                        Triple(
                            block.optString("id", ""),
                            block.optString("name", ""),
                            block.optJSONObject("input")?.toString() ?: "{}"
                        )
                    )
                }
            }
        } catch (_: Exception) { }
        return text.toString() to tools
    }
}
