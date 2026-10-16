package net.xinkong.furagent.desktop.system

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.xinkong.furagent.desktop.data.DesktopPaths
import java.io.File
import java.io.OutputStreamWriter
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Windows 语音合成。
 *
 * 实现：PowerShell 桥接 System.Speech.Synthesis（SAPI）。
 *
 * 错误处理策略：
 *  - stdout 里 "ERR:" 前缀 → 写进 state.error
 *  - stderr 全部内容 → 写 DesktopLogger，同时保留在 state.error 里
 *  - state.error 持久保留，直到用户点"关闭"或开始新朗读
 *
 * 【临时文件策略】
 *  - 朗读脚本固定落 ~/.furagent/temp/furagent_speak.ps1（TempCleaner 会管）
 *  - 探测脚本固定落 ~/.furagent/temp/furagent_tts_probe.ps1
 *  - 都不 deleteOnExit（文件是持久的，由 TempCleaner 按 mtime 清理）
 */
object DesktopSpeechHelper {

    data class State(
        val speaking: Boolean = false,
        val text: String = "",
        val progress: Float = 0f,
        val startedAt: Long = 0L,
        val error: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var process: Process? = null
    @Volatile private var readerJob: Job? = null
    @Volatile private var stderrJob: Job? = null

    @Volatile private var availableCache: Boolean? = null

    // ============================================================
    // 固定名脚本文件
    // ============================================================

    private val psLock = Any()

    private val psProbeFile: File by lazy {
        File(DesktopPaths.tempDir(), "furagent_tts_probe.ps1")
    }

    private val speechPsLock = Any()

    private val speechScriptFile: File by lazy {
        File(DesktopPaths.tempDir(), "furagent_speak.ps1")
    }

    // ============================================================
    // 探测
    // ============================================================

    fun isAvailable(): Boolean {
        availableCache?.let { return it }
        val result = checkAvailable()
        availableCache = result
        return result
    }

    fun clearAvailabilityCache() {
        availableCache = null
    }

    private fun checkAvailable(): Boolean {
        return isWindows() && try {
            val script = $$"""
                    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                    try {
                        Add-Type -AssemblyName System.Speech | Out-Null
                        $synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
                        $voices = $synth.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.Name -like 'zh*' }
                        if ($voices) { [Console]::Out.WriteLine("OK") } else { [Console]::Out.WriteLine("NO_ZH") }
                        $synth.Dispose()
                    } catch {
                        [Console]::Out.WriteLine("ERR:" + $_.Exception.Message)
                    }
                """.trimIndent()
            val out = runPsSync(script, timeoutSec = 8)
            out?.contains("OK") == true
        } catch (e: Exception) {
            DesktopLogger.error("Speech", "可用性检测异常：${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // ============================================================
    // 朗读
    // ============================================================

    fun speak(text: String): Boolean {
        if (!isWindows()) {
            setError("当前系统不支持 TTS（仅 Windows）")
            return false
        }
        val clean = text.trim()
        if (clean.isBlank()) return false

        stop()

        val scriptFile = try {
            writeSpeakerScript()
        } catch (e: Exception) {
            DesktopLogger.error("Speech", "写入 TTS 脚本失败：${e.message}")
            setError("写入 TTS 脚本失败：${e.message}")
            return false
        }

        return try {
            val p = ProcessBuilder(
                "powershell.exe",
                "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-File", scriptFile.absolutePath
            ).redirectErrorStream(false).start()

            process = p
            _state.value = State(
                speaking = true,
                text = clean,
                progress = 0f,
                startedAt = System.currentTimeMillis(),
                error = null
            )

            scope.launch {
                try {
                    OutputStreamWriter(p.outputStream, Charsets.UTF_8).use { w ->
                        w.write(clean)
                        w.flush()
                    }
                } catch (e: Exception) {
                    DesktopLogger.error("Speech", "写入 stdin 失败：${e.message}")
                }
            }

            readerJob = scope.launch {
                try {
                    p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        val t = line.trim()
                        when {
                            t.startsWith("PROGRESS:") -> {
                                val spec = t.removePrefix("PROGRESS:").trim()
                                val parts = spec.split('/')
                                if (parts.size == 2) {
                                    val cur = parts[0].toIntOrNull() ?: 0
                                    val tot = parts[1].toIntOrNull() ?: 1
                                    val prog = if (tot > 0) (cur.toFloat() / tot).coerceIn(0f, 1f) else 0f
                                    _state.value = _state.value.copy(progress = prog)
                                }
                            }
                            t == "DONE" || t == "EMPTY" -> {
                                _state.value = _state.value.copy(progress = 1f)
                            }
                            t.startsWith("ERR:") -> {
                                val err = t.removePrefix("ERR:").trim()
                                DesktopLogger.error("Speech", "脚本报错：$err")
                                setError(err)
                            }
                        }
                    }
                } catch (e: Exception) {
                    DesktopLogger.error("Speech", "读 stdout 异常：${e.message}")
                }
                try { p.waitFor() } catch (_: Exception) { }
                if (process === p) {
                    process = null
                    _state.value = _state.value.copy(speaking = false)
                }
            }

            stderrJob = scope.launch {
                try {
                    p.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        if (line.isNotBlank()) {
                            DesktopLogger.error("Speech", "stderr: $line")
                            if (_state.value.error == null) {
                                setError(line)
                            }
                        }
                    }
                } catch (_: Exception) { }
            }

            true
        } catch (e: Exception) {
            DesktopLogger.error("Speech", "启动 TTS 进程失败：${e.javaClass.simpleName}: ${e.message}")
            process = null
            setError("启动 TTS 进程失败：${e.message}")
            false
        }
    }

    fun stop() {
        val p = process ?: run {
            _state.value = _state.value.copy(speaking = false)
            return
        }
        process = null
        readerJob?.cancel()
        readerJob = null
        stderrJob?.cancel()
        stderrJob = null
        try {
            p.destroy()
            if (!p.waitFor(500, TimeUnit.MILLISECONDS)) {
                DesktopLogger.warn("Speech", "TTS 进程 500ms 未退出，强制 destroyForcibly")
                p.destroyForcibly()
            }
        } catch (e: Exception) {
            DesktopLogger.warn("Speech", "停止 TTS 进程异常：${e.javaClass.simpleName}: ${e.message}")
        }
        _state.value = _state.value.copy(speaking = false)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun reset() {
        stop()
        _state.value = State()
    }

    fun isSpeaking(): Boolean = _state.value.speaking

    private fun setError(msg: String) {
        _state.value = _state.value.copy(error = msg, speaking = false)
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")

    // ============================================================
    // 写入朗读脚本（固定名，持久，由 TempCleaner 按时间清）
    // ============================================================

    private fun writeSpeakerScript(): File {
        synchronized(speechPsLock) {
            val f = speechScriptFile
            // 每次都覆盖，保证脚本与当前代码版本一致（避免旧版本残留）
            f.parentFile?.mkdirs()
            f.outputStream().use { out ->
                out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                out.write(SPEAKER_SCRIPT.toByteArray(Charsets.UTF_8))
            }
            return f
        }
    }

    /**
     * 同步执行 PS 脚本。固定文件名 + synchronized 串行。
     *
     * 注意：探测脚本用完会删（因为探测频率低，删掉无所谓）。
     * 但朗读脚本不删（因为朗读可能在几秒内被连续调用，每次都重建文件反而浪费）。
     */
    private fun runPsSync(script: String, timeoutSec: Int): String? {
        synchronized(psLock) {
            return try {
                psProbeFile.parentFile?.mkdirs()
                psProbeFile.outputStream().use { out ->
                    out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                    out.write(script.toByteArray(Charsets.UTF_8))
                }
                val p = ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-File", psProbeFile.absolutePath
                ).redirectErrorStream(true).start()
                val finished = p.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
                if (!finished) {
                    p.destroyForcibly()
                    return null
                }
                p.inputStream.bufferedReader(Charsets.UTF_8).readText()
            } catch (_: Exception) { null }
            finally {
                try { psProbeFile.delete() } catch (_: Exception) { }
            }
        }
    }

    private val SPEAKER_SCRIPT: String = $$"""
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'
try {
    Add-Type -AssemblyName System.Speech | Out-Null
    $reader = New-Object System.IO.StreamReader([Console]::OpenStandardInput(), [System.Text.Encoding]::UTF8)
    $text = $reader.ReadToEnd()
    $reader.Close()
    if ([string]::IsNullOrEmpty($text)) {
        [Console]::Out.WriteLine("EMPTY")
        exit 0
    }
    $total = $text.Length
    $synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
    $synth.Rate = 0
    $synth.Volume = 100

    $chunkSize = 24
    $i = 0
    while ($i -lt $total) {
        $len = [Math]::Min($chunkSize, $total - $i)
        $chunk = $text.Substring($i, $len)
        $synth.Speak($chunk)
        $i += $len
        [Console]::Out.WriteLine("PROGRESS:" + $i + "/" + $total)
        [Console]::Out.Flush()
    }

    $synth.Dispose()
    [Console]::Out.WriteLine("DONE")
} catch {
    [Console]::Out.WriteLine("ERR:" + $_.Exception.Message)
    [Console]::Error.WriteLine($_.Exception.ToString())
    exit 1
}
""".trimIndent()
}