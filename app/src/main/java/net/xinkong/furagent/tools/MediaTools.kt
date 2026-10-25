package net.xinkong.furagent.tools

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.core.ConfirmationBroker
import net.xinkong.furagent.data.AppSettings
import net.xinkong.furagent.shared.util.formatFileSize
import net.xinkong.furagent.system.MediaSessionListenerService
import net.xinkong.furagent.system.ShizukuHelper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.*
import kotlin.math.pow

object MediaTools {

    val TOOLS = setOf("ffmpeg", "screenshot", "view_image", "media_session")

    suspend fun execute(
        name: String, args: JSONObject, context: Context, settings: AppSettings
    ): String = when (name) {
        "ffmpeg" -> doFfmpeg(args, settings)
        "screenshot" -> doScreenshot()
        "view_image" -> doViewImage(args)
        "media_session" -> doMediaSession(args, context)
        else -> "未实现的媒体工具：$name"
    }

    // ffmpeg
    private suspend fun doFfmpeg(args: JSONObject, settings: AppSettings): String {
        val mode = args.optString("mode", "").lowercase()
        return when (mode) {
            "probe" -> doProbe(args, settings)
            "run" -> doFfmpegRaw(args, settings)
            "preset" -> doFfmpegPreset(args, settings)
            "capabilities" -> doCapabilities(args)
            else -> "失败：mode 只能是 probe / run / preset / capabilities"
        }
    }

    private suspend fun doProbe(args: JSONObject, settings: AppSettings): String {
        val path = normalizeAlias(args.optString("path", ""))
        val format = args.optString("format", "text").lowercase()
        if (path.isBlank()) return "失败：未提供路径"
        PathGuard.check(path, settings)?.let { return it }
        if (!File(path).exists()) return "失败：文件不存在 $path"
        return if (format == "json") FfmpegHelper.probeJson(path) else FfmpegHelper.probe(path)
    }

    private suspend fun doCapabilities(args: JSONObject): String {
        val type = args.optString("type", "version").lowercase()
        val filter = args.optString("filter", "")
        return when (type) {
            "version" -> FfmpegHelper.version()
            "codecs" -> FfmpegHelper.listCodecs(filter)
            "formats" -> FfmpegHelper.listFormats()
            "hwaccels" -> FfmpegHelper.listHwAccels()
            else -> "失败：type 只能是 version / codecs / formats / hwaccels"
        }
    }

    private suspend fun doFfmpegRaw(args: JSONObject, settings: AppSettings): String {
        val rawArgs = args.optString("args", "").trim()
        val timeout = args.optInt("timeout", 600).coerceIn(10, 3600)
        if (rawArgs.isBlank()) return "失败：未提供 FFmpeg 参数"

        val argList = parseCommandLine(rawArgs)
        if (argList.isEmpty()) return "失败：无法解析参数"

        for (a in argList) {
            if (a.startsWith("/")) {
                val normalized = normalizeAlias(a)
                PathGuard.check(normalized, settings)?.let { return it }
            }
        }

        val outPath = extractOutputPath(argList)
        if (outPath != null && File(outPath).exists()) {
            if (settings.requireConfirmFfmpegOverwrite) {
                val approved = ConfirmationBroker.request(
                    "覆盖文件确认",
                    "FFmpeg 输出文件已存在：\n$outPath\n\n是否覆盖？"
                )
                if (!approved) return "用户已拒绝覆盖，操作取消"
            }
        }

        AgentLogger.log("FFMPEG", "参数：${argList.joinToString(" ").take(500)}")
        if (!FfmpegHelper.isAvailable()) return "失败：FFmpeg 未就绪"

        val result = FfmpegHelper.execute(argList.toTypedArray(), timeout)
        return if (result.success) {
            val outInfo = if (outPath != null && File(outPath).exists()) {
                "\n输出：$outPath（${formatFileSize(File(outPath).length())}）"
            } else ""
            "执行成功（耗时 ${result.durationMs / 1000}s）$outInfo"
        } else {
            "失败：${result.output.take(1500)}"
        }
    }

