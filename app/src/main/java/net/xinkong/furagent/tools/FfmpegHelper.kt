package net.xinkong.furagent.tools

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.xinkong.furagent.core.AgentLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * FFmpeg 执行引擎（保守版）。
 *
 * 设计原则：
 *  - 不使用 async + Statistics 回调，避免 fork 之间 API 差异导致崩溃
 *  - 捕获 Throwable，把 UnsatisfiedLinkError / NoClassDefFoundError 都变成错误字符串
 *  - 所有 native 调用前先检查 isAvailable()，失败时立即返回而不是崩溃
 *  - 同步阻塞调用放在 Dispatchers.IO 上，不卡主线程
 *
 * 【优化 29】用 kotlinx.coroutines.sync.Semaphore.withPermit 挂起等待，不阻塞 IO 线程。
 *
 * 【P1 修复】原实现用同步 `FFmpegKit.executeWithArguments`，导致
 * `timeoutSeconds` 参数从头到尾没被用过——用户设 60s 也得等 FFmpeg 自己跑完。
 * 现在改成 `executeWithArgumentsAsync` + `CompletableDeferred` + `withTimeoutOrNull`，
 * 超时后 `FFmpegKit.cancel(sessionId)` 强制终止。
 */
object FfmpegHelper {

    private val semaphore = Semaphore(2)
    private val sessionCounter = AtomicLong(0)

    // miss 有 TTL，避免首次探测失败后永久禁用
    @Volatile private var availabilityChecked: Boolean? = null
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

    fun isAvailable(): Boolean {
        availabilityChecked?.let { return it }
        val now = System.currentTimeMillis()
        if (availabilityMissAt > 0 && now - availabilityMissAt < AVAILABILITY_MISS_TTL_MS) {
            return false
        }
        val ok = try {
            val session = FFmpegKit.execute("-version")
            ReturnCode.isSuccess(session.returnCode)
        } catch (t: Throwable) {
            AgentLogger.log(
                "ERR",
                "FFmpeg 不可用：${t.javaClass.simpleName}: ${t.message}"
            )
            false
        }
        if (ok) {
            availabilityChecked = true
        } else {
            availabilityMissAt = now
        }
        AgentLogger.log("SYS", "FFmpeg 可用性检查：$ok")
        return ok
    }

