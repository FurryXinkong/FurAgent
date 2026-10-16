package net.xinkong.furagent.desktop.tools

import net.xinkong.furagent.shared.tools.ToolResult
import net.xinkong.furagent.shared.util.*
import org.json.JSONObject

/**
 * 屏幕控制工具（`ui`）。桌面端实现。
 *
 * 底层走 UiaScreenController，通过 PowerShell 桥接 Windows UI Automation。
 *
 * 【不截断】
 *  UI 树的文本量本身不大，不设上限。
 */
object ScreenTools {

    data class ToolDef(
        val name: String,
        val displayName: String,
        val description: String,
        val paramsSchema: String,
    )

    val TOOLS: List<ToolDef> = listOf(
        ToolDef(
            name = "ui",
            displayName = "屏幕控制",
            description = "屏幕控制。直接读取屏幕 UI 元素树并操作，不依赖截图，" +
                    "Token 消耗低，坐标不受窗口移动影响。底层使用 Windows UI Automation。" +
                    "op=status 查服务状态；op=dump 获取当前 UI 树；" +
                    "op=find 按条件查找元素（AND 语义）；op=action 对元素操作（需 node_id）；" +
                    "op=gesture 手势；op=global 系统动作。各 op 的参数见 parameters。" +
                    "node_id 只在下一次 dump/find 之前有效，UI 变化后需重新获取。" +
                    "典型工作流：先 dump 或 find 拿 id，再 action。首次调用可能有几百毫秒冷启动延迟。" +
                    "UI 树中标记 (invisible) 的元素当前不可见，不要直接对它执行 action。" +
                    "标记 {gN} 表示这几个节点来自同一个被压平的父容器，同一组内节点有并列关系。",
            paramsSchema = """{"type":"object","properties":{"op":{"type":"string","enum":["status","dump","find","action","gesture","global"]},"max_depth":{"type":"integer"},"max_nodes":{"type":"integer"},"text":{"type":"string"},"text_exact":{"type":"boolean"},"regex":{"type":"string"},"class_name":{"type":"string"},"resource_id":{"type":"string"},"clickable":{"type":"boolean"},"editable":{"type":"boolean"},"scrollable":{"type":"boolean"},"enabled":{"type":"boolean"},"max_results":{"type":"integer"},"node_id":{"type":"string"},"action":{"type":"string"},"type":{"type":"string"},"x":{"type":"integer"},"y":{"type":"integer"},"start_x":{"type":"integer"},"start_y":{"type":"integer"},"end_x":{"type":"integer"},"end_y":{"type":"integer"},"duration_ms":{"type":"integer"}},"required":["op"]}""",
        ),
    )

    val NAMES: Set<String> = TOOLS.map { it.name }.toSet()

    fun displayName(name: String): String =
        TOOLS.firstOrNull { it.name == name }?.displayName ?: name

    suspend fun execute(name: String, args: JSONObject): ToolResult? {
        if (name != "ui") return null

        val controller = ScreenControlRegistry.get()
            ?: return ToolResult.fail(
                "屏幕控制不可用：UIA 控制器未初始化。可能系统不是 Windows，" +
                        "或 PowerShell / .NET UIA 组件不可用。"
            )

        if (!controller.isAvailable()) {
            return ToolResult.fail(
                "屏幕控制不可用：UIA 组件未就绪。" +
                        "请确认系统是 Windows 10+，且 PowerShell 可执行策略允许运行脚本。"
            )
        }

        val op = args.optString("op", "").lowercase()
        return when (op) {
            "status" -> doStatus()
            "dump" -> doDump(args, controller)
            "find" -> doFind(args, controller)
            "action" -> doAction(args, controller)
            "gesture" -> doGesture(args, controller)
            "global" -> doGlobal(args, controller)
            else -> ToolResult.fail("op 只能是 status / dump / find / action / gesture / global")
        }
    }

    private fun doStatus(): ToolResult {
        val os = System.getProperty("os.name") + " " + System.getProperty("os.version")
        return ToolResult.ok(
            buildString {
                append("屏幕控制：Windows UI Automation\n")
                append("系统：$os\n")
                append("状态：已就绪（每次操作冷启动 PowerShell 约 300~800ms）")
            }
        )
    }

