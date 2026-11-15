package net.xinkong.furagent.shared.util

import java.io.File

/**
 * 工作日记存储。
 *
 * 设计对齐 WorkBuddy 的分层记忆：**按天一篇**，一天内多次写入是追加，
 * 不做覆盖。这样既能「今天做了什么」一目了然，也不会因为 AI 一天写十次
 * 就把早期记录冲掉。
 *
 * 目录结构：
 * ```
 * {root}/diary/2026-11-15.md
 * {root}/diary/2026-11-14.md
 * ```
 *
 * 单篇格式：
 * ```
 * # 2026-11-15 星期日 工作日记
 *
 * ## 16:38 · 修复双端大文件传输
 * - 标签：传输、Android
 *
 * 正文……
 * ```
 *
 * 为什么单独存文件而不是塞进 memory.json：
 *  - 日记会持续增长，塞进记忆会污染 system prompt 且无限膨胀；
 *  - 按天分文件，AI 只读需要的日期，检索成本恒定；
 *  - 用户可以直接用文件管理器 / 编辑器打开查看。
 */
object DiaryStore {

    private const val DIR_NAME = "diary"
    private const val MAX_TITLE = 60
    private const val MAX_CONTENT = 8000

    private val writeLock = Any()

    data class WriteResult(
        val date: String,
        val path: String,
        /** 本篇是今天第几条 */
        val indexInDay: Int,
        val totalChars: Int
    )

    data class DiaryDay(
        val date: String,
        val entries: Int,
        val preview: String
    )

    fun dir(root: File): File = File(root, DIR_NAME)

    fun fileOf(root: File, date: String): File = File(dir(root), "$date.md")

