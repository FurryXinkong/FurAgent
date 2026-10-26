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
import net.xinkong.furagent.shared.util.ConditionEvaluator
import net.xinkong.furagent.system.ShizukuHelper
import net.xinkong.furagent.shared.util.ConditionResult
import net.xinkong.furagent.shared.util.FileRecovery
import net.xinkong.furagent.shared.util.TaskCondition
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*

// TaskCondition / ConditionResult 已移入 shared/util/ConditionEvaluator.kt

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
    /**
     * 统一动作枚举（与桌面端一致）：
     * notify / run_command / run_workflow / open_app / run_tool / run_agent
     * 旧数据里的 send_notification / run_cron 读入时自动归一。
     */
    val action: String = "open_app",
    val command: String = "",
    /** run_tool 专用：工具名与 JSON 参数（与桌面端字段对齐） */
    val toolName: String = "",
    val toolArgs: String = "",
    /** 桌面端 open_app 用的路径字段；Android 用 packageName，此字段仅保数据互通不丢 */
    val appPath: String = "",
    val notificationTitle: String = "",
    val notificationContent: String = "",
    val workflowName: String = "",
    val conditions: List<TaskCondition> = emptyList(),
    val conditionMode: String = "and",
    val skipIfNotMet: Boolean = true,
    /**
     * 是否启用。
     * true（默认）：正常注册 AlarmManager，TaskReceiver 正常执行。
     * false：不注册 alarm（setEnabled 时会 cancel），TaskReceiver 会跳过。
     * 反序列化缺省 true，兼容旧数据。
     */
    val enabled: Boolean = true,
    val description: String = "",
    /**
     * AI 主动说话 run_agent 动作专用：要发给 AI 的 prompt。
     * action == "run_agent" 时必填。
     */
    val prompt: String = "",
    /**
     * 会话级任务 绑定到特定会话 ID。
     * 非空时：
     *   - run_agent 结果写回该会话（作为 assistant 消息）
     *   - 会话删除时自动清理关联任务
     * 空时为全局任务，结果走系统通知。
     */
    val sessionId: String = ""
)

object ScheduleStore {
    private const val FILE_NAME = "schedules.json"

    // 从 shared 统一常量，双端一致
    const val MIN_INTERVAL_SECONDS = net.xinkong.furagent.shared.data.ScheduleLimits.MIN_INTERVAL_SECONDS
    const val MAX_INTERVAL_SECONDS = net.xinkong.furagent.shared.data.ScheduleLimits.MAX_INTERVAL_SECONDS

    /** 双端统一动作枚举（数据以桌面端为规范） */
    val VALID_ACTIONS = listOf(
        "notify", "run_command", "run_workflow", "open_app", "run_tool", "run_agent"
    )

