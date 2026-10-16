package net.xinkong.furagent.core

import android.content.Context
import kotlinx.coroutines.*
import net.xinkong.furagent.data.*
import net.xinkong.furagent.shared.util.*
import net.xinkong.furagent.tools.ToolRegistry
import net.xinkong.furagent.tools.VisionHelper
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

private const val TOOL_FAILURE_STREAK_LIMIT = 3

/**
 * 截断历史时至少保留的消息条数（含工具消息）。
 * 防止极端情况下把上下文清空，导致 AI 完全失忆。
 */
private const val MIN_KEEP_MESSAGES = 40

/**
 * 管理正在进行的 OkHttp Call。
 *
 * 【A12 修复】旧实现是单槽位（一个 currentCall），并发调用时后到的 Call 会
 * 覆盖前一个，导致 cancelAll() 只能取消最后一个，"停止"按钮有时不生效。
 * 现在改成 LinkedHashSet：
 *   - setCall(call)    加入
 *   - cancelAll()      全部取消（用户点"停止"时调用）
 *
 * 保守清理：集合超过 16 个时，移除最早的几个（前面请求早已完成）。
 * 精确清理由调用方在流读完后调用 clearCall(call)，拿不到 call 时忽略。
 */
object AgentSession {
    private val lock = Any()
    private val calls = LinkedHashSet<Call>()

    /**
     * 取消代际。
     *
     * 【用途】让阻塞式 IO（如下载）能感知「用户点了停止」。
     *   - beginTask()  新任务开始，代际 +1，返回新代际
     *   - cancelAll()  用户点停止，代际 +1（所有旧代际立即失效）
     *   - isCancelled(gen)  传入任务开始时拿到的代际，比对当前代际
     *
     * 为什么用代际而不是布尔标志：布尔标志需要「新任务开始时清位」，
     * 一旦漏清就会误判；代际天然单调递增，无需清理。
     */
    @Volatile private var generation: Long = 0L

    /** 新任务开始：代际 +1，返回新代际。 */
    fun beginTask(): Long {
        generation++
        return generation
    }

    /** 当前代际（不递增）。 */
    fun currentGeneration(): Long = generation

    /** 传入任务开始时的代际，判断是否已被取消。 */
    fun isCancelled(gen: Long): Boolean = gen != generation

    fun setCall(call: Call?) {
        if (call == null) return
        synchronized(lock) {
            calls.add(call)
            while (calls.size > 16) {
                val it = calls.iterator()
                if (it.hasNext()) { it.next(); it.remove() } else break
            }
        }
    }

    fun clearCall(call: Call? = null) {
        if (call != null) {
            synchronized(lock) { calls.remove(call) }
        }
    }

    fun cancelAll() {
        // 代际 +1：所有旧任务的 isCancelled(gen) 立即返回 true
        generation++
        val snapshot: List<Call>
        synchronized(lock) {
            snapshot = calls.toList()
            calls.clear()
        }
        snapshot.forEach { c ->
            try { c.cancel() } catch (_: Exception) { }
        }
    }

    // 兼容旧调用点
    fun cancelCurrentCall() = cancelAll()
}

