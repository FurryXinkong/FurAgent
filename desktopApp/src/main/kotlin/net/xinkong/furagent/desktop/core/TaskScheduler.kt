package net.xinkong.furagent.desktop.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import net.xinkong.furagent.desktop.data.*
import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.desktop.system.DesktopNotifier
import net.xinkong.furagent.desktop.tools.DesktopTools
import net.xinkong.furagent.shared.data.ChatMessage
import net.xinkong.furagent.shared.util.AnthropicCompat
import net.xinkong.furagent.shared.util.ApiProtocol
import net.xinkong.furagent.shared.util.DateFormats
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * 桌面端定时任务调度器。
 *
 * 与 Android 不同：没有系统 AlarmManager，自己每秒轮询。
 * 好处是可以做到秒级精度，且条件评估非常灵活。
 *
 * 关键设计：
 *  - 单例 object，App 生命周期内一直运行
 *  - 每秒 tick 一次，检查所有到期任务
 *  - 条件评估放 Dispatchers.IO，避免阻塞主 loop
 *  - 同一任务并发保护：runningIds 集合
 *  - 执行超时保护：每个任务最多 10 分钟
 *  - ScheduleStore 读写全部走 Dispatchers.IO，不阻塞 tick
 *
 */
object TaskScheduler {

    private const val TICK_INTERVAL_MS = 1000L
    private const val TASK_TIMEOUT_MS = 10 * 60 * 1000L

    /**
     * run_agent 写回会话后的 UI 刷新事件。
     * Main 里 collect：当前会话直接注入消息，同时刷新侧栏会话列表。
     */
    data class AgentResultEvent(val sessionId: String, val message: ChatMessage)

    private val _agentResults = MutableSharedFlow<AgentResultEvent>(extraBufferCapacity = 8)
    val agentResults: SharedFlow<AgentResultEvent> = _agentResults.asSharedFlow()

    /** run_agent 专用：非流式 LLM 调用 */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var loopJob: Job? = null

    private val runningIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var lastTickAt: Long = 0L
        private set

