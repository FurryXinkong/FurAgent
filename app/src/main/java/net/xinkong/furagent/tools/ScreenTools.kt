package net.xinkong.furagent.tools

import android.content.Context
import net.xinkong.furagent.data.AppSettings
import net.xinkong.furagent.shared.util.*
import net.xinkong.furagent.system.ScreenAccessibilityService
import org.json.JSONObject

/**
 * 屏幕控制工具（`ui`）。
 *
 * 把 ScreenController 抽象暴露给 LLM。所有 op 都走同一个控制器，
 * 底层自动适配 Android 无障碍 / Windows UIA。
 *
 * 典型工作流：
 *   1. `ui(op=find, text="登录")` 或 `ui(op=dump)` → 拿 node_id
 *   2. `ui(op=action, node_id=..., action=click)` → 操作
 *
 * node_id 只在下一次 dump/find 之前有效。UI 变化后需重新获取。
 *
 * 【不截断】
 *  UI 树的文本量本身不大（复杂屏幕也就几万字符），不设上限。
 *  node 数量靠 max_depth / max_nodes 参数控制，默认已放宽到 30/2000。
 */
object ScreenTools {

    val TOOLS = setOf("ui")

    suspend fun execute(
        name: String, args: JSONObject, context: Context, settings: AppSettings
    ): String {
        if (name != "ui") return "未实现的屏幕工具：$name"

        val controller = ScreenControlRegistry.get()
            ?: return "失败：无障碍服务未开启。" +
                    "请到系统设置 → 无障碍 → 已安装的服务，开启 FurAgent 屏幕控制。"

        if (!controller.isAvailable()) {
            return "失败：无障碍服务当前不可用（可能被系统暂停）。"
        }

        val op = args.optString("op", "").lowercase()
        return when (op) {
            "status" -> doStatus(context)
            "dump" -> doDump(args)
            "find" -> doFind(args)
            "action" -> doAction(args)
            "gesture" -> doGesture(args)
            "global" -> doGlobal(args)
            else -> "失败：op 只能是 status / dump / find / action / gesture / global"
        }
    }

