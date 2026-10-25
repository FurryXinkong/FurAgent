package net.xinkong.furagent.service

import net.xinkong.furagent.shared.util.DateFormats
import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.core.AgentSession
import net.xinkong.furagent.core.runAgentTurn
import net.xinkong.furagent.data.ChatMessage
import net.xinkong.furagent.data.ScheduleStore
import net.xinkong.furagent.data.ScheduledTask
import net.xinkong.furagent.data.Session
import net.xinkong.furagent.data.SessionStore
import net.xinkong.furagent.data.SettingsStore
import net.xinkong.furagent.data.currentProfile
import net.xinkong.furagent.system.NotificationHelper
import net.xinkong.furagent.system.ShizukuHelper
import net.xinkong.furagent.system.SpeechHelper
import net.xinkong.furagent.tools.ToolRegistry
import org.json.JSONObject
import java.util.*

/**
 * 定时任务执行服务。
 *
 * 【为什么需要】
 *  TaskReceiver 是 BroadcastReceiver，goAsync() 只有 10 秒窗口。
 *  run_workflow / run_command / ffmpeg 这类任务可能跑几分钟到几小时，必然超时被 KILL。
 *  用一个前台服务承载：
 *    - 无时长限制
 *    - 可持 WakeLock 防止 CPU 休眠
 *    - 用户可见进度（前台通知）
 *
 * 【生命周期】
 *  每次触发 startForegroundService → onCreate → onStartCommand(处理) →
 *  执行完 stopSelf。不在 AndroidManifest 里写 STOP 策略，由 Service 自管理。
 *
 * 【并发保护】
 *  taskJob?.isActive 判断：同一时间只跑一个任务。
 *  定时任务本身通常不会重叠（重叠的会被忽略 + 日志记录）。
 */
