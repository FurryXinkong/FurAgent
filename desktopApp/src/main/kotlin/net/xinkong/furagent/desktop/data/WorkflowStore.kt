package net.xinkong.furagent.desktop.data

import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.shared.util.FileRecovery
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*


object WorkflowStore {

    private fun file(): File = File(DesktopPaths.rootDir(), "workflows.json")

    private fun atomicWrite(target: File, content: String) {
        net.xinkong.furagent.shared.util.JsonFile.atomicWrite(target, content)
    }

    fun list(): List<Workflow> {
        return try {
            val f = file()
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val out = mutableListOf<Workflow>()
            var skipped = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o == null) { skipped++; continue }
                // 单条容错：一个工作流坏掉不应把全部工作流冲掉
                try {
                    val paramsArr = o.optJSONArray("params") ?: JSONArray()
                    val params = (0 until paramsArr.length()).mapNotNull {
                        paramsArr.optString(it, "").takeIf { s -> s.isNotBlank() }
                    }
                    val stepsArr = o.optJSONArray("steps") ?: JSONArray()
                    val steps = parseSteps(stepsArr)
                    out.add(
                        Workflow(
                            id = o.optString("id", ""),
                            name = o.optString("name", ""),
                            description = o.optString("description", ""),
                            params = params,
                            steps = steps
                        )
                    )
                } catch (e: Exception) {
                    skipped++
                    DesktopLogger.warn("Workflow", "workflow[$i] 解析失败，已跳过：${e.message}")
                }
            }
            if (skipped > 0) DesktopLogger.warn("Workflow", "已跳过 $skipped 个异常工作流")
            out
        } catch (e: Exception) {
            DesktopLogger.error(
                "Workflow",
                "读取工作流失败，已备份损坏文件：${e.javaClass.simpleName}: ${e.message}"
            )
            FileRecovery.backupCorrupted(file())
            emptyList()
        }
    }

    fun find(nameOrId: String): Workflow? {
        val list = list()
        return list.firstOrNull { it.id == nameOrId }
            ?: list.firstOrNull { it.name == nameOrId }
    }

    fun save(workflow: Workflow) {
        val current = list().toMutableList()
        current.removeAll { it.id == workflow.id }
        current.add(workflow)
        persist(current)
    }

    fun delete(id: String) {
        val current = list().toMutableList()
        current.removeAll { it.id == id }
        persist(current)
    }

    private fun persist(list: List<Workflow>) {
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
            atomicWrite(file(), arr.toString())
        } catch (e: Exception) {
            DesktopLogger.error(
                "Workflow",
                "保存工作流失败（${list.size} 个）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun parseSteps(arr: JSONArray): List<WorkflowStep> =
        net.xinkong.furagent.shared.data.WorkflowCodec.parseSteps(arr)

    private fun stepsToJson(steps: List<WorkflowStep>): JSONArray =
        net.xinkong.furagent.shared.data.WorkflowCodec.stepsToJson(steps)

    /**
     * substitute 抽到 shared/data/WorkflowCodec。
     * 保留函数名，让既有调用点（WorkflowTools / SettingsPage）不改。
     */
    fun substitute(template: String, vars: Map<String, String>): String =
        net.xinkong.furagent.shared.data.WorkflowCodec.substitute(template, vars)

    fun newId(): String = UUID.randomUUID().toString()
}