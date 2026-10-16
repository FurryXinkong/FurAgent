package net.xinkong.furagent.desktop.system

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.xinkong.furagent.desktop.data.DesktopPaths
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Windows SMTC（System Media Transport Controls）控制器。
 *
 * 用途：读取"当前在放什么歌"以及精确控制（比键盘事件可靠）。
 * 依赖：Windows 10 1809+ 的 Windows.Media.Control WinRT API。
 *
 * 【临时文件策略】
 *  固定名脚本落 ~/.furagent/temp/furagent_smtc.ps1（TempCleaner 会按 mtime 清理）。
 *  禁止落系统 %TEMP%——TempCleaner 扫不到那个目录，会永久残留。
 *
 * 【串行保证】
 *  runPs 用协程 Mutex.withLock 串行，挂起而非阻塞线程。
 *  SMTC 是低频操作，串行代价可接受。
 */
object SmtcController {

    data class NowPlaying(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val app: String = "",
        val status: String = "",
        val positionMs: Long = 0L,
        val durationMs: Long = 0L
    )

    data class ControlOutcome(
        val ok: Boolean,
        val method: String,
        val before: String = "",
        val after: String = "",
        val raw: String = ""
    )

    @Volatile private var cachedAvailable: Boolean? = null

    /** 串行保护 + 固定文件名 */
    private val psMutex = Mutex()

