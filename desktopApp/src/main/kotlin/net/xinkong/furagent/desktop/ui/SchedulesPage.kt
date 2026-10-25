@file:OptIn(ExperimentalAnimationApi::class)
package net.xinkong.furagent.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.xinkong.furagent.desktop.data.ScheduleStore
import net.xinkong.furagent.desktop.data.ScheduledTask
import net.xinkong.furagent.desktop.tools.DesktopTools
import net.xinkong.furagent.desktop.ui.theme.Radius
import net.xinkong.furagent.desktop.ui.theme.Spacing

/**
 * 定时任务页面（从 Pages.kt 拆分）。
 *
 * 包含 SchedulesPage / EmptyScheduleState / ScheduleCard。
 */

@Composable
fun SchedulesPage() {
    var refreshKey by remember { mutableIntStateOf(0) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var editingTask by remember { mutableStateOf<ScheduledTask?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    val tasks = remember(refreshKey) { ScheduleStore.list() }

    Column(Modifier.fillMaxSize()) {
        PageTitleBar(
            title = "定时任务",
            subtitle = "${tasks.size} 个任务 · 支持秒级轮询",
            actions = {
                PrimaryButton(text = "新建任务", icon = Icons.Default.Add) {
                    editingTask = null; showEditor = true
                }
            }
        )

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            if (tasks.isEmpty()) {
                EmptyScheduleState(onCreate = { editingTask = null; showEditor = true })
            } else {
                Column(
                    Modifier.widthIn(max = 1400.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            horizontal = Spacing.PAGE_H,
                            vertical = Spacing.PAGE_V,
                        ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.CARD_GAP)
                ) {
                    tasks.forEach { t ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(tween(200)) +
                                    expandVertically(tween(240, easing = FastOutSlowInEasing)),
                            exit = fadeOut(tween(140)) +
                                    shrinkVertically(tween(180))
                        ) {
                            key(t.id) {
                                ScheduleCard(
                                    task = t,
                                    onToggleEnabled = { enabled ->
                                        ScheduleStore.setEnabled(t.id, enabled)
                                        refreshKey++
                                    },
                                    onEdit = { editingTask = t; showEditor = true },
                                    onDelete = { pendingDeleteId = t.id }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDeleteId?.let { id ->
        val t = ScheduleStore.list().firstOrNull { it.id == id }
        ConfirmDialog(
            title = "取消定时任务",
            message = "确定取消「${t?.description ?: id}」？",
            confirmText = "取消任务", danger = true,
            onConfirm = { ScheduleStore.delete(id); refreshKey++; pendingDeleteId = null },
            onDismiss = { pendingDeleteId = null }
        )
    }

    if (showEditor) {
        ScheduleEditorDialog(
            initial = editingTask,
            onDismiss = { showEditor = false; editingTask = null },
            onSave = { t -> ScheduleStore.add(t); refreshKey++; showEditor = false; editingTask = null }
        )
    }
}

@Composable
private fun EmptyScheduleState(onCreate: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Schedule,
            contentDescription = null,
            tint = TextMuted(),
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("暂无定时任务", fontSize = 15.sp, color = TextSecondary())
        Spacer(Modifier.height(8.dp))
        Text(
            "点击下方按钮新建，或在聊天里对 AI 说：\n· 每 3 秒给我弹一个通知\n· 每天早上 9 点执行某个工作流",
            fontSize = 12.sp,
            color = TextMuted(),
            lineHeight = 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton(text = "新建任务", icon = Icons.Default.Add) { onCreate() }
    }
}

@Composable
private fun ScheduleCard(
    task: ScheduledTask,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val repeatLabel = when (task.repeatMode) {
        "interval" -> ScheduleStore.formatInterval(task.intervalSeconds)
        "daily" -> "每天"
        "cron" -> "cron: ${task.cronExpression}"
        else -> "单次"
    }
    val actionLabel = when (task.action) {
        "notify" -> "发送通知"
        "run_tool" -> "调用工具：${DesktopTools.displayName(task.toolName)}"
        "run_workflow" -> "执行工作流：${task.workflowName}"
        "open_app" -> "打开应用"
        "run_command" -> "执行命令：${task.command.take(30)}"
        else -> task.action
    }

    GlassCard(padding = PaddingValues(Spacing.CARD_PADDING)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(
                    if (task.enabled) AccentSoft() else CardStrongColor()
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    tint = if (task.enabled) AccentColor() else TextMuted(),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        task.description.ifBlank { "(无描述)" },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (task.enabled) TextPrimary() else TextSecondary(),
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!task.enabled) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(Radius.XS))
                                .background(CardStrongColor())
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(
                                "已禁用",
                                fontSize = 9.sp,
                                color = TextMuted(),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        actionLabel,
                        fontSize = 11.sp,
                        color = if (task.enabled) TextSecondary() else TextMuted()
                    )
                    Text("·", fontSize = 11.sp, color = TextMuted())
                    Text(
                        repeatLabel,
                        fontSize = 11.sp,
                        color = if (task.enabled) AccentColor() else TextMuted()
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "下次：${ScheduleStore.formatTime(task.triggerTime)}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextMuted()
                )
                if (task.conditions.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "条件：${task.conditions.size} 个（${if (task.conditionMode == "or") "任意" else "全部"}满足）",
                        fontSize = 11.sp,
                        color = TextMuted()
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Switch(
                checked = task.enabled,
                onCheckedChange = onToggleEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = OnAccent(),
                    checkedTrackColor = AccentColor(),
                    uncheckedThumbColor = TextSecondary(),
                    uncheckedTrackColor = CardStrongColor(),
                    uncheckedBorderColor = BorderColor()
                )
            )

            Spacer(Modifier.width(4.dp))

            IconActionButton(Icons.Default.Edit, onEdit, tint = TextSecondary())
            IconActionButton(Icons.Default.Delete, onDelete, tint = ErrorColor())
        }
    }
}