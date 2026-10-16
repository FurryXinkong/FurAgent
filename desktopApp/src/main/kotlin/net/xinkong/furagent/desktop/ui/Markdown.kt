package net.xinkong.furagent.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import net.xinkong.furagent.shared.util.DisplayWidth
import net.xinkong.furagent.shared.util.MarkdownParser
import net.xinkong.furagent.shared.util.MdBlock
import net.xinkong.furagent.shared.util.MdSpan

/**
 * 桌面端 Markdown 渲染器（对齐 Android AndroidMarkdownRenderer）。
 *
 * 表格：等宽字体 + 显示宽度对齐（CJK 算 2，其他算 1）。
 *
 * 【优化 18】displayWidth / padTo 已抽到 shared/util/DisplayWidth.kt，两端共用。
 */
object DesktopMarkdownRenderer {

    fun render(
        text: String,
        textColor: Color,
        linkColor: Color,
        codeBg: Color
    ): AnnotatedString {
        val blocks = MarkdownParser.parseBlocks(text)
        return buildAnnotatedString {
            blocks.forEachIndexed { idx, block ->
                if (idx > 0) append("\n")
                appendBlock(block, textColor, linkColor, codeBg)
            }
        }
    }

    private fun AnnotatedString.Builder.appendBlock(
        block: MdBlock,
        textColor: Color,
        linkColor: Color,
        codeBg: Color
    ) {
        when (block) {
            is MdBlock.Heading -> {
                val size = when (block.level) {
                    1 -> 18.sp
                    2 -> 16.sp
                    3 -> 15.sp
                    else -> 14.sp
                }
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = size, color = textColor))
                appendInline(block.text, textColor, linkColor, codeBg)
                pop()
            }
            is MdBlock.Paragraph -> {
                // 【修复】段落内按 \n 分行渲染。
                // MarkdownParser 现在用 \n 连接段落内的行（不再用空格），
                // 这里必须逐行 appendInline，否则 \n 会被当成普通字符塞进
                // 行内解析，导致换行不显示。
                block.text.lines().forEachIndexed { i, line ->
                    if (i > 0) append("\n")
                    appendInline(line, textColor, linkColor, codeBg)
                }
            }
            is MdBlock.BulletList -> {
                block.items.forEachIndexed { i, item ->
                    if (i > 0) append("\n")
                    append("•  ")
                    appendInline(item, textColor, linkColor, codeBg)
                }
            }
            is MdBlock.OrderedList -> {
                block.items.forEachIndexed { i, item ->
                    if (i > 0) append("\n")
                    append("${block.start + i}.  ")
                    appendInline(item, textColor, linkColor, codeBg)
                }
            }
            is MdBlock.Quote -> {
                pushStyle(SpanStyle(color = textColor.copy(alpha = 0.75f), fontStyle = FontStyle.Italic))
                block.text.lines().forEachIndexed { i, line ->
                    if (i > 0) append("\n")
                    append("│ ")
                    appendInline(line, textColor, linkColor, codeBg)
                }
                pop()
            }
            is MdBlock.CodeBlock -> {
                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBg,
                        color = textColor,
                        fontSize = 13.sp
                    )
                )
                append(block.code)
                pop()
            }
            is MdBlock.Divider -> {
                pushStyle(SpanStyle(color = textColor.copy(alpha = 0.25f)))
                append("─".repeat(20))
                pop()
            }
            is MdBlock.Table -> {
                appendTable(block, textColor, codeBg)
            }
        }
    }

    private fun AnnotatedString.Builder.appendTable(
        table: MdBlock.Table,
        textColor: Color,
        codeBg: Color
    ) {
        val headers = table.headers
        if (headers.isEmpty()) return
        val colCount = headers.size
        val rows = table.rows

        val widths = IntArray(colCount) { i ->
            DisplayWidth.width(headers.getOrElse(i) { "" })
        }
        for (row in rows) {
            for (i in 0 until colCount) {
                val w = DisplayWidth.width(row.getOrElse(i) { "" })
                if (w > widths[i]) widths[i] = w
            }
        }

        pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = textColor))

        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
        headers.forEachIndexed { i, h ->
            if (i > 0) append(" │ ")
            append(DisplayWidth.padTo(h, widths[i]))
        }
        pop()

        append("\n")
        pushStyle(SpanStyle(color = textColor.copy(alpha = 0.35f)))
        val totalWidth = widths.sum() + (colCount - 1) * 3
        append("─".repeat(totalWidth.coerceAtMost(120)))
        pop()

        for (row in rows) {
            append("\n")
            for (i in 0 until colCount) {
                if (i > 0) append(" │ ")
                append(DisplayWidth.padTo(row.getOrElse(i) { "" }, widths[i]))
            }
        }
        pop()
    }

    private fun AnnotatedString.Builder.appendInline(
        text: String,
        textColor: Color,
        linkColor: Color,
        codeBg: Color
    ) {
        val spans = MarkdownParser.parseInline(text)
        spans.forEach { span ->
            when (span) {
                is MdSpan.Text -> append(span.text)
                is MdSpan.Bold -> withStyle(
                    SpanStyle(fontWeight = FontWeight.Bold, color = textColor)
                ) { append(span.text) }
                is MdSpan.Italic -> withStyle(
                    SpanStyle(fontStyle = FontStyle.Italic, color = textColor)
                ) { append(span.text) }
                is MdSpan.InlineCode -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBg,
                        color = textColor
                    )
                ) { append(span.text) }
                is MdSpan.Strikethrough -> withStyle(
                    SpanStyle(textDecoration = TextDecoration.LineThrough, color = textColor)
                ) { append(span.text) }
                is MdSpan.Link -> {
                    pushStringAnnotation(tag = "url", annotation = span.url)
                    withStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline
                        )
                    ) { append(span.text) }
                    pop()
                }
            }
        }
    }
}