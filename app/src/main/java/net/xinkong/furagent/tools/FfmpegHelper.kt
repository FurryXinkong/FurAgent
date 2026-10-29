package net.xinkong.furagent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import net.xinkong.furagent.core.AgentLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * FFmpeg 执行引擎（外部二进制版）。
 *
 * 内置 ffmpeg-kit 已移除（APK 不再打包 native 库，体积和维护成本都不划算）。
 * 现在统一调用设备上已有的 ffmpeg / ffprobe 可执行文件：
 *   - 优先用 [ffmpegPath] / [ffprobePath] 指定的路径（用户可在设置里配）
 *   - 否则在常见目录里找（/system/bin、/data/local/tmp、Termux 的 usr/bin 等）
 *
 * 设计原则：
 *  - 所有外部进程调用都捕获 Throwable，失败变成错误字符串而不是崩溃
 *  - 同步阻塞调用放在 Dispatchers.IO 上，不卡主线程
 *  - 用 Semaphore 限制并发，避免同时跑多个重编码把手机烤了
 *  - 超时用 Process.destroyForcibly() 强制终止
 */
object FfmpegHelper {

    private val semaphore = Semaphore(2)
    private val sessionCounter = AtomicLong(0)

    /** 用户配置的 ffmpeg 路径（空串 = 自动探测）。由设置页写入。 */
    @Volatile var ffmpegPath: String = ""

    /** 用户配置的 ffprobe 路径（空串 = 自动探测）。 */
    @Volatile var ffprobePath: String = ""

    /** 自动探测的候选目录。 */
    private val SEARCH_DIRS = listOf(
        "/system/bin",
        "/system/xbin",
        "/vendor/bin",
        "/data/local/tmp",
        "/data/data/com.termux/files/usr/bin",
        "/sdcard/FurAgent/bin"
    )

    // 探测结果缓存，miss 有 TTL，避免首次失败后永久禁用
    @Volatile private var ffmpegResolved: String? = null
    @Volatile private var ffprobeResolved: String? = null
    @Volatile private var availabilityMissAt: Long = 0L
    private const val AVAILABILITY_MISS_TTL_MS = 30_000L

    data class FfmpegResult(
        val success: Boolean,
        val output: String,
        val durationMs: Long = 0,
        val sessionId: Long = 0
    )

    @Volatile
    var totalExecutions: Long = 0
        private set

    @Volatile
    var totalDurationMs: Long = 0
        private set

    /** 解析 ffmpeg 可执行文件路径，找不到返回 null。 */
    private fun resolveFfmpeg(): String? {
        ffmpegPath.trim().takeIf { it.isNotBlank() }?.let { p ->
            if (File(p).canExecute()) return p
        }
        ffmpegResolved?.let { if (File(it).canExecute()) return it }
        for (dir in SEARCH_DIRS) {
            val f = File(dir, "ffmpeg")
            if (f.canExecute()) {
                ffmpegResolved = f.absolutePath
                return f.absolutePath
            }
        }
        return null
    }

    /** 解析 ffprobe 可执行文件路径，找不到返回 null。 */
    private fun resolveFfprobe(): String? {
        ffprobePath.trim().takeIf { it.isNotBlank() }?.let { p ->
            if (File(p).canExecute()) return p
        }
        ffprobeResolved?.let { if (File(it).canExecute()) return it }
        for (dir in SEARCH_DIRS) {
            val f = File(dir, "ffprobe")
            if (f.canExecute()) {
                ffprobeResolved = f.absolutePath
                return f.absolutePath
            }
        }
        return null
    }

    fun isAvailable(): Boolean {
        val now = System.currentTimeMillis()
        if (availabilityMissAt > 0 && now - availabilityMissAt < AVAILABILITY_MISS_TTL_MS) {
            return false
        }
        val bin = resolveFfmpeg()
        val ok = bin != null
        if (!ok) availabilityMissAt = now
        AgentLogger.log("SYS", "FFmpeg 可用性检查：$ok（路径：${bin ?: "未找到"}）")
        return ok
    }

