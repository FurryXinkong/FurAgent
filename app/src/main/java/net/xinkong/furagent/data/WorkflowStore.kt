package net.xinkong.furagent.data

import android.content.Context
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.shared.util.FileRecovery
import org.json.JSONArray
import org.json.JSONObject
import java.io.File


object WorkflowStore {
    private const val FILE_NAME = "workflows.json"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /**
     * 单条脏数据不再清空整个工作流列表。
     * 顶层反序列化失败 → FileRecovery 备份损坏文件。
     */
    fun list(context: Context): List<Workflow> {
        return try {
            val f = file(context)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val out = mutableListOf<Workflow>()
            var skipped = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o == null) {
                    skipped++
                    continue
                }
                try {
                    val paramsArr = o.optJSONArray("params") ?: JSONArray()
                    val params = (0 until paramsArr.length()).mapNotNull { idx ->
                        paramsArr.optString(idx, "").takeIf { it.isNotBlank() }
                    }
                    val stepsArr = o.optJSONArray("steps") ?: JSONArray()
                    val steps = parseSteps(stepsArr)
                    out.add(Workflow(
                        id = o.optString("id", ""),
                        name = o.optString("name", ""),
                        description = o.optString("description", ""),
                        params = params,
                        steps = steps
                    ))
                } catch (e: Exception) {
                    skipped++
                    AgentLogger.log(
                        "Workflow",
                        "workflow[$i] 解析失败，已跳过：${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }
            if (skipped > 0) {
                AgentLogger.log("Workflow", "已跳过 $skipped 个异常工作流，保留 ${out.size} 个")
            }
            out
        } catch (e: Exception) {
            AgentLogger.log(
                "Workflow",
                "读取工作流失败，已备份损坏文件：${e.javaClass.simpleName}: ${e.message}"
            )
            FileRecovery.backupCorrupted(file(context))
            emptyList()
        }
    }

    private fun parseSteps(arr: JSONArray): List<WorkflowStep> =
        net.xinkong.furagent.shared.data.WorkflowCodec.parseSteps(arr)

    fun find(context: Context, nameOrId: String): Workflow? {
        val list = list(context)
        return list.firstOrNull { it.id == nameOrId }
            ?: list.firstOrNull { it.name == nameOrId }
    }

    fun save(context: Context, workflow: Workflow) {
        val current = list(context).toMutableList()
        current.removeAll { it.id == workflow.id }
        current.add(workflow)
        persist(context, current)
    }

    fun delete(context: Context, id: String) {
        val current = list(context).toMutableList()
        current.removeAll { it.id == id }
        persist(context, current)
    }

    private fun persist(context: Context, list: List<Workflow>) {
        try {
            val arr = JSONArray()
            list.forEach { w ->
                arr.put(JSONObject().apply {
                    put("id", w.id)
                    put("name", w.name)
                    put("description", w.description)
                    put("params", JSONArray().apply { w.params.forEach { put(it) } })
                    put("steps", stepsToJson(w.steps))
                })
            }
            net.xinkong.furagent.shared.util.JsonFile.atomicWrite(file(context), arr.toString())
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "保存工作流失败（${list.size} 个）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun stepsToJson(steps: List<WorkflowStep>): JSONArray =
        net.xinkong.furagent.shared.data.WorkflowCodec.stepsToJson(steps)

    /**
     * 变量替换。与桌面端同算法：单次扫描，避免：
     *  1. 原代码 `{${'$'}{$k}}` 花括号错位
     *  2. 循环 replace 导致替换值里 `$other` 被二次替换
     *
     * 支持语法：$var / ${var} / {var}
     */
    /**
     * substitute 抽到 shared/data/WorkflowCodec。
     * 保留函数名，让既有调用点（WorkflowTools / SystemTools）不改。
     */
    fun substitute(template: String, vars: Map<String, String>): String =
        net.xinkong.furagent.shared.data.WorkflowCodec.substitute(template, vars)
}