package net.xinkong.furagent.desktop.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.desktop.data.DesktopPaths
import net.xinkong.furagent.desktop.system.DesktopNotifier
import net.xinkong.furagent.desktop.system.SmtcController
import net.xinkong.furagent.shared.tools.ToolResult
import net.xinkong.furagent.shared.util.formatFileSize
import org.json.JSONObject
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * 系统集成工具（system）。
 *
 * 从原 SystemIntegrationTools 拆分而来，只保留 system 工具。
 * apps / screenshot 已拆到 AppTools / ScreenshotTools。
 *
 * 临时文件策略
 *  - PowerShell 脚本固定落 ~/.furagent/temp/furagent_sys.ps1
 *    （避免 File.createTempFile 落到系统 %TEMP% 导致 TempCleaner 无法清理）
 *  - 用 synchronized 串行保护 + try-finally 删除
 */
object SystemIntegrationTools {

    data class ToolDef(
        val name: String, val displayName: String,
        val description: String, val paramsSchema: String
    )

    val TOOLS: List<ToolDef> = listOf(
        ToolDef(
            "system", "系统集成",
            "系统集成。" +
                    "op=info 读取系统信息（OS/CPU/内存/GPU/主板/磁盘）；" +
                    "op=volume 音量控制（action=up/down/mute，可选 step）；" +
                    "op=media 媒体控制（action=play_pause/next/prev/stop/play，Windows 优先走 SMTC，回退键盘事件）；" +
                    "op=media_info 读取当前正在播放的媒体信息（标题/艺术家/专辑/来源应用/进度/时长，仅 Windows SMTC）；" +
                    "op=window 窗口控制（action=list/activate/minimize/maximize/close，除 list 外需 match）；" +
                    "op=power 电源控制（action=shutdown/reboot/hibernate/sleep/logout/cancel，可选 delay_seconds）；" +
                    "op=lock 锁屏；" +
                    "op=notify 弹系统通知（title，content）；" +
                    "op=clipboard_read 读剪贴板；" +
                    "op=clipboard_write 写剪贴板（text）；" +
                    "op=wallpaper 设置壁纸（path）。",
            """{"type":"object","properties":{"op":{"type":"string","enum":["info","volume","media","media_info","window","power","lock","notify","clipboard_read","clipboard_write","wallpaper"]},"action":{"type":"string"},"step":{"type":"integer"},"match":{"type":"string"},"delay_seconds":{"type":"integer"},"title":{"type":"string"},"content":{"type":"string"},"text":{"type":"string"},"path":{"type":"string"}},"required":["op"]}"""
        )
    )

    val NAMES: Set<String> = TOOLS.map { it.name }.toSet()

    fun displayName(name: String): String =
        TOOLS.firstOrNull { it.name == name }?.displayName ?: name

    suspend fun execute(name: String, args: JSONObject): ToolResult? {
        if (name != "system") return null
        return doSystem(args)
    }

    private val os: String get() = System.getProperty("os.name").lowercase(Locale.ROOT)
    private val isWin: Boolean get() = os.contains("win")
    private val isMac: Boolean get() = os.contains("mac")
    private val isLinux: Boolean get() = !isWin && !isMac

    private suspend fun doSystem(args: JSONObject): ToolResult {
        val op = args.optString("op", "").lowercase()
        return when (op) {
            "info" -> systemInfo()
            "volume" -> doAudioVolume(args)
            "media" -> doMediaControl(args)
            "media_info" -> doMediaInfo()
            "window" -> doWindowControl(args)
            "power" -> doPowerControl(args)
            "lock" -> doLockScreen()
            "notify" -> doNotify(args)
            "clipboard_read" -> doClipboardRead()
            "clipboard_write" -> doClipboardWrite(args)
            "wallpaper" -> doSetWallpaper(args)
            else -> ToolResult.fail("op 只能是 info / volume / media / media_info / window / power / lock / notify / clipboard_read / clipboard_write / wallpaper")
        }
    }

