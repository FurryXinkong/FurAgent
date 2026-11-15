package net.xinkong.furagent.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.xinkong.furagent.desktop.data.DesktopPaths
import net.xinkong.furagent.shared.icons.Icons
import net.xinkong.furagent.shared.util.DateFormats
import net.xinkong.furagent.shared.util.DiaryStore

/**
 * 工作日记：按天浏览 + 直接编辑（即时保存）。
 *
 * 左侧日期列表，右侧是当天 .md 原文的编辑框；每次改动立刻写回文件，
 * 与 AI 的 diary 工具共用同一个 DiaryStore，改完 AI 下一轮就能读到。
 */
@Composable
fun DiaryPage() {
    val root = remember { DesktopPaths.rootDir() }
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

    Column(Modifier.fillMaxSize()) {
        PageTitleBar(
            title = "工作日记",
            subtitle = if (status.isNotBlank()) status else "按天归档 · 修改即时保存 · ${days.size} 天",
            actions = {
                SecondaryButton(text = "今天", icon = Icons.Default.Add) {
                    selected = today
                    if (DiaryStore.raw(root, today).isBlank()) {
                        DiaryStore.overwrite(root, today, "# $today 工作日记\n\n")
                    }
                    refreshKey++
                }
            }
        )

        Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
            // 日期列表
            LazyColumn(Modifier.width(150.dp).fillMaxHeight()) {
                items(days, key = { it.date }) { d ->
                    TextButton(
                        onClick = { selected = d.date },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "${d.date} (${d.entries})",
                            fontSize = 12.sp,
                            color = if (d.date == selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                if (days.isEmpty()) {
                    item {
                        Text(
                            "还没有日记。\nAI 完成一个任务后会自动写一篇。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            OutlinedTextField(
                value = text,
                onValueChange = { saveEdited(it) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
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
