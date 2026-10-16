package net.xinkong.furagent.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import net.xinkong.furagent.MainActivity
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.core.AgentRunner
import net.xinkong.furagent.system.NotificationHelper
import kotlin.time.Duration.Companion.milliseconds

class AgentService : Service() {

    companion object {
        private const val CHANNEL_ID = "furagent_agent_task"
        private const val CHANNEL_NAME = "AI 任务"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "FurAgent:AgentService"

        /**
         * WakeLock 兜底超时（10 分钟）。
         * 正常情况下由 releaseWakeLock() 显式释放。
         * 万一 release 因为异常没走到，超时后系统会自动释放，避免泄漏。
         */
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

        fun start(context: Context) {
            try {
                val intent = Intent(context, AgentService::class.java)
                context.startForegroundService(intent)
            } catch (e: Exception) {
                AgentLogger.log("ERR", "启动 AgentService 失败：${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, AgentService::class.java))
            } catch (e: Exception) {
                AgentLogger.log("ERR", "停止 AgentService 失败：${e.message}")
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AgentLogger.log("SYS", "AgentService onCreate")
        createChannel()
        try {
            startForeground(NOTIFICATION_ID, buildNotification("AI 正在启动..."))
            foregroundStarted = true
        } catch (e: Exception) {
            AgentLogger.log("ERR", "startForeground 失败：${e.javaClass.simpleName}: ${e.message}")
        }

        serviceScope.launch {
            AgentRunner.snapshot.collectLatest { snap ->
                if (snap.isRunning) {
                    if (wakeLock?.isHeld != true) {
                        acquireWakeLock()
                    }
                    updateNotification(buildStateText(snap))
                } else {
                    delay(600.milliseconds)
                    if (!AgentRunner.isRunning()) {
                        releaseWakeLock()
                        NotificationHelper.clearLiveActivity(this@AgentService)
                        stopSelf()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AgentRunner.isRunning()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AgentLogger.log("SYS", "AgentService onDestroy")
        serviceScope.cancel()
        releaseWakeLock()
        if (foregroundStarted) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                AgentLogger.log(
                    "SYS",
                    "stopForeground 失败（可忽略）：${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
        super.onDestroy()
    }

    private fun buildStateText(snap: AgentRunner.Snapshot): String {
        return when {
            snap.currentToolName.isNotBlank() -> "正在调用：${snap.currentToolName}"
            snap.thinking.isNotBlank() -> snap.thinking.take(60)
            snap.streaming.isNotBlank() -> "正在生成回复…"
            else -> "处理中…"
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                // 带超时：即使 release 失败，10 分钟后系统也会自动释放
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            AgentLogger.log("SYS", "WakeLock 已获取（超时 ${WAKE_LOCK_TIMEOUT_MS / 1000}s）")
        } catch (e: Exception) {
            AgentLogger.log("ERR", "获取 WakeLock 失败：${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    AgentLogger.log("SYS", "WakeLock 已释放")
                }
            }
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "释放 WakeLock 失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
        wakeLock = null
    }

    private fun createChannel() {
        try {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "AI 执行长任务时的进度通知"
                    setShowBadge(false)
                    enableVibration(false)
                    enableLights(false)
                }
                mgr.createNotificationChannel(ch)
            }
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "创建 AgentService 通知渠道失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("FurAgent 正在工作")
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
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "更新任务通知失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }
}