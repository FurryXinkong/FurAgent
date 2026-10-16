package net.xinkong.furagent.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object MessageType {
    const val HELLO = "hello"
    const val WELCOME = "welcome"
    const val PING = "ping"
    const val PONG = "pong"
    const val LIST_TOOLS = "list_tools"
    const val TOOLS_LIST = "tools_list"
    const val TOOL_CALL = "tool_call"
    const val TOOL_RESULT = "tool_result"
    const val ERROR = "error"
    const val DEVICE_INFO = "device_info"
    const val REQUEST_DEVICE_INFO = "request_device_info"
    const val RELAY_FILE = "relay_file"
    const val RELAY_FILE_RESULT = "relay_file_result"
    const val LIST_PEERS = "list_peers"
    const val PEERS_LIST = "peers_list"
}

object EndpointId {
    const val PHONE = "phone"
    const val PC = "pc"
}

object BridgeConfig {
    const val DEFAULT_PORT = 8425

    /**
     * 【F9】心跳间隔 20s → 10s。
     *
     * 原因：部分路由器的 NAT 表对空闲 TCP 连接的超时在 30~60 秒。
     * 旧值 20s 会在弱网/空闲时被路由或运营商设备悄悄断掉，客户端表现为
     * onFailure(1006)。缩短到 10s 显著减少"莫名断线"。
     */
    const val HEARTBEAT_INTERVAL_MS = 10_000L

    const val RECONNECT_BASE_DELAY_MS = 1_000L
    const val RECONNECT_MAX_DELAY_MS = 30_000L
    const val HANDSHAKE_TIMEOUT_MS = 8_000L
    const val PROTOCOL_VERSION = "1.0"

    /**
     * 【F9】连续 N 次没收到 pong 才判定连接死。
     *
     * 20s×3 或 10s×5：超时窗口从 60s 略放宽到 50s，但探测频率翻倍，
     * 实际死连接检测更快，而弱网下误判断线更少。
     */
    const val MAX_PENDING_PINGS = 5L
}

