package net.xinkong.furagent.desktop.bridge

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import net.xinkong.furagent.desktop.data.DesktopPaths
import net.xinkong.furagent.desktop.data.DesktopSettingsHolder
import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.desktop.system.DesktopNotifier
import net.xinkong.furagent.shared.util.FileNaming
import java.io.File
import java.net.InetSocketAddress
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
        // token 从持久化文件读，首次生成后不再变
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
     * 从 token 派生的短码（6 位大写 HEX）。
     * SHA-256(token) 前 3 字节转 16 进制。单向，泄露也无法反推 token。
     */
    fun getShortCode(): String = deriveShortCode(token)

    /**
     * 主动刷新 token。
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

    private fun deriveShortCode(t: String): String =
        net.xinkong.furagent.shared.util.HttpTokenCodec.deriveShortCode(t)

    private fun handlePing(ex: HttpExchange) {
        try {
            // 诊断增强 返回端口、短码、receiveDir，方便用户用浏览器直接测：
            //   http://127.0.0.1:<port>/ping?token=<token>
            // 能看到这段文本就说明 HTTP 服务本身没问题，问题在网络/端口/防火墙。
            val receiveDir = try { DesktopSettingsHolder.current.receiveDir } catch (_: Throwable) { "?" }
            val writable = try { File(receiveDir).let { !it.exists() || it.canWrite() } } catch (_: Throwable) { false }
            val body = buildString {
                append("ok\n")
                append("port=").append(port).append('\n')
                append("shortCode=").append(getShortCode()).append('\n')
                append("tokenLength=").append(token.length).append('\n')
                append("receiveDir=").append(receiveDir).append('\n')
                append("receiveDirWritable=").append(writable).append('\n')
            }
            respond(ex, 200, body, "text/plain")
        } catch (e: Exception) {
            DesktopLogger.warn("HttpServer", "handlePing 异常：${e.message}")
        } finally {
            try { ex.close() } catch (_: Exception) { }
        }
    }

    /**
     * pair 端点。
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

    /**
     * 解析 HTTP 请求里的 path 参数。
     *
     * 原实现只做 File(receiveDir, rawPath).absolutePath，不做规范化，
     * 也不校验系统目录，导致 `?path=../../etc/passwd` 能读到 receiveDir 之外的文件。
     * token 泄露 = 任意文件读，必须修。
     *
     * 修复策略：
     *   1. canonicalPath 展开 `..` 与软链，得到真实访问路径
     *   2. 明确拒绝系统敏感目录（Linux /proc /sys /dev /boot + Windows System32）
     *   3. 返回 null 表示拒绝访问（不改 resolveRemotePath 的"绝对路径原样访问"能力，
     *      桌面端设计上允许用户下载任意文件，只做最低限度系统目录防护）
     */
    private fun resolveRemotePath(rawPath: String): String? {
        if (rawPath.isBlank()) return null
        val file = if (rawPath.startsWith("/") || rawPath.contains(":\\")) {
            File(rawPath)
        } else {
            File(DesktopSettingsHolder.current.receiveDir, rawPath)
        }
        val canonical = try {
            file.canonicalPath
        } catch (_: Exception) {
            file.absolutePath
        }
        val lower = canonical.lowercase(Locale.ROOT).replace('\\', '/')
        // Linux / macOS 系统敏感目录
        for (s in listOf("/proc", "/sys", "/dev", "/boot")) {
            if (lower == s || lower.startsWith("$s/")) {
                DesktopLogger.warn("HttpServer", "拒绝访问系统目录：$s（请求 $rawPath）")
                return null
            }
        }
        // Windows 系统敏感目录
        if (lower.contains("/windows/system32") || lower.contains("/windows/syswow64")) {
            DesktopLogger.warn("HttpServer", "拒绝访问 Windows 系统目录（请求 $rawPath）")
            return null
        }
        return canonical
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
            // resolveRemotePath 返回 null 表示拒绝访问（系统敏感目录或规范化失败）
            val actualPath = resolveRemotePath(rawPath) ?: run {
                respond(ex, 403, "path not allowed", "text/plain")
                return
            }
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
            // 大文件下载 unexpected end of stream。
            //
            // 根因：com.sun.net.httpserver 在 sendResponseHeaders(200, contentLength) 声明了
            // Content-Length 的模式下，JDK 内部在 close() 时检查实际写入是否等于声明长度。
            // Windows JVM 17/21 上，当响应体 > socket buffer 时，JDK 的节流逻辑偶尔在检查前
            // 提前 shutdown socket 输出流，客户端（OkHttp）读到 EOF 时实际字节数少于声明值，
            // 抛 IOException: unexpected end of stream on http://...
            // KB 级响应一次写完就 flush 不触发；MB 级要分多次写 socket，触发这个竞态。
            //
            // 修复：改用 chunked 传输编码（sendResponseHeaders(200, 0)），显式 flush。
            // 这是 JDK 官方推荐的流式大文件写法，OkHttp 完全支持 chunked 解码。
            // 副作用：客户端看不到 Content-Length（resp.body.contentLength() 返回 -1），
            //       下载进度条走不确定动画，功能不受影响。
            ex.sendResponseHeaders(200, 0)
            val actualLen = file.length()
            var written = 0L
            file.inputStream().use { input ->
                ex.responseBody.use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        out.write(buf, 0, n)
                        out.flush()
                        written += n
                    }
                }
            }
            // 声明长度 > 0 时做个兜底日志（chunked 模式下不再是致命错误，只是记录）
            if (actualLen > 0 && written != actualLen) {
                DesktopLogger.warn(
                    "HttpServer",
                    "download 字节数不符：声明 $actualLen 实际 $written（chunked 模式，客户端仍能正确接收）"
                )
            }
            // 诊断加强 下载完成打完整日志：路径 + 字节数 + 远端地址。
            // 手机端"下载不了"时对照本端日志，能一眼判断请求到底有没有到达。
            DesktopLogger.info(
                "HttpServer",
                "download 已发送：$actualPath（${formatSize(file.length())}）→ ${ex.remoteAddress}"
            )
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

            val fileName = FileNaming.extractFileName(rawPath)
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
            // 诊断增强 记录本次上传的落点，失败时能一眼看出是目录不可写还是别的
            DesktopLogger.info(
                "HttpServer",
                "upload 请求：rawPath=$rawPath dir=$dirParam → targetDir=${targetDir.absolutePath}（存在=${targetDir.exists()} 可写=${targetDir.canWrite()}）"
            )

            val target = FileNaming.resolveReceiveFile(targetDir.absolutePath, fileName)
            target.parentFile?.mkdirs()

            // B1/B3 .part 临时文件落点：
            //   - 与 target 同盘时落 ~/.furagent/temp/（不污染用户接收目录），完成后 rename（快）
            //   - 不同盘时直接在 target 目录写 .part（同目录 rename 必成功，避免跨盘全量拷贝）
            // 用唯一名避免并发上传同名文件互相覆盖。
            val sameDrive = sameDrive(DesktopPaths.tempDir(), target.parentFile ?: target)
            val tmp = if (sameDrive) {
                File(DesktopPaths.tempDir(), "upload_${UUID.randomUUID()}.part")
            } else {
                File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.part")
            }
            // F6/诊断加强 原实现 input.copyTo 读到 EOF 就 rename，
            // 客户端提前断流（断网/代理超时/手机切后台）时只会写入一部分，
            // 但仍然 respond 200 ok，用户看到"上传成功"实际文件已损坏。
            // 现在校验 Content-Length 与实写字节数，不一致直接 500，不再假装成功。
            val declaredLen = ex.requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: -1L
            var writtenLen = 0L
            try {
                ex.requestBody.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            writtenLen += n
                        }
                    }
                }
                if (declaredLen > 0 && writtenLen != declaredLen) {
                    throw RuntimeException("上传不完整：声明 $declaredLen 字节，实际写入 $writtenLen 字节")
                }
                // renameTo 失败时用流式 copyTo，避免 readBytes() 大文件 OOM。
                if (!tmp.renameTo(target)) {
                    tmp.inputStream().use { input ->
                        target.outputStream().use { out ->
                            input.copyTo(out, bufferSize = 128 * 1024)
                        }
                    }
                }
            } catch (e: Exception) {
                DesktopLogger.warn(
                    "HttpServer",
                    "upload 失败：声明=$declaredLen 实际=$writtenLen 临时文件=${tmp.absolutePath} " +
                            "${e.javaClass.simpleName}: ${e.message}"
                )
                try { respond(ex, 500, "upload failed: ${e.message}", "text/plain") } catch (_: Exception) { }
                return
            } finally {
                // 无论成功与否都清理临时文件（rename 成功后 tmp 已不存在，delete 无害）
                if (tmp.exists()) tmp.delete()
            }
            val size = target.length()
            respond(ex, 200, "ok: ${target.absolutePath} (${formatSize(size)})", "text/plain")
            DesktopLogger.info(
                "HttpServer",
                "upload 已接收：${target.absolutePath}（${formatSize(size)}）声明=$declaredLen 实际=$writtenLen"
            )

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
            // resolveRemotePath 返回 null 表示拒绝访问
            val actualPath = resolveRemotePath(rawPath) ?: run {
                respond(ex, 403, "path not allowed", "text/plain")
                return
            }
            val file = File(actualPath)
            if (!file.exists()) {
                DesktopLogger.warn("HttpServer", "delete 文件不存在：$actualPath")
                respond(ex, 404, "not found", "text/plain")
                return
            }
            // 不真删，移入回收站（~/.furagent/temp/trash），由用户决定何时清空。
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
        // token 未初始化时拒绝所有请求，
        // 避免 ?token= 空串绕过（与 AndroidHttpFileServer 对齐）。
        if (token.isBlank()) return false
        val params = parseQuery(ex.requestURI.query ?: "")
        return params["token"] == token
    }

    /** 判断两个路径是否在同一盘符（Windows 比较根，如 C:\ / D:\；非 Windows 比较根路径）。 */
    private fun sameDrive(a: File, b: File): Boolean {
        val ra = a.absoluteFile.toPath().root
        val rb = b.absoluteFile.toPath().root
        return ra != null && ra == rb
    }

    private fun parseQuery(q: String): Map<String, String> =
        net.xinkong.furagent.shared.util.HttpQuery.parse(q)

    private fun respond(ex: HttpExchange, code: Int, body: String, contentType: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", contentType)
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    // 统一到 shared/util/Format.formatFileSize
    private fun formatSize(bytes: Long): String =
        net.xinkong.furagent.shared.util.formatFileSize(bytes)
}