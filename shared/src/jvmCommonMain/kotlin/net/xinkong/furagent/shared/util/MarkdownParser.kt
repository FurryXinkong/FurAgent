package net.xinkong.furagent.shared.util

/**
 * 轻量 Markdown 解析器。
 *
 * 只做"基础子集"：
 *   块级：标题（# / ## / ###）、无序列表（- * +）、有序列表（1.）、引用（>）、
 *         代码块（``` ... ```）、分隔线（---）、表格（| a | b |）、段落
 *   行内：**加粗**、*斜体*、`行内代码`、~~删除线~~、[文字](url)
 *
 * 不做的：嵌套列表、HTML、脚注、数学公式、图片（图片由上层单独处理）。
 */
sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class BulletList(val items: List<String>) : MdBlock()
    data class OrderedList(val start: Int, val items: List<String>) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class CodeBlock(val language: String, val code: String) : MdBlock()
    data object Divider : MdBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock()
}

sealed class MdSpan {
    data class Text(val text: String) : MdSpan()
    data class Bold(val text: String) : MdSpan()
    data class Italic(val text: String) : MdSpan()
    // 【B3 修复】***粗斜体*** 需要独立的 span 类型，
    // 否则 `***text***` 会被当成 bold(`*text`) + 单独的 `*`。
    data class BoldItalic(val text: String) : MdSpan()
    data class InlineCode(val text: String) : MdSpan()
    data class Strikethrough(val text: String) : MdSpan()
    data class Link(val text: String, val url: String) : MdSpan()
}

object MarkdownParser {

    // 预编译正则（原先在循环/方法内每次 Regex(...) 重新编译）
    private val RE_DIVIDER = Regex("""^\s*([-*_])\1{2,}\s*$""")
    private val RE_HEADING = Regex("""^\s*#{1,6}\s+.*""")
    private val RE_BULLET = Regex("""^\s*[-*+]\s+.*""")
    private val RE_ORDERED = Regex("""^\s*\d+\.\s+.*""")
    private val RE_BULLET_STRIP = Regex("""^\s*[-*+]\s+""")
    private val RE_ORDERED_FIRST = Regex("""^\s*(\d+)\.""")
    private val RE_ORDERED_STRIP = Regex("""^\s*\d+\.\s+""")
    private val RE_BLOCK_BULLET = Regex("""^[-*+]\s+.*""")
    private val RE_BLOCK_ORDERED = Regex("""^\d+\.\s+.*""")
    private val RE_BLOCK_DIVIDER = Regex("""^([-*_])\1{2,}\s*$""")
    // 【S3 修复】GFM 规范只要 1 个 '-' 就能当表格分隔符（如 |-|-|）；
    // 原来要求 2 个，导致 AI 输出的紧凑表格识别失败。
    private val RE_TABLE_SEP = Regex("""^:?-+:?$""")

    fun parseBlocks(text: String): List<MdBlock> {
        if (text.isBlank()) return emptyList()

        val out = mutableListOf<MdBlock>()
        val lines = text.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimEnd()

            when {
                // 代码块
                trimmed.trimStart().startsWith("```") -> {
                    val lang = trimmed.trimStart().removePrefix("```").trim()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        code.append(lines[i]).append("\n")
                        i++
                    }
                    if (i < lines.size) i++ // 跳过结尾 ```
                    out.add(MdBlock.CodeBlock(lang, code.toString().trimEnd('\n')))
                    continue
                }

                // 分隔线
                trimmed.matches(RE_DIVIDER) -> {
                    out.add(MdBlock.Divider)
                    i++
                    continue
                }

                // 表格
                isTableHeader(trimmed) && i + 1 < lines.size && isTableSeparator(lines[i + 1]) -> {
                    val headers = splitTableRow(trimmed)
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                        rows.add(splitTableRow(lines[i]))
                        i++
                    }
                    out.add(MdBlock.Table(headers, rows))
                    continue
                }

                // 标题
                trimmed.matches(RE_HEADING) -> {
                    val level = trimmed.takeWhile { it == '#' || it == ' ' }.count { it == '#' }
                    val content = trimmed.trimStart().removePrefix("#".repeat(level)).trimStart()
                    out.add(MdBlock.Heading(level.coerceIn(1, 6), content))
                    i++
                    continue
                }

