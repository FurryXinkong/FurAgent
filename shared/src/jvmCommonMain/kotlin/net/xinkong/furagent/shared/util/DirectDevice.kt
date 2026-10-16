package net.xinkong.furagent.shared.util

import java.util.*

/**
 * 直连设备（不经过电脑中转，两台设备直接 HTTP 传文件）。
 *
 * 连接串格式：
 *   host:port:token           完整连接串，直接可用
 *   host:port:token:name      name 可选，只作为显示名
 *   host:port:code            code 是短码（4~8 位），需要先调 /pair 换 token
 *   host:code                 省略端口（默认 8426），同上
 *
 * 也支持粘贴浏览器 URL：http://host:port/?token=xxx
 *
 * 数据类放 shared，两端各自的 DirectDeviceStore 只需负责持久化。
 */
data class DirectDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val token: String,
    val lastSeenAt: Long = 0L
)

object DirectDeviceCodec {

    /** 直连服务的默认端口。同 AndroidHttpFileServer / HttpFileServer 的默认 HTTP 端口 */
    const val DEFAULT_PORT = 8426

    /**
     * 解析连接串。
     *
     * 支持：
     *   "192.168.1.100:8426:abc123def..."          完整连接串
     *   "192.168.1.100:8426:abc123def...:我的手机"  带名字
     *   "http://192.168.1.100:8426/?token=abc123"  浏览器 URL
     *   "192.168.1.100:8426:A3F7C1"                短码（需调 /pair）
     *   "192.168.1.100:A3F7C1"                     短码 + 省略端口
     *
     * 注意：本方法**不做网络调用**。若第三段是短码，返回的 DirectDevice 里
     * `token` 字段就是短码本身。调用方应该先 `if (isShortCode(raw))` 判断，
     * 是的话再调 DirectTransfer.pair() 换取真 token，然后重新构造 DirectDevice。
     */
    fun parseConnectionString(raw: String, defaultName: String = ""): DirectDevice? {
        val t = raw.trim()
        if (t.isBlank()) return null

        if (t.startsWith("http://") || t.startsWith("https://")) {
            return try {
                val uri = java.net.URI(t)
                val host = uri.host ?: return null
                val port = if (uri.port > 0) uri.port else DEFAULT_PORT
                val token = uri.query?.split("&")
                    ?.firstOrNull { it.startsWith("token=") }
                    ?.substringAfter("token=")
                    ?: return null
                DirectDevice(
                    id = UUID.randomUUID().toString(),
                    name = defaultName.ifBlank { host },
                    host = host,
                    port = port,
                    token = token
                )
            } catch (_: Exception) { null }
        }

        val parts = t.split(":").map { it.trim() }
        if (parts.isEmpty()) return null

        // 情况 1：host:code （两段式）
        if (parts.size == 2) {
            val host = parts[0]
            val second = parts[1]
            // 第二段是纯数字 → 视为 host:port，缺少 code（拒绝）
            // 第二段是短码 → host:code
            if (second.toIntOrNull() != null) return null
            if (host.isBlank() || !isShortCodeString(second)) return null
            return DirectDevice(
                id = UUID.randomUUID().toString(),
                name = defaultName.ifBlank { host },
                host = host,
                port = DEFAULT_PORT,
                token = second    // 存短码，等调用方换
            )
        }

        // 情况 3+：host:port:token[:name]
        if (parts.size < 3) return null
        val host = parts[0]
        val port = parts[1].toIntOrNull() ?: return null
        val token = parts[2]
        val name = if (parts.size >= 4) {
            parts.subList(3, parts.size).joinToString(":")
        } else ""
        if (host.isBlank() || port <= 0 || token.isBlank()) return null

        return DirectDevice(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { defaultName.ifBlank { host } },
            host = host,
            port = port,
            token = token
        )
    }

    /**
     * 输入的连接串是否是"短码格式"（需要先 /pair 换取真 token）。
     *
     * 判据：
     *   - 不是 http(s) URL
     *   - 第三段（或两段式的第二段）长度 4~8，且全为字母数字
     *   - 完整 token 是 32 位 hex，必然 ≥ 16 位，不会误判
     */
    fun isShortCode(raw: String): Boolean {
        val t = raw.trim()
        if (t.isBlank()) return false
        if (t.startsWith("http://") || t.startsWith("https://")) return false
        val parts = t.split(":").map { it.trim() }
        return when {
            parts.size == 2 -> isShortCodeString(parts[1])
            parts.size >= 3 -> isShortCodeString(parts[2])
            else -> false
        }
    }

    private fun isShortCodeString(s: String): Boolean {
        if (s.length !in 4..8) return false
        return s.all { it.isLetterOrDigit() }
    }

    /** 生成连接串（用于本机显示给用户复制）。 */
    fun buildConnectionString(host: String, port: Int, token: String, name: String = ""): String {
        return if (name.isBlank()) "$host:$port:$token" else "$host:$port:$token:$name"
    }
}