    private suspend fun doDump(
        args: JSONObject,
        controller: net.xinkong.furagent.shared.util.ScreenController,
    ): ToolResult {
        val maxDepth = args.optInt("max_depth", 30)
        val maxNodes = args.optInt("max_nodes", 2000)
        val dump = controller.dumpScreen(maxDepth, maxNodes)
        return ToolResult.ok(UiTreeFormatter.toText(dump))
    }

    private suspend fun doFind(
        args: JSONObject,
        controller: net.xinkong.furagent.shared.util.ScreenController,
    ): ToolResult {
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
            return ToolResult.fail(
                "至少需要一个查询条件（text / regex / class_name / resource_id / " +
                        "clickable / editable / scrollable / enabled）"
            )
        }

        val results = controller.findNodes(query)
        if (results.isEmpty()) {
            return ToolResult.ok(
                "未找到匹配节点（条件：${UiTreeFormatter.describeQuery(query)}）。\n" +
                        "提示：先用 op=dump 看完整结构，或放宽条件。"
            )
        }

        val sb = StringBuilder()
        sb.append("找到 ${results.size} 个匹配节点（条件：${UiTreeFormatter.describeQuery(query)}）：\n\n")
        results.forEach { sb.append(UiTreeFormatter.describeNode(it)).append("\n") }
        sb.append("\n用 op=action node_id=<上面的 id> 对目标元素操作。")
        return ToolResult.ok(sb.toString().trim())
    }

    private suspend fun doAction(
        args: JSONObject,
        controller: net.xinkong.furagent.shared.util.ScreenController,
    ): ToolResult {
        val nodeId = args.optString("node_id", "").trim()
        if (nodeId.isBlank()) return ToolResult.fail("未提供 node_id")

        val actionStr = args.optString("action", "").trim()
        if (actionStr.isBlank()) return ToolResult.fail("未提供 action")

        val action = UiAction.parse(actionStr)
            ?: return ToolResult.fail(
                "未知动作 \"$actionStr\"。可用：${
                    UiAction.entries.joinToString(", ") { it.name.lowercase() }
                }"
            )

        val text = args.optString("text", "")
        if (action == UiAction.SET_TEXT && text.isEmpty()) {
            return ToolResult.fail("SET_TEXT 需要 text 参数")
        }

        val r = controller.performAction(nodeId, action, text)
        return if (r.success) {
            ToolResult.ok(r.message.ifBlank { "已执行 $action" })
        } else {
            ToolResult.fail(r.message)
        }
    }

    private suspend fun doGesture(
        args: JSONObject,
        controller: net.xinkong.furagent.shared.util.ScreenController,
    ): ToolResult {
        val type = args.optString("type", "").trim().lowercase()
        if (type !in listOf("tap", "long_press", "swipe")) {
            return ToolResult.fail("type 只能是 tap / long_press / swipe")
        }

        val coords: IntArray = when (type) {
            "swipe" -> {
                val sx = args.optInt("start_x", -1)
                val sy = args.optInt("start_y", -1)
                val ex = args.optInt("end_x", -1)
                val ey = args.optInt("end_y", -1)
                if (sx < 0 || sy < 0 || ex < 0 || ey < 0) {
                    return ToolResult.fail(
                        "swipe 需要 start_x / start_y / end_x / end_y（屏幕像素）"
                    )
                }
                intArrayOf(sx, sy, ex, ey)
            }
            else -> {
                val x = args.optInt("x", -1)
                val y = args.optInt("y", -1)
                if (x < 0 || y < 0) {
                    return ToolResult.fail("$type 需要 x / y（屏幕像素）")
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

        val r = controller.dispatchGesture(gesture)
        return if (r.success) ToolResult.ok(r.message.ifBlank { "已执行 $type" })
        else ToolResult.fail(r.message)
    }

    private suspend fun doGlobal(
        args: JSONObject,
        controller: net.xinkong.furagent.shared.util.ScreenController,
    ): ToolResult {
        val actionStr = args.optString("action", "").trim()
        if (actionStr.isBlank()) return ToolResult.fail("未提供 action")

        val action = GlobalAction.parse(actionStr)
            ?: return ToolResult.fail(
                "未知全局动作 \"$actionStr\"。可用：${
                    GlobalAction.entries.joinToString(", ") { it.name.lowercase() }
                }"
            )

        val r = controller.globalAction(action)
        return if (r.success) ToolResult.ok(r.message.ifBlank { "已执行 $action" })
        else ToolResult.fail(r.message)
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