    /** 固定名脚本落 ~/.furagent/temp/，不再落系统 %TEMP% */
    private val psScriptFile: File by lazy {
        File(DesktopPaths.tempDir(), "furagent_smtc.ps1")
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    suspend fun isAvailable(): Boolean {
        if (!isWindows()) return false
        cachedAvailable?.let { return it }
        val v = try {
            val out = runPs(PROBE_SCRIPT, 8)
            out != null && out.contains("SMTC_OK")
        } catch (_: Exception) { false }
        cachedAvailable = v
        return v
    }

    fun clearCache() {
        cachedAvailable = null
    }

    suspend fun nowPlaying(): NowPlaying? {
        if (!isWindows()) return null
        val out = runPs(NOW_PLAYING_SCRIPT, 12) ?: return null
        if (out.contains("NO_SESSION")) return null
        return parseNowPlaying(out)
    }

    suspend fun control(action: String): ControlOutcome {
        if (!isWindows()) return ControlOutcome(false, "none")
        val act = action.lowercase()
        val call = when (act) {
            "play" -> $$"$s.TryPlayAsync()"
            "pause" -> $$"$s.TryPauseAsync()"
            "toggle", "play_pause" -> $$"$s.TryTogglePlayPauseAsync()"
            "next" -> $$"$s.TrySkipNextAsync()"
            "prev", "previous" -> $$"$s.TrySkipPreviousAsync()"
            "stop" -> $$"$s.TryStopAsync()"
            else -> return ControlOutcome(false, "invalid")
        }
        val script = buildControlScript(call)
        val out = runPs(script, 12) ?: return ControlOutcome(false, act)
        return parseControlResult(act, out)
    }

    suspend fun controlWithFallback(action: String): ControlOutcome {
        val primary = control(action)
        if (primary.ok) return primary
        if (action.equals("stop", ignoreCase = true)) {
            val alt = control("pause")
            if (alt.ok) {
                return ControlOutcome(
                    ok = true,
                    method = "pause",
                    before = alt.before,
                    after = alt.after,
                    raw = alt.raw
                )
            }
        }
        return primary
    }

    suspend fun seek(positionMs: Long): Boolean {
        if (!isWindows()) return false
        if (positionMs < 0) return false
        val ticks = positionMs * 10_000L
        val script = $$"$$HEADER\n$$SESSION_BOOTSTRAP\nif (-not $s) { Write-Output \"NO_SESSION\"; exit }\n" +
                $$"$task = $s.TryChangePlaybackPositionAsync($$ticks)\n" +
                $$"$ok = $false\n" +
                "try {\n" +
                $$"  if ($task -is [bool]) { $ok = $task }\n" +
                "  else {\n" +
                $$"    $deadline = (Get-Date).AddSeconds(3)\n" +
                $$"    while ((Get-Date) -lt $deadline) {\n" +
                $$"      if ($task.Status.ToString() -ne 'Started') { break }\n" +
                "      Start-Sleep -Milliseconds 30\n" +
                "    }\n" +
                $$"    if ($task.Status.ToString() -eq 'Completed') { $ok = $task.GetResults() }\n" +
                "  }\n" +
                $$"} catch { $ok = $false }\n" +
                $$"Write-Output (\"RESULT=\" + $ok)\n" +
                FOOTER
        val out = runPs(script, 10) ?: return false
        return out.lineSequence()
            .firstOrNull { it.startsWith("RESULT=") }
            ?.removePrefix("RESULT=")
            ?.trim()
            ?.equals("True", ignoreCase = true) == true
    }

    // ============================================================
    // 脚本片段
    // ============================================================

    private fun buildControlScript(call: String): String {
        return $$"$$HEADER\n$$SESSION_BOOTSTRAP\nif (-not $s) { Write-Output \"NO_SESSION\"; exit }\n" +
                $$"$before = \"\"\n" +
                $$"try { $before = $s.GetPlaybackInfo().PlaybackStatus.ToString() } catch { }\n" +
                $$"Write-Output (\"BEFORE=\" + $before)\n" +
                $$"$task = $$call\n" +
                $$"$ok = $false\n" +
                "try {\n" +
                $$"  if ($task -is [bool]) { $ok = $task }\n" +
                "  else {\n" +
                $$"    $deadline = (Get-Date).AddSeconds(3)\n" +
                $$"    while ((Get-Date) -lt $deadline) {\n" +
                $$"      if ($task.Status.ToString() -ne 'Started') { break }\n" +
                "      Start-Sleep -Milliseconds 30\n" +
                "    }\n" +
                $$"    if ($task.Status.ToString() -eq 'Completed') { $ok = $task.GetResults() }\n" +
                "  }\n" +
                $$"} catch { $ok = $false }\n" +
                "Start-Sleep -Milliseconds 200\n" +
                $$"$after = \"\"\n" +
                $$"try { $after = $s.GetPlaybackInfo().PlaybackStatus.ToString() } catch { }\n" +
                $$"Write-Output (\"AFTER=\" + $after)\n" +
                $$"Write-Output (\"RESULT=\" + $ok)\n" +
                FOOTER
    }

    private const val HEADER = $$"""
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'
try {
  Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null
  $asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
  })[0]
  function Await($WinRtTask, $ResultType) {
    if ($WinRtTask -is [bool]) { return $WinRtTask }
    $asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
    $netTask = $asTask.Invoke($null, @($WinRtTask))
    $netTask.Wait(-1) | Out-Null
    $netTask.Result
  }
  [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime] | Out-Null
"""

    private const val SESSION_BOOTSTRAP = $$"""
  $mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
  $s = if ($mgr) { $mgr.GetCurrentSession() } else { $null }
"""

    private const val FOOTER = $$"""
} catch {
  Write-Output ("ERROR=" + $_.Exception.Message)
}
"""

    private const val PROBE_SCRIPT = HEADER + $$"""
  Write-Output "SMTC_OK"
} catch {
  Write-Output ("ERROR=" + $_.Exception.Message)
}
"""

    private const val NOW_PLAYING_SCRIPT = HEADER + """
  [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties,Windows.Media.Control,ContentType=WindowsRuntime] | Out-Null
""" + SESSION_BOOTSTRAP + $$"""
  if (-not $s) { Write-Output "NO_SESSION"; exit }
  Write-Output ("APP=" + $s.SourceAppUserModelId)
  try {
    $props = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
    if ($props) {
      Write-Output ("TITLE=" + $props.Title)
      Write-Output ("ARTIST=" + $props.Artist)
      Write-Output ("ALBUM=" + $props.AlbumTitle)
    }
  } catch {}
  try {
    $info = $s.GetPlaybackInfo()
    if ($info) { Write-Output ("STATUS=" + $info.PlaybackStatus) }
  } catch {}
  try {
    $tl = $s.GetTimelineProperties()
    if ($tl) {
      Write-Output ("POSITION_MS=" + [math]::Floor($tl.Position.TotalMilliseconds))
      Write-Output ("DURATION_MS=" + [math]::Floor($tl.EndTime.TotalMilliseconds))
    }
  } catch {}
""" + FOOTER

    // ============================================================
    // 解析
    // ============================================================

    private fun parseNowPlaying(raw: String): NowPlaying {
        var title = ""
        var artist = ""
        var album = ""
        var app = ""
        var status = ""
        var positionMs = 0L
        var durationMs = 0L

        raw.lines().forEach { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) return@forEach
            val k = line.substring(0, idx).trim()
            val v = line.substring(idx + 1).trim()
            when (k) {
                "TITLE" -> title = v
                "ARTIST" -> artist = v
                "ALBUM" -> album = v
                "APP" -> app = shortApp(v)
                "STATUS" -> status = v
                "POSITION_MS" -> positionMs = v.toLongOrNull() ?: 0L
                "DURATION_MS" -> durationMs = v.toLongOrNull() ?: 0L
            }
        }
        return NowPlaying(
            title = title,
            artist = artist,
            album = album,
            app = app,
            status = status,
            positionMs = positionMs,
            durationMs = durationMs
        )
    }

