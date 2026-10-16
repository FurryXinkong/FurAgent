package net.xinkong.furagent.shared.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 跨端下载引擎（桌面端 + 手机端共用）。
 *
 * 只依赖 OkHttp + java.io，**不依赖任何平台 UI / 任务运行时 / 确认机制**。
 * 调用方负责：路径解析、用户确认、进度展示、取消信号。
 *
 * 能力：
 *   1. 多线程分片下载（threads 参数，由 AI 判断）
 *   2. 断点续传（resume=true，复用已下载分片）
 *   3. 代理（proxy 参数，走梯子；也读环境变量 HTTP_PROXY/HTTPS_PROXY）
 *   4. 进度回调（onProgress）
 *   5. 取消（isCancelled 回调）
 *
 * 线程数策略（工具层引导 AI 判断，引擎只做兜底）：
 *   - 默认 1（单线程流式，最稳）
 *   - 服务端不支持 Range（Accept-Ranges 缺失）时自动降级为单线程
 *   - 文件小于 1MB 时自动降级为单线程（分片开销大于收益）
 */
object DownloadEngine {

    /** 单文件大小上限（10GB），防止误下超大文件。 */
    const val MAX_DOWNLOAD_BYTES = 10L * 1024 * 1024 * 1024

    /** 小于此大小不分片（分片开销大于收益）。 */
    private const val MIN_MULTI_THREAD_BYTES = 1L * 1024 * 1024

    /** 分片最小粒度（避免线程数过多导致每片过小）。 */
    private const val MIN_CHUNK_BYTES = 512L * 1024

    /** 进度回调节流间隔（毫秒）。 */
    private const val PROGRESS_THROTTLE_MS = 200L

    /**
     * 下载结果。
     *
     * @param ok              是否成功
     * @param message         失败原因（ok=true 时为空）
     * @param bytes           实际写入字节数
     * @param threadsUsed     实际使用的线程数
     * @param cancelled       是否被取消
     */
    data class Result(
        val ok: Boolean,
        val message: String = "",
        val bytes: Long = 0L,
        val threadsUsed: Int = 1,
        val cancelled: Boolean = false
    )

