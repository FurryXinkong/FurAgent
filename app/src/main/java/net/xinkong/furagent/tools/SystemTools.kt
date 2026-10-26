package net.xinkong.furagent.tools

import net.xinkong.furagent.shared.util.DateFormats
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.core.ConfirmationBroker
import net.xinkong.furagent.data.*
import net.xinkong.furagent.shared.util.TaskCondition
import net.xinkong.furagent.shared.util.WebSearchEngine
import net.xinkong.furagent.shared.util.formatFileSize
import net.xinkong.furagent.system.MediaSessionListenerService
import net.xinkong.furagent.system.NotificationHelper
import net.xinkong.furagent.system.PermissionRequestBroker
import net.xinkong.furagent.system.ShizukuHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

object SystemTools {

    private val RE_RANGE = Regex("""^(\d+)\.\.(\d+)$""")
    private val RE_LENGTH = Regex("^length(>=|<=|==|=|>|<)(\\d+)$")
    private val RE_NUM_OP = Regex("^(>=|<=|==|=|>|<)(-?\\d+(?:\\.\\d+)?)$")

    val TOOLS = setOf(
        "app", "device", "calendar", "schedule", "workflow", "memory", "web",
        "set_alarm", "execute_shell", "get_location", "add_contact", "notifications"
    )

    private fun requireShizuku(): String? {
        if (!ShizukuHelper.isAvailable()) return "失败：Shizuku 服务未运行"
        if (!ShizukuHelper.hasPermission()) return "失败：未获得 Shizuku 权限"
        return null
    }

    suspend fun execute(
        name: String, args: JSONObject, context: Context, settings: AppSettings
    ): String = when (name) {
        "app" -> doApp(args, context)
        "device" -> doDevice(args, context)
        "calendar" -> doCalendar(args, context)
        "schedule" -> doSchedule(args, context, settings)
        "workflow" -> doWorkflow(args, context, settings)
        "memory" -> doMemory(args, context)
        "web" -> doWeb(args)
        "set_alarm" -> doSetAlarm(args, context)
        "execute_shell" -> doExecuteShell(args, settings)
        "get_location" -> doGetLocation(context)
        "add_contact" -> doAddContact(args, context)
        "notifications" -> doNotifications(args, context)
        else -> "未实现的系统工具：$name"
    }

    // app
    private suspend fun doApp(args: JSONObject, context: Context): String {
        val op = args.optString("op", "").lowercase()
        return when (op) {
            "list" -> listInstalledApps(context, args)
            "open" -> openApp(context, args)
            "open_url" -> openUrl(context, args)
            "running" -> getRunningApps(args)
            else -> "失败：op 只能是 list / open / open_url / running"
        }
    }

