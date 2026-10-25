package net.xinkong.furagent.data

import android.content.Context
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.shared.util.FileRecovery
import net.xinkong.furagent.shared.util.McpConfigCodec
import net.xinkong.furagent.shared.util.JsonFile
import java.io.File

// McpServerConfig 数据类已移入 shared/util/McpServerConfig.kt
// 这里 typealias 保持既有 import 路径不变
typealias McpServerConfig = net.xinkong.furagent.shared.util.McpServerConfig

/** Android 端 summary 扩展：stdio 显示不支持提示 */
fun McpServerConfig.androidSummary(): String = when (transport) {
    "stdio" -> "（Android 不支持 stdio）"
    "http", "sse" -> url
    else -> transport
}

object McpConfigStore {

    private const val FILE_NAME = "mcp.json"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun list(context: Context): List<McpServerConfig> =
        McpConfigCodec.deserialize(file(context)) { msg -> AgentLogger.log("MCP", msg) }

    fun save(context: Context, list: List<McpServerConfig>) {
        try {
            JsonFile.atomicWrite(file(context), McpConfigCodec.serialize(list))
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
}
