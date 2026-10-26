package net.xinkong.furagent.bridge

import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.data.*
import net.xinkong.furagent.shared.*
import net.xinkong.furagent.shared.util.FileNaming
import net.xinkong.furagent.shared.util.formatFileSize
import net.xinkong.furagent.shared.util.isToolFailure
import net.xinkong.furagent.tools.ToolRegistry
import net.xinkong.furagent.tools.WolHelper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

object PcBridgeClient {

    enum class Status { DISCONNECTED, CONNECTING, CONNECTED }

    data class PeerInfo(
        val id: String,
        val endpoint: String,
        val deviceName: String,
        val mac: String,
        val remote: String
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var heartbeatJob: Job? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var currentHost: String = ""
    @Volatile private var currentPort: Int = BridgeConfig.DEFAULT_PORT
    @Volatile private var manualDisconnect: Boolean = false
    @Volatile private var currentSettings: AppSettings? = null
    @Volatile private var connectionToken: Long = 0L

    private val pendingPings = AtomicLong(0)

    /**
     * 连接建立后必须稳定存活超过这个阈值才重置退避。
     *
     * 背景：某些场景（网络切换、旧连接未清理、中间设备干扰）会出现
     * "连上 1~2 秒就断，断了立刻重连"的循环。旧代码只要有 `onOpen`
     * 就重置 delayMs=1s，导致重连风暴、CPU 占满、日志刷屏。
     *
     * 现在：短命连接不重置，delayMs 按 1→2→4→…→30s 翻倍，
     * 稳定存活 ≥ 15 秒才回到基线。
     */
    private const val STABLE_CONNECTION_THRESHOLD_MS = 15_000L

    /** 缓存本机 MAC（避免每次查网络接口） */
    @Volatile private var cachedLocalMac: String? = null

    private val pending = ConcurrentHashMap<String, CompletableDeferred<BridgeMessage>>()

    /** relayId → (成功?, 消息) */
    private val _relayStatus = MutableStateFlow<Map<String, Pair<Boolean, String>>>(emptyMap())
    val relayStatus: StateFlow<Map<String, Pair<Boolean, String>>> = _relayStatus.asStateFlow()

    private val _status = MutableStateFlow(Status.DISCONNECTED)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _remoteTools = MutableStateFlow<List<BridgeToolInfo>>(emptyList())
    val remoteTools: StateFlow<List<BridgeToolInfo>> = _remoteTools.asStateFlow()

    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError.asStateFlow()

    private val _httpPort = MutableStateFlow(0)
    val httpPort: StateFlow<Int> = _httpPort.asStateFlow()

    private val _httpToken = MutableStateFlow("")
    val httpToken: StateFlow<String> = _httpToken.asStateFlow()

    private val _pcDeviceName = MutableStateFlow("")
    val pcDeviceName: StateFlow<String> = _pcDeviceName.asStateFlow()

    private val _pcMac = MutableStateFlow("")
    val pcMac: StateFlow<String> = _pcMac.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    fun getHost(): String = currentHost
    fun getPort(): Int = currentPort
    fun getHttpPort(): Int = _httpPort.value
    fun getHttpToken(): String = _httpToken.value
    fun getBaseUrl(): String = "http://$currentHost:${_httpPort.value}"
    fun getPcMac(): String = _pcMac.value

    fun getLocalDeviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    /**
     * 获取本机主网卡 MAC。
     * Android 6.0+ 通常拿不到（返回 02:00:00:00:00:00），拿不到返回空串。
     * 拿到的值仍会用于桥接显示和 WOL。
     */
    fun getLocalMac(): String {
        cachedLocalMac?.let { return it }
        val mac = try {
            val nics = NetworkInterface.getNetworkInterfaces()?.toList() ?: return ""
            for (nic in nics) {
                if (!nic.isUp || nic.isLoopback) continue
                val name = nic.name.lowercase(Locale.ROOT)
                if (name.contains("wlan") || name.contains("wl") ||
                    name.contains("eth") || name == "en0"
                ) {
                    val hw = nic.hardwareAddress ?: continue
                    val formatted = hw.joinToString(":") { "%02X".format(it) }
                    // 过滤明显无效的假 MAC
                    if (formatted == "02:00:00:00:00:00") continue
                    if (formatted.startsWith("00:00:00")) continue
                    cachedLocalMac = formatted
                    return formatted
                }
            }
            ""
        } catch (e: Exception) {
            AgentLogger.log(
                "BRIDGE",
                "读取本机 MAC 失败：${e.javaClass.simpleName}: ${e.message}"
            )
            ""
        }
        cachedLocalMac = mac
        return mac
    }

    fun connect(context: Context, host: String, port: Int, settings: AppSettings) {
        appContext = context.applicationContext
        currentHost = host.trim()
        currentPort = port
        currentSettings = settings
        manualDisconnect = false

        if (currentHost.isBlank()) { _lastError.value = "地址为空"; return }

        try {
            webSocket?.close(1000, "reconnect")
        } catch (e: Exception) {
            AgentLogger.log(
                "BRIDGE",
                "关闭旧 socket 失败（继续重连）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
        webSocket = null
        heartbeatJob?.cancel(); heartbeatJob = null
        connectionToken++
        pendingPings.set(0)

        pending.values.forEach { it.completeExceptionally(RuntimeException("重连")) }
        pending.clear()

        reconnectJob?.cancel()
        reconnectJob = scope.launch { connectLoop() }
    }

    fun updateSettings(settings: AppSettings) { currentSettings = settings }

    fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel(); reconnectJob = null
        heartbeatJob?.cancel(); heartbeatJob = null
        try {
            webSocket?.close(1000, "user disconnect")
        } catch (e: Exception) {
            AgentLogger.log(
                "BRIDGE",
                "主动断开 socket 失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
        webSocket = null
        connectionToken++
        pendingPings.set(0)
        _status.value = Status.DISCONNECTED
        _httpPort.value = 0
        _httpToken.value = ""
        _pcMac.value = ""
        pending.values.forEach { it.completeExceptionally(RuntimeException("已断开")) }
        pending.clear()
    }

    /**
     * 重连主循环。
     *
     * 【F9 修复】稳定阈值重置：连接建立后，只有存活 ≥ STABLE_CONNECTION_THRESHOLD_MS
     * 才把 delayMs 拉回基线。短命连接保持翻倍退避，避免重连风暴。
     */
    private suspend fun connectLoop() {
        var delayMs = BridgeConfig.RECONNECT_BASE_DELAY_MS
        while (!manualDisconnect) {
            _status.value = Status.CONNECTING
            val opened = tryConnect()
            if (opened) {
                val connectedAt = System.currentTimeMillis()
                while (!manualDisconnect && _status.value == Status.CONNECTED) {
                    delay(1000.milliseconds)
                }
                if (manualDisconnect) break

                val survivedMs = System.currentTimeMillis() - connectedAt
                if (survivedMs >= STABLE_CONNECTION_THRESHOLD_MS) {
                    if (delayMs != BridgeConfig.RECONNECT_BASE_DELAY_MS) {
                        AgentLogger.log(
                            "BRIDGE",
                            "本次连接稳定存活 ${survivedMs}ms（≥ ${STABLE_CONNECTION_THRESHOLD_MS}ms），退避重置为基线"
                        )
                    }
                    delayMs = BridgeConfig.RECONNECT_BASE_DELAY_MS
                } else {
                    AgentLogger.log(
                        "BRIDGE",
                        "本次连接仅存活 ${survivedMs}ms（< ${STABLE_CONNECTION_THRESHOLD_MS}ms），退避保持 $delayMs ms"
                    )
                }
            } else {
                _status.value = Status.DISCONNECTED
            }
            if (manualDisconnect) break

            AgentLogger.log("BRIDGE", "将于 $delayMs ms 后重试（manualDisconnect=$manualDisconnect）")
            delay(delayMs.milliseconds)
            delayMs = (delayMs * 2).coerceAtMost(BridgeConfig.RECONNECT_MAX_DELAY_MS)
        }
    }

    private suspend fun tryConnect(): Boolean {
        val url = "ws://$currentHost:$currentPort/"
        AgentLogger.log("BRIDGE", "尝试连接 $url")
        val myToken = connectionToken
        val startMs = System.currentTimeMillis()
        val openedSignal = CompletableDeferred<Boolean>()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (myToken != connectionToken) {
                    AgentLogger.log(
                        "BRIDGE",
                        "旧 socket onOpen（token 已过期，立即关闭）：$url"
                    )
                    try {
                        webSocket.close(1000, "stale")
                    } catch (e: Exception) {
                        AgentLogger.log(
                            "BRIDGE",
                            "关闭过期 socket 失败：${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                    if (!openedSignal.isCompleted) openedSignal.complete(false)
                    return
                }
                val elapsed = System.currentTimeMillis() - startMs
                AgentLogger.log("BRIDGE", "已连接 $url（握手 ${elapsed}ms）")
                this@PcBridgeClient.webSocket = webSocket
                _status.value = Status.CONNECTED
                _lastError.value = ""
                pendingPings.set(0)

                try {
                    val hp = AndroidHttpFileServer.getPort()
                    val ht = AndroidHttpFileServer.getToken()
                    val hello = BridgeMessage.helloExtended(
                        from = EndpointId.PHONE,
                        version = BridgeConfig.PROTOCOL_VERSION,
                        deviceName = getLocalDeviceName(),
                        httpPort = hp,
                        httpToken = ht,
                        mac = getLocalMac()
                    )
                    webSocket.send(hello.encodeToString())
                } catch (e: Exception) {
                    AgentLogger.log(
                        "BRIDGE",
                        "发送 hello 失败：${e.javaClass.simpleName}: ${e.message}"
                    )
                }

                startHeartbeat()
                scope.launch { refreshRemoteTools() }
                openedSignal.complete(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (myToken != connectionToken) return
                handleIncoming(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                AgentLogger.log("BRIDGE", "对端关闭中：$url code=$code reason=$reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (myToken != connectionToken) {
                    AgentLogger.log(
                        "BRIDGE",
                        "旧 socket onClosed（token 已过期，忽略）code=$code"
                    )
                    return
                }
                val aliveMs = System.currentTimeMillis() - startMs
                AgentLogger.log(
                    "BRIDGE",
                    "连接关闭：$url code=$code reason=$reason 存活=${aliveMs}ms"
                )
                onConnectionLost()
                if (!openedSignal.isCompleted) openedSignal.complete(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (myToken != connectionToken) {
                    AgentLogger.log(
                        "BRIDGE",
                        "旧 socket onFailure（token 已过期，忽略）：${t.javaClass.simpleName}"
                    )
                    return
                }
                val aliveMs = System.currentTimeMillis() - startMs
                val respInfo = response?.let { "HTTP ${it.code}" } ?: "无 response"
                AgentLogger.log(
                    "BRIDGE",
                    "连接失败：$url 存活=${aliveMs}ms $respInfo " +
                            "${t.javaClass.simpleName}: ${t.message}"
                )
                _lastError.value = t.message ?: "连接失败"
                onConnectionLost()
                if (!openedSignal.isCompleted) openedSignal.complete(false)
            }
        }

        return try {
            val request = Request.Builder().url(url).build()
            httpClient.newWebSocket(request, listener)
            val result = withTimeoutOrNull(BridgeConfig.HANDSHAKE_TIMEOUT_MS.milliseconds) { openedSignal.await() }
            if (result == null) {
                // 【N2 修复】握手超时：可能 socket 已建立但 openedSignal 未在 8s 内完成。
                // 主动关闭当前 webSocket，避免 connectLoop 重连时两个 socket 并存。
                AgentLogger.log("BRIDGE", "握手超时（$url），主动关闭可能已建立的 socket")
                try { webSocket?.close(1000, "handshake timeout") } catch (_: Exception) { }
                onConnectionLost()
                false
            } else {
                result
            }
        } catch (e: Exception) {
            AgentLogger.log("BRIDGE", "建立连接异常：${e.message}")
            _lastError.value = e.message ?: "未知错误"
            false
        }
    }

    private fun onConnectionLost() {
        heartbeatJob?.cancel(); heartbeatJob = null
        webSocket = null
        _status.value = Status.DISCONNECTED
        pendingPings.set(0)
        pending.values.forEach { it.completeExceptionally(RuntimeException("连接已断开")) }
        pending.clear()
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(BridgeConfig.HEARTBEAT_INTERVAL_MS.milliseconds)

                // 阈值从 BridgeConfig 读（5），比旧值（3）更抗丢包
                if (pendingPings.get() >= BridgeConfig.MAX_PENDING_PINGS) {
                    AgentLogger.log(
                        "BRIDGE",
                        "心跳超时：连续 ${BridgeConfig.MAX_PENDING_PINGS} 次未收到 pong，判定连接已断"
                    )
                    _lastError.value = "心跳超时"
                    try { webSocket?.close(1000, "heartbeat timeout") } catch (_: Exception) { }
                    onConnectionLost()
                    break
                }

                try {
                    val sent = webSocket?.send(
                        BridgeMessage.ping(EndpointId.PHONE).encodeToString()
                    ) ?: false
                    if (sent) {
                        pendingPings.incrementAndGet()
                    } else {
                        AgentLogger.log("BRIDGE", "发送心跳失败：socket 未就绪")
                        // 【N3 修复】发送失败要通知连接丢失。
                        // 之前只 break，heartbeatJob 结束但 _status 还是 CONNECTED，
                        // 后续工具调用会失败但状态显示在线，要等 TCP FIN 才刷新。
                        onConnectionLost()
                        break
                    }
                } catch (e: Exception) {
                    AgentLogger.log(
                        "BRIDGE",
                        "发送心跳失败：${e.javaClass.simpleName}: ${e.message}"
                    )
                    onConnectionLost()
                    break
                }
            }
        }
    }

    private fun handleIncoming(ws: WebSocket, text: String) {
        val msg = BridgeMessage.decodeFromString(text) ?: run {
            AgentLogger.log("BRIDGE", "无法解析：${text.take(200)}")
            return
        }

        when (msg.type) {
            MessageType.PING -> try {
                ws.send(BridgeMessage.pong(msg.id, EndpointId.PHONE).encodeToString())
            } catch (e: Exception) {
                AgentLogger.log(
                    "BRIDGE",
                    "回复 pong 失败：${e.javaClass.simpleName}: ${e.message}"
                )
            }
            MessageType.PONG -> pendingPings.set(0)

            MessageType.WELCOME -> {
                val v = msg.payload["version"] ?: "?"
                val hp = msg.payload["httpPort"]?.toIntOrNull() ?: 0
                val ht = msg.payload["httpToken"] ?: ""
                val dn = msg.payload["deviceName"] ?: ""
                val macRaw = msg.payload["mac"] ?: ""
                _httpPort.value = hp
                _httpToken.value = ht
                if (dn.isNotBlank()) _pcDeviceName.value = dn
                if (macRaw.isNotBlank()) {
                    _pcMac.value = macRaw
                    val ctx = appContext
                    if (ctx != null) {
                        val normalized = KnownPcStore.normalizeMac(macRaw)
                        if (normalized.length == 12) {
                            try {
                                KnownPcStore.upsert(
                                    ctx,
                                    KnownPc(
                                        mac = normalized,
                                        displayMac = KnownPcStore.formatMac(macRaw),
                                        deviceName = dn.ifBlank { currentHost },
                                        host = currentHost,
                                        port = currentPort,
                                        lastSeenAt = System.currentTimeMillis()
                                    )
                                )
                            } catch (e: Exception) {
                                AgentLogger.log(
                                    "BRIDGE",
                                    "记录已知电脑失败（MAC=$normalized）：${e.javaClass.simpleName}: ${e.message}"
                                )
                            }
                        }
                    }
                }
                AgentLogger.log("BRIDGE", "welcome：v$v httpPort=$hp device=$dn mac=$macRaw")
            }

            MessageType.REQUEST_DEVICE_INFO -> {
                try {
                    val info = BridgeMessage.deviceInfo(
                        EndpointId.PHONE,
                        AndroidHttpFileServer.getPort(),
                        AndroidHttpFileServer.getToken()
                    )
                    ws.send(info.encodeToString())
                } catch (e: Exception) {
                    AgentLogger.log(
                        "BRIDGE",
                        "回复 deviceInfo 失败：${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }

            MessageType.DEVICE_INFO -> {
                val hp = msg.payload["httpPort"]?.toIntOrNull() ?: 0
                val ht = msg.payload["httpToken"] ?: ""
                val dn = msg.payload["deviceName"] ?: ""
                val mac = msg.payload["mac"] ?: ""
                if (hp > 0 && ht.isNotBlank()) {
                    _httpPort.value = hp
                    _httpToken.value = ht
                }
                if (dn.isNotBlank()) _pcDeviceName.value = dn
                if (mac.isNotBlank()) _pcMac.value = mac
                AgentLogger.log("BRIDGE", "收到 DEVICE_INFO httpPort=$hp device=$dn mac=$mac")
            }

            MessageType.TOOLS_LIST -> handleToolsList(msg)
            MessageType.LIST_TOOLS -> handleListToolsRequest(ws, msg)
            MessageType.TOOL_CALL -> scope.launch { handleRemoteToolCall(ws, msg) }
            MessageType.TOOL_RESULT -> { pending.remove(msg.id)?.complete(msg) }
            MessageType.ERROR -> {
                val message = msg.payload["message"] ?: ""
                AgentLogger.log("BRIDGE", "对端错误：$message")
                _lastError.value = message
                if (msg.id.isNotBlank()) {
                    pending.remove(msg.id)?.complete(msg)
                }
            }

            MessageType.RELAY_FILE -> scope.launch { handleIncomingRelayFile(ws, msg) }
            MessageType.RELAY_FILE_RESULT -> {
                val relayId = msg.payload["relayId"] ?: ""
                val success = msg.payload["success"] == "true"
                val message = msg.payload["message"] ?: ""
                if (relayId.isNotBlank()) {
                    _relayStatus.value += (relayId to (success to message))
                }
                AgentLogger.log(
                    "BRIDGE",
                    "relay_result relayId=$relayId success=$success msg=$message"
                )
            }

            MessageType.PEERS_LIST -> {
                pending.remove(msg.id)?.complete(msg)
            }

            else -> AgentLogger.log("BRIDGE", "未知消息类型：${msg.type}")
        }
    }

    // Relay 相关

    private fun handleIncomingRelayFile(ws: WebSocket, msg: BridgeMessage) {
        val relayId = msg.payload["relayId"] ?: return
        val fileName = msg.payload["fileName"] ?: "unnamed"
        val relayPath = msg.payload["relayPath"] ?: return
        val sourceClient = msg.from
        val sourceName = msg.payload["source"] ?: ""

        val ctx = appContext ?: run {
            replyRelayResult(ws, msg, sourceClient, relayId, false, "手机端未就绪")
            return
        }

        val hp = _httpPort.value
        val ht = _httpToken.value
        if (hp <= 0 || ht.isBlank()) {
            replyRelayResult(ws, msg, sourceClient, relayId, false, "HTTP 通道未就绪")
            return
        }

        val prefs = ctx.getSharedPreferences("FurAgentPrefs", Context.MODE_PRIVATE)
        val settings = SettingsStore.load(prefs)
        val target = FileNaming.resolveReceiveFile(settings.pcReceiveDir, fileName)

        val url = "http://$currentHost:$hp/download" +
                "?token=${encode(ht)}" +
                "&path=${encode(relayPath)}"

        try {
            AgentLogger.log("BRIDGE", "relay 开始下载：$url → ${target.absolutePath}")
            val req = Request.Builder().url(url).get().build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                val body = resp.use { it.body.string() }
                replyRelayResult(ws, msg, sourceClient, relayId, false, "HTTP ${resp.code}：${body.take(100)}")
                return
            }
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")
            resp.use {
                it.body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        input.copyTo(output, 128 * 1024)
                    }
                }
            }
            if (!tmp.renameTo(target)) {
                target.writeBytes(tmp.readBytes())
                tmp.delete()
            }
            val size = target.length()
            AgentLogger.log(
                "BRIDGE",
                "relay 完成：${target.absolutePath}（${formatFileSize(size)}）"
            )
            replyRelayResult(
                ws, msg, sourceClient, relayId, true,
                "已接收：${target.name}（${formatFileSize(size)}）"
            )
        } catch (e: Exception) {
            AgentLogger.log(
                "BRIDGE",
                "relay 下载失败：${e.javaClass.simpleName}: ${e.message}"
            )
            replyRelayResult(ws, msg, sourceClient, relayId, false, "下载失败：${e.message}")
        }
    }

    private fun replyRelayResult(
        ws: WebSocket,
        original: BridgeMessage,
        targetClientId: String,
        relayId: String,
        success: Boolean,
        message: String
    ) {
        try {
            val reply = BridgeMessage.relayFileResult(
                id = original.id,
                from = EndpointId.PHONE,
                targetClientId = targetClientId,
                relayId = relayId,
                success = success,
                message = message
            )
            ws.send(reply.encodeToString())
        } catch (e: Exception) {
            AgentLogger.log(
                "BRIDGE",
                "回复 relay 结果失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    /**
     * 上传文件到电脑的 HTTP 服务。
     *
     * 原来 PcHubPage.uploadFileViaHttp 和 sendRelayFile 各写了一份 OkHttp 上传逻辑，
     * 现统一到这里。
     *
     * @param dir 可选，目标子目录（相对电脑 receiveDir）。为空则落 receiveDir 根。
     * @return Pair(是否成功, 提示文本)
     */
    suspend fun uploadFileToPc(
        localFile: File,
        dir: String = ""
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val hp = _httpPort.value
        val ht = _httpToken.value
        if (hp <= 0 || ht.isBlank()) {
            // 【诊断增强】把端口和 token 长度打出来：
            // hp=0 表示电脑端没在 welcome/device_info 里给出 HTTP 端口，
            // 通常是电脑 HttpFileServer 启动失败（端口被占用 fallback 到随机端口，
            // 或防火墙/初始化顺序问题）。ht 为空表示握手没带 httpToken。
            AgentLogger.log(
                "BRIDGE",
                "上传失败：HTTP 通道未就绪（httpPort=$hp tokenLength=${ht.length}）"
            )
            return@withContext false to "HTTP 通道未就绪（httpPort=$hp）"
        }
        if (!localFile.exists() || !localFile.isFile) {
            return@withContext false to "文件不存在：${localFile.absolutePath}"
        }

        val url = buildString {
            append("http://$currentHost:$hp/upload")
            append("?token=").append(encode(ht))
            append("&path=").append(encode(localFile.name))
            if (dir.isNotBlank()) append("&dir=").append(encode(dir))
        }

        try {
            AgentLogger.log(
                "BRIDGE",
                "上传开始：${localFile.name}（${formatFileSize(localFile.length())}）→ $url"
            )
            val body = localFile.asRequestBody("application/octet-stream".toMediaType())
            val req = Request.Builder().url(url).post(body).build()
            httpClient.newCall(req).execute().use { resp ->
                // 【诊断增强】读取响应体可能失败，包一层，失败时至少能打出 HTTP 状态码
                val text = try { resp.body.string() } catch (_: Exception) { "" }
                if (resp.isSuccessful) {
                    AgentLogger.log(
                        "BRIDGE",
                        "上传成功：${localFile.name}（HTTP ${resp.code}）"
                    )
                    true to "已上传：${localFile.name}（${formatFileSize(localFile.length())}）"
                } else {
                    // 失败时把服务端的错误响应完整截出来：
                    // 常见响应：
                    //   - 403 forbidden（token 不匹配）
                    //   - 500 upload failed（目录不可写 / 磁盘满）
                    //   - 404 not found（服务没在跑或被反向代理吞了）
                    AgentLogger.log(
                        "BRIDGE",
                        "上传失败 HTTP ${resp.code}：响应=${text.take(500)}"
                    )
                    false to "HTTP ${resp.code}：${text.take(200)}"
                }
            }
        } catch (e: Exception) {
            AgentLogger.log(
                "BRIDGE",
                "上传异常：${e.javaClass.simpleName}: ${e.message}"
            )
            false to "上传失败：${e.message}"
        }
    }

    /**
     * 【批次 8】手机端从电脑下载文件 —— HTTP 流式下载。
     *
     * 【为什么新加这个方法】
     * 原 PcBrowserTab.downloadFile 走的是 `read_file(mode="base64")` 通过 WebSocket 传文件，
     * 结果被电脑端 CommonTools.maybeSpill 的 16KB 阈值截断（返回摘要而不是完整 base64），
     * MB 级文件在手机端只能解出前 30 行的碎片，写入失败或得到损坏文件。
     *
     * HTTP /download 是电脑端 HttpFileServer 的完整流式下载端点，
     * 无大小限制、无结果落盘、支持进度回调，是正确的文件传输通道。
     *
     * @return Pair(是否成功, 提示文本)
     */
    suspend fun downloadFileToLocal(
        remotePath: String,
        localFile: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val hp = _httpPort.value
        val ht = _httpToken.value
        if (hp <= 0 || ht.isBlank()) {
            AgentLogger.log(
                "BRIDGE",
                "下载失败：HTTP 通道未就绪（httpPort=$hp tokenLength=${ht.length}）"
            )
            return@withContext false to "HTTP 通道未就绪（httpPort=$hp）"
        }

        val url = "http://$currentHost:$hp/download" +
                "?token=${URLEncoder.encode(ht, "UTF-8")}" +
                "&path=${URLEncoder.encode(remotePath, "UTF-8")}"

        localFile.parentFile?.mkdirs()
        val tmp = File(localFile.parentFile, localFile.name + ".part")

        try {
            AgentLogger.log(
                "BRIDGE",
                "下载开始：$url → ${localFile.absolutePath}"
            )
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = try { resp.body.string() } catch (_: Exception) { "" }
                    AgentLogger.log("BRIDGE", "下载失败 HTTP ${resp.code}：${body.take(200)}")
                    return@withContext false to "HTTP ${resp.code}：${body.take(200)}"
                }
                val total = resp.body.contentLength()
                var downloaded = 0L
                resp.body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n == 0) continue
                            out.write(buf, 0, n)
                            downloaded += n
                            onProgress(downloaded, total)
                        }
                    }
                }
                // 完整性校验：声明长度和实际写入必须一致，否则丢弃 tmp 并报错
                if (total > 0 && downloaded != total) {
                    try { tmp.delete() } catch (_: Exception) { }
                    AgentLogger.log(
                        "BRIDGE",
                        "下载不完整：声明 $total 实际 $downloaded"
                    )
                    return@withContext false to "下载不完整：声明 $total 字节，实际 $downloaded 字节"
                }
                if (!tmp.renameTo(localFile)) {
                    localFile.writeBytes(tmp.readBytes())
                    tmp.delete()
                }
                AgentLogger.log(
                    "BRIDGE",
                    "下载完成：${localFile.absolutePath}（$downloaded 字节）"
                )
                true to "已下载：${localFile.name}（${formatFileSize(downloaded)}）"
            }
        } catch (e: Exception) {
            try { tmp.delete() } catch (_: Exception) { }
            AgentLogger.log(
                "BRIDGE",
                "下载异常：${e.javaClass.simpleName}: ${e.message}"
            )
            false to "下载失败：${e.message}"
        }
    }

    suspend fun sendRelayFile(targetClientId: String, localFile: File): String {
        val ws = webSocket ?: throw RuntimeException("未连接到电脑")
        if (_status.value != Status.CONNECTED) throw RuntimeException("连接状态异常")

        val relayId = UUID.randomUUID().toString().replace("-", "").take(16)
        val relayDir = "relay/$relayId"
        val fileName = localFile.name

        // 上传逻辑统一走 uploadFileToPc（内部已做 HTTP 通道/文件校验）
        val (uploaded, uploadMsg) = uploadFileToPc(localFile, relayDir)
        if (!uploaded) throw RuntimeException(uploadMsg)

        val msgId = UUID.randomUUID().toString()
        val relayMsg = BridgeMessage.relayFile(
            id = msgId,
            from = EndpointId.PHONE,
            targetClientId = targetClientId,
            relayId = relayId,
            fileName = fileName,
            size = localFile.length(),
            relayPath = "$relayDir/$fileName",
            source = getLocalDeviceName()
        )
        try {
            ws.send(relayMsg.encodeToString())
            AgentLogger.log("BRIDGE", "relay 消息已发送：relayId=$relayId → $targetClientId")
        } catch (e: Exception) {
            AgentLogger.log(
                "BRIDGE",
                "relay 消息发送失败：${e.javaClass.simpleName}: ${e.message}"
            )
            throw RuntimeException("发送 relay 消息失败：${e.message}")
        }

        AgentLogger.log(
            "BRIDGE",
            "relay 已发起：relayId=$relayId → $targetClientId，file=$fileName（${formatFileSize(localFile.length())}）"
        )
        return relayId
    }

    fun getRelayStatus(relayId: String): Pair<Boolean, String>? = _relayStatus.value[relayId]

    // Peers

    suspend fun listPeers(): List<PeerInfo> {
        val ws = webSocket ?: return emptyList()
        if (_status.value != Status.CONNECTED) return emptyList()

        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<BridgeMessage>()
        pending[id] = deferred
        return try {
            ws.send(BridgeMessage.listPeers(id, EndpointId.PHONE).encodeToString())
            val reply = withTimeoutOrNull(10_000L.milliseconds) { deferred.await() }
            pending.remove(id)
            if (reply == null) emptyList()
            else parsePeers(reply.payload["peers"] ?: "")
        } catch (e: Exception) {
            AgentLogger.log(
                "BRIDGE",
                "listPeers 失败：${e.javaClass.simpleName}: ${e.message}"
            )
            pending.remove(id)
            emptyList()
        }
    }

    private fun parsePeers(json: String): List<PeerInfo> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = mutableListOf<PeerInfo>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    PeerInfo(
                        id = o.optString("id", ""),
                        endpoint = o.optString("endpoint", ""),
                        deviceName = o.optString("deviceName", ""),
                        mac = o.optString("mac", ""),
                        remote = o.optString("remote", "")
                    )
                )
            }
            out
        } catch (e: Exception) {
            AgentLogger.log("BRIDGE", "解析 peers 失败：${e.message}")
            emptyList()
        }
    }

    // 工具调用

    private fun handleToolsList(msg: BridgeMessage) {
        val raw = msg.payload["tools"] ?: return
        if (raw.isBlank()) { _remoteTools.value = emptyList(); return }
        val list = BridgeToolList.decodeFromString(raw)
        _remoteTools.value = list?.tools ?: emptyList()
    }

    private fun handleListToolsRequest(ws: WebSocket, msg: BridgeMessage) {
        val settings = currentSettings
        val emptyPayload = BridgeToolList(emptyList()).encodeToString()
        if (settings == null) {
            try {
                ws.send(BridgeMessage.toolsList(msg.id, EndpointId.PHONE, emptyPayload).encodeToString())
            } catch (e: Exception) {
                AgentLogger.log(
                    "BRIDGE",
                    "回复 list_tools（settings 未就绪）失败：${e.javaClass.simpleName}: ${e.message}"
                )
            }
            return
        }
        try {
            val toolsJson = ToolRegistry.getTools(settings)
            val list = mutableListOf<BridgeToolInfo>()
            for (i in 0 until toolsJson.length()) {
                val t = toolsJson.getJSONObject(i)
                val fn = t.getJSONObject("function")
                list.add(BridgeToolInfo(
                    name = fn.getString("name"),
                    displayName = ToolRegistry.displayName(fn.getString("name")),
                    description = fn.optString("description", "").take(200),
                    paramsJson = fn.optJSONObject("parameters")?.toString() ?: ""
                ))
            }
            ws.send(BridgeMessage.toolsList(
                msg.id, EndpointId.PHONE, BridgeToolList(list).encodeToString()
            ).encodeToString())
        } catch (e: Exception) {
            AgentLogger.log(
                "BRIDGE",
                "枚举本机工具失败：${e.javaClass.simpleName}: ${e.message}"
            )
            try {
                ws.send(BridgeMessage.toolsList(msg.id, EndpointId.PHONE, emptyPayload).encodeToString())
            } catch (e2: Exception) {
                AgentLogger.log(
                    "BRIDGE",
                    "回复 list_tools（降级空列表）失败：${e2.javaClass.simpleName}: ${e2.message}"
                )
            }
        }
    }

    private suspend fun handleRemoteToolCall(ws: WebSocket, msg: BridgeMessage) {
        val tool = msg.payload["tool"] ?: ""
        val rawArgs = msg.payload["args"] ?: "{}"
        val ctx = appContext
        val settings = currentSettings
        if (ctx == null || settings == null) {
            try {
                ws.send(BridgeMessage.toolResult(msg.id, EndpointId.PHONE, false, "手机端未就绪").encodeToString())
            } catch (e: Exception) {
                AgentLogger.log(
                    "BRIDGE",
                    "回复 toolResult（未就绪）失败：${e.javaClass.simpleName}: ${e.message}"
                )
            }
            return
        }

        val argsJson = rewriteWriteArgs(tool, rawArgs, settings)

        val pair: Pair<String, Boolean> = try {
            val args = JSONObject(argsJson)
            val r = ToolRegistry.execute(tool, args, ctx, settings)
            r to !isToolFailure(r)
        } catch (e: Exception) {
            AgentLogger.log(
                "BRIDGE",
                "执行远端工具 $tool 异常：${e.javaClass.simpleName}: ${e.message}"
            )
            "执行异常：${e.message}" to false
        }

        try {
            ws.send(BridgeMessage.toolResult(msg.id, EndpointId.PHONE, pair.second, pair.first).encodeToString())
        } catch (e: Exception) {
            AgentLogger.log(
                "BRIDGE",
                "回复工具 $tool 结果失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun rewriteWriteArgs(tool: String, argsJson: String, settings: AppSettings): String {
        if (tool != "write_file") return argsJson
        return try {
            val args = JSONObject(argsJson)
            val rawPath = args.optString("path", "").trim()
            if (rawPath.isBlank()) return argsJson

            // 【与桌面端 BridgeServer.rewriteIfWrite 对齐】纯文件名才改写到 receiveDir，
            // 带路径（含 / \ 或盘符）的尊重原路径，避免 AI 指定绝对路径被静默改写。
            // 【N8 修复】之前只查 '/' 不查 '\\'，Windows 路径 foo\\bar.txt 会被误判为
            // "无路径"而改写，与电脑端行为不一致。现在两端判断条件完全一致。
            val hasSeparator = rawPath.contains('/') ||
                    rawPath.contains('\\') ||
                    Regex("^[A-Za-z]:").containsMatchIn(rawPath)
            if (hasSeparator) {
                AgentLogger.log("BRIDGE", "write_file 带路径，按原路径写入：$rawPath")
                return argsJson
            }

            val fileName = FileNaming.extractFileName(rawPath)
            val target = FileNaming.resolveReceiveFile(settings.pcReceiveDir, fileName)
            args.put("path", target.absolutePath)
            args.toString()
        } catch (e: Exception) {
            AgentLogger.log(
                "BRIDGE",
                "重写 write_file 路径失败，用原始 args：${e.javaClass.simpleName}: ${e.message}"
            )
            argsJson
        }
    }

    suspend fun refreshRemoteTools(): Boolean {
        val ws = webSocket ?: return false
        if (_status.value != Status.CONNECTED) return false
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<BridgeMessage>()
        pending[id] = deferred
        return try {
            ws.send(BridgeMessage.listTools(id, EndpointId.PHONE).encodeToString())
            val reply = withTimeoutOrNull(10_000L.milliseconds) { deferred.await() }
            pending.remove(id)
            if (reply == null) false
            else if (reply.type == MessageType.ERROR) {
                AgentLogger.log("BRIDGE", "刷新远端工具被对端拒绝：${reply.payload["message"] ?: ""}")
                false
            } else true
        } catch (e: Exception) {
            AgentLogger.log(
                "BRIDGE",
                "刷新远端工具失败：${e.javaClass.simpleName}: ${e.message}"
            )
            pending.remove(id)
            false
        }
    }

    suspend fun callRemoteTool(tool: String, args: JSONObject, timeoutSec: Int = 120): String {
        val ws = webSocket ?: return "失败：未连接到电脑"
        if (_status.value != Status.CONNECTED) return "失败：连接状态异常（${_status.value}）"
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<BridgeMessage>()
        pending[id] = deferred
        return try {
            ws.send(BridgeMessage.toolCall(id, EndpointId.PHONE, tool, args.toString()).encodeToString())
            val reply = withTimeoutOrNull((timeoutSec * 1000L).milliseconds) { deferred.await() }
            pending.remove(id)
            if (reply == null) "失败：等待电脑响应超时（${timeoutSec}秒）"
            else {
                if (reply.type == MessageType.ERROR) {
                    "失败：${reply.payload["message"] ?: "对端错误"}"
                } else {
                    val success = reply.payload["success"] == "true"
                    val result = reply.payload["result"] ?: ""
                    if (success) result else "失败：$result"
                }
            }
        } catch (e: Exception) {
            AgentLogger.log(
                "BRIDGE",
                "调用远端工具 $tool 失败：${e.javaClass.simpleName}: ${e.message}"
            )
            pending.remove(id)
            "失败：${e.message}"
        }
    }

    fun isConnected(): Boolean = _status.value == Status.CONNECTED

    /**
     * 把一条手机通知转发给电脑。
     *
     * 单向：手机 → 电脑。未连接时静默丢弃（不报错、不重试），
     * 因为通知是实时流，积压重发反而会造成风暴。
     *
     * @return true 表示已交给 WebSocket 发送队列
     */
    fun sendNotification(
        appName: String,
        packageName: String,
        title: String,
        text: String,
        postTime: Long,
        notifKey: String = ""
    ): Boolean {
        val ws = webSocket ?: return false
        if (_status.value != Status.CONNECTED) return false
        return try {
            val msg = BridgeMessage.notification(
                from = EndpointId.PHONE,
                appName = appName,
                packageName = packageName,
                title = title,
                text = text,
                postTime = postTime,
                notifKey = notifKey
            )
            ws.send(msg.encodeToString())
        } catch (e: Exception) {
            AgentLogger.log(
                "NOTIFY",
                "转发通知失败：${e.javaClass.simpleName}: ${e.message}"
            )
            false
        }
    }

    // WOL 唤醒

    suspend fun wakeAndReconnect(
        context: Context,
        mac: String,
        broadcast: String = "",
        timeoutSec: Int = 60
    ): Boolean {
        val ctx = context.applicationContext
        appContext = ctx

        val normalized = KnownPcStore.normalizeMac(mac)
        if (normalized.length != 12) {
            _lastError.value = "MAC 格式不正确：$mac"
            return false
        }

        val known = KnownPcStore.find(ctx, normalized)
        val host = known?.host?.takeIf { it.isNotBlank() }
            ?: currentHost.takeIf { it.isNotBlank() }
            ?: return false.also { _lastError.value = "不知道电脑 IP，请先连接一次或手动添加" }
        val port = (known?.port ?: 0).takeIf { it > 0 }
            ?: currentPort.takeIf { it > 0 }
            ?: BridgeConfig.DEFAULT_PORT

        val bcast = broadcast.takeIf { it.isNotBlank() }
            ?: deriveBroadcast(host)
            ?: "255.255.255.255"

        try {
            webSocket?.close(1000, "wol")
        } catch (e: Exception) {
            AgentLogger.log(
                "BRIDGE",
                "WOL 前关闭旧 socket 失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
        webSocket = null
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        connectionToken++
        pendingPings.set(0)
        _status.value = Status.DISCONNECTED
        pending.values.forEach { it.completeExceptionally(RuntimeException("WOL 重连")) }
        pending.clear()

        val err = WolHelper.wake(normalized, bcast, 9, ctx)
        if (err != null) {
            _lastError.value = err
            AgentLogger.log("BRIDGE", "WOL 发送失败：$err")
            return false
        }
        AgentLogger.log("BRIDGE", "已发送 WOL 到 $normalized @ $bcast:9，等待 $host:$port 上线…")

        manualDisconnect = false
        val settings = currentSettings
        if (settings != null) {
            connect(ctx, host, port, settings)
        }

        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (_status.value == Status.CONNECTED) {
                try {
                    KnownPcStore.touch(ctx, normalized, host, port)
                } catch (e: Exception) {
                    AgentLogger.log(
                        "BRIDGE",
                        "更新已知电脑时间失败（MAC=$normalized）：${e.javaClass.simpleName}: ${e.message}"
                    )
                }
                return true
            }
            delay(1000L.milliseconds)
        }
        return _status.value == Status.CONNECTED
    }

    private fun deriveBroadcast(host: String): String? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}.255"
    }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
}