    /** 当前使用的 ffmpeg 路径，供 UI 展示。 */
    fun currentFfmpegPath(): String = resolveFfmpeg() ?: ""

    /** 当前使用的 ffprobe 路径，供 UI 展示。 */
    fun currentFfprobePath(): String = resolveFfprobe() ?: ""

    /**
     * 执行一次 FFmpeg。到 [timeoutSeconds] 还没完成则强制 kill。
     */
    suspend fun execute(
        args: Array<String>,
        timeoutSeconds: Int = 600
    ): FfmpegResult = withContext(Dispatchers.IO) {
        val bin = resolveFfmpeg()
            ?: return@withContext FfmpegResult(
                false,
                "FFmpeg 不可用：未找到 ffmpeg 可执行文件。\n" +
                        "请在设置里指定 ffmpeg 路径，或把 ffmpeg 放到 /data/local/tmp 等目录。"
            )

        val safeTimeout = timeoutSeconds.coerceIn(1, 24 * 3600)

        semaphore.withPermit {
            val startTime = System.currentTimeMillis()
            AgentLogger.log("FFMPEG", "开始：${args.joinToString(" ").take(500)}")

            val process = try {
                ProcessBuilder(listOf(bin) + args.toList())
                    .redirectErrorStream(true)
                    .start()
            } catch (t: Throwable) {
                val elapsed = System.currentTimeMillis() - startTime
                AgentLogger.log(
                    "ERR",
                    "FFmpeg 启动失败：${t.javaClass.name}: ${t.message}"
                )
                return@withPermit FfmpegResult(
                    false,
                    "启动失败：${t.javaClass.simpleName}: ${t.message ?: "未知错误"}",
                    elapsed
                )
            }

            val sid = sessionCounter.incrementAndGet()

            // 后台读输出，避免管道缓冲区满导致进程阻塞
            val outputBuf = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().use { r ->
                        val chunk = CharArray(4096)
                        while (true) {
                            val n = r.read(chunk)
                            if (n <= 0) break
                            synchronized(outputBuf) {
                                // 只保留尾部，避免长任务把内存吃满
                                outputBuf.append(chunk, 0, n)
                                if (outputBuf.length > 200_000) {
                                    outputBuf.delete(0, outputBuf.length - 100_000)
                                }
                            }
                        }
                    }
                } catch (_: Throwable) { }
            }
            reader.isDaemon = true
            reader.start()

            val finished = try {
                process.waitFor(safeTimeout.toLong(), TimeUnit.SECONDS)
            } catch (t: Throwable) {
                AgentLogger.log("ERR", "FFmpeg 等待异常：${t.javaClass.simpleName}: ${t.message}")
                false
            }

            val elapsed = System.currentTimeMillis() - startTime

            if (!finished) {
                try {
                    process.destroyForcibly()
                    AgentLogger.log("FFMPEG", "执行超时（${safeTimeout}s），已强制终止")
                } catch (t: Throwable) {
                    AgentLogger.log("ERR", "强制终止失败：${t.javaClass.simpleName}: ${t.message}")
                }
                return@withPermit FfmpegResult(
                    false,
                    "执行超时（${safeTimeout}s），已强制终止",
                    elapsed,
                    sid
                )
            }

            val exitCode = try { process.exitValue() } catch (_: Throwable) { -1 }
            val success = exitCode == 0
            val logs = synchronized(outputBuf) { outputBuf.toString() }

            val output = if (success) {
                "成功"
            } else {
                buildString {
                    append("失败（返回码 $exitCode）\n")
                    append(logs.takeLast(2500))
                }
            }

            AgentLogger.log(
                "FFMPEG",
                "结束：${if (success) "成功" else "失败"}，耗时 ${elapsed}ms"
            )