    // 媒体

    private suspend fun doMediaControl(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val action = args.optString("action", "").trim().lowercase()
        if (action.isBlank()) return@withContext ToolResult.fail("未提供 action")

        if (isWin && SmtcController.isAvailable()) {
            val smtcAction = when (action) {
                "play_pause", "toggle" -> "toggle"
                "play" -> "play"
                "pause" -> "pause"
                "next" -> "next"
                "prev", "previous" -> "prev"
                "stop" -> "stop"
                else -> null
            }
            if (smtcAction != null) {
                val outcome = SmtcController.controlWithFallback(smtcAction)
                if (outcome.ok) {
                    val methodNote = if (outcome.method != smtcAction) {
                        "（降级为 ${outcome.method}）"
                    } else ""
                    val statusNote = if (outcome.after.isNotBlank()) {
                        "，当前状态：${statusText(outcome.after)}"
                    } else ""
                    return@withContext ToolResult.ok("已执行媒体控制（SMTC）$methodNote$statusNote")
                }
            }
        }

        val ok = when {
            isWin -> sendMediaKeyWindows(action)
            isMac -> sendMediaKeyMac(action)
            else -> sendMediaKeyLinux(action)
        }
        if (ok) ToolResult.ok("已执行媒体控制（键盘事件）：$action")
        else ToolResult.fail("媒体控制失败：$action")
    }

    private suspend fun doMediaInfo(): ToolResult = withContext(Dispatchers.IO) {
        if (!isWin) {
            return@withContext ToolResult.fail("media_info 仅支持 Windows")
        }
        if (!SmtcController.isAvailable()) {
            return@withContext ToolResult.fail(
                "当前系统不支持 SMTC（需要 Windows 10 1809+）"
            )
        }
        val np = SmtcController.nowPlaying()
            ?: return@withContext ToolResult.ok("当前没有正在播放的媒体")

        val sb = StringBuilder()
        if (np.app.isNotBlank()) sb.append("来源应用：").append(np.app).append("\n")
        if (np.status.isNotBlank()) sb.append("状态：").append(statusText(np.status)).append("\n")
        if (np.title.isNotBlank()) sb.append("标题：").append(np.title).append("\n")
        if (np.artist.isNotBlank()) sb.append("艺术家：").append(np.artist).append("\n")
        if (np.album.isNotBlank()) sb.append("专辑：").append(np.album).append("\n")
        if (np.durationMs > 0) {
            sb.append("时长：").append(fmtMs(np.durationMs)).append("\n")
            val pct = (np.positionMs.toDouble() / np.durationMs * 100).toInt()
            sb.append("进度：").append(fmtMs(np.positionMs))
                .append("（").append(pct).append("%）\n")
        }
        ToolResult.ok(sb.toString().trim())
    }

    private fun statusText(raw: String): String = when (raw.lowercase()) {
        "playing" -> "播放中"
        "paused" -> "已暂停"
        "stopped" -> "已停止"
        "closed" -> "已关闭"
        "opened" -> "已打开"
        "changing" -> "切换中"
        else -> raw
    }

