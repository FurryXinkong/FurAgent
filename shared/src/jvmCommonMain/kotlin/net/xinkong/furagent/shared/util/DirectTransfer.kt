package net.xinkong.furagent.shared.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.shared.tools.ToolLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 直连文件传输（双端共用）。
 *
 * 不经过电脑中转，两台设备直接 HTTP 传文件。
 * 依赖对方设备的 HTTP 文件服务器（AndroidHttpFileServer / HttpFileServer）。
 */
object DirectTransfer {

    /**
     * 连接超时 15 秒。
     * readTimeout = 120 秒（防止对方设备卡死导致永久挂起），
     * writeTimeout = 0（上传大文件不超时，由调用方控制）。
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** 测试连通性。对方返回 "ok" 视为在线。 */
    suspend fun ping(device: DirectDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "http://${device.host}:${device.port}/ping?token=${encode(device.token)}"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                resp.isSuccessful && (resp.body.string().trim() == "ok")
            }
        } catch (e: Exception) {
            ToolLogger.log(
                "DIRECT",
                "ping ${device.host}:${device.port} 失败：${e.javaClass.simpleName}: ${e.message}"
            )
            false
        }
    }

    /**
     * 用短码配对，从对端换回真 token。
     *
     * @return Pair(成功, token 或错误信息)
     */
    suspend fun pair(host: String, port: Int, code: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val c = code.trim().uppercase()
            if (c.isBlank()) return@withContext false to "短码为空"
            try {
                val url = "http://$host:$port/pair?code=${encode(c)}"
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body.string().trim()
                    if (!resp.isSuccessful) {
                        return@withContext false to "配对失败：HTTP ${resp.code}${if (body.isBlank()) "" else "（$body）"}"
                    }
                    val token = try {
                        JSONObject(body).optString("token", "")
                    } catch (_: Exception) { "" }
                    if (token.isBlank()) {
                        return@withContext false to "配对失败：对端返回不含 token"
                    }
                    true to token
                }
            } catch (e: Exception) {
                ToolLogger.log(
                    "DIRECT",
                    "pair $host:$port 失败：${e.javaClass.simpleName}: ${e.message}"
                )
                false to "配对失败：${e.message}"
            }
        }

    /**
     * 上传本地文件到对方（落在对方 receiveDir，文件名沿用 localFile.name 或 remoteName）。
     * @param remoteName 可选，指定对方接收后的文件名；为空则用 localFile.name
     */
    suspend fun upload(
        device: DirectDevice,
        localFile: File,
        remoteName: String = ""
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!localFile.exists() || !localFile.isFile) {
            return@withContext false to "本地文件不存在：${localFile.absolutePath}"
        }
        try {
            val name = remoteName.ifBlank { localFile.name }
            val url = "http://${device.host}:${device.port}/upload" +
                    "?token=${encode(device.token)}" +
                    "&path=${encode(name)}"
            val body = localFile.asRequestBody("application/octet-stream".toMediaType())
            val req = Request.Builder().url(url).post(body).build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body.string()
                if (resp.isSuccessful) {
                    true to "已发送到 ${device.name.ifBlank { device.host }}：$name（${formatFileSize(localFile.length())}）"
                } else {
                    false to "HTTP ${resp.code}：${text.take(200)}"
                }
            }
        } catch (e: Exception) {
            ToolLogger.log(
                "DIRECT",
                "上传到 ${device.host}:${device.port} 失败：${e.javaClass.simpleName}: ${e.message}"
            )
            false to "上传失败：${e.message}"
        }
    }

    /**
     * 从对方下载文件。
     * @param remotePath 远端文件名（自动在对方 receiveDir 里找），或绝对路径（以 / 开头）
     */
    suspend fun download(
        device: DirectDevice,
        remotePath: String,
        saveFile: File
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val url = "http://${device.host}:${device.port}/download" +
                    "?token=${encode(device.token)}" +
                    "&path=${encode(remotePath)}"
            val req = Request.Builder().url(url).get().build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                val text = resp.use { it.body.string() }
                return@withContext false to "HTTP ${resp.code}：${text.take(200)}"
            }
            saveFile.parentFile?.mkdirs()
            val tmp = File(saveFile.parentFile, saveFile.name + ".part")
            resp.use {
                // okhttp 5.x：body 是 non-null，无需 ?: 兜底
                it.body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        input.copyTo(output, 128 * 1024)
                    }
                }
            }
            if (!tmp.renameTo(saveFile)) {
                saveFile.writeBytes(tmp.readBytes())
                tmp.delete()
            }
            val size = saveFile.length()
            true to "已从 ${device.name.ifBlank { device.host }} 接收：${saveFile.name}（${formatFileSize(size)}）"
        } catch (e: Exception) {
            ToolLogger.log(
                "DIRECT",
                "从 ${device.host}:${device.port} 下载失败：${e.javaClass.simpleName}: ${e.message}"
            )
            false to "下载失败：${e.message}"
        }
    }

    /** 请求对方删除远端文件。 */
    suspend fun delete(
        device: DirectDevice,
        remotePath: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val url = "http://${device.host}:${device.port}/delete" +
                    "?token=${encode(device.token)}" +
                    "&path=${encode(remotePath)}"
            val body = "".toRequestBody("text/plain".toMediaType())
            val req = Request.Builder().url(url).post(body).build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body.string()
                if (resp.isSuccessful) true to "已删除对方文件：$remotePath"
                else false to "HTTP ${resp.code}：${text.take(200)}"
            }
        } catch (e: Exception) {
            ToolLogger.log(
                "DIRECT",
                "删除对方文件失败：${e.javaClass.simpleName}: ${e.message}"
            )
            false to "删除失败：${e.message}"
        }
    }
}