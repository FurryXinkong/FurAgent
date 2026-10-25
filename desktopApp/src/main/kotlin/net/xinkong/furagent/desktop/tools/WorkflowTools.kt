package net.xinkong.furagent.desktop.tools

import kotlinx.coroutines.delay
import net.xinkong.furagent.desktop.data.Workflow
import net.xinkong.furagent.desktop.data.WorkflowStep
import net.xinkong.furagent.desktop.data.WorkflowStore
import net.xinkong.furagent.shared.tools.ToolResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

object WorkflowTools {

    private val RE_RANGE = Regex("""^(\d+)\.\.(\d+)$""")
    private val RE_LENGTH = Regex("^length(>=|<=|==|=|>|<)(\\d+)$")
    private val RE_NUM_OP = Regex("^(>=|<=|==|=|>|<)(-?\\d+(?:\\.\\d+)?)$")

    private const val MAX_RECURSION_DEPTH = 10

    data class ToolDef(
        val name: String,
        val displayName: String,
        val description: String,
        val paramsSchema: String
    )

    val TOOLS: List<ToolDef> = listOf(
        ToolDef(
            name = "workflow",
            displayName = "工作流",
            description = "工作流管理。op=create 创建或更新工作流（name + steps_json，可选 description / params）；op=delete 按 name 删除；op=list 列出所有工作流；op=run 执行指定工作流（name，可选 params）。steps_json 是 JSON 数组，每元素描述一个步骤，stepType 支持 call/condition/loop/delay/set_var。",
            paramsSchema = """{"type":"object","properties":{"op":{"type":"string","enum":["create","delete","list","run"]},"name":{"type":"string"},"description":{"type":"string"},"params":{"type":"object"},"steps_json":{"type":"string"}},"required":["op"]}"""
        )
    )

    val NAMES: Set<String> = TOOLS.map { it.name }.toSet()

    fun displayName(name: String): String =
        TOOLS.firstOrNull { it.name == name }?.displayName ?: name

    /**
     * @param executor 用来执行工具的回调。通常传入 DesktopTools::execute 的闭包。
     */
    suspend fun execute(
        name: String,
        args: JSONObject,
        executor: suspend (String, JSONObject) -> ToolResult
    ): ToolResult? {
        if (name != "workflow") return null
        val op = args.optString("op", "").lowercase()
        return when (op) {
            "create" -> doCreate(args)
            "delete" -> doDelete(args)
            "list" -> doList()
            "run" -> doRun(args, executor)
            else -> ToolResult.fail("op 只能是 create / delete / list / run")
        }
    }

    // create

    private fun doCreate(args: JSONObject): ToolResult {
        val name = args.optString("name", "").trim()
        if (name.isBlank()) return ToolResult.fail("未提供 name")
        val description = args.optString("description", "").trim()
        val paramsRaw = args.optString("params", "").trim()
        val params = paramsRaw.split(',').map { it.trim() }.filter { it.isNotBlank() }
        val stepsJson = args.optString("steps_json", "").trim()
        if (stepsJson.isBlank()) return ToolResult.fail("未提供 steps_json")

        val stepsArr = try { JSONArray(stepsJson) } catch (e: Exception) {
            return ToolResult.fail("steps_json 不是合法 JSON 数组（${e.message}）")
        }
        val steps = parseWorkflowSteps(stepsArr)
        if (steps.isEmpty()) return ToolResult.fail("工作流没有有效步骤")

        val existing = WorkflowStore.find(name)
        val id = existing?.id ?: WorkflowStore.newId()
        WorkflowStore.save(Workflow(id, name, description, params, steps))

        return if (existing != null) {
            ToolResult.ok("已更新工作流「$name」，共 ${steps.size} 步")
        } else {
            ToolResult.ok("已创建工作流「$name」，共 ${steps.size} 步")
        }
    }