class TaskExecutionService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "furagent_task_exec"
        private const val CHANNEL_NAME = "定时任务执行"
        private const val WAKE_LOCK_TAG = "FurAgent:TaskExecution"

        /**
         * WakeLock 兜底超时：30 分钟。
         * 比 BroadcastReceiver 的 10 秒宽松得多，覆盖绝大多数 ffmpeg / workflow。
         * 万一 release 失败，超时后系统自动释放，避免泄漏。
         */
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L

        /** 定时任务 AI 结果回流广播：MainActivity 收到后刷新当前会话。 */
        const val ACTION_SCHEDULED_AGENT_RESULT =
            "net.xinkong.furagent.ACTION_SCHEDULED_AGENT_RESULT"

        // 朗读前的文本清洗（与 AgentRunner 保持一致）
        private val RE_STRIP_URL = Regex("""https?://\S+""")
        private val RE_STRIP_PATH = Regex("""/\S+""")
        private val RE_STRIP_MARKDOWN = Regex("""[*_`#>\[\]()!]+""")

        fun start(context: Context, originalIntent: Intent) {
            val intent = Intent(context, TaskExecutionService::class.java).apply {
                putExtras(originalIntent)
            }
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                AgentLogger.log(
                    "ERR",
                    "[定时任务] startForegroundService 失败：${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var taskJob: Job? = null
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            startForeground(NOTIFICATION_ID, buildNotification("正在执行定时任务…"))
            foregroundStarted = true
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "[定时任务] startForeground 失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (taskJob?.isActive == true) {
            AgentLogger.log("SYS", "[定时任务] 已有任务在执行，忽略本次触发")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        taskJob = scope.launch {
            try {
                handleIntent(intent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AgentLogger.log(
                    "ERR",
                    "[定时任务] 未捕获异常：${e.javaClass.simpleName}: ${e.message}"
                )
            } finally {
                // 让通知至少显示 1 秒，避免秒级任务的通知疯狂闪烁
                withContext(NonCancellable) {
                    delay(1000L)
                }
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        if (foregroundStarted) {
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        }
        super.onDestroy()
    }

    // 主逻辑（从 TaskReceiver 搬过来）

    private suspend fun handleIntent(intent: Intent) {
        val taskId = intent.getStringExtra("task_id")
        val fallbackAction = intent.getStringExtra("action") ?: "open_app"

        val storedTask: ScheduledTask? = if (taskId != null) {
            withContext(Dispatchers.IO) {
                ScheduleStore.list(this@TaskExecutionService).firstOrNull { it.id == taskId }
            }
        } else null

        // 动作名归一：旧任务/旧 intent 的 send_notification、run_cron 转为统一命名
        val action = ScheduleStore.normalizeAction(storedTask?.action ?: fallbackAction)
        val appName = storedTask?.appName ?: intent.getStringExtra("app_name").orEmpty()

        AgentLogger.log(
            "SYS",
            "[定时任务] 触发：task=${storedTask?.description ?: taskId} action=$action"
        )

        // 【修复 F6】定时任务和 Agent 是两条独立前台服务，可能同时跑。
        // 不阻止并行（用户可能确实希望），但记一条日志，方便排查资源争抢。
        if (net.xinkong.furagent.core.AgentRunner.isRunning()) {
            AgentLogger.log(
                "SYS",
                "[定时任务] 警告：AgentRunner 正在执行任务，可能与定时任务争抢 CPU / IO。"
            )
        }

        // 禁用的任务直接跳过（正常情况下 setEnabled(false) 时已 cancel alarm，这里兜底）
        if (storedTask != null && !storedTask.enabled) {
            AgentLogger.log("SYS", "[定时任务] 任务已禁用，跳过：${storedTask.id.take(8)}")
            return
        }

        // 条件评估
        if (storedTask != null && storedTask.conditions.isNotEmpty()) {
            val result = withContext(Dispatchers.IO) {
                ScheduleStore.evaluateConditions(this@TaskExecutionService, storedTask)
            }
            if (!result.met) {
                if (storedTask.skipIfNotMet) {
                    AgentLogger.log("SYS", "[定时任务] 条件不满足，跳过本次：${result.reason}")
                    NotificationHelper.showGeneric(
                        this,
                        "任务已跳过",
                        "${storedTask.description.ifBlank { appName }}：${result.reason}"
                    )
                    rescheduleNext(storedTask)
                    return
                } else {
                    AgentLogger.log("SYS", "[定时任务] 条件不满足但已配置强制执行：${result.reason}")
                }
            }
        }

        // 更新前台通知，让用户看到在跑什么
        updateNotification("正在执行：${storedTask?.description?.ifBlank { action } ?: action}")

        when (action) {
            "run_command" -> executeCron(intent, storedTask, appName)
            "notify" -> executeNotification(intent, storedTask)
            "run_workflow" -> executeWorkflow(intent, storedTask)
            "run_agent" -> executeAgent(intent, storedTask)
            "run_tool" -> executeTool(intent, storedTask)
            else -> executeOpenApp(intent, storedTask, appName)
        }

        // 续排下次
        if (storedTask != null) {
            rescheduleNext(storedTask)
        }
    }

    private suspend fun executeCron(intent: Intent, task: ScheduledTask?, appName: String) {
        val command = task?.command ?: intent.getStringExtra("command").orEmpty()
        if (command.isBlank()) return

        val out = withContext(Dispatchers.IO) {
            if (ShizukuHelper.isAvailable() && ShizukuHelper.hasPermission()) {
                ShizukuHelper.executeCommand(command, 60)
            } else null
        }

        if (out != null) {
            AgentLogger.log("SYS", "[定时任务] shell 结果：${out.take(500)}")
            NotificationHelper.showGeneric(this, "定时命令已执行", "$appName：${out.take(200)}")
        } else {
            NotificationHelper.showGeneric(this, "定时命令未执行", "Shizuku 不可用或无权限")
        }
    }

    private suspend fun executeNotification(intent: Intent, task: ScheduledTask?) {
        val title = task?.notificationTitle?.ifBlank { null }
            ?: intent.getStringExtra("notification_title").orEmpty().ifBlank { "FurAgent 提醒" }
        val content = task?.notificationContent?.ifBlank { null }
            ?: intent.getStringExtra("notification_content").orEmpty().ifBlank { "定时任务已触发" }
        NotificationHelper.showGeneric(this, title, content)
        AgentLogger.log("SYS", "[定时任务] 已发送通知：$title")
    }

    private suspend fun executeWorkflow(intent: Intent, task: ScheduledTask?) {
        val name = task?.workflowName?.ifBlank { null }
            ?: intent.getStringExtra("workflow_name").orEmpty()
        if (name.isBlank()) {
            NotificationHelper.showGeneric(this, "工作流未执行", "未提供工作流名称")
            return
        }

        AgentLogger.log("SYS", "[定时任务] 开始执行工作流：$name")

        try {
            val prefs = getSharedPreferences("FurAgentPrefs", Context.MODE_PRIVATE)
            val settings = SettingsStore.load(prefs)
            val args = JSONObject().apply {
                put("op", "run")
                put("name", name)
            }
            val result = withContext(Dispatchers.IO) {
                ToolRegistry.execute("workflow", args, this@TaskExecutionService, settings)
            }
            AgentLogger.log("SYS", "[定时任务] 工作流结果：${result.take(500)}")
            val summary = if (result.startsWith("失败")) result.take(200)
            else "执行完成，输出 ${result.length} 字符"
            NotificationHelper.showGeneric(this, "工作流已执行：$name", summary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "[定时任务] 工作流执行异常：${e.javaClass.simpleName}: ${e.message}"
            )
            NotificationHelper.showGeneric(
                this,
                "工作流执行失败",
                "$name：${e.message ?: "未知错误"}"
            )
        }
    }

    /**
     * 【能力对齐桌面】run_tool 动作执行：调用 ToolRegistry 里的任意工具。
     * 工具名来自 task.toolName，参数是 task.toolArgs（JSON 对象字符串）。
     */
    private suspend fun executeTool(intent: Intent, task: ScheduledTask?) {
        val toolName = task?.toolName?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra("tool_name")?.takeIf { it.isNotBlank() }
        if (toolName == null) {
            NotificationHelper.showGeneric(this, "工具任务未执行", "未提供工具名")
            return
        }
        val argsText = task?.toolArgs?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra("tool_args").orEmpty().ifBlank { "{}" }
        val args = try {
            JSONObject(argsText)
        } catch (e: Exception) {
            NotificationHelper.showGeneric(this, "工具任务失败", "参数不是合法 JSON")
            return
        }

        AgentLogger.log("SYS", "[定时任务] run_tool 开始：$toolName")
        try {
            val prefs = getSharedPreferences("FurAgentPrefs", Context.MODE_PRIVATE)
            val settings = SettingsStore.load(prefs)
            val result = withContext(Dispatchers.IO) {
                ToolRegistry.execute(toolName, args, this@TaskExecutionService, settings)
            }
            AgentLogger.log("SYS", "[定时任务] 工具结果：${result.take(500)}")
            val summary = if (result.startsWith("失败")) result.take(200)
            else "执行完成，输出 ${result.length} 字符"
            NotificationHelper.showGeneric(this, "工具已执行：$toolName", summary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "[定时任务] 工具执行异常：${e.javaClass.simpleName}: ${e.message}"
            )
            NotificationHelper.showGeneric(
                this,
                "工具执行失败",
                "$toolName：${e.message ?: "未知错误"}"
            )
        }
    }

    /**
     * 【AI 主动说话】run_agent 动作执行。
     *
     * 调用 LLM API 获取回复，如果任务绑定了 sessionId，
     * 将结果作为 assistant 消息写回该会话（结果回流）。
     */
    /**
     * 【AI 主动说话】run_agent 动作执行。
     *
     * 【与桌面端对齐】旧实现是手写的非流式 HTTP 单轮问答，导致三个问题：
     *   1. 没有工具能力（AI 只能空口说话，不能查天气/读文件/发消息）
     *   2. 没有记忆、没有系统提示词、没有会话上下文
     *   3. 结果只发通知，不朗读 —— 用户问的「为什么只能通知不能说话」就是这个
     *
     * 现在改为复用 runAgentTurn（与聊天页完全同一条链路）：
     *   - 完整 Agent 循环：工具调用、记忆、系统提示词、多轮
     *   - 绑定了 sessionId 时，把该会话历史作为上下文，结果追加回该会话
     *   - 执行完按 autoSpeakReplies 用 TTS 朗读（这就是「让 AI 说话」）
     *   - 广播通知 UI 实时刷新
     */
    private suspend fun executeAgent(intent: Intent, task: ScheduledTask?) {
        val prompt = task?.prompt?.ifBlank { null }
            ?: intent.getStringExtra("prompt").orEmpty().ifBlank { null }
        if (prompt == null) {
            NotificationHelper.showGeneric(this, "AI 任务未执行", "未提供 prompt")
            return
        }

        val prefs = getSharedPreferences("FurAgentPrefs", Context.MODE_PRIVATE)
        val settings = SettingsStore.load(prefs)
        val profile = settings.currentProfile()
        if (profile == null || profile.apiKey.isBlank()) {
            NotificationHelper.showGeneric(this, "AI 任务未执行", "未配置模型或 API Key")
            return
        }

        val sessionId = task?.sessionId.orEmpty()
        AgentLogger.log(
            "SYS",
            "[定时任务] run_agent 开始（${profile.name}，会话=${sessionId.ifBlank { "无" }}）：${prompt.take(100)}"
        )

        // 绑定会话时以该会话历史为上下文；未绑定则空历史（纯问答）
        val baseHistory: List<ChatMessage> = if (sessionId.isNotBlank()) {
            withContext(Dispatchers.IO) { SessionStore.load(this@TaskExecutionService, sessionId) }
                ?.messages.orEmpty()
        } else emptyList()

        // 把本次 prompt 作为 user 消息追加
        val history = baseHistory + ChatMessage(role = "user", content = prompt)

        // 与 AgentRunner 一致：开始任务时拿代际，工具执行前会比对
        val cancelGen = AgentSession.beginTask()

        var finalHistory: List<ChatMessage> = history
        var errorText = ""

        // 【P0-3】会话专用提示词：与聊天页 AgentRunner.start 保持一致
        val sessionPrompt = if (sessionId.isNotBlank()) {
            withContext(Dispatchers.IO) { SessionStore.load(this@TaskExecutionService, sessionId) }
                ?.systemPrompt ?: ""
        } else ""

        try {
            runAgentTurn(
                profile = profile,
                history = history,
                maxTurns = settings.maxToolCallLoops.coerceAtLeast(1),
                context = this@TaskExecutionService,
                settings = settings,
                cancelGen = cancelGen,
                onHistoryChanged = { h -> finalHistory = h },
                onThinking = { },
                onError = { err -> errorText = err },
                onReasoning = { },
                onStreamChunk = { },
                onStreamCommit = { },
                onFinish = { },
                sessionPrompt = sessionPrompt
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AgentLogger.log("ERR", "[定时任务] run_agent 异常：${e.javaClass.simpleName}: ${e.message}")
            NotificationHelper.showGeneric(this, "AI 任务失败", e.message ?: "未知错误")
            return
        }

        if (errorText.isNotBlank()) {
            AgentLogger.log("ERR", "[定时任务] run_agent 失败：$errorText")
            NotificationHelper.showGeneric(this, "AI 任务失败", errorText.take(200))
            return
        }

        // 取本轮最后一条 AI 正文（跳过工具消息 / 思考消息）
        val reply = finalHistory.lastOrNull {
            it.role == "assistant" && it.type == "text" && it.content.isNotBlank()
        }?.content.orEmpty()

        if (reply.isBlank()) {
            AgentLogger.log("ERR", "[定时任务] run_agent 未产出正文")
            NotificationHelper.showGeneric(this, "AI 任务失败", "模型未返回内容")
            return
        }

        AgentLogger.log("SYS", "[定时任务] run_agent 完成：${reply.take(200)}")

        // 结果回流：整轮历史写回会话（含工具调用过程，与聊天页一致）
        if (sessionId.isNotBlank()) {
            val written = withContext(Dispatchers.IO) {
                writeAgentResultToSession(sessionId, finalHistory)
            }
            if (written) {
                val refreshIntent = Intent(ACTION_SCHEDULED_AGENT_RESULT).apply {
                    setPackage(packageName)
                    putExtra("session_id", sessionId)
                }
                sendBroadcast(refreshIntent)
                AgentLogger.log("SYS", "[定时任务] 结果已写入会话 $sessionId")
            }
        }

        // 【让 AI 说话】与聊天页 autoSpeakReplies 同一开关、同一实现
        speakIfEnabled(settings.autoSpeakReplies, reply)

        NotificationHelper.showGeneric(this, "AI 已回复", reply.take(200))
    }

    /**
     * 朗读 AI 回复。
     *
     * 与 AgentRunner.autoSpeakIfEnabled 保持一致的清洗规则：
     * 去掉 URL / 路径 / Markdown 符号，避免 TTS 念出 "https 冒号 斜杠斜杠"。
     */
    private fun speakIfEnabled(enabled: Boolean, text: String) {
        if (!enabled) return
        // 【P1-抽 shared】统一走 shared/util/SpeechCleaner，与桌面端完全同源
        val clean = net.xinkong.furagent.shared.util.SpeechCleaner.cleanForSpeech(text)
        if (clean.isBlank()) return

        // TTS 引擎可能还没初始化（进程被闹钟冷启动时 MainActivity 没跑过）
        if (!SpeechHelper.isReady()) {
            SpeechHelper.init(applicationContext)
            AgentLogger.log("SYS", "[定时任务] TTS 未就绪，已触发初始化，本次跳过朗读")
            return
        }
        val ok = SpeechHelper.speakSegmented(clean.take(500))
        AgentLogger.log(
            "SYS",
            if (ok) "[定时任务] 已朗读回复（${clean.length} 字）"
            else "[定时任务] 朗读失败"
        )
    }

    /**
     * 把本轮完整历史写回会话。
     *
     * 【修复】旧实现手写 sessions/$id.json，绕过了 SessionStore 的索引维护和
     * 500ms 节流，导致：会话列表的 messageCount / updatedAt 不更新、
     * 与聊天页并发写时互相覆盖。现在统一走 SessionStore.save。
     *
     * @return true 写入成功，false 会话不存在或写入失败
     */
    private fun writeAgentResultToSession(sessionId: String, history: List<ChatMessage>): Boolean {
        return try {
            val session: Session = SessionStore.load(this, sessionId) ?: run {
                AgentLogger.log("ERR", "[定时任务] 会话不存在，跳过写回：$sessionId")
                return false
            }
            SessionStore.save(
                this,
                session.copy(
                    messages = history,
                    updatedAt = System.currentTimeMillis()
                )
            )
            true
        } catch (e: Exception) {
            AgentLogger.log("ERR", "[定时任务] 写入会话结果失败：${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private suspend fun executeOpenApp(intent: Intent, task: ScheduledTask?, appName: String) {
        val pkg = task?.packageName?.ifBlank { null }
            ?: intent.getStringExtra("package_name").orEmpty()
        if (pkg.isBlank()) return

        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent == null) {
            AgentLogger.log("ERR", "[定时任务] 找不到应用 $pkg")
            NotificationHelper.showTaskNotification(this, pkg, appName, "应用未安装")
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        var autoOpened = false
        if (Settings.canDrawOverlays(this)) {
            try {
                startActivity(launchIntent)
                autoOpened = true
            } catch (e: Exception) {
                AgentLogger.log("ERR", "[定时任务] 悬浮窗打开失败：${e.message}")
            }
        }
        if (!autoOpened && ShizukuHelper.isAvailable() && ShizukuHelper.hasPermission()) {
            if (ShizukuHelper.startActivityByPackage(pkg)) autoOpened = true
        }
        if (!autoOpened) {
            NotificationHelper.showTaskNotification(
                this,
                pkg,
                appName,
                "系统限制了后台启动，点击打开"
            )
        }
    }

    private suspend fun rescheduleNext(task: ScheduledTask) {
        val next = withContext(Dispatchers.IO) {
            ScheduleStore.calculateNextTrigger(task)
        }
        if (next != null) {
            val updated = task.copy(triggerTime = next)
            withContext(Dispatchers.IO) {
                // 【性能】只改 triggerTime，不再 add() 全量重写整个 schedules.json
                ScheduleStore.updateTriggerTime(this@TaskExecutionService, task.id, next)
                ScheduleStore.scheduleAlarm(this@TaskExecutionService, updated)
            }
            AgentLogger.log(
                "SYS",
                "[定时任务] 已排下次：" + DateFormats.formatDateTime(next)
            )
        } else if (task.repeatMode == "cron") {
            // 【体验优化 B8】cron 非法：不删任务，日志 + 通知，让用户改
            AgentLogger.log(
                "ERR",
                "[定时任务] cron 表达式可能非法（${task.cronExpression}），已保留但暂停调度：${task.id.take(8)}"
            )
            NotificationHelper.showGeneric(
                this,
                "定时任务暂停",
                "「${task.description.ifBlank { task.id.take(8) }}」cron 表达式可能非法"
            )
        } else {
            withContext(Dispatchers.IO) {
                ScheduleStore.remove(this@TaskExecutionService, task.id)
            }
            AgentLogger.log("SYS", "[定时任务] 任务完成，已移除：${task.id}")
        }
    }

    // 前台通知

    private fun createChannel() {
        try {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "定时任务执行时的进度通知"
                    setShowBadge(false)
                    enableVibration(false)
                    enableLights(false)
                }
                mgr.createNotificationChannel(ch)
            }
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "[定时任务] 创建渠道失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, net.xinkong.furagent.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("FurAgent 定时任务")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) { }
    }

    // WakeLock

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            AgentLogger.log(
                "SYS",
                "[定时任务] WakeLock 已获取（超时 ${WAKE_LOCK_TIMEOUT_MS / 1000}s）"
            )
        } catch (e: Exception) {
            AgentLogger.log("ERR", "[定时任务] 获取 WakeLock 失败：${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    AgentLogger.log("SYS", "[定时任务] WakeLock 已释放")
                }
            }
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "[定时任务] 释放 WakeLock 失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
        wakeLock = null
    }
}