    private fun fmtMs(ms: Long): String {
        if (ms <= 0) return "-"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun sendMediaKeyWindows(action: String): Boolean {
        val vk = when (action) {
            "play_pause", "toggle", "play" -> 0xB3
            "next" -> 0xB0
            "prev", "previous" -> 0xB1
            "stop" -> 0xB2
            else -> return false
        }
        val script = $$"""
            $code = @"
            using System;
            using System.Runtime.InteropServices;
            public class Kbd {
                [DllImport("user32.dll")]
                public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);
                public static void Press(byte vk) {
                    keybd_event(vk, 0, 0, UIntPtr.Zero);
                    keybd_event(vk, 0, 2, UIntPtr.Zero);
                }
            }
"@
            Add-Type -TypeDefinition $code
            [Kbd]::Press($$vk)
        """.trimIndent()
        return runPowerShell(script, 8) != null
    }

    private fun sendMediaKeyMac(action: String): Boolean {
        val key = when (action) {
            "play_pause", "toggle", "play" -> 16
            "next" -> 17
            "prev", "previous" -> 18
            else -> return false
        }
        return runProcess(listOf("osascript", "-e", "tell application \"System Events\" to key code $key"), 8) != null
    }

    private fun sendMediaKeyLinux(action: String): Boolean {
        val pcAction = when (action) {
            "play_pause", "toggle" -> "play-pause"
            "next" -> "next"
            "prev", "previous" -> "previous"
            "stop" -> "stop"
            "play" -> "play"
            else -> return false
        }
        if (runProcess(listOf("playerctl", pcAction), 5) != null) return true
        val key = when (pcAction) {
            "play-pause" -> "XF86AudioPlay"
            "next" -> "XF86AudioNext"
            "previous" -> "XF86AudioPrev"
            "stop" -> "XF86AudioStop"
            else -> return false
        }
        return runProcess(listOf("xdotool", "key", key), 5) != null
    }

    // 音量

    private suspend fun doAudioVolume(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val action = args.optString("action", "").trim().lowercase()
        val step = args.optInt("step", 1).coerceIn(1, 50)
        when (action) {
            "up" -> {
                val ok = sendVolumeKeys(action, step)
                if (ok) ToolResult.ok("已调高音量 $step 档")
                else ToolResult.fail("调高音量失败")
            }
            "down" -> {
                val ok = sendVolumeKeys(action, step)
                if (ok) ToolResult.ok("已调低音量 $step 档")
                else ToolResult.fail("调低音量失败")
            }
            "mute" -> {
                val ok = sendVolumeKeys(action, 1)
                if (ok) ToolResult.ok("已切换静音")
                else ToolResult.fail("切换静音失败")
            }
            else -> ToolResult.fail("action 只能是 up / down / mute")
        }
    }

    private fun sendVolumeKeys(action: String, count: Int): Boolean {
        val n = count.coerceIn(1, 50)
        return when {
            isWin -> {
                val vk = when (action) { "up" -> 0xAF; "down" -> 0xAE; "mute" -> 0xAD; else -> return false }
                val script = $$"""
                    $code = @"
                    using System;
                    using System.Runtime.InteropServices;
                    public class Kbd {
                        [DllImport("user32.dll")]
                        public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);
                        public static void Press(byte vk) {
                            keybd_event(vk, 0, 0, UIntPtr.Zero);
                            keybd_event(vk, 0, 2, UIntPtr.Zero);
                        }
                    }
"@
                    Add-Type -TypeDefinition $code
                    1..$$n | ForEach-Object { [Kbd]::Press($$vk) }
                """.trimIndent()
                runPowerShell(script, 15) != null
            }
            isMac -> {
                val key = when (action) { "up" -> 72; "down" -> 73; "mute" -> 74; else -> return false }
                val script = """
                    tell application "System Events"
                        repeat $n times
                            key code $key
                        end repeat
                    end tell
                """.trimIndent()
                runProcess(listOf("osascript", "-e", script), 15) != null
            }
            else -> {
                val k = when (action) {
                    "up" -> "XF86AudioRaiseVolume"
                    "down" -> "XF86AudioLowerVolume"
                    "mute" -> "XF86AudioMute"
                    else -> return false
                }
                val keys = List(n) { k }
                if (runProcess(listOf("xdotool") + listOf("key") + keys, 10) != null) return true
                runProcess(listOf("amixer", "-q", "sset", "Master", "toggle"), 5) != null
            }
        }
    }

    // 剪贴板

    private suspend fun doClipboardRead(): ToolResult = withContext(Dispatchers.IO) {
        try {
            val cb = Toolkit.getDefaultToolkit().systemClipboard
            val text = try { cb.getData(DataFlavor.stringFlavor) as? String ?: "" }
            catch (_: Exception) { "" }
            if (text.isBlank()) ToolResult.ok("剪贴板为空") else ToolResult.ok("剪贴板内容：\n$text")
        } catch (e: Exception) { ToolResult.fail("剪贴板读取失败：${e.message}") }
    }

    private suspend fun doClipboardWrite(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val text = args.optString("text", "")
            if (text.isBlank()) return@withContext ToolResult.fail("clipboard_write 需要 text")
            val cb = Toolkit.getDefaultToolkit().systemClipboard
            cb.setContents(StringSelection(text), null)
            ToolResult.ok("已写入剪贴板（${text.length} 字符）")
        } catch (e: Exception) { ToolResult.fail("剪贴板写入失败：${e.message}") }
    }

