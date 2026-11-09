package net.xinkong.furagent.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.core.ConfirmationBroker
import net.xinkong.furagent.data.AppSettings
import net.xinkong.furagent.system.ShizukuHelper
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 远程协助工具（Windows → Android）。
 *
 * 链路：桌面端 phone_remote → BridgeServer → PcBridgeClient → 本工具。
 * 复用项目已有的双向 WebSocket 工具桥，不需要 USB、也不需要桌面端直连 adb.exe；
 * 但提供与 adb 等价的能力集（screencap / input tap|swipe|text|keyevent /
 * uiautomator dump / pm / am），所以桌面端可以按 adb 的心智模型编写指令。
 *
 * 执行优先级：
 *  - 屏幕/UI 相关 → 已有无障碍控制器（ScreenTools 的 ui 工具），坐标与 UI 树都能用；
 *  - 无障碍不可用但有 Shizuku → input / uiautomator / screencap 等 shell 命令兜底；
 *  - 都没有 → 明确报错并提示用户去开哪个开关，不静默失败。
 *
 * 安全：任意 shell 命令沿用 execute_shell 的 requireConfirmShell 确认策略；
 * 截图落到应用可读目录并返回路径（不把整张图塞进桥接文本通道）。
 */
object RemoteControlTools {

    const val TOOL_NAME = "adb"
    val TOOLS = setOf(TOOL_NAME)

    private val KEYEVENT_ALIASES = mapOf(
        "back" to "4", "home" to "3", "recents" to "187", "menu" to "82",
        "enter" to "66", "delete" to "67", "space" to "62", "power" to "26",
        "volume_up" to "24", "volume_down" to "25", "tab" to "61", "escape" to "111"
    )

    suspend fun execute(
        name: String, args: JSONObject, context: Context, settings: AppSettings
    ): String {
        if (name != TOOL_NAME) return "未实现的远程控制工具：$name"
        val op = args.optString("op", "").trim().lowercase()
        return when (op) {
            "status" -> doStatus(context)
            "dump" -> viaUi("dump", args, context, settings)
            "find" -> viaUi("find", args, context, settings)
            "tap" -> doTap(args, context, settings)
            "long_press" -> doTap(args, context, settings, longPress = true)
            "swipe" -> doSwipe(args, context, settings)
            "text" -> doText(args, context, settings)
            "key" -> doKey(args, context, settings)
            "screencap" -> doScreencap(args, context)
            "scroll" -> doScroll(args, context, settings)
            "shell" -> doShell(args, settings)
            "apps" -> doApps(args, settings)
            "launch" -> doLaunch(args, settings)
            else -> "失败：op 只能是 status / dump / find / tap / long_press / swipe / text / " +
                    "key / screencap / scroll / shell / apps / launch"
        }
    }

    /**
     * 滚动（对应桌面端鼠标滚轮）。dx/dy 为相对位移：dy 为负=向上滚，正=向下滚。
     * 优先无障碍手势，Shizuku `input swipe` 兜底。
     */
    private suspend fun doScroll(args: JSONObject, context: Context, settings: AppSettings): String {
        val x = args.optInt("x", -1)
        val y = args.optInt("y", -1)
        val dx = args.optInt("dx", 0)
        val dy = args.optInt("dy", 0)
        if (x < 0 || y < 0 || (dx == 0 && dy == 0)) {
            return "失败：scroll 需要 x / y 以及非零的 dx / dy"
        }
        val ex = (x + dx).coerceAtLeast(0)
        val ey = (y + dy).coerceAtLeast(0)
        if (net.xinkong.furagent.system.ScreenAccessibilityService.isRunning()) {
            val r = viaUi(
                "gesture",
                JSONObject().apply {
                    put("op", "gesture"); put("type", "swipe")
                    put("start_x", x); put("start_y", y)
                    put("end_x", ex); put("end_y", ey)
                    put("duration_ms", 40)
                },
                context, settings
            )
            if (!r.startsWith("失败")) return r
        }
        return shizukuInput("input swipe $x $y $ex $ey 40")
            ?: "失败：无障碍与 Shizuku 均不可用，无法滚动。"
    }

