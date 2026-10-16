package net.xinkong.furagent.data

import android.content.Context
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.shared.util.FileRecovery
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*

/**
 * MCP 服务器配置。
 *
 * 与桌面端格式完全一致（可互相复制配置）。
 * Android 端支持 "http"（Streamable HTTP）与 "sse"（旧版 HTTP+SSE）传输（无法 spawn 子进程）。
 */
data class McpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val transport: String = "http",
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true
) {
    fun summary(): String = when (transport) {
        "stdio" -> "（Android 不支持 stdio）"
        "http", "sse" -> url
        else -> transport
    }
}

object McpConfigStore {

    private const val FILE_NAME = "mcp.json"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun atomicWrite(target: File, content: String) {
        // 【冗余清理 Batch 12a】实现已抽到 shared/util/JsonFile
        net.xinkong.furagent.shared.util.JsonFile.atomicWrite(target, content)
    }

    /**
     * 读取 MCP 配置。
     *
     * 【修复 P0】
     *   - 数组元素不是 object → 跳过（不再整体崩）
     *   - 单条字段缺失/类型异常 → 单条 try-catch 跳过
     *   - 顶层反序列化失败 → FileRecovery 备份损坏文件（TOAI §14.2）
     */
    fun list(context: Context): List<McpServerConfig> {
        return try {
            val f = file(context)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val out = mutableListOf<McpServerConfig>()
            var skipped = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o == null) {
                    skipped++
                    continue
                }
                try {
                    out.add(
                        McpServerConfig(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            name = o.optString("name", "未命名"),
                            transport = o.optString("transport", "http"),
                            command = o.optString("command", ""),
                            args = jsonArrayToStringList(o.optJSONArray("args")),
                            env = jsonObjectToStringMap(o.optJSONObject("env")),
                            url = o.optString("url", ""),
                            headers = jsonObjectToStringMap(o.optJSONObject("headers")),
                            enabled = o.optBoolean("enabled", true)
                        )
                    )
                } catch (e: Exception) {
                    skipped++
                    AgentLogger.log(
                        "MCP",
                        "mcp[$i] 解析失败，已跳过：${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }
            if (skipped > 0) {
                AgentLogger.log(
                    "MCP",
                    "已跳过 $skipped 个异常 MCP 配置，保留 ${out.size} 个"
                )
            }
            out
        } catch (e: Exception) {
            AgentLogger.log(
                "MCP",
                "读取 MCP 配置失败，已备份损坏文件：${e.javaClass.simpleName}: ${e.message}"
            )
            FileRecovery.backupCorrupted(file(context))
            emptyList()
        }
    }

    fun save(context: Context, list: List<McpServerConfig>) {
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
            atomicWrite(file(context), arr.toString(2))
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "保存 MCP 配置失败（${list.size} 个）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    fun add(context: Context, server: McpServerConfig) {
        val l = list(context).toMutableList()
        l.removeAll { it.id == server.id }
        l.add(server)
        save(context, l)
    }

    fun update(context: Context, server: McpServerConfig) = add(context, server)

    fun delete(context: Context, id: String) {
        save(context, list(context).filterNot { it.id == id })
    }

    // 【冗余清理 Batch 12c】jsonArrayToStringList / jsonObjectToStringMap
    // 抽到 shared/util/JsonFile。
    private fun jsonArrayToStringList(arr: JSONArray?): List<String> =
        net.xinkong.furagent.shared.util.JsonFile.jsonArrayToStringList(arr)

    private fun jsonObjectToStringMap(obj: JSONObject?): Map<String, String> =
        net.xinkong.furagent.shared.util.JsonFile.jsonObjectToStringMap(obj)
}