package net.xinkong.furagent.shared.util

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class MarkdownColors(
    val textColor: Color,
    val linkColor: Color,
    val codeBg: Color,
    val quoteColor: Color,
    val dividerColor: Color,
    val tableBorderColor: Color
)

@Composable
fun ComposeMarkdownRenderer(
    text: String,
    colors: MarkdownColors,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    onLinkClick: ((String) -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onTap: (() -> Unit)? = null
) {
    val blocks = remember(text) { MarkdownParser.parseBlocks(text) }

    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            RenderBlock(block, colors, maxLines, onLinkClick, onLongPress, onTap)
            if (index < blocks.size - 1) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun RenderBlock(
    block: MdBlock,
    colors: MarkdownColors,
    maxLines: Int,
    onLinkClick: ((String) -> Unit)?,
    onLongPress: (() -> Unit)?,
    onTap: (() -> Unit)?
) {
    when (block) {
        is MdBlock.Heading -> {
            val fontSize = when (block.level) {
                1 -> 20.sp
                2 -> 18.sp
                3 -> 16.sp
                else -> 15.sp
            }
            MarkdownStyledText(
                text = buildInlineAnnotatedString(block.text, colors, fontSize, true),
                colors = colors,
                maxLines = maxLines,
                onLinkClick = onLinkClick,
                onLongPress = onLongPress,
                onTap = onTap,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
        }

        is MdBlock.Paragraph -> {
            MarkdownStyledText(
                text = buildInlineAnnotatedString(block.text, colors, 14.sp, false),
                colors = colors,
                maxLines = maxLines,
                onLinkClick = onLinkClick,
                onLongPress = onLongPress,
                onTap = onTap
            )
        }

        is MdBlock.BulletList -> {
            Column(modifier = Modifier.padding(start = 4.dp)) {
                block.items.forEach { item ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(
                            text = "\u2022",
                            color = colors.textColor,
                            fontSize = 14.sp,
                            modifier = Modifier.width(16.dp).padding(top = 2.dp)
                        )
                        MarkdownStyledText(
                            text = buildInlineAnnotatedString(item, colors, 14.sp, false),
                            colors = colors,
                            maxLines = maxLines,
                            onLinkClick = onLinkClick,
                            onLongPress = onLongPress,
                            onTap = onTap
                        )
                    }
                }
            }
        }

        is MdBlock.OrderedList -> {
            Column(modifier = Modifier.padding(start = 4.dp)) {
                block.items.forEachIndexed { index, item ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(
                            text = "${block.start + index}.",
                            color = colors.textColor,
                            fontSize = 14.sp,
                            modifier = Modifier.width(24.dp).padding(top = 2.dp)
                        )
                        MarkdownStyledText(
                            text = buildInlineAnnotatedString(item, colors, 14.sp, false),
                            colors = colors,
                            maxLines = maxLines,
                            onLinkClick = onLinkClick,
                            onLongPress = onLongPress,
                            onTap = onTap
                        )
                    }
                }
            }
        }

        is MdBlock.Quote -> {
            Row(
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(colors.quoteColor, RoundedCornerShape(2.dp))
                )
                MarkdownStyledText(
                    text = buildInlineAnnotatedString(block.text, colors, 14.sp, false, italic = true),
                    colors = colors,
                    maxLines = maxLines,
                    onLinkClick = onLinkClick,
                    onLongPress = onLongPress,
                    onTap = onTap
                )
            }
        }

        is MdBlock.CodeBlock -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.codeBg, RoundedCornerShape(6.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = block.code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = colors.textColor,
                    lineHeight = 18.sp,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                )
            }
        }

        is MdBlock.Divider -> {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                thickness = 1.dp,
                color = colors.dividerColor
            )
        }

        is MdBlock.Table -> {
            RenderTable(block, colors)
        }
    }
}