    /**
     * 执行一次 FFmpeg。到 [timeoutSeconds] 还没完成则强制 cancel。
     *
     * 【P1 修复】原实现是 `FFmpegKit.executeWithArguments(args)` 同步阻塞，
     * timeoutSeconds 完全没用。现在：
     *   1. `executeWithArgumentsAsync` 立即返回一个 session
     *   2. callback 里 complete 一个 CompletableDeferred
     *   3. 主协程 `withTimeoutOrNull` 等 deferred
     *   4. 超时 → `FFmpegKit.cancel(session.sessionId)` → 返回失败
     */
    suspend fun execute(
        args: Array<String>,
        timeoutSeconds: Int = 600
    ): FfmpegResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext FfmpegResult(
                false,
                "FFmpeg 不可用：native 库未加载成功。请确认 APK 里包含 arm64-v8a 的 libffmpegkit.so"
            )
        }

        val safeTimeout = timeoutSeconds.coerceIn(1, 24 * 3600)

        // 挂起等待配额，不阻塞 IO 线程
        semaphore.withPermit {
            val startTime = System.currentTimeMillis()
            val completed = CompletableDeferred<FFmpegSession>()

            AgentLogger.log("FFMPEG", "开始：${args.joinToString(" ").take(500)}")

            val session: FFmpegSession? = try {
                FFmpegKit.executeWithArgumentsAsync(
                    args,
                    { s ->
                        if (!completed.isCompleted) completed.complete(s)
                    },
                    null,   // logCallback
                    null    // statisticsCallback
                )
            } catch (t: Throwable) {
                val elapsed = System.currentTimeMillis() - startTime
                AgentLogger.log(
                    "ERR",
                    "FFmpeg 启动崩溃：${t.javaClass.name}: ${t.message}\n" +
                            t.stackTrace.take(8).joinToString("\n") { "  at $it" }
                )
                return@withPermit FfmpegResult(
                    false,
                    "执行崩溃：${t.javaClass.simpleName}: ${t.message ?: "未知错误"}",
                    elapsed
                )
            }

            sessionCounter.incrementAndGet()

            val sid: Long = try { session!!.sessionId } catch (_: Throwable) { 0L }

            // 等完成或超时
            val completedSession = try {
                withTimeoutOrNull((safeTimeout * 1000L).milliseconds) { completed.await() }
            } catch (t: Throwable) {
                // withTimeoutOrNull 本身不该抛，但 callback 里如果抛了会传播到这里
                val elapsed = System.currentTimeMillis() - startTime
                AgentLogger.log(
                    "ERR",
                    "FFmpeg 等待异常：${t.javaClass.simpleName}: ${t.message}"
                )
                null
            }

            if (completedSession == null) {
                // 超时（或等待异常）→ 强制 cancel
                if (sid > 0) {
                    try {
                        FFmpegKit.cancel(sid)
                        AgentLogger.log("FFMPEG", "已发送 cancel（sessionId=$sid）")
                    } catch (t: Throwable) {
                        AgentLogger.log(
                            "ERR",
                            "cancel 失败（sessionId=$sid）：${t.javaClass.simpleName}: ${t.message}"
                        )
                    }
                }
                val elapsed = System.currentTimeMillis() - startTime
                AgentLogger.log("FFMPEG", "执行超时（${safeTimeout}s），已强制终止")
                return@withPermit FfmpegResult(
                    false,
                    "执行超时（${safeTimeout}s），已强制终止",
                    elapsed,
                    sid
                )
            }

            val elapsed = System.currentTimeMillis() - startTime
            val rc = completedSession.returnCode
            val success = ReturnCode.isSuccess(rc)

            val output = if (success) {
                "成功"
            } else {
                val logs = try { completedSession.allLogsAsString ?: "" } catch (_: Throwable) { "" }
                val failStack = try { completedSession.failStackTrace ?: "" } catch (_: Throwable) { "" }
                buildString {
                    append("失败（返回码 ${rc?.value ?: -1}）\n")
                    append(logs.takeLast(2500))
                    if (failStack.isNotBlank()) {
                        append("\n调用栈：")
                        append(failStack.take(500))
                    }
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

    /** 取消所有正在运行的会话。 */
    fun cancelAll() {
        try {
            FFmpegKit.cancel()
            AgentLogger.log("FFMPEG", "已取消全部会话")
        } catch (t: Throwable) {
            AgentLogger.log("ERR", "取消 FFmpeg 失败：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ============================================================
    // 媒体信息探测（用 ffprobe 输出 JSON，绕开 StreamInformation）
    // ============================================================

    suspend fun probeRawJson(path: String): String = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext """{"error":"FFmpeg native 库未加载"}"""
        }
        try {
            if (!File(path).exists()) {
                return@withContext """{"error":"文件不存在"}"""
            }
            val safePath = path.replace("\"", "\\\"")
            val cmd = "-v quiet -print_format json -show_format -show_streams \"$safePath\""
            val session = FFprobeKit.execute(cmd)
            val output = session.allLogsAsString ?: ""
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

    // ============================================================
    // 能力查询
    // ============================================================

    suspend fun version(): String = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext "FFmpeg native 库未加载"
        try {
            val session = FFmpegKit.execute("-version")
            val logs = session.allLogsAsString ?: ""
            logs.lines().take(6).joinToString("\n")
        } catch (t: Throwable) {
            "获取版本失败：${t.javaClass.simpleName}: ${t.message}"
        }
    }

    suspend fun listCodecs(filter: String = ""): String = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext "FFmpeg native 库未加载"
        try {
            val session = FFmpegKit.executeWithArguments(arrayOf("-codecs"))
            val logs = session.allLogsAsString ?: ""
            if (filter.isBlank()) {
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
        } catch (t: Throwable) {
            "获取编解码器列表失败：${t.javaClass.simpleName}: ${t.message}"
        }
    }

    suspend fun listFormats(): String = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext "FFmpeg native 库未加载"
        try {
            val session = FFmpegKit.executeWithArguments(arrayOf("-formats"))
            val logs = session.allLogsAsString ?: ""
            logs.lines().filter { line ->
                line.startsWith(" D") || line.startsWith("  E") || line.startsWith(" DE")
            }.take(300).joinToString("\n")
        } catch (t: Throwable) {
            "获取格式列表失败：${t.javaClass.simpleName}: ${t.message}"
        }
    }

    suspend fun listHwAccels(): String = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext "FFmpeg native 库未加载"
        try {
            val session = FFmpegKit.executeWithArguments(arrayOf("-hwaccels"))
            val logs = session.allLogsAsString ?: ""
            val lines = logs.lines().filter {
                it.isNotBlank() && !it.startsWith("Hardware") && !it.startsWith("ffmpeg")
            }
            if (lines.isEmpty()) "无硬件加速支持" else lines.joinToString("\n")
        } catch (t: Throwable) {
            "获取硬件加速列表失败：${t.javaClass.simpleName}: ${t.message}"
        }
    }
}