object Prompts {
    val DEFAULT_SYSTEM = """
你是 FurAgent，运行在用户的 Android 手机上。当前时间 {TIME}，可访问目录 {PATHS}。
每个工具的具体用法见其 description。

【工具发现（重要）】
你的 tools 数组只包含"核心工具"和两个元工具 tool_search / tool_load。
其他工具（文件操作、媒体处理、系统控制、MCP 扩展等）需要按需加载：
  - 目录已经在 tool_search 的 description 里列出，先扫一眼
  - 需要某个工具时，调用 tool_search(query="描述") 或 tool_load(name="工具名")
  - 加载后下一轮就能直接调用，本轮不要重复搜索
不要因为 tools 数组里没看到某个工具就放弃，先 tool_search 找一下。

【运行环境】
真实手机，不是服务器。CPU 是 ARM 芯片，性能远低于桌面；内存有限；电量宝贵；存储有限。
- 大文件处理前先估算大小和电量
- 长时间高负载会发热，前台用户可能感知到卡顿
- 一次只跑一个 FFmpeg 任务，不要并发

【基本规则】
1. 需要执行操作时必须调用工具，禁止用文字声称完成。
2. 只依据工具返回判断成败。返回以"失败"开头才算失败，成功不得说失败；含"用户已拒绝"时才说被拒绝。
3. 禁止在正文里书写"[工具调用]""结果：""[工具返回]"等字样。要执行就走 tools 通道，否则用自然语言。
4. 只有在没有专用工具时才用 execute_shell（需 Shizuku 权限）。
5. 复杂任务先用编号列出简短计划，再逐步调用工具。
6. 用户提供 URL 且要求总结或查看内容时，直接抓网页正文，不要只依赖搜索摘要。
7. 操作别的 App 的界面时优先用 ui 工具（屏幕控制），只有 ui 拿不到信息时才回退到 screenshot。
8. 需要看本地图片用 view_image，会自动附加到下一轮上下文。需要当前时间用 time(op=now)。

【批量操作与并行（省 token 的关键）】

一条 assistant 消息里可以返回多个 tool_call。系统会并行执行，
下一轮一次性返回所有结果 —— 这比"调一个等一个"省 N 倍历史重发成本。

可以并行（互不依赖）：
- 多个 read_file（同时看多个文件）
- 多个 list_files / app(op=list) 这类独立查询
- 多个 file_op(op=create) 创建不同文件

不能并行：
- tool_search / tool_load（会改 tools 数组，系统强制串行）
- 有依赖的操作（后一步依赖前一步的输出）
- FFmpeg 转码（按上面【运行环境】约束，一次只跑一个）

file_op(op=edit) 是整文件覆盖，不是锚点编辑。
每次覆盖一个 300 行的文件，就要把 300 行都写一遍，token 成本极高。
所以：
- 改多个文件时，尽量在同一条消息里返回多个 file_op（并行执行）
- 避免"改一个文件 → 看结果 → 再改下一个"这种串行模式

复杂任务先想清楚要改哪些文件、要执行哪些命令，能并行的尽量一条消息发完。

【路径与目录】
路径别名：
- sdcard / 内部存储 / 根目录 → /sdcard
- 下载 → /sdcard/Download
- 图片 / 相册 / 照片 → /sdcard/DCIM
- 文档 → /sdcard/Documents
- 音乐 → /sdcard/Music

FurAgent 自己产出的文件默认放 /sdcard/FurAgent/ 下：
- 下载的文件 → /sdcard/FurAgent/downloads/
- 从电脑接收 → /sdcard/FurAgent/received/
- 生成的媒体 → /sdcard/FurAgent/media/
- 临时文件 → /sdcard/FurAgent/temp/
- 截图 → /sdcard/FurAgent/screenshots/
除非用户明确指定其它路径。

【失败处理】
工具返回失败时：
- 参数错误 → 修正参数重试一次
- 权限问题 → 走权限请求流程
- 同一工具同一参数连续失败 2 次 → 停止重试，向用户说明原因，换思路
- 禁止盲目重试同一个调用。连续失败 3 次系统会强制中断。

【主动记忆】
主动识别值得长期记住的信息，用 memory(op=save) 保存，不要等用户说"记住"。
值得存：姓名/昵称、职业/身份、偏好/习惯/忌讳、常用路径/应用、用户纠正过你的事实。
不值得存：一次性临时信息、寒暄。
每轮最多存一次。需要回忆时用 memory(op=recall)。

【回复规范】
中文，简洁直接，不用 emoji 或颜文字，代码块用 ```语言 包裹。
""".trimIndent()
}

val httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .build()

