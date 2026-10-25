package net.xinkong.furagent.core

import net.xinkong.furagent.shared.util.DateFormats
import net.xinkong.furagent.shared.ThinkingLevel
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
import java.util.concurrent.atomic.AtomicLong
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
    private val generation = AtomicLong(0L)

    /** 新任务开始：代际 +1，返回新代际。 */
    fun beginTask(): Long = generation.incrementAndGet()

    /** 当前代际（不递增）。 */
    fun currentGeneration(): Long = generation.get()

    /** 传入任务开始时的代际，判断是否已被取消。 */
    fun isCancelled(gen: Long): Boolean = gen != generation.get()

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
        generation.incrementAndGet()
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
你是 FurAgent，运行在用户 Android 手机上。时间 {TIME}，可访问 {PATHS}。工具用法见各 description。

【工具发现】tools 只含核心工具 + tool_search/tool_load。
缺工具→先看 tool_search 目录，再 tool_load(name=) 或 tool_search(query=)。加载后下轮可用，本轮勿重复搜。

【环境】真实手机非服务器。ARM CPU，性能低，内存/电量/存储有限。大文件先估算；一次只跑一个 FFmpeg。

【规则】
1 执行操作必须调工具，禁止文字声称完成。
2 只依据工具返回判断成败；以"失败"开头才算失败；含"用户已拒绝"才算被拒。
3 禁止在正文写"[工具调用]""结果：""[工具返回]"。
4 无专用工具时才用 execute_shell（需 Shizuku）。
5 复杂任务先列编号计划。
6 用户给 URL 要看内容→直接抓正文。
7 操作别的 App 界面优先用 ui，拿不到再 screenshot。
8 看图用 view_image；取时间用 time(op=now)。

【批量与并行】互不依赖的操作放同一条消息返回多个 tool_call，系统并行执行。
可并行：多个 read_file、多个 list_files、多个 file_op(create)。
必须串行：tool_search/tool_load（改 tools 数组）、有依赖的操作、FFmpeg 转码。
file_op(op=edit) 是整文件覆盖；改多文件尽量一条消息发多个 file_op。

【路径】别名：sdcard/内部存储/根目录→/sdcard；下载→/sdcard/Download；图片→/sdcard/DCIM；文档→/sdcard/Documents；音乐→/sdcard/Music。
产出目录（/sdcard/FurAgent/）：downloads 下载 / images 生成图 / media 媒体 / temp 临时 / screenshots 截图 / scripts 脚本（无外部存储权限时回退 App 私有目录）。除非用户指定其它路径。

【工作目录】execute_shell 经 Shizuku 执行，默认 cwd 是 /（根目录），不是 /sdcard 也不是用户目录。相对路径先 cd 或用绝对路径。

【失败处理】参数错→修正重试一次；权限问题→走权限请求；同工具同参数连失 2 次→停止换思路；连失 3 次系统强制中断。

