@file:OptIn(ExperimentalMaterial3Api::class)

package net.xinkong.furagent.ui.pages

import net.xinkong.furagent.shared.util.DateFormats
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.xinkong.furagent.data.ScheduleStore
import net.xinkong.furagent.data.ScheduledTask
import net.xinkong.furagent.system.notifyToast
import net.xinkong.furagent.tools.ToolRegistry
import net.xinkong.furagent.ui.*
import net.xinkong.furagent.ui.theme.AppRadius
import java.text.SimpleDateFormat
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * 定时任务列表 —— 整屏页面。
 *
 * 每条任务右侧加 Switch：关闭后不注册 alarm（setEnabled 里 cancel），
 * TaskReceiver 触发时也会跳过。任务保留在列表，可随时重开。
 */
@Composable
fun SchedulesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    var editingTask by remember { mutableStateOf<ScheduledTask?>(null) }
    var showNewTask by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    val tasks = remember(refreshKey) { ScheduleStore.list(context) }
    val enabledCount = tasks.count { it.enabled }
    ApplyBackgroundBlur(true)

    Scaffold(
        topBar = {
            PocoPageTopBar(
                title = "定时任务",
                subtitle = "${tasks.size} 个 · $enabledCount 个启用",
                onBack = onBack,
                actions = {
                    TextButton(onClick = { showNewTask = true }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "新建",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            )
        },
        containerColor = glassPageBg(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
        ) {
            GlassSheen(
                modifier = Modifier.align(Alignment.TopCenter),
                height = 100.dp,
                alpha = 0.06f
            )
            if (tasks.isEmpty()) {
                PocoEmptyState(
                    icon = Icons.Default.Schedule,
                    title = "暂无定时任务",
                    subtitle = "点右上角\"新建\"，或让 AI 帮你设置",
                    modifier = Modifier.fillMaxSize(),
                    action = {
                        Button(
                            onClick = { showNewTask = true },
                            shape = RoundedCornerShape(AppRadius.MD_MINUS)
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("新建任务")
                        }
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(tasks, key = { _, t -> t.id }) { index, t ->
                        var visible by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) {
                            delay((40L * index.coerceAtMost(10)).milliseconds)
                            visible = true
                        }
                        AnimatedVisibility(
                            visible = visible,
                            modifier = Modifier.animateItem(),
                            enter = fadeIn(tween(200)) + slideInVertically(
                                animationSpec = tween(240),
                                initialOffsetY = { it / 3 }
                            )
                        ) {
                            ScheduleCard(
                                task = t,
                                onToggleEnabled = { enabled ->
                                    val err = ScheduleStore.setEnabled(context, t.id, enabled)
                                    if (err == null) {
                                        refreshKey++
                                    } else {
                                        notifyToast(context, err)
                                    }
                                },
                                onEdit = { editingTask = t },
                                onDelete = { pendingDeleteId = t.id }
                            )
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    editingTask?.let { task ->
        EditScheduleDialog(
            initial = task,
            onDismiss = { editingTask = null },
            onConfirm = { updated ->
                ScheduleStore.add(context, updated)
                ScheduleStore.scheduleAlarm(context, updated)
                refreshKey++
                editingTask = null
                notifyToast(context, "已保存")
            }
        )
    }

    if (showNewTask) {
        NewScheduleDialog(
            onDismiss = { showNewTask = false },
            onConfirm = { t ->
                ScheduleStore.add(context, t)
                ScheduleStore.scheduleAlarm(context, t)
                refreshKey++
                showNewTask = false
                notifyToast(context, "已创建")
            }
        )
    }

    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            shape = RoundedCornerShape(AppRadius.DIALOG),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("取消定时任务") },
            text = { Text("确定取消这个任务？") },
            confirmButton = {
                TextButton(onClick = {
                    ScheduleStore.cancelAlarm(context, id)
                    ScheduleStore.remove(context, id)
                    refreshKey++
                    pendingDeleteId = null
                    notifyToast(context, "已取消")
                }) { Text("取消任务", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("返回") }
            }
        )
    }
}


// 定时任务卡片

@Composable
private fun ScheduleCard(
    task: ScheduledTask,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val time = DateFormats.formatDateTime(task.triggerTime)
    val repeatLabel = when (task.repeatMode) {
        "daily" -> "每天"
        "weekly" -> "每周 " + task.repeatDays.sorted().joinToString(" ") { d ->
            listOf("日", "一", "二", "三", "四", "五", "六")[d]
        }
        "interval" -> ScheduleStore.formatInterval(task.intervalSeconds)
        "cron" -> "cron: ${task.cronExpression}"
        else -> "单次"
    }
    val title = when (ScheduleStore.normalizeAction(task.action)) {
        "run_command" -> "执行命令：${task.command.take(30)}"
        "notify" -> "通知：${task.notificationTitle.ifBlank { task.description }}"
        "run_workflow" -> "工作流：${task.workflowName}"
        "run_tool" -> "工具：${ToolRegistry.displayName(task.toolName)}"
        "run_agent" -> "AI：${task.prompt.take(30)}"
        else -> "打开 ${task.appName.ifBlank { task.packageName }}"
    }

    PocoCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (task.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                        },
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!task.enabled) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(AppRadius.MICRO))
                                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "已禁用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    time,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    repeatLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (task.enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (task.conditions.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "条件：${task.conditions.size} 个（${if (task.conditionMode == "or") "任意" else "全部"}满足）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // AI 任务：标出是否绑定了会话（绑定才有上下文，回复会写回该会话）
                if (ScheduleStore.normalizeAction(task.action) == "run_agent") {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (task.sessionId.isBlank()) "未绑定会话（无上下文）"
                        else "已绑定会话（有上下文，回复写回）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // 启用/禁用开关
            Switch(
                checked = task.enabled,
                onCheckedChange = onToggleEnabled,
                colors = pocoSwitchColors()
            )

            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        onClick = { showMenu = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text = {
                            Text("取消任务", color = MaterialTheme.colorScheme.error)
                        },
                        onClick = { showMenu = false; onDelete() }
                    )
                }
            }
        }
    }
}