package net.xinkong.furagent.desktop.tools

import net.xinkong.furagent.shared.util.DateFormats
import kotlinx.coroutines.*
import net.xinkong.furagent.desktop.bridge.BridgeServer
import net.xinkong.furagent.desktop.core.DownloadManager
import net.xinkong.furagent.desktop.data.DesktopSettingsHolder
import net.xinkong.furagent.shared.util.FileNaming
import net.xinkong.furagent.shared.BridgeMessage
import net.xinkong.furagent.shared.BridgeToolList
import net.xinkong.furagent.shared.EndpointId
import net.xinkong.furagent.shared.tools.ToolResult
import net.xinkong.furagent.shared.util.formatFileSize
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

object PhoneTools {

    private const val MAX_FILE_BYTES = 10L * 1024 * 1024

    data class ClientHttpInfo(val port: Int, val token: String, val deviceName: String)
    private val clientHttpMap = ConcurrentHashMap<String, ClientHttpInfo>()

    /**
     * 【修复 7】共享 OkHttpClient，避免每次传文件都新建连接池和线程池。
     *
     * - upload：读写都无超时，大文件传输不打断
     * - short：读 60 秒，用于 delete 等短请求
     */
    private val sharedUploadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    private val sharedShortClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    fun registerClientHttp(clientId: String, port: Int, token: String, deviceName: String) {
        clientHttpMap[clientId] = ClientHttpInfo(port, token, deviceName)
    }

    fun getClientHttp(clientId: String): ClientHttpInfo? = clientHttpMap[clientId]

    fun removeClientHttp(clientId: String) {
        clientHttpMap.remove(clientId)
    }

    fun hasPhoneHttp(): Boolean = clientHttpMap.values.any { it.port > 0 && it.token.isNotBlank() }

    data class PhoneTool(
        val name: String, val displayName: String,
        val description: String, val paramsSchema: String
    )

    val TOOLS: List<PhoneTool> = listOf(
        PhoneTool(
            name = "phone_list_tools",
            displayName = "列出手机工具",
            description = "查询手机端 FurAgent 提供的工具列表。调用 phone_call 之前先调用它。" +
                    "可选 client_id 指定设备；不填时默认用第一台在线设备。" +
                    "多设备场景先用 phone_status 查所有在线设备的 client_id。" +
                    "手机端离线时 phone_* 工具会失败。",
            paramsSchema = """{"type":"object","properties":{"client_id":{"type":"string"}}}"""
        ),
        PhoneTool(
            name = "phone_call",
            displayName = "调用手机工具",
            description = "让手机端 FurAgent 执行一个工具并返回结果。参数必须是手机端真实存在的工具名。" +
                    "可选 client_id 指定设备；不填时默认用第一台在线设备。",
            paramsSchema = """{"type":"object","properties":{"tool":{"type":"string"},"args":{"type":"object"},"timeout":{"type":"integer"},"client_id":{"type":"string"}},"required":["tool"]}"""
        ),
        PhoneTool(
            name = "phone_status",
            displayName = "手机连接状态",
            description = "查看手机端连接状态、在线客户端列表（含 MAC）。" +
                    "返回的 client_id 可用于其它 phone_* 工具指定设备。",
            paramsSchema = """{"type":"object","properties":{}}"""
        ),
        PhoneTool(
            name = "phone_send_file",
            displayName = "发送文件到手机",
            description = "把电脑上的文件传送到手机。大文件自动走 HTTP 通道。" +
                    "可选 client_id 指定设备；不填时默认用第一台在线设备。",
            paramsSchema = """{"type":"object","properties":{"local_path":{"type":"string"},"remote_path":{"type":"string"},"client_id":{"type":"string"}},"required":["local_path"]}"""
        ),
        PhoneTool(
            name = "phone_receive_file",
            displayName = "从手机接收文件",
            description = "把手机上的文件拉取到电脑。大文件自动走 HTTP 通道（异步下载，不阻塞）。" +
                    "可选 client_id 指定设备；不填时默认用第一台在线设备。",
            paramsSchema = """{"type":"object","properties":{"remote_path":{"type":"string"},"local_path":{"type":"string"},"client_id":{"type":"string"}},"required":["remote_path"]}"""
        )
    )

