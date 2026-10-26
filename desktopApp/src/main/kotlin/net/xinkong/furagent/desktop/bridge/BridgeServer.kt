package net.xinkong.furagent.desktop.bridge

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.xinkong.furagent.desktop.data.DesktopSettingsHolder
import net.xinkong.furagent.desktop.data.getSystemHostname
import net.xinkong.furagent.desktop.data.primaryMacAddress
import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.desktop.tools.DesktopTools
import net.xinkong.furagent.desktop.tools.PhoneTools
import net.xinkong.furagent.shared.*
import net.xinkong.furagent.shared.util.FileNaming
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.*
import java.nio.channels.UnresolvedAddressException
import java.nio.file.AccessDeniedException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

object BridgeServer {

    private val RE_DRIVE_LETTER = Regex("^[A-Za-z]:")

    enum class Status { STOPPED, STARTING, RUNNING, ERROR }

    enum class ErrorType {
        NONE,
        PORT_IN_USE,
        PERMISSION_DENIED,
        ADDRESS_UNAVAILABLE,
        UNKNOWN
    }

    data class ClientInfo(
        val id: String,
        val remote: String,
        val endpoint: String,
        val connectedAt: Long,
        val deviceName: String = "",
        val mac: String = ""
    )

    private const val AUTO_RESTART_DELAY_SEC = 15
    private const val MAX_CONSECUTIVE_FAILURES = 5

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var server: ServerImpl? = null
    @Volatile private var restartJob: Job? = null
    @Volatile private var consecutiveFailures: Int = 0

    private val _status = MutableStateFlow(Status.STOPPED)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _port = MutableStateFlow(BridgeConfig.DEFAULT_PORT)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _clients = MutableStateFlow<List<ClientInfo>>(emptyList())
    val clients: StateFlow<List<ClientInfo>> = _clients.asStateFlow()

    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError.asStateFlow()

    private val _errorType = MutableStateFlow(ErrorType.NONE)
    val errorType: StateFlow<ErrorType> = _errorType.asStateFlow()

    private val _restartCountdown = MutableStateFlow(0)
    val restartCountdown: StateFlow<Int> = _restartCountdown.asStateFlow()

    private val _toolCallCount = MutableStateFlow(0L)
    val toolCallCount: StateFlow<Long> = _toolCallCount.asStateFlow()

    private val connIdMap = ConcurrentHashMap<WebSocket, String>()
    private val connEndpointMap = ConcurrentHashMap<String, String>()
    private val connTimeMap = ConcurrentHashMap<String, Long>()
    private val connDeviceNameMap = ConcurrentHashMap<String, String>()
    private val connMacMap = ConcurrentHashMap<String, String>()

    /** relayId → relayPath（相对 receiveDir 的路径），用于清理中转文件 */
    private val relayPathMap = ConcurrentHashMap<String, String>()

    fun isRunning(): Boolean = _status.value == Status.RUNNING
    fun toolCount(): Int = DesktopTools.TOOLS.size + PhoneTools.TOOLS.size

    fun start(port: Int = BridgeConfig.DEFAULT_PORT) {
        if (server != null) return

        restartJob?.cancel()
        restartJob = null
        _restartCountdown.value = 0
        _errorType.value = ErrorType.NONE

        _port.value = port
        _status.value = Status.STARTING
        _lastError.value = ""

        scope.launch {
            try {
                val s = ServerImpl(InetSocketAddress(port))
                s.isReuseAddr = true
                s.start()
                server = s
            } catch (e: Exception) {
                handleStartFailure(e)
            }
        }
    }

    fun stop() {
        restartJob?.cancel()
        restartJob = null
        _restartCountdown.value = 0
        _errorType.value = ErrorType.NONE
        consecutiveFailures = 0

        val s = server
        server = null
        try { s?.stop(1000) } catch (e: Exception) {
            DesktopLogger.warn("Bridge", "stop 异常：${e.message}")
        }
        connIdMap.clear(); connEndpointMap.clear(); connTimeMap.clear()
        connDeviceNameMap.clear(); connMacMap.clear()
        relayPathMap.clear()
        _clients.value = emptyList()
        _status.value = Status.STOPPED
    }