    /**
     * 追加一条日记。
     *
     * @param title 一句话标题（必填，过长的会截断）
     * @param content 正文（必填）
     * @param tags 标签，便于以后按主题检索
     * @param sessionId 来源会话 id（可选，便于回溯）
     */
    fun write(
        root: File,
        title: String,
        content: String,
        tags: List<String> = emptyList(),
        sessionId: String = "",
        timeMillis: Long = System.currentTimeMillis()
    ): WriteResult {
        val cleanTitle = title.trim().replace('\n', ' ').take(MAX_TITLE).ifBlank { "工作记录" }
        val body = content.trim().take(MAX_CONTENT)
        require(body.isNotBlank()) { "日记正文不能为空" }

        val date = DateFormats.formatDate(timeMillis)
        val d = dir(root)
        if (!d.isDirectory && !d.mkdirs()) {
            error("无法创建日记目录：${d.absolutePath}")
        }
        val f = fileOf(root, date)

        val entry = StringBuilder()
        entry.append("## ").append(DateFormats.formatTimeShort(timeMillis))
            .append(" · ").append(cleanTitle).append('\n')
        if (tags.isNotEmpty()) {
            entry.append("- 标签：").append(tags.joinToString("、")).append('\n')
        }
        if (sessionId.isNotBlank()) {
            entry.append("- 会话：").append(sessionId).append('\n')
        }
        entry.append('\n').append(body).append("\n\n")

        synchronized(writeLock) {
            try {
                if (!f.exists()) {
                    val header = "# $date ${weekdayCn(timeMillis)} 工作日记\n\n"
                    f.writeText(header, Charsets.UTF_8)
                }
                f.appendText(entry.toString(), Charsets.UTF_8)
            } catch (e: Exception) {
                error("写入日记失败：${e.message ?: e.javaClass.simpleName}")
            }
            val text = try { f.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
            val count = Regex("(?m)^## ").findAll(text).count()
            return WriteResult(date, f.absolutePath, count, text.length)
        }
    }

    /**
     * 读取日记。
     *
     * @param date 指定日期 yyyy-MM-dd；为空则读最近 [days] 天
     * @param keyword 只返回包含该关键词的条目（按 ## 分段过滤）
     */
    fun read(
        root: File,
        date: String? = null,
        keyword: String? = null,
        days: Int = 1,
        maxChars: Int = 20_000
    ): String {
        val files = if (!date.isNullOrBlank()) {
            listOf(fileOf(root, date.trim())).filter { it.isFile }
        } else {
            allDayFiles(root).take(days.coerceIn(1, 60))
        }
        if (files.isEmpty()) return ""

        val sb = StringBuilder()
        for (f in files) {
            var text = try { f.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
            if (text.isBlank()) continue
            if (!keyword.isNullOrBlank()) text = filterSections(text, keyword)
            if (text.isBlank()) continue
            sb.append(text.trimEnd()).append("\n\n")
            if (sb.length >= maxChars) break
        }
        val out = sb.toString().trim()
        return if (out.length > maxChars) out.take(maxChars) + "\n…（日记已截断）" else out
    }

    /** 列出有日记的日期（新 → 旧）。 */
    fun listDays(root: File, limit: Int = 30): List<DiaryDay> {
        return allDayFiles(root).take(limit.coerceIn(1, 365)).mapNotNull { f ->
            val text = try { f.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
            if (text.isBlank()) return@mapNotNull null
            val date = f.nameWithoutExtension
            val entries = Regex("(?m)^## ").findAll(text).count()
            val preview = Regex("(?m)^## (.+)$").find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            DiaryDay(date, entries, preview)
        }
    }

    /** 读某天日记的原始全文（编辑器用，不做 trim / 过滤 / 截断）。 */
    fun raw(root: File, date: String): String {
        val f = fileOf(root, date)
        if (!f.isFile) return ""
        return try { f.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
    }

    /** 整体覆盖某天日记（编辑器即时保存用）；目录不存在会自动建，写盘走临时文件。 */
    fun overwrite(root: File, date: String, content: String) {
        val d = dir(root)
        if (!d.isDirectory && !d.mkdirs()) error("无法创建日记目录：${d.absolutePath}")
        synchronized(writeLock) {
            val tmp = File(d, "$date.md.tmp")
            val target = fileOf(root, date)
            tmp.writeText(content, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(content, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    /** 今天是否已经写过日记。 */
    fun hasToday(root: File): Boolean {
        val f = fileOf(root, DateFormats.formatDate())
        return f.isFile && f.length() > 0
    }

    /**
     * 取最近几天日记的摘要，注入 system prompt，让 AI 知道「昨天做到哪」。
     *
     * 只取每篇的最后 [maxCharsPerDay] 字：一天里最新的一条通常就是当前进度。
     */
    fun recentForPrompt(root: File, days: Int = 2, maxCharsPerDay: Int = 900): String {
        val files = allDayFiles(root).take(days.coerceIn(1, 7))
        if (files.isEmpty()) return ""
        val sb = StringBuilder()
        for (f in files) {
            val text = try { f.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
            if (text.isBlank()) continue
            val body = if (text.length > maxCharsPerDay) "…" + text.takeLast(maxCharsPerDay) else text
            sb.append(body.trim()).append("\n\n")
        }
        return sb.toString().trim()
    }

    // 内部

    private fun allDayFiles(root: File): List<File> {
        val d = dir(root)
        if (!d.isDirectory) return emptyList()
        val re = Regex("""^\d{4}-\d{2}-\d{2}\.md$""")
        return (d.listFiles() ?: emptyArray())
            .filter { it.isFile && re.matches(it.name) }
            .sortedByDescending { it.name }
    }

    /** 按 `## ` 分段，只保留含关键词的段（保留文件头）。 */
    private fun filterSections(text: String, keyword: String): String {
        val parts = text.split(Regex("(?m)^(?=## )"))
        if (parts.isEmpty()) return ""
        val head = parts.first().takeIf { !it.trimStart().startsWith("## ") }.orEmpty()
        val matched = parts.filter { it.trimStart().startsWith("## ") && it.contains(keyword, true) }
        if (matched.isEmpty()) return ""
        return (head.trimEnd() + "\n\n" + matched.joinToString("")).trim()
    }

    private fun weekdayCn(millis: Long): String =
        try { DateFormats.formatDateWeekday(millis).substringAfter(' ', "") } catch (_: Exception) { "" }
}