    private val pending = ConcurrentHashMap<String, CompletableDeferred<BridgeMessage>>()
    fun onToolResult(msg: BridgeMessage) { pending.remove(msg.id)?.complete(msg) }

    private fun allPhoneClients(): List<BridgeServer.ClientInfo> =
        BridgeServer.clients.value.filter { it.endpoint == EndpointId.PHONE }

    private fun findPhoneClient(): String? = allPhoneClients().firstOrNull()?.id

    private fun resolveClientId(args: JSONObject): String? {
        val requested = args.optString("client_id", "").trim()
        if (requested.isBlank()) return findPhoneClient()
        val phones = allPhoneClients()
        phones.firstOrNull { it.id == requested }?.let { return it.id }
        phones.firstOrNull { it.id.startsWith(requested) }?.let { return it.id }
        return null
    }

    private fun findHostByClientId(clientId: String): String? {
        val remote = BridgeServer.clients.value.firstOrNull { it.id == clientId }?.remote ?: return null
        return remote.trimStart('/').substringBeforeLast(':').ifBlank { null }
    }

    private fun clientDisplayName(clientId: String): String {
        val c = BridgeServer.clients.value.firstOrNull { it.id == clientId }
        return c?.deviceName?.ifBlank { null } ?: c?.remote ?: clientId.take(8)
    }

    suspend fun execute(name: String, argsJson: String): ToolResult {
        return try {
            val args = if (argsJson.isBlank()) JSONObject() else JSONObject(argsJson)
            when (name) {
                "phone_status" -> phoneStatus()
                "phone_list_tools" -> phoneListTools(args)
                "phone_call" -> phoneCall(args)
                "phone_send_file" -> phoneSendFile(args)
                "phone_receive_file" -> phoneReceiveFile(args)
                else -> ToolResult.fail("未知手机工具：$name")
            }
        } catch (e: Exception) { ToolResult.fail("执行异常：${e.message}") }
    }