    /**
     * preset 处理。
     *
     * 【P1/P2 修复】
     *   - concat / grid / slideshow 的多文件列表：split 后**逐个** normalizeAlias，
     *     否则中文别名 / 相对路径会走到 PathGuard.check 时按 cwd 解析被误杀。
     *   - watermark_path / subtitle_path / overlay / audio 也是用户提供的文件路径，
     *     也要 normalizeAlias + PathGuard.check（之前完全漏检）。
     */
    private suspend fun doFfmpegPreset(args: JSONObject, settings: AppSettings): String {
        val preset = args.optString("preset", "").trim()
        val inputRaw = args.optString("input", "").trim()
        val outputRaw = args.optString("output", "").trim()
        val opts = args.optJSONObject("options") ?: JSONObject()

        if (preset.isBlank()) return "失败：未提供 preset"
        if (inputRaw.isBlank() || outputRaw.isBlank()) return "失败：需要 input 和 output"

        // 收集所有要检查的路径
        val pathsToCheck = mutableListOf<String>()

        val isMultiInput = preset == "video_concat" ||
                preset == "audio_concat" ||
                preset == "video_grid" ||
                preset == "image_slideshow"

        if (isMultiInput) {
            val rawFiles = inputRaw.split(',').map { it.trim() }.filter { it.isNotBlank() }
            if (rawFiles.size < 2) return "失败：该预设至少需要 2 个输入文件（逗号分隔）"
            for (f in rawFiles) {
                pathsToCheck.add(normalizeAlias(f))
            }
        } else {
            pathsToCheck.add(normalizeAlias(inputRaw))
        }

        val output = normalizeAlias(outputRaw)
        pathsToCheck.add(output)

        // 第二输入（watermark / subtitle / pip overlay / replace_audio）
        for (key in listOf("watermark_path", "subtitle_path", "overlay", "audio")) {
            val v = opts.optString(key, "").trim()
            if (v.isNotBlank()) {
                pathsToCheck.add(normalizeAlias(v))
            }
        }

        // 逐个检查路径
        for (p in pathsToCheck) {
            PathGuard.check(p, settings)?.let { return it }
        }

        // 重建 input：多输入时用 normalize 过的路径拼回去
        val normalizedInput: String = if (isMultiInput) {
            inputRaw.split(',').map { it.trim() }.filter { it.isNotBlank() }
                .map { normalizeAlias(it) }
                .joinToString(",")
        } else {
            normalizeAlias(inputRaw)
        }

        val cmd = buildPresetArgs(preset, normalizedInput, output, opts)
            ?: return "失败：未知预设 '$preset'。可用预设：video_* / audio_* / image_* / info_* 前缀。"

        return doFfmpegRaw(
            JSONObject().apply {
                put("args", cmd)
                put("timeout", opts.optInt("timeout", 900))
            },
            settings
        )
    }

