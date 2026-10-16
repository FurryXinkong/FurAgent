package net.xinkong.furagent.tools

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.bridge.PcBridgeClient
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.data.AppSettings
import net.xinkong.furagent.data.KnownPcStore
import net.xinkong.furagent.data.extractFileName
import net.xinkong.furagent.shared.util.formatFileSize
import org.json.JSONObject
import java.io.File

/**
 * 让手机端 AI 能够访问电脑端能力的工具集。
 *
 * 文件传输安全模型：
 *  - 电脑推文件给手机：手机端 PcBridgeClient 拦截 write_file，强制落 pcReceiveDir
 *  - 手机推文件给电脑：电脑端 BridgeServer 拦截 write_file，强制落 receiveDir
 *  - 手机→手机：通过电脑中转（relay）
 */
object RemoteTools {

    private const val MAX_TRANSFER_BYTES = 10L * 1024 * 1024

    val TOOLS = setOf("pc")

    suspend fun execute(
        name: String, args: JSONObject, context: Context, settings: AppSettings
    ): String = when (name) {
        "pc" -> doPc(args, settings, context)
        else -> "未实现的远程工具：$name"
    }

    private suspend fun doPc(
        args: JSONObject, settings: AppSettings, context: Context
    ): String {
        val op = args.optString("op", "").lowercase()
        return when (op) {
            "status" -> doPcStatus()
            "list_tools" -> doPcListTools()
            "call" -> doPcCall(args)
            "send_file" -> doPcSendFile(args, settings)
            "receive_file" -> doPcReceiveFile(args, settings)
            "wake" -> doPcWake(args, context)
            "known" -> doPcKnown(context)
            "relay" -> doPcRelay(args, settings)
            "peers" -> doPcPeers()
            "relay_status" -> doPcRelayStatus(args)
            else -> "失败：op 只能是 status / list_tools / call / send_file / receive_file / wake / known / relay / peers / relay_status"
        }
    }

    private fun doPcStatus(): String {
        val status = PcBridgeClient.status.value
        val err = PcBridgeClient.lastError.value
        return buildString {
            append("电脑桥接状态：")
            append(
                when (status) {
                    PcBridgeClient.Status.CONNECTED -> "已连接"
                    PcBridgeClient.Status.CONNECTING -> "连接中"
                    PcBridgeClient.Status.DISCONNECTED -> "未连接"
                }
            )
            append("\n")
            val mac = PcBridgeClient.getPcMac()
            if (mac.isNotBlank()) append("电脑 MAC：$mac\n")
            val deviceName = PcBridgeClient.pcDeviceName.value
            if (deviceName.isNotBlank()) append("电脑名称：$deviceName\n")
            val localMac = PcBridgeClient.getLocalMac()
            if (localMac.isNotBlank()) append("本机 MAC：$localMac\n")
            if (err.isNotBlank() && status != PcBridgeClient.Status.CONNECTED) {
                append("最近错误：$err\n")
            }
            append("远端工具数：${PcBridgeClient.remoteTools.value.size}")
        }
    }

    private suspend fun doPcListTools(): String {
        if (!PcBridgeClient.isConnected()) {
            return "失败：未连接到电脑。请确认电脑端 FurAgent Desktop 已启动，" +
                    "并且在手机端设置里填写了电脑 IP 和端口。"
        }
        PcBridgeClient.refreshRemoteTools()
        val list = PcBridgeClient.remoteTools.value
        if (list.isEmpty()) {
            return "电脑端暂时没有可用工具。"
        }
        val sb = StringBuilder("电脑端共 ${list.size} 个工具：\n\n")
        list.forEach { t ->
            sb.append("- ${t.name}")
            if (t.displayName.isNotBlank() && t.displayName != t.name) {
                sb.append("（${t.displayName}）")
            }
            sb.append("\n")
            if (t.description.isNotBlank()) {
                sb.append("  说明：${t.description}\n")
            }
            if (t.paramsJson.isNotBlank()) {
                sb.append("  参数：${t.paramsJson.take(200)}\n")
            }
        }
        sb.append("\n用 op=call 调用：tool=工具名，args={参数对象}")
        return sb.toString()
    }

    private suspend fun doPcCall(args: JSONObject): String {
        val tool = args.optString("tool", "").trim()
        if (tool.isBlank()) return "失败：未提供 tool 参数"

        val toolArgs = args.optJSONObject("args") ?: JSONObject()
        val timeoutSec = args.optInt("timeout", 120).coerceIn(5, 600)

        if (!PcBridgeClient.isConnected()) {
            return "失败：未连接到电脑"
        }

        val remoteList = PcBridgeClient.remoteTools.value
        if (remoteList.isNotEmpty() && remoteList.none { it.name == tool }) {
            return "失败：电脑端没有工具 $tool。先调用 op=list_tools 查看可用工具。"
        }

        return PcBridgeClient.callRemoteTool(tool, toolArgs, timeoutSec)
    }

