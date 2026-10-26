package net.xinkong.furagent.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.xinkong.furagent.desktop.data.Workflow
import net.xinkong.furagent.desktop.data.WorkflowStep
import net.xinkong.furagent.desktop.tools.DesktopTools
import net.xinkong.furagent.desktop.ui.theme.Radius
import java.util.*

// 统一 OutlinedTextField 配色（批次 17 色板统一）
// 输入框配色统一到 FurColors，不再混用 M3 colorScheme，
// 与 GlassTextField 的配色逻辑保持一致。

@Composable
private fun workflowDialogFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = CardColor(),
    unfocusedContainerColor = CardColor(),
    focusedBorderColor = AccentColor(),
    unfocusedBorderColor = BorderColor(),
    cursorColor = AccentColor(),
    focusedTextColor = TextPrimary(),
    unfocusedTextColor = TextPrimary()
)

/**
 * 工作流可视化编辑器。
 *
 * 步骤类型：
 *  - call       调用工具
 *  - condition  条件分支
 *  - loop       循环
 *  - delay      延时
 *  - set_var    设置变量
 *
 * UI 统一约定
 *   - StepTypeChip 12dp 圆角，主色走 colorScheme
 *   - StepCard 用 surfaceVariant 0.4f 底 + 16dp 圆角
 *   - 输入框统一 surfaceVariant 0.4~0.5f 底 + outlineVariant 边
 */
@Composable
fun WorkflowEditorDialog(
    initial: Workflow?,
    onDismiss: () -> Unit,
    onSave: (Workflow) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var paramsText by remember { mutableStateOf(initial?.params?.joinToString(", ") ?: "") }
    var steps by remember { mutableStateOf(initial?.steps ?: emptyList()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var editingStepIndex by remember { mutableIntStateOf(-1) }
    var editingStep by remember { mutableStateOf<WorkflowStep?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(Radius.DIALOG),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(if (initial == null) "新建工作流" else "编辑工作流") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; errorMsg = null },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radius.SM),
                    colors = workflowDialogFieldColors()
                )
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text("说明（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radius.SM),
                    colors = workflowDialogFieldColors()
                )
                OutlinedTextField(
                    value = paramsText, onValueChange = { paramsText = it },
                    label = { Text("参数名（逗号分隔，可选）") },
                    placeholder = { Text("例如：app_name, delay") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radius.SM),
                    colors = workflowDialogFieldColors()
                )

                Text(
                    "步骤（从上往下执行）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(steps) { idx, step ->
                        StepCard(
                            index = idx,
                            step = step,
                            total = steps.size,
                            onEdit = {
                                editingStepIndex = idx
                                editingStep = step
                            },
                            onDelete = {
                                steps = steps.toMutableList().also { it.removeAt(idx) }
                            },
                            onMoveUp = {
                                if (idx > 0) {
                                    val l = steps.toMutableList()
                                    val t = l[idx - 1]; l[idx - 1] = l[idx]; l[idx] = t
                                    steps = l
                                }
                            },
                            onMoveDown = {
                                if (idx < steps.size - 1) {
                                    val l = steps.toMutableList()
                                    val t = l[idx + 1]; l[idx + 1] = l[idx]; l[idx] = t
                                    steps = l
                                }
                            }
                        )
                    }
                    item {
                        OutlinedButton(
                            onClick = {
                                editingStepIndex = -1
                                editingStep = WorkflowStep(stepType = "call")
                            },
                            shape = RoundedCornerShape(Radius.SM),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("添加步骤")
                        }
                    }
                }

                errorMsg?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) {
                    errorMsg = "名称不能为空"; return@TextButton
                }
                if (steps.isEmpty()) {
                    errorMsg = "至少需要一个步骤"; return@TextButton
                }
                val params = paramsText.split(',').map { it.trim() }.filter { it.isNotBlank() }
                onSave(
                    Workflow(
                        id = initial?.id ?: UUID.randomUUID().toString(),
                        name = name.trim(),
                        description = description.trim(),
                        params = params,
                        steps = steps
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )

    editingStep?.let { step ->
        StepEditorDialog(
            initial = step,
            onDismiss = { editingStep = null; editingStepIndex = -1 },
            onSave = { newStep ->
                steps = if (editingStepIndex >= 0) {
                    steps.toMutableList().also { it[editingStepIndex] = newStep }
                } else {
                    steps + newStep
                }
                editingStep = null
                editingStepIndex = -1
            }
        )
    }
}

// 步骤卡片

