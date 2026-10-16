package net.xinkong.furagent.bridge

import android.content.Context
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.shared.tools.PathResolver
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


object AndroidHttpFileServer {
    private const val MAX_HEADER = 8192
    private const val TOKEN_FILE_NAME = "http_token.txt"

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var pool: ExecutorService? = null
    @Volatile private var token: String = ""
    @Volatile private var port: Int = 0
    @Volatile private var receiveDir: String = "/sdcard/FurAgent/received"

    @Volatile private var appContext: Context? = null

    @Volatile private var allowedPaths: List<String> = listOf("/sdcard")
    @Volatile private var blockedPaths: List<String> =
        listOf("/data", "/system", "/vendor", "/proc", "/sys")

    @Volatile private var fileReceivedCallback: ((String) -> Unit)? = null

    /**
     * 启动服务。
     *
     * 【B4 改动】签名从 `start(preferredPort)` 改为 `start(context, preferredPort)`，
     * 以便持久化 token（写到 filesDir/http_token.txt）。
     * 调用方 MainActivity.onCreate 一处需要同步更新。
     */
    fun start(context: Context, preferredPort: Int): Pair<Int, String> {
        if (serverSocket != null) return port to token
        appContext = context.applicationContext
        // 【B4】token 从持久化文件读，首次生成后不再变
        token = loadOrCreateToken()

        val candidates = listOf(preferredPort, 0)
        for (p in candidates) {
            try {
                val s = ServerSocket(p)
                s.reuseAddress = true
                serverSocket = s
                port = s.localPort

                val ex = Executors.newFixedThreadPool(16) { r ->
                    Thread(r, "FurAgentHttp-worker").apply { isDaemon = true }
                }
                pool = ex

                acceptThread = Thread {
                    while (!s.isClosed) {
                        try {
                            val client = s.accept()
                            ex.execute { handle(client) }
                        } catch (e: Exception) {
                            AgentLogger.log(
                                "BRIDGE",
                                "HTTP accept 循环退出：${e.javaClass.simpleName}: ${e.message}"
                            )
                            break
                        }
                    }
                }.apply { isDaemon = true; start() }
                return port to token
            } catch (e: Exception) {
                AgentLogger.log(
                    "BRIDGE",
                    "HTTP 端口 $p 启动失败，尝试下一个：${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
        AgentLogger.log("ERR", "HTTP 文件服务启动失败：所有候选端口均被占用")
        port = 0
        token = ""
        return -1 to ""
    }

    fun stop() {
        try { serverSocket?.close() } catch (e: Exception) {
            AgentLogger.log("BRIDGE", "关闭 HTTP serverSocket 异常：${e.message}")
        }
        try { pool?.shutdownNow() } catch (e: Exception) {
            AgentLogger.log("BRIDGE", "关闭 HTTP 线程池异常：${e.message}")
        }
        serverSocket = null
        acceptThread = null
        pool = null
        port = 0
        token = ""
        appContext = null
    }

    fun getPort() = port
    fun getToken() = token
    fun isRunning() = serverSocket != null

    /**
     * 【B4 新增】从 token 派生的短码（6 位大写 HEX）。
     *
     * 用于用户在另一台设备上手工输入配对。
     * 算法：SHA-256(token) 前 3 字节转 16 进制。
     * 即使短码泄露也无法反推 token（hash 单向），但知道 token 一定能算出短码。
     */
    fun getShortCode(): String = deriveShortCode(token)

    /**
     * 【B4 新增】主动刷新 token。
     *
     * 用户可以在 UI 上点"刷新 token"，让已配对过的其他设备失效。
     * 服务本身不重启，只换 token，后续请求要用新 token。
     */
    fun refreshToken(): String {
        val ctx = appContext
        val newToken = UUID.randomUUID().toString().replace("-", "")
        try {
            if (ctx != null) {
                File(ctx.filesDir, TOKEN_FILE_NAME).writeText(newToken, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            AgentLogger.log(
                "BRIDGE",
                "刷新 token 写盘失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
        token = newToken
        AgentLogger.log("BRIDGE", "HTTP token 已刷新（短码=${getShortCode()}）")
        return newToken
    }

    private fun loadOrCreateToken(): String {
        val ctx = appContext
            ?: return UUID.randomUUID().toString().replace("-", "")
        return try {
            val f = File(ctx.filesDir, TOKEN_FILE_NAME)
            if (f.exists()) {
                val t = f.readText(Charsets.UTF_8).trim()
                if (t.length >= 16) {
                    AgentLogger.log("BRIDGE", "HTTP token 已从磁盘加载（短码=${deriveShortCode(t)}）")
                    return t
                }
            }
            val newToken = UUID.randomUUID().toString().replace("-", "")
            f.writeText(newToken, Charsets.UTF_8)
            AgentLogger.log("BRIDGE", "HTTP token 已生成（短码=${deriveShortCode(newToken)}）")
            newToken
        } catch (e: Exception) {
            AgentLogger.log(
                "BRIDGE",
                "读写 token 文件失败，使用临时 token：${e.javaClass.simpleName}: ${e.message}"
            )
            UUID.randomUUID().toString().replace("-", "")
        }
    }

    // 【2026-10-15 去重】实现已抽到 shared HttpTokenCodec
    private fun deriveShortCode(t: String): String =
        net.xinkong.furagent.shared.util.HttpTokenCodec.deriveShortCode(t)

    fun setReceiveDir(dir: String) { if (dir.isNotBlank()) receiveDir = dir }

    fun setPathPolicy(allowed: List<String>, blocked: List<String>) {
        allowedPaths = allowed.map { it.trim() }.filter { it.isNotBlank() }
        blockedPaths = blocked.map { it.trim() }.filter { it.isNotBlank() }
    }

    fun setFileReceivedCallback(cb: (String) -> Unit) {
        fileReceivedCallback = cb
    }

    private fun handle(socket: Socket) {
        var method = "?"
        var url = "?"
        try {
            socket.soTimeout = 60_000
            val input = socket.getInputStream().buffered()
            val output = socket.getOutputStream().buffered()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            method = parts[0]
            url = parts[1]

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }

            val qIdx = url.indexOf('?')
            val path = if (qIdx >= 0) url.substring(0, qIdx) else url
            val query = if (qIdx >= 0) url.substring(qIdx + 1) else ""
            val params = parseQuery(query)

            // 【B4】/pair 端点必须在 token 校验之前处理：
            // 用短码作为凭证换真 token。
            if (method == "GET" && path == "/pair") {
                val code = (params["code"] ?: "").trim().uppercase()
                if (code.isBlank()) {
                    respondText(output, 400, "missing code")
                    return
                }
                val expected = getShortCode()
                if (expected.isBlank() || code != expected) {
                    AgentLogger.log(
                        "BRIDGE",
                        "pair 配对码错误（来自 ${socket.remoteSocketAddress}）"
                    )
                    respondText(output, 403, "invalid code")
                    return
                }
                respondText(output, 200, """{"token":"$token"}""")
                return
            }

            // 其余端点：token 校验
            // 【修复 4】token 为空时拒绝一切请求，避免 ?token= 空串绕过校验
            if (token.isBlank() || params["token"] != token) {
                respondText(output, 403, "forbidden")
                return
            }

            when (method) {
                "GET" if path == "/ping" -> respondText(output, 200, "ok")

                "GET" if path == "/download" -> {
                    val remoteRaw = params["path"] ?: ""
                    val remote = resolveRemotePath(remoteRaw)
                    val resolved = PathResolver.check(remote, allowedPaths, blockedPaths).resolved
                    if (resolved == null) { respondText(output, 403, "path not allowed"); return }
                    val f = File(resolved)
                    if (!f.exists() || !f.isFile) { respondText(output, 404, "not found"); return }
                    respondFile(output, f)
                }

                "POST" if path == "/upload" -> {
                    val remote = params["path"] ?: ""
                    val dirParam = params["dir"] ?: ""

                    val name = remote.substringAfterLast('/').substringAfterLast('\\')
                        .ifBlank { "unnamed_${System.currentTimeMillis()}" }

                    // 【修复 1】dirParam 是相对 receiveDir 的路径（如 "relay/<id>"），
                    // 必须先拼到 receiveDir 再做安全检查。
                    val targetDir: File = if (dirParam.isNotBlank()) {
                        val candidate = File(receiveDir, dirParam)
                        val resolved = PathResolver.check(
                            candidate.absolutePath, allowedPaths, blockedPaths
                        ).resolved
                        if (resolved == null) {
                            respondText(output, 403, "dir not allowed")
                            return
                        }
                        File(resolved)
                    } else {
                        File(receiveDir)
                    }
                    if (!targetDir.exists()) targetDir.mkdirs()

                    var target = File(targetDir, name)
                    if (target.exists()) {
                        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                        val base = name.substringBeforeLast('.')
                        val ext = name.substringAfterLast('.', "")
                        target = File(
                            targetDir,
                            if (ext.isBlank()) "${base}_$ts" else "${base}_$ts.$ext"
                        )
                    }

                    val len = headers["content-length"]?.toLongOrNull() ?: -1L
                    if (len <= 0) { respondText(output, 400, "no content-length"); return }
                    writeBody(input, target, len)
                    respondText(output, 200, "ok: ${target.absolutePath}")
                    try {
                        fileReceivedCallback?.invoke(target.absolutePath)
                    } catch (e: Exception) {
                        AgentLogger.log(
                            "BRIDGE",
                            "文件接收回调异常（${target.absolutePath}）：${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                }

                "POST" if path == "/delete" -> {
                    val remoteRaw = params["path"] ?: ""
                    val remote = resolveRemotePath(remoteRaw)
                    val resolved = PathResolver.check(remote, allowedPaths, blockedPaths).resolved
                    if (resolved == null) { respondText(output, 403, "path not allowed"); return }

                    val f = File(resolved)
                    if (!f.exists()) { respondText(output, 404, "not found"); return }
                    // 【安全删除】不真删，移入回收站，由用户决定何时清空。
                    val trashDir = appContext?.let { File(it.filesDir, "temp/trash") }
                        ?: net.xinkong.furagent.shared.tools.TrashHelper.defaultTrashDir()
                    val r = net.xinkong.furagent.shared.tools.TrashHelper.moveToTrash(f, trashDir)
                    AgentLogger.log(
                        "BRIDGE",
                        "HTTP delete ${if (r.ok) "已移入回收站" else "失败"}：$resolved -> ${r.trashPath}"
                    )
                    respondText(output, if (r.ok) 200 else 500, if (r.ok) "ok" else "delete failed: ${r.message}")
                }

                else -> respondText(output, 404, "not found")
            }
        } catch (e: Exception) {
            AgentLogger.log(
                "BRIDGE",
                "HTTP 请求处理异常：$method $url - ${e.javaClass.simpleName}: ${e.message}"
            )
        } finally {
            try { socket.close() } catch (_: Exception) { }
        }
    }

    /** 相对路径（不以 / 开头且不含盘符）拼到 receiveDir */
    private fun resolveRemotePath(rawPath: String): String {
        if (rawPath.isBlank()) return rawPath
        if (rawPath.startsWith("/")) return rawPath
        if (rawPath.contains(":\\")) return rawPath
        return File(receiveDir, rawPath).absolutePath
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < MAX_HEADER) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
        return null
    }

    // 【2026-10-15 去重】实现已抽到 shared HttpQuery
    private fun parseQuery(q: String): Map<String, String> =
        net.xinkong.furagent.shared.util.HttpQuery.parse(q)

    private fun respondText(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val statusText = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        out.write("HTTP/1.1 $code $statusText\r\n".toByteArray())
        out.write("Content-Type: text/plain; charset=utf-8\r\n".toByteArray())
        out.write("Content-Length: ${bytes.size}\r\n".toByteArray())
        out.write("Connection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun respondFile(out: OutputStream, f: File) {
        out.write("HTTP/1.1 200 OK\r\n".toByteArray())
        out.write("Content-Type: application/octet-stream\r\n".toByteArray())
        out.write(
            "Content-Disposition: attachment; filename=\"${f.name.replace("\"", "_")}\"\r\n"
                .toByteArray()
        )
        out.write("Content-Length: ${f.length()}\r\n".toByteArray())
        out.write("Connection: close\r\n\r\n".toByteArray())
        f.inputStream().use { it.copyTo(out, 128 * 1024) }
        out.flush()
    }

    private fun writeBody(input: InputStream, target: File, len: Long) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.outputStream().use { out ->
            var remaining = len
            val buf = ByteArray(128 * 1024)
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
                remaining -= n
            }
        }
        if (!tmp.renameTo(target)) {
            target.writeBytes(tmp.readBytes())
            tmp.delete()
        }
    }
}