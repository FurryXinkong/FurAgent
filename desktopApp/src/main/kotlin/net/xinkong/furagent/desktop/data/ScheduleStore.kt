package net.xinkong.furagent.desktop.data

import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.shared.util.ConditionEvaluator
import net.xinkong.furagent.shared.util.ConditionResult
import net.xinkong.furagent.shared.util.DateFormats
import net.xinkong.furagent.shared.util.FileRecovery
import net.xinkong.furagent.shared.util.TaskCondition
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.util.*
import java.util.concurrent.TimeUnit

// TaskCondition / ConditionResult 已移入 shared/util/ConditionEvaluator.kt

data class ScheduledTask(
    val id: String,
    val triggerTime: Long,
    val repeatMode: String = "once",
    val repeatDays: List<Int> = emptyList(),
    val intervalSeconds: Int = 0,
    val cronExpression: String = "",
    val action: String = "notify",

    val toolName: String = "",
    val toolArgs: String = "",
    val workflowName: String = "",
    val appPath: String = "",
    val command: String = "",
    val notificationTitle: String = "",
    val notificationContent: String = "",

    val conditions: List<TaskCondition> = emptyList(),
    val conditionMode: String = "and",
    val skipIfNotMet: Boolean = true,

    /**
     * 是否启用。
     * true（默认）：TaskScheduler 正常调度。
     * false：TaskScheduler.tick() 跳过，UI 显示为禁用态。
     * 反序列化缺省 true，兼容旧数据。
     */
    val enabled: Boolean = true,

    val description: String = "",

    /**
     * 能力对齐 Android run_agent 动作专用：要发给 AI 的 prompt。
     * action == "run_agent" 时必填。
     */
    val prompt: String = "",
    /**
     * 能力对齐 Android 绑定到特定会话 ID。
     * 非空时 run_agent 结果作为 assistant 消息写回该会话；空时走系统通知。
     */
    val sessionId: String = ""
)

object ScheduleStore {
    private const val FILE_NAME = "schedules.json"

    // 条件探测结果缓存时长：调度器每秒 tick 一次，条件若每次都真去起进程
    // （尤其 PowerShell，冷启动 1-2 秒）会拖垮整个 tick 循环
    private const val COND_CACHE_TTL_MS = 10_000L

    // 从 shared 统一常量，双端一致
    const val MIN_INTERVAL_SECONDS = net.xinkong.furagent.shared.data.ScheduleLimits.MIN_INTERVAL_SECONDS
    const val MAX_INTERVAL_SECONDS = net.xinkong.furagent.shared.data.ScheduleLimits.MAX_INTERVAL_SECONDS

    private fun file(): File = File(DesktopPaths.rootDir(), FILE_NAME)

    private fun atomicWrite(target: File, content: String) {
        net.xinkong.furagent.shared.util.JsonFile.atomicWrite(target, content)
    }

    fun list(): List<ScheduledTask> {
        return try {
            val f = file()
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val list = mutableListOf<ScheduledTask>()
            var skipped = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o == null) { skipped++; continue }
                // 单个任务容错：一个任务坏掉不应把全部定时任务冲掉
                try {
                    val daysArr = o.optJSONArray("repeatDays")
                    val days = mutableListOf<Int>()
                    if (daysArr != null) {
                        for (j in 0 until daysArr.length()) {
                            daysArr.optInt(j, -1).takeIf { it in 0..6 }?.let { days.add(it) }
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
                            repeatMode = o.optString("repeatMode", "once"),
                            repeatDays = days,
                            intervalSeconds = o.optInt("intervalSeconds", 0)
                                .coerceIn(0, MAX_INTERVAL_SECONDS),
                            cronExpression = o.optString("cronExpression", ""),
                            action = o.optString("action", "notify"),
                            toolName = o.optString("toolName", ""),
                            toolArgs = o.optString("toolArgs", ""),
                            workflowName = o.optString("workflowName", ""),
                            appPath = o.optString("appPath", ""),
                            command = o.optString("command", ""),
                            notificationTitle = o.optString("notificationTitle", ""),
                            notificationContent = o.optString("notificationContent", ""),
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
                    DesktopLogger.warn("Schedule", "task[$i] 解析失败，已跳过：${e.message}")
                }
            }
            if (skipped > 0) {
                DesktopLogger.warn("Schedule", "已跳过 $skipped 个异常任务，保留 ${list.size} 个")
            }
            list.sortedBy { it.triggerTime }
        } catch (e: Exception) {
            DesktopLogger.error(
                "Schedule",
                "读取定时任务失败，已备份损坏文件：${e.javaClass.simpleName}: ${e.message}"
            )
            FileRecovery.backupCorrupted(file())
            emptyList()
        }
    }

