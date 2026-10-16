package net.xinkong.furagent.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.core.AgentLogger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.*

/**
 * Wake-on-LAN。
 *
 * 【本版关键修复】
 *  1. DatagramSocket 必须用 DatagramSocket(null) 创建（未绑定），否则
 *     DatagramSocket() 无参构造会隐式 bind 到通配地址，之后 network.bindSocket
 *     会抛 IllegalStateException: Socket is already bound。
 *     原来 catch 掉异常静默降级，导致"绑定 WiFi"从未生效。
 *  2. 显式绑定 WiFi 网络 —— 避免多网络下 UDP 从移动数据出去
 *  3. 同时发 255.255.255.255 和子网广播 —— 兼容会拦全局广播的路由器
 *  4. 每个组合连发 3 次 + 间隔 50ms —— UDP 不可靠
 *  5. 端口 9 和 7 都发 —— 有些设备只监听 7
 */
object WolHelper {

    suspend fun wake(
        mac: String,
        broadcast: String = "",
        port: Int = 9,
        context: Context? = null
    ): String? = withContext(Dispatchers.IO) {
        val macBytes = parseMac(mac) ?: return@withContext "MAC 格式不正确：$mac"
        if (port !in 1..65535) return@withContext "端口不合法：$port"

        // 构造 Magic Packet
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (i in 0 until 16) {
            System.arraycopy(macBytes, 0, packet, 6 + i * 6, 6)
        }

        val broadcasts = if (broadcast.isNotBlank()) {
            listOf(broadcast)
        } else {
            val detected = detectSubnetBroadcast(context)
            if (detected != null) {
                listOf(detected, "255.255.255.255")   // 子网优先
            } else {
                listOf("255.255.255.255")
            }
        }

        val ports = if (port == 7) listOf(7) else listOf(port, 7)

        var lastError: String? = null
        var anySent = false

        try {
            // 【修复 A4】
            // DatagramSocket(null) 创建未绑定 socket。
            // 无参 DatagramSocket() 会隐式 bind，之后 bindSocket 会抛异常。
            val socket = DatagramSocket(null)
            try {
                socket.broadcast = true
                try { socket.reuseAddress = true } catch (_: Exception) { }

                // 【关键修复】在 bind 之前绑定 WiFi 网络
                if (context != null) {
                    try {
                        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                                as ConnectivityManager
                        val wifiNetwork = cm.allNetworks.firstOrNull { network ->
                            val caps = cm.getNetworkCapabilities(network)
                            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                        }
                        if (wifiNetwork != null) {
                            wifiNetwork.bindSocket(socket)
                            AgentLogger.log("BRIDGE", "WOL socket 已绑定到 WiFi 网络")
                        } else {
                            AgentLogger.log("BRIDGE", "WOL 未找到 WiFi 网络，使用默认路由")
                        }
                    } catch (e: Exception) {
                        AgentLogger.log(
                            "BRIDGE",
                            "WOL 绑定 WiFi 失败（继续用默认路由）：${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                }

                // 显式 bind 到通配地址 + 随机端口
                socket.bind(InetSocketAddress(0))

                for (bcast in broadcasts) {
                    val addr = try {
                        InetAddress.getByName(bcast)
                    } catch (e: Exception) {
                        AgentLogger.log("BRIDGE", "WOL 广播地址解析失败：$bcast - ${e.message}")
                        continue
                    }
                    for (p in ports) {
                        repeat(3) { idx ->
                            try {
                                val pkt = DatagramPacket(packet, packet.size, addr, p)
                                socket.send(pkt)
                                anySent = true
                                if (idx == 0) {
                                    AgentLogger.log("BRIDGE", "WOL 发送：$bcast:$p")
                                }
                            } catch (e: Exception) {
                                lastError = "${e.javaClass.simpleName}: ${e.message}"
                                AgentLogger.log(
                                    "BRIDGE",
                                    "WOL 发送失败 $bcast:$p - $lastError"
                                )
                            }
                            if (idx < 2) {
                                try { Thread.sleep(50) } catch (_: InterruptedException) { }
                            }
                        }
                    }
                }
            } finally {
                try { socket.close() } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            AgentLogger.log(
                "BRIDGE",
                "WOL socket 创建失败：${e.javaClass.simpleName}: ${e.message}"
            )
            return@withContext "发送失败：${e.message}"
        }

        if (anySent) null
        else "发送失败：所有广播地址和端口都失败了（最后错误：$lastError）"
    }

    private fun detectSubnetBroadcast(context: Context?): String? {
        if (context == null) return null
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as ConnectivityManager
            val wifiNetwork = cm.allNetworks.firstOrNull { network ->
                val caps = cm.getNetworkCapabilities(network)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: return null

            val linkProps = cm.getLinkProperties(wifiNetwork) ?: return null
            for (addr in linkProps.linkAddresses) {
                val ip = addr.address
                val prefix = addr.prefixLength
                if (ip is java.net.Inet4Address && prefix in 1..31) {
                    val bytes = ip.address
                    val mask = (-1 shl (32 - prefix))
                    val broadcastInt = bytes.fold(0) { acc, b ->
                        (acc shl 8) or (b.toInt() and 0xFF)
                    } or mask.inv()
                    val b1 = (broadcastInt ushr 24) and 0xFF
                    val b2 = (broadcastInt ushr 16) and 0xFF
                    val b3 = (broadcastInt ushr 8) and 0xFF
                    val b4 = broadcastInt and 0xFF
                    return "$b1.$b2.$b3.$b4"
                }
            }
            null
        } catch (e: Exception) {
            AgentLogger.log("BRIDGE", "探测子网广播失败：${e.message}")
            null
        }
    }

    private fun parseMac(raw: String): ByteArray? {
        val hex = raw.replace(Regex("[^0-9A-Fa-f]"), "")
        if (hex.length != 12) return null
        return try {
            ByteArray(6) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: Exception) { null }
    }

    fun normalizeMac(raw: String): String =
        raw.replace(Regex("[^0-9A-Fa-f]"), "").uppercase(Locale.ROOT)

    fun formatMac(raw: String): String {
        val n = normalizeMac(raw)
        if (n.length != 12) return raw
        return n.chunked(2).joinToString(":")
    }
}