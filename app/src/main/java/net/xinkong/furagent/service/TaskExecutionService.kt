package net.xinkong.furagent.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.data.ScheduleStore
import net.xinkong.furagent.data.ScheduledTask
import net.xinkong.furagent.data.SettingsStore
import net.xinkong.furagent.system.NotificationHelper
import net.xinkong.furagent.system.ShizukuHelper
import net.xinkong.furagent.tools.ToolRegistry
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * 定时任务执行服务。
 *
 * 【为什么需要】
 *  TaskReceiver 是 BroadcastReceiver，goAsync() 只有 10 秒窗口。
 *  run_workflow / run_cron / ffmpeg 这类任务可能跑几分钟到几小时，必然超时被 KILL。
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

    // ============================================================
    // 主逻辑（从 TaskReceiver 搬过来）
    // ============================================================

    private suspend fun handleIntent(intent: Intent) {
        val taskId = intent.getStringExtra("task_id")
        val fallbackAction = intent.getStringExtra("action") ?: "open_app"

        val storedTask: ScheduledTask? = if (taskId != null) {
            withContext(Dispatchers.IO) {
                ScheduleStore.list(this@TaskExecutionService).firstOrNull { it.id == taskId }
            }
        } else null

        val action = storedTask?.action ?: fallbackAction
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
            "run_cron" -> executeCron(intent, storedTask, appName)
            "send_notification" -> executeNotification(intent, storedTask)
            "run_workflow" -> executeWorkflow(intent, storedTask)
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
                ScheduleStore.add(this@TaskExecutionService, updated)
                ScheduleStore.scheduleAlarm(this@TaskExecutionService, updated)
            }
            AgentLogger.log(
                "SYS",
                "[定时任务] 已排下次：" + SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", Locale.getDefault()
                ).format(Date(next))
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

    // ============================================================
    // 前台通知
    // ============================================================

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

    // ============================================================
    // WakeLock
    // ============================================================

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