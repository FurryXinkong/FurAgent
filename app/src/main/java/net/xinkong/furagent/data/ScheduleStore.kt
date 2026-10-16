package net.xinkong.furagent.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import net.xinkong.furagent.TaskReceiver
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.shared.util.FileRecovery
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*

data class TaskCondition(
    val type: String,
    val operator: String = "eq",
    val value: String = "",
    val value2: String = ""
)

data class ConditionResult(
    val met: Boolean,
    val reason: String = ""
)

data class ScheduledTask(
    val id: String,
    val triggerTime: Long,
    val packageName: String,
    val appName: String,
    /** once / daily / weekly / interval / cron */
    val repeatMode: String = "once",
    val repeatDays: List<Int> = emptyList(),
    /** 每 N 秒重复一次（repeatMode == "interval" 时生效，最小 1 秒） */
    val intervalSeconds: Int = 0,
    /** 标准 5 段 cron："分 时 日 月 周" */
    val cronExpression: String = "",
    /** open_app / run_cron / send_notification / run_workflow */
    val action: String = "open_app",
    val command: String = "",
    val notificationTitle: String = "",
    val notificationContent: String = "",
    val workflowName: String = "",
    val conditions: List<TaskCondition> = emptyList(),
    val conditionMode: String = "and",
    val skipIfNotMet: Boolean = true,
    /**
     * 【F4 新增】是否启用。
     * true（默认）：正常注册 AlarmManager，TaskReceiver 正常执行。
     * false：不注册 alarm（setEnabled 时会 cancel），TaskReceiver 会跳过。
     * 反序列化缺省 true，兼容旧数据。
     */
    val enabled: Boolean = true,
    val description: String = ""
)

object ScheduleStore {
    private const val FILE_NAME = "schedules.json"

    const val MIN_INTERVAL_SECONDS = 1
    const val MAX_INTERVAL_SECONDS = 60 * 60 * 24 * 30

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    // ========================================================
    // 序列化
    // ========================================================

    fun list(context: Context): List<ScheduledTask> {
        return try {
            val f = file(context)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val list = mutableListOf<ScheduledTask>()
            var skipped = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o == null) { skipped++; continue }
                try {
                    val repeatDaysArr = o.optJSONArray("repeatDays")
                    val repeatDays = mutableListOf<Int>()
                    if (repeatDaysArr != null) {
                        for (j in 0 until repeatDaysArr.length()) {
                            val d = repeatDaysArr.optInt(j, -1)
                            if (d in 0..6) repeatDays.add(d)
                        }
                    }

                    val condArr = o.optJSONArray("conditions")
                    val conds = mutableListOf<TaskCondition>()
                    if (condArr != null) {
                        for (j in 0 until condArr.length()) {
                            val c = condArr.optJSONObject(j) ?: continue
                            conds.add(
                                TaskCondition(
                                    type = c.optString("type", ""),
                                    operator = c.optString("operator", "eq"),
                                    value = c.optString("value", ""),
                                    value2 = c.optString("value2", "")
                                )
                            )
                        }
                    }

                    list.add(
                        ScheduledTask(
                            id = o.optString("id", ""),
                            triggerTime = o.optLong("triggerTime", 0L),
                            packageName = o.optString("packageName", ""),
                            appName = o.optString("appName", ""),
                            repeatMode = o.optString("repeatMode", "once"),
                            repeatDays = repeatDays,
                            intervalSeconds = o.optInt("intervalSeconds", 0)
                                .coerceIn(0, MAX_INTERVAL_SECONDS),
                            cronExpression = o.optString("cronExpression", ""),
                            action = o.optString("action", "open_app"),
                            command = o.optString("command", ""),
                            notificationTitle = o.optString("notificationTitle", ""),
                            notificationContent = o.optString("notificationContent", ""),
                            workflowName = o.optString("workflowName", ""),
                            conditions = conds,
                            conditionMode = o.optString("conditionMode", "and"),
                            skipIfNotMet = o.optBoolean("skipIfNotMet", true),
                            // 【F4】缺省 true，兼容旧数据
                            enabled = o.optBoolean("enabled", true),
                            description = o.optString("description", "")
                        )
                    )
                } catch (e: Exception) {
                    skipped++
                    AgentLogger.log(
                        "Schedule",
                        "task[$i] 解析失败，已跳过：${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }
            if (skipped > 0) {
                AgentLogger.log("Schedule", "已跳过 $skipped 个异常任务，保留 ${list.size} 个")
            }
            list.sortedBy { it.triggerTime }
        } catch (e: Exception) {
            AgentLogger.log(
                "Schedule",
                "读取定时任务失败，已备份损坏文件：${e.javaClass.simpleName}: ${e.message}"
            )
            FileRecovery.backupCorrupted(file(context))
            emptyList()
        }
    }

    fun add(context: Context, task: ScheduledTask) {
        val list = list(context).toMutableList()
        list.removeAll { it.id == task.id }
        list.add(task)
        save(context, list)
    }

    fun remove(context: Context, id: String) {
        val list = list(context).toMutableList()
        list.removeAll { it.id == id }
        save(context, list)
    }

    /**
     * 【F4 新增】切换任务的启用状态。
     *
     * Android 的特殊性：AlarmManager 注册是一次性的，禁用时要主动 cancel，
     * 启用时要重新 scheduleAlarm。
     *
     * @return 成功返回 null，失败返回原因
     */
    fun setEnabled(context: Context, id: String, enabled: Boolean): String? {
        return try {
            val list = list(context).toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx < 0) return "任务不存在：$id"
            val old = list[idx]
            val updated = old.copy(enabled = enabled)
            list[idx] = updated
            save(context, list)

            if (enabled) {
                // 启用：重新注册 alarm（除非过去的一次性任务，不需要再排）
                val now = System.currentTimeMillis()
                if (updated.repeatMode != "once" || updated.triggerTime > now) {
                    scheduleAlarm(context, updated)
                }
            } else {
                // 禁用：撤销所有 alarm
                cancelAlarm(context, id)
            }
            null
        } catch (e: Exception) {
            val msg = "切换任务状态失败（$id）：${e.javaClass.simpleName}: ${e.message}"
            AgentLogger.log("ERR", msg)
            msg
        }
    }