                // 引用（合并连续 > 行）
                trimmed.trimStart().startsWith(">") -> {
                    val sb = StringBuilder()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        sb.append(lines[i].trimStart().removePrefix(">").trimStart()).append("\n")
                        i++
                    }
                    out.add(MdBlock.Quote(sb.toString().trimEnd('\n')))
                    continue
                }

                // 无序列表
                trimmed.matches(RE_BULLET) -> {
                    val items = mutableListOf<String>()
                    while (i < lines.size && lines[i].matches(RE_BULLET)) {
                        items.add(lines[i].replaceFirst(RE_BULLET_STRIP, ""))
                        i++
                    }
                    out.add(MdBlock.BulletList(items))
                    continue
                }

                // 有序列表
                trimmed.matches(RE_ORDERED) -> {
                    val items = mutableListOf<String>()
                    val firstMatch = RE_ORDERED_FIRST.find(lines[i])
                    val start = firstMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    while (i < lines.size && lines[i].matches(RE_ORDERED)) {
                        items.add(lines[i].replaceFirst(RE_ORDERED_STRIP, ""))
                        i++
                    }
                    out.add(MdBlock.OrderedList(start, items))
                    continue
                }

                // 空行
                trimmed.isBlank() -> {
                    i++
                    continue
                }

                // 段落
                else -> {
                    val sb = StringBuilder()
                    while (i < lines.size
                        && lines[i].isNotBlank()
                        && !isBlockStart(lines[i], lines, i)
                    ) {
                        if (sb.isNotEmpty()) sb.append("\n")
                        sb.append(lines[i].trim())
                        i++
                    }
                    if (sb.isNotEmpty()) out.add(MdBlock.Paragraph(sb.toString()))
                    else i++
                    continue
                }
            }
        }
        return out
    }

    private fun isBlockStart(line: String, lines: List<String>, idx: Int): Boolean {
        val t = line.trimStart()
        if (t.startsWith("```")) return true
        if (t.startsWith("#")) return true
        if (t.startsWith(">")) return true
        if (t.matches(RE_BLOCK_BULLET)) return true
        if (t.matches(RE_BLOCK_ORDERED)) return true
        if (t.matches(RE_BLOCK_DIVIDER)) return true
        if (isTableHeader(t) && idx + 1 < lines.size && isTableSeparator(lines[idx + 1])) return true
        return false
    }

    private fun isTableHeader(line: String): Boolean {
        val t = line.trim()
        // 【A3 修复】GFM 规范允许首尾不带 '|'，例如 "a | b"。
        // 只要含至少 1 个 '|' 且不是分隔符本身，就当表格头。
        val pipeCount = t.count { it == '|' }
        if (pipeCount < 1) return false
        // 排除分隔符本身（如 "|---|"）：必须有非 '-'、非 ':'、非 '|'、非空白的字符
        if (t.all { it == '|' || it == '-' || it == ':' || it.isWhitespace() }) return false
        return true
    }

    private fun isTableSeparator(line: String): Boolean {
        val t = line.trim()
        // 【A3 修复】首尾不必带 '|'（GFM 规范允许 "a | b"）
        if (t.count { it == '|' } < 1) return false
        val segs = t.split("|")
        var nonEmpty = 0
        for (seg in segs) {
            val s = seg.trim()
            if (s.isEmpty()) continue
            nonEmpty++
            if (!s.matches(RE_TABLE_SEP)) return false
        }
        return nonEmpty >= 1
    }

    private fun splitTableRow(line: String): List<String> {
        // 【A3 修复】首尾 '|' 可有可无，不强制 strip
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|")) t = t.substring(0, t.length - 1)
        return t.split("|").map { it.trim() }
    }

    /**
     * 解析行内 Markdown。
     * 覆盖：`code`、**bold**、*italic*、~~strike~~、[text](url)。
     * 优先级：code > bold > italic > strike > link。
     */
    fun parseInline(text: String): List<MdSpan> {
        if (text.isEmpty()) return emptyList()

        val out = mutableListOf<MdSpan>()
        val sb = StringBuilder()
        var i = 0
        val n = text.length

        fun flush() {
            if (sb.isNotEmpty()) {
                out.add(MdSpan.Text(sb.toString()))
                sb.clear()
            }
        }

        while (i < n) {
            val c = text[i]

            // 行内代码 `...`，支持双反引号 ``...``（GFM 规范）
            // 【B2 修复】原实现遇到 `` 就截断错了，因为第一个 backtick 会匹配到
            // 内容里的第二个 backtick。GFM 用「长度相同的反引号」作为定界符。
            if (c == '`') {
                // 计算起始反引号的数量
                var startCount = 1
                while (i + startCount < n && text[i + startCount] == '`') startCount++
                val delim = "`".repeat(startCount)
                // 从 i + startCount 开始找长度相同的闭合反引号
                var end = text.indexOf(delim, i + startCount)
                // 要确保这个 delim 后面不是更长的反引号序列（否则会截短）
                while (end >= 0 && end + startCount < n && text[end + startCount] == '`') {
                    end = text.indexOf(delim, end + 1)
                }
                if (end > i + startCount) {
                    flush()
                    var code = text.substring(i + startCount, end)
                    // GFM 规范：双反引号时，去掉首尾各一个空格
                    if (startCount >= 2 && code.startsWith(" ") && code.endsWith(" ") && code.length > 1) {
                        code = code.substring(1, code.length - 1)
                    }
                    out.add(MdSpan.InlineCode(code))
                    i = end + startCount
                    continue
                }
            }

            // 粗斜体 ***...***（必须在 ** 之前检查，否则会被拆成 bold + italic）
            // 【B3 修复】`***text***` 当前会先匹配 `**` 成 bold，剩下 `*text*` 变
            // 额外 italic —— 顺序反了。必须先消费 `***`。
            if (c == '*' && i + 2 < n && text[i + 1] == '*' && text[i + 2] == '*' &&
                !text[i + 3.coerceAtMost(n - 1)].isWhitespace()
            ) {
                val end = text.indexOf("***", i + 3)
                if (end > i + 3 && !text[end - 1].isWhitespace()) {
                    flush()
                    out.add(MdSpan.BoldItalic(text.substring(i + 3, end)))
                    i = end + 3
                    continue
                }
            }

            // 加粗 **...**
            if (c == '*' && i + 1 < n && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    flush()
                    out.add(MdSpan.Bold(text.substring(i + 2, end)))
                    i = end + 2
                    continue
                }
            }

            // 斜体 *...*（不跨 **）
            // 【S2 修复】GFM 规范：
            //   - 起始 '*' 后面不能是空白
            //   - 结尾 '*' 前面不能是空白
            // 否则 `*a * b*` 里的 `a ` 会被误判成斜体。
            if (c == '*' && i + 1 < n && text[i + 1] != '*' && !text[i + 1].isWhitespace()) {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1
                    && (end + 1 >= n || text[end + 1] != '*')
                    && !text[end - 1].isWhitespace()
                ) {
                    flush()
                    out.add(MdSpan.Italic(text.substring(i + 1, end)))
                    i = end + 1
                    continue
                }
            }

            // 删除线 ~~...~~
            if (c == '~' && i + 1 < n && text[i + 1] == '~') {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 2) {
                    flush()
                    out.add(MdSpan.Strikethrough(text.substring(i + 2, end)))
                    i = end + 2
                    continue
                }
            }

            // 链接 [text](url)
            // 【S1 修复】URL 里可能有 '(' / ')'（如维基百科条目），
            // 直接 indexOf(')') 会在第一个 ')' 处截断。
            // 改成括号深度匹配。
            if (c == '[') {
                val textEnd = text.indexOf(']', i + 1)
                if (textEnd > i + 1 && textEnd + 1 < n && text[textEnd + 1] == '(') {
                    var depth = 1
                    var urlEnd = textEnd + 2
                    while (urlEnd < n && depth > 0) {
                        when (text[urlEnd]) {
                            '(' -> depth++
                            ')' -> depth--
                        }
                        if (depth > 0) urlEnd++
                    }
                    if (depth == 0 && urlEnd > textEnd + 2) {
                        flush()
                        out.add(MdSpan.Link(
                            text.substring(i + 1, textEnd),
                            text.substring(textEnd + 2, urlEnd)
                        ))
                        i = urlEnd + 1
                        continue
                    }
                }
            }

            sb.append(c)
            i++
        }
        flush()
        return out
    }
}