    private suspend fun doPcSendFile(args: JSONObject, settings: AppSettings): String {
        val localPathRaw = args.optString("local_path", "").trim()
        if (localPathRaw.isBlank()) return "失败：未提供 local_path"

        val localPath = normalizeAlias(localPathRaw)
        PathGuard.check(localPath, settings)?.let { return it }

        val file = File(localPath)
        if (!file.exists()) return "失败：本地文件不存在 $localPath"
        if (file.isDirectory) return "失败：这是目录而非文件 $localPath"
        if (file.length() > MAX_TRANSFER_BYTES) {
            return "失败：文件过大（${formatFileSize(file.length())}），上限 ${formatFileSize(MAX_TRANSFER_BYTES)}"
        }

        if (!PcBridgeClient.isConnected()) return "失败：未连接到电脑"

        return withContext(Dispatchers.IO) {
            try {
                val bytes = file.readBytes()
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val callArgs = JSONObject().apply {
                    put("tool", "write_file")
                    put("args", JSONObject().apply {
                        put("path", file.name)
                        put("mode", "base64")
                        put("data", base64)
                    })
                    put("timeout", 60)
                }
                val result = doPcCall(callArgs)
                if (result.startsWith("失败")) result
                else "已发送到电脑：$localPath（${formatFileSize(bytes.size.toLong())}）"
            } catch (e: Exception) {
                AgentLogger.log(
                    "ERR",
                    "发送文件到电脑失败（$localPath）：${e.javaClass.simpleName}: ${e.message}"
                )
                "失败：读取本地文件失败：${e.message}"
            }
        }
    }

    private suspend fun doPcReceiveFile(args: JSONObject, settings: AppSettings): String {
        val remotePath = args.optString("remote_path", "").trim()
        if (remotePath.isBlank()) return "失败：未提供 remote_path"

        if (!PcBridgeClient.isConnected()) return "失败：未连接到电脑"

        return withContext(Dispatchers.IO) {
            try {
                val callArgs = JSONObject().apply {
                    put("tool", "read_file")
                    put("args", JSONObject().apply {
                        put("path", remotePath)
                        put("mode", "base64")
                    })
                    put("timeout", 60)
                }
                val result = doPcCall(callArgs)
                if (result.startsWith("失败")) {
                    return@withContext result
                }
                val base64 = result.trim()
                if (base64.isBlank()) {
                    return@withContext "失败：电脑返回空数据"
                }
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                if (bytes.size > MAX_TRANSFER_BYTES) {
                    return@withContext "失败：数据过大（${formatFileSize(bytes.size.toLong())}）"
                }

                val fileName = extractFileName(remotePath)
                val target = net.xinkong.furagent.data.resolveReceiveFile(
                    settings.pcReceiveDir, fileName
                )
                target.writeBytes(bytes)
                "已接收到：${target.absolutePath}（${formatFileSize(bytes.size.toLong())}）"
            } catch (e: Exception) {
                AgentLogger.log(
                    "ERR",
                    "接收电脑文件失败（$remotePath）：${e.javaClass.simpleName}: ${e.message}"
                )
                "失败：写入本地文件失败：${e.message}"
            }
        }
    }

    // ============================================================
    // relay（手机 → 电脑中转 → 手机）
    // ============================================================

