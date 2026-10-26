package net.xinkong.furagent.desktop.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.desktop.core.TaskRuntime
import net.xinkong.furagent.desktop.data.DesktopSettingsHolder
import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.shared.tools.ToolResult
import net.xinkong.furagent.shared.util.formatFileSize
import org.json.JSONObject
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object FfmpegTools {

    data class ToolDef(
        val name: String,
        val displayName: String,
        val description: String,
        val paramsSchema: String
    )

    val TOOLS: List<ToolDef> = listOf(
        ToolDef(
            name = "ffmpeg",
            displayName = "音视频处理",
            description = "FFmpeg 工具集。mode=run 执行任意 FFmpeg 命令（args 是完整参数，不含可执行文件名，例如 -i input.mp4 -c copy output.mp4）；mode=probe 读取媒体信息（path，可选 format=text/json）；mode=preset 使用预设快速处理（preset + input + output，可选 options）；mode=capabilities 查询能力（type=version/codecs/formats/hwaccels，可选 filter）。危险操作，需确认。换容器不换内容（mkv→mp4、提取音轨、裁剪不改码）必须用 -c copy，不要重编码。",
            paramsSchema = """{"type":"object","properties":{"mode":{"type":"string","enum":["run","probe","preset","capabilities"]},"args":{"type":"string"},"timeout":{"type":"integer"},"path":{"type":"string"},"format":{"type":"string"},"preset":{"type":"string"},"input":{"type":"string"},"output":{"type":"string"},"options":{"type":"object"},"type":{"type":"string"},"filter":{"type":"string"}},"required":["mode"]}"""
        )
    )

    val NAMES: Set<String> = TOOLS.map { it.name }.toSet()

    fun displayName(name: String): String =
        TOOLS.firstOrNull { it.name == name }?.displayName ?: name

    @Volatile private var cachedFfmpegPath: String? = null
    @Volatile private var ffmpegMissExpireAt: Long = 0L
    @Volatile private var cachedFfprobePath: String? = null
    @Volatile private var ffprobeMissExpireAt: Long = 0L

    private const val MISS_TTL_MS = 5_000L

    private val tempSeq = AtomicLong(0)

    private fun getFfmpegPath(): String? {
        val configured = DesktopSettingsHolder.current.toolPath("ffmpeg").trim()
        if (configured.isNotBlank()) {
            val f = File(configured)
            if (f.exists() && f.isFile && f.canExecute()) return configured
        }
        cachedFfmpegPath?.let { return it }
        val now = System.currentTimeMillis()
        if (now < ffmpegMissExpireAt) return null
        val p = findExecutable("ffmpeg")
        if (p != null) cachedFfmpegPath = p
        else ffmpegMissExpireAt = now + MISS_TTL_MS
        return p
    }

    private fun getFfprobePath(): String? {
        val configured = DesktopSettingsHolder.current.toolPath("ffmpeg").trim()
        if (configured.isNotBlank()) {
            val ffmpegFile = File(configured)
            val isWin = System.getProperty("os.name").lowercase().contains("win")
            val ffprobeName = if (isWin) "ffprobe.exe" else "ffprobe"
            val sibling = File(ffmpegFile.parentFile, ffprobeName)
            if (sibling.exists() && sibling.isFile && sibling.canExecute()) {
                return sibling.absolutePath
            }
        }
        cachedFfprobePath?.let { return it }
        val now = System.currentTimeMillis()
        if (now < ffprobeMissExpireAt) return null
        val p = findExecutable("ffprobe")
        if (p != null) cachedFfprobePath = p
        else ffprobeMissExpireAt = now + MISS_TTL_MS
        return p
    }

    fun clearCache() {
        cachedFfmpegPath = null
        ffmpegMissExpireAt = 0L
        cachedFfprobePath = null
        ffprobeMissExpireAt = 0L
    }

    private fun findExecutable(name: String): String? {
        val isWin = System.getProperty("os.name").lowercase().contains("win")
        val exeNames = if (isWin) listOf("$name.exe", name) else listOf(name)

        for (exe in exeNames) {
            try {
                val p = ProcessBuilder(exe, "-version").redirectErrorStream(true).start()
                val finished = p.waitFor(5, TimeUnit.SECONDS)
                if (finished && p.exitValue() == 0) {
                    return exe
                }
                p.destroyForcibly()
            } catch (_: Exception) { }
        }

        if (isWin) {
            val candidates = mutableListOf<String>()
            val localAppData = System.getenv("LOCALAPPDATA") ?: ""
            val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
            val programFilesX86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
            val userProfile = System.getProperty("user.home") ?: ""
            candidates.addAll(listOf(
                "$localAppData\\Microsoft\\WinGet\\Links\\ffmpeg.exe",
                "$userProfile\\scoop\\shims\\ffmpeg.exe",
                "C:\\ProgramData\\chocolatey\\bin\\ffmpeg.exe",
                "$programFiles\\ffmpeg\\bin\\ffmpeg.exe",
                "$programFilesX86\\ffmpeg\\bin\\ffmpeg.exe",
                "C:\\ffmpeg\\bin\\ffmpeg.exe"
            ))
            for (c in candidates) {
                val f = File(c)
                if (f.exists() && f.isFile) {
                    try {
                        val p = ProcessBuilder(f.absolutePath, "-version").redirectErrorStream(true).start()
                        val finished = p.waitFor(5, TimeUnit.SECONDS)
                        if (finished && p.exitValue() == 0) return f.absolutePath
                        p.destroyForcibly()
                    } catch (_: Exception) { }
                }
            }
        }

        val unixCandidates = listOf(
            "/usr/local/bin/$name",
            "/opt/homebrew/bin/$name",
            "/usr/bin/$name",
            "/bin/$name",
            "/snap/bin/$name"
        )
        for (c in unixCandidates) {
            val f = File(c)
            if (f.exists() && f.canExecute()) return c
        }

        return null
    }

    fun isAvailable(): Boolean = getFfmpegPath() != null

    private fun notInstalledMsg(): String = "FFmpeg 未安装或不在 PATH 中"

    suspend fun execute(name: String, args: JSONObject): ToolResult? {
        if (name != "ffmpeg") return null
        val mode = args.optString("mode", "").lowercase()
        return when (mode) {
            "run" -> doFfmpeg(args)
            "probe" -> doFfprobe(args)
            "preset" -> doFfmpegMedia(args)
            "capabilities" -> doCapabilities(args)
            else -> ToolResult.fail("mode 只能是 run / probe / preset / capabilities")
        }
    }

    private suspend fun doFfmpeg(args: JSONObject): ToolResult {
        val ffmpegPath = getFfmpegPath() ?: return ToolResult.fail(notInstalledMsg())
        val rawArgs = args.optString("args", "").trim()
        if (rawArgs.isBlank()) return ToolResult.fail("未提供 args")
        val timeoutSec = args.optInt("timeout", 1800).coerceIn(10, 7200)

        val argList = parseCommandLine(rawArgs)
        if (argList.isEmpty()) return ToolResult.fail("无法解析参数")

        val outPath = extractOutputPath(argList)
        if (outPath != null && File(outPath).exists()) {
            // 覆盖确认改用独立开关 requireConfirmFfmpegOverwrite
            if (DesktopSettingsHolder.current.requireConfirmFfmpegOverwrite) {
                val approved = net.xinkong.furagent.desktop.core.DesktopConfirmation.request(
                    "FFmpeg 覆盖确认",
                    "输出文件已存在：\n$outPath\n\n是否覆盖？"
                )
                if (!approved) return ToolResult.fail("用户已拒绝覆盖")
            }
        }

        val result = runFfmpeg(ffmpegPath, argList, timeoutSec)
        return if (result.success) {
            val outInfo = if (outPath != null && File(outPath).exists()) {
                "\n输出：$outPath（${formatFileSize(File(outPath).length())}）"
            } else ""
            ToolResult.ok("执行成功（耗时 ${result.durationMs / 1000}s）$outInfo")
        } else ToolResult.fail(result.output.take(1500))
    }

    private suspend fun doFfprobe(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val ffprobePath = getFfprobePath()
            ?: return@withContext ToolResult.fail("FFprobe 未安装或不在 PATH 中")
        val path = resolvePath(args.optString("path", "").trim())
            ?: return@withContext ToolResult.fail("路径无效")
        if (!File(path).exists()) return@withContext ToolResult.fail("文件不存在：$path")

        try {
            val raw = ffprobeJson(ffprobePath, path)
            val root = try { JSONObject(raw) } catch (e: Exception) {
                DesktopLogger.warn("Ffmpeg", "ffprobe JSON 解析失败：${e.message}")
                return@withContext ToolResult.fail("解析 ffprobe 输出失败")
            }
            if (root.has("error")) {
                return@withContext ToolResult.fail("读取失败：${root.optString("error")}")
            }
            ToolResult.ok(formatProbeText(path, root))
        } catch (e: Exception) {
            DesktopLogger.error("Ffmpeg", "doFfprobe 异常：${e.javaClass.simpleName}: ${e.message}")
            ToolResult.fail("读取失败：${e.message}")
        }
    }

    private fun ffprobeJson(ffprobePath: String, path: String): String {
        val cmd = listOf(
            ffprobePath, "-v", "quiet", "-print_format", "json",
            "-show_format", "-show_streams", path
        )
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val outFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            try { p.inputStream.bufferedReader(Charsets.UTF_8).readText() }
            catch (_: Exception) { "" }
        }
        val finished = p.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            p.destroyForcibly()
            DesktopLogger.warn("Ffmpeg", "ffprobe 超时（30s），已终止：$path")
            return ""
        }
        val out = try { outFuture.get(2, TimeUnit.SECONDS) } catch (_: Exception) { "" }
        val idx = out.indexOf('{')
        return if (idx >= 0) out.substring(idx).trim() else out.trim()
    }

    private fun formatProbeText(path: String, root: JSONObject): String {
        val sb = StringBuilder()
        val format = root.optJSONObject("format") ?: JSONObject()
        val streams = root.optJSONArray("streams") ?: return "无流信息"

        sb.append("文件：").append(path).append("\n")
        sb.append("格式：").append(format.optString("format_long_name",
            format.optString("format_name", "未知"))).append("\n")
        val dur = format.optString("duration", "")
        if (dur.isNotBlank()) {
            val d = dur.toDoubleOrNull() ?: 0.0
            sb.append("时长：").append(formatDurationText(d)).append("\n")
        }
        val size = format.optString("size", "")
        if (size.isNotBlank()) {
            sb.append("大小：").append(formatFileSize(size.toLongOrNull() ?: 0L)).append("\n")
        }
        val br = format.optString("bit_rate", "")
        if (br.isNotBlank()) {
            sb.append("总码率：").append(formatBitrate(br.toLongOrNull() ?: 0L)).append("\n")
        }

        for (i in 0 until streams.length()) {
            val s = streams.optJSONObject(i) ?: continue
            val type = s.optString("codec_type", "")
            val codec = s.optString("codec_long_name", s.optString("codec_name", ""))
            sb.append("\n流 #${i + 1}：").append(type).append("\n")
            if (codec.isNotBlank()) sb.append("  编解码器：").append(codec).append("\n")
            val w = s.optInt("width", 0)
            val h = s.optInt("height", 0)
            if (w > 0 && h > 0) sb.append("  分辨率：").append(w).append("x").append(h).append("\n")
            val fr = s.optString("r_frame_rate", "")
            if (fr.isNotBlank()) {
                val parts = fr.split('/')
                if (parts.size == 2) {
                    val fps = parts[0].toDoubleOrNull() ?: 0.0
                    val den = parts[1].toDoubleOrNull() ?: 1.0
                    if (den > 0) sb.append("  帧率：").append(String.format(Locale.US, "%.2f", fps / den)).append(" fps\n")
                }
            }
            val sr = s.optString("sample_rate", "")
            if (sr.isNotBlank()) sb.append("  采样率：").append(sr).append(" Hz\n")
            val ch = s.optString("channels", "")
            if (ch.isNotBlank()) sb.append("  声道：").append(ch).append("\n")
            val sbr = s.optString("bit_rate", "")
            if (sbr.isNotBlank()) {
                sb.append("  码率：").append(formatBitrate(sbr.toLongOrNull() ?: 0L)).append("\n")
            }
        }
        return sb.toString().trim()
    }

    private fun formatDurationText(sec: Double): String {
        val total = sec.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun formatBitrate(bps: Long): String = when {
        bps <= 0 -> "未知"
        bps < 1000 -> "$bps bps"
        bps < 1_000_000 -> String.format(Locale.US, "%.1f kbps", bps / 1000.0)
        else -> String.format(Locale.US, "%.2f Mbps", bps / 1_000_000.0)
    }

    private suspend fun doFfmpegMedia(args: JSONObject): ToolResult {
        val ffmpegPath = getFfmpegPath() ?: return ToolResult.fail(notInstalledMsg())
        val preset = args.optString("preset", "").trim()
        val inputRaw = args.optString("input", "").trim()
        val outputRaw = args.optString("output", "").trim()
        val opts = args.optJSONObject("options") ?: JSONObject()

        if (preset.isBlank()) return ToolResult.fail("未提供 preset")
        if (inputRaw.isBlank() || outputRaw.isBlank()) return ToolResult.fail("需要 input 和 output")

        val inputs = inputRaw.split(',').map { it.trim() }.filter { it.isNotBlank() }
        val resolvedInputs = inputs.mapNotNull { resolvePath(it) }
        if (resolvedInputs.size != inputs.size) return ToolResult.fail("部分输入路径无效")
        val output = resolvePath(outputRaw) ?: return ToolResult.fail("输出路径无效")

        var concatListFile: File? = null
        val inputArg = if (preset == "video_concat" || preset == "audio_concat") {
            val listFile = File(
                net.xinkong.furagent.desktop.data.DesktopPaths.tempDir(),
                "concat_${System.currentTimeMillis()}_${tempSeq.incrementAndGet()}.txt"
            )
            listFile.writeText(resolvedInputs.joinToString("\n") { "file '$it'" }, Charsets.UTF_8)
            concatListFile = listFile
            listFile.absolutePath
        } else resolvedInputs.firstOrNull() ?: return ToolResult.fail("无有效输入")

        try {
            val cmd = buildPresetArgs(preset, inputArg, output, opts, resolvedInputs)
                ?: return ToolResult.fail("未知预设 '$preset'。可用预设：video_* / audio_* / image_* 前缀。")

            val argList = parseCommandLine(cmd)
            if (File(output).exists()) {
                // 独立开关
                if (DesktopSettingsHolder.current.requireConfirmFfmpegOverwrite) {
                    val approved = net.xinkong.furagent.desktop.core.DesktopConfirmation.request(
                        "FFmpeg 覆盖确认",
                        "输出文件已存在：\n$output\n\n是否覆盖？"
                    )
                    if (!approved) return ToolResult.fail("用户已拒绝覆盖")
                }
            }

            val timeoutSec = opts.optInt("timeout", 1800).coerceIn(10, 7200)
            val result = runFfmpeg(ffmpegPath, argList, timeoutSec)
            return if (result.success) {
                val outInfo = if (File(output).exists()) "\n输出：$output（${formatFileSize(File(output).length())}）" else ""
                ToolResult.ok("处理成功（耗时 ${result.durationMs / 1000}s）$outInfo")
            } else ToolResult.fail(result.output.take(1500))
        } finally {
            try { concatListFile?.delete() } catch (_: Exception) { }
        }
    }

    private fun buildPresetArgs(
        preset: String,
        input: String,
        output: String,
        opts: JSONObject,
        inputs: List<String>
    ): String? {
        val crf = opts.optString("crf", "23")
        val presetName = opts.optString("preset", "veryfast")
        val bitrate = opts.optString("bitrate", "")
        val scale = opts.optString("scale", "")
        val speed = opts.optString("speed", "2.0")
        val start = opts.optString("start", "")
        val duration = opts.optString("duration", "")
        val fps = opts.optString("fps", "")
        val format = opts.optString("format", "")
        val volume = opts.optString("volume", "1.0")
        val loop = opts.optInt("loop_count", 0)

        val ss = if (start.isNotBlank()) "-ss $start " else ""
        val t = if (duration.isNotBlank()) "-t $duration " else ""

        val videoEncode = if (bitrate.isNotBlank())
            "-c:v libx264 -b:v $bitrate -preset $presetName"
        else "-c:v libx264 -crf $crf -preset $presetName"

        val audioEncode = "-c:a aac -b:a 192k"

        return when (preset) {
            "video_convert" -> "-i \"$input\" $videoEncode $audioEncode \"$output\""
            "video_compress" -> {
                val s = scale.ifBlank { "1280:-2" }
                "-i \"$input\" -vf scale=$s $videoEncode $audioEncode \"$output\""
            }
            "video_trim" -> "${ss}${t}-i \"$input\" -c copy \"$output\""
            "video_trim_reencode" -> "${ss}${t}-i \"$input\" $videoEncode $audioEncode \"$output\""
            "video_concat" -> "-f concat -safe 0 -i \"$input\" -c copy \"$output\""
            "video_speed" -> {
                val sp = speed.toDoubleOrNull() ?: 2.0
                val videoPts = 1.0 / sp
                val audioTempo = sp.coerceIn(0.5, 2.0)
                "-i \"$input\" -filter_complex \"[0:v]setpts=${videoPts}*PTS[v];[0:a]atempo=$audioTempo[a]\" " +
                        "-map \"[v]\" -map \"[a]\" $videoEncode $audioEncode \"$output\""
            }
            "video_resize" -> {
                val s = scale.ifBlank { "1280:-2" }
                "-i \"$input\" -vf scale=$s $videoEncode $audioEncode \"$output\""
            }
            "video_mute" -> "-i \"$input\" -an -c:v copy \"$output\""
            "video_extract_audio" -> {
                val fmt = format.ifBlank { "mp3" }
                when (fmt) {
                    "mp3" -> "-i \"$input\" -vn -c:a libmp3lame -b:a 192k \"$output\""
                    "aac", "m4a" -> "-i \"$input\" -vn -c:a aac -b:a 192k \"$output\""
                    "wav" -> "-i \"$input\" -vn -c:a pcm_s16le \"$output\""
                    "flac" -> "-i \"$input\" -vn -c:a flac \"$output\""
                    else -> "-i \"$input\" -vn -c:a copy \"$output\""
                }
            }
            "video_to_gif" -> {
                val s = scale.ifBlank { "480:-2" }
                val f = fps.ifBlank { "12" }
                "-i \"$input\" -vf \"fps=$f,scale=$s:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse\" -loop 0 \"$output\""
            }
            "video_extract_frames" -> {
                val f = fps.ifBlank { "1" }
                "${ss}-i \"$input\" -vf fps=$f \"$output\""
            }
            "video_extract_thumbnail" -> {
                val time = start.ifBlank { "00:00:01" }
                "-ss $time -i \"$input\" -vframes 1 -q:v 2 \"$output\""
            }
            "video_rotate" -> {
                val rot = opts.optString("rotate", "90")
                val filter = when (rot) {
                    "90" -> "transpose=1"
                    "180" -> "transpose=1,transpose=1"
                    "270" -> "transpose=2"
                    else -> return null
                }
                "-i \"$input\" -vf \"$filter\" $videoEncode $audioEncode \"$output\""
            }
            "video_loop" -> {
                val n = loop.coerceAtLeast(1)
                "-stream_loop $n -i \"$input\" -c copy \"$output\""
            }
            "audio_convert" -> {
                val fmt = format.ifBlank { "mp3" }
                when (fmt) {
                    "mp3" -> "-i \"$input\" -vn -c:a libmp3lame -b:a 192k \"$output\""
                    "aac", "m4a" -> "-i \"$input\" -vn -c:a aac -b:a 192k \"$output\""
                    "wav" -> "-i \"$input\" -vn -c:a pcm_s16le \"$output\""
                    "flac" -> "-i \"$input\" -vn -c:a flac \"$output\""
                    "ogg" -> "-i \"$input\" -vn -c:a libvorbis -q:a 6 \"$output\""
                    else -> "-i \"$input\" -vn -c:a copy \"$output\""
                }
            }
            "audio_trim" -> "${ss}${t}-i \"$input\" -c copy \"$output\""
            "audio_concat" -> "-f concat -safe 0 -i \"$input\" -c copy \"$output\""
            "audio_speed" -> {
                val sp = speed.toDoubleOrNull() ?: 2.0
                "-i \"$input\" -filter:a \"atempo=${sp.coerceIn(0.5, 2.0)}\" \"$output\""
            }
            "audio_volume" -> "-i \"$input\" -filter:a \"volume=$volume\" \"$output\""
            "audio_normalize" -> "-i \"$input\" -filter:a loudnorm \"$output\""
            "image_convert" -> "-i \"$input\" \"$output\""
            "image_resize" -> {
                val s = scale.ifBlank { "1920:-1" }
                "-i \"$input\" -vf \"scale=$s\" \"$output\""
            }
            "image_compress" -> {
                val q = opts.optInt("quality", 85).coerceIn(1, 100)
                val qv = ((100 - q) * 31 / 100).coerceIn(2, 31)
                "-i \"$input\" -q:v $qv \"$output\""
            }
            "image_to_video" -> {
                val dur = duration.ifBlank { "5" }
                val f = fps.ifBlank { "25" }
                "-loop 1 -t $dur -i \"$input\" -vf \"fps=$f\" $videoEncode -pix_fmt yuv420p \"$output\""
            }
            else -> null
        }
    }

    private fun doCapabilities(args: JSONObject): ToolResult {
        val ffmpegPath = getFfmpegPath() ?: return ToolResult.fail(notInstalledMsg())
        val type = args.optString("type", "version").lowercase()
        val filter = args.optString("filter", "")
        return when (type) {
            "version" -> runSimpleCommand(listOf(ffmpegPath, "-version"), 6).let { out ->
                ToolResult.ok(out.lines().take(8).joinToString("\n"))
            }
            "codecs" -> {
                val out = runSimpleCommand(listOf(ffmpegPath, "-codecs"), 10)
                val filtered = if (filter.isBlank()) out
                else out.lines().filter { line ->
                    when (filter.lowercase()) {
                        "video" -> line.startsWith(" V")
                        "audio" -> line.startsWith(" A")
                        "subtitle" -> line.startsWith(" S")
                        else -> true
                    }
                }.joinToString("\n")
                ToolResult.ok(filtered)
            }
            "formats" -> {
                val out = runSimpleCommand(listOf(ffmpegPath, "-formats"), 10)
                ToolResult.ok(out.lines().filter { it.startsWith(" D") || it.startsWith("  E") || it.startsWith(" DE") }
                    .joinToString("\n"))
            }
            "hwaccels" -> {
                val out = runSimpleCommand(listOf(ffmpegPath, "-hwaccels"), 6)
                val lines = out.lines().filter {
                    it.isNotBlank() && !it.startsWith("Hardware") && !it.startsWith("ffmpeg")
                }
                ToolResult.ok(if (lines.isEmpty()) "无硬件加速支持" else lines.joinToString("\n"))
            }
            else -> ToolResult.fail("type 只能是 version / codecs / formats / hwaccels")
        }
    }

    private data class ExecResult(
        val success: Boolean,
        val output: String,
        val durationMs: Long
    )

    private val FFMPEG_PROGRESS_KEYS = setOf(
        "frame", "fps", "stream_0_0_q", "bitrate", "total_size",
        "out_time_us", "out_time_ms", "out_time", "dup_frames",
        "drop_frames", "speed", "progress"
    )

    private suspend fun runFfmpeg(
        ffmpegPath: String,
        argList: List<String>,
        timeoutSec: Int
    ): ExecResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()

        val inputFile = findInputFile(argList)
        val totalDurationMs = if (inputFile != null) probeDurationMs(inputFile) else 0L

        val cmd = listOf(ffmpegPath) + argList + listOf("-y", "-hide_banner", "-nostats", "-progress", "pipe:1")
        return@withContext try {
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            val p = pb.start()

            val taskId = TaskRuntime.currentId()

            val outputSb = StringBuilder()
            val readerThread = Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        if (outputSb.length < 100_000_000) outputSb.append(line).append("\n")

                        val eqIdx = line.indexOf('=')
                        val isProgressLine = eqIdx > 0 &&
                                line.substring(0, eqIdx).trim() in FFMPEG_PROGRESS_KEYS

                        if (taskId != null) {
                            if (isProgressLine) {
                                if (line.startsWith("out_time=")) {
                                    val timeStr = line.removePrefix("out_time=").trim()
                                    val ms = parseFfmpegTime(timeStr)
                                    if (ms >= 0) {
                                        if (totalDurationMs > 0) {
                                            val progress = (ms.toFloat() / totalDurationMs).coerceIn(0f, 1f)
                                            val detail = "${(progress * 100).toInt()}% · ${formatMs(ms)} / ${formatMs(totalDurationMs)}"
                                            TaskRuntime.updateProgress(taskId, progress, detail)
                                        } else {
                                            TaskRuntime.updateProgress(taskId, null, "已处理 ${formatMs(ms)}")
                                        }
                                    }
                                } else if (line.startsWith("progress=") && line.contains("end")) {
                                    TaskRuntime.updateProgress(taskId, 1f, "已完成")
                                }
                            } else if (line.isNotBlank()) {
                                TaskRuntime.appendOutput(taskId, line)
                            }
                        }
                    }
                } catch (e: Exception) {
                    DesktopLogger.warn("Ffmpeg", "读 stdout 失败：${e.message}")
                }
            }
            readerThread.start()

            val finished = p.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            val elapsed = System.currentTimeMillis() - start

            if (!finished) {
                p.destroyForcibly()
                ExecResult(false, "命令超时（${timeoutSec}s），已强制终止\n$outputSb", elapsed)
            } else {
                val exit = p.exitValue()
                val out = outputSb.toString().trim()
                if (exit == 0) ExecResult(true, out, elapsed)
                else ExecResult(false, buildString {
                    append("退出码 $exit\n")
                    append(out)
                }, elapsed)
            }
        } catch (e: Exception) {
            DesktopLogger.error("Ffmpeg", "执行异常：${e.javaClass.simpleName}: ${e.message}")
            ExecResult(false, "执行失败：${e.message}", System.currentTimeMillis() - start)
        }
    }

    private fun runSimpleCommand(cmd: List<String>, timeoutSec: Int): String {
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val outFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                try { p.inputStream.bufferedReader(Charsets.UTF_8).readText() }
                catch (_: Exception) { "" }
            }
            val finished = p.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                DesktopLogger.warn("Ffmpeg", "runSimpleCommand 超时（${timeoutSec}s），已终止：${cmd.firstOrNull()}")
                return ""
            }
            try { outFuture.get(2, TimeUnit.SECONDS) } catch (_: Exception) { "" }
        } catch (e: Exception) {
            DesktopLogger.warn("Ffmpeg", "runSimpleCommand 失败：${cmd.firstOrNull()} - ${e.message}")
            ""
        }
    }

    private fun parseCommandLine(cmd: String): List<String> =
        net.xinkong.furagent.shared.util.CommandLine.parse(cmd)

    private fun extractOutputPath(args: List<String>): String? {
        val knownFlags = setOf(
            "-i", "-vf", "-af", "-c", "-c:v", "-c:a", "-crf", "-preset",
            "-b:a", "-b:v", "-ss", "-t", "-map", "-filter_complex", "-filter:a",
            "-loop", "-ar", "-ac", "-vn", "-an", "-f", "-safe", "-q:v", "-vframes",
            "-movflags", "-pix_fmt", "-r", "-s", "-stream_loop"
        )
        for (i in args.indices.reversed()) {
            val a = args[i]
            if (a.startsWith("-")) continue
            if (i > 0 && args[i - 1] in knownFlags) continue
            return a
        }
        return null
    }

    private fun resolvePath(input: String): String? {
        if (input.isBlank()) return null
        val t = input.trim().trim('"', '\'', '`')
        if (File(t).isAbsolute) return t
        val home = System.getProperty("user.home") ?: return null
        return File(home, t).absolutePath
    }

    private fun findInputFile(args: List<String>): String? {
        val idx = args.indexOf("-i")
        if (idx < 0 || idx + 1 >= args.size) return null
        return args[idx + 1]
    }

    private fun parseFfmpegTime(timeStr: String): Long {
        if (timeStr == "N/A" || timeStr.isBlank()) return -1
        return try {
            val parts = timeStr.split(':')
            if (parts.size != 3) return -1
            val h = parts[0].toLongOrNull() ?: 0L
            val m = parts[1].toLongOrNull() ?: 0L
            val s = parts[2].toDoubleOrNull() ?: 0.0
            (h * 3600 + m * 60) * 1000L + (s * 1000).toLong()
        } catch (_: Exception) { -1 }
    }

    private fun probeDurationMs(inputFile: String): Long {
        val ffprobePath = getFfprobePath() ?: return 0L
        return try {
            if (!File(inputFile).exists()) return 0L
            val cmd = listOf(
                ffprobePath, "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                inputFile
            )
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val outFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                try { p.inputStream.bufferedReader(Charsets.UTF_8).readText().trim() }
                catch (_: Exception) { "" }
            }
            val finished = p.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                DesktopLogger.debug("Ffmpeg", "探测时长超时（10s），已终止：$inputFile")
                return 0L
            }
            val out = try { outFuture.get(2, TimeUnit.SECONDS) } catch (_: Exception) { "" }
            val seconds = out.toDoubleOrNull() ?: return 0L
            (seconds * 1000).toLong()
        } catch (e: Exception) {
            DesktopLogger.debug("Ffmpeg", "探测时长失败：${e.message}")
            0L
        }
    }

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }
}