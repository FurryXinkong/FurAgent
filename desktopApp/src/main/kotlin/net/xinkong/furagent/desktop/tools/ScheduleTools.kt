package net.xinkong.furagent.desktop.tools

import net.xinkong.furagent.desktop.data.ScheduleStore
import net.xinkong.furagent.desktop.data.ScheduledTask
import net.xinkong.furagent.desktop.data.TaskCondition
import net.xinkong.furagent.shared.tools.ToolResult
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

object ScheduleTools {

    data class ToolDef(
        val name: String,
        val displayName: String,
        val description: String,
        val paramsSchema: String
    )

    val TOOLS: List<ToolDef> = listOf(
        ToolDef(
            name = "schedule",
            displayName = "定时任务",
            description = "定时任务管理（秒级轮询 / cron / 条件触发）。op=create 创建任务（action 支持 notify/run_tool/run_workflow/open_app/run_command，可带 trigger_time / repeat_mode / interval_seconds / cron_expression / conditions 等）；op=list 列出所有任务；op=cancel 按 task_id 或 keyword 取消。run_tool 可调用任意桌面工具，包括 pc 相关的手机端调用。" +
                    "条件触发类型：time_between / weekday_in / process_running / network_available / command_matches。",
            paramsSchema = """{"type":"object","properties":{"op":{"type":"string","enum":["create","list","cancel"]},"action":{"type":"string"},"description":{"type":"string"},"trigger_time":{"type":"string"},"repeat_mode":{"type":"string"},"interval_seconds":{"type":"integer"},"cron_expression":{"type":"string"},"notification_title":{"type":"string"},"notification_content":{"type":"string"},"tool_name":{"type":"string"},"tool_args":{"type":"string"},"workflow_name":{"type":"string"},"app_path":{"type":"string"},"command":{"type":"string"},"conditions":{"type":"array","items":{"type":"object","properties":{"type":{"type":"string"},"operator":{"type":"string"},"value":{"type":"string"},"value2":{"type":"string"}}}},"condition_mode":{"type":"string"},"skip_if_not_met":{"type":"boolean"},"task_id":{"type":"string"},"keyword":{"type":"string"}},"required":["op"]}"""
        )
    )

    val NAMES: Set<String> = TOOLS.map { it.name }.toSet()

    fun displayName(name: String): String =
        TOOLS.firstOrNull { it.name == name }?.displayName ?: name

    fun execute(name: String, args: JSONObject): ToolResult? {
        if (name != "schedule") return null
        val op = args.optString("op", "").lowercase()
        return when (op) {
            "create" -> doCreate(args)
            "list" -> doList()
            "cancel" -> doCancel(args)
            else -> ToolResult.fail("op 只能是 create / list / cancel")
        }
    }

    // ============================================================