    /**
     * 把 preset 参数翻译成 FFmpeg 命令。
     *
     * 【P1 修复】watermark overlay 坐标语法修正。
     *
     *   drawtext filter 里的变量：
     *     w / h   画面宽/高
     *     tw / th 文本宽/高
     *   语法：`x=w-tw-10:y=h-th-10`
     *
     *   overlay filter 里的变量：
     *     W / H   主输入（第一个 -i）宽/高
     *     w / h   overlay 输入（第二个 -i）宽/高
     *   语法：`x=W-w-10:y=H-h-10`
     *
     *   两套语法不同，不能对 drawtext 的字符串做 replace 转换。
     *   旧代码 `wmPos().replace("tw","w").replace("th","h")` 会得到
     *   `w-w-10:h-h-10`（overlay 里 w 是自己的宽，恒等于自身减自身），完全错误。
     */
    private fun buildPresetArgs(
        preset: String, input: String, output: String, opts: JSONObject
    ): String? {
        val crf = opts.optString("crf", "28")
        val presetName = opts.optString("preset", "veryfast")
        val scale = opts.optString("scale", "")
        val speed = opts.optString("speed", "2.0")
        val start = opts.optString("start", "")
        val duration = opts.optString("duration", "")
        val bitrate = opts.optString("bitrate", "")
        val fps = opts.optString("fps", "")
        val volume = opts.optString("volume", "1.0")
        val pitch = opts.optString("pitch", "0")
        val channels = opts.optString("channels", "")
        val sampleRate = opts.optString("sample_rate", "")
        val watermarkText = opts.optString("watermark_text", "")
        val watermarkPath = normalizeAlias(opts.optString("watermark_path", ""))
        val watermarkPos = opts.optString("watermark_position", "bottom_right")
        val subtitlePath = normalizeAlias(opts.optString("subtitle_path", ""))
        val format = opts.optString("format", "")
        val rotate = opts.optString("rotate", "90")
        val brightness = opts.optString("brightness", "")
        val contrast = opts.optString("contrast", "")
        val saturation = opts.optString("saturation", "")
        val blurSigma = opts.optString("blur_sigma", "10")
        val sharpenAmount = opts.optString("sharpen", "1.0")
        val fadeIn = opts.optString("fade_in", "0")
        val fadeOut = opts.optString("fade_out", "0")
        val loopCount = opts.optInt("loop_count", 0)
        val hwaccel = opts.optBoolean("hwaccel", true)
        val forceCopy = opts.optBoolean("copy", false)

        // drawtext 坐标（w/h 是画面，tw/th 是文本）
        fun textPos(pos: String): String = when (pos.lowercase()) {
            "top_left" -> "10:10"
            "top_right" -> "w-tw-10:10"
            "bottom_left" -> "10:h-th-10"
            "bottom_right" -> "w-tw-10:h-th-10"
            "center" -> "(w-tw)/2:(h-th)/2"
            else -> "w-tw-10:h-th-10"
        }

        // overlay 坐标（W/H 是主输入，w/h 是 overlay 输入）
        fun overlayPos(pos: String): String = when (pos.lowercase()) {
            "top_left" -> "10:10"
            "top_right" -> "W-w-10:10"
            "bottom_left" -> "10:H-h-10"
            "bottom_right" -> "W-w-10:H-h-10"
            "center" -> "(W-w)/2:(H-h)/2"
            else -> "W-w-10:H-h-10"
        }

        val ss = if (start.isNotBlank()) "-ss $start " else ""
        val t = if (duration.isNotBlank()) "-t $duration " else ""

        val encVideo = when {
            forceCopy -> "-c:v copy"
            hwaccel -> {
                val b = bitrate.ifBlank { "2M" }
                "-c:v h264_mediacodec -b:v $b"
            }
            else -> "-c:v libx264 -crf $crf -preset $presetName"
        }

        val encAudio = "-c:a aac -b:a 128k"
        val encAudioCopy = "-c:a copy"

        return when (preset) {
            "video_convert" ->
                if (forceCopy) "-i \"$input\" -c copy \"$output\""
                else "-i \"$input\" $encVideo $encAudio \"$output\""

            "video_compress" -> {
                val s = scale.ifBlank { "960:-2" }
                "-i \"$input\" -vf scale=$s $encVideo $encAudio \"$output\""
            }

            "video_quick_compress" -> {
                val b = bitrate.ifBlank { "1500k" }
                "-i \"$input\" $encVideo $encAudio \"$output\""
            }

            "video_trim" -> "${ss}${t}-i \"$input\" -c copy \"$output\""
            "video_trim_reencode" -> "${ss}${t}-i \"$input\" $encVideo $encAudio \"$output\""

            "video_concat" -> {
                // 传进来的 input 已在 doFfmpegPreset 里 normalize 过
                val files = input.split(',').map { it.trim() }.filter { it.isNotBlank() }
                if (files.size < 2) return null
                val listFile = "${SDCARD_PATH}/FurAgent/temp/concat_${System.currentTimeMillis()}.txt"
                try {
                    File(listFile).parentFile?.mkdirs()
                    File(listFile).writeText(files.joinToString("\n") { "file '$it'" }, Charsets.UTF_8)
                } catch (_: Exception) { return null }
                "-f concat -safe 0 -i \"$listFile\" -c copy \"$output\""
            }

            "video_speed" -> {
                val sp = speed.toDoubleOrNull() ?: 2.0
                val videoPts = 1.0 / sp
                val audioTempo = sp.coerceIn(0.5, 2.0)
                "-i \"$input\" -filter_complex \"[0:v]setpts=${videoPts}*PTS[v];[0:a]atempo=$audioTempo[a]\" " +
                        "-map \"[v]\" -map \"[a]\" $encVideo $encAudio \"$output\""
            }

            "video_resize" -> {
                val s = scale.ifBlank { "960:-2" }
                "-i \"$input\" -vf scale=$s $encVideo $encAudioCopy \"$output\""
            }

            "video_crop" -> {
                val w = opts.optString("crop_w", "")
                val h = opts.optString("crop_h", "")
                val x = opts.optString("crop_x", "0")
                val y = opts.optString("crop_y", "0")
                if (w.isBlank() || h.isBlank()) return null
                "-i \"$input\" -vf crop=$w:$h:$x:$y $encVideo $encAudioCopy \"$output\""
            }

            "video_rotate" -> {
                val filter = when (rotate) {
                    "90" -> "transpose=1"
                    "180" -> "transpose=1,transpose=1"
                    "270" -> "transpose=2"
                    else -> return null
                }
                "-i \"$input\" -vf \"$filter\" $encVideo $encAudioCopy \"$output\""
            }

            "video_flip_h" -> "-i \"$input\" -vf hflip $encVideo $encAudioCopy \"$output\""
            "video_flip_v" -> "-i \"$input\" -vf vflip $encVideo $encAudioCopy \"$output\""

            "video_watermark_text" -> {
                if (watermarkText.isBlank()) return null
                val escaped = watermarkText.replace("'", "\\'").replace(":", "\\:")
                "-i \"$input\" -vf \"drawtext=text='$escaped':fontsize=28:fontcolor=white@0.85:" +
                        "box=1:boxcolor=black@0.35:boxborderw=6:${textPos(watermarkPos)}\" " +
                        "$encVideo $encAudioCopy \"$output\""
            }

            // 【P1 修复】改用 overlayPos（原先是 wmPos().replace 得到错误坐标）
            "video_watermark_image" -> {
                if (watermarkPath.isBlank()) return null
                val pos = overlayPos(watermarkPos)
                "-i \"$input\" -i \"$watermarkPath\" -filter_complex " +
                        "\"overlay=$pos\" $encVideo $encAudioCopy \"$output\""
            }

            "video_add_subtitle" -> {
                if (subtitlePath.isBlank()) return null
                "-i \"$input\" -vf \"subtitles='$subtitlePath'\" $encVideo $encAudioCopy \"$output\""
            }

            "video_mute" -> "-i \"$input\" -an -c:v copy \"$output\""

            "video_extract_audio" -> {
                val fmt = format.ifBlank { "mp3" }
                when (fmt) {
                    "mp3" -> "-i \"$input\" -vn -c:a libmp3lame -b:a 128k \"$output\""
                    "aac", "m4a" -> "-i \"$input\" -vn -c:a aac -b:a 128k \"$output\""
                    "wav" -> "-i \"$input\" -vn -c:a pcm_s16le \"$output\""
                    "flac" -> "-i \"$input\" -vn -c:a flac \"$output\""
                    "ogg" -> "-i \"$input\" -vn -c:a libvorbis -q:a 5 \"$output\""
                    else -> "-i \"$input\" -vn -c:a copy \"$output\""
                }
            }

            "video_replace_audio" -> {
                val audio = normalizeAlias(opts.optString("audio", ""))
                if (audio.isBlank()) return null
                "-i \"$input\" -i \"$audio\" -c:v copy -c:a aac -map 0:v -map 1:a -shortest \"$output\""
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

            "video_grid" -> {
                // input 已在 doFfmpegPreset 里 normalize 过
                val files = input.split(',').map { it.trim() }.filter { it.isNotBlank() }
                if (files.size < 2) return null
                val n = files.size
                val inputs = files.joinToString(" ") { "-i \"$it\"" }
                val filter = when (n) {
                    2 -> "[0:v][1:v]hstack=inputs=2[v]"
                    3 -> "[0:v][1:v][2:v]hstack=inputs=3[v]"
                    4 -> "[0:v][1:v]hstack[t];[2:v][3:v]hstack[b];[t][b]vstack[v]"
                    else -> return null
                }
                "$inputs -filter_complex \"$filter\" -map \"[v]\" $encVideo \"$output\""
            }

            "video_pip" -> {
                val overlay = normalizeAlias(opts.optString("overlay", ""))
                if (overlay.isBlank()) return null
                val pos = opts.optString("pip_position", "top_right")
                val overlayPosStr = overlayPos(pos)
                val overlayScale = opts.optString("pip_scale", "0.3")
                "-i \"$input\" -i \"$overlay\" -filter_complex " +
                        "\"[1:v]scale=iw*$overlayScale:-2[pip];[0:v][pip]overlay=$overlayPosStr[v]\" " +
                        "-map \"[v]\" -map 0:a? $encVideo $encAudioCopy \"$output\""
            }

            "video_fade" -> {
                val fi = fadeIn.toDoubleOrNull() ?: 1.0
                val fo = fadeOut.toDoubleOrNull() ?: 1.0
                val filters = mutableListOf<String>()
                if (fi > 0) filters.add("fade=t=in:st=0:d=$fi")
                if (fo > 0) filters.add("fade=t=out:st=duration-$fo:d=$fo")
                if (filters.isEmpty()) return null
                "-i \"$input\" -vf \"${filters.joinToString(",")}\" $encVideo $encAudioCopy \"$output\""
            }

            "video_color_adjust" -> {
                val eq = mutableListOf<String>()
                if (brightness.isNotBlank()) eq.add("brightness=$brightness")
                if (contrast.isNotBlank()) eq.add("contrast=$contrast")
                if (saturation.isNotBlank()) eq.add("saturation=$saturation")
                if (eq.isEmpty()) return null
                "-i \"$input\" -vf \"eq=${eq.joinToString(":")}\" $encVideo $encAudioCopy \"$output\""
            }

            "video_blur" -> "-i \"$input\" -vf \"boxblur=$blurSigma:2\" $encVideo $encAudioCopy \"$output\""
            "video_sharpen" -> "-i \"$input\" -vf \"unsharp=5:5:$sharpenAmount\" $encVideo $encAudioCopy \"$output\""

            "video_frame_rate" -> {
                val f = fps.ifBlank { "30" }
                "-i \"$input\" -r $f $encVideo $encAudioCopy \"$output\""
            }

            "video_loop" -> {
                val n = loopCount.coerceAtLeast(1)
                "-stream_loop $n -i \"$input\" -c copy \"$output\""
            }

            "video_reverse" -> "-i \"$input\" -vf reverse -af areverse $encVideo $encAudio \"$output\""
            "video_stabilize" -> "-i \"$input\" -vf \"deshake\" $encVideo $encAudioCopy \"$output\""

            "video_hw_encode" -> {
                val b = bitrate.ifBlank { "2M" }
                "-i \"$input\" -c:v h264_mediacodec -b:v $b $encAudio \"$output\""
            }

            "audio_convert" -> {
                val fmt = format.ifBlank { "mp3" }
                when (fmt) {
                    "mp3" -> "-i \"$input\" -vn -c:a libmp3lame -b:a 128k \"$output\""
                    "aac", "m4a" -> "-i \"$input\" -vn -c:a aac -b:a 128k \"$output\""
                    "wav" -> "-i \"$input\" -vn -c:a pcm_s16le \"$output\""
                    "flac" -> "-i \"$input\" -vn -c:a flac \"$output\""
                    "ogg" -> "-i \"$input\" -vn -c:a libvorbis -q:a 5 \"$output\""
                    else -> "-i \"$input\" -vn -c:a copy \"$output\""
                }
            }

            "audio_extract" -> {
                val fmt = format.ifBlank { "mp3" }
                when (fmt) {
                    "mp3" -> "-i \"$input\" -vn -c:a libmp3lame -b:a 128k \"$output\""
                    "wav" -> "-i \"$input\" -vn -c:a pcm_s16le \"$output\""
                    "flac" -> "-i \"$input\" -vn -c:a flac \"$output\""
                    "aac", "m4a" -> "-i \"$input\" -vn -c:a aac -b:a 128k \"$output\""
                    else -> "-i \"$input\" -vn -c:a copy \"$output\""
                }
            }

            "audio_trim" -> "${ss}${t}-i \"$input\" -c copy \"$output\""

            "audio_concat" -> {
                // input 已在 doFfmpegPreset 里 normalize 过
                val files = input.split(',').map { it.trim() }.filter { it.isNotBlank() }
                if (files.size < 2) return null
                val listFile = "${SDCARD_PATH}/FurAgent/temp/concat_${System.currentTimeMillis()}.txt"
                try {
                    File(listFile).parentFile?.mkdirs()
                    File(listFile).writeText(files.joinToString("\n") { "file '$it'" }, Charsets.UTF_8)
                } catch (_: Exception) { return null }
                "-f concat -safe 0 -i \"$listFile\" -c copy \"$output\""
            }

            "audio_speed" -> {
                val sp = speed.toDoubleOrNull() ?: 2.0
                "-i \"$input\" -filter:a \"atempo=${sp.coerceIn(0.5, 2.0)}\" \"$output\""
            }

            "audio_pitch" -> {
                val semitones = pitch.toDoubleOrNull() ?: 0.0
                val factor = 2.0.pow(semitones / 12.0)
                "-i \"$input\" -filter:a \"asetrate=44100*$factor,aresample=44100,atempo=${1.0 / factor}\" \"$output\""
            }

            "audio_volume" -> "-i \"$input\" -filter:a \"volume=$volume\" \"$output\""
            "audio_normalize" -> "-i \"$input\" -filter:a \"loudnorm\" \"$output\""

            "audio_channels" -> {
                val ch = channels.ifBlank { "1" }
                "-i \"$input\" -ac $ch \"$output\""
            }

            "audio_sample_rate" -> {
                val sr = sampleRate.ifBlank { "44100" }
                "-i \"$input\" -ar $sr \"$output\""
            }

            "audio_bitrate" -> {
                val br = bitrate.ifBlank { "128k" }
                "-i \"$input\" -c:a libmp3lame -b:a $br \"$output\""
            }

            "audio_fade" -> {
                val fi = fadeIn.toDoubleOrNull() ?: 1.0
                val fo = fadeOut.toDoubleOrNull() ?: 1.0
                val filters = mutableListOf<String>()
                if (fi > 0) filters.add("afade=t=in:st=0:d=$fi")
                if (fo > 0) filters.add("afade=t=out:st=duration-$fo:d=$fo")
                if (filters.isEmpty()) return null
                "-i \"$input\" -af \"${filters.joinToString(",")}\" \"$output\""
            }

            "audio_remove_vocal" ->
                "-i \"$input\" -af \"pan=stereo|c0=c0-c1|c1=c1-c0\" \"$output\""

            "audio_denoise" -> "-i \"$input\" -af \"afftdn=nf=-25\" \"$output\""
            "audio_reverse" -> "-i \"$input\" -af areverse \"$output\""

            "image_convert" -> "-i \"$input\" \"$output\""

            "image_resize" -> {
                val s = scale.ifBlank { "1280:-1" }
                "-i \"$input\" -vf \"scale=$s\" \"$output\""
            }

            "image_compress" -> {
                val q = opts.optInt("quality", 75).coerceIn(1, 100)
                val qv = ((100 - q) * 31 / 100).coerceIn(2, 31)
                "-i \"$input\" -q:v $qv \"$output\""
            }

            // 【P1 修复】同 overlay 坐标修正
            "image_watermark" -> {
                if (watermarkPath.isBlank()) return null
                val pos = overlayPos(watermarkPos)
                "-i \"$input\" -i \"$watermarkPath\" -filter_complex \"overlay=$pos\" \"$output\""
            }

            "image_to_video" -> {
                val dur = duration.ifBlank { "5" }
                val f = fps.ifBlank { "25" }
                "-loop 1 -t $dur -i \"$input\" -vf \"fps=$f\" $encVideo -pix_fmt yuv420p \"$output\""
            }

            "image_slideshow" -> {
                // input 已在 doFfmpegPreset 里 normalize 过
                val files = input.split(',').map { it.trim() }.filter { it.isNotBlank() }
                if (files.size < 2) return null
                val dur = duration.ifBlank { "2" }
                val f = fps.ifBlank { "25" }
                val inputs = files.joinToString(" ") { "-loop 1 -t $dur -i \"$it\"" }
                val concatFilter = files.indices.joinToString("") { "[$it:v]" } + "concat=n=${files.size}:v=1:a=0,fps=$f[v]"
                "$inputs -filter_complex \"$concatFilter\" -map \"[v]\" $encVideo -pix_fmt yuv420p \"$output\""
            }

            "info_probe" -> null

            "info_thumbnail" -> {
                val time = start.ifBlank { "00:00:01" }
                "-ss $time -i \"$input\" -vframes 1 -vf \"scale=320:-1\" \"$output\""
            }

            else -> null
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
            if (i > 0 && args[i - 1].startsWith("-") &&
                !args[i - 1].startsWith("--") && args[i - 1].contains("=")
            ) continue
            return normalizeAlias(a)
        }
        return null
    }

    private suspend fun doScreenshot(): String {
        if (!ShizukuHelper.isAvailable()) return "失败：Shizuku 服务未运行"
        if (!ShizukuHelper.hasPermission()) return "失败：未获得 Shizuku 权限"
        return withContext(Dispatchers.IO) {
            try {
                val path = VisionHelper.newScreenshotPath()
                val result = ShizukuHelper.executeCommand("screencap -p \"$path\"", timeoutSeconds = 15)
                val ok = File(path).exists() && File(path).length() > 0
                if (!ok) return@withContext "截图失败：${result.take(200)}"
                VisionHelper.pushPendingImage(path)
                "已截图并附加到下一轮上下文：$path"
            } catch (e: Exception) {
                AgentLogger.log(
                    "ERR",
                    "截图异常：${e.javaClass.simpleName}: ${e.message}"
                )
                "截图异常：${e.message}"
            }
        }
    }

    private fun doViewImage(args: JSONObject): String {
        val path = normalizeAlias(args.optString("path", ""))
        if (path.isBlank()) return "失败：未提供图片路径"
        val file = File(path)
        if (!file.exists()) return "失败：图片不存在 $path"
        if (!file.isFile) return "失败：不是文件 $path"
        val ext = file.extension.lowercase(Locale.ROOT)
        // 对齐桌面端：支持 jpg/jpeg/png/gif/webp/bmp
        if (ext !in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")) {
            return "失败：不支持的图片格式 .$ext（支持 jpg/jpeg/png/gif/webp/bmp）"
        }
        val size = file.length()
        if (size > 20 * 1024 * 1024) return "失败：图片过大（${formatFileSize(size)}）"
        VisionHelper.pushPendingImage(path)
        return "已加载图片并附加到下一轮上下文：$path（${formatFileSize(size)}）"
    }

    // media_session

    private fun doMediaSession(args: JSONObject, context: Context): String {
        val op = args.optString("op", "").lowercase()
        if (op.isBlank()) return "失败：未提供 op"
        if (!hasNotificationListenerAccess(context)) {
            return "失败：缺少「通知使用权」，无法读取媒体会话。" +
                    "请在系统设置 → 通知 → 通知使用权 中开启 FurAgent（或 设备的 设置 → 应用管理 → 特殊权限 → 通知使用权）。"
        }

        return when (op) {
            "status" -> mediaStatus(context)
            "now" -> mediaNow(context, args)
            "play" -> mediaControl(context, args, "play")
            "pause" -> mediaControl(context, args, "pause")
            "toggle" -> mediaControl(context, args, "toggle")
            "next" -> mediaControl(context, args, "next")
            "prev", "previous" -> mediaControl(context, args, "prev")
            "stop" -> mediaControl(context, args, "stop")
            "seek" -> mediaSeek(context, args)
            else -> "失败：op 只能是 status / now / play / pause / toggle / next / prev / stop / seek"
        }
    }

    fun hasNotificationListenerAccess(context: Context): Boolean {
        return try {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val component = ComponentName(context, MediaSessionListenerService::class.java)
            flat.split(":").any {
                try {
                    ComponentName.unflattenFromString(it) == component
                } catch (_: Exception) { false }
            }
        } catch (e: Exception) {
            AgentLogger.log(
                "MEDIA",
                "读取通知使用权状态失败：${e.javaClass.simpleName}: ${e.message}"
            )
            false
        }
    }

    private fun getControllers(context: Context): List<MediaController> {
        return try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return emptyList()
            val component = ComponentName(context, MediaSessionListenerService::class.java)
            msm.getActiveSessions(component)
        } catch (e: SecurityException) {
            AgentLogger.log("MEDIA", "getActiveSessions 权限被拒：${e.message}")
            emptyList()
        } catch (e: Exception) {
            AgentLogger.log("MEDIA", "getActiveSessions 异常：${e.message}")
            emptyList()
        }
    }