            totalExecutions++
            totalDurationMs += elapsed

            FfmpegResult(
                success = success,
                output = output,
                durationMs = elapsed,
                sessionId = sid
            )
        }
    }

    /**
     * 取消所有正在运行的会话。
     *
     * 外部进程模式下没有全局 session 表，这里只做日志占位。
     * 真正的终止由 [execute] 内部的超时 destroyForcibly 负责。
     */
    fun cancelAll() {
        AgentLogger.log("FFMPEG", "cancelAll：外部进程模式无全局会话表，依赖超时终止")
    }

    // 媒体信息探测（用 ffprobe 输出 JSON）

    suspend fun probeRawJson(path: String): String = withContext(Dispatchers.IO) {
        val bin = resolveFfprobe()
            ?: return@withContext """{"error":"未找到 ffprobe 可执行文件"}"""
        try {
            if (!File(path).exists()) {
                return@withContext """{"error":"文件不存在"}"""
            }
            val process = ProcessBuilder(
                bin, "-v", "quiet", "-print_format", "json",
                "-show_format", "-show_streams", path
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(60, TimeUnit.SECONDS)

            if (output.isBlank()) {
                return@withContext """{"error":"ffprobe 无输出"}"""
            }
            val jsonStart = output.indexOf('{')
            if (jsonStart < 0) {
                return@withContext """{"error":"ffprobe 输出不是 JSON"}"""
            }
            val json = output.substring(jsonStart).trim()
            try {
                JSONObject(json)
                json
            } catch (_: Throwable) {
                """{"error":"ffprobe 输出 JSON 解析失败"}"""
            }
        } catch (t: Throwable) {
            """{"error":"${esc(t.message ?: "未知错误")}"}"""
        }
    }

    suspend fun probeJson(path: String): String = probeRawJson(path)

    suspend fun probe(path: String): String = withContext(Dispatchers.IO) {
        try {
            if (!File(path).exists()) return@withContext "失败：文件不存在 $path"
            val raw = probeRawJson(path)
            val root = try {
                JSONObject(raw)
            } catch (_: Throwable) {
                return@withContext "读取媒体信息失败：ffprobe 输出无法解析"
            }
            if (root.has("error")) {
                return@withContext "读取媒体信息失败：${root.optString("error")}"
            }
            formatProbeText(path, root)
        } catch (t: Throwable) {
            "读取媒体信息失败：${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun formatProbeText(path: String, root: JSONObject): String {
        val format = root.optJSONObject("format") ?: JSONObject()
        val streams = root.optJSONArray("streams") ?: JSONArray()

        val sb = StringBuilder()
        sb.append("文件：$path\n")
        sb.append("格式：${format.optString("format_long_name", format.optString("format_name", "未知"))}\n")
        sb.append("时长：${format.optString("duration", "未知")} 秒\n")
        sb.append("大小：${format.optString("size", "未知")} 字节\n")
        sb.append("总比特率：${format.optString("bit_rate", "未知")}\n")

        for (i in 0 until streams.length()) {
            val s = streams.optJSONObject(i) ?: continue
            val type = s.optString("codec_type", "")
            val codec = s.optString("codec_name", "")
            val codecLong = s.optString("codec_long_name", "")
            val width = s.optLong("width", 0L)
            val height = s.optLong("height", 0L)
            val rFrameRate = s.optString("r_frame_rate", "")
            val avgFrameRate = s.optString("avg_frame_rate", "")
            val pixelFormat = s.optString("pix_fmt", "")
            val sampleRate = s.optString("sample_rate", "")
            val channels = s.optString("channels", "")
            val channelLayout = s.optString("channel_layout", "")
            val bitrate = s.optString("bit_rate", "")
            val sampleFormat = s.optString("sample_fmt", "")

            sb.append("\n流 #${i + 1}：\n")
            sb.append("  类型：${type.ifBlank { "未知" }}\n")
            if (codec.isNotBlank()) sb.append("  编解码器：$codec\n")
            if (codecLong.isNotBlank()) sb.append("  完整编解码器：$codecLong\n")
            if (width > 0 && height > 0) {
                sb.append("  分辨率：${width}x${height}\n")
                if (width <= Int.MAX_VALUE && height <= Int.MAX_VALUE) {
                    val g = gcd(width.toInt(), height.toInt())
                    if (g > 0) sb.append("  宽高比：${width / g}:${height / g}\n")
                }
            }
            if (rFrameRate.isNotBlank()) {
                val fps = fractionToFps(rFrameRate)
                sb.append("  帧率：$fps（$rFrameRate）\n")
            }
            if (avgFrameRate.isNotBlank() && avgFrameRate != rFrameRate) {
                val fps = fractionToFps(avgFrameRate)
                sb.append("  平均帧率：$fps\n")
            }
            if (pixelFormat.isNotBlank()) sb.append("  像素格式：$pixelFormat\n")
            if (sampleRate.isNotBlank()) sb.append("  采样率：$sampleRate Hz\n")
            if (channels.isNotBlank()) sb.append("  声道数：$channels\n")
            if (channelLayout.isNotBlank()) sb.append("  声道布局：$channelLayout\n")
            if (sampleFormat.isNotBlank()) sb.append("  采样格式：$sampleFormat\n")
            if (bitrate.isNotBlank()) sb.append("  比特率：$bitrate\n")
        }
        return sb.toString().trim()
    }

    private fun fractionToFps(frac: String): String {
        return try {
            val parts = frac.split('/')
            if (parts.size == 2) {
                val num = parts[0].toDouble()
                val den = parts[1].toDouble()
                if (den != 0.0) {
                    String.format(java.util.Locale.US, "%.2f", num / den)
                } else frac
            } else frac
        } catch (_: Throwable) { frac }
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val t = y
            y = x % y
            x = t
        }
        return x
    }

    private fun esc(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    }

    // 能力查询

    /** 跑一次 ffmpeg 并返回输出（用于 -version / -codecs 等查询）。 */
    private suspend fun runForOutput(args: Array<String>): String = withContext(Dispatchers.IO) {
        val bin = resolveFfmpeg() ?: return@withContext "FFmpeg 不可用：未找到 ffmpeg 可执行文件"
        try {
            val process = ProcessBuilder(listOf(bin) + args.toList())
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(30, TimeUnit.SECONDS)
            out
        } catch (t: Throwable) {
            "执行失败：${t.javaClass.simpleName}: ${t.message}"
        }
    }

    suspend fun version(): String {
        val out = runForOutput(arrayOf("-version"))
        return out.lines().take(6).joinToString("\n")
    }

    suspend fun listCodecs(filter: String = ""): String {
        val logs = runForOutput(arrayOf("-codecs"))
        return if (filter.isBlank()) {
            logs.take(8000)
        } else {
            logs.lines().filter { line ->
                when (filter.lowercase()) {
                    "video" -> line.startsWith(" V")
                    "audio" -> line.startsWith(" A")
                    "subtitle" -> line.startsWith(" S")
                    else -> true
                }
            }.take(300).joinToString("\n")
        }
    }

    suspend fun listFormats(): String {
        val logs = runForOutput(arrayOf("-formats"))
        return logs.lines().filter { line ->
            line.startsWith(" D") || line.startsWith("  E") || line.startsWith(" DE")
        }.take(300).joinToString("\n")
    }

    suspend fun listHwAccels(): String {
        val logs = runForOutput(arrayOf("-hwaccels"))
        val lines = logs.lines().filter {
            it.isNotBlank() && !it.startsWith("Hardware") && !it.startsWith("ffmpeg")
        }
        return if (lines.isEmpty()) "无硬件加速支持" else lines.joinToString("\n")
    }
}