    private fun doStatus(context: Context): String {
        val dm = context.resources.displayMetrics
        val enabled = ScreenAccessibilityService.isEnabledInSettings(context)
        val running = ScreenAccessibilityService.isRunning()
        return buildString {
            append("无障碍服务：")
            append(
                when {
                    running -> "已开启且正在运行"
                    enabled -> "已在系统设置中开启，但服务未 bind（可能刚开启还没生效）"
                    else -> "未开启"
                }
            )
            append("\n")
            append("屏幕尺寸：${dm.widthPixels}x${dm.heightPixels}\n")
            append("密度：${dm.densityDpi}dpi\n")
            append("系统版本：Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        }
    }

    private suspend fun doDump(args: JSONObject): String {
        val controller = ScreenControlRegistry.get() ?: return "失败：无障碍服务未开启"
        val maxDepth = args.optInt("max_depth", 30)
        val maxNodes = args.optInt("max_nodes", 2000)
        val dump = controller.dumpScreen(maxDepth, maxNodes)
        // 不截断
        return UiTreeFormatter.toText(dump)
    }

    private suspend fun doFind(args: JSONObject): String {
        val controller = ScreenControlRegistry.get() ?: return "失败：无障碍服务未开启"

        val query = UiQuery(
            text = args.optString("text", "").trim(),
            textExact = args.optBoolean("text_exact", false),
            textRegex = args.optString("regex", "").trim(),
            className = args.optString("class_name", "").trim(),
            resourceId = args.optString("resource_id", "").trim(),
            clickable = args.optBooleanOrNull("clickable"),
            editable = args.optBooleanOrNull("editable"),
            scrollable = args.optBooleanOrNull("scrollable"),
            enabled = args.optBooleanOrNull("enabled"),
            maxResults = args.optInt("max_results", 50).coerceIn(1, 500),
        )

        if (query.isEmpty) {
            return "失败：至少需要一个查询条件（text / regex / class_name / resource_id / clickable / editable / scrollable / enabled）"
        }

        val results = controller.findNodes(query)
        if (results.isEmpty()) {
            return "未找到匹配节点（条件：${UiTreeFormatter.describeQuery(query)}）。\n" +
                    "提示：先用 op=dump 看完整结构，或放宽条件（改用 text 非精确匹配、去掉 id 限制）。"
        }

        val sb = StringBuilder()
        sb.append("找到 ${results.size} 个匹配节点（条件：${UiTreeFormatter.describeQuery(query)}）：\n\n")
        results.forEach { node ->
            sb.append(UiTreeFormatter.describeNode(node)).append("\n")
        }
        sb.append("\n用 op=action node_id=<上面的 id> 对目标元素操作。")
        return sb.toString().trim()
    }

    private suspend fun doAction(args: JSONObject): String {
        val controller = ScreenControlRegistry.get() ?: return "失败：无障碍服务未开启"
        val nodeId = args.optString("node_id", "").trim()
        if (nodeId.isBlank()) return "失败：未提供 node_id"

        val actionStr = args.optString("action", "").trim()
        if (actionStr.isBlank()) return "失败：未提供 action"

        val action = UiAction.parse(actionStr)
            ?: return "失败：未知动作 \"$actionStr\"。可用：${
                UiAction.entries.joinToString(", ") { it.name.lowercase() }
            }"

        val text = args.optString("text", "")
        if (action == UiAction.SET_TEXT && text.isEmpty()) {
            return "失败：SET_TEXT 需要 text 参数"
        }

        val result = controller.performAction(nodeId, action, text)
        return if (result.success) {
            result.message.ifBlank { "已执行 $action" }
        } else {
            "失败：${result.message}"
        }
    }

    private suspend fun doGesture(args: JSONObject): String {
        val controller = ScreenControlRegistry.get() ?: return "失败：无障碍服务未开启"

        val type = args.optString("type", "").trim().lowercase()
        if (type !in listOf("tap", "long_press", "swipe")) {
            return "失败：type 只能是 tap / long_press / swipe"
        }

        val coords: IntArray = when (type) {
            "swipe" -> {
                val sx = args.optInt("start_x", -1)
                val sy = args.optInt("start_y", -1)
                val ex = args.optInt("end_x", -1)
                val ey = args.optInt("end_y", -1)
                if (sx < 0 || sy < 0 || ex < 0 || ey < 0) {
                    return "失败：swipe 需要 start_x / start_y / end_x / end_y（屏幕像素）"
                }
                intArrayOf(sx, sy, ex, ey)
            }
            else -> {
                val x = args.optInt("x", -1)
                val y = args.optInt("y", -1)
                if (x < 0 || y < 0) {
                    return "失败：$type 需要 x / y（屏幕像素）"
                }
                intArrayOf(x, y, x, y)
            }
        }

        val gesture = UiGesture(
            type = type,
            startX = coords[0],
            startY = coords[1],
            endX = coords[2],
            endY = coords[3],
            durationMs = args.optLong(
                "duration_ms",
                when (type) {
                    "long_press" -> 800L
                    "swipe" -> 300L
                    else -> 100L
                },
            ),
        )

        val result = controller.dispatchGesture(gesture)
        return if (result.success) {
            result.message.ifBlank { "已执行 $type" }
        } else {
            "失败：${result.message}"
        }
    }

    private suspend fun doGlobal(args: JSONObject): String {
        val controller = ScreenControlRegistry.get() ?: return "失败：无障碍服务未开启"
        val actionStr = args.optString("action", "").trim()
        if (actionStr.isBlank()) return "失败：未提供 action"

        val action = GlobalAction.parse(actionStr)
            ?: return "失败：未知全局动作 \"$actionStr\"。可用：${
                GlobalAction.entries.joinToString(", ") { it.name.lowercase() }
            }"

        val result = controller.globalAction(action)
        return if (result.success) {
            result.message.ifBlank { "已执行 $action" }
        } else {
            "失败：${result.message}"
        }
    }

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? {
        if (!has(key) || isNull(key)) return null
        return when (val v = opt(key)) {
            is Boolean -> v
            is String -> when (v.trim().lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> null
            }
            is Number -> v.toInt() != 0
            else -> null
        }
    }
}