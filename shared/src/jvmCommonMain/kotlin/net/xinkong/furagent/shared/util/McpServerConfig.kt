package net.xinkong.furagent.shared.util

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*

/**
 * MCP 服务器配置（共享数据类 + 序列化）。
 *
 * 从 app/desktopApp 两端的 McpServerConfig + McpConfigStore 提取。
 * 平台差异仅 defaultTransport 和 summary() 的 stdio 展示，其余完全相同。
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
    /** 通用 summary：http/sse 返回 url，其他返回 transport 名。平台可扩展。 */
    fun summary(): String = when (transport) {
        "http", "sse" -> url
        else -> transport
    }
}

/**
 * MCP 配置序列化 / 反序列化（共享逻辑）。
 *
 * 平台负责提供 File（配置文件路径）和日志回调；
 * 这里只做 JSON <-> McpServerConfig 的转换。
 */
object McpConfigCodec {

    fun deserialize(file: File, onError: (String) -> Unit = {}): List<McpServerConfig> {
        return try {
            if (!file.exists()) return emptyList()
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            val out = mutableListOf<McpServerConfig>()
            var skipped = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o == null) { skipped++; continue }
                try {
                    out.add(fromJson(o))
                } catch (e: Exception) {
                    skipped++
                    onError("mcp[$i] 解析失败，已跳过：${e.javaClass.simpleName}: ${e.message}")
                }
            }
            if (skipped > 0) {
                onError("已跳过 $skipped 个异常 MCP 配置，保留 ${out.size} 个")
            }
            out
        } catch (e: Exception) {
            onError("读取 MCP 配置失败：${e.javaClass.simpleName}: ${e.message}")
            FileRecovery.backupCorrupted(file)
            emptyList()
        }
    }

    fun serialize(list: List<McpServerConfig>): String {
        val arr = JSONArray()
        list.forEach { s -> arr.put(toJson(s)) }
        return arr.toString(2)
    }

    fun fromJson(o: JSONObject): McpServerConfig = McpServerConfig(
        id = o.optString("id", UUID.randomUUID().toString()),
        name = o.optString("name", "未命名"),
        transport = o.optString("transport", "http"),
        command = o.optString("command", ""),
        args = JsonFile.jsonArrayToStringList(o.optJSONArray("args")),
        env = JsonFile.jsonObjectToStringMap(o.optJSONObject("env")),
        url = o.optString("url", ""),
        headers = JsonFile.jsonObjectToStringMap(o.optJSONObject("headers")),
        enabled = o.optBoolean("enabled", true)
    )

    fun toJson(s: McpServerConfig): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("transport", s.transport)
        put("command", s.command)
        put("args", JSONArray().apply { s.args.forEach { put(it) } })
        put("env", JSONObject().apply { s.env.forEach { (k, v) -> put(k, v) } })
        put("url", s.url)
        put("headers", JSONObject().apply { s.headers.forEach { (k, v) -> put(k, v) } })
        put("enabled", s.enabled)
    }
}