    /**
     * 下载文件。
     *
     * @param url         下载地址
     * @param target      目标文件（父目录会自动创建）
     * @param threads     期望线程数（1 = 单线程）
     * @param proxy       代理地址，如 http://127.0.0.1:7890；null 时读环境变量
     * @param resume      断点续传
     * @param onProgress  进度回调 (已下载字节, 总字节)；总字节未知时为 -1
     * @param isCancelled 取消检查；返回 true 时尽快中止
     * @param onCallCreated 每次发起 HTTP 请求时回调（调用方可注册进自己的取消机制，
     *                      以便在阻塞的 execute() 期间也能被 call.cancel() 打断）
     */
    suspend fun download(
        url: String,
        target: File,
        threads: Int = 1,
        proxy: String? = null,
        resume: Boolean = false,
        onProgress: ((Long, Long) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
        onCallCreated: ((okhttp3.Call) -> Unit)? = null
    ): Result = withContext(Dispatchers.IO) {
        val client = buildClient(proxy)
        try {
            // 1. 探测：HEAD 拿 Content-Length + Accept-Ranges
            val probe = probe(client, url)
                ?: return@withContext Result(false, "无法访问 URL（探测请求失败）：$url")

            val total = probe.length
            if (total > MAX_DOWNLOAD_BYTES) {
                return@withContext Result(
                    false,
                    "文件大小 ${formatFileSize(total)} 超过上限 ${formatFileSize(MAX_DOWNLOAD_BYTES)}"
                )
            }

            // 2. 决定实际线程数
            val effectiveThreads = when {
                threads <= 1 -> 1
                !probe.acceptRanges -> 1
                total in 1 until MIN_MULTI_THREAD_BYTES -> 1
                total <= 0 -> 1
                else -> {
                    val maxByChunk = (total / MIN_CHUNK_BYTES).toInt().coerceAtLeast(1)
                    threads.coerceAtMost(maxByChunk)
                }
            }

            target.parentFile?.mkdirs()

            val err = if (effectiveThreads <= 1) {
                downloadSingle(client, url, target, total, resume, onProgress, isCancelled, onCallCreated)
            } else {
                downloadMulti(client, url, target, total, effectiveThreads, resume, onProgress, isCancelled, onCallCreated)
            }

            if (isCancelled()) {
                return@withContext Result(false, "下载已取消", cancelled = true)
            }
            if (err != null) {
                return@withContext Result(false, err, threadsUsed = effectiveThreads)
            }

            Result(true, bytes = target.length(), threadsUsed = effectiveThreads)
        } catch (e: Exception) {
            Result(false, "下载失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ============================================================
    // HTTP 客户端
    // ============================================================

    private fun buildClient(proxyUrl: String?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)

        val effectiveProxy = proxyUrl?.trim()?.ifBlank { null }
            ?: System.getenv("HTTPS_PROXY")?.ifBlank { null }
            ?: System.getenv("https_proxy")?.ifBlank { null }
            ?: System.getenv("HTTP_PROXY")?.ifBlank { null }
            ?: System.getenv("http_proxy")?.ifBlank { null }

        if (effectiveProxy != null) {
            try {
                val uri = URI(effectiveProxy)
                val host = uri.host ?: throw IllegalArgumentException("代理地址缺少主机名")
                val port = if (uri.port > 0) uri.port else 8080
                builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
            } catch (_: Exception) {
                // 代理地址非法则忽略，走直连
            }
        }
        return builder.build()
    }

    // ============================================================
    // 探测
    // ============================================================

    private data class Probe(val length: Long, val acceptRanges: Boolean)

    private fun probe(client: OkHttpClient, url: String): Probe? {
        return try {
            val req = Request.Builder().url(url).head().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return probeByGet(client, url)
                }
                val len = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                val ranges = resp.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
                Probe(len, ranges)
            }
        } catch (_: Exception) {
            probeByGet(client, url)
        }
    }

    private fun probeByGet(client: OkHttpClient, url: String): Probe? {
        return try {
            val req = Request.Builder().url(url).header("Range", "bytes=0-0").build()
            client.newCall(req).execute().use { resp ->
                val contentRange = resp.header("Content-Range")
                val len = if (contentRange != null) {
                    contentRange.substringAfter('/').trim().toLongOrNull() ?: -1L
                } else {
                    resp.header("Content-Length")?.toLongOrNull() ?: -1L
                }
                val ranges = resp.code == 206 ||
                        resp.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
                Probe(len, ranges)
            }
        } catch (_: Exception) {
            null
        }
    }

    // ============================================================
    // 单线程下载
    // ============================================================

    /** @return null 表示成功，否则返回错误信息。 */
    private fun downloadSingle(
        client: OkHttpClient,
        url: String,
        target: File,
        total: Long,
        resume: Boolean,
        onProgress: ((Long, Long) -> Unit)?,
        isCancelled: () -> Boolean,
        onCallCreated: ((okhttp3.Call) -> Unit)?
    ): String? {
        val existing = if (resume && target.exists()) target.length() else 0L
        if (resume && existing > 0 && total > 0 && existing >= total) {
            return null // 已完整
        }

        val reqBuilder = Request.Builder().url(url)
        if (existing > 0) reqBuilder.header("Range", "bytes=$existing-")

        val call = client.newCall(reqBuilder.build())
        onCallCreated?.invoke(call)
        val resp = call.execute()
        resp.use {
            if (!it.isSuccessful) return "HTTP ${it.code}"

            val append = existing > 0 && it.code == 206
            var written = if (append) existing else 0L
            val lastReport = AtomicLong(0)

            it.body.byteStream().use { input ->
                FileOutputStream(target, append).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) return "下载已取消"
                        val n = input.read(buf)
                        if (n <= 0) break
                        written += n
                        if (written > MAX_DOWNLOAD_BYTES) {
                            return "下载超过上限 ${formatFileSize(MAX_DOWNLOAD_BYTES)}，已中止"
                        }
                        output.write(buf, 0, n)

                        val now = System.currentTimeMillis()
                        if (onProgress != null && now - lastReport.get() > PROGRESS_THROTTLE_MS) {
                            lastReport.set(now)
                            onProgress(written, total)
                        }
                    }
                }
            }
        }
        return null
    }

    // ============================================================
    // 多线程分片下载
    // ============================================================

    /** @return null 表示成功，否则返回错误信息。 */
    private suspend fun downloadMulti(
        client: OkHttpClient,
        url: String,
        target: File,
        total: Long,
        threads: Int,
        resume: Boolean,
        onProgress: ((Long, Long) -> Unit)?,
        isCancelled: () -> Boolean,
        onCallCreated: ((okhttp3.Call) -> Unit)?
    ): String? = coroutineScope {
        val chunkSize = (total + threads - 1) / threads
        val partDir = File(target.parentFile, ".${target.name}.parts")
        partDir.mkdirs()

        val downloaded = AtomicLong(0)
        val lastReport = AtomicLong(0)

        if (resume) {
            for (i in 0 until threads) {
                val pf = File(partDir, "part_$i")
                if (pf.exists()) downloaded.addAndGet(pf.length())
            }
        }

        val jobs = (0 until threads).map { i ->
            async(Dispatchers.IO) {
                val start = i * chunkSize
                val end = minOf(start + chunkSize - 1, total - 1)
                if (start > end) return@async null

                val partFile = File(partDir, "part_$i")
                val already = if (resume && partFile.exists()) partFile.length() else 0L
                val expected = end - start + 1
                if (already >= expected) return@async null // 该片已完成

                val reqStart = start + already
                val req = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=$reqStart-$end")
                    .build()

                try {
                    val call = client.newCall(req)
                    onCallCreated?.invoke(call)
                    call.execute().use { resp ->
                        if (resp.code != 206 && resp.code != 200) {
                            return@async "分片 $i HTTP ${resp.code}"
                        }
                        resp.body.byteStream().use { input ->
                            FileOutputStream(partFile, already > 0).use { output ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    if (isCancelled()) return@use
                                    val n = input.read(buf)
                                    if (n <= 0) break
                                    output.write(buf, 0, n)
                                    val cur = downloaded.addAndGet(n.toLong())
                                    val now = System.currentTimeMillis()
                                    if (onProgress != null && now - lastReport.get() > PROGRESS_THROTTLE_MS) {
                                        lastReport.set(now)
                                        onProgress(cur, total)
                                    }
                                }
                            }
                        }
                    }
                    null
                } catch (e: Exception) {
                    "分片 $i 失败：${e.message}"
                }
            }
        }

        val errors = jobs.awaitAll().filterNotNull()
        if (errors.isNotEmpty()) {
            return@coroutineScope errors.first()
        }
        if (isCancelled()) {
            return@coroutineScope "下载已取消"
        }

        // 合并分片
        try {
            FileOutputStream(target, false).use { out ->
                for (i in 0 until threads) {
                    val pf = File(partDir, "part_$i")
                    if (!pf.exists()) continue
                    pf.inputStream().use { it.copyTo(out, 64 * 1024) }
                }
            }
        } catch (e: Exception) {
            return@coroutineScope "合并分片失败：${e.message}"
        } finally {
            partDir.deleteRecursively()
        }

        null
    }

    // ============================================================
    // 工具方法
    // ============================================================

    /** 从 URL 猜文件名（去掉 query/fragment，URL 解码）。 */
    fun guessFileName(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val seg = path.trimEnd('/').substringAfterLast('/')
        val decoded = try {
            URLDecoder.decode(seg, "UTF-8")
        } catch (_: Exception) {
            seg
        }
        return if (decoded.isBlank() || !decoded.contains('.')) {
            "download_${System.currentTimeMillis()}"
        } else {
            decoded
        }
    }
}
