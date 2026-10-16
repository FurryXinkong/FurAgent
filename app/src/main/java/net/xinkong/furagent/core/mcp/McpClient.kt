package net.xinkong.furagent.core.mcp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.data.McpServerConfig
import net.xinkong.furagent.shared.tools.ToolResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * 单个 MCP 服务器连接（Android）。
 *
 * Android 无法 spawn 子进程，只支持网络传输：
 *  - http：Streamable HTTP（2025-03-26 规范）
 *      · POST 请求，Accept 同时声明 application/json 与 text/event-stream
 *      · 响应可能是单个 JSON，也可能是 SSE 流（data: 行）
 *      · initialize 响应若带 Mcp-Session-Id 头，后续请求自动回传
 *  - sse：旧版 HTTP+SSE（2024-11-05 规范）
 *      · 先 GET 建立 SSE 长连接，服务器通过该流推送所有响应
 *      · POST 只用于发送请求，响应从 SSE 流读取
 *
 * 【P2-4 修复】`connectSse` 改为 suspend，去掉 runBlocking。
 *   调用方 connect() 本身就是 suspend，无需阻塞线程等建流完成。
 */
class McpClient(val config: McpServerConfig) {

    sealed class State {
        object Disconnected : State()
        object Connecting : State()
        object Ready : State()
        data class Error(val msg: String) : State()
    }

    data class McpToolInfo(
        val name: String,
        val description: String,
        val inputSchema: JSONObject?
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _tools = MutableStateFlow<List<McpToolInfo>>(emptyList())
    val tools: StateFlow<List<McpToolInfo>> = _tools.asStateFlow()

    private val requestId = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()

    private var sseJob: Job? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var negotiatedVersion: String = PROTOCOL_VERSION

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // SSE 长连接需要长读超时；普通请求靠 withTimeout 控制
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val id: String get() = config.id
    val name: String get() = config.name

    suspend fun connect() {
        if (_state.value == State.Ready || _state.value == State.Connecting) return
        _state.value = State.Connecting
        try {
            when (config.transport) {
                "http" -> connectHttp()
                "sse" -> connectSse()
                else -> throw RuntimeException("Android 端只支持 http / sse 传输（当前：${config.transport}）")
            }
            initializeHandshake()
            refreshTools()
            _state.value = State.Ready
            AgentLogger.log(
                "MCP",
                "[${config.name}] 已连接（${config.transport}，协议 $negotiatedVersion），工具 ${_tools.value.size} 个"
            )
        } catch (e: Exception) {
            AgentLogger.log("ERR", "MCP [${config.name}] 连接失败：${e.message}")
            disconnect()
            _state.value = State.Error(e.message ?: "连接失败")
        }
    }

    fun disconnect() {
        sseJob?.cancel()
        sseJob = null
        sessionId = null
        pending.values.forEach { it.completeExceptionally(RuntimeException("已断开")) }
        pending.clear()
        _tools.value = emptyList()
        _state.value = State.Disconnected
    }

    /** 释放全部资源（含协程作用域）。 */
    fun shutdown() {
        disconnect()
        scope.cancel()
    }

    // ============================================================
    // http（Streamable HTTP）
    // ============================================================
    private fun connectHttp() {
        if (config.url.isBlank()) throw RuntimeException("未配置 URL")
        // Streamable HTTP 无需预先建流，握手时按需处理
    }

    // ============================================================
    // sse（旧版 HTTP+SSE）
    // ============================================================
    private suspend fun connectSse() {
        if (config.url.isBlank()) throw RuntimeException("未配置 URL")
        val req = Request.Builder()
            .url(config.url)
            .get()
            .apply {
                addHeader("Accept", "text/event-stream")
                config.headers.forEach { (k, v) -> addHeader(k, v) }
            }
            .build()

        val latch = CompletableDeferred<Unit>()
        sseJob = scope.launch {
            try {
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw RuntimeException("SSE 连接失败：HTTP ${resp.code}")
                    }
                    val ct = resp.header("Content-Type") ?: ""
                    if (!ct.contains("text/event-stream", ignoreCase = true)) {
                        throw RuntimeException("SSE 响应 Content-Type 不是 text/event-stream：$ct")
                    }
                    latch.complete(Unit)
                    readSseStream(resp) { json -> dispatchIncoming(json) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!latch.isCompleted) {
                    latch.completeExceptionally(e)
                } else {
                    AgentLogger.log("MCP", "[${config.name}] SSE 流中断：${e.message}")
                    if (_state.value == State.Ready) {
                        _state.value = State.Error("SSE 流已断开")
                    }
                }
            }
        }

        withTimeoutOrNull(15_000L.milliseconds) { latch.await() }
            ?: throw RuntimeException("SSE 建流超时")
    }

