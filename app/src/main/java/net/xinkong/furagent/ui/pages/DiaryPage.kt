@file:OptIn(ExperimentalMaterial3Api::class)

package net.xinkong.furagent.ui.pages

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.xinkong.furagent.shared.icons.Icons
import net.xinkong.furagent.shared.util.DateFormats
import net.xinkong.furagent.shared.util.DiaryStore
import net.xinkong.furagent.ui.PocoEmptyState
import net.xinkong.furagent.ui.PocoPageTopBar

/**
 * 工作日记：按天浏览 + 直接编辑。
 *
 * 编辑是**即时保存**的：每次文本变化直接写回当天的 .md 文件，
 * 没有「保存」按钮，避免用户改完忘记点保存导致 AI 写的内容或手工订正丢失。
 */
@Composable
fun DiaryPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val root = remember { context.filesDir }
    val today = remember { DateFormats.formatDate() }

    var refreshKey by remember { mutableIntStateOf(0) }
    val days = remember(refreshKey) { DiaryStore.listDays(root, 365) }
    var selected by remember { mutableStateOf(days.firstOrNull()?.date ?: today) }
    var text by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(selected, refreshKey) {
        text = DiaryStore.raw(root, selected)
        status = ""
    }

    fun saveEdited(next: String) {
        text = next
        status = try {
            DiaryStore.overwrite(root, selected, next)
            "已保存 · ${DateFormats.formatTime()}"
        } catch (e: Exception) {
            "保存失败：${e.message ?: "未知错误"}"
        }
    }

    Scaffold(
        topBar = {
            PocoPageTopBar(
                title = "工作日记",
                subtitle = if (status.isNotBlank()) status else "按天归档 · 修改即时保存",
                onBack = onBack,
                actions = {
                    TextButton(onClick = {
                        selected = today
                        if (DiaryStore.raw(root, today).isBlank()) {
                            DiaryStore.overwrite(root, today, "# $today 工作日记\n\n")
                        }
                        refreshKey++
                    }) { Text("今天") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (days.isEmpty()) {
                PocoEmptyState(
                    icon = Icons.Default.Description,
                    title = "还没有工作日记",
                    subtitle = "AI 每完成一个任务会自动写一篇，也可以点右上角「今天」手动开始",
                    modifier = Modifier.weight(1f)
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    days.forEach { d ->
                        FilterChip(
                            selected = d.date == selected,
                            onClick = { selected = d.date },
                            label = { Text(d.date, fontSize = 12.sp) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { saveEdited(it) },
                    modifier = Modifier.fillMaxSize(),
                    label = { Text("$selected · ${DiaryStore.fileOf(root, selected).name}") },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }
    }
}