    fun add(task: ScheduledTask) {
        val list = list().toMutableList()
        list.removeAll { it.id == task.id }
        list.add(task)
        saveAll(list)
    }

    fun delete(id: String) {
        val list = list().toMutableList()
        list.removeAll { it.id == id }
        saveAll(list)
    }

    /**
     * 切换任务的启用状态。
     * 桌面端 TaskScheduler 是纯轮询，不需要注册/取消；只需写盘即可。
     */
    fun setEnabled(id: String, enabled: Boolean): String? {
        return try {
            val list = list().toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx < 0) return "任务不存在：$id"
            list[idx] = list[idx].copy(enabled = enabled)
            saveAll(list)
            null
        } catch (e: Exception) {
            val msg = "切换任务状态失败（$id）：${e.javaClass.simpleName}: ${e.message}"
            DesktopLogger.error("Schedule", msg)
            msg
        }
    }

    fun batchUpdate(updates: List<ScheduledTask>, deletes: List<String>) {
        if (updates.isEmpty() && deletes.isEmpty()) return
        try {
            val current = list().toMutableList()
            if (deletes.isNotEmpty()) {
                val deleteSet = deletes.toSet()
                current.removeAll { it.id in deleteSet }
            }
            for (t in updates) {
                current.removeAll { it.id == t.id }
                current.add(t)
            }
            saveAll(current)
        } catch (e: Exception) {
            DesktopLogger.error(
                "Schedule",
                "批量更新失败（${updates.size} 更新 / ${deletes.size} 删除）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun saveAll(list: List<ScheduledTask>) {
        try {
            val arr = JSONArray()
            list.forEach { t ->
                arr.put(JSONObject().apply {
                    put("id", t.id)
                    put("triggerTime", t.triggerTime)
                    put("repeatMode", t.repeatMode)
                    put("intervalSeconds", t.intervalSeconds)
                    put("cronExpression", t.cronExpression)
                    put("action", t.action)
                    put("toolName", t.toolName)
                    put("toolArgs", t.toolArgs)
                    put("workflowName", t.workflowName)
                    put("appPath", t.appPath)
                    put("command", t.command)
                    put("notificationTitle", t.notificationTitle)
                    put("notificationContent", t.notificationContent)
                    put("conditionMode", t.conditionMode)
                    put("skipIfNotMet", t.skipIfNotMet)
                    // 序列化 enabled
                    put("enabled", t.enabled)
                    put("description", t.description)
                    put("prompt", t.prompt)
                    put("sessionId", t.sessionId)
                    put("repeatDays", JSONArray().apply {
                        t.repeatDays.forEach { put(it) }
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
            atomicWrite(file(), arr.toString())
        } catch (e: Exception) {
            DesktopLogger.error(
                "Schedule",
                "保存定时任务失败（${list.size} 个）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun calculateNextTrigger(task: ScheduledTask, now: Long = System.currentTimeMillis()): Long? {
        if (task.repeatMode == "once") return null

        if (task.repeatMode == "interval") {
            if (task.intervalSeconds < MIN_INTERVAL_SECONDS) return null
            val step = task.intervalSeconds * 1000L
            var next = task.triggerTime + step
            while (next <= now) next += step
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

    /**
     * cron 解析抽到 shared/util/CronParser。
     * 调用点保持不变（calculateNextTrigger 里依然调 nextCronTime）。
     */
    private fun nextCronTime(expr: String, fromMs: Long): Long? =
        net.xinkong.furagent.shared.util.CronParser.nextCronTime(expr, fromMs)

    // 条件评估（与 Android 端对齐）

    fun evaluateConditions(task: ScheduledTask): ConditionResult {
        if (task.conditions.isEmpty()) return ConditionResult(true)
        val results = task.conditions.map { evaluateOne(it) }
        return if (task.conditionMode == "or") {
            if (results.any { it.met }) ConditionResult(true)
            else ConditionResult(false, "所有 OR 条件都不满足")
        } else {
            val fail = results.firstOrNull { !it.met }
            if (fail != null) ConditionResult(false, fail.reason)
            else ConditionResult(true)
        }
    }

    private fun evaluateOne(cond: TaskCondition): ConditionResult {
        return try {
            when (cond.type) {
                "time_between" -> evalTimeBetween(cond)
                "weekday_in" -> evalWeekdayIn(cond)
                "network_available" -> evalNetworkAvailable(cond)
                "process_running" -> evalProcessRunning(cond)
                "command_matches" -> evalCommandMatches(cond)
                // 与 Android 端对齐
                "battery_level" -> evalNumeric(getBatteryLevel().toDouble(), cond)
                "is_charging" -> evalBool(isCharging(), cond)
                "wifi_connected" -> evalBool(isWifiConnected(), cond)
                "wifi_ssid" -> evalString(getWifiSsid(), cond)
                "screen_on" -> evalBool(isScreenOn(), cond)
                "network_type" -> evalString(getNetworkType(), cond)
                "app_installed" -> evalBool(isAppInstalled(cond.value), cond)
                else -> ConditionResult(false, "未知条件类型：${cond.type}")
            }
        } catch (e: Exception) {
            DesktopLogger.error(
                "Schedule",
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

    private fun evalNetworkAvailable(cond: TaskCondition): ConditionResult {
        val want = cond.value.lowercase() in listOf("true", "1", "yes", "是")
        val actual = try {
            val addrs = java.net.NetworkInterface.getNetworkInterfaces().toList()
            addrs.any { ni ->
                ni.isUp && !ni.isLoopback &&
                        ni.inetAddresses.toList().any { !it.isLoopbackAddress }
            }
        } catch (_: Exception) { false }
        val ok = if (cond.operator == "ne") actual != want else actual == want
        return if (ok) ConditionResult(true)
        else ConditionResult(false, "网络可用=$actual，期望=$want")
    }

    private val condCacheLock = Any()
    private val condCache = HashMap<String, Pair<Long, ConditionResult>>()

    private fun cachedCond(key: String, producer: () -> ConditionResult): ConditionResult {
        synchronized(condCacheLock) {
            val now = System.currentTimeMillis()
            condCache[key]?.let { (at, r) ->
                if (now - at < COND_CACHE_TTL_MS) return r
            }
            if (condCache.size > 64) {
                condCache.entries.removeAll { now - it.value.first >= COND_CACHE_TTL_MS }
            }
        }
        val fresh = producer()
        synchronized(condCacheLock) {
            condCache[key] = System.currentTimeMillis() to fresh
        }
        return fresh
    }

    private fun evalProcessRunning(cond: TaskCondition): ConditionResult {
        val keyword = cond.value.trim()
        if (keyword.isBlank()) return ConditionResult(false, "process_running 需要进程名")
        return cachedCond("process:${cond.operator}:${cond.value.trim()}") {
            evalProcessRunningUncached(cond)
        }
    }

    private fun evalProcessRunningUncached(cond: TaskCondition): ConditionResult {
        val keyword = cond.value.trim()
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        val cmd = if (os.contains("win")) arrayOf("cmd.exe", "/c", "tasklist")
        else arrayOf("sh", "-c", "ps -A")
        val output = try {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val txt = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor(5, TimeUnit.SECONDS)
            txt
        } catch (_: Exception) { "" }

        val found = output.contains(keyword, ignoreCase = true)
        val ok = if (cond.operator == "ne") !found else found
        return if (ok) ConditionResult(true)
        else ConditionResult(false, "进程 '$keyword' " + if (found) "在运行" else "未找到")
    }

    private fun evalCommandMatches(cond: TaskCondition): ConditionResult {
        val sep = cond.value.indexOf('|')
        if (sep <= 0) return ConditionResult(false, "command_matches 需要 命令|正则")
        val cmd = cond.value.substring(0, sep).trim()
        val regex = cond.value.substring(sep + 1).trim()
        if (cmd.isBlank() || regex.isBlank()) return ConditionResult(false, "command_matches 参数为空")
        return cachedCond("cmd:${cond.value.trim()}") { evalCommandMatchesUncached(cmd, regex) }
    }

    private fun evalCommandMatchesUncached(cmd: String, regex: String): ConditionResult {

        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        val shell = if (os.contains("win")) arrayOf("cmd.exe", "/c", cmd)
        else arrayOf("sh", "-c", cmd)
        val output = try {
            val p = ProcessBuilder(*shell).redirectErrorStream(true).start()
            val txt = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor(10, TimeUnit.SECONDS)
            txt
        } catch (_: Exception) { "" }

        val ok = try {
            Regex(regex).containsMatchIn(output)
        } catch (_: Exception) { false }

        return if (ok) ConditionResult(true)
        else ConditionResult(false, "命令输出未匹配 /$regex/")
    }

    // 环境读取（与 Android 端对齐）

    private fun isWin(): Boolean =
        System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")

    /**
     * 执行一段 PowerShell 脚本，返回 stdout。
     *
     * 固定用 furagent_cond.ps1 + synchronized 串行，脚本落 ~/.furagent/temp/。
     */
    private val psLock = Any()
    private val psScriptFile: File by lazy {
        File(DesktopPaths.tempDir(), "furagent_cond.ps1")
    }

    private fun runPs(script: String, timeoutSec: Int = 8): String? {
        if (!isWin()) return null
        synchronized(psLock) {
            return try {
                psScriptFile.outputStream().use { out ->
                    out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                    out.write(
                        ("[Console]::OutputEncoding = [System.Text.Encoding]::UTF8\r\n$script")
                            .toByteArray(Charsets.UTF_8)
                    )
                }
                try {
                    val p = ProcessBuilder(
                        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                        "-File", psScriptFile.absolutePath
                    ).redirectErrorStream(true).start()
                    val finished = p.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
                    if (!finished) {
                        p.destroyForcibly()
                        return null
                    }
                    p.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
                } finally {
                    try { psScriptFile.delete() } catch (_: Exception) { }
                }
            } catch (_: Exception) { null }
        }
    }

    private fun getBatteryLevel(): Int {
        if (!isWin()) return -1
        val out = runPs(
            $$"$b = Get-CimInstance Win32_Battery -ErrorAction SilentlyContinue | Select-Object -First 1; " +
                    $$"if ($b) { Write-Output $b.EstimatedChargeRemaining } else { Write-Output -1 }"
        ) ?: return -1
        return out.trim().toIntOrNull() ?: -1
    }

    private fun isCharging(): Boolean {
        if (!isWin()) return false
        val out = runPs(
            $$"$b = Get-CimInstance Win32_Battery -ErrorAction SilentlyContinue | Select-Object -First 1; " +
                    $$"if ($b) { Write-Output $b.BatteryStatus } else { Write-Output 0 }"
        ) ?: return false
        val status = out.trim().toIntOrNull() ?: 0
        return status == 2 || status == 3 || status == 6 || status == 7 || status == 8 || status == 9
    }

    private fun isWifiConnected(): Boolean {
        if (isWin()) {
            val out = runPs(
                $$"$w = Get-NetAdapter -ErrorAction SilentlyContinue | " +
                        $$"Where-Object { $_.InterfaceDescription -like '*Wireless*' -or $_.Name -like '*Wi-Fi*' -or $_.Name -like '*WLAN*' } | " +
                        $$"Select-Object -First 1; if ($w) { Write-Output $w.Status } else { Write-Output 'NotFound' }"
            ) ?: return false
            return out.contains("Up", ignoreCase = true)
        }
        return try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .any { ni ->
                    val name = ni.name.lowercase(Locale.ROOT)
                    (name.startsWith("wlan") || name.startsWith("wl") || name == "en0") &&
                            ni.isUp && !ni.isLoopback
                }
        } catch (_: Exception) { false }
    }

    private fun getWifiSsid(): String? {
        if (isWin()) {
            val out = runPs(
                "netsh wlan show interfaces | Select-String -Pattern '^\\s*SSID\\s*:' | Select-Object -First 1"
            ) ?: return null
            val idx = out.indexOf(':')
            if (idx < 0) return null
            return out.substring(idx + 1).trim().ifBlank { null }
        }
        return null
    }

    private fun isScreenOn(): Boolean {
        if (!isWin()) {
            return true
        }
        val out = runPs("""
            Add-Type -TypeDefinition @"
            using System;
            using System.Runtime.InteropServices;
            public struct LASTINPUTINFO {
                public uint cbSize;
                public uint dwTime;
            }
            public class Idle {
                [DllImport("user32.dll")]
                public static extern bool GetLastInputInfo(ref LASTINPUTINFO plii);
                public static uint GetIdle() {
                    LASTINPUTINFO lii = new LASTINPUTINFO();
                    lii.cbSize = (uint)System.Runtime.InteropServices.Marshal.SizeOf(lii);
                    GetLastInputInfo(ref lii);
                    return ((uint)Environment.TickCount - lii.dwTime);
                }
            }
"@
            Write-Output ([Idle]::GetIdle())
        """.trimIndent()) ?: return true
        val idleMs = out.trim().toLongOrNull() ?: return true
        return idleMs < 5 * 60 * 1000L
    }

    private fun getNetworkType(): String {
        return try {
            val addrs = java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
            val hasEthernet = addrs.any { ni ->
                val name = ni.name.lowercase(Locale.ROOT)
                (name.startsWith("eth") || name.startsWith("en") && name != "en0") &&
                        ni.inetAddresses.toList().any { it is Inet4Address && !it.isLoopbackAddress }
            }
            val hasWifi = addrs.any { ni ->
                val name = ni.name.lowercase(Locale.ROOT)
                (name.startsWith("wlan") || name.startsWith("wl") || name == "en0") &&
                        ni.inetAddresses.toList().any { it is Inet4Address && !it.isLoopbackAddress }
            }
            when {
                hasEthernet -> "ethernet"
                hasWifi -> "wifi"
                addrs.isEmpty() -> "none"
                else -> "other"
            }
        } catch (_: Exception) { "unknown" }
    }

    private fun isAppInstalled(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        return try {
            when {
                os.contains("win") -> {
                    val out = runPs(
                        $$"$found = Get-ItemProperty HKLM:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\* -ErrorAction SilentlyContinue | " +
                                $$"Where-Object { $_.DisplayName -like '*$$pkg*' -or $_.InstallLocation -like '*$$pkg*' } | " +
                                $$"Select-Object -First 1; if ($found) { Write-Output 'YES' } else { Write-Output 'NO' }"
                    )
                    out?.contains("YES") == true
                }
                os.contains("mac") -> File("/Applications/$pkg.app").exists()
                else -> {
                    val candidates = listOf(
                        "/usr/share/applications/$pkg.desktop",
                        "/usr/local/share/applications/$pkg.desktop",
                        System.getProperty("user.home") + "/.local/share/applications/$pkg.desktop"
                    )
                    candidates.any { File(it).exists() } || runCatching {
                        val p = ProcessBuilder("sh", "-c", "which $pkg").redirectErrorStream(true).start()
                        p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0
                    }.getOrDefault(false)
                }
            }
        } catch (_: Exception) { false }
    }

    fun formatTime(ms: Long): String = DateFormats.formatDateTime(ms)
}