    /** 旧动作名 → 统一名（读旧数据 / 旧 intent 兼容；写出一律用统一名） */
    fun normalizeAction(raw: String): String = when (raw) {
        "send_notification" -> "notify"
        "run_cron" -> "run_command"
        else -> raw
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    // 序列化

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
                            action = normalizeAction(o.optString("action", "open_app")),
                            command = o.optString("command", ""),
                            toolName = o.optString("toolName", ""),
                            toolArgs = o.optString("toolArgs", ""),
                            appPath = o.optString("appPath", ""),
                            notificationTitle = o.optString("notificationTitle", ""),
                            notificationContent = o.optString("notificationContent", ""),
                            workflowName = o.optString("workflowName", ""),
                            conditions = conds,
                            conditionMode = o.optString("conditionMode", "and"),
                            skipIfNotMet = o.optBoolean("skipIfNotMet", true),
                            // 缺省 true，兼容旧数据
                            enabled = o.optBoolean("enabled", true),
                            description = o.optString("description", ""),
                            prompt = o.optString("prompt", ""),
                            sessionId = o.optString("sessionId", "")
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
     * 性能 只更新单个任务的 triggerTime。
     *
     * 定时任务每次触发后都要排下次，旧实现走 add() → list() + save()，
     * 每次都要反序列化 + 序列化整个 schedules.json。任务一多就是 O(n²)。
     * 这里只改内存里那一条再整体写一次，语义与 add 相同但意图明确，
     * 且不会因为 add 的 removeAll 逻辑误删同 id 的其他字段。
     *
     * @return true 找到并更新，false 任务不存在
     */
    fun updateTriggerTime(context: Context, id: String, triggerTime: Long): Boolean {
        val list = list(context).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        list[idx] = list[idx].copy(triggerTime = triggerTime)
        save(context, list)
        return true
    }

    /**
     * 切换任务的启用状态。
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
                    put("action", normalizeAction(t.action))
                    put("command", t.command)
                    put("toolName", t.toolName)
                    put("toolArgs", t.toolArgs)
                    put("appPath", t.appPath)
                    put("notificationTitle", t.notificationTitle)
                    put("notificationContent", t.notificationContent)
                    put("workflowName", t.workflowName)
                    put("conditionMode", t.conditionMode)
                    put("skipIfNotMet", t.skipIfNotMet)
                    // 序列化 enabled
                    put("enabled", t.enabled)
                    put("description", t.description)
                    put("prompt", t.prompt)
                    put("sessionId", t.sessionId)
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
            net.xinkong.furagent.shared.util.JsonFile.atomicWrite(file(context), arr.toString())
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "保存定时任务失败（${list.size} 个）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    // 闹钟注册

    fun pruneExpired(context: Context) {
        val now = System.currentTimeMillis()
        save(context, list(context).filter {
            it.triggerTime > now || it.repeatMode != "once"
        })
    }

    fun reregisterAll(context: Context) {
        val now = System.currentTimeMillis()
        list(context).forEach { task ->
            // 禁用的任务不注册
            if (!task.enabled) return@forEach
            if (task.repeatMode == "once" && task.triggerTime <= now) return@forEach
            scheduleAlarm(context, task)
        }
    }

    fun scheduleAlarm(context: Context, task: ScheduledTask) {
        // 兜底：不注册禁用任务的 alarm
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

    // 下次触发时间

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

    // Cron 解析

    /**
     * cron 解析抽到 shared/util/CronParser。
     * 调用点保持不变（calculateNextTrigger 里依然调 nextCronTime）。
     */
    private fun nextCronTime(expr: String, fromMs: Long): Long? =
        net.xinkong.furagent.shared.util.CronParser.nextCronTime(expr, fromMs)

    // 条件评估

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
                "process_running" -> evalProcessRunning(cond)
                "command_matches" -> evalCommandMatches(cond)
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

    private fun evalNumeric(actual: Double, cond: TaskCondition): ConditionResult =
        ConditionEvaluator.evalNumeric(actual, cond)

    private fun evalBool(actual: Boolean, cond: TaskCondition): ConditionResult =
        ConditionEvaluator.evalBool(actual, cond)

    private fun evalString(actual: String?, cond: TaskCondition): ConditionResult =
        ConditionEvaluator.evalString(actual, cond)

    private fun evalTimeBetween(cond: TaskCondition): ConditionResult =
        ConditionEvaluator.evalTimeBetween(cond)

    private fun evalWeekdayIn(cond: TaskCondition): ConditionResult =
        ConditionEvaluator.evalWeekdayIn(cond)

    /**
     * process_running：检查进程是否在运行（需要 Shizuku，非 root 时普通 App 只能看到自己的进程）。
     * value 是进程名关键词（支持部分匹配，如 "com.tencent"）。
     */
    private fun evalProcessRunning(cond: TaskCondition): ConditionResult {
        val keyword = cond.value.trim()
        if (keyword.isBlank()) return ConditionResult(false, "process_running 需要进程名")
        if (!ShizukuHelper.isAvailable() || !ShizukuHelper.hasPermission()) {
            return ConditionResult(false, "process_running 需要 Shizuku 权限")
        }
        return try {
            val output = ShizukuHelper.executeCommand("ps -A", timeoutSeconds = 10)
            val found = output.contains(keyword, ignoreCase = true)
            val ok = if (cond.operator == "ne") !found else found
            if (ok) ConditionResult(true)
            else ConditionResult(false, "进程 '$keyword' " + if (found) "在运行" else "未找到")
        } catch (e: Exception) {
            AgentLogger.log("ERR", "查询进程失败：${e.message}")
            ConditionResult(false, "查询进程失败：${e.message}")
        }
    }

    /**
     * command_matches：跑一条 shell 命令并匹配输出正则（需要 Shizuku）。
     * value 格式："命令|正则"。
     */
    private fun evalCommandMatches(cond: TaskCondition): ConditionResult {
        val sep = cond.value.indexOf('|')
        if (sep <= 0) return ConditionResult(false, "command_matches 需要 命令|正则")
        val cmd = cond.value.substring(0, sep).trim()
        val regex = cond.value.substring(sep + 1).trim()
        if (cmd.isBlank() || regex.isBlank()) return ConditionResult(false, "command_matches 参数为空")
        if (!ShizukuHelper.isAvailable() || !ShizukuHelper.hasPermission()) {
            return ConditionResult(false, "command_matches 需要 Shizuku 权限")
        }
        return try {
            val output = ShizukuHelper.executeCommand(cmd, timeoutSeconds = 10)
            val ok = try { Regex(regex).containsMatchIn(output) } catch (_: Exception) { false }
            if (ok) ConditionResult(true)
            else ConditionResult(false, "命令输出未匹配 /$regex/")
        } catch (e: Exception) {
            AgentLogger.log("ERR", "command_matches 执行失败：${e.message}")
            ConditionResult(false, "命令执行失败：${e.message}")
        }
    }

    // 环境读取

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