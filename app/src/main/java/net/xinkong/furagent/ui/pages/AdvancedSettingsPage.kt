@file:OptIn(ExperimentalMaterial3Api::class)

package net.xinkong.furagent.ui.pages

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.xinkong.furagent.data.*
import net.xinkong.furagent.system.notifyToast
import net.xinkong.furagent.tools.ToolRegistry
import net.xinkong.furagent.ui.*
import net.xinkong.furagent.ui.theme.AppRadius

/**
 * 高级设置 —— 整屏页面。
 *
 * UI 统一约定
 *   - 卡片统一 PocoCard
 *   - 页背景 glassPageBg() + 顶部 GlassSheen
 */
@Composable
fun AdvancedSettingsPage(
    settings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
    onImportRequested: (String) -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenEnvStore: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("FurAgentPrefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var local by remember { mutableStateOf(settings) }
    var showPromptAdvanced by remember { mutableStateOf(false) }
    var showNotifyToolsAdvanced by remember { mutableStateOf(false) }
    var showToolsAdvanced by remember { mutableStateOf(false) }
    var wfRefreshKey by remember { mutableIntStateOf(0) }
    var editingWorkflow by remember { mutableStateOf<Workflow?>(null) }
    var showWorkflowEditor by remember { mutableStateOf(false) }
    var pendingDeleteWorkflow by remember { mutableStateOf<Workflow?>(null) }

    LaunchedEffect(settings) {
        if (settings != local) local = settings
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    // 上限 50MB：备份文件含 base64 图片时会很大。不加限制时
                    // readText() 进内存（Java 字符串 2 字节/字符）+ 后续
                    // JSONObject(text) 再解析一次，峰值可达文件大小 ×2，
                    // 中端机（4GB RAM）直接 OOM。
                    val size = try {
                        val afd = context.contentResolver.openAssetFileDescriptor(it, "r")
                        val len = afd?.length ?: -1L
                        afd?.close()
                        len
                    } catch (_: Exception) { -1L }
                    if (size > 50L * 1024 * 1024) {
                        notifyToast(
                            context,
                            "备份文件过大（${size / 1024 / 1024}MB），上限 50MB"
                        )
                        return@launch
                    }
                    val inputStream = context.contentResolver.openInputStream(it)
                    val text = inputStream?.bufferedReader()?.use { reader -> reader.readText() }
                    if (text.isNullOrBlank()) {
                        notifyToast(context, "读取文件失败")
                        return@launch
                    }
                    onImportRequested(text)
                } catch (e: Exception) {
                    notifyToast(context, "读取失败：${e.message}")
                }
            }
        }
    }

    fun quickSave(newSettings: AppSettings) {
        local = newSettings
        SettingsStore.save(prefs, newSettings)
        onSettingsChanged(newSettings)
    }

    fun flushAndBack() {
        onSettingsChanged(local)
        onBack()
    }

    ApplyBackgroundBlur(true)

    if (showWorkflowEditor) {
        WorkflowEditorDialog(
            initial = editingWorkflow,
            onDismiss = { showWorkflowEditor = false; editingWorkflow = null },
            onSave = { wf ->
                WorkflowStore.save(context, wf)
                wfRefreshKey++
                showWorkflowEditor = false
                editingWorkflow = null
                notifyToast(context, "已保存")
            }
        )
    }

    pendingDeleteWorkflow?.let { w ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDeleteWorkflow = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("删除工作流") },
            text = { Text("确定删除「${w.name}」？不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    WorkflowStore.delete(context, w.id)
                    wfRefreshKey++
                    pendingDeleteWorkflow = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteWorkflow = null }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            PocoPageTopBar(
                title = "高级设置",
                subtitle = "安全、权限、提示词、工具与工作流",
                onBack = { flushAndBack() },
                actions = {
                    TextButton(onClick = { flushAndBack() }) {
                        Text(
                            "完成",
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                // ============ 记忆 ============
                // 去重 安全确认开关已统一到「设置 → 安全确认」，
                // 这里不再重复一份（之前两处都有，用户看到两个“安全确认”很困惑，
                // 且这里缺 requireConfirmPython，两边不一致）。
                item { PocoGroupTitle("记忆") }
                item {
                    PocoCard {
                        PocoSwitchRow(
                            "启用长期记忆", "AI 自动读取已保存的记忆",
                            local.enableMemory
                        ) { quickSave(local.copy(enableMemory = it)) }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }

                // ============ 权限 ============
                item { PocoGroupTitle("权限") }
                item {
                    PocoCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "查看并管理通知、悬浮窗、定位、Shizuku 等系统权限。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = onOpenPermissions,
                                shape = RoundedCornerShape(AppRadius.SM),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("打开权限管理")
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }

                // ============ 用户附加指令 ============
                item { PocoGroupTitle("用户附加指令") }
                item {
                    PocoCard {
                        Column(modifier = Modifier.padding(16.dp).animateContentSize()) {
                            Text(
                                "默认系统提示词始终生效，这里填写的内容会追加在后面，" +
                                        "用于额外指令（例如\"回答尽量简短\"），而不是覆盖默认规则。" +
                                        "支持 {TIME} 和 {PATHS} 占位符。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { showPromptAdvanced = !showPromptAdvanced }) {
                                Text(
                                    if (showPromptAdvanced) "收起" else "展开编辑",
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            AnimatedVisibility(
                                visible = showPromptAdvanced,
                                enter = expandVertically(tween(240)) + fadeIn(tween(200)),
                                exit = shrinkVertically(tween(200)) + fadeOut(tween(150))
                            ) {
                                PocoTextField(
                                    local.customSystemPrompt,
                                    { quickSave(local.copy(customSystemPrompt = it)) },
                                    minLines = 6
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }

                // ============ 工具 ============
                item { PocoGroupTitle("工具") }
                item {
                    PocoCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "关闭不需要的工具可以减少模型误调用，并节省 token 消耗。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { showToolsAdvanced = !showToolsAdvanced }) {
                                Text(
                                    if (showToolsAdvanced) "收起工具列表" else "展开工具列表",
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                item {
                    AnimatedVisibility(
                        visible = showToolsAdvanced,
                        enter = expandVertically(tween(240)) + fadeIn(tween(200)),
                        exit = shrinkVertically(tween(200)) + fadeOut(tween(150))
                    ) {
                        PocoCard {
                            val toolList = ToolRegistry.allToolNames()
                            Column {
                                toolList.forEachIndexed { index, (toolName, displayName) ->
                                    PocoSwitchRow(
                                        title = displayName,
                                        subtitle = toolName,
                                        checked = toolName !in local.disabledTools
                                    ) { enabled ->
                                        val newDisabled = if (enabled) {
                                            local.disabledTools - toolName
                                        } else {
                                            local.disabledTools + toolName
                                        }
                                        quickSave(local.copy(disabledTools = newDisabled))
                                    }
                                    if (index != toolList.lastIndex) PocoDivider(startIndent = 16.dp)
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }

                // ============ 环境变量 ============
                item { PocoGroupTitle("环境变量") }
                item {
                    PocoCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "AI 通过 env_store 工具读写的键值对，跨轮次保存数据。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = onOpenEnvStore,
                                shape = RoundedCornerShape(AppRadius.SM),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("打开环境变量管理")
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }

                // ============ 工具通知白名单 ============
                item { PocoGroupTitle("工具通知白名单") }
                item {
                    PocoCard {
                        Column(modifier = Modifier.padding(16.dp).animateContentSize()) {
                            Text(
                                "为空表示所有工具都通知；非空时只通知勾选的工具。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { showNotifyToolsAdvanced = !showNotifyToolsAdvanced }) {
                                Text(
                                    if (showNotifyToolsAdvanced) "收起" else "展开白名单",
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            AnimatedVisibility(
                                visible = showNotifyToolsAdvanced,
                                enter = expandVertically(tween(240)) + fadeIn(tween(200)),
                                exit = shrinkVertically(tween(200)) + fadeOut(tween(150))
                            ) {
                                Column {
                                    val tools = ToolRegistry.allToolNames()
                                    tools.forEachIndexed { idx, (toolName, displayName) ->
                                        PocoSwitchRow(
                                            title = displayName,
                                            subtitle = toolName,
                                            checked = toolName in local.notifyTools
                                        ) { enabled ->
                                            val newSet = if (enabled) {
                                                local.notifyTools + toolName
                                            } else {
                                                local.notifyTools - toolName
                                            }
                                            quickSave(local.copy(notifyTools = newSet))
                                        }
                                        if (idx != tools.lastIndex) PocoDivider(startIndent = 16.dp)
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }

                // ============ 工作流 ============
                item { PocoGroupTitle("工作流") }
                item {
                    PocoCard {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "工作流把多个工具调用串成命名流程，AI 可以通过 workflow 工具直接执行。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val workflows = remember(wfRefreshKey) { WorkflowStore.list(context) }
                            if (workflows.isEmpty()) {
                                Text(
                                    "暂无工作流。可点下方按钮添加。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                workflows.forEach { w ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .animateContentSize()
                                        ) {
                                            Text(w.name, style = MaterialTheme.typography.bodyMedium)
                                            if (w.description.isNotBlank()) {
                                                Text(
                                                    w.description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Text(
                                                "${w.steps.size} 步 · 参数：${
                                                    w.params.joinToString(", ").ifBlank { "无" }
                                                }",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        TextButton(onClick = {
                                            editingWorkflow = w
                                            showWorkflowEditor = true
                                        }) { Text("编辑") }
                                        TextButton(onClick = {
                                            pendingDeleteWorkflow = w
                                        }) {
                                            Text("删除", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    editingWorkflow = null
                                    showWorkflowEditor = true
                                },
                                shape = RoundedCornerShape(AppRadius.SM),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("添加工作流") }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }

                // ============ 备份与恢复 ============
                item { PocoGroupTitle("备份与恢复") }
                item {
                    PocoCard {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = {
                                    val f = SessionStore.exportAll(context, prefs)
                                    if (f != null) {
                                        notifyToast(context, "已导出：${f.absolutePath}")
                                    } else {
                                        notifyToast(context, "导出失败（无存储权限或路径不可写）")
                                    }
                                },
                                shape = RoundedCornerShape(AppRadius.SM),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("导出全部配置与会话") }

                            OutlinedButton(
                                onClick = { importLauncher.launch("application/json") },
                                shape = RoundedCornerShape(AppRadius.SM),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("导入配置 (JSON)") }

                            Text(
                                "导出时 API Key 会被内置密钥混淆；导入时自动还原。" +
                                        "导入时会弹出选项：追加导入保留现有数据，覆盖导入会先清空现有会话与记忆（覆盖前会二次确认）。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}