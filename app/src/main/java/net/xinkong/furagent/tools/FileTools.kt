package net.xinkong.furagent.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.core.AgentSession
import net.xinkong.furagent.core.ConfirmationBroker
import net.xinkong.furagent.data.AppSettings
import net.xinkong.furagent.shared.tools.TrashHelper
import net.xinkong.furagent.shared.util.DownloadEngine
import net.xinkong.furagent.shared.util.formatFileSize
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object FileTools {

    val TOOLS = setOf("file_op", "zip", "base64", "download_file")

    // ============================================================
    // 大小上限（【P2-8】从 ToolRegistry.kt 顶部移入）
    //
    // 这几个常量只被 FileTools 使用，放在通用 ToolRegistry 里是历史遗留。
    // 移到本类内部，作用域更清晰。
    // ============================================================
    private const val MAX_ZIP_TOTAL_BYTES = 512L * 1024 * 1024
    private const val MAX_UNZIP_TOTAL_BYTES = 1024L * 1024 * 1024
    private const val MAX_BASE64_DECODE_BYTES = 32L * 1024 * 1024

    // ============================================================
    // 公共辅助
    // ============================================================

    /**
     * 弹确认。
     * 返回 null 表示通过（或未开启确认），非 null 是要返回给 AI 的拒绝文案。
     */
    private suspend fun confirmOrReject(
        enabled: Boolean,
        title: String,
        message: String,
        rejectMessage: String
    ): String? {
        if (!enabled) return null
        val approved = ConfirmationBroker.request(title, message)
        return if (approved) null else rejectMessage
    }

    private fun previewText(content: String, limit: Int = 200): String =
        if (content.length > limit) content.take(limit) + "..." else content

    suspend fun execute(
        name: String, args: JSONObject, context: Context, settings: AppSettings
    ): String = when (name) {
        "file_op" -> doFileOp(args, context, settings)
        "zip" -> doZip(args, settings)
        "base64" -> doBase64(args, settings)
        "download_file" -> doDownloadFile(args, settings)
        else -> "未实现的文件工具：$name"
    }

    private suspend fun doFileOp(args: JSONObject, context: Context, settings: AppSettings): String {
        val op = args.optString("op", "").lowercase()
        return when (op) {
            "delete" -> doDelete(args, context, settings)
            "create" -> doCreate(args, settings)
            "rename" -> doRename(args, settings)
            "copy" -> doCopy(args, settings)
            "edit" -> doEdit(args, settings)
            else -> "失败：op 只能是 create / delete / rename / copy / edit"
        }
    }

    private suspend fun doZip(args: JSONObject, settings: AppSettings): String {
        val op = args.optString("op", "").lowercase()
        return when (op) {
            "zip" -> doZipFiles(args, settings)
            "unzip" -> doUnzipFile(args, settings)
            else -> "失败：op 只能是 zip / unzip"
        }
    }

    private suspend fun doBase64(args: JSONObject, settings: AppSettings): String {
        val op = args.optString("op", "").lowercase()
        return when (op) {
            "decode" -> doDecodeBase64(args, settings)
            "encode" -> doEncodeBase64(args, settings)
            else -> "失败：op 只能是 decode / encode"
        }
    }

    // ========================================================
    // file_op 实现
    // ========================================================

    private suspend fun doDelete(args: JSONObject, context: Context, settings: AppSettings): String {
        val path = normalizeAlias(args.optString("path", ""))
        PathGuard.check(path, settings)?.let { return it }
        val file = File(path)
        if (!file.exists()) return "文件不存在：$path"

        confirmOrReject(
            enabled = settings.requireConfirmDelete,
            title = "删除文件确认",
            message = "AI 请求删除：\n$path\n\n文件会移入回收站（可恢复），是否允许？",
            rejectMessage = "用户已拒绝删除操作，文件未删除"
        )?.let { return it }

        // 【安全删除】不真删，移入回收站，由用户决定何时清空。
        return try {
            val trashDir = File(context.filesDir, "temp/trash")
            val r = TrashHelper.moveToTrash(file, trashDir)
            if (r.ok) "已移入回收站：$path\n回收站位置：${r.trashPath}"
            else "删除失败：${r.message}"
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "删除文件失败（$path）：${e.javaClass.simpleName}: ${e.message}"
            )
            "删除失败：${e.message}"
        }
    }

    private suspend fun doCreate(args: JSONObject, settings: AppSettings): String {
        val path = normalizeAlias(args.optString("path", ""))
        val content = args.optString("content", "")
        PathGuard.check(path, settings)?.let { return it }
        val file = File(path)
        if (file.exists()) return "失败：文件已存在，如需修改请用 op=edit"

        confirmOrReject(
            enabled = settings.requireConfirmWrite,
            title = "创建文件确认",
            message = "AI 请求创建文件：\n$path\n\n内容预览：\n${previewText(content)}\n\n是否允许？",
            rejectMessage = "用户已拒绝创建操作，文件未创建"
        )?.let { return it }

        return try {
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            "已创建文件：$path（${content.length} 字符）"
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "创建文件失败（$path）：${e.javaClass.simpleName}: ${e.message}"
            )
            "创建失败：${e.message}"
        }
    }

    private suspend fun doRename(args: JSONObject, settings: AppSettings): String {
        val oldPath = normalizeAlias(args.optString("old_path", ""))
        val newPath = normalizeAlias(args.optString("new_path", ""))
        PathGuard.check(oldPath, settings)?.let { return it }
        PathGuard.check(newPath, settings)?.let { return it }
        val oldFile = File(oldPath)
        if (!oldFile.exists()) return "失败：源文件不存在 $oldPath"
        val newFile = File(newPath)
        if (newFile.exists()) return "失败：目标路径已存在 $newPath"

        confirmOrReject(
            enabled = settings.requireConfirmWrite,
            title = "重命名确认",
            message = "AI 请求重命名：\n从：$oldPath\n到：$newPath\n\n是否允许？",
            rejectMessage = "用户已拒绝重命名操作，文件未改动"
        )?.let { return it }

        return try {
            newFile.parentFile?.mkdirs()
            if (oldFile.renameTo(newFile)) "已重命名：$oldPath -> $newPath"
            else "失败：权限不足"
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "重命名失败（$oldPath -> $newPath）：${e.javaClass.simpleName}: ${e.message}"
            )
            "重命名失败：${e.message}"
        }
    }

    private suspend fun doCopy(args: JSONObject, settings: AppSettings): String {
        val src = normalizeAlias(args.optString("source", ""))
        val dst = normalizeAlias(args.optString("dest", ""))
        PathGuard.check(src, settings)?.let { return it }
        PathGuard.check(dst, settings)?.let { return it }
        val srcFile = File(src)
        if (!srcFile.exists()) return "失败：源文件不存在 $src"
        if (srcFile.isDirectory) return "失败：暂不支持复制目录 $src"
        val dstFile = File(dst)
        if (dstFile.exists()) return "失败：目标已存在 $dst"

        confirmOrReject(
            enabled = settings.requireConfirmWrite,
            title = "复制文件确认",
            message = "AI 请求复制：\n从：$src\n到：$dst\n\n是否允许？",
            rejectMessage = "用户已拒绝复制操作"
        )?.let { return it }

        return try {
            dstFile.parentFile?.mkdirs()
            srcFile.copyTo(dstFile, overwrite = false)
            "已复制：$src -> $dst（${formatFileSize(dstFile.length())}）"
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "复制文件失败（$src -> $dst）：${e.javaClass.simpleName}: ${e.message}"
            )
            "复制失败：${e.message}"
        }
    }

    private suspend fun doEdit(args: JSONObject, settings: AppSettings): String {
        val path = normalizeAlias(args.optString("path", ""))
        val content = args.optString("content", "")
        PathGuard.check(path, settings)?.let { return it }
        val file = File(path)
        if (!file.exists()) return "失败：文件不存在 $path"
        if (file.isDirectory) return "失败：这是一个目录而非文件 $path"

        confirmOrReject(
            enabled = settings.requireConfirmWrite,
            title = "编辑文件确认",
            message = "AI 请求覆盖写入：\n$path\n\n新内容预览：\n${previewText(content)}\n\n是否允许？",
            rejectMessage = "用户已拒绝编辑操作，文件未改动"
        )?.let { return it }

        return try {
            file.writeText(content, Charsets.UTF_8)
            "已更新文件：$path（${content.length} 字符）"
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "编辑文件失败（$path）：${e.javaClass.simpleName}: ${e.message}"
            )
            "编辑失败：${e.message}"
        }
    }

    // ========================================================
    // zip 实现
    // ========================================================

    private suspend fun doZipFiles(args: JSONObject, settings: AppSettings): String {
        val sourceDir = normalizeAlias(args.optString("source_dir", ""))
        val outputZip = normalizeAlias(args.optString("output_zip", ""))
        PathGuard.check(sourceDir, settings)?.let { return it }
        PathGuard.check(outputZip, settings)?.let { return it }
        val src = File(sourceDir)
        if (!src.exists()) return "失败：源目录不存在 $sourceDir"
        if (!src.isDirectory) return "失败：源不是目录 $sourceDir"
        if (File(outputZip).exists()) return "失败：目标 zip 已存在 $outputZip"

        var totalBytes = 0L
        src.walkTopDown().forEach {
            if (it.isFile) {
                totalBytes += it.length()
                if (totalBytes > MAX_ZIP_TOTAL_BYTES) {
                    return "失败：源目录总大小 ${formatFileSize(totalBytes)} 超过上限 ${formatFileSize(MAX_ZIP_TOTAL_BYTES)}"
                }
            }
        }

        confirmOrReject(
            enabled = settings.requireConfirmWrite,
            title = "压缩确认",
            message = "AI 请求压缩：\n$sourceDir\n到 $outputZip\n总大小：${formatFileSize(totalBytes)}\n\n是否允许？",
            rejectMessage = "用户已拒绝压缩操作"
        )?.let { return it }

        return try {
            val outFile = File(outputZip)
            var count = 0
            withContext(Dispatchers.IO) {
                outFile.parentFile?.mkdirs()
                val base = src.canonicalPath + File.separator
                ZipOutputStream(FileOutputStream(outFile)).use { zos ->
                    src.walkTopDown().forEach { f ->
                        if (f.isFile) {
                            val rel = f.canonicalPath.removePrefix(base)
                                .replace(File.separatorChar, '/')
                            zos.putNextEntry(ZipEntry(rel))
                            f.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                            count++
                        }
                    }
                }
            }
            "已压缩 $count 个文件到：$outputZip（${formatFileSize(outFile.length())}）"
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "压缩失败（$sourceDir -> $outputZip）：${e.javaClass.simpleName}: ${e.message}"
            )
            "压缩失败：${e.message}"
        }
    }

    private suspend fun doUnzipFile(args: JSONObject, settings: AppSettings): String {
        val zipPath = normalizeAlias(args.optString("zip_path", ""))
        val targetDir = normalizeAlias(args.optString("target_dir", ""))
        PathGuard.check(zipPath, settings)?.let { return it }
        PathGuard.check(targetDir, settings)?.let { return it }
        val zip = File(zipPath)
        if (!zip.exists()) return "失败：zip 不存在 $zipPath"
        if (!zip.isFile) return "失败：不是文件 $zipPath"

        confirmOrReject(
            enabled = settings.requireConfirmWrite,
            title = "解压确认",
            message = "AI 请求解压：\n$zipPath\n到 $targetDir\n\n是否允许？",
            rejectMessage = "用户已拒绝解压操作"
        )?.let { return it }

        return try {
            val target = File(targetDir)
            var count = 0
            var totalWritten = 0L
            withContext(Dispatchers.IO) {
                target.mkdirs()
                val targetCanonical = target.canonicalPath + File.separator
                ZipInputStream(FileInputStream(zip)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(target, entry.name)
                        if (!outFile.canonicalPath.startsWith(targetCanonical)) {
                            throw SecurityException("非法路径：${entry.name}")
                        }
                        if (entry.isDirectory) outFile.mkdirs()
                        else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out ->
                                val buf = ByteArray(8192)
                                while (true) {
                                    val n = zis.read(buf)
                                    if (n <= 0) break
                                    totalWritten += n
                                    if (totalWritten > MAX_UNZIP_TOTAL_BYTES) {
                                        throw SecurityException(
                                            "解压总大小超过上限 ${formatFileSize(MAX_UNZIP_TOTAL_BYTES)}"
                                        )
                                    }
                                    out.write(buf, 0, n)
                                }
                            }
                            count++
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            "已解压 $count 个文件到：$targetDir"
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "解压失败（$zipPath -> $targetDir）：${e.javaClass.simpleName}: ${e.message}"
            )
            "解压失败：${e.message}"
        }
    }

    // ========================================================
    // base64 实现
    // ========================================================

    private suspend fun doDecodeBase64(args: JSONObject, settings: AppSettings): String {
        val rawContent = args.optString("content", "").trim()
        val savePath = normalizeAlias(args.optString("save_path", ""))
        if (rawContent.isBlank()) return "失败：未提供 base64 内容"
        if (savePath.isBlank()) return "失败：未提供保存路径"
        PathGuard.check(savePath, settings)?.let { return it }

        var base64 = if (rawContent.startsWith("data:")) {
            rawContent.substringAfter(",", "")
        } else rawContent

        base64 = base64.replace(Regex("""\s+"""), "")
        base64 = base64.replace('-', '+').replace('_', '/')
        val mod = base64.length % 4
        if (mod != 0) base64 += "=".repeat(4 - mod)
        if (base64.isBlank()) return "失败：base64 内容为空"

        val bytes: ByteArray = try {
            android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        } catch (_: Exception) {
            try {
                android.util.Base64.decode(
                    base64,
                    android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                )
            } catch (e2: Exception) {
                AgentLogger.log(
                    "ERR",
                    "base64 解码失败（长度 ${base64.length}）：${e2.javaClass.simpleName}: ${e2.message}"
                )
                return "失败：base64 格式不正确（${base64.length} 字符）。"
            }
        }

        if (bytes.isEmpty()) return "失败：解码结果为空"
        if (bytes.size > MAX_BASE64_DECODE_BYTES) {
            return "失败：解码结果 ${formatFileSize(bytes.size.toLong())} 超过上限 ${formatFileSize(MAX_BASE64_DECODE_BYTES)}"
        }

        val isPng = bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        val isJpeg = bytes.size >= 2 &&
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        val isGif = bytes.size >= 6 &&
                bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()
        val isWebp = bytes.size >= 12 &&
                bytes[0] == 0x52.toByte() && bytes[8] == 0x57.toByte()
        val looksLikeImage = isPng || isJpeg || isGif || isWebp

        if (looksLikeImage && bytes.size < 512) {
            return "失败：解码成功但数据只有 ${bytes.size} 字节，太小了，不可能是一张完整图片。\n" +
                    "这通常意味着 AI 编造了 base64 内容，只生成了魔术字节开头。" +
                    "FurAgent 没有图像生成能力，请直接告知用户你无法生成图片。"
        }

        confirmOrReject(
            enabled = settings.requireConfirmWrite,
            title = "保存解码文件确认",
            message = "AI 请求保存 base64 解码结果：\n$savePath\n\n大小：${bytes.size} 字节\n\n是否允许？",
            rejectMessage = "用户已拒绝保存"
        )?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val f = File(savePath)
                f.parentFile?.mkdirs()
                f.writeBytes(bytes)
                val extra = if (looksLikeImage) {
                    VisionHelper.pushPendingImage(savePath)
                    "\n已自动附加到下一轮上下文，你可以直接看到图片内容。"
                } else ""
                "已解码并保存：$savePath（${formatFileSize(bytes.size.toLong())}）$extra"
            } catch (e: Exception) {
                AgentLogger.log(
                    "ERR",
                    "保存 base64 解码结果失败（$savePath）：${e.javaClass.simpleName}: ${e.message}"
                )
                "保存失败：${e.message}"
            }
        }
    }

    private fun doEncodeBase64(args: JSONObject, settings: AppSettings): String {
        val path = normalizeAlias(args.optString("path", ""))
        val maxSizeKb = args.optInt("max_size_kb", 512).coerceIn(1, 4096)
        if (path.isBlank()) return "失败：未提供文件路径"
        PathGuard.check(path, settings)?.let { return it }
        val file = File(path)
        if (!file.exists()) return "失败：文件不存在 $path"
        if (!file.isFile) return "失败：不是文件 $path"
        val sizeKb = file.length() / 1024
        if (sizeKb > maxSizeKb) {
            return "失败：文件过大（${formatFileSize(file.length())}），超过上限 ${maxSizeKb}KB"
        }
        return try {
            val bytes = file.readBytes()
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val mime = when (file.extension.lowercase(Locale.ROOT)) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "pdf" -> "application/pdf"
                "txt" -> "text/plain"
                else -> "application/octet-stream"
            }
            "文件：$path\n大小：${formatFileSize(file.length())}\nMIME：$mime\nbase64：\ndata:$mime;base64,$base64"
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "base64 编码失败（$path）：${e.javaClass.simpleName}: ${e.message}"
            )
            "编码失败：${e.message}"
        }
    }

    // ========================================================
    // download_file
    // ========================================================

    private suspend fun doDownloadFile(args: JSONObject, settings: AppSettings): String {
        val url = args.optString("url", "").trim()
        val savePath = normalizeAlias(args.optString("save_path", ""))
        if (url.isBlank()) return "失败：未提供 URL"
        if (savePath.isBlank()) return "失败：未提供保存路径"
        PathGuard.check(savePath, settings)?.let { return it }
        val target = File(savePath)

        val threads = args.optInt("threads", 1).coerceIn(1, 32)
        val proxy = args.optString("proxy", "").trim().ifBlank { null }
        val resume = args.optBoolean("resume", false)

        if (target.exists() && !resume) return "失败：目标文件已存在 $savePath"

        confirmOrReject(
            enabled = settings.requireConfirmWrite,
            title = "下载文件确认",
            message = "AI 请求下载：\nURL：$url\n保存到：$savePath\n" +
                    (if (threads > 1) "线程数：$threads\n" else "") +
                    (if (proxy != null) "代理：$proxy\n" else "") +
                    "\n是否允许？",
            rejectMessage = "用户已拒绝下载操作"
        )?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                // 记下当前取消代际：用户点「停止」时 AgentSession.cancelAll() 会递增代际，
                // 引擎的 isCancelled 回调据此中断下载。
                val gen = AgentSession.currentGeneration()
                val result = DownloadEngine.download(
                    url = url,
                    target = target,
                    threads = threads,
                    proxy = proxy,
                    resume = resume,
                    onProgress = { done, total ->
                        AgentLogger.log(
                            "INFO",
                            "下载中：${formatFileSize(done)}" +
                                    if (total > 0) " / ${formatFileSize(total)}" else ""
                        )
                    },
                    isCancelled = { AgentSession.isCancelled(gen) },
                    onCallCreated = { call -> AgentSession.setCall(call) }
                )

                if (!result.ok) {
                    "失败：${result.message}"
                } else {
                    val note = buildString {
                        append("已下载：$savePath（${formatFileSize(result.bytes)}）")
                        if (result.threadsUsed > 1) append("，${result.threadsUsed} 线程")
                        else if (threads > 1) append("，单线程（服务器不支持 Range 或文件过小）")
                        if (proxy != null) append("，经代理 $proxy")
                    }
                    note
                }
            } catch (e: Exception) {
                AgentLogger.log(
                    "ERR",
                    "下载文件失败（$url -> $savePath）：${e.javaClass.simpleName}: ${e.message}"
                )
                "下载失败：${e.message}"
            }
        }
    }
}