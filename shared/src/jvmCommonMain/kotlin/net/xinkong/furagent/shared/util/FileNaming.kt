package net.xinkong.furagent.shared.util

import net.xinkong.furagent.shared.util.DateFormats
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件名处理（双端共用）。
 *
 * 几乎逐字相同的 extractFileName / resolveReceiveFile 实现。现统一到这里。
 */
object FileNaming {

    private val RE_CONTROL = Regex("""[\r\n\t\u0000-\u001F]""")
    private val RE_ILLEGAL_FN = Regex("""[\\/:*?"<>|]""")

    fun extractFileName(path: String): String {
        val s = path.trim().trimEnd('/', '\\')
        val rawName = s.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = rawName
            .replace(RE_CONTROL, "_")
            .replace(RE_ILLEGAL_FN, "_")
            .trim()
        // 【N10/N11 修复】`.` 不是非法字符，`extractFileName("..")` 会原样返回 `..`。
        // 传给 resolveReceiveFile(dir, "..") 后 File(dir, "..") 指向父目录，
        // 走 conflict 分支生成 `dir/._ts.` 这种乱名，文件能落盘但用户找不到了。
        // HTTP 上传（两端 handleUpload）、relay 中转、write_file 路径改写都调这里，
        // 统一拦在最底层，避免每个调用方各补一遍。
        if (cleaned == "." || cleaned == "..") {
            return "unnamed_${System.currentTimeMillis()}"
        }
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
        val ts = DateFormats.formatFileTs()
        var candidate = File(dirFile, "${base}_$ts$ext")
        var tries = 0
        while (candidate.exists() && tries < 10) {
            candidate = File(dirFile, "${base}_${ts}_${tries}$ext")
            tries++
        }
        return candidate
    }
}
