package net.xinkong.furagent.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.xinkong.furagent.data.ModelProfile
import net.xinkong.furagent.data.ScheduleStore
import net.xinkong.furagent.data.ScheduledTask
import java.text.SimpleDateFormat
import java.util.*

// ============================================================
// 通用输入对话框
// ============================================================
//
// 【UI 统一】所有弹窗均 28dp 圆角 + surface 底色 + 主色走 colorScheme。

@Composable
fun InputDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ============================================================
// 编辑模型对话框
// ============================================================

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
    var supportsDisable by remember { mutableStateOf(initial.supportsDisableThinking) }
    var supportsVision by remember { mutableStateOf(initial.supportsVision) }
    var temperature by remember { mutableStateOf(initial.temperature.toFloat()) }
    val isImageModel = initial.kind == "image"

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
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
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    placeholder = { Text("http://192.168.1.100:11434/v1/chat/completions") },
                    modifier = Modifier.fillMaxWidth(), singleLine = false, maxLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
                OutlinedTextField(
                    value = model, onValueChange = { model = it },
                    label = { Text("模型名称") },
                    placeholder = { Text("例如：qwen2.5:7b") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text("sk-... 或留空") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { supportsDisable = !supportsDisable },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = supportsDisable,
                        onCheckedChange = { supportsDisable = it }
                    )
                    Spacer(Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "支持关闭深度思考",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "强制思考的模型请取消勾选",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (!isImageModel) {
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
                    Slider(
                        value = temperature,
                        onValueChange = { temperature = it },
                        valueRange = 0f..1.5f,
                        steps = 29
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
                        supportsDisableThinking = supportsDisable,
                        supportsVision = supportsVision,
                        temperature = temperature.toDouble()
                    ))
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ============================================================
// 编辑定时任务对话框（【F4】加启用开关）
// ============================================================

@Composable
fun EditScheduleDialog(
    initial: ScheduledTask,
    onDismiss: () -> Unit,
    onConfirm: (ScheduledTask) -> Unit
) {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
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

    // 【F4】启用状态
    var enabled by remember { mutableStateOf(initial.enabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
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
                        "run_cron" -> initial.command
                        else -> initial.appName.ifBlank { initial.packageName }
                    }
                    Text(
                        target,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // 【F4】启用开关
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
                    shape = RoundedCornerShape(12.dp),
                    isError = timeError != null,
                    supportingText = timeError?.let { { Text(it) } },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
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
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                            )
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
                            // 【F4】
                            enabled = enabled
                        ))
                    }
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ============================================================
// 新建定时任务对话框
// ============================================================
//
// 【F4】新建默认 enabled=true，不加开关（想关的用户建完到列表里关即可）。

@Composable
fun NewScheduleDialog(
    onDismiss: () -> Unit,
    onConfirm: (ScheduledTask) -> Unit
) {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    var action by remember { mutableStateOf("open_app") }
    var packageName by remember { mutableStateOf("") }
    var appName by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var timeText by remember {
        mutableStateOf(sdf.format(Date(System.currentTimeMillis() + 60_000L)))
    }
    var timeError by remember { mutableStateOf<String?>(null) }
    var repeatMode by remember { mutableStateOf("once") }
    var intervalText by remember { mutableStateOf("60") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text("新建定时任务") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column {
                    Text(
                        "类型",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RepeatOption("打开应用", "open_app", action) { action = it }
                        RepeatOption("Shell 命令", "run_cron", action) { action = it }
                    }
                }

                if (action == "open_app") {
                    OutlinedTextField(
                        value = packageName, onValueChange = { packageName = it },
                        label = { Text("包名") },
                        placeholder = { Text("com.example.app") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                    OutlinedTextField(
                        value = appName, onValueChange = { appName = it },
                        label = { Text("显示名称（可选）") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                } else {
                    OutlinedTextField(
                        value = command, onValueChange = { command = it },
                        label = { Text("Shell 命令") },
                        placeholder = { Text("am start -n ...") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                        minLines = 3,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                }

                Column {
                    Text(
                        "重复",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RepeatOption("单次", "once", repeatMode) { repeatMode = it }
                        RepeatOption("按间隔", "interval", repeatMode) { repeatMode = it }
                    }
                }

                OutlinedTextField(
                    value = timeText,
                    onValueChange = { timeText = it; timeError = null },
                    label = {
                        Text(if (repeatMode == "interval") "首次触发时间" else "触发时间")
                    },
                    placeholder = { Text("yyyy-MM-dd HH:mm:ss") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    isError = timeError != null,
                    supportingText = timeError?.let { { Text(it) } },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )

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
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                            )
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
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = try { sdf.parse(timeText)?.time } catch (_: Exception) { null }
                val sec = intervalText.toIntOrNull() ?: 0
                when {
                    parsed == null -> timeError = "时间格式不正确"
                    action == "open_app" && packageName.isBlank() -> timeError = "请填写包名"
                    action == "run_cron" && command.isBlank() -> timeError = "请填写命令"
                    repeatMode == "interval" && sec < ScheduleStore.MIN_INTERVAL_SECONDS ->
                        timeError = "间隔不能小于 ${ScheduleStore.MIN_INTERVAL_SECONDS} 秒"
                    repeatMode == "interval" && sec > ScheduleStore.MAX_INTERVAL_SECONDS ->
                        timeError = "间隔不能超过 ${ScheduleStore.MAX_INTERVAL_SECONDS} 秒"
                    else -> {
                        val task = ScheduledTask(
                            id = UUID.randomUUID().toString(),
                            triggerTime = parsed,
                            packageName = if (action == "open_app") packageName.trim() else "",
                            appName = if (action == "open_app")
                                appName.trim().ifBlank { packageName.trim() }
                            else "run_cron: ${command.trim().take(30)}",
                            repeatMode = repeatMode,
                            intervalSeconds = if (repeatMode == "interval") sec else 0,
                            action = action,
                            command = if (action == "run_cron") command.trim() else ""
                            // 【F4】enabled 缺省 true，不用显式传
                        )
                        onConfirm(task)
                    }
                }
            }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ============================================================
// RepeatOption（统一 12dp 圆角）
// ============================================================

@Composable
private fun RowScope.RepeatOption(
    label: String, value: String, current: String, onChange: (String) -> Unit
) {
    val selected = current == value
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(12.dp),
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