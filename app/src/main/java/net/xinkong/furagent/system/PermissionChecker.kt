package net.xinkong.furagent.system

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.tools.MediaTools

data class PermissionItem(
    val key: String,
    val title: String,
    val description: String,
    val granted: Boolean,
    val onOpenSettings: (Context) -> Unit
)

fun notifyToast(context: Context, message: String) {
    try {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    } catch (_: Exception) { }
}

fun notifyToastLong(context: Context, message: String) {
    try {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    } catch (_: Exception) { }
}

object PermissionChecker {

    fun checkAll(context: Context): List<PermissionItem> = listOf(
        notificationPermission(context),
        overlayPermission(context),
        exactAlarmPermission(context),
        fileAccessPermission(context),
        batteryOptimization(context),
        locationPermission(context),
        notificationListenerPermission(context),
        accessibilityPermission(context),
        shizukuPermission(context)
    )

    // ── 通用 helper：打开系统设置页 ────────────────────────

    /** 打开指定 Action 的系统设置页，自动附加 package URI 和 NEW_TASK 标志。 */
    private fun openSettings(ctx: Context, action: String, data: String? = null): Boolean {
        val intent = Intent(action).apply {
            data?.let { this.data = it.toUri() }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return safeStart(ctx, intent)
    }

    /** 打开应用详情页（通用兜底）。 */
    private fun openAppDetails(ctx: Context): Boolean =
        openSettings(ctx, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${ctx.packageName}")

    // ── 权限检查 ──────────────────────────────────────────

    private fun notificationPermission(context: Context) = PermissionItem(
        key = "notification",
        title = "通知权限",
        description = "定时任务触发时弹通知提醒",
        granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED,
        onOpenSettings = { ctx ->
            openSettings(ctx, Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        }
    )

    private fun overlayPermission(context: Context) = PermissionItem(
        key = "overlay",
        title = "悬浮窗权限",
        description = "后台定时打开应用绕过系统限制，以及悬浮球",
        granted = Settings.canDrawOverlays(context),
        onOpenSettings = { ctx ->
            openSettings(ctx, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${ctx.packageName}")
        }
    )

    private fun exactAlarmPermission(context: Context) = PermissionItem(
        key = "exact_alarm",
        title = "精确闹钟",
        description = "准点执行定时任务",
        granted = try {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        } catch (_: Exception) { false },
        onOpenSettings = { ctx ->
            openSettings(ctx, Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${ctx.packageName}")
        }
    )

    private fun fileAccessPermission(context: Context) = PermissionItem(
        key = "file_access",
        title = "文件访问权限",
        description = "读写 /sdcard 下的文件",
        granted = Environment.isExternalStorageManager(),
        onOpenSettings = { ctx ->
            val appUri = "package:${ctx.packageName}"
            if (!openSettings(ctx, Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, appUri)) {
                openSettings(ctx, Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
        }
    )

    private fun batteryOptimization(context: Context) = PermissionItem(
        key = "battery",
        title = "保活白名单",
        description = "关闭电池优化避免后台被杀",
        granted = try {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) { false },
        onOpenSettings = { ctx ->
            val appUri = "package:${ctx.packageName}"
            if (!openSettings(ctx, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, appUri)) {
                openSettings(ctx, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            }
        }
    )

    private fun locationPermission(context: Context): PermissionItem {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val granted = fine || coarse
        return PermissionItem(
            key = "location",
            title = "定位权限",
            description = when {
                granted && fine -> "已获得精确定位权限"
                granted -> "已获得模糊定位权限"
                else -> "获取当前位置，用于 get_location 工具"
            },
            granted = granted,
            onOpenSettings = { ctx -> openAppDetails(ctx) }
        )
    }

    private fun notificationListenerPermission(context: Context) = PermissionItem(
        key = "notification_listener",
        title = "通知使用权",
        description = if (MediaTools.hasNotificationListenerAccess(context))
            "已授权，可读取媒体播放信息与系统通知"
        else "读取手机当前播放的音乐 / 播客、系统通知（media_session / notifications 工具需要）",
        granted = MediaTools.hasNotificationListenerAccess(context),
        onOpenSettings = { ctx ->
            openSettings(ctx, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
    )

    private fun accessibilityPermission(context: Context) = PermissionItem(
        key = "accessibility",
        title = "屏幕控制（无障碍）",
        description = if (ScreenAccessibilityService.isEnabledInSettings(context))
            "已开启，AI 可直接读取并操作屏幕元素"
        else "让 AI 读取屏幕元素并操作，无需截图（ui 工具需要）",
        granted = ScreenAccessibilityService.isEnabledInSettings(context),
        onOpenSettings = { ctx ->
            openSettings(ctx, Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
    )

    private fun shizukuPermission(context: Context): PermissionItem {
        val available = ShizukuHelper.isAvailable()
        val granted = available && ShizukuHelper.hasPermission()
        return PermissionItem(
            key = "shizuku",
            title = "Shizuku 权限",
            description = when {
                !available -> "需先安装并启动 Shizuku"
                granted -> "已获得 ADB 级权限"
                else -> "点右侧按钮申请"
            },
            granted = granted,
            onOpenSettings = { ctx ->
                if (!available) {
                    val launch = ctx.packageManager
                        .getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        safeStart(ctx, launch)
                    } else {
                        if (!openSettings(ctx, Intent.ACTION_VIEW, "market://details?id=moe.shizuku.privileged.api")) {
                            openSettings(ctx, Intent.ACTION_VIEW, "https://shizuku.rikka.app/")
                        }
                    }
                }
            }
        )
    }

    fun openAutoStartSettings(context: Context) {
        openAppDetails(context)
    }

    private fun safeStart(ctx: Context, intent: Intent): Boolean {
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            AgentLogger.log(
                "SYS",
                "启动设置页失败（${intent.action}）：${e.javaClass.simpleName}: ${e.message}"
            )
            false
        }
    }
}