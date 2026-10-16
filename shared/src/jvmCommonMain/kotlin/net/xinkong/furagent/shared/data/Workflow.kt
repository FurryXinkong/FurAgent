package net.xinkong.furagent.shared.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工作流步骤。
 * stepType = "call"       调用一个工具（toolName + argsTemplate）
 *           "condition"   条件分支（conditionExpr + trueBranch + falseBranch）
 *           "loop"        循环（loopVar + loopItems + loopBody）
 *           "delay"       延时（delaySeconds）
 *           "set_var"     设置变量（varName + varValue）
 *
 * 变量引用语法：
 *   $var         直接引用
 *   ${var}       带花括号引用
 *   {var}        原语法，保留兼容
 *   $prev        上一步的工具返回值
 *   $index       循环当前下标（loop 内）
 */
data class WorkflowStep(
    val stepType: String = "call",
    val toolName: String = "",
    val argsTemplate: Map<String, String> = emptyMap(),
    val conditionExpr: String = "",
    val trueBranch: List<WorkflowStep> = emptyList(),
    val falseBranch: List<WorkflowStep> = emptyList(),
    val loopVar: String = "",
    val loopItems: String = "",
    val loopBody: List<WorkflowStep> = emptyList(),
    val delaySeconds: Int = 0,
    val varName: String = "",
    val varValue: String = ""
)

data class Workflow(
    val id: String,
    val name: String,
    val description: String,
    val params: List<String>,
    val steps: List<WorkflowStep>
)

/**
 * Workflow JSON 编解码 + 变量替换（双端共用）。
 *
 * 【冗余清理 Batch 7】原来 Android 和桌面的 WorkflowStore 各有一份
 * 逐字相同的 parseSteps / stepsToJson / substitute（共约 190 行）。
 * 现统一到这里。
 */
object WorkflowCodec {

    /** 解析 steps 数组（递归支持嵌套分支 / 循环体）。 */
    fun parseSteps(arr: JSONArray): List<WorkflowStep> {
        val list = mutableListOf<WorkflowStep>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val argsObj = s.optJSONObject("args") ?: JSONObject()
            val argsMap = mutableMapOf<String, String>()
            argsObj.keys().forEach { k -> argsMap[k] = argsObj.optString(k, "") }

            list.add(
                WorkflowStep(
                    stepType = s.optString("stepType", "call"),
                    toolName = s.optString("tool", ""),
                    argsTemplate = argsMap,
                    conditionExpr = s.optString("conditionExpr", ""),
                    trueBranch = s.optJSONArray("trueBranch")?.let { parseSteps(it) } ?: emptyList(),
                    falseBranch = s.optJSONArray("falseBranch")?.let { parseSteps(it) } ?: emptyList(),
                    loopVar = s.optString("loopVar", ""),
                    loopItems = s.optString("loopItems", ""),
                    loopBody = s.optJSONArray("loopBody")?.let { parseSteps(it) } ?: emptyList(),
                    delaySeconds = s.optInt("delaySeconds", 0),
                    varName = s.optString("varName", ""),
                    varValue = s.optString("varValue", "")
                )
            )
        }
        return list
    }

    /** 序列化 steps 数组。 */
    fun stepsToJson(steps: List<WorkflowStep>): JSONArray {
        val arr = JSONArray()
        steps.forEach { s ->
            arr.put(JSONObject().apply {
                put("stepType", s.stepType)
                put("tool", s.toolName)
                put("args", JSONObject().apply {
                    s.argsTemplate.forEach { (k, v) -> put(k, v) }
                })
                put("conditionExpr", s.conditionExpr)
                put("trueBranch", stepsToJson(s.trueBranch))
                put("falseBranch", stepsToJson(s.falseBranch))
                put("loopVar", s.loopVar)
                put("loopItems", s.loopItems)
                put("loopBody", stepsToJson(s.loopBody))
                put("delaySeconds", s.delaySeconds)
                put("varName", s.varName)
                put("varValue", s.varValue)
            })
        }
        return arr
    }

    /**
     * 变量替换。单次扫描，避免：
     *  1. 花括号错位（`{$k}`）
     *  2. 循环 replace 导致替换值里 `$other` 被二次替换
     *
     * 支持语法：$var / ${var} / {var}
     */
    fun substitute(template: String, vars: Map<String, String>): String {
        if (vars.isEmpty() || template.isEmpty()) return template

        val sb = StringBuilder(template.length)
        var i = 0
        while (i < template.length) {
            val c = template[i]

            // ${key}
            if (c == '$' && i + 1 < template.length && template[i + 1] == '{') {
                val end = template.indexOf('}', i + 2)
                if (end > 0) {
                    val key = template.substring(i + 2, end)
                    val v = vars[key]
                    if (v != null) {
                        sb.append(v)
                        i = end + 1
                        continue
                    }
                }
                sb.append(c); i++
                continue
            }

            // {key}
            if (c == '{') {
                val end = template.indexOf('}', i + 1)
                if (end > 0) {
                    val key = template.substring(i + 1, end)
                    val v = vars[key]
                    if (v != null) {
                        sb.append(v)
                        i = end + 1
                        continue
                    }
                }
                sb.append(c); i++
                continue
            }

            // $key
            if (c == '$' && i + 1 < template.length &&
                (template[i + 1].isLetter() || template[i + 1] == '_')
            ) {
                var j = i + 2
                while (j < template.length && (template[j].isLetterOrDigit() || template[j] == '_')) j++
                val key = template.substring(i + 1, j)
                val v = vars[key]
                if (v != null) {
                    sb.append(v)
                    i = j
                    continue
                }
                sb.append(c); i++
                continue
            }

            sb.append(c); i++
        }
        return sb.toString()
    }
}
