package net.xinkong.furagent.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import net.xinkong.furagent.data.AppSettings
import net.xinkong.furagent.data.ChatMessage
import net.xinkong.furagent.data.ModelProfile
import net.xinkong.furagent.service.AgentService
import net.xinkong.furagent.system.NotificationHelper
import net.xinkong.furagent.system.SpeechHelper
import net.xinkong.furagent.tools.ToolRegistry
import kotlin.time.Duration.Companion.milliseconds

/**
 * Agent 任务运行器。
 *
 * 关键设计：
 *  - Application 级 CoroutineScope，不受 Activity 生命周期影响
 *  - 状态通过 StateFlow 暴露，UI 只订阅
 *  - 每次启动都会拉起前台服务 AgentService
 *  - TTS 自动朗读由 AgentRunner 负责，切后台也能读
 *  - taskToken 机制：cancel 后旧任务的所有回调自动失效，
 *    避免"取消后立刻发新消息 → 旧任务 finally 覆盖新任务状态"
 *
 * 【修复 18】cancel() 里三个清理调用（SpeechHelper.stop / clearLiveActivity /
 * AgentService.stop）原来合并在一个 try 里。如果第一个抛异常，后两个不会执行，
 * AgentService 会永远活着占用前台通知。拆成三段独立 try。
 *
 * 【修复 A8】onFinish 回调里会改 MainActivity 的 Compose State（chatHistory、
 * sessionList 等），必须在主线程执行。AgentRunner 的 scope 是 Default
 * Dispatcher，所以所有 Compose 相关回调统一用 mainHandler.post 包住。
 *
 * 【修复 C7】删掉传给 runAgentTurn 的 prefs 参数（历史上从未被使用）。
 */
object AgentRunner {

    private val RE_STRIP_URL = Regex("""https?://\S+""")
    private val RE_STRIP_PATH = Regex("""/\S+""")
    private val RE_STRIP_MARKDOWN = Regex("""[*_`#>\[\]()!]+""")

