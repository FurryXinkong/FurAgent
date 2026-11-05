package net.xinkong.furagent.desktop.system

import net.xinkong.furagent.desktop.data.DesktopPaths
import net.xinkong.furagent.desktop.system.TempCleaner.PROTECTED_NAMES
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * 临时文件清理器。
 *
 * 背景：temp 目录（~/.furagent/temp）此前没有任何自动清理逻辑，
 * 程序生成的临时脚本 / concat 清单 / 中间产物会一直堆积。
 *
 * 旧策略（已废弃）：按「已知前缀白名单」匹配，只删 run_ / concat_ / tmp_ 等前缀的文件。
 * 问题：AI 生成的文件名不会遵守前缀约定（AI 不会记得规定），
 * 导致大量文件永远不被清理，temp 目录无限膨胀。
 *
 * 新策略：**黑名单保护 + 其余全删**。
 *  - temp/ 目录：除了 [PROTECTED_NAMES] 里列出的运行时依赖文件，其余全部按时间清理。
 *  - received/ 目录：只清 `.part` 后缀的残留文件
 *    （上传/下载中途中断会残留，HttpFileServer 用 .part 做临时文件后缀）。
 *  - 按「最后修改时间」判断，超过 N 天的才删。
 *  - N = 0 表示永久不清理。
 *  - 启动时执行一次；也可由设置页手动触发。
 *
 * 为什么用黑名单而不是白名单：
 * 白名单要求「所有写入方都遵守命名约定」，而写入方包括 AI（不可控）；
 * 黑名单只要求「保护少数几个固定文件」，这些文件由程序自己写入，可控。
 * 默认安全（默认删）比默认保留更符合 temp 目录的语义。
 */
object TempCleaner {

    /**
     * 受保护文件名：这些文件是程序**运行时依赖**的固定脚本，
     * 每次用前会覆盖写入，但删除后若正好在用会出问题。它们**永不自动清理**。
     *
     * 对应：UiaScreenController / SmtcController / SystemIntegrationTools /
     *       DesktopSpeechHelper / ScheduleStore 里的固定脚本。
     */
    private val PROTECTED_NAMES = setOf(
        "furagent_uia.ps1",
        "furagent_smtc.ps1",
        "furagent_sys.ps1",
        "furagent_speak.ps1",
        "furagent_cond.ps1",
        "furagent_tts_probe.ps1",
        // Chaquopy / 桌面端共用的固定解析脚本，程序运行时依赖，删了会直接报错
        "_skill_parser.py"
    )

    /**
     * 受保护文件名前缀：以这些前缀开头的文件永不清理。
     * 用于程序自己写入、名字带动态后缀的固定文件（如 furagent_uia_<pid>.ps1）。
     */
    private val PROTECTED_PREFIXES = listOf(
        "furagent_uia",
        "furagent_smtc",
        "furagent_sys",
        "furagent_speak",
        "furagent_cond",
        "furagent_tts_probe"
    )

    data class Result(
        val deleted: Int,
        val freedBytes: Long,
        val skipped: Int,
        val errors: Int
    ) {
        fun summary(): String {
            val size = formatSize(freedBytes)
            val sb = StringBuilder("已清理 $deleted 个临时文件，释放 $size")
            if (skipped > 0) sb.append("；跳过 $skipped 个（受保护或未到期）")
            if (errors > 0) sb.append("；$errors 个删除失败")
            return sb.toString()
        }
    }

    /**
     * 按天数清理。
     *
     * @param days 保留天数。<= 0 表示永久不清理，直接返回。
     */
    fun cleanup(days: Int): Result {
        if (days <= 0) return Result(0, 0L, 0, 0)

        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())

        // temp/：除受保护文件外全部清理
        val r1 = cleanupDir(
            dir = DesktopPaths.tempDir(),
            cutoff = cutoff,
            shouldDelete = { name -> !isProtected(name) }
        )

        // received/：只清 .part 残留（HttpFileServer 上传/下载中途中断留下的）
        val r2 = cleanupDir(
            dir = DesktopPaths.receivedDir(),
            cutoff = cutoff,
            shouldDelete = { name -> name.endsWith(".part", ignoreCase = true) }
        )

        val total = Result(
            deleted = r1.deleted + r2.deleted,
            freedBytes = r1.freedBytes + r2.freedBytes,
            skipped = r1.skipped + r2.skipped,
            errors = r1.errors + r2.errors
        )

        if (total.deleted > 0 || total.errors > 0) {
            DesktopLogger.info(
                "TempCleaner",
                "清理完成（保留 $days 天）：删除 ${total.deleted} 个，释放 ${formatSize(total.freedBytes)}，失败 ${total.errors} 个"
            )
        }
        return total
    }

    /** 启动时调用：按当前设置清理一次。 */
    fun cleanupOnStartup(days: Int) {
        try {
            cleanup(days)
        } catch (e: Exception) {
            DesktopLogger.warn("TempCleaner", "启动清理异常：${e.message}")
        }
    }

    /** 统计 temp 目录中「会被清理的」文件占用（用于设置页展示）。 */
    fun generatedUsage(): Pair<Int, Long> {
        val dir = DesktopPaths.tempDir()
        if (!dir.exists()) return 0 to 0L
        var count = 0
        var bytes = 0L
        dir.listFiles()?.forEach { f ->
            if (f.isFile && !isProtected(f.name)) {
                count++
                bytes += f.length()
            }
        }
        return count to bytes
    }

    // 内部实现

    private fun cleanupDir(
        dir: File,
        cutoff: Long,
        shouldDelete: (name: String) -> Boolean
    ): Result {
        if (!dir.exists() || !dir.isDirectory) return Result(0, 0L, 0, 0)

        var deleted = 0
        var freed = 0L
        var skipped = 0
        var errors = 0

        val files = dir.listFiles() ?: return Result(0, 0L, 0, 0)
        for (f in files) {
            try {
                // 子目录：递归清理（AI 可能建子目录放中间产物）
                if (f.isDirectory) {
                    val sub = cleanupDir(f, cutoff, shouldDelete)
                    deleted += sub.deleted
                    freed += sub.freedBytes
                    skipped += sub.skipped
                    errors += sub.errors
                    // 子目录空了就一并删掉
                    if (f.listFiles()?.isEmpty() == true) f.delete()
                    continue
                }
                if (!f.isFile) continue
                if (!shouldDelete(f.name)) { skipped++; continue }
                if (f.lastModified() >= cutoff) { skipped++; continue }
                val size = f.length()
                if (f.delete()) {
                    deleted++
                    freed += size
                } else {
                    errors++
                }
            } catch (e: Exception) {
                errors++
                DesktopLogger.warn("TempCleaner", "删除失败：${f.name} - ${e.message}")
            }
        }
        return Result(deleted, freed, skipped, errors)
    }

    private fun isProtected(name: String): Boolean {
        if (name in PROTECTED_NAMES) return true
        return PROTECTED_PREFIXES.any { name.startsWith(it) }
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 ->
            String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 ->
            String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024L ->
            String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