@Composable
private fun StepCard(
    index: Int,
    step: WorkflowStep,
    total: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val info = describeStep(step)

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(Radius.SM),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable { onEdit() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = info.color.copy(alpha = 0.15f),
                shape = CircleShape,
                modifier = Modifier.size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        info.icon,
                        contentDescription = null,
                        tint = info.color,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${index + 1}. ${stepTypeLabel(step.stepType)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    info.summary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            Column {
                IconButton(
                    onClick = onMoveUp,
                    enabled = index > 0,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "上移",
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = index < total - 1,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "下移",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private data class StepInfo(
    val icon: ImageVector,
    val color: Color,
    val summary: String
)

private fun describeStep(step: WorkflowStep): StepInfo {
    return when (step.stepType) {
        "call" -> StepInfo(
            Icons.Default.Build,
            Color(0xFF6E7CFA),
            "${DesktopTools.displayName(step.toolName)}（${step.argsTemplate.size} 参数）"
        )
        "condition" -> StepInfo(
            Icons.AutoMirrored.Filled.CallSplit,
            Color(0xFFF59E0B),
            "条件：${step.conditionExpr.ifBlank { "(未设置)" }}"
        )
        "loop" -> StepInfo(
            Icons.Default.Loop,
            Color(0xFF8B5CF6),
            "${step.loopVar} over ${step.loopItems.ifBlank { "(未设置)" }}（${step.loopBody.size} 子步骤）"
        )
        "delay" -> StepInfo(
            Icons.Default.Schedule,
            Color(0xFF10B981),
            "等待 ${step.delaySeconds} 秒"
        )
        "set_var" -> StepInfo(
            Icons.Default.Code,
            Color(0xFF6366F1),
            "${step.varName} = ${step.varValue.take(40)}"
        )
        else -> StepInfo(
            Icons.AutoMirrored.Filled.Help,
            Color.Gray,
            "未知类型：${step.stepType}"
        )
    }
}

private fun stepTypeLabel(type: String): String = when (type) {
    "call" -> "调用工具"
    "condition" -> "条件分支"
    "loop" -> "循环"
    "delay" -> "延时"
    "set_var" -> "设置变量"
    else -> type
}

// 步骤编辑器

@Composable
fun StepEditorDialog(
    initial: WorkflowStep,
    onDismiss: () -> Unit,
    onSave: (WorkflowStep) -> Unit
) {
    var stepType by remember { mutableStateOf(initial.stepType.ifBlank { "call" }) }
    var toolName by remember { mutableStateOf(initial.toolName) }
    var argsList by remember { mutableStateOf(initial.argsTemplate.toList()) }
    var conditionExpr by remember { mutableStateOf(initial.conditionExpr) }
    var trueBranch by remember { mutableStateOf(initial.trueBranch) }
    var falseBranch by remember { mutableStateOf(initial.falseBranch) }
    var loopVar by remember { mutableStateOf(initial.loopVar) }
    var loopItems by remember { mutableStateOf(initial.loopItems) }
    var loopBody by remember { mutableStateOf(initial.loopBody) }
    var delayText by remember {
        mutableStateOf(if (initial.delaySeconds > 0) initial.delaySeconds.toString() else "1")
    }
    var varName by remember { mutableStateOf(initial.varName) }
    var varValue by remember { mutableStateOf(initial.varValue) }

    var editingBranch by remember { mutableStateOf<String?>(null) }
    var editingBranchIndex by remember { mutableIntStateOf(-1) }
    var editingBranchStep by remember { mutableStateOf<WorkflowStep?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(Radius.DIALOG),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("编辑步骤") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StepTypeChip("调用", "call", stepType) { stepType = it }
                    StepTypeChip("条件", "condition", stepType) { stepType = it }
                    StepTypeChip("循环", "loop", stepType) { stepType = it }
                    StepTypeChip("延时", "delay", stepType) { stepType = it }
                    StepTypeChip("变量", "set_var", stepType) { stepType = it }
                }

                when (stepType) {
                    "call" -> CallStepForm(
                        toolName = toolName,
                        onToolNameChange = { toolName = it },
                        argsList = argsList,
                        onArgsChange = { argsList = it }
                    )
                    "condition" -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = conditionExpr, onValueChange = { conditionExpr = it },
                                label = { Text("条件表达式") },
                                placeholder = { Text("例如 contains:成功") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(Radius.SM),
                                colors = workflowDialogFieldColors()
                            )
                            Text(
                                "支持 contains:x / notContains:x / startsWith:x / endsWith:x / equals:x / matches:regex / isEmpty / isNotEmpty",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            BranchEditor(
                                label = "满足时执行",
                                steps = trueBranch,
                                onAdd = {
                                    editingBranch = "true"
                                    editingBranchIndex = -1
                                    editingBranchStep = WorkflowStep(stepType = "call")
                                },
                                onEdit = { i, s ->
                                    editingBranch = "true"
                                    editingBranchIndex = i
                                    editingBranchStep = s
                                },
                                onDelete = { i ->
                                    trueBranch = trueBranch.toMutableList().also { it.removeAt(i) }
                                },
                                onMoveUp = { i ->
                                    if (i > 0) {
                                        val l = trueBranch.toMutableList()
                                        val t = l[i - 1]; l[i - 1] = l[i]; l[i] = t
                                        trueBranch = l
                                    }
                                },
                                onMoveDown = { i ->
                                    if (i < trueBranch.size - 1) {
                                        val l = trueBranch.toMutableList()
                                        val t = l[i + 1]; l[i + 1] = l[i]; l[i] = t
                                        trueBranch = l
                                    }
                                }
                            )
                            BranchEditor(
                                label = "不满足时执行",
                                steps = falseBranch,
                                onAdd = {
                                    editingBranch = "false"
                                    editingBranchIndex = -1
                                    editingBranchStep = WorkflowStep(stepType = "call")
                                },
                                onEdit = { i, s ->
                                    editingBranch = "false"
                                    editingBranchIndex = i
                                    editingBranchStep = s
                                },
                                onDelete = { i ->
                                    falseBranch = falseBranch.toMutableList().also { it.removeAt(i) }
                                },
                                onMoveUp = { i ->
                                    if (i > 0) {
                                        val l = falseBranch.toMutableList()
                                        val t = l[i - 1]; l[i - 1] = l[i]; l[i] = t
                                        falseBranch = l
                                    }
                                },
                                onMoveDown = { i ->
                                    if (i < falseBranch.size - 1) {
                                        val l = falseBranch.toMutableList()
                                        val t = l[i + 1]; l[i + 1] = l[i]; l[i] = t
                                        falseBranch = l
                                    }
                                }
                            )
                        }
                    }
                    "loop" -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = loopVar, onValueChange = { loopVar = it },
                                label = { Text("循环变量名") },
                                placeholder = { Text("例如 item") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(Radius.SM),
                                colors = workflowDialogFieldColors()
                            )
                            OutlinedTextField(
                                value = loopItems, onValueChange = { loopItems = it },
                                label = { Text("遍历项") },
                                placeholder = {
                                    Text("a,b,c 或 1..5 或 " + $$"$listVar")
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(Radius.SM),
                                colors = workflowDialogFieldColors()
                            )
                            BranchEditor(
                                label = "循环体",
                                steps = loopBody,
                                onAdd = {
                                    editingBranch = "loop"
                                    editingBranchIndex = -1
                                    editingBranchStep = WorkflowStep(stepType = "call")
                                },
                                onEdit = { i, s ->
                                    editingBranch = "loop"
                                    editingBranchIndex = i
                                    editingBranchStep = s
                                },
                                onDelete = { i ->
                                    loopBody = loopBody.toMutableList().also { it.removeAt(i) }
                                },
                                onMoveUp = { i ->
                                    if (i > 0) {
                                        val l = loopBody.toMutableList()
                                        val t = l[i - 1]; l[i - 1] = l[i]; l[i] = t
                                        loopBody = l
                                    }
                                },
                                onMoveDown = { i ->
                                    if (i < loopBody.size - 1) {
                                        val l = loopBody.toMutableList()
                                        val t = l[i + 1]; l[i + 1] = l[i]; l[i] = t
                                        loopBody = l
                                    }
                                }
                            )
                        }
                    }
                    "delay" -> {
                        OutlinedTextField(
                            value = delayText,
                            onValueChange = { delayText = it.filter { c -> c.isDigit() } },
                            label = { Text("等待秒数") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(Radius.SM),
                            colors = workflowDialogFieldColors()
                        )
                    }
                    "set_var" -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = varName, onValueChange = { varName = it },
                                label = { Text("变量名") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(Radius.SM),
                                colors = workflowDialogFieldColors()
                            )
                            OutlinedTextField(
                                value = varValue, onValueChange = { varValue = it },
                                label = { Text("值") },
                                placeholder = {
                                    Text("可以是字面值或 " + $$"$prev 引用上一步结果")
                                },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                shape = RoundedCornerShape(Radius.SM),
                                colors = workflowDialogFieldColors()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val newStep = when (stepType) {
                    "call" -> WorkflowStep(
                        stepType = "call",
                        toolName = toolName,
                        argsTemplate = argsList.filter { it.first.isNotBlank() }.toMap()
                    )
                    "condition" -> WorkflowStep(
                        stepType = "condition",
                        conditionExpr = conditionExpr,
                        trueBranch = trueBranch,
                        falseBranch = falseBranch
                    )
                    "loop" -> WorkflowStep(
                        stepType = "loop",
                        loopVar = loopVar,
                        loopItems = loopItems,
                        loopBody = loopBody
                    )
                    "delay" -> WorkflowStep(
                        stepType = "delay",
                        delaySeconds = (delayText.toIntOrNull() ?: 1).coerceIn(0, 600)
                    )
                    "set_var" -> WorkflowStep(
                        stepType = "set_var",
                        varName = varName,
                        varValue = varValue
                    )
                    else -> initial
                }
                onSave(newStep)
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )

    editingBranchStep?.let { step ->
        StepEditorDialog(
            initial = step,
            onDismiss = {
                editingBranchStep = null
                editingBranchIndex = -1
                editingBranch = null
            },
            onSave = { newStep ->
                when (editingBranch) {
                    "true" -> trueBranch = if (editingBranchIndex >= 0) {
                        trueBranch.toMutableList().also { it[editingBranchIndex] = newStep }
                    } else trueBranch + newStep
                    "false" -> falseBranch = if (editingBranchIndex >= 0) {
                        falseBranch.toMutableList().also { it[editingBranchIndex] = newStep }
                    } else falseBranch + newStep
                    "loop" -> loopBody = if (editingBranchIndex >= 0) {
                        loopBody.toMutableList().also { it[editingBranchIndex] = newStep }
                    } else loopBody + newStep
                }
                editingBranchStep = null
                editingBranchIndex = -1
                editingBranch = null
            }
        )
    }
}

// StepTypeChip（统一 12dp 圆角）

@Composable
private fun RowScope.StepTypeChip(
    label: String, value: String, current: String, onChange: (String) -> Unit
) {
    val selected = current == value
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(Radius.SM),
        modifier = Modifier.weight(1f).clickable { onChange(value) }
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}

// 分支编辑器

@Composable
private fun BranchEditor(
    label: String,
    steps: List<WorkflowStep>,
    onAdd: () -> Unit,
    onEdit: (Int, WorkflowStep) -> Unit,
    onDelete: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        steps.forEachIndexed { i, s ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "• ${stepTypeLabel(s.stepType)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).clickable { onEdit(i, s) }
                )
                IconButton(
                    onClick = { onMoveUp(i) },
                    enabled = i > 0,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, null, modifier = Modifier.size(14.dp))
                }
                IconButton(
                    onClick = { onMoveDown(i) },
                    enabled = i < steps.size - 1,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(14.dp))
                }
                IconButton(onClick = { onDelete(i) }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Close,
                        null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        TextButton(onClick = onAdd, contentPadding = PaddingValues(0.dp)) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("添加", fontSize = 12.sp)
        }
    }
}

// 调用工具表单

@Composable
private fun CallStepForm(
    toolName: String,
    onToolNameChange: (String) -> Unit,
    argsList: List<Pair<String, String>>,
    onArgsChange: (List<Pair<String, String>>) -> Unit
) {
    val allTools = remember { DesktopTools.allToolNames() }
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "工具",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                shape = RoundedCornerShape(Radius.SM),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    toolName.ifBlank { "选择工具" },
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                allTools.forEach { (name, display) ->
                    DropdownMenuItem(
                        text = { Text("$display  ($name)") },
                        onClick = {
                            onToolNameChange(name)
                            expanded = false
                        }
                    )
                }
            }
        }

        Text(
            "参数",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        argsList.forEachIndexed { idx, (k, v) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                OutlinedTextField(
                    value = k,
                    onValueChange = { newK ->
                        onArgsChange(argsList.toMutableList().also { it[idx] = newK to v })
                    },
                    label = { Text("参数名") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Radius.SM),
                    colors = workflowDialogFieldColors()
                )
                OutlinedTextField(
                    value = v,
                    onValueChange = { newV ->
                        onArgsChange(argsList.toMutableList().also { it[idx] = k to newV })
                    },
                    label = { Text("值") },
                    singleLine = true,
                    modifier = Modifier.weight(1.6f),
                    shape = RoundedCornerShape(Radius.SM),
                    colors = workflowDialogFieldColors()
                )
                IconButton(
                    onClick = {
                        onArgsChange(argsList.toMutableList().also { it.removeAt(idx) })
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                }
            }
        }
        TextButton(
            onClick = { onArgsChange(argsList + ("" to "")) },
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("添加参数", fontSize = 12.sp)
        }

        Text(
            "提示：值里可以用 " + $$"$变量名 引用工作流变量，" +
                    $$"$prev 引用上一步结果，" +
                    $$"$index 引用当前循环下标",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}