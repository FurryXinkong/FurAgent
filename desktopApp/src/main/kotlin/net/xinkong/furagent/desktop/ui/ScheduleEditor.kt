package net.xinkong.furagent.desktop.ui

import net.xinkong.furagent.shared.util.DateFormats
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.xinkong.furagent.desktop.data.ScheduleStore
import net.xinkong.furagent.shared.util.TaskCondition
import net.xinkong.furagent.desktop.data.DesktopSessionStore
import net.xinkong.furagent.desktop.data.ScheduledTask
import net.xinkong.furagent.desktop.data.WorkflowStore
import net.xinkong.furagent.desktop.tools.DesktopTools
import net.xinkong.furagent.desktop.ui.theme.Radius
import java.text.SimpleDateFormat
import java.util.*

// 定时任务编辑器

@Composable
fun ScheduleEditorDialog(
    initial: ScheduledTask?,
    onDismiss: () -> Unit,
    onSave: (ScheduledTask) -> Unit
) {
    val sdf = DateFormats.dateTimeFormatter()

    var description by remember { mutableStateOf(initial?.description ?: "") }
    var action by remember { mutableStateOf(initial?.action ?: "notify") }
    var repeatMode by remember { mutableStateOf(initial?.repeatMode ?: "once") }
    var triggerText by remember {
        mutableStateOf(
            if (initial != null) sdf.format(Date(initial.triggerTime))
            else sdf.format(Date(System.currentTimeMillis() + 60_000L))
        )
    }
    var intervalText by remember {
        mutableStateOf(if ((initial?.intervalSeconds ?: 0) > 0) initial!!.intervalSeconds.toString() else "60")
    }
    var cronText by remember { mutableStateOf(initial?.cronExpression ?: "") }

    var toolName by remember { mutableStateOf(initial?.toolName ?: "") }
    var toolArgs by remember { mutableStateOf(initial?.toolArgs ?: "{}") }
    var workflowName by remember { mutableStateOf(initial?.workflowName ?: "") }
    var appPath by remember { mutableStateOf(initial?.appPath ?: "") }
    var command by remember { mutableStateOf(initial?.command ?: "") }
    var notifTitle by remember { mutableStateOf(initial?.notificationTitle ?: "") }
    var notifContent by remember { mutableStateOf(initial?.notificationContent ?: "") }
    var agentPrompt by remember { mutableStateOf(initial?.prompt ?: "") }
    var agentSessionId by remember { mutableStateOf(initial?.sessionId ?: "") }

    var conditions by remember { mutableStateOf(initial?.conditions ?: emptyList()) }
    var conditionMode by remember { mutableStateOf(initial?.conditionMode ?: "and") }
    var skipIfNotMet by remember { mutableStateOf(initial?.skipIfNotMet ?: true) }

    // 启用状态（新建默认 true）
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }

    var errorMsg by remember { mutableStateOf<String?>(null) }
    var editingConditionIndex by remember { mutableIntStateOf(-1) }
    var editingCondition by remember { mutableStateOf<TaskCondition?>(null) }

    GlassDialog(
        title = if (initial == null) "新建定时任务" else "编辑定时任务",
        onDismiss = onDismiss,
        width = 640.dp,
        content = {
            Column(
                Modifier.heightIn(max = 580.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassTextField(
                    description, { description = it; errorMsg = null },
                    label = "任务描述", placeholder = "例如：每小时提醒我喝水"
                )

                // 启用开关
                GlassSwitchRow(
                    "启用此任务",
                    if (enabled) "任务会按计划执行" else "任务暂时不执行，但仍保留在列表里",
                    enabled
                ) { enabled = it }

                Spacer(Modifier.height(4.dp))
                SectionLabel("触发方式")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RepeatChip("单次", "once", repeatMode) { repeatMode = it }
                    RepeatChip("间隔", "interval", repeatMode) { repeatMode = it }
                    RepeatChip("每天", "daily", repeatMode) { repeatMode = it }
                    RepeatChip("cron", "cron", repeatMode) { repeatMode = it }
                }

                if (repeatMode == "interval") {
                    GlassTextField(
                        intervalText,
                        { intervalText = it.filter { c -> c.isDigit() } },
                        label = "间隔秒数（最小 1）",
                        placeholder = "60"
                    )
                    val sec = intervalText.toIntOrNull() ?: 0
                    if (sec > 0) {
                        HintText("即：${ScheduleStore.formatInterval(sec)}")
                    }
                }

                if (repeatMode == "cron") {
                    GlassTextField(
                        cronText, { cronText = it },
                        label = "cron 表达式（5 段：分 时 日 月 周）",
                        placeholder = "0 9 * * *"
                    )
                    HintText("示例：0 9 * * * = 每天 9:00；*/5 * * * * = 每 5 分钟")
                } else {
                    GlassTextField(
                        triggerText, { triggerText = it },
                        label = if (repeatMode == "interval") "首次触发时间" else "触发时间",
                        placeholder = "yyyy-MM-dd HH:mm:ss"
                    )
                }

                Spacer(Modifier.height(4.dp))
                SectionLabel("执行动作")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionChip("通知", "notify", action) { action = it }
                    ActionChip("工具", "run_tool", action) { action = it }
                    ActionChip("工作流", "run_workflow", action) { action = it }
                    ActionChip("应用", "open_app", action) { action = it }
                    ActionChip("命令", "run_command", action) { action = it }
                    ActionChip("AI", "run_agent", action) { action = it }
                }

                when (action) {
                    "notify" -> {
                        GlassTextField(
                            notifTitle, { notifTitle = it },
                            label = "通知标题", placeholder = "FurAgent 提醒"
                        )
                        GlassTextField(
                            notifContent, { notifContent = it },
                            label = "通知内容", placeholder = "该喝水了",
                            singleLine = false, minLines = 2
                        )
                    }
                    "run_tool" -> {
                        ToolPicker(toolName) { toolName = it }
                        GlassTextField(
                            toolArgs, { toolArgs = it },
                            label = "工具参数（JSON）",
                            placeholder = "{}",
                            singleLine = false, minLines = 2, maxLines = 4, monospace = true
                        )
                        HintText("例：{\"path\":\"/tmp/a.txt\",\"content\":\"hi\"}")
                    }
                    "run_workflow" -> {
                        val workflows = remember { WorkflowStore.list() }
                        if (workflows.isEmpty()) {
                            HintText("还没有工作流，请先到设置页创建工作流")
                        } else {
                            var expanded by remember { mutableStateOf(false) }
                            Text("选择工作流", fontSize = 12.sp, color = TextSecondary())
                            Box {
                                SecondaryButton(
                                    workflowName.ifBlank { "选择…" },
                                    onClick = { expanded = true },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false },
                                    containerColor = CardColor()
                                ) {
                                    workflows.forEach { w ->
                                        DropdownMenuItem(
                                            text = { Text(w.name, fontSize = 12.sp) },
                                            onClick = { workflowName = w.name; expanded = false }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    "open_app" -> {
                        GlassTextField(
                            appPath, { appPath = it },
                            label = "应用路径或命令",
                            placeholder = "C:\\Program Files\\...\\app.exe"
                        )
                        HintText("Windows 可填完整 exe 路径；也可填 notepad 等系统命令")
                    }
                    "run_command" -> {
                        GlassTextField(
                            command, { command = it },
                            label = "Shell 命令",
                            placeholder = "echo hello >> log.txt",
                            singleLine = false, minLines = 2, maxLines = 4, monospace = true
                        )
                    }
                    "run_agent" -> {
                        GlassTextField(
                            agentPrompt, { agentPrompt = it },
                            label = "发给 AI 的内容（prompt）",
                            placeholder = "例如：总结今天的待办并提醒我",
                            singleLine = false, minLines = 2, maxLines = 4
                        )
                        val sessions = remember { DesktopSessionStore.list() }
                        Text("结果写入会话（可选）", fontSize = 12.sp, color = TextSecondary())
                        Box {
                            var sessionExpanded by remember { mutableStateOf(false) }
                            val boundTitle = sessions.firstOrNull { it.id == agentSessionId }?.title
                            SecondaryButton(
                                boundTitle?.let { "已绑定：$it" } ?: "不绑定（只发通知）",
                                onClick = { sessionExpanded = true },
                                modifier = Modifier.fillMaxWidth()
                            )
                            DropdownMenu(
                                expanded = sessionExpanded,
                                onDismissRequest = { sessionExpanded = false },
                                containerColor = CardColor()
                            ) {
                                DropdownMenuItem(
                                    text = { Text("不绑定（只发通知）", fontSize = 12.sp) },
                                    onClick = { agentSessionId = ""; sessionExpanded = false }
                                )
                                sessions.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text(s.title, fontSize = 12.sp) },
                                        onClick = { agentSessionId = s.id; sessionExpanded = false }
                                    )
                                }
                            }
                        }
                        HintText("绑定会话后，AI 回复会追加为该会话的 assistant 消息；不绑定则只发系统通知")
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("触发条件")
                    Spacer(Modifier.weight(1f))
                    SecondaryButton("添加", onClick = {
                        editingConditionIndex = -1
                        editingCondition = TaskCondition(type = "time_between")
                    })
                }

                if (conditions.isEmpty()) {
                    HintText("无条件，每次触发都执行")
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RepeatChip("全部满足", "and", conditionMode) { conditionMode = it }
                        RepeatChip("任一满足", "or", conditionMode) { conditionMode = it }
                    }
                    conditions.forEachIndexed { i, c ->
                        key("cond_$i") {
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(tween(180)) +
                                        expandVertically(tween(220, easing = FastOutSlowInEasing)),
                                exit = fadeOut(tween(120)) +
                                        shrinkVertically(tween(160))
                            ) {
                                GlassCard(padding = PaddingValues(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(conditionLabel(c), fontSize = 12.sp, color = TextPrimary())
                                            Text(
                                                c.type,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = TextMuted()
                                            )
                                        }
                                        IconActionButton(Icons.Default.Edit, {
                                            editingConditionIndex = i
                                            editingCondition = c
                                        }, size = 24.dp)
                                        IconActionButton(Icons.Default.Close, {
                                            conditions = conditions.toMutableList().also { it.removeAt(i) }
                                        }, tint = ErrorColor(), size = 24.dp)
                                    }
                                }
                            }
                        }
                    }
                    GlassSwitchRow("条件不满足时跳过本次", null, skipIfNotMet) { skipIfNotMet = it }
                }

                AnimatedVisibility(
                    visible = errorMsg != null,
                    enter = fadeIn(tween(160)) + expandVertically(tween(200)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(160))
                ) {
                    Text(errorMsg ?: "", color = ErrorColor(), fontSize = 12.sp)
                }
            }
        },
        actions = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary()) }
            TextButton(onClick = {
                val parsed = try { sdf.parse(triggerText)?.time } catch (_: Exception) { null }
                val intervalSec = intervalText.toIntOrNull() ?: 0

                when {
                    description.isBlank() -> errorMsg = "描述不能为空"
                    repeatMode != "cron" && parsed == null -> errorMsg = "时间格式不正确"
                    repeatMode == "interval" && intervalSec < ScheduleStore.MIN_INTERVAL_SECONDS ->
                        errorMsg = "间隔不能小于 ${ScheduleStore.MIN_INTERVAL_SECONDS} 秒"
                    repeatMode == "cron" && cronText.isBlank() -> errorMsg = "请输入 cron 表达式"
                    action == "run_tool" && toolName.isBlank() -> errorMsg = "请选择工具"
                    action == "run_workflow" && workflowName.isBlank() -> errorMsg = "请选择工作流"
                    action == "open_app" && appPath.isBlank() -> errorMsg = "请输入应用路径"
                    action == "run_command" && command.isBlank() -> errorMsg = "请输入命令"
                    action == "run_agent" && agentPrompt.isBlank() -> errorMsg = "请输入发给 AI 的内容"
                    else -> {
                        val triggerTime = when (repeatMode) {
                            "interval" -> parsed ?: System.currentTimeMillis()
                            "cron" -> System.currentTimeMillis() + 60_000L
                            else -> parsed ?: System.currentTimeMillis()
                        }
                        val task = ScheduledTask(
                            id = initial?.id ?: ScheduleStore.newId(),
                            triggerTime = triggerTime,
                            repeatMode = repeatMode,
                            intervalSeconds = if (repeatMode == "interval") intervalSec else 0,
                            cronExpression = if (repeatMode == "cron") cronText else "",
                            action = action,
                            toolName = if (action == "run_tool") toolName else "",
                            toolArgs = if (action == "run_tool") toolArgs else "",
                            workflowName = if (action == "run_workflow") workflowName else "",
                            appPath = if (action == "open_app") appPath else "",
                            command = if (action == "run_command") command else "",
                            notificationTitle = if (action == "notify") notifTitle else "",
                            notificationContent = if (action == "notify") notifContent else "",
                            prompt = if (action == "run_agent") agentPrompt else "",
                            sessionId = if (action == "run_agent") agentSessionId else "",
                            conditions = conditions,
                            conditionMode = conditionMode,
                            skipIfNotMet = skipIfNotMet,
                            enabled = enabled,
                            description = description
                        )
                        onSave(task)
                    }
                }
            }) {
                Text("保存", color = AccentColor(), fontWeight = FontWeight.SemiBold)
            }
        }
    )

    editingCondition?.let { cond ->
        ConditionEditorDialog(
            initial = cond,
            onDismiss = { editingCondition = null; editingConditionIndex = -1 },
            onSave = { newCond ->
                conditions = if (editingConditionIndex >= 0) {
                    conditions.toMutableList().also { it[editingConditionIndex] = newCond }
                } else conditions + newCond
                editingCondition = null
                editingConditionIndex = -1
            }
        )
    }
}

