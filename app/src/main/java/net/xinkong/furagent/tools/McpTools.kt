package net.xinkong.furagent.tools

import net.xinkong.furagent.core.mcp.McpManager
import net.xinkong.furagent.shared.tools.ToolResult
import org.json.JSONObject

/**
 * 把 MCP 工具桥接到 ToolRegistry。
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
     * 动态生成工具列表（每次调用都从 McpManager 拿最新状态）。
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

    /**
     * 如果名字是 MCP 工具前缀，交给 McpManager；否则返回 null。
     */
    suspend fun execute(name: String, args: JSONObject): ToolResult? {
        if (!McpManager.isMcpTool(name)) return null
        return McpManager.callTool(name, args)
    }
}