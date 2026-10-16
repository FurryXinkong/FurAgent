package net.xinkong.furagent.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 从任意路径串里抽取文件名（去掉所有目录部分）。
 * 顺手清洗：换行、制表、控制字符、路径分隔符。
 * 空串时返回 unnamed_<ts>。
 */
fun extractFileName(path: String): String {
    val s = path.trim().trimEnd('/', '\\')
    val rawName = s.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = rawName
        .replace(Regex("[\\r\\n\\t\\u0000-\\u001F]"), "_")
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
    return cleaned.ifBlank { "unnamed_${System.currentTimeMillis()}" }
}

/**
 * 在指定目录下解析目标文件：
 * - 目录不存在则创建
 * - 若同名文件已存在，自动加时间戳（不会覆盖）
 *
 * B4：时间戳精确到毫秒，避免 1 秒内两次上传同名文件互相覆盖。
 */
fun resolveReceiveFile(dir: String, fileName: String): File {
    val dirFile = File(dir)
    if (!dirFile.exists()) dirFile.mkdirs()

    val safeName = fileName.ifBlank { "unnamed_${System.currentTimeMillis()}" }
    var target = File(dirFile, safeName)
    if (!target.exists()) return target

    val dot = safeName.lastIndexOf('.')
    val base = if (dot > 0) safeName.substring(0, dot) else safeName
    val ext = if (dot > 0) safeName.substring(dot) else ""
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
    var candidate = File(dirFile, "${base}_$ts$ext")
    // 极端情况下同毫秒还会冲突，加个短随机后缀兜底
    var tries = 0
    while (candidate.exists() && tries < 10) {
        candidate = File(dirFile, "${base}_${ts}_${tries}$ext")
        tries++
    }
    return candidate
}