    fun broadcast(msg: BridgeMessage) {
        val text = msg.encodeToString()
        connIdMap.keys.forEach { ws ->
            try { if (ws.isOpen) ws.send(text) } catch (e: Exception) {
                DesktopLogger.warn("Bridge", "broadcast 单连接失败：${e.message}")
            }
        }
    }

    fun sendTo(clientId: String, msg: BridgeMessage): Boolean {
        val text = msg.encodeToString()
        connIdMap.forEach { (ws, id) ->
            if (id == clientId && ws.isOpen) {
                return try {
                    ws.send(text); true
                } catch (e: Exception) {
                    DesktopLogger.error("Bridge", "sendTo($clientId) 失败：${e.message}")
                    false
                }
            }
        }
        DesktopLogger.warn("Bridge", "sendTo 目标不存在或已关闭：$clientId")
        return false
    }

    private fun sendError(ws: WebSocket, refId: String, message: String) {
        try {
            ws.send(BridgeMessage.error(EndpointId.PC, message, refId).encodeToString())
        } catch (e: Exception) {
            DesktopLogger.warn("Bridge", "发送 error 失败：${e.message}")
        }
    }

    private fun refreshClients() {
        _clients.value = connIdMap.map { (ws, id) ->
            ClientInfo(
                id = id,
                remote = ws.remoteSocketAddress?.toString() ?: "unknown",
                endpoint = connEndpointMap[id] ?: "unknown",
                connectedAt = connTimeMap[id] ?: System.currentTimeMillis(),
                deviceName = connDeviceNameMap[id] ?: "",
                mac = connMacMap[id] ?: ""
            )
        }
    }

    private fun classifyError(e: Throwable): ErrorType {
        var cur: Throwable? = e
        var sawPermission = false
        var sawAddress = false

        while (cur != null) {
            if (cur is BindException) return ErrorType.PORT_IN_USE
            val msg1 = (cur.message ?: "").lowercase(Locale.ROOT)
            if (msg1.contains("address already in use")) return ErrorType.PORT_IN_USE
            if (msg1.contains("已在使用的地址")) return ErrorType.PORT_IN_USE
            if (msg1.contains("bind") && msg1.contains("use")) return ErrorType.PORT_IN_USE
            if (msg1.contains("无法绑定")) return ErrorType.PORT_IN_USE

            if (cur is AccessDeniedException) sawPermission = true
            if (cur is SecurityException) sawPermission = true
            val msg2 = (cur.message ?: "").lowercase(Locale.ROOT)
            if (msg2.contains("permission denied")) sawPermission = true
            if (msg2.contains("access denied")) sawPermission = true
            if (msg2.contains("not permitted")) sawPermission = true
            if (msg2.contains("permissionexception")) sawPermission = true
            if (msg2.contains("拒绝访问")) sawPermission = true
            if (msg2.contains("权限不足")) sawPermission = true
            if (msg2.contains("没有权限")) sawPermission = true

            if (cur is NoRouteToHostException) sawAddress = true
            if (cur is PortUnreachableException) sawAddress = true
            if (cur is UnresolvedAddressException) sawAddress = true
            val msg3 = (cur.message ?: "").lowercase(Locale.ROOT)
            if (msg3.contains("no route to host")) sawAddress = true
            if (msg3.contains("network is unreachable")) sawAddress = true
            if (msg3.contains("cannot assign requested address")) sawAddress = true
            if (msg3.contains("bind to address")) sawAddress = true
            if (msg3.contains("socketexception") && msg3.contains("address")) sawAddress = true
            if (msg3.contains("无法分配请求的地址")) sawAddress = true
            if (msg3.contains("地址不可用")) sawAddress = true
            if (msg3.contains("网络不可达")) sawAddress = true
            if (cur is SocketException && msg3.contains("invalid argument")) sawAddress = true

            cur = cur.cause
        }

        if (sawPermission) return ErrorType.PERMISSION_DENIED
        if (sawAddress) return ErrorType.ADDRESS_UNAVAILABLE
        return ErrorType.UNKNOWN
    }