@Serializable
data class BridgeMessage(
    val type: String,
    val id: String = "",
    val from: String = "",
    val timestamp: Long = 0L,
    val payload: Map<String, String> = emptyMap()
) {
    fun encodeToString(): String = Json.encodeToString(serializer(), this)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
        }

        fun decodeFromString(text: String): BridgeMessage? =
            try { json.decodeFromString(serializer(), text) } catch (_: Exception) { null }

        /**
         * 上线握手。携带 HTTP 通道信息 + 本机 MAC（WOL 用）。
         */
        fun helloExtended(
            from: String, version: String,
            deviceName: String, httpPort: Int, httpToken: String,
            mac: String = ""
        ): BridgeMessage = BridgeMessage(
            MessageType.HELLO, from = from, timestamp = currentTimeMillis(),
            payload = mapOf(
                "version" to version,
                "deviceName" to deviceName,
                "httpPort" to httpPort.toString(),
                "httpToken" to httpToken,
                "mac" to mac
            )
        )

        fun welcomeWithHttp(
            from: String, version: String,
            httpPort: Int, httpToken: String, deviceName: String = "",
            mac: String = ""
        ): BridgeMessage = BridgeMessage(
            MessageType.WELCOME, from = from, timestamp = currentTimeMillis(),
            payload = mapOf(
                "version" to version,
                "httpPort" to httpPort.toString(),
                "httpToken" to httpToken,
                "deviceName" to deviceName,
                "mac" to mac
            )
        )

        fun ping(from: String): BridgeMessage =
            BridgeMessage(MessageType.PING, from = from, timestamp = currentTimeMillis())

        fun pong(id: String, from: String): BridgeMessage =
            BridgeMessage(MessageType.PONG, id = id, from = from, timestamp = currentTimeMillis())

        fun listTools(id: String, from: String): BridgeMessage =
            BridgeMessage(MessageType.LIST_TOOLS, id = id, from = from, timestamp = currentTimeMillis())

        fun toolsList(id: String, from: String, toolsJson: String): BridgeMessage =
            BridgeMessage(MessageType.TOOLS_LIST, id = id, from = from,
                timestamp = currentTimeMillis(), payload = mapOf("tools" to toolsJson))

        fun toolCall(id: String, from: String, tool: String, argsJson: String): BridgeMessage =
            BridgeMessage(MessageType.TOOL_CALL, id = id, from = from,
                timestamp = currentTimeMillis(),
                payload = mapOf("tool" to tool, "args" to argsJson))

        fun toolResult(id: String, from: String, success: Boolean, result: String): BridgeMessage =
            BridgeMessage(MessageType.TOOL_RESULT, id = id, from = from,
                timestamp = currentTimeMillis(),
                payload = mapOf("success" to if (success) "true" else "false", "result" to result))

        fun error(from: String, message: String, refId: String = ""): BridgeMessage =
            BridgeMessage(MessageType.ERROR, id = refId, from = from,
                timestamp = currentTimeMillis(),
                payload = mapOf("message" to message))

        fun deviceInfo(from: String, httpPort: Int, httpToken: String): BridgeMessage =
            BridgeMessage(MessageType.DEVICE_INFO, from = from, timestamp = currentTimeMillis(),
                payload = mapOf("httpPort" to httpPort.toString(), "httpToken" to httpToken))

        fun requestDeviceInfo(id: String, from: String): BridgeMessage =
            BridgeMessage(MessageType.REQUEST_DEVICE_INFO, id = id, from = from,
                timestamp = currentTimeMillis())

        /**
         * 请求中转文件。
         * @param targetClientId 目标设备的 client_id
         * @param relayId 本次中转的唯一 ID
         * @param fileName 文件名
         * @param size 字节大小
         * @param relayPath 在桌面 HTTP 服务器上的相对路径（基于 receiveDir）
         * @param source 发起端显示名
         */
        fun relayFile(
            id: String, from: String,
            targetClientId: String,
            relayId: String,
            fileName: String,
            size: Long,
            relayPath: String,
            source: String = ""
        ): BridgeMessage = BridgeMessage(
            MessageType.RELAY_FILE, id = id, from = from, timestamp = currentTimeMillis(),
            payload = mapOf(
                "targetClientId" to targetClientId,
                "relayId" to relayId,
                "fileName" to fileName,
                "size" to size.toString(),
                "relayPath" to relayPath,
                "source" to source
            )
        )

        /**
         * 中转结果回执。
         * @param targetClientId 需要把结果转发给谁（发起端 client_id）
         */
        fun relayFileResult(
            id: String, from: String,
            targetClientId: String,
            relayId: String,
            success: Boolean,
            message: String
        ): BridgeMessage = BridgeMessage(
            MessageType.RELAY_FILE_RESULT, id = id, from = from, timestamp = currentTimeMillis(),
            payload = mapOf(
                "targetClientId" to targetClientId,
                "relayId" to relayId,
                "success" to if (success) "true" else "false",
                "message" to message
            )
        )

        /** 请求列出当前在线的所有设备。 */
        fun listPeers(id: String, from: String): BridgeMessage =
            BridgeMessage(MessageType.LIST_PEERS, id = id, from = from,
                timestamp = currentTimeMillis())

        /** 回复设备列表。peersJson 是 JSON 数组。 */
        fun peersList(id: String, from: String, peersJson: String): BridgeMessage =
            BridgeMessage(MessageType.PEERS_LIST, id = id, from = from,
                timestamp = currentTimeMillis(), payload = mapOf("peers" to peersJson))
    }
}

expect fun currentTimeMillis(): Long

@Serializable
data class BridgeToolInfo(
    val name: String,
    val displayName: String,
    val description: String = "",
    val paramsJson: String = ""
)

@Serializable
data class BridgeToolList(val tools: List<BridgeToolInfo>) {
    fun encodeToString(): String = Json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun decodeFromString(text: String): BridgeToolList? =
            try { json.decodeFromString(serializer(), text) } catch (_: Exception) { null }
    }
}