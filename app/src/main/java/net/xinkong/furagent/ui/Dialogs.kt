package net.xinkong.furagent.ui
import net.xinkong.furagent.ui.theme.AppRadius

import net.xinkong.furagent.shared.util.DateFormats
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.xinkong.furagent.data.ModelProfile
import net.xinkong.furagent.data.ScheduleStore
import net.xinkong.furagent.data.ScheduledTask
import net.xinkong.furagent.data.SessionStore
import net.xinkong.furagent.data.WorkflowStore
import net.xinkong.furagent.tools.ToolRegistry
import java.text.SimpleDateFormat
import java.util.*

// 通用输入对话框

// 【UI 统一】所有弹窗均 28dp 圆角 + surface 底色 + 主色走 colorScheme。

@Composable
fun InputDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    PocoAlertDialog(
        onDismiss = onDismiss,
        title = title,
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(AppRadius.MD_MINUS),
                colors = pocoFieldColors()
            )
        },
        confirmText = "确定",
        onConfirm = { if (text.isNotBlank()) onConfirm(text.trim()) },
        confirmEnabled = text.isNotBlank(),
        dismissText = "取消",
        onDismissClick = onDismiss
    )
}

// 编辑模型对话框

@Composable
fun EditModelDialog(
    title: String,
    initial: ModelProfile,
    onDismiss: () -> Unit,
    onConfirm: (ModelProfile) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var supportsThinking by remember { mutableStateOf(initial.supportsThinking) }
    var supportsVision by remember { mutableStateOf(initial.supportsVision) }
    var temperature by remember { mutableStateOf(initial.temperature.toFloat()) }
    var presencePenalty by remember { mutableStateOf(initial.presencePenalty.toFloat()) }
    var frequencyPenalty by remember { mutableStateOf(initial.frequencyPenalty.toFloat()) }
    val isImageModel = initial.kind == "image"

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(AppRadius.DIALOG),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("显示名称") },
                    placeholder = { Text("例如：本地 Ollama") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    shape = RoundedCornerShape(AppRadius.SM),
                    colors = pocoFieldColors()
                )
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    placeholder = { Text("http://192.168.1.100:11434/v1/chat/completions") },
                    modifier = Modifier.fillMaxWidth(), singleLine = false, maxLines = 3,
                    shape = RoundedCornerShape(AppRadius.SM),
                    colors = pocoFieldColors()
                )
                OutlinedTextField(
                    value = model, onValueChange = { model = it },
                    label = { Text("模型名称") },
                    placeholder = { Text("例如：qwen2.5:7b") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    shape = RoundedCornerShape(AppRadius.SM),
                    colors = pocoFieldColors()
                )
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text("sk-... 或留空") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    shape = RoundedCornerShape(AppRadius.SM),
                    colors = pocoFieldColors()
                )
                if (!isImageModel) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { supportsThinking = !supportsThinking },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = supportsThinking,
                        onCheckedChange = { supportsThinking = it }
                    )
                    Spacer(Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "支持深度思考",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "关闭后聊天页不显示「深度思考」开关（如 DeepSeek-V3 非思考版）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { supportsVision = !supportsVision },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = supportsVision,
                        onCheckedChange = { supportsVision = it }
                    )
                    Spacer(Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "支持图片输入（多模态）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "关闭后禁用「截图」「查看图片」工具，并拒绝手动发送的图片",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // 【新增】采样温度
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "采样温度",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            String.format(java.util.Locale.US, "%.2f", temperature),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        "越低越稳定（适合调工具），越高越有创意（适合写作）。默认 0.1",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    GradientSlider(
                        value = temperature,
                        onValueChange = { temperature = it },
                        valueRange = 0f..1.5f,
                        steps = 29
                    )
                }
                // 【新增】存在惩罚
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "存在惩罚",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            String.format(java.util.Locale.US, "%.2f", presencePenalty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        "正值鼓励引入新话题，负值鼓励重复。默认 0（不干预）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    GradientSlider(
                        value = presencePenalty,
                        onValueChange = { presencePenalty = it },
                        valueRange = -2f..2f,
                        steps = 39
                    )
                }
                // 【新增】频率惩罚
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "频率惩罚",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            String.format(java.util.Locale.US, "%.2f", frequencyPenalty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        "正值抑制重复用词，负值鼓励重复。默认 0（不干预）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    GradientSlider(
                        value = frequencyPenalty,
                        onValueChange = { frequencyPenalty = it },
                        valueRange = -2f..2f,
                        steps = 39
                    )
                }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()) {
                    onConfirm(initial.copy(
                        name = name.trim(),
                        apiKey = apiKey.trim(),
                        baseUrl = baseUrl.trim(),
                        model = model.trim(),
                        supportsThinking = supportsThinking,
                        supportsVision = supportsVision,
                        temperature = temperature.toDouble(),
                        presencePenalty = presencePenalty.toDouble(),
                        frequencyPenalty = frequencyPenalty.toDouble()
                    ))
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// 编辑定时任务对话框

@Composable
fun EditScheduleDialog(
    initial: ScheduledTask,
    onDismiss: () -> Unit,
    onConfirm: (ScheduledTask) -> Unit
) {
    val sdf = DateFormats.dateTimeFormatter()
    var timeText by remember { mutableStateOf(sdf.format(Date(initial.triggerTime))) }
    var timeError by remember { mutableStateOf<String?>(null) }
    var repeatMode by remember { mutableStateOf(initial.repeatMode) }
    var repeatDays by remember { mutableStateOf(initial.repeatDays) }
    var intervalText by remember {
        mutableStateOf(
            if (initial.intervalSeconds > 0) initial.intervalSeconds.toString()
            else "60"
        )
    }

    // 启用状态
    var enabled by remember { mutableStateOf(initial.enabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(AppRadius.DIALOG),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text("编辑定时任务") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column {
                    Text(
                        "目标",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    val target = when (initial.action) {
                        "run_command", "run_cron" -> initial.command
                        else -> initial.appName.ifBlank { initial.packageName }
                    }
                    Text(
                        target,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // 启用开关
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { enabled = !enabled },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "启用此任务",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (enabled) "任务会按计划执行" else "任务暂时不执行，但仍保留在列表",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                OutlinedTextField(
                    value = timeText,
                    onValueChange = { timeText = it; timeError = null },
                    label = { Text("首次触发时间") },
                    placeholder = { Text("yyyy-MM-dd HH:mm:ss") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(AppRadius.SM),
                    isError = timeError != null,
                    supportingText = timeError?.let { { Text(it) } },
                    colors = pocoFieldColors()
                )
                Column {
                    Text(
                        "重复",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RepeatOption("单次", "once", repeatMode) { repeatMode = it }
                        RepeatOption("每天", "daily", repeatMode) { repeatMode = it }
                        RepeatOption("每周", "weekly", repeatMode) { repeatMode = it }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RepeatOption("间隔", "interval", repeatMode) { repeatMode = it }
                    }
                }

                AnimatedVisibility(
                    visible = repeatMode == "interval",
                    enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(120))
                ) {
                    Column {
                        OutlinedTextField(
                            value = intervalText,
                            onValueChange = { intervalText = it.filter { c -> c.isDigit() } },
                            label = { Text("间隔（秒）") },
                            placeholder = { Text("最小 1 秒") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(AppRadius.SM),
                            colors = pocoFieldColors()
                        )
                        Spacer(Modifier.height(4.dp))
                        val sec = intervalText.toIntOrNull() ?: 0
                        Text(
                            if (sec > 0) "即：${ScheduleStore.formatInterval(sec)}"
                            else "请输入正整数秒数",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "提示：小于 60 秒的间隔在系统进入深度休眠时可能会有数分钟延迟。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                AnimatedVisibility(
                    visible = repeatMode == "weekly",
                    enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(120))
                ) {
                    Column {
                        Text(
                            "星期几",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("日", "一", "二", "三", "四", "五", "六").forEachIndexed { idx, label ->
                                val sel = repeatDays.contains(idx)
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (sel) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .clickable {
                                            repeatDays = if (sel) repeatDays - idx else repeatDays + idx
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        label,
                                        fontSize = 13.sp,
                                        color = if (sel) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = try { sdf.parse(timeText)?.time } catch (_: Exception) { null }
                val sec = intervalText.toIntOrNull() ?: 0
                when {
                    parsed == null -> {
                        timeError = "时间格式不正确，请使用 yyyy-MM-dd HH:mm:ss"
                    }
                    repeatMode == "interval" && sec < ScheduleStore.MIN_INTERVAL_SECONDS -> {
                        timeError = "间隔不能小于 ${ScheduleStore.MIN_INTERVAL_SECONDS} 秒"
                    }
                    repeatMode == "interval" && sec > ScheduleStore.MAX_INTERVAL_SECONDS -> {
                        timeError = "间隔不能超过 ${ScheduleStore.MAX_INTERVAL_SECONDS} 秒（30 天）"
                    }
                    repeatMode == "weekly" && repeatDays.isEmpty() -> {
                        timeError = "每周模式需至少选择一天"
                    }
                    else -> {
                        val interval = if (repeatMode == "interval") sec else 0
                        onConfirm(initial.copy(
                            triggerTime = parsed,
                            repeatMode = repeatMode,
                            repeatDays = if (repeatMode == "weekly") repeatDays else emptyList(),
                            intervalSeconds = interval,
                            enabled = enabled
                        ))
                    }
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}


// 新建定时任务对话框

// 新建默认 enabled=true，不加开关（想关的用户建完到列表里关即可）。

/**
 * 新建定时任务。
 *
 * 【与桌面端对齐】旧版只支持「打开应用 / Shell 命令」两种动作、
 * 「单次 / 间隔」两种重复，而 ScheduleStore 和 TaskExecutionService
 * 早就支持 notify / run_tool / run_workflow / run_agent 和 daily / weekly / cron。
 * 结果就是：用户在安卓上根本建不出 AI 定时任务，只能靠 AI 自己调工具建。
 *
 * 现在动作和重复方式全部对齐桌面端 ScheduleEditorDialog。
 */
@Composable
fun NewScheduleDialog(
    onDismiss: () -> Unit,
    onConfirm: (ScheduledTask) -> Unit
) {
    val context = LocalContext.current
    val sdf = DateFormats.dateTimeFormatter()

    var description by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("notify") }
    var packageName by remember { mutableStateOf("") }
    var appName by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var notifTitle by remember { mutableStateOf("") }
    var notifContent by remember { mutableStateOf("") }
    var toolName by remember { mutableStateOf("") }
    var toolArgs by remember { mutableStateOf("{}") }
    var workflowName by remember { mutableStateOf("") }
    var agentPrompt by remember { mutableStateOf("") }
    var agentSessionId by remember { mutableStateOf("") }

    var timeText by remember {
        mutableStateOf(sdf.format(Date(System.currentTimeMillis() + 60_000L)))
    }
    var timeError by remember { mutableStateOf<String?>(null) }
    var repeatMode by remember { mutableStateOf("once") }
    var intervalText by remember { mutableStateOf("60") }
    var cronText by remember { mutableStateOf("") }
    var repeatDays by remember { mutableStateOf(emptyList<Int>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(AppRadius.DIALOG),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text("新建定时任务") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = description, onValueChange = { description = it; timeError = null },
                    label = { Text("任务描述") },
                    placeholder = { Text("例如：每天早上提醒我喝水") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    shape = RoundedCornerShape(AppRadius.SM),
                    colors = pocoFieldColors()
                )

                // 动作
                Column {
                    Text(
                        "执行动作",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RepeatOption("通知", "notify", action) { action = it }
                        RepeatOption("AI", "run_agent", action) { action = it }
                        RepeatOption("工具", "run_tool", action) { action = it }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RepeatOption("工作流", "run_workflow", action) { action = it }
                        RepeatOption("应用", "open_app", action) { action = it }
                        RepeatOption("命令", "run_command", action) { action = it }
                    }
                }

                when (action) {
                    "notify" -> {
                        OutlinedTextField(
                            value = notifTitle, onValueChange = { notifTitle = it },
                            label = { Text("通知标题") },
                            placeholder = { Text("FurAgent 提醒") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            shape = RoundedCornerShape(AppRadius.SM),
                            colors = pocoFieldColors()
                        )
                        OutlinedTextField(
                            value = notifContent, onValueChange = { notifContent = it },
                            label = { Text("通知内容") },
                            placeholder = { Text("该喝水了") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                            minLines = 2,
                            shape = RoundedCornerShape(AppRadius.SM),
                            colors = pocoFieldColors()
                        )
                    }

                    "run_agent" -> {
                        OutlinedTextField(
                            value = agentPrompt,
                            onValueChange = { agentPrompt = it; timeError = null },
                            label = { Text("发给 AI 的内容") },
                            placeholder = { Text("例如：总结今天的待办并提醒我") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
                            minLines = 3,
                            shape = RoundedCornerShape(AppRadius.SM),
                            colors = pocoFieldColors()
                        )
                        // 会话绑定：绑定后 AI 有上下文，回复写回该会话
                        val sessions = remember { SessionStore.list(context) }
                        var sessionExpanded by remember { mutableStateOf(false) }
                        val boundTitle = sessions.firstOrNull { it.id == agentSessionId }?.title
                        Box {
                            OutlinedButton(
                                onClick = { sessionExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(AppRadius.SM)
                            ) {
                                Text(
                                    boundTitle?.let { "已绑定：$it" } ?: "不绑定（无上下文，只发通知）",
                                    maxLines = 1
                                )
                            }
                            DropdownMenu(
                                expanded = sessionExpanded,
                                onDismissRequest = { sessionExpanded = false },
                                containerColor = MaterialTheme.colorScheme.surface
                            ) {
                                DropdownMenuItem(
                                    text = { Text("不绑定（无上下文，只发通知）") },
                                    onClick = { agentSessionId = ""; sessionExpanded = false }
                                )
                                sessions.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text(s.title, maxLines = 1) },
                                        onClick = { agentSessionId = s.id; sessionExpanded = false }
                                    )
                                }
                            }
                        }
                        Text(
                            "绑定会话后 AI 会带上该会话的上下文，回复也写回该会话；" +
                                    "不绑定则每次都是全新对话。回复是否朗读由设置里的「自动朗读」控制。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                    }

                    "run_tool" -> {
                        val tools = remember { ToolRegistry.allToolNames() }
                        var toolExpanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(
                                onClick = { toolExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(AppRadius.SM)
                            ) {
                                Text(
                                    if (toolName.isBlank()) "选择工具…"
                                    else ToolRegistry.displayName(toolName),
                                    maxLines = 1
                                )
                            }
                            DropdownMenu(
                                expanded = toolExpanded,
                                onDismissRequest = { toolExpanded = false },
                                containerColor = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.heightIn(max = 360.dp)
                            ) {
                                tools.forEach { (name, display) ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(display, style = MaterialTheme.typography.bodyMedium)
                                                Text(
                                                    name,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = { toolName = name; toolExpanded = false }
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = toolArgs, onValueChange = { toolArgs = it },
                            label = { Text("工具参数（JSON）") },
                            placeholder = { Text("{}") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                            minLines = 2,
                            shape = RoundedCornerShape(AppRadius.SM),
                            colors = pocoFieldColors()
                        )
                    }

                    "run_workflow" -> {
                        val workflows = remember { WorkflowStore.list(context) }
                        if (workflows.isEmpty()) {
                            Text(
                                "还没有工作流，请先到设置页创建工作流",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            var wfExpanded by remember { mutableStateOf(false) }
                            Box {
                                OutlinedButton(
                                    onClick = { wfExpanded = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(AppRadius.SM)
                                ) {
                                    Text(workflowName.ifBlank { "选择工作流…" }, maxLines = 1)
                                }
                                DropdownMenu(
                                    expanded = wfExpanded,
                                    onDismissRequest = { wfExpanded = false },
                                    containerColor = MaterialTheme.colorScheme.surface
                                ) {
                                    workflows.forEach { w ->
                                        DropdownMenuItem(
                                            text = { Text(w.name) },
                                            onClick = { workflowName = w.name; wfExpanded = false }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    "open_app" -> {
                        OutlinedTextField(
                            value = packageName,
                            onValueChange = { packageName = it; timeError = null },
                            label = { Text("包名") },
                            placeholder = { Text("com.example.app") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            shape = RoundedCornerShape(AppRadius.SM),
                            colors = pocoFieldColors()
                        )
                        OutlinedTextField(
                            value = appName, onValueChange = { appName = it },
                            label = { Text("显示名称（可选）") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            shape = RoundedCornerShape(AppRadius.SM),
                            colors = pocoFieldColors()
                        )
                    }

                    else -> {
                        OutlinedTextField(
                            value = command,
                            onValueChange = { command = it; timeError = null },
                            label = { Text("Shell 命令") },
                            placeholder = { Text("am start -n ...") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                            minLines = 3,
                            shape = RoundedCornerShape(AppRadius.SM),
                            colors = pocoFieldColors()
                        )
                        Text(
                            "需要 Shizuku 权限才能执行 shell 命令",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                    }
                }

                // 重复方式
                Column {
                    Text(
                        "重复",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RepeatOption("单次", "once", repeatMode) { repeatMode = it }
                        RepeatOption("间隔", "interval", repeatMode) { repeatMode = it }
                        RepeatOption("每天", "daily", repeatMode) { repeatMode = it }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RepeatOption("每周", "weekly", repeatMode) { repeatMode = it }
                        RepeatOption("cron", "cron", repeatMode) { repeatMode = it }
                    }
                }

                if (repeatMode == "cron") {
                    OutlinedTextField(
                        value = cronText, onValueChange = { cronText = it; timeError = null },
                        label = { Text("cron 表达式（分 时 日 月 周）") },
                        placeholder = { Text("0 9 * * *") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        shape = RoundedCornerShape(AppRadius.SM),
                        colors = pocoFieldColors()
                    )
                    Text(
                        "示例：0 9 * * * = 每天 9:00；*/5 * * * * = 每 5 分钟",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    OutlinedTextField(
                        value = timeText,
                        onValueChange = { timeText = it; timeError = null },
                        label = {
                            Text(if (repeatMode == "interval") "首次触发时间" else "触发时间")
                        },
                        placeholder = { Text("yyyy-MM-dd HH:mm:ss") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(AppRadius.SM),
                        isError = timeError != null,
                        supportingText = timeError?.let { { Text(it) } },
                        colors = pocoFieldColors()
                    )
                }

                AnimatedVisibility(
                    visible = repeatMode == "interval",
                    enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(120))
                ) {
                    Column {
                        OutlinedTextField(
                            value = intervalText,
                            onValueChange = { intervalText = it.filter { c -> c.isDigit() } },
                            label = { Text("间隔（秒）") },
                            placeholder = { Text("最小 1 秒") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(AppRadius.SM),
                            colors = pocoFieldColors()
                        )
                        Spacer(Modifier.height(4.dp))
                        val sec = intervalText.toIntOrNull() ?: 0
                        Text(
                            if (sec > 0) "即：${ScheduleStore.formatInterval(sec)}"
                            else "请输入正整数秒数",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                AnimatedVisibility(
                    visible = repeatMode == "weekly",
                    enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(120))
                ) {
                    Column {
                        Text(
                            "星期几",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("日", "一", "二", "三", "四", "五", "六").forEachIndexed { idx, label ->
                                val sel = repeatDays.contains(idx)
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (sel) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .clickable {
                                            repeatDays = if (sel) repeatDays - idx else repeatDays + idx
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        label,
                                        fontSize = 13.sp,
                                        color = if (sel) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = try { sdf.parse(timeText)?.time } catch (_: Exception) { null }
                val sec = intervalText.toIntOrNull() ?: 0
                when {
                    repeatMode != "cron" && parsed == null -> timeError = "时间格式不正确"
                    repeatMode == "cron" && cronText.isBlank() -> timeError = "请输入 cron 表达式"
                    repeatMode == "weekly" && repeatDays.isEmpty() -> timeError = "每周模式需至少选择一天"
                    action == "open_app" && packageName.isBlank() -> timeError = "请填写包名"
                    action == "run_command" && command.isBlank() -> timeError = "请填写命令"
                    action == "run_tool" && toolName.isBlank() -> timeError = "请选择工具"
                    action == "run_workflow" && workflowName.isBlank() -> timeError = "请选择工作流"
                    action == "run_agent" && agentPrompt.isBlank() -> timeError = "请输入发给 AI 的内容"
                    repeatMode == "interval" && sec < ScheduleStore.MIN_INTERVAL_SECONDS ->
                        timeError = "间隔不能小于 ${ScheduleStore.MIN_INTERVAL_SECONDS} 秒"
                    repeatMode == "interval" && sec > ScheduleStore.MAX_INTERVAL_SECONDS ->
                        timeError = "间隔不能超过 ${ScheduleStore.MAX_INTERVAL_SECONDS} 秒"
                    else -> {
                        val triggerTime = when (repeatMode) {
                            "cron" -> System.currentTimeMillis() + 60_000L
                            else -> parsed ?: System.currentTimeMillis()
                        }
                        val task = ScheduledTask(
                            id = UUID.randomUUID().toString(),
                            triggerTime = triggerTime,
                            packageName = if (action == "open_app") packageName.trim() else "",
                            appName = if (action == "open_app")
                                appName.trim().ifBlank { packageName.trim() }
                            else description.trim(),
                            repeatMode = repeatMode,
                            repeatDays = if (repeatMode == "weekly") repeatDays else emptyList(),
                            intervalSeconds = if (repeatMode == "interval") sec else 0,
                            cronExpression = if (repeatMode == "cron") cronText.trim() else "",
                            action = action,
                            command = if (action == "run_command") command.trim() else "",
                            toolName = if (action == "run_tool") toolName else "",
                            toolArgs = if (action == "run_tool") toolArgs.trim().ifBlank { "{}" } else "",
                            workflowName = if (action == "run_workflow") workflowName else "",
                            notificationTitle = if (action == "notify") notifTitle.trim() else "",
                            notificationContent = if (action == "notify") notifContent.trim() else "",
                            prompt = if (action == "run_agent") agentPrompt.trim() else "",
                            sessionId = if (action == "run_agent") agentSessionId else "",
                            description = description.trim()
                            // enabled 缺省 true，不用显式传
                        )
                        onConfirm(task)
                    }
                }
            }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// RepeatOption（统一 12dp 圆角）

@Composable
private fun RowScope.RepeatOption(
    label: String, value: String, current: String, onChange: (String) -> Unit
) {
    val selected = current == value
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(AppRadius.SM),
        modifier = Modifier.weight(1f).clickable { onChange(value) }
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}