    private fun friendlyMessage(type: ErrorType, e: Throwable): String = when (type) {
        ErrorType.PORT_IN_USE -> "端口 ${_port.value} 被占用"
        ErrorType.PERMISSION_DENIED ->
            "权限不足：端口 ${_port.value} 可能被系统占用或保留，建议换一个大于 1024 的端口"
        ErrorType.ADDRESS_UNAVAILABLE -> "地址不可用：网卡未就绪或网络配置异常"
        ErrorType.UNKNOWN -> {
            val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            "未知错误：$msg"
        }
        ErrorType.NONE -> ""
    }

    private fun handleStartFailure(e: Throwable) {
        val type = classifyError(e)
        DesktopLogger.error("Bridge", "启动失败($type): ${e.javaClass.simpleName}: ${e.message}")
        _errorType.value = type
        _lastError.value = friendlyMessage(type, e)
        _status.value = Status.ERROR
        server = null

        consecutiveFailures++
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            DesktopLogger.error(
                "Bridge",
                "已连续失败 $consecutiveFailures 次，停止自动重启。请手动修正后重启应用。"
            )
            _restartCountdown.value = 0
            _lastError.value += "（已停止自动重启）"
            return
        }

        scheduleAutoRestart(_port.value)
    }

    private fun scheduleAutoRestart(targetPort: Int) {
        if (restartJob?.isActive == true) return
        restartJob = scope.launch {
            try {
                var remaining = AUTO_RESTART_DELAY_SEC
                while (remaining > 0) {
                    _restartCountdown.value = remaining
                    delay(1000L.milliseconds)
                    remaining--
                }
                _restartCountdown.value = 0
                if (_status.value == Status.STOPPED) return@launch
                DesktopLogger.info(
                    "Bridge",
                    "自动重启：尝试重新监听 $targetPort（第 $consecutiveFailures 次）"
                )
                start(targetPort)
            } catch (e: CancellationException) {
                _restartCountdown.value = 0
                throw e
            } catch (e: Exception) {
                _restartCountdown.value = 0
                DesktopLogger.error("Bridge", "自动重启调度异常：${e.message}")
            }
        }
    }

    private fun getRemoteIp(ws: WebSocket): String {
        return when (val addr = ws.remoteSocketAddress) {
            is InetSocketAddress -> {
                val ip = addr.address?.hostAddress
                if (!ip.isNullOrBlank()) ip else addr.hostString ?: "unknown"
            }
            null -> "unknown"
        }
    }

    // relay 中转文件清理

    private fun cleanupRelayFile(relayPath: String) {
        try {
            val receiveDir = DesktopSettingsHolder.current.receiveDir
            val file = File(receiveDir, relayPath)
            if (file.exists()) {
                val ok = file.delete()
                DesktopLogger.info(
                    "Bridge",
                    "relay 中转文件${if (ok) "已清理" else "清理失败"}：$relayPath"
                )
            }
            val parent = file.parentFile
            if (parent != null && parent.exists()) {
                val kids = parent.listFiles()
                if (kids == null || kids.isEmpty()) {
                    parent.delete()
                }
            }
        } catch (e: Exception) {
            DesktopLogger.warn("Bridge", "清理 relay 文件失败：${e.message}")
        }
    }

    private fun cleanupStaleRelayDirs() {
        try {
            val receiveDir = DesktopSettingsHolder.current.receiveDir
            val relayRoot = File(receiveDir, "relay")
            if (!relayRoot.exists() || !relayRoot.isDirectory) return
            val cutoff = System.currentTimeMillis() - 24L * 3600 * 1000
            relayRoot.listFiles()?.forEach { dir ->
                if (dir.lastModified() < cutoff) {
                    val ok = dir.deleteRecursively()
                    if (ok) {
                        DesktopLogger.info("Bridge", "清理过期 relay 目录：${dir.name}")
                    }
                }
            }
        } catch (e: Exception) {
            DesktopLogger.warn("Bridge", "清理过期 relay 目录失败：${e.message}")
        }
    }

    private fun handleMessage(conn: WebSocket, msg: BridgeMessage) {
        when (msg.type) {
            MessageType.HELLO -> {
                val clientId = connIdMap[conn]
                val deviceName = msg.payload["deviceName"] ?: ""
                val peerMac = msg.payload["mac"] ?: ""

                if (clientId != null) {
                    connEndpointMap[clientId] = msg.from.ifBlank { "unknown" }
                    if (deviceName.isNotBlank()) connDeviceNameMap[clientId] = deviceName
                    if (peerMac.isNotBlank()) connMacMap[clientId] = peerMac
                    refreshClients()
                }
                DesktopLogger.info(
                    "Bridge",
                    "hello from=${msg.from} device=$deviceName mac=$peerMac"
                )

                val hp = msg.payload["httpPort"]?.toIntOrNull() ?: 0
                val ht = msg.payload["httpToken"] ?: ""
                if (hp > 0 && ht.isNotBlank()) {
                    if (clientId != null) {
                        PhoneTools.registerClientHttp(clientId, hp, ht, deviceName)
                    }
                }

                try {
                    val welcomeMsg = BridgeMessage.welcomeWithHttp(
                        from = EndpointId.PC,
                        version = BridgeConfig.PROTOCOL_VERSION,
                        httpPort = HttpFileServer.getPort(),
                        httpToken = HttpFileServer.getToken(),
                        deviceName = getSystemHostname(),
                        mac = primaryMacAddress()
                    )
                    conn.send(welcomeMsg.encodeToString())
                } catch (e: Exception) {
                    DesktopLogger.error("Bridge", "发送 welcome 失败：${e.javaClass.simpleName}: ${e.message}")
                }
            }
            MessageType.PING -> try {
                conn.send(BridgeMessage.pong(msg.id, EndpointId.PC).encodeToString())
            } catch (e: Exception) {
                DesktopLogger.warn("Bridge", "回复 pong 失败：${e.message}")
            }
            MessageType.PONG -> { }
            MessageType.REQUEST_DEVICE_INFO -> {
                try {
                    conn.send(BridgeMessage.deviceInfo(
                        EndpointId.PC, HttpFileServer.getPort(), HttpFileServer.getToken()
                    ).encodeToString())
                } catch (e: Exception) {
                    DesktopLogger.error("Bridge", "发送 deviceInfo 失败：${e.message}")
                }
            }
            MessageType.DEVICE_INFO -> {
                val clientId = connIdMap[conn]
                val hp = msg.payload["httpPort"]?.toIntOrNull() ?: 0
                val ht = msg.payload["httpToken"] ?: ""
                val dn = msg.payload["deviceName"] ?: ""
                val mac = msg.payload["mac"] ?: ""
                DesktopLogger.info("Bridge", "收到 DEVICE_INFO httpPort=$hp device=$dn mac=$mac")
                if (hp > 0 && ht.isNotBlank()) {
                    if (clientId != null) {
                        PhoneTools.registerClientHttp(clientId, hp, ht, dn)
                    }
                }
                if (clientId != null && mac.isNotBlank()) {
                    connMacMap[clientId] = mac
                    refreshClients()
                }
            }
            MessageType.TOOLS_LIST -> PhoneTools.onToolResult(msg)
            MessageType.TOOL_RESULT -> PhoneTools.onToolResult(msg)
            MessageType.LIST_TOOLS -> scope.launch { sendToolsList(conn, msg) }
            MessageType.TOOL_CALL -> scope.launch { handleToolCall(conn, msg) }

            MessageType.RELAY_FILE -> {
                val targetId = msg.payload["targetClientId"] ?: ""
                val relayId = msg.payload["relayId"] ?: ""
                val relayPath = msg.payload["relayPath"] ?: ""
                if (relayId.isNotBlank() && relayPath.isNotBlank()) {
                    relayPathMap[relayId] = relayPath
                }
                if (targetId.isBlank()) {
                    sendError(conn, msg.id, "relay_file 缺少 targetClientId")
                } else {
                    val ok = sendTo(targetId, msg)
                    if (!ok) {
                        sendError(conn, msg.id, "目标设备不在线：$targetId")
                        if (relayId.isNotBlank()) {
                            relayPathMap.remove(relayId)
                            cleanupRelayFile(relayPath)
                        }
                    } else {
                        DesktopLogger.info(
                            "Bridge",
                            "relay_file 已转发 → $targetId (relayId=$relayId)"
                        )
                    }
                }
            }
            MessageType.RELAY_FILE_RESULT -> {
                val targetId = msg.payload["targetClientId"] ?: ""
                val relayId = msg.payload["relayId"] ?: ""
                if (targetId.isNotBlank()) {
                    sendTo(targetId, msg)
                    DesktopLogger.info(
                        "Bridge",
                        "relay_file_result 已转发 → $targetId (relayId=$relayId)"
                    )
                }
                if (relayId.isNotBlank()) {
                    val relayPath = relayPathMap.remove(relayId)
                    if (relayPath != null) {
                        cleanupRelayFile(relayPath)
                    }
                }
            }

            MessageType.LIST_PEERS -> scope.launch { sendPeersList(conn, msg) }

            MessageType.NOTIFICATION -> {
                // 手机端通知同步：单向 手机 → 电脑
                val appName = msg.payload["appName"] ?: ""
                val pkg = msg.payload["packageName"] ?: ""
                val title = msg.payload["title"] ?: ""
                val text = msg.payload["text"] ?: ""
                val postTime = msg.payload["postTime"]?.toLongOrNull() ?: 0L
                val notifKey = msg.payload["notifKey"] ?: ""
                DesktopNotificationManager.onNotificationReceived(
                    appName = appName,
                    packageName = pkg,
                    title = title,
                    text = text,
                    postTime = postTime,
                    notifKey = notifKey
                )
            }

            else -> DesktopLogger.debug("Bridge", "未处理的消息类型：${msg.type}")
        }
    }

    private fun sendPeersList(conn: WebSocket, msg: BridgeMessage) {
        try {
            val arr = JSONArray()
            connIdMap.forEach { (ws, id) ->
                arr.put(JSONObject().apply {
                    put("id", id)
                    put("endpoint", connEndpointMap[id] ?: "")
                    put("deviceName", connDeviceNameMap[id] ?: "")
                    put("mac", connMacMap[id] ?: "")
                    put("remote", ws.remoteSocketAddress?.toString() ?: "")
                })
            }
            conn.send(BridgeMessage.peersList(
                msg.id, EndpointId.PC, arr.toString()
            ).encodeToString())
            DesktopLogger.info("Bridge", "已发送 peers 列表：${arr.length()} 个")
        } catch (e: Exception) {
            DesktopLogger.error("Bridge", "发送 peers 列表失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun sendToolsList(conn: WebSocket, msg: BridgeMessage) {
        try {
            val tools = DesktopTools.TOOLS.map {
                BridgeToolInfo(it.name, it.displayName, it.description, it.paramsSchema)
            } + PhoneTools.TOOLS.map {
                BridgeToolInfo(it.name, it.displayName, it.description, it.paramsSchema)
            }
            conn.send(BridgeMessage.toolsList(
                msg.id, EndpointId.PC, BridgeToolList(tools).encodeToString()
            ).encodeToString())
            DesktopLogger.info("Bridge", "已发送工具列表：${tools.size} 个")
        } catch (e: Exception) {
            DesktopLogger.error("Bridge", "发送工具列表失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun handleToolCall(conn: WebSocket, msg: BridgeMessage) {
        val tool = msg.payload["tool"] ?: ""
        val rawArgs = msg.payload["args"] ?: "{}"
        val rewrittenArgs = rewriteIfWrite(tool, rawArgs)
        DesktopLogger.info("Bridge", "收到工具调用：$tool")

        scope.launch {
            val result: net.xinkong.furagent.shared.tools.ToolResult =
                if (tool in PhoneTools.TOOLS.map { it.name }) {
                    net.xinkong.furagent.shared.tools.ToolResult.fail("不允许通过桥接调用 $tool")
                } else {
                    try {
                        DesktopTools.execute(tool, rewrittenArgs)
                    } catch (e: Exception) {
                        DesktopLogger.error(
                            "Bridge",
                            "桥接执行工具 $tool 异常：${e.javaClass.simpleName}: ${e.message}"
                        )
                        net.xinkong.furagent.shared.tools.ToolResult.fail(
                            "执行异常：${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                }
            _toolCallCount.update { it + 1 }
            try {
                conn.send(BridgeMessage.toolResult(
                    msg.id, EndpointId.PC, result.success, result.output
                ).encodeToString())
                DesktopLogger.info("Bridge", "工具 $tool 完成，success=${result.success}")
            } catch (e: Exception) {
                DesktopLogger.error("Bridge", "返回工具结果失败：${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * 桥接调用 write_file 时的路径处理。
     *
     * 旧实现无条件把 path 改写到 receiveDir，导致手机端 AI
     * 通过 phone_call 指定绝对路径（如 D:\project\config.json）时被静默改写，
     * AI 以为写到了指定位置，实际落在 receiveDir。
     *
     * 新规则：
     *  - path 是「纯文件名」（不含 / \ 且不含盘符）→ 视为「接收文件」，改写进 receiveDir
     *  - path 带路径（含分隔符或盘符）→ 尊重原路径，不改写
     *
     * 改写时把实际落点写进日志，并在 args 里加 _resolved_path 供上层提示。
     */
    private fun rewriteIfWrite(tool: String, argsJson: String): String {
        if (tool != "write_file") return argsJson
        return try {
            val args = JSONObject(argsJson)
            val rawPath = args.optString("path", "").trim()
            if (rawPath.isBlank()) return argsJson

            val looksLikeBareName = !rawPath.contains('/') &&
                    !rawPath.contains('\\') &&
                    !RE_DRIVE_LETTER.containsMatchIn(rawPath)

            if (!looksLikeBareName) {
                // 带路径：尊重原路径，不改写
                DesktopLogger.info("Bridge", "write_file 带路径，按原路径写入：$rawPath")
                return argsJson
            }

            val fileName = FileNaming.extractFileName(rawPath)
            val target = FileNaming.resolveReceiveFile(DesktopSettingsHolder.current.receiveDir, fileName)
            args.put("path", target.absolutePath)
            DesktopLogger.info("Bridge", "接收文件 → ${target.absolutePath}")
            args.toString()
        } catch (e: Exception) {
            DesktopLogger.warn("Bridge", "rewriteIfWrite 失败，用原始 args：${e.message}")
            argsJson
        }
    }

    private class ServerImpl(address: InetSocketAddress) : WebSocketServer(address) {
        init {
            // 关闭 Java-WebSocket 的协议级连接丢失检测（默认 60s）。
            // 背景：服务端默认每 60s 发协议级 ping，若 60s 内未收到 pong 就强制
            // 关闭 TCP，手机端表现为 code=1006 Connection reset，约 89s 掉线重连。
            // 本项目已有应用层心跳（手机端每 10s 发 PING，服务端回 PONG），
            // 两套机制并存会互相干扰，故关闭协议级检测，完全依赖应用层心跳。
            connectionLostTimeout = 0
        }

        override fun onStart() {
            DesktopLogger.info("Bridge", "已启动，监听 ${address.hostString}:${address.port}")
            _status.value = Status.RUNNING
            _lastError.value = ""
            _errorType.value = ErrorType.NONE
            _restartCountdown.value = 0
            consecutiveFailures = 0

            scope.launch {
                cleanupStaleRelayDirs()
            }
        }

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val remote = conn.remoteSocketAddress?.toString() ?: "unknown"
            val newIp = getRemoteIp(conn)

            // 踢同 IP 的旧连接。
            // 注意：先 remove map 再 close socket，顺序很重要 ——
            //   - remove map 后旧连接的 onClose 即使晚到，oldId 也是 null，
            //     不会再触发一遍清理（有 if (oldId != null) 保护）
            //   - close() 是异步的，不会阻塞当前 onOpen
            val existing = connIdMap.keys.filter { oldWs ->
                oldWs != conn && getRemoteIp(oldWs) == newIp
            }
            if (existing.isNotEmpty()) {
                DesktopLogger.info(
                    "Bridge",
                    "检测到同 IP 旧连接 ${existing.size} 个，全部踢掉（newIp=$newIp）"
                )
            }
            existing.forEach { oldWs ->
                val oldId = connIdMap.remove(oldWs)
                if (oldId != null) {
                    val oldAlive = connTimeMap[oldId]?.let {
                        System.currentTimeMillis() - it
                    } ?: 0L
                    connEndpointMap.remove(oldId)
                    connTimeMap.remove(oldId)
                    connDeviceNameMap.remove(oldId)
                    connMacMap.remove(oldId)
                    // 原来 onOpen 踢旧连接时漏了这步，
                    // 导致旧 client_id 对应的 HTTP 通道信息残留。
                    PhoneTools.removeClientHttp(oldId)
                    DesktopLogger.info(
                        "Bridge",
                        "踢旧连接：id=$oldId remote=${oldWs.remoteSocketAddress} 已存活=${oldAlive}ms"
                    )
                }
                try {
                    oldWs.close(1000, "replaced")
                } catch (e: Exception) {
                    DesktopLogger.warn(
                        "Bridge",
                        "踢旧连接 close 失败：${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }

            val id = UUID.randomUUID().toString()
            connIdMap[conn] = id
            connTimeMap[id] = System.currentTimeMillis()
            DesktopLogger.info(
                "Bridge",
                "客户端连接：id=$id from=$remote ip=$newIp 当前在线数=${connIdMap.size}"
            )
            refreshClients()
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            val id = connIdMap.remove(conn)
            var aliveMs = 0L
            if (id != null) {
                aliveMs = connTimeMap[id]?.let {
                    System.currentTimeMillis() - it
                } ?: 0L
                connEndpointMap.remove(id)
                connTimeMap.remove(id)
                connDeviceNameMap.remove(id)
                connMacMap.remove(id)
                PhoneTools.removeClientHttp(id)
            }
            // 详细日志：close code + reason + 存活时长 + 剩余在线数
            DesktopLogger.info(
                "Bridge",
                "客户端断开：id=$id code=$code reason=${reason.ifBlank { "(empty)" }} " +
                        "remoteInitiated=$remote 已存活=${aliveMs}ms " +
                        "剩余在线数=${connIdMap.size}"
            )
            refreshClients()
        }

        override fun onMessage(conn: WebSocket, message: String) {
            val msg = BridgeMessage.decodeFromString(message)
            if (msg == null) {
                DesktopLogger.warn("Bridge", "无法解析消息：${message.take(200)}")
                sendError(conn, "", "无法解析消息")
                return
            }
            try {
                handleMessage(conn, msg)
            } catch (e: Exception) {
                DesktopLogger.error(
                    "Bridge",
                    "handleMessage 异常：${e.javaClass.simpleName}: ${e.message}"
                )
                sendError(conn, msg.id, "处理消息异常：${e.message}")
            }
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            val id = conn?.let { connIdMap[it] } ?: "(server)"
            val remote = conn?.remoteSocketAddress?.toString() ?: "(server)"
            // 详细日志：区分"单连接出错"和"服务器级出错"
            DesktopLogger.error(
                "Bridge",
                "连接错误：id=$id remote=$remote " +
                        "${ex.javaClass.simpleName}: ${ex.message}"
            )

            // 单连接级的 onError（conn != null）不应该触发服务器重启流程。
            // 只有 conn == null 表示服务器本身出错（bind 失败之类）。
            if (conn != null) return

            val cur = _status.value
            if (cur == Status.STOPPED) return
            if (restartJob?.isActive == true) return
            handleStartFailure(ex)
        }
    }
}