suspend fun httpWithRetry(
    maxAttempts: Int = 3,
    buildRequest: () -> Request
): okhttp3.Response {
    var lastException: Exception? = null
    var delayMs = 1000L
    repeat(maxAttempts) { attempt ->
        currentCoroutineContext().ensureActive()
        try {
            val call = httpClient.newCall(buildRequest())
            AgentSession.setCall(call)
            val resp = call.execute()
            if (resp.isSuccessful) return resp

            val retryable = resp.code == 429 || resp.code == 408 || resp.code in 500..599
            if (!retryable) return resp

            if (attempt < maxAttempts - 1) {
                AgentLogger.log("SYS", "HTTP ${resp.code}，${delayMs}ms 后重试")
                try { resp.close() } catch (_: Exception) { }
                delay(delayMs.milliseconds); delayMs *= 2
            } else return resp
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            lastException = e
            if (attempt < maxAttempts - 1) {
                AgentLogger.log("SYS", "请求异常：${e.message}，${delayMs}ms 后重试")
                delay(delayMs.milliseconds); delayMs *= 2
            }
        }
    }
    throw lastException ?: RuntimeException("请求失败")
}

fun testModelConnection(profile: ModelProfile): String? {
    return try {
        val base = profile.baseUrl.trimEnd('/')
        val url = when {
            base.endsWith("/chat/completions") ->
                base.substringBeforeLast("/chat/completions") + "/models"
            else -> "$base/models"
        }
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${profile.apiKey}")
            .get()
            .build()
        val resp = httpClient.newCall(req).execute()
        val code = resp.code
        // okhttp 5.x：body 是 non-null
        val body = resp.use { it.body.string().take(500) }
        when (code) {
            in 200..299 -> null
            401, 403 -> "认证失败（HTTP $code）：API Key 无效或权限不足"
            404 -> "HTTP 404：${base} 可能不是 OpenAI 兼容接口"
            else -> "HTTP $code：$body"
        }
    } catch (e: Exception) {
        "连接失败：${e.message}"
    }
}

private val FAKE_TOOL_PATTERN = Regex(
    """(?m)^\s*(?:>\s*)?(?:\[工具调用]|\[工具结果]|\[工具返回])\s*"""
)

