package net.xinkong.furagent.shared.tools

import net.xinkong.furagent.shared.util.DateFormats
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 回收站工具（Android / Desktop 共用）。
 *
 * 【设计原则】AI 不得以任何方式真正删除文件。
 * 所有"删除"都改为"移入回收站"，由用户决定何时清空。
 *
 * 回收站目录：
 *   - 桌面：~/.furagent/temp/trash
 *   - Android：<filesDir>/temp/trash
 *
 * 每次移入会加时间戳前缀防重名，并往 manifest.jsonl 追加一条记录
 * （原始路径 / 回收站路径 / 是否目录 / 时间），供用户查看与恢复。
 */
object TrashHelper {

    /** 桌面端默认回收站目录。Android 端请显式传 trashDir。 */
    fun defaultTrashDir(): File {
        val home = System.getProperty("user.home") ?: "."
        return File(home, ".furagent/temp/trash")
    }

    /**
     * 把文件/目录移入回收站。
     *
     * 【数据安全】跨盘 renameTo 会失败，此时退化为"复制 + 校验 + 删源"。
     * 旧实现在复制后无条件删源，一旦复制中途 IO 出错（磁盘满 / 中断）会丢源文件。
     * 现在：复制成功 + 大小/存在性校验通过 → 才删源；否则清掉半成品、保留源。
     *
     * @param src      要"删除"的文件/目录
     * @param trashDir 回收站根目录；不传则用 defaultTrashDir()
     */
    fun moveToTrash(src: File, trashDir: File = defaultTrashDir()): TrashResult {
        return try {
            if (!src.exists()) return TrashResult(false, message = "文件不存在：${src.absolutePath}")

            // 不允许把回收站自己移进回收站（防套娃）
            val srcCanon = try { src.canonicalPath } catch (_: Exception) { src.absolutePath }
            val trashCanon = try { trashDir.canonicalPath } catch (_: Exception) { trashDir.absolutePath }
            if (srcCanon == trashCanon || srcCanon.startsWith(trashCanon + File.separator)) {
                return TrashResult(false, message = "回收站目录本身不能被删除")
            }

            if (!trashDir.exists() && !trashDir.mkdirs()) {
                return TrashResult(false, message = "无法创建回收站目录：${trashDir.absolutePath}")
            }

            // isDirectory 必须在删除前读取（deleteRecursively 之后再读会返回 false）
            val isDir = src.isDirectory

            // 时间戳前缀防重名：20261014_164500_123__原名
            val stamp = DateFormats.formatFileTs()
            val safeName = src.name.ifBlank { "unnamed" }
            var dest = File(trashDir, "${stamp}__$safeName")
            var seq = 1
            while (dest.exists()) {
                dest = File(trashDir, "${stamp}_${seq}__$safeName")
                seq++
            }

            // 【数据安全修复】
            // 旧实现：renameTo 失败 → 复制 → 无条件 deleteRecursively(src)。
            // 复制中途 IO 出错（磁盘满 / 中断）时，控制流会进 catch 返回失败，
            // 但如果 copyTo 只写了一部分就返回（某些 FS 上会发生），
            // 后面无条件删源 = 数据丢。
            // 新实现：复制 → 校验 → 通过才删源；失败清掉半成品、保留源。
            val moved: Boolean = try {
                if (src.renameTo(dest)) {
                    true
                } else {
                    // 跨盘符 renameTo 会失败，退化为复制 + 校验 + 删源
                    val copied = try {
                        if (isDir) {
                            src.copyRecursively(dest, overwrite = true)
                        } else {
                            dest.parentFile?.mkdirs()
                            src.copyTo(dest, overwrite = true)
                        }
                        true
                    } catch (_: Exception) {
                        false
                    }

                    if (!copied) {
                        return TrashResult(false, message = "移入回收站失败（复制失败，源文件未动）")
                    }

                    // 校验：目录存在即可；文件要求大小一致
                    val complete = if (isDir) {
                        dest.exists()
                    } else {
                        dest.exists() && dest.length() == src.length()
                    }

                    if (!complete) {
                        // 清掉半成品，保留源文件
                        try { dest.deleteRecursively() } catch (_: Exception) { }
                        return TrashResult(false, message = "移入回收站失败（复制不完整，源文件未动）")
                    }

                    src.deleteRecursively()
                }
            } catch (e: Exception) {
                return TrashResult(false, message = "移入回收站失败：${e.message}")
            }

            if (!moved || !dest.exists()) {
                return TrashResult(false, message = "移入回收站失败（可能是权限不足）")
            }

            appendManifest(trashDir, srcCanon, dest.absolutePath, isDir)
            TrashResult(true, trashPath = dest.absolutePath)
        } catch (e: Exception) {
            TrashResult(false, message = "移入回收站异常：${e.message}")
        }
    }

    /** 追加一条回收站记录（JSONL），供用户查看/恢复。 */
    private fun appendManifest(trashDir: File, originalPath: String, trashPath: String, isDir: Boolean) {
        try {
            val manifest = File(trashDir, "manifest.jsonl")
            val o = JSONObject()
            o.put("time", System.currentTimeMillis())
            o.put("original", originalPath)
            o.put("trash", trashPath)
            o.put("isDir", isDir)
            manifest.appendText(o.toString() + "\n", Charsets.UTF_8)
        } catch (_: Exception) {
            // manifest 写失败不影响删除本身
        }
    }
}