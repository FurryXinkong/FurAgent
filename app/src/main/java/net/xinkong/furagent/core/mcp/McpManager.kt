package net.xinkong.furagent.core.mcp

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.data.McpConfigStore
import net.xinkong.furagent.data.McpServerConfig
import net.xinkong.furagent.shared.tools.ToolResult
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP 服务器管理器（Android）。
 *
 * 职责：
 *  - 启动 / 停止所有启用的 MCP 服务器
 *  - 暴露所有 MCP 工具
 *  - 转发工具调用
 */
object McpManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = ConcurrentHashMap<String, McpClient>()

    @Volatile private var appContext: Context? = null

    private val _clients = MutableStateFlow<List<McpClient>>(emptyList())
    val clientList: StateFlow<List<McpClient>> = _clients.asStateFlow()

    /**
     * 缓存关键 按 clientId 排序后的客户端列表。
     *
     * ConcurrentHashMap 的遍历顺序不保证稳定（按 hash 桶，扩容时会变），
     * 直接遍历会导致 MCP 工具顺序每轮可能不同，进而使 tools 数组变化、
     * DeepSeek/OpenAI 兼容接口的前缀缓存失效。
     *
     * 与桌面端 McpManager.sortedClients() 行为保持一致。
     */
    private fun sortedClients(): List<McpClient> =
        clients.values.sortedBy { it.id }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun startAll() {
        val ctx = appContext ?: return
        val configs = McpConfigStore.list(ctx)
        AgentLogger.log("MCP", "启动 ${configs.count { it.enabled }} 个服务器")
        configs.forEach { cfg ->
            if (cfg.enabled) startServer(cfg)
        }
    }

    fun stopAll() {
        clients.values.forEach { it.disconnect() }
        clients.clear()
        _clients.value = emptyList()
    }

    /** 释放全部资源（含协程作用域）。 */
    fun shutdown() {
        clients.values.forEach { it.shutdown() }
        clients.clear()
        _clients.value = emptyList()
        scope.cancel()
    }

    fun startServer(config: McpServerConfig) {
        val existing = clients[config.id]
        existing?.disconnect()
        val client = McpClient(config)
        clients[config.id] = client
        _clients.value = sortedClients()
        scope.launch { client.connect() }
    }

    fun stopServer(id: String) {
        val c = clients.remove(id) ?: return
        c.disconnect()
        _clients.value = sortedClients()
    }

    fun reload() {
        stopAll()
        startAll()
    }

    /**
     * 返回所有 MCP 工具。
     * 返回 (clientId, clientName, toolInfo)。
     *
     * 缓存关键 用 sortedClients() 而非 clients.values，保证顺序稳定。
     * 否则每轮 tools 数组里 MCP 工具顺序可能变化，前缀缓存全部失效。
     */
    fun allTools(): List<Triple<String, String, McpClient.McpToolInfo>> {
        val out = mutableListOf<Triple<String, String, McpClient.McpToolInfo>>()
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
     * 旧实现用 `id.startsWith(shortId)` 匹配，两个 clientId 前 8 位相同时会串到错误的服务器。
     * 改为精确匹配 `id.take(8) == shortId`。
     */
    suspend fun callTool(prefixedName: String, args: JSONObject): ToolResult {
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

    fun isMcpTool(name: String): Boolean = name.startsWith("mcp__")

    fun prefixedToolName(clientId: String, toolName: String): String =
        "mcp__${clientId.take(8)}__$toolName"
}