// Chip —— 薄包装，实际渲染复用公共库 GlassChip（Glass.kt）

@Composable
private fun RowScope.RepeatChip(
    label: String, value: String, current: String, onChange: (String) -> Unit
) {
    GlassChip(label, value == current, Modifier.weight(1f)) { onChange(value) }
}

@Composable
private fun RowScope.ActionChip(
    label: String, value: String, current: String, onChange: (String) -> Unit
) {
    GlassChip(label, value == current, Modifier.weight(1f)) { onChange(value) }
}

// 工具选择器

@Composable
private fun ToolPicker(currentTool: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Text("工具", fontSize = 12.sp, color = TextSecondary())
    Box {
        SecondaryButton(
            if (currentTool.isBlank()) "选择工具…" else DesktopTools.displayName(currentTool),
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = CardColor(),
            modifier = Modifier.heightIn(max = 400.dp)
        ) {
            DesktopTools.TOOLS.forEach { t ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(t.displayName, fontSize = 12.sp, color = TextPrimary())
                            Text(
                                t.name,
                                fontSize = 10.sp,
                                color = TextMuted(),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    },
                    onClick = { onChange(t.name); expanded = false }
                )
            }
        }
    }
}

// 条件描述

private fun conditionLabel(c: TaskCondition): String = when (c.type) {
    "time_between" -> "时间范围：${c.value}"
    "weekday_in" -> "星期：${c.value}"
    "network_available" -> "网络可用 = ${c.value}"
    "process_running" -> "进程运行：${c.value}"
    "command_matches" -> "命令输出匹配：${c.value}"
    else -> "${c.type} ${c.operator} ${c.value}"
}