    // 锁屏 / 电源 / 通知

    private suspend fun doLockScreen(): ToolResult = withContext(Dispatchers.IO) {
        try {
            when {
                isWin -> ProcessBuilder("rundll32.exe", "user32.dll,LockWorkStation").start()
                isMac -> ProcessBuilder("osascript", "-e",
                    "tell application \"System Events\" to keystroke \"q\" using {command down, control down}").start()
                else -> {
                    if (runProcess(listOf("loginctl", "lock-session"), 5) == null) {
                        runProcess(listOf("xdg-screensaver", "lock"), 5)
                    }
                }
            }
            ToolResult.ok("已锁屏")
        } catch (e: Exception) { ToolResult.fail("锁屏失败：${e.message}") }
    }

    private suspend fun doPowerControl(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val action = args.optString("action", "").trim().lowercase()
        val delay = args.optInt("delay_seconds", 0).coerceIn(0, 600)
        try {
            when (action) {
                "shutdown" -> {
                    when {
                        isWin -> ProcessBuilder("shutdown", "/s", "/t", delay.toString()).start()
                        isMac -> ProcessBuilder("osascript", "-e",
                            "tell app \"System Events\" to shut down").start()
                        else -> ProcessBuilder("systemctl", "poweroff").start()
                    }
                    ToolResult.ok("已排定关机（${delay}秒后）")
                }
                "reboot" -> {
                    when {
                        isWin -> ProcessBuilder("shutdown", "/r", "/t", delay.toString()).start()
                        isMac -> ProcessBuilder("osascript", "-e",
                            "tell app \"System Events\" to restart").start()
                        else -> ProcessBuilder("systemctl", "reboot").start()
                    }
                    ToolResult.ok("已排定重启（${delay}秒后）")
                }
                "hibernate" -> {
                    when {
                        isWin -> ProcessBuilder("shutdown", "/h").start()
                        isMac -> return@withContext ToolResult.fail("macOS 不支持脚本休眠")
                        else -> ProcessBuilder("systemctl", "hibernate").start()
                    }
                    ToolResult.ok("已请求休眠")
                }
                "sleep" -> {
                    when {
                        isWin -> ProcessBuilder("rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0").start()
                        isMac -> ProcessBuilder("pmset", "sleepnow").start()
                        else -> ProcessBuilder("systemctl", "suspend").start()
                    }
                    ToolResult.ok("已请求睡眠")
                }
                "logout" -> {
                    when {
                        isWin -> ProcessBuilder("shutdown", "/l").start()
                        isMac -> ProcessBuilder("osascript", "-e",
                            "tell app \"System Events\" to log out").start()
                        else -> ProcessBuilder("loginctl", "terminate-user", "").start()
                    }
                    ToolResult.ok("已请求注销")
                }
                "cancel" -> {
                    if (!isWin) return@withContext ToolResult.fail("仅 Windows 支持取消")
                    ProcessBuilder("shutdown", "/a").start()
                    ToolResult.ok("已取消排定的关机/重启")
                }
                else -> ToolResult.fail("action 只能是 shutdown / reboot / hibernate / sleep / logout / cancel")
            }
        } catch (e: Exception) { ToolResult.fail("电源操作失败：${e.message}") }
    }