    private fun parseWorkflowSteps(arr: JSONArray): List<WorkflowStep> {
        val list = mutableListOf<WorkflowStep>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val argsObj = o.optJSONObject("args") ?: JSONObject()
            val argsMap = mutableMapOf<String, String>()
            argsObj.keys().forEach { k -> argsMap[k] = argsObj.optString(k, "") }
            list.add(
                WorkflowStep(
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
                )
            )
        }
        return list
    }

    // delete / list

    private fun doDelete(args: JSONObject): ToolResult {
        val name = args.optString("name", "").trim()
        if (name.isBlank()) return ToolResult.fail("未提供 name")
        val wf = WorkflowStore.find(name) ?: return ToolResult.fail("未找到工作流「$name」")
        WorkflowStore.delete(wf.id)
        return ToolResult.ok("已删除工作流「${wf.name}」")
    }

    private fun doList(): ToolResult {
        val list = WorkflowStore.list()
        if (list.isEmpty()) return ToolResult.ok("暂无工作流。可在设置面板中添加。")
        val sb = StringBuilder("共 ${list.size} 个工作流：\n\n")
        list.forEachIndexed { i, w ->
            sb.append("${i + 1}. ${w.name}\n")
            if (w.description.isNotBlank()) sb.append("   说明：${w.description}\n")
            if (w.params.isNotEmpty()) sb.append("   参数：${w.params.joinToString(", ")}\n")
            sb.append("   步骤数：${w.steps.size}\n\n")
        }
        return ToolResult.ok(sb.toString())
    }

    // run

    private suspend fun doRun(
        args: JSONObject,
        executor: suspend (String, JSONObject) -> ToolResult,
        depth: Int = 0
    ): ToolResult {
        val nameOrId = args.optString("name", "").trim()
        if (nameOrId.isBlank()) return ToolResult.fail("未提供工作流名称")

        // 【修复 B6】递归深度用参数传递，不再用全局 AtomicInteger。
        // 旧实现是全局计数，两个工作流并发执行时会互相干扰（各嵌套 5 层就误判超限）。
        if (depth > MAX_RECURSION_DEPTH) {
            return ToolResult.fail("工作流递归深度超过 $MAX_RECURSION_DEPTH 层，已中止")
        }

        val wf = WorkflowStore.find(nameOrId)
            ?: return ToolResult.fail("未找到工作流 \"$nameOrId\"")

        val paramsObj = args.optJSONObject("params") ?: JSONObject()
        val paramsMap = mutableMapOf<String, String>()
        paramsObj.keys().forEach { k -> paramsMap[k] = paramsObj.optString(k, "") }

        val missing = wf.params.filter { it !in paramsMap || paramsMap[it].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            return ToolResult.fail("缺少必需参数：${missing.joinToString(", ")}")
        }

        val log = StringBuilder()
        log.append("执行工作流「${wf.name}」共 ${wf.steps.size} 步：\n\n")
        val vars = mutableMapOf<String, String>()
        vars.putAll(paramsMap)
        executeSteps(wf.steps, vars, executor, log, 0, depth)
        return ToolResult.ok(log.toString())
    }

    private suspend fun executeSteps(
        steps: List<WorkflowStep>,
        vars: MutableMap<String, String>,
        executor: suspend (String, JSONObject) -> ToolResult,
        log: StringBuilder,
        depth: Int,
        workflowDepth: Int = 0
    ) {
        // 【修复 B7】步骤嵌套上限从 10 提到 30。
        // 10 层对正常用户太浅（循环套条件套循环就 3 层了）。
        if (depth > 30) {
            log.append("[警告] 工作流步骤嵌套过深（$depth），已终止\n")
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
                    log.append("$prefix> ${displayNameFor(toolName)}\n")

                    val r = try {
                        executor(toolName, stepArgs)
                    } catch (e: Exception) {
                        ToolResult.fail("异常：${e.message}")
                    }
                    vars["prev"] = r.output
                    log.append("$prefix  结果：${r.output.take(300)}\n\n")
                }

                "condition" -> {
                    val condResult = evaluateCondition(conditionExpr, vars)
                    log.append("$prefix◇ 条件：$conditionExpr -> $condResult\n")
                    val branch = if (condResult) trueBranch else falseBranch
                    if (branch.isNotEmpty()) {
                        executeSteps(branch, vars, executor, log, depth + 1, workflowDepth)
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
                        executeSteps(loopBody, vars, executor, log, depth + 1, workflowDepth)
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

    private fun displayNameFor(toolName: String): String {
        if (toolName.isBlank()) return "(未指定工具)"
        val fromDesktop = DesktopTools.displayName(toolName)
        if (fromDesktop != toolName) return fromDesktop
        val fromWorkflow = displayName(toolName)
        if (fromWorkflow != toolName) return fromWorkflow
        return toolName
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

    private fun evaluateCondition(expr: String, vars: Map<String, String>): Boolean {
        val prev = vars["prev"] ?: ""
        val e = WorkflowStore.substitute(expr, vars).trim()
        if (e.isEmpty()) return prev.isNotBlank()

        if (e.startsWith("not:", true)) {
            return !evaluateCondition(e.substring(4), vars)
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
}