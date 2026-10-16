package net.xinkong.furagent.desktop.tools

import net.xinkong.furagent.desktop.core.mcp.McpManager
import net.xinkong.furagent.shared.tools.ToolResult
import org.json.JSONObject

/**
 * 把 MCP 工具桥接到 DesktopTools。
 *
 * 工具名：mcp__<clientId8>__<toolName>
 * 描述里会加上 [MCP:服务器名] 前缀，方便 AI 识别归属。
 */
object McpTools {

    data class ToolDef(
        val name: String,
        val displayName: String,
        val description: String,
        val paramsSchema: String
    )

    /**
     * 动态生成 TOOLS（每次调用时都从 McpManager 拿最新状态）。
     */
    fun getTools(): List<ToolDef> {
        return McpManager.allTools().map { (clientId, clientName, info) ->
            val prefixed = McpManager.prefixedToolName(clientId, info.name)
            val schema = info.inputSchema?.toString()
                ?: """{"type":"object","properties":{}}"""
            ToolDef(
                name = prefixed,
                displayName = "[MCP:$clientName] ${info.name}",
                description = info.description.ifBlank { "MCP 工具" },
                paramsSchema = schema
            )
        }
    }

    suspend fun execute(name: String, args: JSONObject): ToolResult? {
        if (!McpManager.isMcpTool(name)) return null
        return McpManager.callTool(name, args)
    }
}