suspend fun runAgentTurn(
    profile: ModelProfile, history: List<ChatMessage>, maxTurns: Int,
    context: Context, settings: AppSettings,
    onHistoryChanged: (List<ChatMessage>) -> Unit,
    onThinking: (String) -> Unit,
    onReasoning: (String) -> Unit,
    onStreamChunk: (String) -> Unit,
    onStreamCommit: () -> Unit,
    onFinish: () -> Unit
) {
    var currentHistory = history
    try {
        withContext(Dispatchers.IO) {
            runAgentLoop(profile, history, maxTurns, context, settings,
                onEvent = { event ->
                    mainHandler.post {
                        when (event.type) {
                            "thinking" -> onThinking(event.content)

                            "reasoning" -> onReasoning(event.content)

                            "assistant_text" -> {
                                onThinking("")
                                currentHistory = currentHistory + ChatMessage(
                                    role = "assistant",
                                    content = event.content,
                                    type = "text",
                                    reasoning = event.reasoning
                                )
                                onHistoryChanged(currentHistory)
                                onStreamCommit()
                            }

                            "tool_call" -> {
                                onThinking("")
                                // 【缓存关键】把 API 返回的原始 tool_call_id 存进 ChatMessage。
                                currentHistory = currentHistory + ChatMessage(
                                    role = "tool",
                                    content = event.content,
                                    type = "tool_call",
                                    toolCallId = event.callId
                                )
                                onHistoryChanged(currentHistory)
                            }

                            "tool_result" -> {
                                currentHistory = currentHistory + ChatMessage(
                                    role = "tool",
                                    content = event.content,
                                    type = "tool_result",
                                    toolCallId = event.callId
                                )
                                onHistoryChanged(currentHistory)
                            }

                            "final" -> onThinking("")
                        }
                    }
                },
                onStreamChunk = { chunk -> mainHandler.post { onStreamChunk(chunk) } },
                onReasoning = { r -> mainHandler.post { onReasoning(r) } }
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        mainHandler.post {
            onThinking("")
            currentHistory = currentHistory + ChatMessage(
                role = "assistant",
                content = "发生异常：${e.message ?: "未知错误"}"
            )
            onHistoryChanged(currentHistory)
        }
    } finally {
        mainHandler.post { onFinish() }
    }
}

/**
 * 从历史重建消息。
 *
 * 把连续的 (tool_call, tool_result) 对合并到同一条 assistant 消息里，
 * 使其结构对齐真实响应（一条 assistant + N 条 tool），而不是 N 条 assistant + N 条 tool。
 *
 * 【缓存关键】
 * tool_call_id 优先用 ChatMessage.toolCallId（那是 API 返回的原始 id）。
 * 只有老会话没有这个字段时才 fallback 到 "call_" + UUID。
 */
private fun buildHistoryMessages(
    history: List<ChatMessage>,
    maxTurns: Int,
    messages: JSONArray,
    supportsVision: Boolean
) {
    // 按「完整轮次」截断，绝不从一轮的中间切开。
    // 截断起点计算与桌面端共用（shared.computeTruncateStart）。
    val startIdx = computeTruncateStart(
        history = history,
        maxTurns = maxTurns.coerceAtLeast(1),
        minKeep = MIN_KEEP_MESSAGES,
        roleOf = { it.role },
        typeOf = { it.type }
    )
    val recent = history.subList(startIdx, history.size)

    var i = 0
    while (i < recent.size) {
        val msg = recent[i]
        when (msg.type) {

            "tool_call" -> {
                val toolCallFunctions = mutableListOf<JSONObject>()
                val toolResultMessages = mutableListOf<JSONObject>()
                var idx = i

                while (idx < recent.size && recent[idx].type == "tool_call") {
                    val cur = recent[idx]
                    val firstLine = cur.content.substringBefore('\n').trim()
                    val argsPart = cur.content.substringAfter('\n', "").trim()

                    val toolName = ToolRegistry.findToolNameByDisplay(firstLine) ?: firstLine
                    val argsJson = try {
                        if (argsPart.isBlank()) "{}"
                        else JSONObject(argsPart).toString()
                    } catch (e: Exception) {
                        AgentLogger.log(
                            "ERR",
                            "构建历史消息时解析工具参数失败（$toolName）：${e.javaClass.simpleName}: ${e.message}，原始：${argsPart.take(200)}"
                        )
                        "{}"
                    }

                    val callId = cur.toolCallId.ifBlank {
                        "call_${cur.id.replace("-", "")}"
                    }

                    toolCallFunctions.add(JSONObject().apply {
                        put("id", callId)
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", toolName)
                            put("arguments", argsJson)
                        })
                    })

                    val hasResult = idx + 1 < recent.size &&
                            recent[idx + 1].type == "tool_result"

                    if (hasResult) {
                        val resultMsg = recent[idx + 1]
                        toolResultMessages.add(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", callId)
                            put("content", resultMsg.content)
                        })
                        idx += 2
                    } else {
                        toolResultMessages.add(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", callId)
                            put("content", "（该工具调用未记录返回结果，用户可能中断了）")
                        })
                        idx += 1
                    }
                }

                messages.put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", "")
                    put("tool_calls", JSONArray().apply {
                        toolCallFunctions.forEach { put(it) }
                    })
                })
                toolResultMessages.forEach { messages.put(it) }

                i = idx
            }

            "tool_result" -> i += 1
            "system_note" -> i += 1

            else -> {
                if (msg.content.isNotBlank() || msg.imagePaths.isNotEmpty()) {
                    val obj = JSONObject()
                    obj.put("role", msg.role)

                    if (msg.imagePaths.isNotEmpty() && supportsVision) {
                        val arr = JSONArray()
                        if (msg.content.isNotBlank()) {
                            arr.put(JSONObject().apply {
                                put("type", "text")
                                put("text", msg.content)
                            })
                        }
                        for (path in msg.imagePaths) {
                            val dataUrl = VisionHelper.readAsDataUrl(path) ?: continue
                            arr.put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().put("url", dataUrl))
                            })
                        }
                        obj.put("content", arr)
                    } else {
                        obj.put("content", msg.content)
                    }
                    messages.put(obj)
                }
                i += 1
            }
        }
    }
}