    suspend fun uploadFileToClient(
        clientId: String,
        localFile: File,
        remoteDir: String
    ): Pair<Boolean, String> {
        if (!localFile.exists() || !localFile.isFile) return false to "文件不存在：${localFile.absolutePath}"
        val info = clientHttpMap[clientId] ?: return false to "目标设备 HTTP 通道未就绪"
        val host = findHostByClientId(clientId) ?: return false to "目标设备 IP 未知"
        if (info.port <= 0 || info.token.isBlank()) return false to "HTTP 信息不完整"

        return try {
            val url = "http://$host:${info.port}/upload" +
                    "?token=${URLEncoder.encode(info.token, "UTF-8")}" +
                    "&path=${URLEncoder.encode(localFile.name, "UTF-8")}" +
                    "&dir=${URLEncoder.encode(remoteDir, "UTF-8")}"
            val client = sharedUploadClient
            val body = localFile.asRequestBody("application/octet-stream".toMediaType())
            withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url(url).post(body).build()).execute().use { resp ->
                    val text = resp.body.string()
                    if (resp.isSuccessful) {
                        true to "已上传：${localFile.name}（${formatFileSize(localFile.length())}）"
                    } else {
                        false to "HTTP ${resp.code}：$text"
                    }
                }
            }
        } catch (e: Exception) {
            false to "上传失败：${e.message}"
        }
    }

    suspend fun downloadFileFromClient(
        clientId: String,
        remotePath: String,
        localFile: File
    ): Pair<Boolean, String> {
        val info = clientHttpMap[clientId] ?: return false to "目标设备 HTTP 通道未就绪"
        val host = findHostByClientId(clientId) ?: return false to "目标设备 IP 未知"
        if (info.port <= 0 || info.token.isBlank()) return false to "HTTP 信息不完整"

        val url = "http://$host:${info.port}/download" +
                "?token=${URLEncoder.encode(info.token, "UTF-8")}" +
                "&path=${URLEncoder.encode(remotePath, "UTF-8")}"

        val taskId = DownloadManager.download(url, localFile.absolutePath)
        val result = waitForDownload(taskId, timeoutMs = 10 * 60 * 1000L)
        return result.success to result.output
    }

    suspend fun deleteFileOnClient(
        clientId: String,
        remotePath: String
    ): Pair<Boolean, String> {
        val info = clientHttpMap[clientId] ?: return false to "HTTP 通道未就绪"
        val host = findHostByClientId(clientId) ?: return false to "IP 未知"
        if (info.port <= 0 || info.token.isBlank()) return false to "HTTP 信息不完整"

        return try {
            val url = "http://$host:${info.port}/delete" +
                    "?token=${URLEncoder.encode(info.token, "UTF-8")}" +
                    "&path=${URLEncoder.encode(remotePath, "UTF-8")}"
            val client = sharedShortClient
            withContext(Dispatchers.IO) {
                val body = "".toRequestBody("text/plain".toMediaType())
                client.newCall(Request.Builder().url(url).post(body).build()).execute().use { resp ->
                    val text = resp.body.string()
                    if (resp.isSuccessful) true to "已移入手机回收站"
                    else false to "HTTP ${resp.code}：$text"
                }
            }
        } catch (e: Exception) {
            false to "删除失败：${e.message}"
        }
    }

    suspend fun callRemoteTool(clientId: String, tool: String, args: JSONObject, timeoutSec: Int = 180): ToolResult {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<BridgeMessage>()
        pending[id] = deferred
        return try {
            if (!BridgeServer.sendTo(clientId,
                    BridgeMessage.toolCall(id, EndpointId.PC, tool, args.toString()))) {
                pending.remove(id)
                return ToolResult.fail("发送请求失败")
            }
            val reply = withTimeoutOrNull((timeoutSec * 1000L).milliseconds) { deferred.await() }
            pending.remove(id)
            if (reply == null) return ToolResult.fail("等待响应超时（${timeoutSec}秒）")
            val success = reply.payload["success"] == "true"
            val result = reply.payload["result"] ?: ""
            if (success) ToolResult.ok(result) else ToolResult.fail(result)
        } catch (e: Exception) {
            pending.remove(id)
            ToolResult.fail("失败：${e.message}")
        }
    }

    private fun phoneStatus(): ToolResult {
        val phoneClients = allPhoneClients()
        val sb = StringBuilder()
        sb.append("手机端在线客户端数：${phoneClients.size}\n")
        sb.append("HTTP 通道：${if (hasPhoneHttp()) "至少一台已就绪" else "均未就绪"}\n\n")
        if (phoneClients.isEmpty()) {
            sb.append("(手机端未连接)")
        } else {
            sb.append("在线设备列表：\n\n")
            phoneClients.forEachIndexed { i, c ->
                val http = clientHttpMap[c.id]
                val httpText = if (http != null && http.port > 0 && http.token.isNotBlank())
                    "HTTP 就绪（端口 ${http.port}）"
                else "HTTP 未就绪"
                sb.append("${i + 1}. 名称：${c.deviceName.ifBlank { "未知" }}\n")
                sb.append("   client_id：${c.id}\n")
                sb.append("   地址：${c.remote.trimStart('/')}\n")
                if (c.mac.isNotBlank()) sb.append("   MAC：${c.mac}\n")
                sb.append("   连接时间：")
                    .append(
                        DateFormats.timeFormatter()
                        .format(Date(c.connectedAt)))
                    .append("\n")
                sb.append("   HTTP 通道：$httpText\n\n")
            }
            sb.append("调用其它 phone_* 工具时，用参数 client_id=<上面的 id> 指定设备；不填则默认用第一台。")
        }
        return ToolResult.ok(sb.toString().trim())
    }

    private suspend fun phoneListTools(args: JSONObject): ToolResult {
        val clientId = resolveClientId(args)
            ?: return ToolResult.fail(
                "未找到目标手机（可能未连接，或 client_id 无效）。先用 phone_status 查看在线设备。"
            )

        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<BridgeMessage>()
        pending[id] = deferred
        return try {
            if (!BridgeServer.sendTo(clientId, BridgeMessage.listTools(id, EndpointId.PC))) {
                pending.remove(id); return ToolResult.fail("发送请求失败")
            }
            val reply = withTimeoutOrNull(10_000L.milliseconds) { deferred.await() }
            pending.remove(id)
            if (reply == null) return ToolResult.fail("手机端未响应（10秒超时）")
            val toolsJson = reply.payload["tools"] ?: ""
            if (toolsJson.isBlank()) return ToolResult.ok("手机端没有可用工具")
            val list = BridgeToolList.decodeFromString(toolsJson)
            if (list == null || list.tools.isEmpty()) return ToolResult.ok("手机端没有可用工具")
            val sb = StringBuilder("来自「${clientDisplayName(clientId)}」的 ${list.tools.size} 个工具：\n\n")
            list.tools.forEach { t ->
                sb.append("- ${t.name}")
                if (t.displayName.isNotBlank() && t.displayName != t.name) sb.append("（${t.displayName}）")
                sb.append("\n")
                if (t.description.isNotBlank()) sb.append("  说明：${t.description}\n")
            }
            sb.append("\n用 phone_call 调用：tool=工具名，args={参数}，client_id=${clientId.take(8)}")
            ToolResult.ok(sb.toString())
        } catch (e: Exception) {
            pending.remove(id); ToolResult.fail("失败：${e.message}")
        }
    }

    private suspend fun phoneCall(args: JSONObject): ToolResult {
        val tool = args.optString("tool", "").trim()
        if (tool.isBlank()) return ToolResult.fail("未提供 tool 参数")
        val toolArgs = args.optJSONObject("args") ?: JSONObject()
        val timeoutSec = args.optInt("timeout", 120).coerceIn(5, 600)
        val clientId = resolveClientId(args)
            ?: return ToolResult.fail(
                "未找到目标手机（可能未连接，或 client_id 无效）。先用 phone_status 查看在线设备。"
            )
        return callRemoteTool(clientId, tool, toolArgs, timeoutSec)
    }

    private suspend fun phoneSendFile(args: JSONObject): ToolResult {
        val localPath = args.optString("local_path", "").trim()
        if (localPath.isBlank()) return ToolResult.fail("未提供 local_path")
        val fileNameHint = FileNaming.extractFileName(args.optString("remote_path", ""))
        val localFile = File(localPath)
        if (!localFile.exists()) return ToolResult.fail("本地文件不存在：$localPath")
        if (localFile.isDirectory) return ToolResult.fail("这是目录而非文件：$localPath")

        val clientId = resolveClientId(args)
            ?: return ToolResult.fail(
                "未找到目标手机（可能未连接，或 client_id 无效）。先用 phone_status 查看在线设备。"
            )

        val httpInfo = clientHttpMap[clientId]
        if (httpInfo != null && httpInfo.port > 0 && httpInfo.token.isNotBlank()) {
            val r = uploadViaPhoneHttp(clientId, localFile, fileNameHint)
            if (r != null) return r
        }

        if (localFile.length() > MAX_FILE_BYTES) {
            return ToolResult.fail(
                "文件过大（${formatFileSize(localFile.length())}）。该设备 HTTP 通道未就绪。"
            )
        }
        val base64 = try {
            Base64.getEncoder().encodeToString(localFile.readBytes())
        } catch (e: Exception) { return ToolResult.fail("读取本地文件失败：${e.message}") }

        val result = callRemoteTool(clientId, "write_file", JSONObject().apply {
            put("path", fileNameHint)
            put("mode", "base64")
            put("data", base64)
        }, 60)

        return if (result.success)
            ToolResult.ok("已发送到「${clientDisplayName(clientId)}」：$localPath（${formatFileSize(localFile.length())}）")
        else ToolResult.fail("发送失败：${result.output}")
    }

    private fun uploadViaPhoneHttp(
        clientId: String,
        localFile: File,
        fileNameHint: String
    ): ToolResult? {
        val info = clientHttpMap[clientId] ?: return null
        val host = findHostByClientId(clientId) ?: return null
        if (info.port <= 0 || info.token.isBlank()) return null
        return try {
            val url = "http://$host:${info.port}/upload" +
                    "?token=${URLEncoder.encode(info.token, "UTF-8")}" +
                    "&path=${URLEncoder.encode(fileNameHint, "UTF-8")}"
            val client = sharedUploadClient
            val body = localFile.asRequestBody("application/octet-stream".toMediaType())
            client.newCall(Request.Builder().url(url).post(body).build()).execute().use { resp ->
                val text = resp.body.string()
                if (resp.isSuccessful) {
                    ToolResult.ok("已发送到「${clientDisplayName(clientId)}」：${localFile.name}（${formatFileSize(localFile.length())}）")
                } else ToolResult.fail("HTTP ${resp.code}：$text")
            }
        } catch (e: Exception) {
            // 【修复 C5】println → DesktopLogger
            net.xinkong.furagent.desktop.system.DesktopLogger.warn(
                "PhoneTools",
                "HTTP 上传失败：${e.javaClass.simpleName}: ${e.message}"
            )
            null
        }
    }

    private suspend fun phoneReceiveFile(args: JSONObject): ToolResult {
        val remotePath = args.optString("remote_path", "").trim()
        if (remotePath.isBlank()) return ToolResult.fail("未提供 remote_path")

        val clientId = resolveClientId(args)
            ?: return ToolResult.fail(
                "未找到目标手机（可能未连接，或 client_id 无效）。先用 phone_status 查看在线设备。"
            )

        val httpInfo = clientHttpMap[clientId]
        if (httpInfo != null && httpInfo.port > 0 && httpInfo.token.isNotBlank()) {
            val host = findHostByClientId(clientId)
            if (host != null) {
                val url = "http://$host:${httpInfo.port}/download" +
                        "?token=${URLEncoder.encode(httpInfo.token, "UTF-8")}" +
                        "&path=${URLEncoder.encode(remotePath, "UTF-8")}"
                val fileName = FileNaming.extractFileName(remotePath)
                val target = FileNaming.resolveReceiveFile(DesktopSettingsHolder.current.receiveDir, fileName)
                val taskId = DownloadManager.download(url, target.absolutePath)
                return waitForDownload(taskId, timeoutMs = 5 * 60 * 1000L)
            }
        }

        val readResult = callRemoteTool(clientId, "read_file", JSONObject().apply {
            put("path", remotePath)
            put("mode", "base64")
        }, 60)
        if (!readResult.success) return ToolResult.fail("读取手机文件失败：${readResult.output}")
        val base64 = readResult.output.trim()
        if (base64.isBlank()) return ToolResult.fail("手机返回空数据")
        return try {
            val bytes = Base64.getDecoder().decode(base64)
            if (bytes.size > MAX_FILE_BYTES) return ToolResult.fail("文件过大（${formatFileSize(bytes.size.toLong())}）")
            val fileName = FileNaming.extractFileName(remotePath)
            val target = FileNaming.resolveReceiveFile(DesktopSettingsHolder.current.receiveDir, fileName)
            target.writeBytes(bytes)
            ToolResult.ok("已接收到：${target.absolutePath}（${formatFileSize(bytes.size.toLong())}）")
        } catch (e: Exception) { ToolResult.fail("写入本地文件失败：${e.message}") }
    }

    private suspend fun waitForDownload(taskId: String, timeoutMs: Long): ToolResult {
        val start = System.currentTimeMillis()
        while (true) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                return ToolResult.fail("下载超时")
            }
            val task = DownloadManager.tasks.value.firstOrNull { it.id == taskId }
                ?: return ToolResult.fail("下载任务丢失")
            when (task.status) {
                DownloadManager.Status.COMPLETED ->
                    return ToolResult.ok("已接收到：${task.savePath}（${formatFileSize(task.totalBytes)}）")
                DownloadManager.Status.FAILED ->
                    return ToolResult.fail("下载失败：${task.error ?: "未知错误"}")
                DownloadManager.Status.CANCELLED ->
                    return ToolResult.fail("下载已取消")
                else -> delay(400.milliseconds)
            }
        }
    }
}