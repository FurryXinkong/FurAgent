package net.xinkong.furagent.system

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import net.xinkong.furagent.bridge.PcBridgeClient
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.data.NotificationSyncSettings
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 通知监听服务。
 *
 * 两个职责：
 *  1. 让 MediaSessionManager.getActiveSessions() 有权限（通知使用权凭证）
 *  2. 缓存最近的通知，供 AI 通过 `notifications` 工具读取
 *
 * 系统在用户授权"通知使用权"后会自动 bind 这个 Service。
 * App 不需要手动启动。
 */
class MediaSessionListenerService : NotificationListenerService() {

    companion object {
        @Volatile
        var connected: Boolean = false
            private set

        /** 最多保留 100 条通知 */
        private const val MAX_CACHED = 100

        private val cache = ConcurrentLinkedDeque<NotifEntry>()

        data class NotifEntry(
            val key: String,
            val packageName: String,
            val appName: String,
            val title: String,
            val text: String,
            val timestamp: Long,
            val isOngoing: Boolean
        )

        fun listRecent(limit: Int): List<NotifEntry> {
            val snapshot = cache.toList().sortedByDescending { it.timestamp }
            return snapshot.take(limit.coerceIn(1, MAX_CACHED))
        }

        fun clearCache() {
            cache.clear()
        }

        fun size(): Int = cache.size
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        AgentLogger.log("MEDIA", "MediaSessionListenerService 已连接")

        // 连接后主动同步一次当前所有活跃通知
        try {
            activeNotifications?.forEach { sbn ->
                appendNotification(sbn)
            }
        } catch (e: Exception) {
            AgentLogger.log(
                "MEDIA",
                "同步活跃通知失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
        AgentLogger.log("MEDIA", "MediaSessionListenerService 已断开")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            appendNotification(sbn)
        } catch (e: Exception) {
            AgentLogger.log(
                "MEDIA",
                "处理通知失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            val key = sbn.key ?: return
            cache.removeIf { it.key == key }
        } catch (e: Exception) {
            AgentLogger.log(
                "MEDIA",
                "移除通知失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun appendNotification(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val extras = n.extras ?: return

        val pkg = sbn.packageName ?: ""
        // 过滤自己发的通知
        if (pkg == applicationContext.packageName) return

        // 截断 title/text：EXTRA_BIG_TEXT 可能几百字，100 条缓存会塞几十 KB 的 WebSocket 消息
        // 到电脑端。在手机端截断比两端都处理更省带宽。
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()?.trim().orEmpty().take(200)
        val text = (
                extras.getCharSequence(Notification.EXTRA_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                )?.toString()?.trim().orEmpty().take(500)

        if (title.isBlank() && text.isBlank()) return

        val appName = try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }

        val key = sbn.key ?: "$pkg:$title:$text:${sbn.postTime}"

        // 去重：同 key 覆盖
        cache.removeIf { it.key == key }

        val entry = NotifEntry(
            key = key,
            packageName = pkg,
            appName = appName,
            title = title,
            text = text,
            timestamp = sbn.postTime,
            isOngoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        )
        cache.addFirst(entry)
        while (cache.size > MAX_CACHED) {
            cache.pollLast()
        }

        // 通知同步：开关打开且已连接电脑时，实时转发
        forwardToPc(entry)
    }

    /**
     * 把通知转发给电脑。
     *
     * 只在开关打开 + 已连接时发送；任何异常都吞掉，
     * 不能因为转发失败影响通知缓存这个主职责。
     */
    private fun forwardToPc(entry: NotifEntry) {
        try {
            if (!NotificationSyncSettings.isPhoneToPcEnabled()) return
            if (!PcBridgeClient.isConnected()) return
            PcBridgeClient.sendNotification(
                appName = entry.appName,
                packageName = entry.packageName,
                title = entry.title,
                text = entry.text,
                postTime = entry.timestamp,
                notifKey = entry.key
            )
        } catch (e: Exception) {
            AgentLogger.log(
                "NOTIFY",
                "转发通知到电脑失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }
}