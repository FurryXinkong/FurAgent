package net.xinkong.furagent.desktop.bridge

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import net.xinkong.furagent.desktop.data.DesktopPaths
import net.xinkong.furagent.desktop.data.DesktopSettingsHolder
import net.xinkong.furagent.desktop.data.extractFileName
import net.xinkong.furagent.desktop.data.resolveReceiveFile
import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.desktop.system.DesktopNotifier
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object HttpFileServer {

    private const val TOKEN_FILE_NAME = "http_token.txt"

    @Volatile private var executor: ExecutorService? = null
    @Volatile private var server: HttpServer? = null
    @Volatile private var token: String = ""
    @Volatile private var port: Int = 0
    @Volatile private var windowActivator: (() -> Unit)? = null

    fun setWindowActivator(action: () -> Unit) {
        windowActivator = action
    }

    fun start(preferredPort: Int): Pair<Int, String> {
        if (server != null) return port to token
        // 【B4】token 从持久化文件读，首次生成后不再变
        token = loadOrCreateToken()

        val candidates = listOf(preferredPort, 0)
        for (p in candidates) {
            try {
                val s = HttpServer.create(InetSocketAddress(p), 0)
                // 从 4 提升到 16
                val ex = Executors.newFixedThreadPool(16) { r ->
                    Thread(r, "HttpFileServer-worker").apply { isDaemon = true }
                }
                s.executor = ex
                executor = ex

                s.createContext("/ping") { ex -> handlePing(ex) }
                s.createContext("/pair") { ex -> handlePair(ex) }
                s.createContext("/download") { ex -> handleDownload(ex) }
                s.createContext("/upload") { ex -> handleUpload(ex) }
                s.createContext("/delete") { ex -> handleDelete(ex) }
                s.start()
                server = s
                port = s.address.port
                DesktopLogger.info("HttpServer", "已启动 :$port（短码=${getShortCode()}）")
                return port to token
            } catch (e: Exception) {
                DesktopLogger.warn("HttpServer", "端口 $p 启动失败：${e.javaClass.simpleName}: ${e.message}")
            }
        }
        DesktopLogger.error("HttpServer", "所有端口均启动失败")
        port = 0
        token = ""
        return -1 to ""
    }

    fun stop() {
        try { server?.stop(0) } catch (e: Exception) {
            DesktopLogger.warn("HttpServer", "stop server 异常：${e.message}")
        }
        try { executor?.shutdownNow() } catch (e: Exception) {
            DesktopLogger.warn("HttpServer", "shutdownNow 异常：${e.message}")
        }
        server = null
        executor = null
        port = 0
        token = ""
        DesktopLogger.info("HttpServer", "已停止")
    }

    fun getPort(): Int = port
    fun getToken(): String = token
    fun isRunning(): Boolean = server != null

    /**
     * 【B4 新增】从 token 派生的短码（6 位大写 HEX）。
     * SHA-256(token) 前 3 字节转 16 进制。单向，泄露也无法反推 token。
     */
    fun getShortCode(): String = deriveShortCode(token)

    /**
     * 【B4 新增】主动刷新 token。
     */
    fun refreshToken(): String {
        val newToken = UUID.randomUUID().toString().replace("-", "")
        try {
            File(DesktopPaths.configDir(), TOKEN_FILE_NAME).writeText(newToken, Charsets.UTF_8)
        } catch (e: Exception) {
            DesktopLogger.warn(
                "HttpServer",
                "刷新 token 写盘失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
        token = newToken
        DesktopLogger.info("HttpServer", "HTTP token 已刷新（短码=${getShortCode()}）")
        return newToken
    }

    private fun loadOrCreateToken(): String {
        return try {
            val f = File(DesktopPaths.configDir(), TOKEN_FILE_NAME)
            if (f.exists()) {
                val t = f.readText(Charsets.UTF_8).trim()
                if (t.length >= 16) {
                    DesktopLogger.info(
                        "HttpServer",
                        "HTTP token 已从磁盘加载（短码=${deriveShortCode(t)}）"
                    )
                    return t
                }
            }
            val newToken = UUID.randomUUID().toString().replace("-", "")
            f.writeText(newToken, Charsets.UTF_8)
            DesktopLogger.info("HttpServer", "HTTP token 已生成（短码=${deriveShortCode(newToken)}）")
            newToken
        } catch (e: Exception) {
            DesktopLogger.warn(
                "HttpServer",
                "读写 token 文件失败，使用临时 token：${e.javaClass.simpleName}: ${e.message}"
            )
            UUID.randomUUID().toString().replace("-", "")
        }
    }

    // 【2026-10-15 去重】实现已抽到 shared HttpTokenCodec
    private fun deriveShortCode(t: String): String =
        net.xinkong.furagent.shared.util.HttpTokenCodec.deriveShortCode(t)

    private fun handlePing(ex: HttpExchange) {
        try {
            respond(ex, 200, "ok", "text/plain")
        } catch (e: Exception) {
            DesktopLogger.warn("HttpServer", "handlePing 异常：${e.message}")
        } finally {
            try { ex.close() } catch (_: Exception) { }
        }
    }

    /**
     * 【B4 新增】/pair 端点。
     * 用短码作为凭证换真 token，不需要 token 前置校验。
     */
    private fun handlePair(ex: HttpExchange) {
        try {
            if (ex.requestMethod != "GET") {
                respond(ex, 405, "method not allowed", "text/plain")
                return
            }
            val params = parseQuery(ex.requestURI.query ?: "")
            val code = (params["code"] ?: "").trim().uppercase()
            if (code.isBlank()) {
                respond(ex, 400, "missing code", "text/plain")
                return
            }
            val expected = getShortCode()
            if (expected.isBlank() || code != expected) {
                DesktopLogger.warn(
                    "HttpServer",
                    "pair 配对码错误（来自 ${ex.remoteAddress}）"
                )
                respond(ex, 403, "invalid code", "text/plain")
                return
            }
            respond(ex, 200, """{"token":"$token"}""", "application/json")
        } catch (e: Exception) {
            DesktopLogger.error("HttpServer", "handlePair 异常：${e.javaClass.simpleName}: ${e.message}")
            try { respond(ex, 500, "error: ${e.message}", "text/plain") } catch (_: Exception) { }
        } finally {
            try { ex.close() } catch (_: Exception) { }
        }
    }

    private fun resolveRemotePath(rawPath: String): String {
        if (rawPath.isBlank()) return rawPath
        if (rawPath.startsWith("/")) return rawPath
        if (rawPath.contains(":\\")) return rawPath
        val receiveDir = DesktopSettingsHolder.current.receiveDir
        return File(receiveDir, rawPath).absolutePath
    }

    private fun handleDownload(ex: HttpExchange) {
        try {
            if (ex.requestMethod != "GET") {
                respond(ex, 405, "method not allowed", "text/plain")
                return
            }
            if (!checkToken(ex)) {
                DesktopLogger.warn("HttpServer", "download token 校验失败：${ex.remoteAddress}")
                respond(ex, 403, "forbidden", "text/plain")
                return
            }
            val params = parseQuery(ex.requestURI.query ?: "")
            val rawPath = params["path"] ?: run {
                respond(ex, 400, "missing path", "text/plain")
                return
            }
            val actualPath = resolveRemotePath(rawPath)
            val file = File(actualPath)
            if (!file.exists() || !file.isFile) {
                DesktopLogger.warn("HttpServer", "download 文件不存在：$actualPath")
                respond(ex, 404, "not found", "text/plain")
                return
            }
            ex.responseHeaders.add("Content-Type", "application/octet-stream")
            ex.responseHeaders.add(
                "Content-Disposition",
                "attachment; filename=\"${file.name.replace("\"", "_")}\""
            )
            ex.sendResponseHeaders(200, file.length())
            file.inputStream().use { input ->
                ex.responseBody.use { out ->
                    input.copyTo(out, bufferSize = 128 * 1024)
                }
            }
            DesktopLogger.info("HttpServer", "download 已发送：$actualPath")
        } catch (e: Exception) {
            DesktopLogger.error("HttpServer", "handleDownload 异常：${e.javaClass.simpleName}: ${e.message}")
            try { respond(ex, 500, "error: ${e.message}", "text/plain") } catch (_: Exception) { }
        } finally {
            try { ex.close() } catch (_: Exception) { }
        }
    }

    private fun handleUpload(ex: HttpExchange) {
        try {
            if (ex.requestMethod != "POST") {
                respond(ex, 405, "method not allowed", "text/plain")
                return
            }
            if (!checkToken(ex)) {
                DesktopLogger.warn("HttpServer", "upload token 校验失败：${ex.remoteAddress}")
                respond(ex, 403, "forbidden", "text/plain")
                return
            }
            val params = parseQuery(ex.requestURI.query ?: "")
            val rawPath = params["path"] ?: ""
            val dirParam = params["dir"] ?: ""

            val fileName = extractFileName(rawPath)
            val receiveDir = DesktopSettingsHolder.current.receiveDir

            val targetDir: File = if (dirParam.isNotBlank()) {
                val safeDir = dirParam.replace("\\", "/").split("/")
                    .filter { it.isNotBlank() && it != "." && it != ".." }
                    .joinToString(File.separator)
                File(receiveDir, safeDir)
            } else {
                File(receiveDir)
            }
            if (!targetDir.exists()) targetDir.mkdirs()

            val target = resolveReceiveFile(targetDir.absolutePath, fileName)
            target.parentFile?.mkdirs()

            // 【B1/B3】.part 临时文件落点：
            //   - 与 target 同盘时落 ~/.furagent/temp/（不污染用户接收目录），完成后 rename（快）
            //   - 不同盘时直接在 target 目录写 .part（同目录 rename 必成功，避免跨盘全量拷贝）
            // 用唯一名避免并发上传同名文件互相覆盖。
            val sameDrive = sameDrive(DesktopPaths.tempDir(), target.parentFile ?: target)
            val tmp = if (sameDrive) {
                File(DesktopPaths.tempDir(), "upload_${UUID.randomUUID()}.part")
            } else {
                File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.part")
            }
            try {
                ex.requestBody.use { input ->
                    tmp.outputStream().use { out ->
                        input.copyTo(out, bufferSize = 128 * 1024)
                    }
                }
                // 【B2】renameTo 失败时用流式 copyTo，避免 readBytes() 大文件 OOM。
                if (!tmp.renameTo(target)) {
                    tmp.inputStream().use { input ->
                        target.outputStream().use { out ->
                            input.copyTo(out, bufferSize = 128 * 1024)
                        }
                    }
                }
            } finally {
                // 无论成功与否都清理临时文件（rename 成功后 tmp 已不存在，delete 无害）
                if (tmp.exists()) tmp.delete()
            }
            val size = target.length()
            respond(ex, 200, "ok: ${target.absolutePath} (${formatSize(size)})", "text/plain")
            DesktopLogger.info("HttpServer", "upload 已接收：${target.absolutePath}（${formatSize(size)}）")

            try {
                DesktopNotifier.notify("已接收到文件", "${target.name}（${formatSize(size)}）")
            } catch (e: Exception) {
                DesktopLogger.warn("HttpServer", "接收通知失败：${e.message}")
            }
            try {
                windowActivator?.invoke()
            } catch (e: Exception) {
                DesktopLogger.warn("HttpServer", "windowActivator 失败：${e.message}")
            }
        } catch (e: Exception) {
            DesktopLogger.error("HttpServer", "handleUpload 异常：${e.javaClass.simpleName}: ${e.message}")
            try { respond(ex, 500, "error: ${e.message}", "text/plain") } catch (_: Exception) { }
        } finally {
            try { ex.close() } catch (_: Exception) { }
        }
    }

    private fun handleDelete(ex: HttpExchange) {
        try {
            if (ex.requestMethod != "POST") {
                respond(ex, 405, "method not allowed", "text/plain")
                return
            }
            if (!checkToken(ex)) {
                DesktopLogger.warn("HttpServer", "delete token 校验失败：${ex.remoteAddress}")
                respond(ex, 403, "forbidden", "text/plain")
                return
            }
            val params = parseQuery(ex.requestURI.query ?: "")
            val rawPath = params["path"] ?: run {
                respond(ex, 400, "missing path", "text/plain")
                return
            }
            val actualPath = resolveRemotePath(rawPath)
            val file = File(actualPath)
            if (!file.exists()) {
                DesktopLogger.warn("HttpServer", "delete 文件不存在：$actualPath")
                respond(ex, 404, "not found", "text/plain")
                return
            }
            // 【安全删除】不真删，移入回收站（~/.furagent/temp/trash），由用户决定何时清空。
            val r = net.xinkong.furagent.shared.tools.TrashHelper.moveToTrash(file)
            DesktopLogger.info(
                "HttpServer",
                "delete ${if (r.ok) "已移入回收站" else "失败"}：$actualPath -> ${r.trashPath}"
            )
            respond(ex, if (r.ok) 200 else 500, if (r.ok) "ok" else "delete failed: ${r.message}", "text/plain")
        } catch (e: Exception) {
            DesktopLogger.error("HttpServer", "handleDelete 异常：${e.javaClass.simpleName}: ${e.message}")
            try { respond(ex, 500, "error: ${e.message}", "text/plain") } catch (_: Exception) { }
        } finally {
            try { ex.close() } catch (_: Exception) { }
        }
    }

    private fun checkToken(ex: HttpExchange): Boolean {
        val params = parseQuery(ex.requestURI.query ?: "")
        return params["token"] == token
    }

    /** 判断两个路径是否在同一盘符（Windows 比较根，如 C:\ / D:\；非 Windows 比较根路径）。 */
    private fun sameDrive(a: File, b: File): Boolean {
        val ra = a.absoluteFile.toPath().root
        val rb = b.absoluteFile.toPath().root
        return ra != null && ra == rb
    }

    // 【2026-10-15 去重】实现已抽到 shared HttpQuery
    private fun parseQuery(q: String): Map<String, String> =
        net.xinkong.furagent.shared.util.HttpQuery.parse(q)

    private fun respond(ex: HttpExchange, code: Int, body: String, contentType: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", contentType)
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1fKB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1fMB", bytes / 1024.0 / 1024.0)
        else -> String.format(Locale.US, "%.2fGB", bytes / 1024.0 / 1024.0 / 1024.0)
    }
}