// 条件编辑器

@Composable
private fun ConditionEditorDialog(
    initial: TaskCondition,
    onDismiss: () -> Unit,
    onSave: (TaskCondition) -> Unit
) {
    var type by remember { mutableStateOf(initial.type.ifBlank { "time_between" }) }
    var value by remember { mutableStateOf(initial.value) }
    var operator by remember { mutableStateOf(initial.operator) }

    GlassDialog(
        title = "编辑条件",
        onDismiss = onDismiss,
        width = 500.dp,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("条件类型", fontSize = 12.sp, color = TextSecondary())
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ConditionTypeRow("时间范围", "time_between", "如 09:00-18:00", type) { type = it }
                    ConditionTypeRow("星期几", "weekday_in", "如 1,2,3,4,5", type) { type = it }
                    ConditionTypeRow("网络可用", "network_available", "true / false", type) { type = it }
                    ConditionTypeRow("进程运行中", "process_running", "进程名，如 QQ.exe", type) { type = it }
                    ConditionTypeRow("命令输出匹配", "command_matches", "命令|正则", type) { type = it }
                }

                GlassTextField(
                    value, { value = it },
                    label = "条件值",
                    placeholder = when (type) {
                        "time_between" -> "09:00-18:00"
                        "weekday_in" -> "1,2,3,4,5"
                        "network_available" -> "true"
                        "process_running" -> "QQ.exe"
                        "command_matches" -> "echo hi|hi"
                        else -> ""
                    }
                )
            }
        },
        actions = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary()) }
            TextButton(onClick = {
                if (value.isNotBlank()) {
                    onSave(TaskCondition(type = type, operator = operator, value = value))
                }
            }) { Text("确定", color = AccentColor(), fontWeight = FontWeight.SemiBold) }
        }
    )
}

@Composable
private fun ConditionTypeRow(
    label: String, typeValue: String, hint: String, current: String,
    onSelect: (String) -> Unit
) {
    val selected = current == typeValue
    val bg by animateColorAsState(
        if (selected) AccentSoft() else CardColor(),
        tween(140), label = "condTypeRowBg"
    )
    val borderColor by animateColorAsState(
        if (selected) AccentColor() else BorderColor(),
        tween(140), label = "condTypeRowBorder"
    )

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.SM_PLUS))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(Radius.SM_PLUS))
            .clickable { onSelect(typeValue) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 13.sp,
                color = TextPrimary(),
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
            )
            Text(
                hint,
                fontSize = 10.sp,
                color = TextMuted(),
                fontFamily = FontFamily.Monospace
            )
        }
        if (selected) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = AccentColor(),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}