@Composable
private fun RenderTable(table: MdBlock.Table, colors: MarkdownColors) {
    if (table.headers.isEmpty()) return
    val colCount = table.headers.size
    val weight = 1f / colCount

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.tableBorderColor, RoundedCornerShape(4.dp))
    ) {
        Row(modifier = Modifier.fillMaxWidth().background(colors.codeBg.copy(alpha = 0.5f))) {
            table.headers.forEach { header ->
                MarkdownStyledText(
                    text = buildInlineAnnotatedString(header, colors, 14.sp, true),
                    colors = colors,
                    modifier = Modifier.weight(weight).padding(8.dp),
                    maxLines = Int.MAX_VALUE
                )
            }
        }
        HorizontalDivider(color = colors.tableBorderColor)

        table.rows.forEachIndexed { rowIndex, row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                for (i in 0 until colCount) {
                    val cellText = row.getOrElse(i) { "" }
                    MarkdownStyledText(
                        text = buildInlineAnnotatedString(cellText, colors, 14.sp, false),
                        colors = colors,
                        modifier = Modifier.weight(weight).padding(8.dp),
                        maxLines = Int.MAX_VALUE
                    )
                }
            }
            if (rowIndex < table.rows.size - 1) {
                HorizontalDivider(color = colors.tableBorderColor.copy(alpha = 0.3f))
            }
        }
    }
}

/**
 * 统一处理点击、长按和链接跳转的 Text 组件
 * 完美兼容 SelectionContainer
 */
@Composable
private fun MarkdownStyledText(
    text: AnnotatedString,
    colors: MarkdownColors,
    modifier: Modifier = Modifier,
    maxLines: Int,
    onLinkClick: ((String) -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onTap: (() -> Unit)? = null
) {
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    Text(
        text = text,
        modifier = modifier.pointerInput(text, onLinkClick, onTap, onLongPress) {
            if (onLinkClick != null || onTap != null || onLongPress != null) {
                detectTapGestures(
                    onLongPress = { onLongPress?.invoke() },
                    onTap = { offset ->
                        val layout = layoutResult
                        val hitLink = if (layout != null && onLinkClick != null) {
                            try {
                                val pos = layout.getOffsetForPosition(offset)
                                text.getStringAnnotations("url", pos, pos).firstOrNull()
                            } catch (_: Exception) { null }
                        } else null

                        if (hitLink != null) {
                            onLinkClick!!.invoke(hitLink.item)
                        } else {
                            onTap?.invoke()
                        }
                    }
                )
            }
        },
        onTextLayout = { layoutResult = it },
        color = colors.textColor,
        lineHeight = 22.sp, // 统一行高
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

private fun buildInlineAnnotatedString(
    text: String,
    colors: MarkdownColors,
    fontSize: androidx.compose.ui.unit.TextUnit,
    bold: Boolean,
    italic: Boolean = false
): AnnotatedString {
    val spans = MarkdownParser.parseInline(text)
    return buildAnnotatedString {
        spans.forEach { span ->
            when (span) {
                is MdSpan.Text -> withStyle(SpanStyle(fontSize = fontSize, fontWeight = if (bold) FontWeight.Bold else null, fontStyle = if (italic) FontStyle.Italic else null)) { append(span.text) }
                is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.textColor, fontSize = fontSize, fontStyle = if (italic) FontStyle.Italic else null)) { append(span.text) }
                is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = colors.textColor, fontSize = fontSize, fontWeight = if (bold) FontWeight.Bold else null)) { append(span.text) }
                is MdSpan.BoldItalic -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, color = colors.textColor, fontSize = fontSize)) { append(span.text) }
                is MdSpan.InlineCode -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBg, color = colors.textColor, fontSize = fontSize * 0.9f)) { append(" ${span.text} ") }
                is MdSpan.Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = colors.textColor, fontSize = fontSize)) { append(span.text) }
                is MdSpan.Link -> {
                    pushStringAnnotation(tag = "url", annotation = span.url)
                    withStyle(SpanStyle(color = colors.linkColor, textDecoration = TextDecoration.Underline, fontSize = fontSize, fontWeight = if (bold) FontWeight.Bold else null)) { append(span.text) }
                    pop()
                }
            }
        }
    }
}