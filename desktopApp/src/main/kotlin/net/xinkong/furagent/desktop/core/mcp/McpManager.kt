package net.xinkong.furagent.desktop.core.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.xinkong.furagent.desktop.data.McpConfigStore
import net.xinkong.furagent.desktop.data.McpServerConfig
import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.shared.tools.ToolResult
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP 服务器管理器。
 *
 * 职责：
 *  - 启动 / 停止所有启用的 MCP 服务器
 *  - 暴露所有 MCP 工具
 *  - 转发工具调用
 */
object McpManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = ConcurrentHashMap<String, McpClient>()

    /**
     * 【缓存关键】按 clientId 排序后的客户端列表。
     *
     * ConcurrentHashMap 的遍历顺序不保证稳定（按 hash 桶，扩容时会变），
     * 直接遍历会导致 MCP 工具顺序每轮可能不同，进而使 tools 数组变化、
     * 前缀缓存失效。统一走这里，保证顺序稳定。
     */
    private fun sortedClients(): List<McpClient> =
        clients.values.sortedBy { it.id }

    private val _clients = MutableStateFlow<List<McpClient>>(emptyList())
    val clientList: StateFlow<List<McpClient>> = _clients.asStateFlow()

    /**
     * 启动所有启用状态的服务器。应在 App 启动时调用。
     */
    fun startAll() {
        val configs = McpConfigStore.list()
        DesktopLogger.info("MCP", "启动 ${configs.size} 个服务器")
        configs.forEach { cfg ->
            if (cfg.enabled) startServer(cfg)
        }
    }

    fun stopAll() {
        clients.values.forEach { scope.launch { it.disconnect() } }
        clients.clear()
        _clients.value = emptyList()
    }

    /** 释放全部资源（含协程作用域）。App 退出时调用。 */
    fun shutdown() {
        clients.values.forEach { it.shutdown() }
        clients.clear()
        _clients.value = emptyList()
        scope.cancel()
    }

    fun startServer(config: McpServerConfig) {
        val existing = clients[config.id]
        if (existing != null) {
            scope.launch { existing.disconnect() }
        }
        val client = McpClient(config)
        clients[config.id] = client
        _clients.value = sortedClients()
        scope.launch { client.connect() }
    }

    fun stopServer(id: String) {
        val c = clients.remove(id) ?: return
        scope.launch { c.disconnect() }
        _clients.value = sortedClients()
    }

    fun reload() {
        stopAll()
        startAll()
    }

    /**
     * 返回所有 MCP 工具。
     * 返回 (clientId, clientName, toolInfo)。
     */
    fun allTools(): List<Triple<String, String, McpClient.McpToolInfo>> {
        val out = mutableListOf<Triple<String, String, McpClient.McpToolInfo>>()
        // 用 sortedClients() 而非 clients.values，保证顺序稳定
        sortedClients().forEach { c ->
            if (c.state.value is McpClient.State.Ready) {
                c.tools.value.forEach { t ->
                    out.add(Triple(c.id, c.name, t))
                }
            }
        }
        return out
    }

    /**
     * 调用 MCP 工具。
     * 工具名格式："mcp__<clientId8>__<toolName>"
     *
     * 【修复】旧实现用 `id.startsWith(shortId)` 匹配，两个 clientId 前 8 位相同时会串到错误的服务器。
     * 改为精确匹配 `id.take(8) == shortId`。
     */
    suspend fun callTool(prefixedName: String, args: JSONObject): ToolResult {
        // 解析
        val parts = prefixedName.split("__")
        if (parts.size < 3 || parts[0] != "mcp") {
            return ToolResult.fail("不是有效的 MCP 工具名：$prefixedName")
        }
        val shortId = parts[1]
        val toolName = parts.drop(2).joinToString("__")
        val client = clients.values.firstOrNull { it.id.take(8) == shortId }
            ?: return ToolResult.fail("MCP 服务器不存在：$shortId")
        return client.callTool(toolName, args)
    }

    /**
     * 判断是否是 MCP 工具名
     */
    fun isMcpTool(name: String): Boolean = name.startsWith("mcp__")

    /**
     * 工具名前缀。使用 clientId 前 8 位。
     */
    fun prefixedToolName(clientId: String, toolName: String): String =
        "mcp__${clientId.take(8)}__$toolName"
}