/**
 * 执行单个工具。抽出供串行 / 并行两条路径复用。
 *
 * 元工具（tool_search / tool_load）会改 loadedToolNames，并行时是竞态，
 * 所以调用方（runAgentLoop）在含元工具时会强制走串行路径。
 */
private suspend fun runOneToolForAgent(
    name: String,
    args: JSONObject,
    context: Context,
    settings: AppSettings,
    loadedToolNames: MutableSet<String>
): String {
    return when (name) {
        "tool_search" -> {
            val query = args.optString("query", "").trim()
            val category = args.optString("category", "").trim()
            if (query.isBlank()) {
                "失败：tool_search 需要 query 参数"
            } else {
                val text = ToolRegistry.searchToolsForAgent(query, category, settings)
                val names = parseToolNamesFromSearchResult(text)
                names.forEach { loadedToolNames.add(it) }
                if (names.isNotEmpty()) {
                    AgentLogger.log(
                        "TOOL",
                        "tool_search 命中 ${names.size} 个：$names，已加入本轮 tools"
                    )
                }
                text
            }
        }
        "tool_load" -> {
            val toolName = args.optString("name", "").trim()
            if (toolName.isBlank()) {
                "失败：tool_load 需要 name 参数"
            } else {
                val text = ToolRegistry.loadToolForAgent(toolName, settings)
                if (!text.startsWith("失败")) {
                    loadedToolNames.add(toolName)
                    AgentLogger.log("TOOL", "tool_load 加载 $toolName 成功")
                }
                text
            }
        }
        else -> ToolRegistry.execute(name, args, context, settings)
    }
}