    private fun pickController(
        controllers: List<MediaController>, target: String?
    ): MediaController? {
        if (controllers.isEmpty()) return null
        if (target.isNullOrBlank()) {
            return controllers.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: controllers.first()
        }
        return controllers.firstOrNull { it.packageName == target }
            ?: controllers.firstOrNull { it.packageName.contains(target, ignoreCase = true) }
    }

    private fun stateText(state: Int): String = when (state) {
        PlaybackState.STATE_NONE -> "无"
        PlaybackState.STATE_STOPPED -> "已停止"
        PlaybackState.STATE_PAUSED -> "暂停"
        PlaybackState.STATE_PLAYING -> "播放中"
        PlaybackState.STATE_FAST_FORWARDING -> "快进"
        PlaybackState.STATE_REWINDING -> "快退"
        PlaybackState.STATE_BUFFERING -> "缓冲中"
        PlaybackState.STATE_ERROR -> "错误"
        PlaybackState.STATE_CONNECTING -> "连接中"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "跳到上一首"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "跳到下一首"
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "跳到队列项"
        else -> "未知($state)"
    }

    private fun appLabel(context: Context, pkg: String): String {
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
    }

    private fun fmtMillis(ms: Long): String {
        if (ms <= 0) return "-"
        val total = ms / 1000
        val m = total / 60
        val s = total % 60
        return String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun readMetadata(controller: MediaController): Map<String, String> {
        val md = controller.metadata ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        md.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { out["title"] = it }
        md.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { out["artist"] = it }
        md.getString(MediaMetadata.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let { out["album"] = it }
        md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.takeIf { it.isNotBlank() }?.let { out["album_artist"] = it }
        val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        if (duration > 0) out["duration_ms"] = duration.toString()
        return out
    }

    private fun controllerToJson(context: Context, controller: MediaController): JSONObject {
        val md = readMetadata(controller)
        val pb = controller.playbackState
        return JSONObject().apply {
            put("package", controller.packageName)
            put("app", appLabel(context, controller.packageName))
            put("state", stateText(pb?.state ?: PlaybackState.STATE_NONE))
            put("state_code", pb?.state ?: PlaybackState.STATE_NONE)
            put("position_ms", pb?.position ?: 0L)
            md["title"]?.let { put("title", it) }
            md["artist"]?.let { put("artist", it) }
            md["album"]?.let { put("album", it) }
            md["album_artist"]?.let { put("album_artist", it) }
            md["duration_ms"]?.let { put("duration_ms", it.toLong()) }
        }
    }

    private fun mediaStatus(context: Context): String {
        val controllers = getControllers(context)
        if (controllers.isEmpty()) return "当前没有活跃的媒体会话"

        val sb = StringBuilder("活跃媒体会话共 ${controllers.size} 个：\n\n")
        controllers.forEachIndexed { i, c ->
            val j = controllerToJson(context, c)
            sb.append("${i + 1}. ${j.optString("app")}（${j.optString("package")}）\n")
            sb.append("   状态：${j.optString("state")}\n")
            j.optString("title").takeIf { it.isNotBlank() }?.let { sb.append("   标题：$it\n") }
            j.optString("artist").takeIf { it.isNotBlank() }?.let { sb.append("   艺术家：$it\n") }
            j.optString("album").takeIf { it.isNotBlank() }?.let { sb.append("   专辑：$it\n") }
            sb.append("\n")
        }
        sb.append("用 now / play / pause / toggle / next / prev / stop / seek 控制，target 参数可指定包名（不填自动选当前播放中的）")
        return sb.toString().trim()
    }

    private fun mediaNow(context: Context, args: JSONObject): String {
        val controllers = getControllers(context)
        if (controllers.isEmpty()) return "当前没有活跃的媒体会话"
        val target = args.optString("target", "").trim().takeIf { it.isNotBlank() }
        val controller = pickController(controllers, target)
            ?: return "未找到目标媒体会话${if (target != null) "：$target" else ""}"

        val j = controllerToJson(context, controller)
        val sb = StringBuilder()
        sb.append("来源：").append(j.optString("app"))
            .append("（").append(j.optString("package")).append("）\n")
        sb.append("状态：").append(j.optString("state")).append("\n")
        j.optString("title").takeIf { it.isNotBlank() }?.let { sb.append("标题：$it\n") }
        j.optString("artist").takeIf { it.isNotBlank() }?.let { sb.append("艺术家：$it\n") }
        j.optString("album").takeIf { it.isNotBlank() }?.let { sb.append("专辑：$it\n") }

        val duration = j.optLong("duration_ms", 0L)
        val position = j.optLong("position_ms", 0L)
        if (duration > 0) {
            sb.append("时长：").append(fmtMillis(duration)).append("\n")
            sb.append("进度：").append(fmtMillis(position)).append("（")
                .append((position.toDouble() / duration * 100).toInt()).append("%）\n")
        }

        if (args.optBoolean("include_art", false)) {
            val b64 = readAlbumArtBase64(controller)
            if (b64 != null) {
                sb.append("\n封面 base64（JPEG）：\n").append(b64)
            } else {
                sb.append("\n（无封面数据）\n")
            }
        }
        return sb.toString().trim()
    }

    private fun readAlbumArtBase64(controller: MediaController): String? {
        val md = controller.metadata ?: return null
        val bmp: Bitmap = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: return null
        return try {
            val baos = ByteArrayOutputStream()
            var quality = 70
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            while (baos.size() > 500_000 && quality > 30) {
                baos.reset()
                quality -= 15
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            }
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            AgentLogger.log(
                "MEDIA",
                "读取封面图失败：${e.javaClass.simpleName}: ${e.message}"
            )
            null
        }
    }

    private fun mediaControl(context: Context, args: JSONObject, action: String): String {
        val controllers = getControllers(context)
        if (controllers.isEmpty()) return "失败：当前没有活跃的媒体会话"
        val target = args.optString("target", "").trim().takeIf { it.isNotBlank() }
        val controller = pickController(controllers, target)
            ?: return "失败：未找到目标媒体会话${if (target != null) "：$target" else ""}"

        val tc = controller.transportControls
        return try {
            when (action) {
                "play" -> tc.play()
                "pause" -> tc.pause()
                "toggle" -> {
                    val st = controller.playbackState?.state ?: PlaybackState.STATE_NONE
                    if (st == PlaybackState.STATE_PLAYING) tc.pause() else tc.play()
                }
                "next" -> tc.skipToNext()
                "prev" -> tc.skipToPrevious()
                "stop" -> tc.stop()
            }
            val app = appLabel(context, controller.packageName)
            "已对「$app」执行：$action"
        } catch (e: Exception) {
            AgentLogger.log(
                "MEDIA",
                "媒体控制 $action 失败（${controller.packageName}）：${e.javaClass.simpleName}: ${e.message}"
            )
            "失败：${e.message}"
        }
    }

    private fun mediaSeek(context: Context, args: JSONObject): String {
        val controllers = getControllers(context)
        if (controllers.isEmpty()) return "失败：当前没有活跃的媒体会话"
        val target = args.optString("target", "").trim().takeIf { it.isNotBlank() }
        val controller = pickController(controllers, target)
            ?: return "失败：未找到目标媒体会话${if (target != null) "：$target" else ""}"

        val positionSec = args.optDouble("position_seconds", -1.0)
        if (positionSec < 0) return "失败：seek 需要 position_seconds"
        val positionMs = (positionSec * 1000).toLong()

        return try {
            controller.transportControls.seekTo(positionMs)
            val app = appLabel(context, controller.packageName)
            "已对「$app」跳转到：${fmtMillis(positionMs)}"
        } catch (e: Exception) {
            AgentLogger.log(
                "MEDIA",
                "媒体跳转失败（${controller.packageName}）：${e.javaClass.simpleName}: ${e.message}"
            )
            "失败：${e.message}"
        }
    }
}