package net.xinkong.furagent.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import net.xinkong.furagent.core.AgentLogger

object NotificationHelper {
    private const val CHANNEL_ID = "furagent_tasks"
    private const val CHANNEL_NAME = "定时任务"
    private const val LIVE_NOTIFICATION_ID = 2002

    /**
     * 通知图标。
     * 想换成自己的图标，把 R.drawable.ic_notify_agent 换成你自己的资源即可
     * （放在 app/src/main/res/drawable/ 下，最好是单色矢量图）。
     */
    private const val ICON_AGENT = android.R.drawable.stat_notify_chat

    private val isLiveUpdateSupported: Boolean
        get() = Build.VERSION.SDK_INT >= 36

    fun ensureChannel(context: Context) {
        try {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "定时任务触发提醒" }
                mgr.createNotificationChannel(ch)
            }
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "创建通知渠道失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    fun showTaskNotification(context: Context, packageName: String, appName: String, tip: String = "") {
        try {
            ensureChannel(context)
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            var contentIntent: PendingIntent? = null
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                contentIntent = PendingIntent.getActivity(
                    context,
                    packageName.hashCode(),
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val text = if (tip.isBlank()) "点击打开 $appName" else "$tip $appName"
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("定时任务触发")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            if (contentIntent != null) builder.setContentIntent(contentIntent)
            try {
                NotificationManagerCompat.from(context).notify(
                    System.currentTimeMillis().toInt(), builder.build()
                )
            } catch (_: SecurityException) {
                // 无通知权限时的正常路径，静默
            }
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "发送任务通知失败（$appName）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    fun showGeneric(context: Context, title: String, content: String) {
        try {
            ensureChannel(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .build()
            try {
                NotificationManagerCompat.from(context).notify(
                    System.currentTimeMillis().toInt(), notification
                )
            } catch (_: SecurityException) {
                // 无通知权限时的正常路径，静默
            }
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "发送通知失败（$title）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    /** 工具调用通知，每次新 ID，累积多条。 */
    fun showToolNotification(context: Context, title: String, content: String) {
        try {
            ensureChannel(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(ICON_AGENT)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .build()
            try {
                NotificationManagerCompat.from(context).notify(
                    System.currentTimeMillis().toInt(), notification
                )
            } catch (_: SecurityException) {
                // 无通知权限时的正常路径，静默
            }
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "发送工具通知失败（$title）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    /**
     * 实时通知。
     *
     * Android 16+ 上会请求系统提升为原生"实时通知"（Live Updates）。
     * 低版本自动降级为普通持续通知。
     */
    fun showLiveActivity(context: Context, title: String, content: String) {
        try {
            ensureChannel(context)
            val appIntent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
            var pi: PendingIntent? = null
            if (appIntent != null) {
                appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                pi = PendingIntent.getActivity(
                    context, 0, appIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(ICON_AGENT)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))

            if (pi != null) builder.setContentIntent(pi)

            if (isLiveUpdateSupported) {
                try {
                    builder.setRequestPromotedOngoing(true)
                } catch (e: Throwable) {
                    // 兼容老版本 core 库；真的没有就降级
                    AgentLogger.log(
                        "SYS",
                        "请求提升为实时通知失败，降级为普通持续通知：${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }

            try {
                NotificationManagerCompat.from(context).notify(LIVE_NOTIFICATION_ID, builder.build())
            } catch (_: SecurityException) {
                // 无通知权限时的正常路径，静默
            }
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "发送实时通知失败（$title）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    /**
     * 【B15】接收文件通知：点击打开文件。
     * 用 FileProvider 生成 content:// URI，让系统各 app 能读。
     */
    fun showFileReceivedNotification(context: Context, file: java.io.File) {
        try {
            ensureChannel(context)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, guessMime(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pi = PendingIntent.getActivity(
                context, file.absolutePath.hashCode(), openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val size = if (file.length() < 1024) "${file.length()}B"
            else if (file.length() < 1024 * 1024) "%.1fKB".format(file.length() / 1024.0)
            else "%.1fMB".format(file.length() / 1024.0 / 1024.0)
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("文件已接收")
                .setContentText("${file.name}（$size）")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pi)
            try {
                NotificationManagerCompat.from(context).notify(
                    file.absolutePath.hashCode(), builder.build()
                )
            } catch (_: SecurityException) { }
        } catch (e: Exception) {
            AgentLogger.log(
                "SYS",
                "发送接收文件通知失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        else -> "*/*"
    }

    fun clearLiveActivity(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(LIVE_NOTIFICATION_ID)
        } catch (_: Exception) {
            // 取消失败无所谓，静默
        }
    }
}