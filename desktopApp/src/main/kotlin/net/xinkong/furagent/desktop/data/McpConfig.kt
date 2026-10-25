package net.xinkong.furagent.desktop.data

import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.shared.util.FileRecovery
import net.xinkong.furagent.shared.util.McpConfigCodec
import net.xinkong.furagent.shared.util.JsonFile
import java.io.File

// McpServerConfig 数据类已移入 shared/util/McpServerConfig.kt
// 这里 typealias 保持既有 import 路径不变
typealias McpServerConfig = net.xinkong.furagent.shared.util.McpServerConfig

/** 桌面端 summary 扩展：stdio 显示命令+参数 */
fun McpServerConfig.desktopSummary(): String = when (transport) {
    "stdio" -> "$command ${args.joinToString(" ")}".trim()
    "http", "sse" -> url
    else -> transport
}

object McpConfigStore {

    private fun file(): File = File(DesktopPaths.configDir(), "mcp.json")

    fun list(): List<McpServerConfig> =
        McpConfigCodec.deserialize(file()) { msg -> DesktopLogger.warn("MCP", msg) }

    fun save(list: List<McpServerConfig>) {
        try {
            JsonFile.atomicWrite(file(), McpConfigCodec.serialize(list))
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
}
