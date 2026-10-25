package net.xinkong.furagent.desktop.system

import net.xinkong.furagent.desktop.data.DesktopPaths
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * 临时文件清理器。
 *
 * 背景：temp 目录（~/.furagent/temp）此前没有任何自动清理逻辑，
 * 程序生成的临时脚本 / concat 清单 / 中间产物会一直堆积。
 *
 * 策略：
 *  - temp/ 目录：只清理「程序生成的」文件，按已知前缀白名单匹配。
 *    用户自己放进 temp 的文件（如手动拷进去的视频）不会被删。
 *  - received/ 目录：只清理 `.part` 后缀的残留文件
 *    （上传/下载中途中断会残留，HttpFileServer 用 .part 做临时文件后缀）。
 *  - 按「最后修改时间」判断，超过 N 天的才删。
 *  - N = 0 表示永久不清理。
 *  - 启动时执行一次；也可由设置页手动触发。
 */
object TempCleaner {

    /**
     * 程序生成的临时文件前缀白名单。
     *
     * 新增临时文件时，命名必须以此处任一前缀开头，否则不会被自动清理。
     */
    private val GENERATED_PREFIXES = listOf(
        "run_",          // PythonTools.run_code 的临时脚本
        "concat_",       // FfmpegTools concat 清单
        "ffmpeg_",       // FfmpegTools 中间产物
        "ffprobe_",      // FfmpegTools 探测输出
        "tmp_",          // 通用临时文件
        "furagent_"      // 通用前缀
    )

    /**
     * 【BUG 修复】固定名脚本黑名单：这些文件虽然以 `furagent_` 开头，
     * 但是程序**运行时依赖**的固定脚本（每次用前会覆盖写入，但删除后
     * 若正好在用会出问题）。它们**永不自动清理**。
     *
     * 对应：UiaScreenController / SmtcController / SystemIntegrationTools /
     *       DesktopSpeechHelper / ScheduleStore 里的固定脚本。
     */
    private val NEVER_DELETE_NAMES = setOf(
        "furagent_uia.ps1",
        "furagent_smtc.ps1",
        "furagent_sys.ps1",
        "furagent_speak.ps1",
        "furagent_cond.ps1",
        "furagent_tts_probe.ps1"
    )

    data class Result(
        val deleted: Int,
        val freedBytes: Long,
        val skipped: Int,
        val errors: Int
    ) {
        fun summary(): String {
            if (deleted == 0 && errors == 0) return "没有需要清理的临时文件"
            val size = formatSize(freedBytes)
            val sb = StringBuilder("已清理 $deleted 个临时文件，释放 $size")
            if (skipped > 0) sb.append("；跳过 $skipped 个（非程序生成或未到期）")
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

        // temp/：只清程序生成的前缀白名单
        val r1 = cleanupDir(
            dir = DesktopPaths.tempDir(),
            cutoff = cutoff,
            shouldDelete = { name -> isGenerated(name) }
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

    /** 统计 temp 目录中「程序生成的」文件占用（用于设置页展示）。 */
    fun generatedUsage(): Pair<Int, Long> {
        val dir = DesktopPaths.tempDir()
        if (!dir.exists()) return 0 to 0L
        var count = 0
        var bytes = 0L
        dir.listFiles()?.forEach { f ->
            if (f.isFile && isGenerated(f.name)) {
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

    private fun isGenerated(name: String): Boolean {
        if (name in NEVER_DELETE_NAMES) return false
        return GENERATED_PREFIXES.any { name.startsWith(it) }
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