    /**
     * 读取 SSE 流，按事件聚合 data 行后回调。
     *
     * SSE 规范：一个事件由若干行组成，`data:` 行可重复，空行表示事件结束。
     * 多行 data 用 \n 连接。旧实现只取第一个 data: 行，多行 JSON 会解析失败。
     */
    private suspend fun readSseStream(resp: Response, onEvent: (JSONObject) -> Unit) {
        val source = resp.body!!.source()
        val dataBuf = StringBuilder()
        while (currentCoroutineContext().isActive) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    if (dataBuf.isNotEmpty()) {
                        val payload = dataBuf.toString()
                        dataBuf.setLength(0)
                        try {
                            onEvent(JSONObject(payload))
                        } catch (e: Exception) {
                            AgentLogger.log("MCP", "[${config.name}] SSE 事件 JSON 解析失败：${e.message}")
                        }
                    }
                }
                line.startsWith("data:") -> {
                    val v = line.substring(5).let { if (it.startsWith(" ")) it.substring(1) else it }
                    if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                    dataBuf.append(v)
                }
                line.startsWith(":") -> { /* 注释行，忽略 */ }
                else -> { /* event: / id: / retry: 等，忽略 */ }
            }
        }
        if (dataBuf.isNotEmpty()) {
            try {
                onEvent(JSONObject(dataBuf.toString()))
            } catch (_: Exception) { }
        }
    }

    private fun dispatchIncoming(json: JSONObject) {
        val id = json.optLong("id", -1L)
        if (id > 0) {
            pending.remove(id)?.complete(json)
        } else {
            handleNotification(json)
        }
    }

    private fun handleNotification(json: JSONObject) {
        val method = json.optString("method", "")
        if (method.isBlank()) return
        AgentLogger.log("MCP", "[${config.name}] 收到通知：$method")
        when (method) {
            "notifications/tools/list_changed" -> {
                scope.launch {
                    try {
                        refreshTools()
                        AgentLogger.log("MCP", "[${config.name}] 工具列表已刷新：${_tools.value.size} 个")
                    } catch (e: Exception) {
                        AgentLogger.log("MCP", "[${config.name}] 刷新工具列表失败：${e.message}")
                    }
                }
            }
            else -> { }
        }
    }

    // ============================================================
    // MCP 握手
    // ============================================================
    private suspend fun initializeHandshake() {
        val params = JSONObject().apply {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", JSONObject())
            put("clientInfo", JSONObject().apply {
                put("name", "FurAgent-Android")
                put("version", "1.0")
            })
        }
        val resp = sendRequest("initialize", params)
        resp.optJSONObject("result")?.optString("protocolVersion", "")
            ?.takeIf { it.isNotBlank() }
            ?.let { negotiatedVersion = it }
        try {
            sendNotification("notifications/initialized", null)
        } catch (e: Exception) {
            AgentLogger.log(
                "MCP",
                "[${config.name}] 发送 initialized 通知失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    suspend fun refreshTools() {
        val result = sendRequest("tools/list", null)
        val toolsArr = result.optJSONObject("result")?.optJSONArray("tools")
            ?: result.optJSONArray("tools")
            ?: JSONArray()
        val list = mutableListOf<McpToolInfo>()
        for (i in 0 until toolsArr.length()) {
            val t = toolsArr.getJSONObject(i)
            list.add(
                McpToolInfo(
                    name = t.optString("name", ""),
                    description = t.optString("description", ""),
                    inputSchema = t.optJSONObject("inputSchema")
                )
            )
        }
        _tools.value = list
    }

    // ============================================================
    // 调用工具
    // ============================================================
    suspend fun callTool(toolName: String, args: JSONObject): ToolResult {
        if (_state.value != State.Ready) {
            return ToolResult.fail("MCP [${config.name}] 未就绪")
        }
        return try {
            val params = JSONObject().apply {
                put("name", toolName)
                put("arguments", args)
            }
            val resp = sendRequest("tools/call", params)
            resp.optJSONObject("error")?.let { err ->
                return ToolResult.fail("MCP 错误：${err.optString("message", "unknown")}")
            }
            val result = resp.optJSONObject("result") ?: return ToolResult.ok("(空结果)")
            val isError = result.optBoolean("isError", false)
            val contentArr = result.optJSONArray("content") ?: JSONArray()
            val sb = StringBuilder()
            for (i in 0 until contentArr.length()) {
                val item = contentArr.getJSONObject(i)
                when (item.optString("type", "")) {
                    "text" -> sb.append(item.optString("text", "")).append("\n")
                    "image" -> sb.append("[image ${item.optString("mimeType", "")}]").append("\n")
                    "resource" -> sb.append("[resource ${item.optString("uri", "")}]").append("\n")
                    else -> sb.append(item.toString()).append("\n")
                }
            }
            val out = sb.toString().trim().ifBlank { "(空)" }
            if (isError) ToolResult.fail(out) else ToolResult.ok(out)
        } catch (e: Exception) {
            ToolResult.fail("MCP 调用失败：${e.message}")
        }
    }

    // ============================================================
    // JSON-RPC over HTTP
    // ============================================================
    private suspend fun sendNotification(method: String, params: JSONObject?) {
        try {
            val json = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", method)
                if (params != null) put("params", params)
            }
            val req = buildHttpRequest(json.toString())
            withContext(Dispatchers.IO) { httpClient.newCall(req).execute().close() }
        } catch (e: Exception) {
            AgentLogger.log(
                "MCP",
                "[${config.name}] 发送通知 $method 失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun buildHttpRequest(body: String): Request {
        return Request.Builder()
            .url(config.url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply {
                addHeader("Accept", "application/json, text/event-stream")
                addHeader("Content-Type", "application/json")
                // 协商后的协议版本（服务器可能返回比我们低的版本）
                addHeader("MCP-Protocol-Version", negotiatedVersion)
                sessionId?.let { addHeader("Mcp-Session-Id", it) }
                config.headers.forEach { (k, v) -> addHeader(k, v) }
            }
            .build()
    }

    private suspend fun sendRequest(method: String, params: JSONObject?): JSONObject {
        return when (config.transport) {
            "http" -> sendHttpRequest(method, params)
            "sse" -> sendSseRequest(method, params)
            else -> throw RuntimeException("不支持的传输类型：${config.transport}")
        }
    }

    /**
     * Streamable HTTP 请求。
     *
     * 响应可能是：
     *  - application/json：单个 JSON-RPC 响应
     *  - text/event-stream：SSE 流，从中读取 id 匹配的响应
     */
    private suspend fun sendHttpRequest(method: String, params: JSONObject?): JSONObject {
        val id = requestId.incrementAndGet()
        val json = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        val req = buildHttpRequest(json.toString())

        val resp = withContext(Dispatchers.IO) { httpClient.newCall(req).execute() }
        resp.use { r ->
            r.header("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }

            if (!r.isSuccessful) {
                val body = try { r.body?.string() ?: "" } catch (_: Exception) { "" }
                throw RuntimeException("HTTP ${r.code}：${body.take(200)}")
            }

            val ct = r.header("Content-Type") ?: ""
            if (ct.contains("text/event-stream", ignoreCase = true)) {
                // 【关键】readSseStream 是阻塞读，服务器可能保持连接不关闭，
                // 直接同步调用会永远卡住，withTimeoutOrNull 根本执行不到。
                // 因此放到独立协程里读，主协程只等 found，超时后取消读取协程。
                val found = CompletableDeferred<JSONObject>()
                val readerJob = scope.launch {
                    try {
                        readSseStream(r) { evt ->
                            val evtId = evt.optLong("id", -1L)
                            if (evtId == id) found.complete(evt) else dispatchIncoming(evt)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        found.completeExceptionally(e)
                    } finally {
                        if (!found.isCompleted) {
                            found.completeExceptionally(RuntimeException("SSE 流已结束"))
                        }
                    }
                }
                try {
                    return withTimeoutOrNull(60_000L.milliseconds) { found.await() }
                        ?: throw RuntimeException("SSE 响应超时：$method")
                } finally {
                    readerJob.cancel()
                }
            }

            val body = r.body?.string() ?: ""
            if (body.isBlank()) throw RuntimeException("空响应：$method")
            return JSONObject(body.trim())
        }
    }

    /**
     * 旧版 HTTP+SSE 请求：POST 只负责发送，响应从已建立的 SSE 流读取。
     */
    private suspend fun sendSseRequest(method: String, params: JSONObject?): JSONObject {
        val id = requestId.incrementAndGet()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred

        val json = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        val req = buildHttpRequest(json.toString())

        try {
            withContext(Dispatchers.IO) {
                httpClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) {
                        val body = try { r.body?.string() ?: "" } catch (_: Exception) { "" }
                        throw RuntimeException("HTTP ${r.code}：${body.take(200)}")
                    }
                }
            }
        } catch (e: Exception) {
            pending.remove(id)
            throw e
        }

        return try {
            withTimeoutOrNull(60_000L.milliseconds) { deferred.await() }
                ?: throw RuntimeException("SSE 响应超时：$method")
        } finally {
            pending.remove(id)
        }
    }

    companion object {
        /** 优先使用的协议版本（Streamable HTTP）。 */
        const val PROTOCOL_VERSION = "2025-03-26"
    }
}