    private fun doNotify(args: JSONObject): ToolResult {
        val title = args.optString("title", "FurAgent").trim().ifBlank { "FurAgent" }
        val content = args.optString("content", "").trim()
        if (content.isBlank()) return ToolResult.fail("content 不能为空")
        DesktopNotifier.notify(title, content)
        return ToolResult.ok("已发送通知：$title")
    }

    // 窗口控制

    private suspend fun doWindowControl(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val action = args.optString("action", "").trim().lowercase()
        val match = args.optString("match", "").trim()
        when {
            isWin -> windowControlWindows(action, match)
            isMac -> windowControlMac(action, match)
            else -> windowControlLinux(action, match)
        }
    }

    private fun windowControlWindows(action: String, match: String): ToolResult {
        if (action == "list") {
            val out = runPowerShell(
                $$"Get-Process | Where-Object { $_.MainWindowTitle -ne '' } | " +
                        "Select-Object ProcessName,MainWindowTitle | Format-Table -AutoSize", 10
            ) ?: return ToolResult.fail("枚举窗口失败")
            return ToolResult.ok("当前窗口：\n$out")
        }
        if (match.isBlank()) return ToolResult.fail("除 list 外需要 match 参数")
        val esc = match.replace("'", "''")
        val script = when (action) {
            "activate" -> $$"""
                $p = Get-Process | Where-Object { $_.MainWindowTitle -like '*$$esc*' -or $_.ProcessName -like '*$$esc*' } | Select-Object -First 1
                if ($p) {
                    Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public class W { [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h); }'
                    [W]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
                    Write-Output "OK"
                } else { Write-Output "NOTFOUND" }
            """.trimIndent()
            "minimize" -> $$"""
                $p = Get-Process | Where-Object { $_.MainWindowTitle -like '*$$esc*' } | Select-Object -First 1
                if ($p) {
                    Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public class W { [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c); }'
                    [W]::ShowWindow($p.MainWindowHandle, 6) | Out-Null
                    Write-Output "OK"
                } else { Write-Output "NOTFOUND" }
            """.trimIndent()
            "maximize" -> $$"""
                $p = Get-Process | Where-Object { $_.MainWindowTitle -like '*$$esc*' } | Select-Object -First 1
                if ($p) {
                    Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public class W { [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c); }'
                    [W]::ShowWindow($p.MainWindowHandle, 3) | Out-Null
                    Write-Output "OK"
                } else { Write-Output "NOTFOUND" }
            """.trimIndent()
            "close" -> $$"""
                $p = Get-Process | Where-Object { $_.MainWindowTitle -like '*$$esc*' -or $_.ProcessName -like '*$$esc*' } | Select-Object -First 1
                if ($p) { $p.CloseMainWindow() | Out-Null; Write-Output "OK" } else { Write-Output "NOTFOUND" }
            """.trimIndent()
            else -> return ToolResult.fail("action 不支持：$action")
        }
        val out = runPowerShell(script, 10) ?: return ToolResult.fail("执行失败")
        return if (out.contains("OK")) ToolResult.ok("已执行：$action $match")
        else ToolResult.fail("未找到匹配的窗口：$match")
    }

    private fun windowControlMac(action: String, match: String): ToolResult {
        if (action == "list") {
            val out = runProcess(listOf("osascript", "-e",
                "tell application \"System Events\" to get name of every process whose background only is false"), 10)
                ?: return ToolResult.fail("枚举失败")
            return ToolResult.ok("当前应用：\n$out")
        }
        if (match.isBlank()) return ToolResult.fail("除 list 外需要 match 参数")
        val esc = match.replace("\"", "\\\"")
        val script = when (action) {
            "activate" -> "tell application \"$esc\" to activate"
            "close" -> "tell application \"$esc\" to quit"
            "minimize" -> "tell application \"$esc\" to set miniaturized of window 1 to true"
            else -> return ToolResult.fail("macOS 不支持：$action")
        }
        runProcess(listOf("osascript", "-e", script), 8)
        return ToolResult.ok("已执行：$action $match")
    }

