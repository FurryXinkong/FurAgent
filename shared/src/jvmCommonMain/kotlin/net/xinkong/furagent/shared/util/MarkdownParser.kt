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
    data class InlineCode(val text: String) : MdSpan()
    data class Strikethrough(val text: String) : MdSpan()
    data class Link(val text: String, val url: String) : MdSpan()
}

object MarkdownParser {

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
                trimmed.matches(Regex("^\\s*([-*_])\\1{2,}\\s*$")) -> {
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
                trimmed.matches(Regex("^\\s*#{1,6}\\s+.*")) -> {
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
                trimmed.matches(Regex("^\\s*[-*+]\\s+.*")) -> {
                    val items = mutableListOf<String>()
                    while (i < lines.size && lines[i].matches(Regex("^\\s*[-*+]\\s+.*"))) {
                        items.add(lines[i].replaceFirst(Regex("^\\s*[-*+]\\s+"), ""))
                        i++
                    }
                    out.add(MdBlock.BulletList(items))
                    continue
                }

                // 有序列表
                trimmed.matches(Regex("^\\s*\\d+\\.\\s+.*")) -> {
                    val items = mutableListOf<String>()
                    val firstMatch = Regex("^\\s*(\\d+)\\.").find(lines[i])
                    val start = firstMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    while (i < lines.size && lines[i].matches(Regex("^\\s*\\d+\\.\\s+.*"))) {
                        items.add(lines[i].replaceFirst(Regex("^\\s*\\d+\\.\\s+"), ""))
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
                    // 【修复】段落内保留换行符，不再用空格连接。
                    //
                    // 旧实现把连续非空行用 " " 拼成一段，这是标准 Markdown 的
                    // "软换行 = 空格" 语义。但 FurAgent 的输入框里用户按一次
                    // Shift+Enter 就是想要一个换行，渲染成空格会让用户觉得
                    // "必须换两行才显示一次换行"。
                    //
                    // 现在用 "\n" 连接，渲染器按 \n 分行显示。
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
        if (t.matches(Regex("^[-*+]\\s+.*"))) return true
        if (t.matches(Regex("^\\d+\\.\\s+.*"))) return true
        if (t.matches(Regex("^([-*_])\\1{2,}\\s*$"))) return true
        if (isTableHeader(t) && idx + 1 < lines.size && isTableSeparator(lines[idx + 1])) return true
        return false
    }

    private fun isTableHeader(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("|") && t.endsWith("|") && t.count { it == '|' } >= 2
    }

    private fun isTableSeparator(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("|") && t.split("|").all { seg ->
            val s = seg.trim()
            s.isEmpty() || s.matches(Regex("^:?-{2,}:?$"))
        }
    }

    private fun splitTableRow(line: String): List<String> {
        val t = line.trim().removePrefix("|").removeSuffix("|")
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

            // 行内代码 `...`
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush()
                    out.add(MdSpan.InlineCode(text.substring(i + 1, end)))
                    i = end + 1
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
            if (c == '*' && i + 1 < n && text[i + 1] != '*' && text[i + 1] != ' ') {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1 && (end + 1 >= n || text[end + 1] != '*')) {
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
            if (c == '[') {
                val textEnd = text.indexOf(']', i + 1)
                if (textEnd > i + 1 && textEnd + 1 < n && text[textEnd + 1] == '(') {
                    val urlEnd = text.indexOf(')', textEnd + 2)
                    if (urlEnd > textEnd + 2) {
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