package net.xinkong.furagent.shared.util

/**
 * MCP 工具名的编解码（双端共用）。
 *
 * 格式："mcp__<clientId8>__<toolName>"。
 * 旧实现用 `id.startsWith(shortId)` 匹配，两个 clientId 前 8 位相同时会串到错误的服务器，
 * 现统一精确匹配 `id.take(8) == shortId`。
 */
object McpToolNames {

    fun isMcpTool(name: String): Boolean = name.startsWith("mcp__")

    fun prefixedToolName(clientId: String, toolName: String): String =
        "mcp__${clientId.take(8)}__$toolName"

    /** 解析成 (shortId, toolName)；格式不符返回 null。 */
    fun parsePrefixedToolName(prefixedName: String): Pair<String, String>? {
        val parts = prefixedName.split("__")
        if (parts.size < 3 || parts[0] != "mcp") return null
        val shortId = parts[1]
        val toolName = parts.drop(2).joinToString("__")
        return shortId to toolName
    }
}