    private fun doStatus(context: Context): String {
        val dm = context.resources.displayMetrics
        val accessibility = net.xinkong.furagent.system.ScreenAccessibilityService.isRunning()
        return buildString {
            append("远程协助能力状态：\n")
            append("· 无障碍屏幕控制：").append(if (accessibility) "已开启" else "未开启").append('\n')
            append("· Shizuku shell：")
                .append(if (ShizukuHelper.isAvailable() && ShizukuHelper.hasPermission()) "已授权" else "不可用")
                .append('\n')
            append("· 屏幕：${dm.widthPixels}x${dm.heightPixels} @ ${dm.densityDpi}dpi\n")
            append("· 机型：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                    "(Android ${android.os.Build.VERSION.RELEASE})\n")
            if (!accessibility && !(ShizukuHelper.isAvailable() && ShizukuHelper.hasPermission())) {
                append("\n两种控制通道都不可用。请到「设置 → 无障碍」开启 FurAgent 屏幕控制，" +
                        "或启动 Shizuku 并授权。")
            }
        }.trim()
    }

    /** 把 adb 风格指令翻译成既有 ui 工具的参数，复用已验证的无障碍实现。 */
    private suspend fun viaUi(
        op: String, args: JSONObject, context: Context, settings: AppSettings
    ): String {
        val uiArgs = JSONObject().apply { put("op", op) }
        when (op) {
            "dump" -> {
                uiArgs.put("max_depth", args.optInt("max_depth", 30))
                uiArgs.put("max_nodes", args.optInt("max_nodes", 4000))
            }
            "find" -> {
                listOf("text", "regex", "class_name", "resource_id", "max_results")
                    .forEach { k -> if (args.has(k)) uiArgs.put(k, args.get(k)) }
                if (args.has("text_exact")) uiArgs.put("text_exact", args.get("text_exact"))
            }
            "action" -> {
                uiArgs.put("node_id", args.optString("node_id", ""))
                uiArgs.put("action", args.optString("action", ""))
                if (args.has("text")) uiArgs.put("text", args.get("text"))
            }
            "gesture" -> {
                listOf("type", "x", "y", "start_x", "start_y", "end_x", "end_y", "duration_ms")
                    .forEach { k -> if (args.has(k)) uiArgs.put(k, args.get(k)) }
            }
            "global" -> uiArgs.put("action", args.optString("action", ""))
        }
        return ScreenTools.execute("ui", uiArgs, context, settings)
    }

    private suspend fun doTap(
        args: JSONObject, context: Context, settings: AppSettings, longPress: Boolean = false
    ): String {
        val x = args.optInt("x", -1)
        val y = args.optInt("y", -1)
        if (x < 0 || y < 0) return "失败：tap 需要 x / y（屏幕像素）"

        val fallbackCmd =
            if (longPress) "input swipe $x $y $x $y 800" else "input tap $x $y"
        if (net.xinkong.furagent.system.ScreenAccessibilityService.isRunning()) {
            val result = viaUi(
                "gesture",
                JSONObject().apply {
                    put("type", if (longPress) "long_press" else "tap")
                    put("x", x); put("y", y)
                    if (args.has("duration_ms")) put("duration_ms", args.get("duration_ms"))
                },
                context, settings
            )
            if (!result.startsWith("失败")) return result
        }
        return shizukuInput(fallbackCmd)
            ?: "失败：无障碍与 Shizuku 均不可用，无法点击。"
    }

    private suspend fun doSwipe(args: JSONObject, context: Context, settings: AppSettings): String {
        val sx = args.optInt("start_x", -1); val sy = args.optInt("start_y", -1)
        val ex = args.optInt("end_x", -1); val ey = args.optInt("end_y", -1)
        if (sx < 0 || sy < 0 || ex < 0 || ey < 0) {
            return "失败：swipe 需要 start_x / start_y / end_x / end_y"
        }
        val duration = args.optLong("duration_ms", 300L).coerceIn(50L, 3000L)
        if (net.xinkong.furagent.system.ScreenAccessibilityService.isRunning()) {
            val result = viaUi(
                "gesture",
                JSONObject().apply {
                    put("op", "gesture"); put("type", "swipe")
                    put("start_x", sx); put("start_y", sy)
                    put("end_x", ex); put("end_y", ey)
                    put("duration_ms", duration)
                },
                context, settings
            )
            if (!result.startsWith("失败")) return result
        }
        return shizukuInput("input swipe $sx $sy $ex $ey $duration")
            ?: "失败：无障碍与 Shizuku 均不可用，无法滑动。"
    }

    private suspend fun doText(args: JSONObject, context: Context, settings: AppSettings): String {
        val text = args.optString("text", "")
        if (text.isEmpty()) return "失败：text 需要非空 text 参数"
        val nodeId = args.optString("node_id", "").trim()
        if (nodeId.isNotBlank() && net.xinkong.furagent.system.ScreenAccessibilityService.isRunning()) {
            return viaUi(
                "action",
                JSONObject().apply {
                    put("op", "action"); put("node_id", nodeId)
                    put("action", "set_text"); put("text", text)
                },
                context, settings
            )
        }
        // Shizuku 兜底：input text 对空格/特殊字符敏感，逐字符白名单转义
        val escaped = buildString {
            text.forEach { c ->
                when {
                    c == ' ' -> append("%s")
                    c.isLetterOrDigit() || c in "-_.,:;/@+=" -> append(c)
                    else -> append("%s")
                }
            }
        }
        if (escaped.isBlank()) return "失败：文本中没有可输入的字符"
        return shizukuInput("input text $escaped")
            ?: "失败：无 node_id 且 Shizuku 不可用。请先 dump/find 拿 node_id，" +
                    "或开启 Shizuku 后重试。"
    }

    private suspend fun doKey(args: JSONObject, context: Context, settings: AppSettings): String {
        val raw = args.optString("key", "").trim().lowercase()
        if (raw.isBlank()) return "失败：key 需要 key 参数（back/home/recents/enter/数字键码）"
        val code = KEYEVENT_ALIASES[raw] ?: raw
        if (!code.all { it.isDigit() }) {
            return "失败：未知按键 \"$raw\"。可用：${KEYEVENT_ALIASES.keys.joinToString(", ")} 或数字 keycode"
        }
        val globalName = when (raw) {
            "back" -> "back"; "home" -> "home"; "recents" -> "recents"; else -> null
        }
        if (globalName != null && net.xinkong.furagent.system.ScreenAccessibilityService.isRunning()) {
            val result = viaUi(
                "global",
                JSONObject().apply { put("op", "global"); put("action", globalName) },
                context, settings
            )
            if (!result.startsWith("失败")) return result
        }
        return shizukuInput("input keyevent $code")
            ?: "失败：无障碍与 Shizuku 均不可用，无法发送按键。"
    }

    private fun doScreencap(args: JSONObject, context: Context): String {
        if (!ShizukuHelper.isAvailable() || !ShizukuHelper.hasPermission()) {
            return "失败：截图需要 Shizuku 权限。请启动 Shizuku 并授权，" +
                    "或在桌面端使用 phone_call(tool=\"screenshot\") 走多模态通道。"
        }
        return try {
            val dir = outputDir(context, "remote")
            val name = "screen_" +
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".png"
            val target = File(dir, name)
            val r = ShizukuHelper.executeCommand("screencap -p ${target.absolutePath}", 30)
            if (target.exists() && target.length() > 0) {
                "截图已保存：${target.absolutePath}（${target.length()} 字节）\n" +
                        "桌面端可用 phone_receive_file(remote_path=上面的路径) 拉取查看。"
            } else "失败：截图文件未生成。screencap 输出：$r"
        } catch (e: Exception) {
            "失败：截图异常：${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private suspend fun doShell(args: JSONObject, settings: AppSettings): String {
        val command = args.optString("command", "").trim()
        if (command.isBlank()) return "失败：shell 需要 command 参数"
        val timeout = args.optInt("timeout", 30).coerceIn(1, 300)
        if (!ShizukuHelper.isAvailable() || !ShizukuHelper.hasPermission()) {
            return "失败：Shizuku 未运行或未授权，无法执行 shell。"
        }
        if (settings.requireConfirmShell) {
            val preview = if (command.length > 300) command.take(300) + "..." else command
            val approved = ConfirmationBroker.request(
                "远程 Shell 命令确认",
                "电脑端请求在手机上执行以下命令：\n\n$preview\n\n是否允许？"
            )
            if (!approved) return "用户已拒绝执行远程命令"
        }
        return withContext(Dispatchers.IO) {
            AgentLogger.log("REMOTE", "远程 shell：${command.take(200)}")
            ShizukuHelper.executeCommand(command, timeout)
        }
    }

    private fun doApps(args: JSONObject, settings: AppSettings): String {
        if (!ShizukuHelper.isAvailable() || !ShizukuHelper.hasPermission()) {
            return "失败：列出应用需要 Shizuku 权限，也可改用 phone_call(tool=\"app\", op=list)。"
        }
        val thirdParty = args.optBoolean("third_party", true)
        val cmd = if (thirdParty) "pm list packages -3" else "pm list packages"
        return try {
            ShizukuHelper.executeCommand(cmd, 30)
        } catch (e: Exception) {
            "失败：${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun doLaunch(args: JSONObject, settings: AppSettings): String {
        val pkg = args.optString("package", "").trim()
        if (pkg.isBlank()) return "失败：launch 需要 package 参数"
        if (!pkg.matches(Regex("^[A-Za-z0-9_.]+$"))) return "失败：非法包名"
        if (!ShizukuHelper.isAvailable() || !ShizukuHelper.hasPermission()) {
            return "失败：启动应用需要 Shizuku 权限，也可改用 phone_call(tool=\"app\", op=open)。"
        }
        return try {
            ShizukuHelper.executeCommand("monkey -p $pkg 1", 20)
        } catch (e: Exception) {
            "失败：${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Shizuku 可用时执行一条 input 系列命令；不可用返回 null 交给调用方报错。 */
    private fun shizukuInput(command: String): String? {
        if (!ShizukuHelper.isAvailable() || !ShizukuHelper.hasPermission()) return null
        return try {
            val r = ShizukuHelper.executeCommand(command, 15)
            if (r.startsWith("失败")) r else "已执行：$command"
        } catch (e: Exception) {
            "失败：${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