    fun save(context: Context, list: List<ScheduledTask>) {
        try {
            val arr = JSONArray()
            list.forEach { t ->
                arr.put(JSONObject().apply {
                    put("id", t.id)
                    put("triggerTime", t.triggerTime)
                    put("packageName", t.packageName)
                    put("appName", t.appName)
                    put("repeatMode", t.repeatMode)
                    put("intervalSeconds", t.intervalSeconds)
                    put("cronExpression", t.cronExpression)
                    put("action", t.action)
                    put("command", t.command)
                    put("notificationTitle", t.notificationTitle)
                    put("notificationContent", t.notificationContent)
                    put("workflowName", t.workflowName)
                    put("conditionMode", t.conditionMode)
                    put("skipIfNotMet", t.skipIfNotMet)
                    // 【F4】序列化 enabled
                    put("enabled", t.enabled)
                    put("description", t.description)
                    put("repeatDays", JSONArray().apply {
                        t.repeatDays.forEach { d -> put(d) }
                    })
                    put("conditions", JSONArray().apply {
                        t.conditions.forEach { c ->
                            put(JSONObject().apply {
                                put("type", c.type)
                                put("operator", c.operator)
                                put("value", c.value)
                                put("value2", c.value2)
                            })
                        }
                    })
                })
            }
            // 【冗余清理 Batch 12b】内联 atomicWrite 收口到 shared/util/JsonFile
            net.xinkong.furagent.shared.util.JsonFile.atomicWrite(file(context), arr.toString())
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "保存定时任务失败（${list.size} 个）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    // ========================================================
    // 闹钟注册
    // ========================================================

    fun pruneExpired(context: Context) {
        val now = System.currentTimeMillis()
        save(context, list(context).filter {
            it.triggerTime > now || it.repeatMode != "once"
        })
    }

    fun reregisterAll(context: Context) {
        val now = System.currentTimeMillis()
        list(context).forEach { task ->
            // 【F4】禁用的任务不注册
            if (!task.enabled) return@forEach
            if (task.repeatMode == "once" && task.triggerTime <= now) return@forEach
            scheduleAlarm(context, task)
        }
    }

    fun scheduleAlarm(context: Context, task: ScheduledTask) {
        // 【F4】兜底：不注册禁用任务的 alarm
        if (!task.enabled) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, TaskReceiver::class.java).apply {
            putExtra("task_id", task.id)
            putExtra("action", task.action)
            putExtra("package_name", task.packageName)
            putExtra("app_name", task.appName)
            putExtra("command", task.command)
            putExtra("notification_title", task.notificationTitle)
            putExtra("notification_content", task.notificationContent)
            putExtra("workflow_name", task.workflowName)
        }
        val requestCode = task.id.hashCode()
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val showIntent = PendingIntent.getActivity(
            context, requestCode,
            Intent(context, net.xinkong.furagent.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            val useAlarmClock = task.repeatMode != "interval" || task.intervalSeconds >= 60
            if (useAlarmClock) {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(task.triggerTime, showIntent),
                    pendingIntent
                )
            } else {
                if (!alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, task.triggerTime, pendingIntent
                    )
                } else {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, task.triggerTime, pendingIntent
                    )
                }
            }
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "注册闹钟失败（${task.id.take(8)} / ${task.repeatMode}）：${e.javaClass.simpleName}: ${e.message}，尝试降级"
            )
            try {
                if (!alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, task.triggerTime, pendingIntent
                    )
                } else {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, task.triggerTime, pendingIntent
                    )
                }
            } catch (e2: Exception) {
                AgentLogger.log(
                    "ERR",
                    "注册闹钟降级失败（${task.id.take(8)}）：${e2.javaClass.simpleName}: ${e2.message}"
                )
            }
        }
    }

    fun cancelAlarm(context: Context, id: String) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val requestCode = id.hashCode()
            val broadcastPi = PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, TaskReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(broadcastPi)
            val showPi = PendingIntent.getActivity(
                context, requestCode,
                Intent(context, net.xinkong.furagent.MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(showPi)
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "取消闹钟失败（${id.take(8)}）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    // ========================================================
    // 下次触发时间
    // ========================================================

    fun calculateNextTrigger(task: ScheduledTask): Long? {
        if (task.repeatMode == "once") return null
        val now = System.currentTimeMillis()

        if (task.repeatMode == "interval") {
            if (task.intervalSeconds <= 0) return null
            val intervalMs = task.intervalSeconds * 1000L
            var next = task.triggerTime + intervalMs
            while (next <= now) next += intervalMs
            return next
        }

        if (task.repeatMode == "cron" && task.cronExpression.isNotBlank()) {
            return nextCronTime(task.cronExpression, now)
        }

        val cal = Calendar.getInstance().apply { timeInMillis = task.triggerTime }

        return when (task.repeatMode) {
            "daily" -> {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                while (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
                cal.timeInMillis
            }
            "weekly" -> {
                if (task.repeatDays.isEmpty()) return null
                repeat(8) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                    val dow = cal.get(Calendar.DAY_OF_WEEK) - 1
                    if (task.repeatDays.contains(dow) && cal.timeInMillis > now) {
                        return cal.timeInMillis
                    }
                }
                null
            }
            else -> null
        }
    }

    fun formatInterval(seconds: Int): String = when {
        seconds <= 0 -> "未设置"
        seconds < 60 -> "每 $seconds 秒"
        seconds % 3600 == 0 -> "每 ${seconds / 3600} 小时"
        seconds % 60 == 0 -> "每 ${seconds / 60} 分钟"
        else -> "每 $seconds 秒"
    }

    // ========================================================
    // Cron 解析
    // ========================================================

    /**
     * 【冗余清理】cron 解析抽到 shared/util/CronParser。
     * 调用点保持不变（calculateNextTrigger 里依然调 nextCronTime）。
     */
    private fun nextCronTime(expr: String, fromMs: Long): Long? =
        net.xinkong.furagent.shared.util.CronParser.nextCronTime(expr, fromMs)

    // ========================================================
    // 条件评估
    // ========================================================

    fun evaluateConditions(context: Context, task: ScheduledTask): ConditionResult {
        if (task.conditions.isEmpty()) return ConditionResult(true)

        val results = task.conditions.map { evaluate(context, it) }

        return if (task.conditionMode == "or") {
            if (results.any { it.met }) ConditionResult(true)
            else ConditionResult(
                false,
                "所有 OR 条件都不满足：" + results.joinToString(" | ") { it.reason }
            )
        } else {
            val firstFail = results.firstOrNull { !it.met }
            if (firstFail != null) ConditionResult(false, firstFail.reason)
            else ConditionResult(true)
        }
    }

    private fun evaluate(context: Context, cond: TaskCondition): ConditionResult {
        return try {
            when (cond.type) {
                "battery_level" -> evalNumeric(getBatteryLevel(context).toDouble(), cond)
                "is_charging" -> evalBool(isCharging(context), cond)
                "wifi_connected" -> evalBool(isWifiConnected(context), cond)
                "wifi_ssid" -> evalString(getWifiSsid(context), cond)
                "screen_on" -> evalBool(isScreenOn(context), cond)
                "time_between" -> evalTimeBetween(cond)
                "weekday_in" -> evalWeekdayIn(cond)
                "app_installed" -> evalBool(isAppInstalled(context, cond.value), cond)
                "network_type" -> evalString(getNetworkType(context), cond)
                "network_available" -> evalBool(isNetworkAvailable(context), cond)
                else -> ConditionResult(false, "未知条件类型：${cond.type}")
            }
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "条件 ${cond.type} 评估异常：${e.javaClass.simpleName}: ${e.message}"
            )
            ConditionResult(false, "条件 ${cond.type} 评估异常：${e.message}")
        }
    }

    private fun evalNumeric(actual: Double, cond: TaskCondition): ConditionResult {
        val target = cond.value.toDoubleOrNull()
            ?: return ConditionResult(false, "条件值不是数字：${cond.value}")
        val ok = when (cond.operator) {
            "eq" -> actual == target
            "ne" -> actual != target
            "gt" -> actual > target
            "lt" -> actual < target
            "gte" -> actual >= target
            "lte" -> actual <= target
            "between" -> {
                val target2 = cond.value2.toDoubleOrNull()
                    ?: return ConditionResult(false, "between 需要 value2")
                actual in target..target2
            }
            else -> return ConditionResult(false, "数值不支持的操作符：${cond.operator}")
        }
        return if (ok) ConditionResult(true)
        else ConditionResult(
            false,
            "${cond.type} 当前=$actual，不满足 ${cond.operator} ${cond.value}" +
                    if (cond.value2.isNotEmpty()) "..${cond.value2}" else ""
        )
    }

    private fun evalBool(actual: Boolean, cond: TaskCondition): ConditionResult {
        val target = cond.value.lowercase() in listOf("true", "1", "yes", "是", "on")
        val ok = if (cond.operator == "ne") actual != target else actual == target
        return if (ok) ConditionResult(true)
        else ConditionResult(
            false,
            "${cond.type} 当前=$actual，期望${if (cond.operator == "ne") "不等于" else "等于"} $target"
        )
    }

    private fun evalString(actual: String?, cond: TaskCondition): ConditionResult {
        val a = actual ?: ""
        val ok = when (cond.operator) {
            "eq" -> a == cond.value
            "ne" -> a != cond.value
            "contains" -> a.contains(cond.value)
            "matches" -> try {
                Regex(cond.value).containsMatchIn(a)
            } catch (_: Exception) { false }
            "in" -> cond.value.split(',').map { it.trim() }.contains(a)
            else -> a == cond.value
        }
        return if (ok) ConditionResult(true)
        else ConditionResult(false, "${cond.type} 当前='$a'，不满足 ${cond.operator} '${cond.value}'")
    }

    private fun evalTimeBetween(cond: TaskCondition): ConditionResult {
        val parts = cond.value.split('-').map { it.trim() }
        if (parts.size != 2) return ConditionResult(false, "time_between 需要 HH:mm-HH:mm")
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val startMin = try {
            val p = parts[0].split(':')
            p[0].toInt() * 60 + p[1].toInt()
        } catch (_: Exception) { return ConditionResult(false, "无效起始时间 ${parts[0]}") }
        val endMin = try {
            val p = parts[1].split(':')
            p[0].toInt() * 60 + p[1].toInt()
        } catch (_: Exception) { return ConditionResult(false, "无效结束时间 ${parts[1]}") }

        val ok = if (startMin <= endMin) nowMin in startMin..endMin
        else nowMin !in (endMin + 1)..<startMin
        val hh = String.format(Locale.US, "%02d:%02d", nowMin / 60, nowMin % 60)
        return if (ok) ConditionResult(true)
        else ConditionResult(false, "当前时间 $hh 不在 ${cond.value} 内")
    }

    private fun evalWeekdayIn(cond: TaskCondition): ConditionResult {
        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1
        val days = cond.value.split(',').mapNotNull { it.trim().toIntOrNull() }
        val ok = days.contains(today)
        val names = listOf("日", "一", "二", "三", "四", "五", "六")
        return if (ok) ConditionResult(true)
        else ConditionResult(false, "今天是周${names[today]}，不在 ${cond.value} 内")
    }

    // ========================================================
    // 环境读取
    // ========================================================

    private fun getBatteryLevel(context: Context): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) { -1 }
    }

    private fun isCharging(context: Context): Boolean {
        return try {
            val intent = context.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
        } catch (_: Exception) { false }
    }

    private fun isWifiConnected(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (_: Exception) { false }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) { false }
    }

    @Suppress("DEPRECATION")
    private fun getWifiSsid(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(net) ?: return null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi.connectionInfo?.ssid?.trim('"')
        } catch (_: Exception) { null }
    }

    private fun isScreenOn(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isInteractive
        } catch (_: Exception) { true }
    }

    private fun getNetworkType(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return "none"
            val caps = cm.getNetworkCapabilities(net) ?: return "none"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
        } catch (_: Exception) { "unknown" }
    }

    private fun isAppInstalled(context: Context, pkg: String): Boolean {
        return pkg.isNotBlank() && try {
            context.packageManager.getLaunchIntentForPackage(pkg) != null
        } catch (_: Exception) { false }
    }
}