    private suspend fun doPcRelay(args: JSONObject, settings: AppSettings): String {
        val target = args.optString("target", "").trim()
        val localPathRaw = args.optString("local_path", "").trim()

        if (target.isBlank()) return "失败：未提供 target（目标设备的 client_id）"
        if (localPathRaw.isBlank()) return "失败：未提供 local_path"

        val localPath = normalizeAlias(localPathRaw)
        PathGuard.check(localPath, settings)?.let { return it }

        val file = File(localPath)
        if (!file.exists()) return "失败：本地文件不存在 $localPath"
        if (file.isDirectory) return "失败：这是目录而非文件"

        if (!PcBridgeClient.isConnected()) return "失败：未连接到电脑"

        return try {
            val relayId = PcBridgeClient.sendRelayFile(target, file)
            "已发起中转（relayId=$relayId），文件：$localPath（${formatFileSize(file.length())}）\n" +
                    "用 op=relay_status relay_id=$relayId 查询结果"
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "发起 relay 失败：${e.javaClass.simpleName}: ${e.message}"
            )
            "失败：${e.message}"
        }
    }

    private fun doPcRelayStatus(args: JSONObject): String {
        val relayId = args.optString("relay_id", "").trim()
        if (relayId.isBlank()) return "失败：未提供 relay_id"
        val status = PcBridgeClient.getRelayStatus(relayId)
            ?: return "等待中：relayId=$relayId 还没有收到对方回执"
        return if (status.first) {
            "已完成：${status.second}"
        } else {
            "失败：${status.second}"
        }
    }

    // ============================================================
    // peers（列出所有在线设备）
    // ============================================================

    private suspend fun doPcPeers(): String {
        if (!PcBridgeClient.isConnected()) {
            return "失败：未连接到电脑。请先在设置里连接电脑。"
        }
        val peers = PcBridgeClient.listPeers()
        if (peers.isEmpty()) {
            return "当前没有其他在线设备（只有本机和电脑）。"
        }
        val localName = PcBridgeClient.getLocalDeviceName()
        val sb = StringBuilder("通过电脑桥接看到的在线设备（${peers.size} 个）：\n\n")
        peers.forEachIndexed { i, p ->
            sb.append("${i + 1}. ${p.deviceName.ifBlank { "未知设备" }}")
            if (p.endpoint == "pc") sb.append(" [电脑]")
            sb.append("\n")
            sb.append("   client_id：${p.id}\n")
            sb.append("   端点类型：${p.endpoint}\n")
            if (p.mac.isNotBlank()) sb.append("   MAC：${p.mac}\n")
            sb.append("   远端：${p.remote}\n\n")
        }
        sb.append("用 op=relay 把文件转发给指定 client_id 的设备。\n")
        sb.append("本机名称：$localName")
        return sb.toString()
    }

    // ============================================================
    // WOL
    // ============================================================

    private suspend fun doPcWake(args: JSONObject, context: Context): String {
        val macRaw = args.optString("mac", "").trim()
        if (macRaw.isBlank()) {
            val current = PcBridgeClient.getPcMac()
            if (current.isBlank()) {
                return "失败：未提供 mac，且当前没有已连接的电脑。可以先用 op=known 查看已知电脑。"
            }
            return wakeByMac(context, current, args)
        }
        val normalized = KnownPcStore.normalizeMac(macRaw)
        if (normalized.length != 12) return "失败：MAC 格式不正确：$macRaw"
        return wakeByMac(context, normalized, args)
    }

    private suspend fun wakeByMac(
        context: Context, mac: String, args: JSONObject
    ): String {
        val broadcast = args.optString("broadcast", "").trim()
        val timeoutSec = args.optInt("timeout", 60).coerceIn(10, 300)

        val ok = PcBridgeClient.wakeAndReconnect(
            context = context,
            mac = mac,
            broadcast = broadcast,
            timeoutSec = timeoutSec
        )
        return if (ok) {
            "已唤醒电脑并成功连接（MAC=$mac）"
        } else {
            "已发送 WOL 魔术封包到 $mac，但 $timeoutSec 秒内没连上。请检查：\n" +
                    "1. 电脑 BIOS 里 WOL 是否开启\n" +
                    "2. 网卡属性里\"魔术封包唤醒\"是否勾选\n" +
                    "3. Windows 快速启动是否已关闭（这是最容易漏的一条）\n" +
                    "4. 电脑必须是有线网卡（Wi-Fi 多数不支持 WOL）"
        }
    }

    private fun doPcKnown(context: Context): String {
        val list = KnownPcStore.list(context)
        if (list.isEmpty()) {
            return "暂无已知电脑。连接一次电脑后会自动记录 MAC。"
        }
        val onlineMac = KnownPcStore.normalizeMac(PcBridgeClient.getPcMac())
        val connected = PcBridgeClient.isConnected()

        val sb = StringBuilder("已知电脑 ${list.size} 台：\n\n")
        list.forEachIndexed { i, p ->
            val online = connected && p.mac == onlineMac
            sb.append("${i + 1}. ${p.deviceName.ifBlank { "未知设备" }} ")
            sb.append(if (online) "[在线]" else "[离线]")
            sb.append("\n")
            sb.append("   MAC：${p.displayMac}\n")
            if (p.host.isNotBlank()) {
                sb.append("   上次 IP：${p.host}:${p.port}\n")
            }
            sb.append("   上次在线：${formatAgo(System.currentTimeMillis() - p.lastSeenAt)}\n\n")
        }
        sb.append("唤醒命令：op=wake, mac=<上面的 MAC>")
        return sb.toString().trim()
    }

    private fun formatAgo(ms: Long): String {
        if (ms < 0) return "刚刚"
        val sec = ms / 1000
        return when {
            sec < 60 -> "${sec}秒"
            sec < 3600 -> "${sec / 60}分钟"
            sec < 86400 -> "${sec / 3600}小时"
            else -> "${sec / 86400}天"
        } + "前"
    }
}