package net.xinkong.furagent.shared.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件名处理（双端共用）。
 *
 * 【2026-10-15 抽公共】原来 Android PcReceiveDir.kt 和 桌面 DesktopSettings.kt 各有一份
 * 几乎逐字相同的 extractFileName / resolveReceiveFile 实现。现统一到这里。
 */
object FileNaming {

    fun extractFileName(path: String): String {
        val s = path.trim().trimEnd('/', '\\')
        val rawName = s.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = rawName
            .replace(Regex("[\\r\\n\\t\\u0000-\\u001F]"), "_")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
        return cleaned.ifBlank { "unnamed_${System.currentTimeMillis()}" }
    }

    fun resolveReceiveFile(dir: String, fileName: String): File {
        val dirFile = File(dir)
        if (!dirFile.exists()) dirFile.mkdirs()

        val safeName = fileName.ifBlank { "unnamed_${System.currentTimeMillis()}" }
        val target = File(dirFile, safeName)
        if (!target.exists()) return target

        val dot = safeName.lastIndexOf('.')
        val base = if (dot > 0) safeName.substring(0, dot) else safeName
        val ext = if (dot > 0) safeName.substring(dot) else ""
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        var candidate = File(dirFile, "${base}_$ts$ext")
        var tries = 0
        while (candidate.exists() && tries < 10) {
            candidate = File(dirFile, "${base}_${ts}_${tries}$ext")
            tries++
        }
        return candidate
    }
}