    private fun parseControlResult(action: String, raw: String): ControlOutcome {
        val before = raw.lineSequence().firstOrNull { it.startsWith("BEFORE=") }
            ?.removePrefix("BEFORE=")?.trim() ?: ""
        val after = raw.lineSequence().firstOrNull { it.startsWith("AFTER=") }
            ?.removePrefix("AFTER=")?.trim() ?: ""
        val result = raw.lineSequence().firstOrNull { it.startsWith("RESULT=") }
            ?.removePrefix("RESULT=")?.trim() ?: ""
        val tryOk = result.equals("True", ignoreCase = true)

        val ok = when (action) {
            "play" -> after == "Playing"
            "pause" -> after == "Paused"
            "stop" -> after == "Stopped" || after == "Paused" || after == "Closed"
            "toggle", "play_pause" -> {
                (before.isNotBlank() && after.isNotBlank() && before != after) || tryOk
            }
            "next", "prev", "previous" -> tryOk
            else -> tryOk
        }
        return ControlOutcome(ok, action, before, after, raw)
    }

    private fun shortApp(aumid: String): String {
        if (aumid.isBlank()) return ""
        val before = aumid.substringBefore('!')
        val noHash = before.substringBeforeLast('_')
        return noHash.ifBlank { before }
    }

    /**
     * suspend + Mutex.withLock。
     * 挂起而非阻塞，不占用线程池。串行代价可接受（SMTC 是低频操作）。
     */
    private suspend fun runPs(script: String, timeoutSec: Int): String? {
        if (!isWindows()) return null
        return psMutex.withLock {
            try {
                val tmp = psScriptFile
                tmp.parentFile?.mkdirs()
                tmp.outputStream().use { out ->
                    out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                    out.write(script.toByteArray(Charsets.UTF_8))
                }
                val pb = ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-File", tmp.absolutePath
                )
                pb.redirectErrorStream(true)
                val p = pb.start()
                val finished = p.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
                if (!finished) {
                    try { p.destroyForcibly() } catch (_: Exception) { }
                    return@withLock null
                }
                p.inputStream.bufferedReader(Charsets.UTF_8).readText()
            } catch (_: Exception) {
                null
            } finally {
                try { psScriptFile.delete() } catch (_: Exception) { }
            }
        }
    }
}