    @Volatile
    var totalExecutions: Long = 0L
        private set

    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            log("调度器已启动（${TICK_INTERVAL_MS}ms 轮询）")
            while (isActive) {
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("tick 异常：${e.javaClass.simpleName}: ${e.message}")
                }
                delay(TICK_INTERVAL_MS.milliseconds)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        log("调度器已停止")
    }

    fun isRunning(): Boolean = loopJob?.isActive == true

    // 核心 tick

    private suspend fun tick() {
        lastTickAt = System.currentTimeMillis()
        val now = lastTickAt

        val tasks = withContext(Dispatchers.IO) { ScheduleStore.list() }
        if (tasks.isEmpty()) return

        val pendingUpdates = mutableListOf<ScheduledTask>()
        val pendingDeletes = mutableListOf<String>()

        for (task in tasks) {
            if (!task.enabled) continue
            if (task.triggerTime > now) continue
            if (task.id in runningIds) continue

            // 条件评估放 IO 线程（可能触发外部命令）
            val condResult = withContext(Dispatchers.IO) {
                ScheduleStore.evaluateConditions(task)
            }

            val label = task.description.ifBlank { task.id.take(8) }

            if (!condResult.met && task.skipIfNotMet) {
                log("跳过「$label」：${condResult.reason}")
                val next = ScheduleStore.calculateNextTrigger(task)
                if (next != null) {
                    pendingUpdates.add(task.copy(triggerTime = next))
                } else if (task.repeatMode == "cron") {
                    // 体验优化 cron 非法：不再静默删任务，改成日志 + 通知，让用户自己改
                    DesktopLogger.warn(
                        "Schedule",
                        "任务「$label」cron 表达式可能非法（${task.cronExpression}），已保留但暂停调度"
                    )
                } else {
                    pendingDeletes.add(task.id)
                }
                continue
            }

            // 派发执行
            runningIds.add(task.id)
            scope.launch {
                // 超时 kill 不能用 withTimeout：
                //   withTimeout 只 cancel 协程，而 DesktopTools.executeCommand 内部
                //   的 Process.waitFor(30s) 是 Java 阻塞调用，不响应协程 cancel，
                //   要等它自然结束才抛 CancellationException —— 那时工具早跑完了，kill 晚了。
                // 正解：独立 timer 协程，到点先 TaskRuntime.cancelBySession 触发
                //   destroyForcibly + taskkill（阻塞读立即返回），再 cancel 主协程。
                val taskJob = currentCoroutineContext()[Job]
                val timeoutJob = launch {
                    try {
                        delay(TASK_TIMEOUT_MS.milliseconds)
                        log("任务执行超时（${TASK_TIMEOUT_MS / 1000}s）：${task.id}")
                        TaskRuntime.cancelBySession("schedule_${task.id}")
                        taskJob?.cancel()
                    } catch (_: CancellationException) {
                    } catch (e: Exception) {
                        log("超时 kill 异常：${e.javaClass.simpleName}: ${e.message}")
                    }
                }
                try {
                    executeTask(task)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("任务执行异常：${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    timeoutJob.cancel()
                    // 兜底清理：正常结束路径下 withTask 的 finally 已移除 task；
                    // cancel 路径下 timer 已 kill 过。这里再调一次防御残留子进程。
                    try { TaskRuntime.cancelBySession("schedule_${task.id}") } catch (_: Exception) { }
                    runningIds.remove(task.id)
                    totalExecutions++
                }
            }

            // 立即排下次（避免下一秒重复触发）
            val next = ScheduleStore.calculateNextTrigger(task)
            if (next != null) {
                pendingUpdates.add(task.copy(triggerTime = next))
            } else if (task.repeatMode == "cron") {
                // cron 非法：不删，只日志
                DesktopLogger.warn(
                    "Schedule",
                    "任务「$label」cron 表达式可能非法（${task.cronExpression}），保留但不排下次"
                )
            } else {
                pendingDeletes.add(task.id)
            }
        }

        if (pendingUpdates.isNotEmpty() || pendingDeletes.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                ScheduleStore.batchUpdate(pendingUpdates, pendingDeletes)
            }
            // 不用解构赋值（字段顺序一变就静默错位），改用属性访问。
            for ((id, triggerTime, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, description) in pendingUpdates) {
                val label = description.ifBlank { id.take(8) }
                log("「$label」下次：${DateFormats.formatDateTime(triggerTime)}")
            }
            for (id in pendingDeletes) {
                log("「${id.take(8)}」已完成，移除")
            }
        }
    }

    // 任务执行

    private suspend fun executeTask(task: ScheduledTask) {
        log("触发「${task.description.ifBlank { task.id.take(8) }}」action=${task.action}")

        when (task.action) {
            "notify" -> {
                DesktopNotifier.notify(
                    task.notificationTitle.ifBlank { "FurAgent 提醒" },
                    task.notificationContent.ifBlank { task.description }
                )
            }

            "run_tool" -> {
                if (task.toolName.isBlank()) {
                    log("run_tool 缺少 toolName")
                    return
                }
                val args = try {
                    if (task.toolArgs.isBlank()) JSONObject()
                    else JSONObject(task.toolArgs)
                } catch (e: Exception) {
                    log("toolArgs 解析失败：${e.message}")
                    return
                }
                // sessionId 用 schedule_ 前缀：超时 timer 到点后调 cancelBySession
                // 精确 kill 本任务启动的进程，不会误杀用户手动的会话工具。
                val result = DesktopTools.execute(task.toolName, args.toString(), sessionId = "schedule_${task.id}")
                log("工具 ${task.toolName} 结果：${result.output.take(200)}")
                if (!result.success) {
                    DesktopNotifier.notify(
                        "任务执行失败",
                        "${task.description.ifBlank { task.toolName }}：${result.output.take(150)}"
                    )
                }
            }

            "run_workflow" -> {
                if (task.workflowName.isBlank()) {
                    log("run_workflow 缺少 workflowName")
                    return
                }
                // 工具名是 "workflow"（WorkflowTools 注册名），
                // 旧代码写 "run_workflow" 会返回「未知工具」，功能完全不可用。
                val args = JSONObject().apply {
                    put("op", "run")
                    put("name", task.workflowName)
                }
                // sessionId 用 schedule_ 前缀，超时可精确取消本任务启动的工具
                val result = DesktopTools.execute("workflow", args.toString(), sessionId = "schedule_${task.id}")
                log("工作流 ${task.workflowName} 结果：${result.output.take(200)}")
                if (!result.success) {
                    DesktopNotifier.notify(
                        "工作流执行失败",
                        "${task.workflowName}：${result.output.take(150)}"
                    )
                }
            }

            "open_app" -> {
                if (task.appPath.isBlank()) {
                    log("open_app 缺少 appPath")
                    return
                }
                // 工具名是 "apps"（AppTools 注册名），
                // 旧代码写 "open_app" 会返回「未知工具」，功能完全不可用。
                val args = JSONObject().apply {
                    put("op", "open")
                    put("path", task.appPath)
                }
                // sessionId 用 schedule_ 前缀，超时可精确取消本任务启动的工具
                val result = DesktopTools.execute("apps", args.toString(), sessionId = "schedule_${task.id}")
                log("打开应用结果：${result.output.take(200)}")
                if (!result.success) {
                    DesktopNotifier.notify(
                        "打开应用失败",
                        "${task.appPath}：${result.output.take(150)}"
                    )
                }
            }

            "run_command" -> {
                if (task.command.isBlank()) {
                    log("run_command 缺少 command")
                    return
                }
                val args = JSONObject().apply {
                    put("command", task.command)
                    put("timeout", 60)
                }
                // sessionId 用 schedule_ 前缀，超时可精确取消本任务启动的工具
                val result = DesktopTools.execute("execute_command", args.toString(), sessionId = "schedule_${task.id}")
                log("命令结果：${result.output.take(200)}")
            }

            "run_agent" -> executeAgent(task)

            else -> log("未知 action：${task.action}")
        }
    }

    /**
     * 能力对齐 Android run_agent 动作执行。
     *
     * 调用当前模型配置的 LLM API（非流式），
     * 任务绑定了 sessionId 时把回复作为 assistant 消息写回该会话，
     * 否则只发系统通知。
     */
    private suspend fun executeAgent(task: ScheduledTask) {
        val prompt = task.prompt.trim()
        if (prompt.isBlank()) {
            log("run_agent 缺少 prompt")
            return
        }

        val settings = withContext(Dispatchers.IO) { DesktopSettings.load() }
        val profile = settings.currentProfile()
        if (profile == null) {
            DesktopNotifier.notify("AI 任务失败", "未配置模型")
            return
        }
        val keyResult = ApiKeyResolver.resolve(settings, profile)
        if (keyResult !is ApiKeyResolver.Result.Found) {
            DesktopNotifier.notify("AI 任务失败", "无法获取 API Key")
            return
        }

        log("run_agent 开始：${prompt.take(100)}")

        // 走完整的 Agent 循环（工具 + 记忆 + 多轮），而不是单次非流式请求。
        // 旧实现只发一次 chat/completions，AI 无法调工具、看不到记忆，
        // 定时任务能做的事被锁死在「纯文本问答」。
        // 与 Android 端对齐 绑定了 sessionId 时把该会话历史作为上下文，
        // 之前只把结果写回会话、不带上下文，用户期望的「AI 记得之前聊过什么」落空。
        val baseHistory = if (task.sessionId.isNotBlank()) {
            withContext(Dispatchers.IO) {
                DesktopSessionStore.load(task.sessionId)?.messages.orEmpty()
            }
        } else emptyList()
        val history = baseHistory + ChatMessage(role = "user", content = prompt)
        val result = try {
            DesktopAgent.runTurn(
                settings = settings,
                history = history,
                onUpdate = { },
                onThinking = { },
                onError = { err -> log("run_agent 错误：$err") },
                onReasoning = { },
                onStream = { },
                onStreamCommit = { },
                // sessionId 用 schedule_ 前缀：不写进用户会话，但超时 timer 能精确 cancel
                sessionId = "schedule_${task.id}",
                // 定时任务必须限轮：无人盯着，跑飞了就是纯烧钱
                maxLoopsOverride = MAX_SCHEDULED_LOOPS
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("run_agent 异常：${e.javaClass.simpleName}: ${e.message}")
            DesktopNotifier.notify("AI 任务失败", e.message ?: "未知错误")
            return
        }

        // 取最后一条 assistant 正文作为结果
        val content = result.lastOrNull {
            it.role == "assistant" && it.type == "text" && it.content.isNotBlank()
        }?.content

        if (content == null) {
            DesktopNotifier.notify("AI 任务失败", "模型未返回内容")
            return
        }

        log("run_agent 完成：${content.take(200)}")

        // 结果回流：写入会话
        if (task.sessionId.isNotBlank()) {
            val session = withContext(Dispatchers.IO) { DesktopSessionStore.load(task.sessionId) }
            if (session != null) {
                val msg = ChatMessage(
                    role = "assistant",
                    content = content,
                    // agent_interject：轮次边界标记，分组时独立成句，不并入上一轮工作过程
                    type = "agent_interject"
                )
                withContext(Dispatchers.IO) {
                    DesktopSessionStore.save(
                        session.copy(
                            messages = session.messages + msg,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
                log("结果已写入会话 ${task.sessionId}")
                // 通知 UI：当前正开着这个会话时立即把消息显示出来
                _agentResults.tryEmit(AgentResultEvent(task.sessionId, msg))
            } else {
                log("会话不存在，跳过写回：${task.sessionId}")
            }
        }

        DesktopNotifier.notify("AI 已回复", content.take(200))
    }

    /**
     * 非流式 chat/completions 调用（与 SessionSummarizer 同套路）。
     *
     * 保留作为轻量入口（不需要工具/多轮的场景），已支持 Anthropic 协议。
     */
    private fun callAgentModel(
        apiKey: String,
        baseUrl: String,
        model: String,
        temperature: Double,
        prompt: String,
        protocol: String = ""
    ): String? {
        val isAnthropic = ApiProtocol.normalize(protocol) == ApiProtocol.ANTHROPIC
        val systemText = "你是一个定时任务助手。根据用户的提示生成简洁的回复。不要使用工具。"

        val body = if (isAnthropic) {
            // Anthropic：system 在顶层，消息里不能有 role=system
            AnthropicCompat.buildRequestBody(
                model = model,
                openAiMessages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemText)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                },
                openAiTools = JSONArray(),
                temperature = temperature,
                maxTokens = 1024,
                stream = false
            )
        } else {
            JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemText)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("stream", false)
                put("temperature", temperature)
                put("max_tokens", 1024)
            }
        }

        val requestBuilder = Request.Builder()
            .url(baseUrl)
            .addHeader("Content-Type", "application/json")
        if (isAnthropic) {
            requestBuilder.addHeader("x-api-key", apiKey)
            requestBuilder.addHeader("anthropic-version", AnthropicCompat.API_VERSION)
        } else {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }
        val request = requestBuilder
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                log("run_agent API 错误：HTTP ${resp.code}")
                return null
            }
            val raw = resp.body.string()
            if (isAnthropic) {
                val (text, _) = AnthropicCompat.parseNonStreamResponse(raw)
                return text.takeIf { it.isNotBlank() }
            }
            val json = JSONObject(raw)
            val msg = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            val text = msg?.optString("content", "").orEmpty()
            if (text.isNotBlank()) return text
            // 推理模型正文为空时回退思考内容（聊胜于无）
            return msg?.optString("reasoning_content", "").orEmpty().takeIf { it.isNotBlank() }
        }
    }

    /**
     * 定时任务的最大工具循环轮数。
     *
     * 定时任务无人盯着，跑飞了就是纯烧钱，所以硬限 8 轮。
     * 比主会话默认值小得多。
     */
    private const val MAX_SCHEDULED_LOOPS = 8

    private fun log(msg: String) {
        DesktopLogger.info("TaskScheduler", msg)
    }
}