    private fun windowControlLinux(action: String, match: String): ToolResult {
        if (action == "list") {
            val out = runProcess(listOf("wmctrl", "-l"), 8) ?: return ToolResult.fail("未安装 wmctrl")
            return ToolResult.ok("当前窗口：\n$out")
        }
        if (match.isBlank()) return ToolResult.fail("除 list 外需要 match 参数")
        val cmd = when (action) {
            "activate" -> listOf("wmctrl", "-a", match)
            "close" -> listOf("wmctrl", "-c", match)
            "minimize" -> return ToolResult.fail("wmctrl 不支持最小化")
            "maximize" -> listOf("wmctrl", "-r", match, "-b", "add,maximized_vert,maximized_horz")
            else -> return ToolResult.fail("action 不支持：$action")
        }
        runProcess(cmd, 8) ?: return ToolResult.fail("执行失败")
        return ToolResult.ok("已执行：$action $match")
    }

    // 壁纸

    private suspend fun doSetWallpaper(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val path = args.optString("path", "").trim()
        if (path.isBlank()) return@withContext ToolResult.fail("未提供 path")
        val file = File(path)
        if (!file.exists() || !file.isFile) return@withContext ToolResult.fail("文件不存在：$path")
        try {
            when {
                isWin -> {
                    val escaped = file.absolutePath.replace("\\", "\\\\").replace("'", "''")
                    val script = $$"""
                        $code = @"
                        using System;
                        using System.Runtime.InteropServices;
                        public class Wall {
                            [DllImport("user32.dll", SetLastError=true)]
                            public static extern int SystemParametersInfo(int uAction, int uParam, string lpvParam, int fuWinIni);
                        }
"@
                        Add-Type -TypeDefinition $code
                        [Wall]::SystemParametersInfo(20, 0, '$$escaped', 3)
                    """.trimIndent()
                    runPowerShell(script, 10)
                }
                isMac -> runProcess(listOf("osascript", "-e",
                    "tell application \"System Events\" to set picture of every desktop to \"$path\""), 10)
                else -> {
                    if (runProcess(listOf("gsettings", "set", "org.gnome.desktop.background",
                            "picture-uri", "file://$path"), 8) == null) {
                        return@withContext ToolResult.fail("当前桌面环境不支持自动换壁纸")
                    }
                }
            }
            ToolResult.ok("已设置壁纸：$path")
        } catch (e: Exception) { ToolResult.fail("设置壁纸失败：${e.message}") }
    }

    // 系统信息

