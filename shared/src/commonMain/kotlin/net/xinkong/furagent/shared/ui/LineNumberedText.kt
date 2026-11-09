package net.xinkong.furagent.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 双端共用的「带行号只读文本阅读器」。
 *
 * 设计约束：
 *  - commonMain 不做任何文件 IO，两端各自在 Dispatchers.IO 读盘后把行传进来，
 *    这样大文件可以分页、也不会把整块 Text 塞进组合里导致界面卡死。
 *  - 用 LazyColumn 逐行渲染，天然支持几万行；行号列与正文同一行，滚动同步。
 *  - 颜色全部取自 MaterialTheme.colorScheme，自动跟随浅色/深色主题。
 *  - 只读，不提供编辑；整块包在 SelectionContainer 里可选中复制。
 */
@Composable
fun LineNumberedTextViewer(
    lines: List<String>,
    modifier: Modifier = Modifier,
    /** 本页第一行的真实行号（分页时 >1） */
    startLineNumber: Int = 1,
    /** 是否还有后续行未加载；为 true 时底部显示提示 */
    hasMore: Boolean = false,
    /** 已加载总行数，仅用于提示文案 */
    loadedLineCount: Int = lines.size,
    /** 行号栏是否显示 */
    showLineNumbers: Boolean = true,
) {
    val lastNumber = startLineNumber + lines.size.coerceAtLeast(1) - 1
    // 行号宽度按最大位数算，避免 5 位数行号被挤掉；最小 36dp。
    val digitCount = lastNumber.toString().length.coerceAtLeast(2)
    val gutterWidth = (16 + digitCount * 9).dp
    val listState = rememberLazyListState()
    val lineNumberColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val bodyColor = MaterialTheme.colorScheme.onSurface
    val gutterBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)

    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 520.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f))
        ) {
            itemsIndexed(
                items = lines,
                // 用真实行号做 key：分页加载、行插入时不会错乱或触发整表动画
                key = { index, _ -> startLineNumber + index }
            ) { index, line ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    if (showLineNumbers) {
                        Box(
                            modifier = Modifier
                                .width(gutterWidth)
                                .background(gutterBg)
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = (startLineNumber + index).toString(),
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                fontFamily = FontFamily.Monospace,
                                color = lineNumberColor
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    // 空行也要占一行高度，否则行号会和正文错位
                    Text(
                        text = line.ifEmpty { " " },
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        color = bodyColor,
                        softWrap = false,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
            if (hasMore) {
                item(key = "more_hint") {
                    Text(
                        text = "已显示前 $loadedLineCount 行，文件还有更多内容（分页加载中可继续读取）。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
            if (lines.isEmpty()) {
                item(key = "empty_hint") {
                    Text(
                        text = "（空文件）",
                        fontSize = 12.sp,
                        color = lineNumberColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * 把文本切成行，保留结尾的空行。
 *
 * Kotlin 自带 String.lines() 会吞掉末尾空行，阅读器里用户看到的
 * 「第 4 行是空行」就没了，所以这里手写切分（纯函数，commonMain 可用）。
 */
fun splitTextToLines(text: String): List<String> {
    if (text.isEmpty()) return listOf("")
    val out = ArrayList<String>()
    var start = 0
    for (i in text.indices) {
        if (text[i] == '\n') {
            var end = i
            if (end > start && text[end - 1] == '\r') end--
            out.add(text.substring(start, end))
            start = i + 1
        }
    }
    if (start <= text.length) out.add(text.substring(start))
    return out
}