suspend fun runAgentLoop(
    profile: ModelProfile, history: List<ChatMessage>, maxTurns: Int,
    context: Context, settings: AppSettings,
    onEvent: (AgentEvent) -> Unit,
    onStreamChunk: (String) -> Unit,
    onReasoning: (String) -> Unit = {}
): String {
    AgentLogger.log("SYS", "=== 开始 Agent 循环（${profile.name}） ===")

    if (profile.apiKey.isBlank()) {
        val msg = "当前模型 [${profile.name}] 未配置 API Key，请在设置中填写"
        onEvent(AgentEvent("assistant_text", msg))
        return msg
    }

    VisionHelper.clearPendingImages()
    VisionHelper.configureFromSettings(
        enabled = settings.imageCompress,
        quality = settings.imageCompressQuality,
        maxDim = settings.imageCompressMaxDim
    )

    val supportsVision = profile.supportsVision

    // 【缓存关键】时间戳只到天。
    val now = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    var systemPrompt = Prompts.DEFAULT_SYSTEM
        .replace("{TIME}", now)
        .replace("{PATHS}", settings.allowedPaths.joinToString(", "))

    if (settings.customSystemPrompt.isNotBlank()) {
        systemPrompt += "\n\n【用户附加指令】\n" + settings.customSystemPrompt
    }

    if (settings.enableMemory) {
        val mem = MemoryStore.toPromptString(context)
        if (mem.isNotBlank()) systemPrompt = "$systemPrompt\n\n$mem"
    }

    if (settings.disabledTools.isNotEmpty()) {
        systemPrompt += "\n\n以下工具已被用户在设置中禁用，禁止调用：${
            settings.disabledTools.joinToString(", ")
        }"
    }

    val messages = JSONArray()
    messages.put(JSONObject().apply {
        put("role", "system")
        put("content", systemPrompt)
    })
    buildHistoryMessages(history, maxTurns, messages, supportsVision)

    val shouldDisableThinking = settings.disableThinking && profile.supportsDisableThinking
    if (settings.disableThinking && !profile.supportsDisableThinking) {
        AgentLogger.log("SYS", "当前模型 [${profile.name}] 不支持关闭深度思考，已跳过")
    }
    val toolFailureStreak = mutableMapOf<String, Int>()
    val maxLoops = settings.maxToolCallLoops.coerceAtLeast(1)

    // 【P0】跨轮次累积"已通过 tool_search / tool_load 加载过"的工具名。
    // 每次 API 请求的 tools 数组 = 核心工具 + 已加载工具 + 元工具。
    val loadedToolNames = mutableSetOf<String>()

    repeat(maxLoops) { loopIndex ->
        currentCoroutineContext().ensureActive()

        onEvent(AgentEvent("thinking", if (loopIndex == 0) "思考中..." else "继续思考中..."))
        AgentLogger.log("REQUEST", "第 ${loopIndex + 1} 轮（已加载额外工具 ${loadedToolNames.size} 个）")

        val promptTokens = estimateMessagesTokens(messages)

        // 【P0】每轮重新构建 tools（loadedToolNames 会变化）
        val availableTools = ToolRegistry.getToolsWithLoaded(settings, loadedToolNames)

        val body = JSONObject().apply {
            put("model", profile.model)
            put("messages", messages)
            put("tools", availableTools)
            // 【可配】温度从 profile 读，默认 0.1（Agent 场景要稳定调工具）
            put("temperature", profile.temperature)
            put("stream", true)
        }
        if (settings.enableThinking) {
            // 主动开启思考（全局开关），优先级高于全局关闭开关
            net.xinkong.furagent.shared.util.applyEnableThinking(body, profile.baseUrl)
        } else if (shouldDisableThinking) {
            net.xinkong.furagent.shared.util.applyDisableThinking(body, profile.baseUrl)
        }

        val response: okhttp3.Response
        try {
            response = httpWithRetry(buildRequest = {
                Request.Builder()
                    .url(profile.baseUrl)
                    .addHeader("Authorization", "Bearer ${profile.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "text/event-stream")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AgentLogger.log("ERR", "网络请求失败：${e.message}")
            onEvent(AgentEvent("thinking", ""))
            val msg = "网络请求失败：${e.message ?: "未知错误"}"
            onEvent(AgentEvent("assistant_text", msg))
            return msg
        }

        if (!response.isSuccessful) {
            // okhttp 5.x：body 是 non-null
            val errStr = response.use { it.body.string() }
            AgentLogger.log("ERR", "HTTP ${response.code}: $errStr")
            onEvent(AgentEvent("thinking", ""))
            val msg = "API 错误 HTTP ${response.code}：${errStr.take(300)}"
            onEvent(AgentEvent("assistant_text", msg))
            return msg
        }

        val contentBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        var toolCalls: MutableMap<Int, ToolCallAccumulator> = mutableMapOf()

        try {
            // okhttp 5.x：body 是 non-null，去掉多余的 ?.
            response.body.source().use { source ->
                toolCalls = parseSseStream(
                    readLine = { source.readUtf8Line() },
                    onContent = { content ->
                        contentBuilder.append(content)
                        onStreamChunk(content)
                    },
                    onReasoning = { r ->
                        reasoningBuilder.append(r)
                        onEvent(AgentEvent("reasoning", r))
                    },
                    onParseError = { cls, data ->
                        AgentLogger.log(
                            "ERR",
                            "SSE 单行 JSON 解析失败：$cls，行：${data.take(150)}"
                        )
                    }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AgentLogger.log("ERR", "SSE 解析失败：${e.message}")
            onEvent(AgentEvent("thinking", ""))
            val msg = "流式解析失败：${e.message ?: "未知错误"}"
            onEvent(AgentEvent("assistant_text", msg))
            return msg
        } finally {
            AgentSession.clearCall()
            try { response.close() } catch (_: Exception) { }
        }

        val contentStr = contentBuilder.toString()

        val completionTokens = estimateCompletionTokens(contentBuilder, toolCalls)
        TokenStats.add(
            promptTokens.toLong(),
            completionTokens.toLong(),
            (promptTokens + completionTokens).toLong()
        )

        val hasFakeToolCall = FAKE_TOOL_PATTERN.containsMatchIn(contentStr)
        if (toolCalls.isEmpty() && hasFakeToolCall) {
            AgentLogger.log("ERR", "检测到伪造工具调用文本，已拦截并重试")
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", "（系统纠正）你的上一条回复里出现了 \"[工具调用]\" 之类的文本。" +
                        "请不要在正文中书写这类字样。需要执行操作时必须使用 tools 字段调用工具；" +
                        "不需要则用自然语言回答。")
            })
            onEvent(AgentEvent("thinking", "检测到伪造工具调用，重试中..."))
            return@repeat
        }

        if (toolCalls.isEmpty()) {
            onEvent(AgentEvent("thinking", ""))
            val finalText = contentStr.ifBlank { "（AI 无回复）" }
            onEvent(AgentEvent("assistant_text", finalText, reasoning = reasoningBuilder.toString()))
            onEvent(AgentEvent("final", finalText))
            return finalText
        }

        if (contentStr.isNotBlank()) {
            onEvent(AgentEvent("assistant_text", contentStr, reasoning = reasoningBuilder.toString()))
        }

        // 给每个 tool_call 算一个稳定的 id
        val callIds = mutableMapOf<Int, String>()
        toolCalls.toSortedMap().forEach { (idx, acc) ->
            val stableId = acc.id.ifBlank {
                "${acc.name.ifBlank { "tool" }}_${loopIndex}_$idx"
            }
            callIds[idx] = stableId
        }

        val cleanAssistant = JSONObject().apply {
            put("role", "assistant")
            put("content", contentStr.ifBlank { "" })
            put("tool_calls", JSONArray().apply {
                toolCalls.toSortedMap().forEach { (idx, acc) ->
                    put(JSONObject().apply {
                        put("id", callIds[idx])
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", acc.name)
                            put("arguments", acc.arguments.toString())
                        })
                    })
                }
            })
        }
        messages.put(cleanAssistant)

        // ============================================================
        // 【并行化改造】工具执行
        //   1. 先按顺序把 tool_call 占位 emit 出去（保持 UI 顺序）
        //   2. 判断是否含元工具：含则整体串行（元工具会改 loadedToolNames）
        //   3. 否则并行执行，用 supervisorScope 保证单个失败不取消整批
        //   4. 结果按 idx 收集后，串行收尾（emit tool_result + 失败计数 + messages.put）
        //   5. vision drain 移到循环外，只做一次
        // ============================================================
        val sortedCalls = toolCalls.toSortedMap().toList()
        val hasMetaTool = sortedCalls.any {
            it.second.name == "tool_search" || it.second.name == "tool_load"
        }

        // 1. 推 tool_call 占位
        sortedCalls.forEach { (idx, acc) ->
            val stableId = callIds[idx]!!
            val args = try { JSONObject(acc.arguments.toString()) } catch (_: Exception) { JSONObject() }
            val friendlyName = ToolRegistry.displayName(acc.name)
            onEvent(AgentEvent(
                "tool_call", "$friendlyName\n${args.toString(2)}", acc.name, stableId
            ))
        }

        // 2/3. 执行（串行或并行）
        val results = LinkedHashMap<Int, String>()
        if (hasMetaTool || sortedCalls.size <= 1) {
            // 串行路径：含元工具或只有一个工具
            sortedCalls.forEach { (idx, acc) ->
                currentCoroutineContext().ensureActive()
                val args = try { JSONObject(acc.arguments.toString()) } catch (_: Exception) { JSONObject() }
                AgentLogger.log("TOOL", "调用（串行）：${acc.name}")
                results[idx] = runOneToolForAgent(acc.name, args, context, settings, loadedToolNames)
            }
        } else {
            // 并行路径
            AgentLogger.log(
                "TOOL",
                "并行执行 ${sortedCalls.size} 个工具：${sortedCalls.map { it.second.name }}"
            )
            val executed = supervisorScope {
                sortedCalls.map { (idx, acc) ->
                    async {
                        val args = try {
                            JSONObject(acc.arguments.toString())
                        } catch (_: Exception) { JSONObject() }
                        val r = try {
                            runOneToolForAgent(acc.name, args, context, settings, loadedToolNames)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            AgentLogger.log(
                                "ERR",
                                "并行工具 ${acc.name} 抛出异常：${t.javaClass.simpleName}: ${t.message}"
                            )
                            "执行异常：${t.javaClass.simpleName}: ${t.message}"
                        }
                        idx to r
                    }
                }.awaitAll()
            }
            executed.forEach { (idx, r) -> results[idx] = r }
        }

        // 4. 串行收尾
        sortedCalls.forEach { (idx, acc) ->
            currentCoroutineContext().ensureActive()

            val stableId = callIds[idx]!!
            val args = try { JSONObject(acc.arguments.toString()) } catch (_: Exception) { JSONObject() }
            val friendlyName = ToolRegistry.displayName(acc.name)
            val rawResult = results[idx] ?: "失败：工具未执行"

            onEvent(AgentEvent("tool_result", rawResult, acc.name, stableId))

            // 失败重试计数（元工具跳过）
            if (acc.name !in setOf("tool_search", "tool_load")) {
                val sig = toolSignature(acc.name, args)
                if (isToolFailure(rawResult)) {
                    val streak = (toolFailureStreak[sig] ?: 0) + 1
                    toolFailureStreak[sig] = streak
                    AgentLogger.log("ERR", "工具 ${acc.name} 失败第 $streak 次（签名：${sig.take(80)}）")
                    if (streak >= TOOL_FAILURE_STREAK_LIMIT) {
                        AgentLogger.log("ERR", "工具连续失败 $TOOL_FAILURE_STREAK_LIMIT 次，中断循环")
                        onEvent(AgentEvent("thinking", ""))
                        val msg = "我连续 $TOOL_FAILURE_STREAK_LIMIT 次用相同参数调用" +
                                "「$friendlyName」都失败了，停止重试。\n\n" +
                                "最后一次错误：${rawResult.take(300)}"
                        onEvent(AgentEvent("assistant_text", msg))
                        onEvent(AgentEvent("final", msg))
                        return msg
                    }
                } else {
                    toolFailureStreak.remove(sig)
                }
            }

            messages.put(JSONObject().apply {
                put("role", "tool")
                put("tool_call_id", stableId)
                put("content", rawResult)
            })
        }

        // 5. vision drain：本轮所有工具跑完后统一 drain 一次（对齐桌面端）
        val pending = VisionHelper.drainPendingImages()
        if (pending.isEmpty()) {
            // nothing
        } else if (supportsVision) {
            messages.put(
                VisionHelper.buildMultiModalUserMessage(
                    "以下是我刚获取的图片，请基于图片内容继续。",
                    pending
                )
            )
            onEvent(AgentEvent("thinking", "已附加 ${pending.size} 张图片"))
        } else {
            AgentLogger.log(
                "SYS",
                "模型 [${profile.name}] 不支持 vision，已丢弃 ${pending.size} 张待附加图片"
            )
        }
    }

    onEvent(AgentEvent("thinking", ""))
    val finalMsg = "已达到工具调用最大轮数（$maxLoops），请重试"
    onEvent(AgentEvent("assistant_text", finalMsg))
    return finalMsg
}