    private suspend fun systemInfo(): ToolResult = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        try {
            sb.append("=== 系统 ===\n")
            sb.append("操作系统：").append(System.getProperty("os.name"))
                .append(" ").append(System.getProperty("os.version"))
                .append(" (").append(System.getProperty("os.arch")).append(")\n")
            sb.append("用户名：").append(System.getProperty("user.name")).append("\n")
            sb.append("主机名：").append(getHostname()).append("\n")

            sb.append("\n=== CPU ===\n")
            sb.append(getCpuInfo())

            sb.append("\n=== 内存 ===\n")
            sb.append(getMemoryInfo())

            sb.append("\n=== GPU ===\n")
            sb.append(getGpuInfo())

            sb.append("\n=== 主板 ===\n")
            sb.append(getMotherboardInfo())

            sb.append("\n=== 磁盘 ===\n")
            sb.append(getDiskInfo())
        } catch (e: Exception) {
            sb.append("\n读取异常：${e.message}")
        }
        ToolResult.ok(sb.toString().trim())
    }

    private fun getHostname(): String = try {
        java.net.InetAddress.getLocalHost().hostName
    } catch (_: Exception) { "unknown" }

    private fun getCpuInfo(): String {
        val sb = StringBuilder()
        val cores = Runtime.getRuntime().availableProcessors()
        sb.append("核心数：").append(cores).append("\n")

        val model = when {
            isWin -> runPowerShell("(Get-CimInstance Win32_Processor).Name", 8)?.trim()
            isMac -> runProcess(listOf("sysctl", "-n", "machdep.cpu.brand_string"), 5)?.trim()
            else -> runProcess(listOf("sh", "-c",
                "grep -m1 'model name' /proc/cpuinfo | cut -d: -f2"), 5)?.trim()
        }
        if (!model.isNullOrBlank()) sb.append("型号：").append(model).append("\n")

        val usage = getCpuUsagePercent()
        if (usage != null) {
            sb.append("当前占用率：").append(String.format(Locale.US, "%.1f%%", usage)).append("\n")
        }
        return sb.toString()
    }

    private fun getCpuUsagePercent(): Double? {
        return try {
            val osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            val cls = Class.forName("com.sun.management.OperatingSystemMXBean")
            val method = cls.getMethod("getCpuLoad")
            val v = method.invoke(osBean) as? Double ?: return null
            if (v.isNaN() || v < 0) null else v * 100.0
        } catch (_: Throwable) { null }
    }

    private fun getMemoryInfo(): String {
        val sb = StringBuilder()
        try {
            val osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            val cls = Class.forName("com.sun.management.OperatingSystemMXBean")

            val totalMem = try {
                cls.getMethod("getTotalMemorySize").invoke(osBean) as? Long
            } catch (_: Throwable) { null }
            val freeMem = try {
                cls.getMethod("getFreeMemorySize").invoke(osBean) as? Long
            } catch (_: Throwable) { null }
            val committed = try {
                cls.getMethod("getCommittedVirtualMemorySize").invoke(osBean) as? Long
            } catch (_: Throwable) { null }

            if (totalMem != null && totalMem > 0) {
                sb.append("物理内存总量：").append(formatFileSize(totalMem)).append("\n")
                if (freeMem != null) {
                    val used = totalMem - freeMem
                    val pct = used * 100.0 / totalMem
                    sb.append("已用：").append(formatFileSize(used))
                        .append("（").append(String.format(Locale.US, "%.1f%%", pct)).append("）\n")
                    sb.append("可用：").append(formatFileSize(freeMem)).append("\n")
                }
            }
            if (committed != null && committed > 0) {
                sb.append("已提交虚拟内存：").append(formatFileSize(committed)).append("\n")
            }
        } catch (e: Throwable) {
            sb.append("(读取系统内存信息失败：${e.message})\n")
        }
        return sb.toString()
    }

    private fun getGpuInfo(): String {
        val sb = StringBuilder()
        try {
            when {
                isWin -> {
                    val out = runPowerShell(
                        "Get-CimInstance Win32_VideoController | Select-Object Name,AdapterRAM,DriverVersion | Format-List",
                        15
                    )
                    if (!out.isNullOrBlank()) sb.append(out.trim()).append("\n")
                    else sb.append("(未获取到 GPU 信息)\n")
                }
                isMac -> {
                    val out = runProcess(listOf("sh", "-c",
                        "system_profiler SPDisplaysDataType | grep -E 'Chipset Model|VRAM|Vendor'"), 15)
                    if (!out.isNullOrBlank()) sb.append(out.trim()).append("\n")
                    else sb.append("(未获取到 GPU 信息)\n")
                }
                else -> {
                    val out = runProcess(listOf("sh", "-c",
                        "lspci 2>/dev/null | grep -iE 'vga|3d|display'"), 8)
                    if (!out.isNullOrBlank()) sb.append(out.trim()).append("\n")
                    else sb.append("(未获取到 GPU 信息)\n")
                }
            }
        } catch (e: Throwable) {
            sb.append("(读取 GPU 信息失败：${e.message})\n")
        }
        return sb.toString()
    }

    private fun getMotherboardInfo(): String {
        val sb = StringBuilder()
        try {
            when {
                isWin -> {
                    val out = runPowerShell(
                        "Get-CimInstance Win32_BaseBoard | Select-Object Manufacturer,Product,SerialNumber | Format-List",
                        10
                    )
                    if (!out.isNullOrBlank()) sb.append(out.trim()).append("\n")
                    else sb.append("(未获取到主板信息)\n")
                }
                isMac -> {
                    val out = runProcess(listOf("sh", "-c",
                        "system_profiler SPHardwareDataType | grep -E 'Model Identifier|Chip|Serial'"), 15)
                    if (!out.isNullOrBlank()) sb.append(out.trim()).append("\n")
                    else sb.append("(未获取到主板信息)\n")
                }
                else -> {
                    val out = runProcess(listOf("sh", "-c",
                        "cat /sys/devices/virtual/dmi/id/board_vendor /sys/devices/virtual/dmi/id/board_name 2>/dev/null"), 5)
                    if (!out.isNullOrBlank()) sb.append(out.trim()).append("\n")
                    else sb.append("(未获取到主板信息)\n")
                }
            }
        } catch (e: Throwable) {
            sb.append("(读取主板信息失败：${e.message})\n")
        }
        return sb.toString()
    }

    private fun getDiskInfo(): String {
        val sb = StringBuilder()
        try {
            File.listRoots().forEach { root ->
                val total = root.totalSpace
                val free = root.usableSpace
                val used = total - free
                if (total > 0) {
                    sb.append(root.absolutePath)
                        .append(" 总 ").append(formatFileSize(total))
                        .append(" · 已用 ").append(formatFileSize(used))
                        .append(" · 可用 ").append(formatFileSize(free))
                        .append("\n")
                }
            }
        } catch (_: Exception) { }
        return sb.toString().ifBlank { "(无磁盘信息)\n" }
    }

    // PowerShell / Process 辅助

    /**
     * 固定名脚本文件（~/.furagent/temp/furagent_sys.ps1）。
     *
     * 为什么要固定名
     * 旧实现用 File.createTempFile("furagent_", ".ps1")，落在系统 %TEMP%，
     * TempCleaner 根本不扫那个目录，导致 PS 脚本永远残留。
     */
    private val psLock = Any()
    private val psScriptFile: File by lazy {
        File(DesktopPaths.tempDir(), "furagent_sys.ps1")
    }

    private fun runPowerShell(script: String, timeoutSec: Int): String? {
        if (!isWin) return null
        synchronized(psLock) {
            return try {
                psScriptFile.parentFile?.mkdirs()
                psScriptFile.outputStream().use { out ->
                    out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                    out.write(("[Console]::OutputEncoding = [System.Text.Encoding]::UTF8\r\n$script").toByteArray(Charsets.UTF_8))
                }
                try {
                    val p = ProcessBuilder(
                        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                        "-File", psScriptFile.absolutePath
                    ).redirectErrorStream(true).start()
                    val finished = p.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
                    if (!finished) { p.destroyForcibly(); return null }
                    p.inputStream.bufferedReader(Charsets.UTF_8).readText()
                } finally {
                    try { psScriptFile.delete() } catch (_: Exception) { }
                }
            } catch (_: Exception) { null }
        }
    }

    private fun runProcess(cmd: List<String>, timeoutSec: Int): String? {
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val finished = p.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (!finished) { p.destroyForcibly(); return null }
            p.inputStream.bufferedReader().readText()
        } catch (_: Exception) { null }
    }
}