    private fun doCreate(args: JSONObject): ToolResult {
        val action = args.optString("action", "").trim()
        val description = args.optString("description", "").trim()
        if (action !in listOf("notify", "run_tool", "run_workflow", "open_app", "run_command")) {
            return ToolResult.fail("不支持的 action=$action")
        }
        if (description.isBlank()) return ToolResult.fail("description 不能为空")

        val intervalSeconds = args.optInt("interval_seconds", 0)
            .coerceIn(0, ScheduleStore.MAX_INTERVAL_SECONDS)
        val repeatMode = args.optString("repeat_mode", "once").trim()
        if (repeatMode !in listOf("once", "interval", "daily", "cron")) {
            return ToolResult.fail("不支持的 repeat_mode=$repeatMode")
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val triggerStr = args.optString("trigger_time", "").trim()
        val triggerTime: Long = when {
            triggerStr.isNotBlank() -> {
                try {
                    sdf.parse(triggerStr)?.time
                        ?: return ToolResult.fail("时间格式错误：$triggerStr")
                } catch (_: Exception) {
                    return ToolResult.fail("时间格式错误：$triggerStr")
                }
            }
            repeatMode == "interval" -> System.currentTimeMillis() + intervalSeconds * 1000L
            repeatMode == "cron" -> System.currentTimeMillis() + 60_000L
            else -> return ToolResult.fail("非 interval/cron 模式必须提供 trigger_time")
        }

        if (triggerTime < System.currentTimeMillis() && repeatMode != "cron") {
            return ToolResult.fail("触发时间已过去")
        }
        if (repeatMode == "interval" && intervalSeconds < ScheduleStore.MIN_INTERVAL_SECONDS) {
            return ToolResult.fail("interval_seconds 最小 ${ScheduleStore.MIN_INTERVAL_SECONDS}")
        }
        if (repeatMode == "cron" && args.optString("cron_expression", "").isBlank()) {
            return ToolResult.fail("repeat_mode=cron 需要 cron_expression")
        }

        val conds = mutableListOf<TaskCondition>()
        args.optJSONArray("conditions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
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

        val task = ScheduledTask(
            id = ScheduleStore.newId(),
            triggerTime = triggerTime,
            repeatMode = repeatMode,
            repeatDays = emptyList(),
            intervalSeconds = if (repeatMode == "interval") intervalSeconds else 0,
            cronExpression = args.optString("cron_expression", ""),
            action = action,
            toolName = args.optString("tool_name", ""),
            toolArgs = args.optString("tool_args", ""),
            workflowName = args.optString("workflow_name", ""),
            appPath = args.optString("app_path", ""),
            command = args.optString("command", ""),
            notificationTitle = args.optString("notification_title", ""),
            notificationContent = args.optString("notification_content", ""),
            conditions = conds,
            conditionMode = args.optString("condition_mode", "and"),
            skipIfNotMet = args.optBoolean("skip_if_not_met", true),
            description = description
        )

        when (action) {
            "run_tool" -> if (task.toolName.isBlank()) {
                return ToolResult.fail("run_tool 需要 tool_name")
            }
            "run_workflow" -> if (task.workflowName.isBlank()) {
                return ToolResult.fail("run_workflow 需要 workflow_name")
            }
            "open_app" -> if (task.appPath.isBlank()) {
                return ToolResult.fail("open_app 需要 app_path")
            }
            "run_command" -> if (task.command.isBlank()) {
                return ToolResult.fail("run_command 需要 command")
            }
        }

        ScheduleStore.add(task)

        val nextText = when (repeatMode) {
            "cron" -> "cron: ${task.cronExpression}"
            "interval" -> ScheduleStore.formatInterval(task.intervalSeconds)
            "daily" -> "每天 ${sdf.format(Date(triggerTime))}"
            else -> sdf.format(Date(triggerTime))
        }
        val condText = if (conds.isEmpty()) ""
        else "\n条件（${if (task.conditionMode == "or") "任意" else "全部"}满足）：" +
                conds.joinToString("\n") { c -> "  - ${c.type} ${c.operator} ${c.value}" }

        return ToolResult.ok(
            "已创建定时任务：\n" +
                    "ID：${task.id}\n" +
                    "描述：$description\n" +
                    "动作：$action\n" +
                    "重复：$nextText" + condText
        )
    }

    private fun doList(): ToolResult {
        val list = ScheduleStore.list()
        if (list.isEmpty()) return ToolResult.ok("当前没有定时任务。")
        val sb = StringBuilder("共 ${list.size} 个定时任务：\n\n")
        list.forEachIndexed { i, t ->
            sb.append("${i + 1}. ${t.description}\n")
            sb.append("   ID：${t.id}\n")
            sb.append("   动作：${t.action}")
            if (t.action == "run_tool") sb.append("（${t.toolName}）")
            if (t.action == "run_workflow") sb.append("（${t.workflowName}）")
            if (t.action == "run_command") sb.append("（${t.command.take(40)}）")
            sb.append("\n")
            sb.append("   下次：${ScheduleStore.formatTime(t.triggerTime)}\n")
            val repeat = when (t.repeatMode) {
                "interval" -> "每 ${t.intervalSeconds} 秒"
                "daily" -> "每天"
                "cron" -> "cron: ${t.cronExpression}"
                else -> "单次"
            }
            sb.append("   重复：$repeat\n")
            if (t.conditions.isNotEmpty()) {
                sb.append("   条件：${t.conditions.size} 个（${t.conditionMode}）\n")
            }
            sb.append("\n")
        }
        return ToolResult.ok(sb.toString())
    }

    private fun doCancel(args: JSONObject): ToolResult {
        val taskId = args.optString("task_id", "").trim()
        val keyword = args.optString("keyword", "").trim()
        val list = ScheduleStore.list()
        val target = when {
            taskId.isNotBlank() -> list.firstOrNull { it.id == taskId }
            keyword.isNotBlank() -> list.firstOrNull {
                it.description.contains(keyword, ignoreCase = true)
            }
            else -> null
        } ?: return ToolResult.fail("未找到匹配的任务")
        ScheduleStore.delete(target.id)
        return ToolResult.ok("已取消：${target.description}")
    }
}