【记忆】主动存：姓名/昵称、职业、偏好/习惯/忌讳、常用路径/应用、用户纠正过的事实。不存：一次性信息、寒暄。取用 memory(op=recall)。
""".trimIndent()
}

val httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .build()

suspend fun httpWithRetry(
    maxAttempts: Int = 3,
    /**
     * 【UI 提示】重试时回调，让界面能显示“服务端繁忙，3s 后重试”。
     * 之前只写日志，用户看到界面卡住不知道在干什么。
     */
    onRetry: ((code: Int, attempt: Int, maxAttempts: Int, waitMs: Long) -> Unit)? = null,
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

            val retryable = if (resp.code == 429) {
                val body = try {
                    resp.peekBody(4096).string()
                } catch (_: Exception) { "" }
                ApiErrorInfo.explain(429, body).retryable
            } else {
                resp.code == 408 || resp.code in 500..599
            }
            if (!retryable) return resp

            if (attempt < maxAttempts - 1) {
                AgentLogger.log("SYS", "HTTP ${resp.code}，${delayMs}ms 后重试")
                onRetry?.invoke(resp.code, attempt + 1, maxAttempts, delayMs)
                try { resp.close() } catch (_: Exception) { }
                delay(delayMs.milliseconds); delayMs *= 2
            } else return resp
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            lastException = e
            if (attempt < maxAttempts - 1) {
                AgentLogger.log("SYS", "请求异常：${e.message}，${delayMs}ms 后重试")
                onRetry?.invoke(0, attempt + 1, maxAttempts, delayMs)
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
    /**
     * 【错误回调】API 错误（HTTP 错误码 / 网络异常）。
     *
     * 与 onThinking 分开的原因：错误要渲染成状态小气泡并保留到下次发消息，
     * 而 thinking 在任务结束时会被清空。也不走 assistant_text，
     * 因为那不是 AI 说的话，不该占 AI 聊天气泡。
     */
    onError: (String) -> Unit = {},
    onReasoning: (String) -> Unit,
    onStreamChunk: (String) -> Unit,
    onStreamCommit: () -> Unit,
    onFinish: () -> Unit,
    /**
     * 【取消代际】调用方（AgentRunner）在任务开始时通过 AgentSession.beginTask() 拿到。
     * 工具执行前会比对当前代际，不一致就立即中止，不再执行任何工具。
     *
     * 【为什么需要】之前只靠协程 cancel()，但工具执行是阻塞调用
     * （Thread.join / readLine / Process.waitFor），取消不会立即中断。
     * 结果就是：UI 上显示已停止，但工具已经真实执行了（删文件、发请求）。
     * 传 0 表示不检查（兼容旧调用）。
     */
    cancelGen: Long = 0L,
    /** 【P0-3】会话专用提示词：注入系统提示词末尾，只作用于当前会话。 */
    sessionPrompt: String = ""
) {
    var currentHistory = history
    // tool_call 发起时间（仅主线程访问），tool_result 据此计算执行耗时
    val callStartMs = mutableMapOf<String, Long>()
    try {
        withContext(Dispatchers.IO) {
            runAgentLoop(profile, history, maxTurns, context, settings,
                cancelGen = cancelGen,
                onError = onError,
                sessionPrompt = sessionPrompt,
                onEvent = { event ->
                    mainHandler.post {
                        when (event.type) {
                            "thinking" -> onThinking(event.content)

                            "reasoning" -> onReasoning(event.content)

                            "reasoning_msg" -> {
                                // 本轮思考过程作为独立消息落库
                                currentHistory = currentHistory + ChatMessage(
                                    role = "assistant",
                                    content = event.content,
                                    type = "reasoning"
                                )
                                onHistoryChanged(currentHistory)
                                // 【清空实时缓冲】思考已落库，运行时缓冲必须清掉，
                                // 否则最后一条工作过程会把旧思考永远悬在那里，
                                // 既与已保存的消息重复，又看不到最新内容。
                                // 此刻助手正文尚未开始流式输出，清空 streaming 无副作用。
                                onStreamCommit()
                            }

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
                                callStartMs[event.callId] = System.currentTimeMillis()
                                // 把 API 返回的原始 tool_call_id 存进 ChatMessage。
                                currentHistory = currentHistory + ChatMessage(
                                    role = "tool",
                                    content = event.content,
                                    type = "tool_call",
                                    toolCallId = event.callId,
                                    parallelGroup = event.parallelGroup
                                )
                                onHistoryChanged(currentHistory)
                            }

                            "tool_result" -> {
                                val durationMs = callStartMs.remove(event.callId)
                                    ?.let { System.currentTimeMillis() - it } ?: 0L
                                currentHistory = currentHistory + ChatMessage(
                                    role = "tool",
                                    content = event.content,
                                    type = "tool_result",
                                    toolCallId = event.callId,
                                    durationMs = durationMs,
                                    parallelGroup = event.parallelGroup
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
    supportsVision: Boolean,
    budgetKTokens: Int = 0
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
    val byTurns = history.subList(startIdx, history.size)
    // 【token 预算二次裁剪】轮次截断拦不住工具输出特别大的会话：
    // 一轮里读十个大文件就能吃掉几万 token。这里再按预算从最旧的工具过程开始丢。
    val recent = if (budgetKTokens > 0) {
        trimToolProcessByBudget(
            history = byTurns,
            budgetTokens = budgetKTokens * 1000,
            entryTokens = {
                TokenEstimator.estimate(it.content) +
                    it.imagePaths.size * TokenEstimator.PER_IMAGE_TOKENS
            },
            isToolEntry = {
                it.type == "tool_call" || it.type == "tool_result" || it.type == "system_note"
            },
            makeElidedNote = { n ->
                ChatMessage(
                    role = "assistant",
                    content = "（此处省略 " + n + " 条工具调用记录，为节省上下文未逐条保留）",
                    type = "system_note"
                )
            }
        ).kept
    } else byTurns

    var i = 0
    while (i < recent.size) {
        val msg = recent[i]
        when (msg.type) {

            "tool_call" -> {
                val toolCallFunctions = mutableListOf<JSONObject>()
                val toolResultMessages = mutableListOf<JSONObject>()
                var idx = i

                // 【并行分批】先把这一批连续的 tool_call 收集起来。
                // 并行执行时它们是一条接一条追加的，结果再按完成顺序陆续追加。
                val batch = mutableListOf<ChatMessage>()
                while (idx < recent.size && recent[idx].type == "tool_call") {
                    batch.add(recent[idx])
                    idx++
                }

                for (cur in batch) {
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

                    // 【关键修复】按 toolCallId 配对，不能只看位置相邻。
                    // 并行跑一批工具时，历史里是「A的call, B的call, C的call,
                    // B的result, A的result, C的result」这种错开的顺序。
                    // 旧逻辑只看下一条是不是 tool_result，于是 A 被判成
                    // “未记录返回结果”，模型以为工具失败 → 重跑一遍（重复读
                    // 同一个文件、重复跑同一个命令），又慢又浪费钱。
                    val resultMsg = recent.firstOrNull {
                        it.type == "tool_result" && it.toolCallId.isNotBlank() &&
                                it.toolCallId == callId
                    } ?: run {
                        // 兼容旧数据（没存 toolCallId）：退回位置相邻判断
                        val pos = recent.indexOf(cur) + 1
                        recent.getOrNull(pos)
                            ?.takeIf { it.type == "tool_result" && it.toolCallId.isBlank() }
                    }

                    if (resultMsg != null) {
                        toolResultMessages.add(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", callId)
                            put("content", resultMsg.content)
                        })
                    } else {
                        toolResultMessages.add(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", callId)
                            put("content", "（该工具调用未记录返回结果，用户可能中断了）")
                        })
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
    onReasoning: (String) -> Unit = {},
    /** 【错误回调】API 错误走这里，不走 assistant_text（不占 AI 聊天气泡）。 */
    onError: (String) -> Unit = {},
    /** 【取消代际】0 表示不检查。见 runAgentTurn 的说明。 */
    cancelGen: Long = 0L,
    /** 【P0-3】会话专用提示词：注入系统提示词末尾，只作用于当前会话。 */
    sessionPrompt: String = ""
): String {
    // 工具执行前调用。代际不一致 = 用户已点停止。
    fun cancelled(): Boolean = cancelGen != 0L && AgentSession.isCancelled(cancelGen)

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

    // 时间戳只到天。
    val now = DateFormats.formatDate()
    var systemPrompt = Prompts.DEFAULT_SYSTEM
        .replace("{TIME}", now)
        .replace("{PATHS}", settings.allowedPaths.joinToString(", "))

    if (settings.customSystemPrompt.isNotBlank()) {
        systemPrompt += "\n\n【用户附加指令】\n" + settings.customSystemPrompt
    }

    // 【P0-3】会话专用提示词：与桌面端 DesktopAgent.runTurn 的 sessionPrompt 参数对齐
    if (sessionPrompt.isNotBlank()) {
        systemPrompt += "\n\n【本会话专用指令】\n" + sessionPrompt
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
    buildHistoryMessages(
        history, maxTurns, messages, supportsVision,
        budgetKTokens = settings.contextTokenBudgetK
    )

    // 与桌面端一致：thinkingLevel 优先，否则只要模型支持就主动关闭。
    // 不再依赖 settings.disableThinking（默认 false 会导致什么都不发，
    // 而 DeepSeek 官方 API 默认开思考，返回 reasoning_content 触发 null 坑）。
    if (settings.thinkingLevel == ThinkingLevel.OFF && !profile.supportsDisableThinking) {
        AgentLogger.log("SYS", "当前模型 [${profile.name}] 不支持关闭深度思考，将使用服务端默认行为")
    }
    val toolFailureStreak = mutableMapOf<String, Int>()
    val maxLoops = settings.maxToolCallLoops.coerceAtLeast(1)

    // 跨轮次累积"已通过 tool_search / tool_load 加载过"的工具名。
    // 每次 API 请求的 tools 数组 = 核心工具 + 已加载工具 + 元工具。
    val loadedToolNames = mutableSetOf<String>()

    repeat(maxLoops) { loopIndex ->
        currentCoroutineContext().ensureActive()

        onEvent(AgentEvent("thinking", if (loopIndex == 0) "思考中..." else "继续思考中..."))
        AgentLogger.log("REQUEST", "第 ${loopIndex + 1} 轮（已加载额外工具 ${loadedToolNames.size} 个）")

        val promptTokens = estimateMessagesTokens(messages)

        // 每轮重新构建 tools（loadedToolNames 会变化）
        val availableTools = ToolRegistry.getToolsWithLoaded(settings, loadedToolNames)

        val body = JSONObject().apply {
            put("model", profile.model)
            put("messages", messages)
            put("tools", availableTools)
            // 温度从 profile 读，默认 0.1（Agent 场景要稳定调工具）
            put("temperature", profile.temperature)
            // 惩罚参数：只在非默认值时发送。
            // 很多中转站/本地模型不认这两个字段，无脑发送可能直接 400。
            if (profile.presencePenalty != 0.0) put("presence_penalty", profile.presencePenalty)
            if (profile.frequencyPenalty != 0.0) put("frequency_penalty", profile.frequencyPenalty)
            put("stream", true)
        }
        if (settings.thinkingLevel != ThinkingLevel.OFF) {
            // 主动开启思考（全局设置），优先级高于全局关闭开关
            applyEnableThinking(body, profile.baseUrl, settings.thinkingLevel)
        } else if (profile.supportsDisableThinking) {
            applyDisableThinking(body, profile.baseUrl)
        }

        val response: okhttp3.Response
        var retryCount = 0
        try {
            response = httpWithRetry(
                onRetry = { code, attempt, maxAttempts, waitMs ->
                    retryCount = attempt
                    val hint = if (code == 0) {
                        "网络异常，${waitMs / 1000}s 后重试（$attempt/$maxAttempts）…"
                    } else {
                        ApiErrorInfo
                            .retryHint(code, attempt, maxAttempts, waitMs)
                    }
                    onEvent(AgentEvent("thinking", hint))
                },
                buildRequest = {
                    Request.Builder()
                        .url(profile.baseUrl)
                        .addHeader("Authorization", "Bearer ${profile.apiKey}")
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Accept", "text/event-stream")
                        .post(body.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AgentLogger.log("ERR", "网络请求失败：${e.message}")
            onEvent(AgentEvent("thinking", ""))
            // 不走 assistant_text，避免占 AI 聊天气泡
            onError("网络请求失败 · ${e.message ?: "未知错误"}")
            return ""
        }

        if (!response.isSuccessful) {
            // okhttp 5.x：body 是 non-null
            val errStr = response.use { it.body.string() }
            AgentLogger.log("ERR", "HTTP ${response.code}: $errStr")
            onEvent(AgentEvent("thinking", ""))
            // 简短文案：`HTTP 429 · 用量配额已用尽`
            onError(ApiErrorInfo.format(response.code, errStr))
            return ""
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
            onEvent(AgentEvent("assistant_text", contentStr))
        }

        // 本轮 reasoning 作为独立消息落库（先于工具调用）。
        // 这样历史会话里能看到「思考过程 → 工具调用 → 思考过程 → ...」的完整过程。
        if (reasoningBuilder.isNotEmpty()) {
            onEvent(AgentEvent("reasoning_msg", reasoningBuilder.toString()))
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

        // 【并行化改造】工具执行
        //   1. 先按顺序把 tool_call 占位 emit 出去（保持 UI 顺序）
        //   2. 判断是否含元工具：含则整体串行（元工具会改 loadedToolNames）
        //   3. 否则并行执行，用 supervisorScope 保证单个失败不取消整批
        //   4. 结果按 idx 收集后，串行收尾（emit tool_result + 失败计数 + messages.put）
        //   5. vision drain 移到循环外，只做一次
        val sortedCalls = toolCalls.toSortedMap().toList()
        val hasMetaTool = sortedCalls.any {
            it.second.name == "tool_search" || it.second.name == "tool_load"
        }

        // 1. 推 tool_call 占位
        // 【并行可见性】并行批次给同批调用写同一个 parallelGroup，
        // UI 据此把同批条目収进「并行 N」分组，用户能看出这几个是一起跑的。
        val parallelTag = if (!hasMetaTool && sortedCalls.size > 1) {
            "pg_${System.currentTimeMillis()}_$loopIndex"
        } else ""
        sortedCalls.forEach { (idx, acc) ->
            val stableId = callIds[idx]!!
            val args = try { JSONObject(acc.arguments.toString()) } catch (_: Exception) { JSONObject() }
            val friendlyName = ToolRegistry.displayName(acc.name)
            onEvent(AgentEvent(
                "tool_call", "$friendlyName\n${args.toString(2)}", acc.name, stableId,
                parallelGroup = parallelTag
            ))
        }

        // 2/3. 执行（串行或并行）
        val results = LinkedHashMap<Int, String>()
        if (hasMetaTool || sortedCalls.size <= 1) {
            // 串行路径：含元工具或只有一个工具
            sortedCalls.forEach { (idx, acc) ->
                currentCoroutineContext().ensureActive()
                // 用户已点停止就不再执行后续工具
                if (cancelled()) {
                    AgentLogger.log("TOOL", "已取消，跳过工具 ${acc.name}")
                    return@forEach
                }
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
                        // 并行分支也要查，否则点停止后仍会全部跑完
                        if (cancelled()) {
                            AgentLogger.log("TOOL", "已取消，跳过工具 ${acc.name}")
                            return@async idx to "失败：用户已停止"
                        }
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

            onEvent(AgentEvent("tool_result", rawResult, acc.name, stableId, parallelGroup = parallelTag))

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