    data class Snapshot(
        val sessionId: String = "",
        val history: List<ChatMessage> = emptyList(),
        val thinking: String = "",
        val reasoning: String = "",
        val streaming: String = "",
        val isRunning: Boolean = false,
        val currentToolName: String = "",
        val startedAt: Long = 0L,
        /**
         * 【错误状态】API 错误信息（如「HTTP 429 · 用量配额已用尽」）。
         *
         * 与 thinking 分开存的原因：
         *   - thinking 在 onFinish 时会被清空（任务结束就不该再转圈）
         *   - error 需要保留到用户下次发消息，否则错误一闪而过看不到
         *
         * 渲染成状态小气泡（与「重试中」同款），不是 AI 聊天气泡。
         */
        val error: String = ""
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var currentJob: Job? = null

    @Volatile
    private var appContext: Context? = null

    /**
     * 任务代际（给协程回调用）。
     *
     * 【P2-19 复核结论：不是冗余，不需要合并】
     * 与 AgentSession.generation 是两套独立机制：
     *  - 本字段：cancel() 时 +1，isCurrent() 判断旧任务回调是否该丢弃
     *  - AgentSession.generation：cancel() 时同步 +1，阻塞 IO 引擎用 isCancelled(gen) 感知取消
     * 两者职责不同，缺一不可。详见 AgentSession.generation 的注释。
     */
    @Volatile
    private var taskToken: Long = 0L

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    fun isRunning(): Boolean = currentJob?.isActive == true

    init {
        scope.launch {
            snapshot
                .map { Triple(it.isRunning, it.currentToolName, it.sessionId.isNotBlank()) }
                .distinctUntilChanged()
                .collect { (isRunning, toolName, _) ->
                    val ctx = appContext ?: return@collect
                    val prefs = ctx.getSharedPreferences(
                        "FurAgentPrefs", Context.MODE_PRIVATE
                    )
                    val mode = prefs.getString("notification_mode", "off") ?: "off"

                    if (mode != "live") {
                        NotificationHelper.clearLiveActivity(ctx)
                        return@collect
                    }

                    if (isRunning) {
                        val content = if (toolName.isNotBlank()) {
                            "正在调用：$toolName"
                        } else {
                            "正在回复…"
                        }
                        NotificationHelper.showLiveActivity(
                            ctx, "FurAgent正在工作", content
                        )
                    } else {
                        NotificationHelper.clearLiveActivity(ctx)
                    }
                }
        }
    }

    /** 用户主动处理了错误（手动关闭气泡 / 换模型）时清掉错误状态，无需等到下次发消息。 */
    fun clearError() {
        _snapshot.value = _snapshot.value.copy(error = "")
    }

    fun start(
        context: Context,
        sessionId: String,
        profile: ModelProfile,
        initialHistory: List<ChatMessage>,
        maxTurns: Int,
        prefs: SharedPreferences,
        settings: AppSettings,
        onFinish: (finalHistory: List<ChatMessage>) -> Unit = {},
        /** 【P0-3】会话专用提示词：注入系统提示词末尾，只作用于当前会话。 */
        sessionPrompt: String = ""
    ): Boolean {
        if (currentJob?.isActive == true) {
            AgentLogger.log("SYS", "已有 Agent 任务在运行，忽略本次启动")
            return false
        }

        val ctx = context.applicationContext
        appContext = ctx

        val myToken = ++taskToken
        // 同步 AgentSession 代际：让阻塞式 IO（下载等）能感知本次任务
        AgentSession.beginTask()
        // 记录主任务会话：子会话完成后「子代理完成」通知回流的目标
        net.xinkong.furagent.tools.ToolRegistry.CurrentTaskSession.id = sessionId

        // 【错误消失】新一轮任务开始，把上一次的 API 错误（如 HTTP 429）清掉。
        // Snapshot(...) 重建时 error 默认就是空，但旧代码在运行中会 copy() 保留旧值，
        // 这里显式保证一下。
        _snapshot.value = Snapshot(
            sessionId = sessionId,
            history = initialHistory,
            isRunning = true,
            error = "",
            startedAt = System.currentTimeMillis()
        )

        AgentService.start(ctx)

        currentJob = scope.launch {
            fun isCurrent(): Boolean = myToken == taskToken

            // 【取消代际】与 taskToken 同步：cancel() 里 taskToken++ 的同时
            // AgentSession.cancelAll() 也会 generation++，两者一致。
            // 传给 runAgentTurn 后，工具执行前会比对代际，
            // 避免“UI 已停止但工具还在跑”。
            val cancelGen = AgentSession.currentGeneration()

            try {
                runAgentTurn(
                    profile = profile,
                    history = initialHistory,
                    maxTurns = maxTurns,
                    context = ctx,
                    settings = settings,
                    cancelGen = cancelGen,
                    sessionPrompt = sessionPrompt,
                    onHistoryChanged = { newHistory ->
                        if (!isCurrent()) return@runAgentTurn
                        val oldSize = _snapshot.value.history.size
                        val lastToolName = extractToolName(newHistory)
                        _snapshot.value = _snapshot.value.copy(
                            history = newHistory,
                            currentToolName = lastToolName
                        )
                        if (settings.notificationMode == "normal") {
                            checkAndNotifyNormal(ctx, settings, oldSize, newHistory)
                        }
                    },
                    onThinking = { text ->
                        if (!isCurrent()) return@runAgentTurn
                        _snapshot.value = _snapshot.value.copy(thinking = text)
                    },
                    onError = { err ->
                        if (!isCurrent()) return@runAgentTurn
                        // 错误走独立字段：不清 thinking（thinking 由 onFinish 清），
                        // 也不进 history（不进 AI 聊天气泡）
                        _snapshot.value = _snapshot.value.copy(error = err)
                    },
                    onReasoning = { r ->
                        if (!isCurrent()) return@runAgentTurn
                        _snapshot.value = _snapshot.value.copy(
                            reasoning = _snapshot.value.reasoning + r
                        )
                    },
                    onStreamChunk = { chunk ->
                        if (!isCurrent()) return@runAgentTurn
                        _snapshot.value = _snapshot.value.copy(
                            streaming = _snapshot.value.streaming + chunk
                        )
                    },
                    onStreamCommit = {
                        if (!isCurrent()) return@runAgentTurn
                        // reasoning 已随 assistant 消息落库，清空运行时状态避免与消息内面板重复
                        _snapshot.value = _snapshot.value.copy(streaming = "", reasoning = "")
                    },
                    onFinish = finish@{
                        if (!isCurrent()) return@finish

                        val finalHistory = _snapshot.value.history
                        _snapshot.value = _snapshot.value.copy(
                            isRunning = false,
                            currentToolName = "",
                            thinking = "",
                            reasoning = "",
                            streaming = ""
                        )
                        currentJob = null

                        // 【修复 A8】onFinish 会改 MainActivity 的 Compose State，
                        // 必须回主线程执行。
                        mainHandler.post {
                            try {
                                autoSpeakIfEnabled(finalHistory, settings, myToken)
                            } catch (e: Exception) {
                                AgentLogger.log("ERR", "autoSpeak 异常：${e.message}")
                            }

                            try {
                                onFinish(finalHistory)
                            } catch (e: Exception) {
                                AgentLogger.log("ERR", "onFinish 回调异常：${e.message}")
                            }

                            try {
                                NotificationHelper.clearLiveActivity(ctx)
                            } catch (_: Exception) { }
                            try {
                                AgentService.stop(ctx)
                            } catch (_: Exception) { }
                        }
                    }
                )
            } catch (e: CancellationException) {
                if (isCurrent()) {
                    AgentLogger.log("SYS", "Agent 任务被取消")
                    _snapshot.value = _snapshot.value.copy(
                        isRunning = false, currentToolName = "", thinking = "", reasoning = "", streaming = ""
                    )
                    currentJob = null
                    mainHandler.post {
                        try {
                            NotificationHelper.clearLiveActivity(ctx)
                        } catch (_: Exception) { }
                        try {
                            AgentService.stop(ctx)
                        } catch (_: Exception) { }
                    }
                }
                throw e
            } catch (e: Exception) {
                if (isCurrent()) {
                    AgentLogger.log("ERR", "Agent 任务异常：${e.javaClass.simpleName}: ${e.message}")
                    _snapshot.value = _snapshot.value.copy(
                        isRunning = false, currentToolName = "", thinking = "", streaming = ""
                    )
                    currentJob = null
                    mainHandler.post {
                        try {
                            NotificationHelper.clearLiveActivity(ctx)
                        } catch (_: Exception) { }
                        try {
                            AgentService.stop(ctx)
                        } catch (_: Exception) { }
                    }
                }
            }
        }
        return true
    }

    fun cancel(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx

        // token 递增，旧任务的所有回调立刻失效
        taskToken++
        AgentSession.cancelCurrentCall()
        currentJob?.cancel()
        currentJob = null
        _snapshot.value = _snapshot.value.copy(
            isRunning = false, currentToolName = "", thinking = "", streaming = ""
        )

        // 三段清理独立 try，任一失败不阻断后续
        try {
            SpeechHelper.stop()
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "停止 TTS 失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
        try {
            NotificationHelper.clearLiveActivity(ctx)
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "清除实时通知失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
        try {
            AgentService.stop(ctx)
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "停止 AgentService 失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun autoSpeakIfEnabled(
        history: List<ChatMessage>,
        settings: AppSettings,
        myToken: Long
    ) {
        if (!settings.autoSpeakReplies) return
        if (myToken != taskToken) return

        val last = history.lastOrNull {
            it.role == "assistant" && it.type == "text"
        } ?: return
        val text = last.content
        if (text.isBlank()) return
        if (text.startsWith("网络请求失败")) return
        if (text.startsWith("API 错误")) return
        if (text.startsWith("流式解析失败")) return
        if (text.startsWith("发生异常")) return

        // 【P1-抽 shared】统一走 shared/util/SpeechCleaner，与桌面端完全同源
        val clean = net.xinkong.furagent.shared.util.SpeechCleaner.cleanForSpeech(text)
        if (clean.isBlank()) return

        scope.launch {
            delay(150.milliseconds)
            if (myToken != taskToken) return@launch
            SpeechHelper.speak(clean.take(500))
        }
    }

    private fun extractToolName(history: List<ChatMessage>): String {
        for (i in history.indices.reversed()) {
            val m = history[i]
            when {
                m.type == "tool_call" -> return m.content.substringBefore('\n').trim()
                m.role == "assistant" && m.type == "text" -> return ""
            }
        }
        return ""
    }

    private fun checkAndNotifyNormal(
        context: Context,
        settings: AppSettings,
        oldSize: Int,
        newHistory: List<ChatMessage>
    ) {
        if (oldSize < 0) return
        val safeStart = oldSize.coerceAtMost(newHistory.size)
        if (safeStart >= newHistory.size) return

        val newMsgs = newHistory.subList(safeStart, newHistory.size)
        // 【脆弱解构清理】显式字段访问，不依赖 ChatMessage 字段位置顺序。
        for (msg in newMsgs) {
            val content = msg.content
            val type = msg.type
            if (type != "tool_call") continue
            val display = content.substringBefore('\n').trim()
            if (display.isBlank()) continue
            if (settings.notifyTools.isNotEmpty()) {
                val toolName = ToolRegistry.findToolNameByDisplay(display) ?: continue
                if (toolName !in settings.notifyTools) continue
            }
            try {
                NotificationHelper.showToolNotification(context, "工具调用", display)
            } catch (e: Exception) {
                AgentLogger.log("ERR", "发送工具通知失败：${e.message}")
            }
        }
    }
}