    private fun listInstalledApps(context: Context, args: JSONObject): String {
        return try {
            val keyword = args.optString("keyword", "").trim()
            val limit = args.optInt("limit", 500).coerceIn(1, 5000)
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
            data class AppEntry(val name: String, val pkg: String)
            val entries = mutableListOf<AppEntry>()
            for (pkg in packages) {
                if (pm.getLaunchIntentForPackage(pkg.packageName) != null) {
                    val appName = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg.packageName, 0)).toString()
                    } catch (_: Exception) { pkg.packageName }
                    if (keyword.isBlank() ||
                        appName.contains(keyword, ignoreCase = true) ||
                        pkg.packageName.contains(keyword, ignoreCase = true)
                    ) {
                        entries.add(AppEntry(appName, pkg.packageName))
                    }
                }
            }
            if (entries.isEmpty()) {
                return if (keyword.isBlank()) "未找到可启动的应用"
                else "未找到匹配 \"$keyword\" 的应用"
            }
            entries.sortBy { it.name.lowercase(Locale.ROOT) }
            val shown = entries.take(limit)
            val sb = StringBuilder()
            sb.append("共 ${entries.size} 个可启动应用，显示前 ${shown.size} 个：\n\n")
            shown.forEach { sb.append("${it.name}: ${it.pkg}\n") }
            if (entries.size > shown.size) sb.append("\n...（还有 ${entries.size - shown.size} 个未显示，可加大 limit 或传 keyword 过滤）")
            sb.toString()
        } catch (e: Exception) {
            AgentLogger.log("ERR", "获取应用列表失败：${e.javaClass.simpleName}: ${e.message}")
            "获取应用列表失败：${e.message}"
        }
    }

    private fun openApp(context: Context, args: JSONObject): String {
        val pkg = args.optString("package_name", "").trim()
        val appName = args.optString("app_name", pkg)
        if (pkg.isBlank()) return "未提供包名"
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "未找到包名为 $pkg 的应用"
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(launchIntent)
            return "已打开：$appName"
        } catch (e: Exception) {
            AgentLogger.log("ERR", "直接打开失败：${e.message}，尝试 Shizuku")
        }
        if (ShizukuHelper.isAvailable() && ShizukuHelper.hasPermission()) {
            if (ShizukuHelper.startActivityByPackage(pkg)) {
                return "已通过 Shizuku 打开：$appName"
            }
        }
        return "失败：系统限制了后台启动，且 Shizuku 不可用"
    }

    private fun openUrl(context: Context, args: JSONObject): String {
        val url = args.optString("url", "").trim()
        if (url.isBlank()) return "未提供 URL"
        val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        return try {
            val intent = Intent(Intent.ACTION_VIEW, normalized.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "已在浏览器打开：$normalized"
        } catch (e: Exception) {
            AgentLogger.log("ERR", "打开 URL 失败：$normalized - ${e.message}")
            "打开失败：${e.message}"
        }
    }

    private suspend fun getRunningApps(args: JSONObject): String {
        val limit = args.optInt("limit", 200).coerceIn(1, 5000)
        requireShizuku()?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val ps = ShizukuHelper.executeCommand("ps -A", timeoutSeconds = 15)
                val pkgs = linkedSetOf<String>()
                val psRegex = Regex(
                    """^\S+\s+\d+\s+\d+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+(\S+)""",
                    RegexOption.MULTILINE
                )
                psRegex.findAll(ps).forEach { m ->
                    val p = m.groupValues[1]
                    if (p.contains('.') && !p.startsWith("[") && !p.startsWith("android.")) {
                        pkgs.add(p)
                    }
                }
                if (pkgs.isEmpty()) return@withContext "未获取到运行中应用"
                val sorted = pkgs.sorted()
                val sb = StringBuilder(
                    "运行中应用（${sorted.size} 个，显示前 ${minOf(sorted.size, limit)} 个）：\n\n"
                )
                sorted.take(limit).forEach { sb.append("$it\n") }
                if (sorted.size > limit) sb.append("\n...（还有 ${sorted.size - limit} 个未显示）")
                sb.toString()
            } catch (e: Exception) {
                AgentLogger.log(
                    "ERR",
                    "获取运行中应用失败：${e.javaClass.simpleName}: ${e.message}"
                )
                "获取失败：${e.message}"
            }
        }
    }

    // device
    private suspend fun doDevice(args: JSONObject, context: Context): String {
        val op = args.optString("op", "").lowercase()
        return when (op) {
            "status" -> deviceStatus(context)
            "info" -> systemInfo(context)
            "clipboard_read" -> clipboardOp(args, context, "read")
            "clipboard_write" -> clipboardOp(args, context, "write")
            "notify" -> sendNotification(args, context)
            else -> "失败：op 只能是 status / info / clipboard_read / clipboard_write / notify"
        }
    }

    private fun sendNotification(args: JSONObject, context: Context): String {
        val title = args.optString("title", "FurAgent").trim()
        val content = args.optString("content", "").trim()
        if (content.isBlank()) return "未提供通知内容"
        return try {
            NotificationHelper.showGeneric(context, title, content)
            "已发送通知：$title"
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "发送通知失败：${e.javaClass.simpleName}: ${e.message}"
            )
            "发送通知失败：${e.message}"
        }
    }

    private suspend fun clipboardOp(args: JSONObject, context: Context, op: String): String {
        return withContext(Dispatchers.Main) {
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                when (op) {
                    "read" -> {
                        val clip = cm.primaryClip
                        if (clip == null || clip.itemCount == 0) return@withContext "剪贴板为空"
                        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: ""
                        if (text.isBlank()) "剪贴板内容为空文本" else "剪贴板内容：\n$text"
                    }
                    "write" -> {
                        val text = args.optString("text", "")
                        if (text.isBlank()) return@withContext "失败：clipboard_write 需要 text"
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("furagent", text))
                        "已写入剪贴板（${text.length} 字符）"
                    }
                    else -> "失败：未知剪贴板操作"
                }
            } catch (e: Exception) {
                AgentLogger.log(
                    "ERR",
                    "剪贴板操作 $op 失败：${e.javaClass.simpleName}: ${e.message}"
                )
                "剪贴板操作失败：${e.message}"
            }
        }
    }

    private fun deviceStatus(context: Context): String {
        return try {
            val sb = StringBuilder()
            try {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
                sb.append("电量：$level%\n")
                sb.append("充电状态：${
                    when (status) {
                        BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
                        BatteryManager.BATTERY_STATUS_DISCHARGING -> "使用中"
                        BatteryManager.BATTERY_STATUS_FULL -> "已充满"
                        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
                        else -> "未知"
                    }
                }\n")
            } catch (_: Exception) { }
            try {
                val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
                val total = stat.blockCountLong * stat.blockSizeLong
                val free = stat.availableBlocksLong * stat.blockSizeLong
                sb.append("内部存储：\n")
                sb.append("  总：${formatFileSize(total)}\n")
                sb.append("  已用：${formatFileSize(total - free)}\n")
                sb.append("  可用：${formatFileSize(free)}\n")
            } catch (_: Exception) { }
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE)
                        as android.app.ActivityManager
                val mi = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                sb.append("内存：\n")
                sb.append("  总：${formatFileSize(mi.totalMem)}\n")
                sb.append("  可用：${formatFileSize(mi.availMem)}\n")
                sb.append("  低内存状态：${mi.lowMemory}\n")
            } catch (_: Exception) { }
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                        as android.net.ConnectivityManager
                val net = cm.activeNetwork
                val caps = if (net != null) cm.getNetworkCapabilities(net) else null
                val type = when {
                    caps == null -> "无网络"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                    else -> "其他"
                }
                sb.append("网络：$type")
            } catch (_: Exception) { }
            sb.toString().trim()
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "读取设备状态失败：${e.javaClass.simpleName}: ${e.message}"
            )
            "读取设备状态失败：${e.message}"
        }
    }

    private fun systemInfo(context: Context): String {
        val sb = StringBuilder()
        sb.append("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
        sb.append("品牌：${android.os.Build.BRAND}\n")
        sb.append("Android：${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
        sb.append("ABI：${android.os.Build.SUPPORTED_ABIS.joinToString(", ")}\n")
        sb.append("指纹：${android.os.Build.FINGERPRINT}\n")
        try {
            val dm = context.resources.displayMetrics
            sb.append("屏幕：${dm.widthPixels}x${dm.heightPixels} @ ${dm.densityDpi}dpi\n")
        } catch (_: Exception) { }
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            sb.append("内存：总 ${formatFileSize(mi.totalMem)}，可用 ${formatFileSize(mi.availMem)}\n")
            sb.append("低内存状态：${mi.lowMemory}\n")
        } catch (_: Exception) { }
        try {
            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            sb.append("内部存储：总 ${formatFileSize(total)}，可用 ${formatFileSize(free)}\n")
        } catch (_: Exception) { }
        val uptimeMin = android.os.SystemClock.elapsedRealtime() / 1000 / 60
        sb.append("系统运行：$uptimeMin 分钟\n")
        sb.append("语言：${Locale.getDefault()}\n")
        sb.append("时区：${TimeZone.getDefault().id}")
        return sb.toString()
    }

    // calendar
    private suspend fun doCalendar(args: JSONObject, context: Context): String {
        val op = args.optString("op", "").lowercase()
        return when (op) {
            "add" -> addCalendar(args, context)
            "read" -> readCalendar(args, context)
            else -> "失败：op 只能是 add / read"
        }
    }

    private fun addCalendar(args: JSONObject, context: Context): String {
        val title = args.optString("title", "新日程")
        val sdf = DateFormats.dateTimeFormatter()
        val start = try { sdf.parse(args.optString("start_time"))?.time } catch (_: Exception) { null }
            ?: System.currentTimeMillis()
        val endStr = args.optString("end_time", "")
        val end = if (endStr.isNotBlank()) {
            try { sdf.parse(endStr)?.time } catch (_: Exception) { null } ?: (start + 3_600_000L)
        } else start + 3_600_000L
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "已打开日历页面，请用户确认后保存日程：$title（未保存前不代表已创建）"
    }

    private suspend fun readCalendar(args: JSONObject, context: Context): String {
        val sdf = DateFormats.dateTimeFormatter()
        val startStr = args.optString("start_time", "").trim()
        val endStr = args.optString("end_time", "").trim()
        val limit = args.optInt("limit", 500).coerceIn(1, 5000)

        val (startMs, endMs) = if (startStr.isBlank() && endStr.isBlank()) {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            start to cal.timeInMillis
        } else {
            val start = try { sdf.parse(startStr)?.time } catch (_: Exception) { null }
                ?: (System.currentTimeMillis() - 86_400_000L)
            val end = try { sdf.parse(endStr)?.time } catch (_: Exception) { null }
                ?: (start + 7L * 86_400_000L)
            start to end
        }

        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(startMs.toString(), endMs.toString())
        val order = "${CalendarContract.Events.DTSTART} ASC"

        return try {
            val sb = StringBuilder()
            var count = 0
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection, selection, selectionArgs, order
            )?.use { cursor ->
                val idxTitle = cursor.getColumnIndex(CalendarContract.Events.TITLE)
                val idxStart = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
                val idxEnd = cursor.getColumnIndex(CalendarContract.Events.DTEND)
                val idxLoc = cursor.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
                val idxDesc = cursor.getColumnIndex(CalendarContract.Events.DESCRIPTION)
                val idxAllDay = cursor.getColumnIndex(CalendarContract.Events.ALL_DAY)
                val idxCal = cursor.getColumnIndex(CalendarContract.Events.CALENDAR_DISPLAY_NAME)

                val displaySdf = DateFormats.dateTimeNoSecFormatter()
                while (cursor.moveToNext() && count < limit) {
                    val title = cursor.getString(idxTitle) ?: "(无标题)"
                    val start = cursor.getLong(idxStart)
                    val end = if (idxEnd >= 0 && !cursor.isNull(idxEnd)) cursor.getLong(idxEnd) else 0L
                    val allDay = idxAllDay >= 0 && cursor.getInt(idxAllDay) == 1
                    val loc = if (idxLoc >= 0) cursor.getString(idxLoc) else null
                    val desc = if (idxDesc >= 0) cursor.getString(idxDesc) else null
                    val cal = if (idxCal >= 0) cursor.getString(idxCal) else null

                    sb.append("${count + 1}. $title\n")
                    if (allDay) sb.append("   时间：全天\n")
                    else {
                        sb.append("   开始：${displaySdf.format(Date(start))}\n")
                        if (end > 0) sb.append("   结束：${displaySdf.format(Date(end))}\n")
                    }
                    if (!cal.isNullOrBlank()) sb.append("   日历：$cal\n")
                    if (!loc.isNullOrBlank()) sb.append("   地点：$loc\n")
                    if (!desc.isNullOrBlank()) sb.append("   说明：$desc\n")
                    sb.append("\n")
                    count++
                }
            }
            if (count == 0) "该时间段内没有日程。" else "共 $count 条日程：\n\n$sb"
        } catch (e: SecurityException) {
            // 【修复 F5】不再只报错，弹一个引导框让用户去应用详情页开权限。
            AgentLogger.log("ERR", "读取日历权限被拒：${e.message}")
            val approved = PermissionRequestBroker.request(
                title = "需要日历读取权限",
                message = "AI 想读取你的日程，需要「读取日历」权限。是否前往应用设置授权？",
                permission = android.Manifest.permission.READ_CALENDAR
            ) { ctx ->
                try {
                    val intent = Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        "package:${ctx.packageName}".toUri()
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                } catch (e2: Exception) {
                    AgentLogger.log(
                        "ERR",
                        "打开日历权限设置失败：${e2.javaClass.simpleName}: ${e2.message}"
                    )
                }
            }
            if (!approved) "用户未授予日历读取权限，操作取消"
            else "已打开设置页，请授权后重试"
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "读取日历失败：${e.javaClass.simpleName}: ${e.message}"
            )
            "读取日历失败：${e.message}"
        }
    }

    // schedule
    private suspend fun doSchedule(
        args: JSONObject, context: Context, settings: AppSettings
    ): String {
        val op = args.optString("op", "").lowercase()
        return when (op) {
            "create" -> scheduleCreate(args, context, settings)
            "list" -> listScheduledTasks(context)
            "cancel" -> cancelScheduledTask(args, context)
            else -> "失败：op 只能是 create / list / cancel"
        }
    }

    private suspend fun scheduleCreate(
        args: JSONObject, context: Context, settings: AppSettings
    ): String {
        // 动作归一：AI 传旧名（send_notification / run_cron）也接受，统一存新名
        val action = ScheduleStore.normalizeAction(args.optString("action", "open_app").trim())
        val description = args.optString("description", "").trim()
        if (action !in ScheduleStore.VALID_ACTIONS) {
            return "失败：action 只能是 ${ScheduleStore.VALID_ACTIONS.joinToString(" / ")}"
        }

        val intervalSeconds = args.optInt("interval_seconds", 0).coerceIn(
            0, ScheduleStore.MAX_INTERVAL_SECONDS
        )

        val repeatMode = args.optString("repeat_mode", "once").trim()
        if (repeatMode !in listOf("once", "daily", "weekly", "interval", "cron")) {
            return "失败：repeat_mode 只能是 once / daily / weekly / interval / cron"
        }

        val sdf = DateFormats.dateTimeFormatter()
        val triggerStr = args.optString("trigger_time", "").trim()
        val delaySeconds = args.optInt("delay_seconds", 0)
        val triggerTime = if (triggerStr.isBlank()) {
            when {
                delaySeconds > 0 -> System.currentTimeMillis() + delaySeconds * 1000L
                repeatMode == "cron" -> System.currentTimeMillis() + 60_000L
                repeatMode == "interval" -> System.currentTimeMillis() +
                        (if (intervalSeconds > 0) intervalSeconds else 60) * 1000L
                else -> return "失败：非 cron/interval 模式必须提供 trigger_time 或 delay_seconds"
            }
        } else {
            try { sdf.parse(triggerStr)?.time } catch (_: Exception) { null }
                ?: return "失败：时间格式错误：$triggerStr"
        }
        if (triggerTime < System.currentTimeMillis() && repeatMode != "cron") {
            return "失败：触发时间已过去"
        }
        if (repeatMode == "interval" && intervalSeconds < ScheduleStore.MIN_INTERVAL_SECONDS) {
            return "失败：interval_seconds 必须 >= ${ScheduleStore.MIN_INTERVAL_SECONDS}"
        }
        if (repeatMode == "cron" && args.optString("cron_expression", "").isBlank()) {
            return "失败：repeat_mode=cron 需要 cron_expression"
        }

        val conds = mutableListOf<TaskCondition>()
        args.optJSONArray("conditions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                conds.add(TaskCondition(
                    type = c.optString("type", ""),
                    operator = c.optString("operator", "eq"),
                    value = c.optString("value", ""),
                    value2 = c.optString("value2", "")
                ))
            }
        }

        val repeatDays = args.optString("repeat_days", "")
            .split(',').mapNotNull { it.trim().toIntOrNull() }

        val task = ScheduledTask(
            id = UUID.randomUUID().toString(),
            triggerTime = triggerTime,
            packageName = args.optString("package_name", ""),
            appName = args.optString("app_name", "").ifBlank {
                args.optString("package_name", "")
            },
            repeatMode = repeatMode,
            repeatDays = if (repeatMode == "weekly") repeatDays else emptyList(),
            intervalSeconds = if (repeatMode == "interval") intervalSeconds else 0,
            cronExpression = args.optString("cron_expression", ""),
            action = action,
            command = args.optString("command", ""),
            toolName = args.optString("tool_name", ""),
            toolArgs = args.optString("tool_args", ""),
            notificationTitle = args.optString("notification_title", ""),
            notificationContent = args.optString("notification_content", ""),
            workflowName = args.optString("workflow_name", ""),
            conditions = conds,
            conditionMode = args.optString("condition_mode", "and"),
            skipIfNotMet = args.optBoolean("skip_if_not_met", true),
            description = description,
            prompt = args.optString("prompt", ""),
            sessionId = args.optString("session_id", "")
        )

        if (action == "run_command") {
            if (task.command.isBlank()) return "失败：run_command 需要 command"
            requireShizuku()?.let { return it }
            if (settings.requireConfirmShell) {
                val preview = task.command.take(300)
                val approved = ConfirmationBroker.request(
                    "周期命令确认",
                    "AI 请求执行 shell：\n\n$preview\n\n是否允许？"
                )
                if (!approved) return "用户已拒绝"
            }
        }
        if (action == "run_tool" && task.toolName.isBlank()) {
            return "失败：run_tool 需要 tool_name"
        }
        if (action == "run_agent") {
            if (task.prompt.isBlank()) return "失败：run_agent 需要 prompt（要发给 AI 的内容）"
        }
        if (action == "open_app" && task.packageName.isBlank()) {
            return "失败：open_app 需要 package_name"
        }
        if (action == "run_workflow" && task.workflowName.isBlank()) {
            return "失败：run_workflow 需要 workflow_name"
        }

        ScheduleStore.add(context, task)
        ScheduleStore.scheduleAlarm(context, task)

        val condText = if (conds.isEmpty()) ""
        else "\n触发条件（${
            if (task.conditionMode == "or") "任意满足" else "全部满足"
        }）：\n" + conds.joinToString("\n") { c ->
            val opText = when (c.operator) {
                "eq" -> "="; "ne" -> "!="; "gt" -> ">"; "lt" -> "<"
                "gte" -> ">="; "lte" -> "<="; "between" -> "介于"
                "contains" -> "包含"; "matches" -> "匹配"; "in" -> "属于"
                else -> c.operator
            }
            "  - ${c.type} $opText ${c.value}${if (c.value2.isNotEmpty()) "..${c.value2}" else ""}"
        }

        val nextText = when (repeatMode) {
            "cron" -> "cron: ${task.cronExpression}"
            "interval" -> ScheduleStore.formatInterval(task.intervalSeconds)
            "daily" -> "每天"
            "weekly" -> "每周 " + task.repeatDays.joinToString(",")
            else -> sdf.format(Date(triggerTime))
        }

        return "已创建定时任务：\n" +
                "描述：${description.ifBlank { "(无)" }}\n" +
                "动作：$action\n" +
                "重复：$nextText" + condText
    }

    private fun listScheduledTasks(context: Context): String {
        val list = ScheduleStore.list(context)
        if (list.isEmpty()) return "当前没有定时任务。"
        val sdf = DateFormats.dateTimeFormatter()
        val sb = StringBuilder("共 ${list.size} 个定时任务：\n\n")
        list.forEachIndexed { i, t ->
            sb.append("${i + 1}. ${t.description.ifBlank { t.appName.ifBlank { t.id.take(8) } }}\n")
            sb.append("   ID: ${t.id}\n")
            sb.append("   动作：${t.action}\n")
            sb.append("   下次：${sdf.format(Date(t.triggerTime))}\n")
            val repeatDetail = when (t.repeatMode) {
                "interval" -> "（${ScheduleStore.formatInterval(t.intervalSeconds)}）"
                "cron" -> "（${t.cronExpression}）"
                else -> ""
            }
            sb.append("   重复：${t.repeatMode}$repeatDetail\n")
            if (t.conditions.isNotEmpty()) {
                sb.append("   条件：${t.conditions.size} 个（${t.conditionMode}）\n")
            }
            sb.append("\n")
        }
        return sb.toString().trim()
    }

    private fun cancelScheduledTask(args: JSONObject, context: Context): String {
        val taskId = args.optString("task_id", "").trim()
        val keyword = args.optString("keyword", "").trim()
        val list = ScheduleStore.list(context)
        val target = when {
            taskId.isNotBlank() -> list.firstOrNull { it.id == taskId }
            keyword.isNotBlank() -> list.firstOrNull {
                it.description.contains(keyword, ignoreCase = true) ||
                        it.appName.contains(keyword, ignoreCase = true)
            }
            else -> null
        } ?: return "未找到匹配的任务"
        ScheduleStore.cancelAlarm(context, target.id)
        ScheduleStore.remove(context, target.id)
        return "已取消：${target.description.ifBlank { target.appName }}"
    }

    // workflow
    private suspend fun doWorkflow(
        args: JSONObject, context: Context, settings: AppSettings
    ): String {
        val op = args.optString("op", "").lowercase()
        return when (op) {
            "create" -> createWorkflow(args, context)
            "delete" -> deleteWorkflow(args, context)
            "list" -> listWorkflows(context)
            "run" -> runWorkflow(args, context, settings)
            else -> "失败：op 只能是 create / delete / list / run"
        }
    }

    private fun createWorkflow(args: JSONObject, context: Context): String {
        val name = args.optString("name", "").trim()
        if (name.isBlank()) return "失败：未提供 name"
        val description = args.optString("description", "").trim()
        val paramsRaw = args.optString("params", "").trim()
        val params = paramsRaw.split(',').map { it.trim() }.filter { it.isNotBlank() }
        val stepsJson = args.optString("steps_json", "").trim()
        if (stepsJson.isBlank()) return "失败：未提供 steps_json"
        val stepsArr = try { JSONArray(stepsJson) } catch (e: Exception) {
            return "失败：steps_json 不是合法 JSON 数组（${e.message}）"
        }
        val steps = parseWorkflowSteps(stepsArr)
        if (steps.isEmpty()) return "失败：工作流没有有效步骤"
        val existing = WorkflowStore.find(context, name)
        val id = existing?.id ?: UUID.randomUUID().toString()
        WorkflowStore.save(context, Workflow(id, name, description, params, steps))
        return if (existing != null) "已更新工作流「$name」，共 ${steps.size} 步"
        else "已创建工作流「$name」，共 ${steps.size} 步"
    }

    private fun parseWorkflowSteps(arr: JSONArray): List<WorkflowStep> {
        val list = mutableListOf<WorkflowStep>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val argsObj = o.optJSONObject("args") ?: JSONObject()
            val argsMap = mutableMapOf<String, String>()
            argsObj.keys().forEach { k -> argsMap[k] = argsObj.optString(k, "") }
            list.add(WorkflowStep(
                stepType = o.optString("stepType", "call"),
                toolName = o.optString("tool", ""),
                argsTemplate = argsMap,
                conditionExpr = o.optString("conditionExpr", ""),
                trueBranch = o.optJSONArray("trueBranch")?.let { parseWorkflowSteps(it) } ?: emptyList(),
                falseBranch = o.optJSONArray("falseBranch")?.let { parseWorkflowSteps(it) } ?: emptyList(),
                loopVar = o.optString("loopVar", ""),
                loopItems = o.optString("loopItems", ""),
                loopBody = o.optJSONArray("loopBody")?.let { parseWorkflowSteps(it) } ?: emptyList(),
                delaySeconds = o.optInt("delaySeconds", 0),
                varName = o.optString("varName", ""),
                varValue = o.optString("varValue", "")
            ))
        }
        return list
    }

    private fun deleteWorkflow(args: JSONObject, context: Context): String {
        val name = args.optString("name", "").trim()
        if (name.isBlank()) return "失败：未提供 name"
        val wf = WorkflowStore.find(context, name) ?: return "未找到工作流「$name」"
        WorkflowStore.delete(context, wf.id)
        return "已删除工作流「${wf.name}」"
    }

    private fun listWorkflows(context: Context): String {
        val list = WorkflowStore.list(context)
        if (list.isEmpty()) return "暂无工作流。可在设置中添加。"
        val sb = StringBuilder("共 ${list.size} 个工作流：\n\n")
        list.forEachIndexed { i, w ->
            sb.append("${i + 1}. ${w.name}\n")
            if (w.description.isNotBlank()) sb.append("   说明：${w.description}\n")
            if (w.params.isNotEmpty()) sb.append("   参数：${w.params.joinToString(", ")}\n")
            sb.append("   步骤数：${w.steps.size}\n\n")
        }
        return sb.toString()
    }

    private suspend fun runWorkflow(
        args: JSONObject, context: Context, settings: AppSettings
    ): String {
        val nameOrId = args.optString("name", "").trim()
        if (nameOrId.isBlank()) return "失败：未提供工作流名称"
        val wf = WorkflowStore.find(context, nameOrId)
            ?: return "失败：未找到工作流 \"$nameOrId\""

        val paramsObj = args.optJSONObject("params") ?: JSONObject()
        val paramsMap = mutableMapOf<String, String>()
        paramsObj.keys().forEach { k -> paramsMap[k] = paramsObj.optString(k, "") }

        val missing = wf.params.filter { it !in paramsMap || paramsMap[it].isNullOrBlank() }
        if (missing.isNotEmpty()) return "失败：缺少必需参数 ${missing.joinToString(", ")}"

        val log = StringBuilder()
        log.append("执行工作流「${wf.name}」共 ${wf.steps.size} 步：\n\n")
        val vars = mutableMapOf<String, String>()
        vars.putAll(paramsMap)
        executeWorkflowSteps(wf.steps, vars, context, settings, log, 0)
        return log.toString()
    }

    private suspend fun executeWorkflowSteps(
        steps: List<WorkflowStep>,
        vars: MutableMap<String, String>,
        context: Context,
        settings: AppSettings,
        log: StringBuilder,
        depth: Int
    ) {
        if (depth > 10) {
            log.append("[警告] 工作流递归过深（$depth），已终止\n")
            return
        }
        // 【脆弱解构清理】显式字段访问，不依赖 WorkflowStep 字段位置顺序。
        for (step in steps) {
            val stepType = step.stepType
            val toolName = step.toolName
            val argsTemplate = step.argsTemplate
            val conditionExpr = step.conditionExpr
            val trueBranch = step.trueBranch
            val falseBranch = step.falseBranch
            val loopVar = step.loopVar
            val loopItems = step.loopItems
            val loopBody = step.loopBody
            val delaySeconds = step.delaySeconds
            val name = step.varName
            val varValue = step.varValue
            val prefix = "  ".repeat(depth)
            when (stepType) {
                "call" -> {
                    val stepArgs = JSONObject()
                    argsTemplate.forEach { (k, v) ->
                        stepArgs.put(k, WorkflowStore.substitute(v, vars))
                    }
                    log.append("$prefix> ${ToolRegistry.displayName(toolName)}\n")
                    val r = try {
                        ToolRegistry.execute(toolName, stepArgs, context, settings)
                    } catch (e: Exception) {
                        AgentLogger.log(
                            "ERR",
                            "工作流步骤 $toolName 异常：${e.javaClass.simpleName}: ${e.message}"
                        )
                        "异常：${e.message}"
                    }
                    vars["prev"] = r
                    log.append("$prefix  结果：${r.take(300)}\n\n")
                }
                "condition" -> {
                    val condResult = evaluateWorkflowCondition(conditionExpr, vars)
                    log.append("$prefix◇ 条件：$conditionExpr -> $condResult\n")
                    val branch = if (condResult) trueBranch else falseBranch
                    if (branch.isNotEmpty()) {
                        executeWorkflowSteps(branch, vars, context, settings, log, depth + 1)
                    } else {
                        log.append("$prefix  （分支为空）\n")
                    }
                }
                "loop" -> {
                    val itemsExpr = WorkflowStore.substitute(loopItems, vars)
                    val items = parseLoopItems(itemsExpr, vars)
                    log.append("$prefix↻ 循环 $loopVar over ${items.size} 项\n")
                    for ((idx, item) in items.withIndex()) {
                        vars[loopVar] = item
                        vars["index"] = idx.toString()
                        executeWorkflowSteps(loopBody, vars, context, settings, log, depth + 1)
                    }
                }
                "delay" -> {
                    val sec = delaySeconds.coerceIn(0, 600)
                    log.append("$prefix⏱ 延时 ${sec}s\n")
                    delay((sec * 1000L).milliseconds)
                }
                "set_var" -> {
                    val value = WorkflowStore.substitute(varValue, vars)
                    if (name.isNotBlank()) vars[name] = value
                    log.append("$prefix✎ $name = ${value.take(200)}\n")
                }
                else -> log.append("$prefix[未知步骤类型：$stepType]\n")
            }
        }
    }

    private fun parseLoopItems(expr: String, vars: Map<String, String>): List<String> {
        val rangeMatch = Regex("""^(\d+)\.\.(\d+)$""").matchEntire(expr.trim())
        if (rangeMatch != null) {
            val a = rangeMatch.groupValues[1].toInt()
            val b = rangeMatch.groupValues[2].toInt()
            return if (a <= b) (a..b).map { it.toString() } else (b..a).map { it.toString() }
        }
        if (expr.startsWith("$")) {
            val key = expr.substring(1)
            val v = vars[key] ?: return emptyList()
            if (v.startsWith("[")) {
                return try {
                    val arr = JSONArray(v)
                    (0 until arr.length()).map { arr.optString(it, "") }
                } catch (_: Exception) {
                    v.split(',', '\n').map { it.trim() }.filter { it.isNotBlank() }
                }
            }
            return v.split(',', '\n').map { it.trim() }.filter { it.isNotBlank() }
        }
        return expr.split(',', '\n').map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun evaluateWorkflowCondition(expr: String, vars: Map<String, String>): Boolean {
        val prev = vars["prev"] ?: ""
        val e = WorkflowStore.substitute(expr, vars).trim()
        if (e.isEmpty()) return prev.isNotBlank()

        if (e.startsWith("not:", true)) {
            return !evaluateWorkflowCondition(e.substring(4), vars)
        }

        val lengthMatch = Regex("^length(>=|<=|==|=|>|<)(\\d+)$").matchEntire(e)
        if (lengthMatch != null) {
            val op = lengthMatch.groupValues[1]
            val n = lengthMatch.groupValues[2].toIntOrNull() ?: return false
            return when (op) {
                ">" -> prev.length > n
                "<" -> prev.length < n
                ">=" -> prev.length >= n
                "<=" -> prev.length <= n
                "=", "==" -> prev.length == n
                else -> false
            }
        }

        val numMatch = Regex("^(>=|<=|==|=|>|<)(-?\\d+(?:\\.\\d+)?)$").matchEntire(e)
        if (numMatch != null) {
            val op = numMatch.groupValues[1]
            val target = numMatch.groupValues[2].toDoubleOrNull() ?: return false
            val actual = prev.trim().toDoubleOrNull() ?: return false
            return when (op) {
                ">" -> actual > target
                "<" -> actual < target
                ">=" -> actual >= target
                "<=" -> actual <= target
                "=", "==" -> actual == target
                else -> false
            }
        }

        return when {
            e.equals("isEmpty", true) -> prev.isBlank()
            e.equals("isNotEmpty", true) -> prev.isNotBlank()
            e.startsWith("contains:", true) -> prev.contains(e.substringAfter(':').trim())
            e.startsWith("notContains:", true) -> !prev.contains(e.substringAfter(':').trim())
            e.startsWith("startsWith:", true) -> prev.startsWith(e.substringAfter(':').trim())
            e.startsWith("endsWith:", true) -> prev.endsWith(e.substringAfter(':').trim())
            e.startsWith("equals:", true) -> prev == e.substringAfter(':').trim()
            e.startsWith("matches:", true) ->
                try { Regex(e.substringAfter(':').trim()).containsMatchIn(prev) }
                catch (_: Exception) { false }
            else -> prev.contains(e)
        }
    }

    // memory
    private fun doMemory(args: JSONObject, context: Context): String {
        val op = args.optString("op", "").lowercase()
        return when (op) {
            "save" -> {
                val content = args.optString("content", "").trim()
                if (content.isBlank()) return "失败：未提供 content"
                val m = MemoryStore.add(context, content)
                "已记住：${m.content}"
            }
            "recall" -> {
                val query = args.optString("query", "").trim()
                val list = if (query.isBlank()) MemoryStore.list(context)
                else MemoryStore.search(context, query)
                if (list.isEmpty()) "暂无相关记忆"
                else {
                    val sb = StringBuilder("找到 ${list.size} 条记忆：\n")
                    list.forEach { sb.append("- ${it.content}\n") }
                    sb.toString()
                }
            }
            "list" -> {
                val list = MemoryStore.list(context)
                if (list.isEmpty()) "暂无记忆"
                else {
                    val sb = StringBuilder("共 ${list.size} 条记忆：\n\n")
                    list.forEachIndexed { i, m ->
                        sb.append("${i + 1}. ${m.content}\n")
                        sb.append("   ID：${m.id}\n\n")
                    }
                    sb.toString()
                }
            }
            "delete" -> {
                val id = args.optString("memory_id", "").trim()
                val keyword = args.optString("keyword", "").trim()
                val list = MemoryStore.list(context)
                val target = when {
                    id.isNotBlank() -> list.firstOrNull { it.id == id }
                    keyword.isNotBlank() -> list.firstOrNull {
                        it.content.contains(keyword, ignoreCase = true)
                    }
                    else -> null
                } ?: return "失败：未找到匹配的记忆（可用 op=list 查看 ID）"
                MemoryStore.remove(context, target.id)
                "已删除：${target.content}"
            }
            else -> "失败：op 只能是 save / recall / list / delete"
        }
    }

    // web
    private suspend fun doWeb(args: JSONObject): String {
        val op = args.optString("op", "").lowercase()
        return when (op) {
            "search" -> {
                val query = args.optString("query", "").trim()
                val page = args.optInt("page", 1).coerceAtLeast(1)
                val engine = args.optString("engine", "").trim()
                WebSearchEngine.search(query, page, engine)
            }
            "fetch" -> {
                val url = args.optString("url", "").trim()
                // 【修复 B6】与桌面端 WebTools 对齐，默认 8000
                val maxLength = args.optInt("max_length", 8000).coerceIn(500, 2_000_000)
                val selector = args.optString("selector", "").trim()
                WebSearchEngine.fetchContent(url, maxLength, selector)
            }
            else -> "失败：op 只能是 search / fetch"
        }
    }

    // notifications
    private fun doNotifications(args: JSONObject, context: Context): String {
        val op = args.optString("op", "list").lowercase()
        return when (op) {
            "list" -> {
                if (!MediaSessionListenerService.connected) {
                    return "失败：通知使用权未授权。请在系统设置 → 通知 → 通知使用权 中开启 FurAgent。"
                }
                val limit = args.optInt("limit", 100).coerceIn(1, 100)
                val list = MediaSessionListenerService.listRecent(limit)
                if (list.isEmpty()) {
                    "暂无通知（缓存 ${MediaSessionListenerService.size()} 条）"
                } else {
                    val sdf = DateFormats.timeFormatter()
                    val sb = StringBuilder("最近 ${list.size} 条通知：\n\n")
                    list.forEachIndexed { i, n ->
                        sb.append("${i + 1}. [${sdf.format(Date(n.timestamp))}] ${n.appName}")
                        if (n.isOngoing) sb.append(" [常驻]")
                        sb.append("\n")
                        if (n.title.isNotBlank()) sb.append("   标题：${n.title}\n")
                        if (n.text.isNotBlank()) sb.append("   内容：${n.text}\n")
                        sb.append("   包名：${n.packageName}\n\n")
                    }
                    sb.toString().trim()
                }
            }
            "clear" -> {
                MediaSessionListenerService.clearCache()
                "已清空通知缓存"
            }
            else -> "失败：op 只能是 list / clear"
        }
    }

    // 保留的独立工具
    private fun doSetAlarm(args: JSONObject, context: Context): String {
        val hour = args.optInt("hour", 8).coerceIn(0, 23)
        val minute = args.optInt("minute", 0).coerceIn(0, 59)
        val label = args.optString("label", "AI 闹钟")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "闹钟已设置：${hour}:${minute.toString().padStart(2, '0')}，标签：$label"
    }

    private suspend fun doExecuteShell(args: JSONObject, settings: AppSettings): String {
        val command = args.optString("command", "").trim()
        val timeout = args.optInt("timeout", 30).coerceIn(1, 120)
        if (command.isBlank()) return "失败：未提供命令"

        // 拦截 shell 里的删除操作。
        // AI 不得以任何方式直接删除文件，包括通过 execute_shell 绕过 file_op。
        detectDestructiveShell(command)?.let { return it }

        if (!ShizukuHelper.isAvailable()) return "失败：Shizuku 服务未运行"
        if (!ShizukuHelper.hasPermission()) {
            val granted = ShizukuHelper.requestPermission()
            if (!granted) return "用户未授权 Shizuku，操作取消"
        }
        if (settings.requireConfirmShell) {
            val preview = if (command.length > 300) command.take(300) + "..." else command
            val approved = ConfirmationBroker.request(
                "执行 Shell 命令确认",
                "AI 请求执行以下命令：\n\n$preview\n\n是否允许？"
            )
            if (!approved) return "用户已拒绝执行命令"
        }
        return withContext(Dispatchers.IO) {
            val ctx = kotlinx.coroutines.currentCoroutineContext()
            AgentLogger.log("SHELL", "开始执行：${command.take(200)}")
            val result = try {
                ShizukuHelper.executeCommand(
                    command, timeout,
                    isCancelled = { !ctx.isActive },
                    onLine = { line ->
                        AgentLogger.log("SHELL", line.take(500))
                    }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                AgentLogger.log("SHELL", "协程被取消")
                "已取消"
            }
            AgentLogger.log("SHELL", "结束：${result.take(300)}")
            result
        }
    }

    /**
     * 检测 shell 命令里是否包含删除/破坏性操作。
     *
     * 【为什么】AI 可以通过 execute_shell 跑 rm / rmdir 绕过 file_op 的回收站机制。
     *
     * 返回 null 表示安全；非 null 是拒绝文案。
     */
    private fun detectDestructiveShell(command: String): String? {
        val c = command.lowercase()
        val patterns = listOf(
            Regex("""(^|[\s&|;(])(rm|rmdir|rd)(\s|$)"""),
            Regex("""(^|[\s&|;(])(del|erase)(\s|$)"""),
            Regex("""(^|[\s&|;(])remove-item(\s|$)"""),
            Regex("""(^|[\s&|;(])ri(\s|$)""")
        )
        val hit = patterns.firstOrNull { it.containsMatchIn(c) } ?: return null
        return "失败：已拦截，命令包含删除操作（${hit.pattern}）。\n" +
                "AI 不得直接删除文件。如需删除，请改用 file_op（op=delete），" +
                "文件会被移入回收站，由用户决定何时清空。"
    }

    private suspend fun doGetLocation(context: Context): String {
        val hasLocation = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) {
            val approved = PermissionRequestBroker.request(
                title = "需要定位权限",
                message = "AI 想获取你的位置，需要定位权限。是否前往设置授权？",
                permission = android.Manifest.permission.ACCESS_FINE_LOCATION
            ) { ctx ->
                try {
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = "package:${ctx.packageName}".toUri()
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent)
                } catch (e: Exception) {
                    AgentLogger.log(
                        "ERR",
                        "打开定位权限设置失败：${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }
            if (!approved) return "用户未授予定位权限，操作取消"
        }
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            var anyEnabled = false
            var best: android.location.Location? = null
            for (p in providers) {
                try {
                    if (!lm.isProviderEnabled(p)) continue
                    anyEnabled = true
                    val loc = lm.getLastKnownLocation(p) ?: continue
                    if (best == null || loc.time > best.time) best = loc
                } catch (e: SecurityException) {
                    AgentLogger.log("ERR", "读取位置提供者 $p 缺少权限：${e.message}")
                    return "失败：缺少定位权限"
                } catch (_: Exception) { }
            }
            if (!anyEnabled) return "失败：系统定位服务未开启，请到系统设置中打开位置信息后重试"
            val loc = best ?: return "失败：暂无可用位置缓存，请稍后重试"
            val sb = StringBuilder()
            sb.append("纬度：${loc.latitude}\n")
            sb.append("经度：${loc.longitude}\n")
            sb.append("精度：${loc.accuracy}米\n")
            sb.append("时间：").append(
                DateFormats.formatDateTime(loc.time)
            ).append("\n")
            try {
                @Suppress("DEPRECATION")
                val list = Geocoder(context, Locale.getDefault())
                    .getFromLocation(loc.latitude, loc.longitude, 1)
                if (!list.isNullOrEmpty()) {
                    val addr = list[0].getAddressLine(0)
                    if (!addr.isNullOrBlank()) sb.append("地址：$addr\n")
                }
            } catch (e: Exception) {
                AgentLogger.log(
                    "ERR",
                    "反向地理编码失败：${e.javaClass.simpleName}: ${e.message}"
                )
            }
            sb.toString()
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "获取位置失败：${e.javaClass.simpleName}: ${e.message}"
            )
            "获取位置失败：${e.message}"
        }
    }

    private fun doAddContact(args: JSONObject, context: Context): String {
        val name = args.optString("name", "").trim()
        val phone = args.optString("phone", "").trim()
        if (name.isBlank()) return "失败：未提供姓名"
        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                type = ContactsContract.Contacts.CONTENT_TYPE
                putExtra(ContactsContract.Intents.Insert.NAME, name)
                if (phone.isNotBlank()) putExtra(ContactsContract.Intents.Insert.PHONE, phone)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "已打开联系人页面：$name${if (phone.isNotBlank()) " / $phone" else ""}，请用户确认保存"
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "打开新建联系人失败：${e.javaClass.simpleName}: ${e.message}"
            )
            "打开失败：${e.message}"
        }
    }
}