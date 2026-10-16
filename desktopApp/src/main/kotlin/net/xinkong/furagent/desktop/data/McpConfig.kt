package net.xinkong.furagent.desktop.data

import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.shared.util.FileRecovery
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*

data class McpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val transport: String = "stdio",
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true
) {
    fun summary(): String = when (transport) {
        "stdio" -> "$command ${args.joinToString(" ")}".trim()
        "http", "sse" -> url
        else -> transport
    }
}

object McpConfigStore {
    private fun file(): File = File(DesktopPaths.configDir(), "mcp.json")

    private fun atomicWrite(target: File, content: String) {
        // 【冗余清理 Batch 12a】实现已抽到 shared/util/JsonFile
        net.xinkong.furagent.shared.util.JsonFile.atomicWrite(target, content)
    }

    fun list(): List<McpServerConfig> {
        return try {
            val f = file()
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val out = mutableListOf<McpServerConfig>()
            var skipped = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o == null) { skipped++; continue }
                try {
                    out.add(McpServerConfig(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    name = o.optString("name", "未命名"),
                    transport = o.optString("transport", "stdio"),
                    command = o.optString("command", ""),
                    args = jsonArrayToStringList(o.optJSONArray("args")),
                    env = jsonObjectToStringMap(o.optJSONObject("env")),
                    url = o.optString("url", ""),
                    headers = jsonObjectToStringMap(o.optJSONObject("headers")),
                        enabled = o.optBoolean("enabled", true)
                    ))
                } catch (e: Exception) {
                    skipped++
                    DesktopLogger.warn("MCP", "mcp[$i] 解析失败，已跳过：${e.message}")
                }
            }
            if (skipped > 0) DesktopLogger.warn("MCP", "已跳过 $skipped 个异常配置")
            out
        } catch (e: Exception) {
            DesktopLogger.error(
                "MCP",
                "读取 MCP 配置失败，已备份损坏文件：${e.javaClass.simpleName}: ${e.message}"
            )
            FileRecovery.backupCorrupted(file())
            emptyList()
        }
    }

    fun save(list: List<McpServerConfig>) {
        try {
            val arr = JSONArray()
            list.forEach { s ->
                arr.put(JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("transport", s.transport)
                    put("command", s.command)
                    put("args", JSONArray().apply { s.args.forEach { put(it) } })
                    put("env", JSONObject().apply { s.env.forEach { (k, v) -> put(k, v) } })
                    put("url", s.url)
                    put("headers", JSONObject().apply { s.headers.forEach { (k, v) -> put(k, v) } })
                    put("enabled", s.enabled)
                })
            }
            atomicWrite(file(), arr.toString(2))
        } catch (e: Exception) {
            DesktopLogger.error(
                "MCP",
                "保存 MCP 配置失败（${list.size} 个）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    fun add(server: McpServerConfig) {
        val l = list().toMutableList()
        l.removeAll { it.id == server.id }
        l.add(server)
        save(l)
    }

    fun update(server: McpServerConfig) = add(server)

    fun delete(id: String) {
        save(list().filterNot { it.id == id })
    }

    // 【冗余清理 Batch 12c】jsonArrayToStringList / jsonObjectToStringMap
    // 抽到 shared/util/JsonFile。
    private fun jsonArrayToStringList(arr: JSONArray?): List<String> =
        net.xinkong.furagent.shared.util.JsonFile.jsonArrayToStringList(arr)

    private fun jsonObjectToStringMap(obj: JSONObject?): Map<String, String> =
        net.xinkong.furagent.shared.util.JsonFile.jsonObjectToStringMap(obj)
}