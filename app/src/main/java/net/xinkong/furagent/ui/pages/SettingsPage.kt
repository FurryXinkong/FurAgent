package net.xinkong.furagent.ui.pages

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.core.testModelConnection
import net.xinkong.furagent.data.AppSettings
import net.xinkong.furagent.data.ModelProfile
import net.xinkong.furagent.data.SettingsStore
import net.xinkong.furagent.shared.icons.Icons
import net.xinkong.furagent.shared.update.UpdateChecker
import net.xinkong.furagent.system.notifyToast
import net.xinkong.furagent.ui.*
import net.xinkong.furagent.ui.theme.AppRadius
import java.util.*

/**
 * 设置页可隐藏的分组列表（P1-15）。
 *
 * key 与桌面端 DesktopSettings.hiddenSettings 对齐。
 * "advanced" 自身不在列表内（否则用户无法恢复隐藏的分组）。
 */
val HIDDEN_GROUPS: List<Pair<String, String>> = listOf(
    "appearance" to "外观",
    "files" to "文件",
    "model" to "模型",
    "agent" to "对话",
    "security" to "安全确认",
    "image" to "图片压缩",
    "notification" to "通知与语音",
    "new_features" to "新功能"
)

/**
 * 快捷设置 —— 整屏页面。
 *
 * UI 统一约定
 *   - 卡片统一 PocoCard
 *   - 页背景 glassPageBg() + 顶部 GlassSheen
 *   - 主色 / 危险色走 MaterialTheme.colorScheme
 * 分组可在「高级」里隐藏
 */
@Composable
fun SettingsPage(
    settings: AppSettings,
    maxTurns: Int,
    themeMode: String,
    onThemeModeChanged: (String) -> Unit,
    onSettingsChanged: (Int, AppSettings) -> Unit,
    onBack: () -> Unit,
    /** 是否检测到新版本，用于在「关于我们」分组标题上点红点 */
    hasUpdate: Boolean = false,
    /** 手动检查更新（忽略缓存） */
    onCheckUpdate: () -> Unit = {},
    /** 一键下载更新包（检测到更新时可用） */
    onDownloadUpdate: () -> Unit = {}
) {
    val context = LocalContext.current
    // 版本号：从 PackageManager 读，读不到就留空
    val appVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    val prefs = remember { context.getSharedPreferences("FurAgentPrefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var turnsInput by remember { mutableStateOf(maxTurns.toString()) }
    var local by remember { mutableStateOf(settings) }
    // 用 local.hiddenSettings 判断分组可见性（quickSave 会更新 local）
    fun isVisible(key: String) = key !in local.hiddenSettings
    var expandedProfileId by remember { mutableStateOf<String?>(null) }
    var editingProfile by remember { mutableStateOf<ModelProfile?>(null) }
    var showAddModel by remember { mutableStateOf(false) }
    var showAddImageModel by remember { mutableStateOf(false) }
    var pendingDeleteProfileId by remember { mutableStateOf<String?>(null) }
    var testingProfileId by remember { mutableStateOf<String?>(null) }
    var testResultText by remember { mutableStateOf<String?>(null) }
    var testResultOk by remember { mutableStateOf(false) }

    LaunchedEffect(settings) {
        if (settings != local) local = settings
    }
    LaunchedEffect(maxTurns) {
        if (turnsInput != maxTurns.toString()) turnsInput = maxTurns.toString()
    }

    fun quickSave(newSettings: AppSettings) {
        local = newSettings
        SettingsStore.save(prefs, newSettings)
        onSettingsChanged(turnsInput.toIntOrNull() ?: maxTurns, newSettings)
    }

    fun flushAndBack() {
        onSettingsChanged(turnsInput.toIntOrNull()?.coerceAtLeast(1) ?: maxTurns, local)
        onBack()
    }

    ApplyBackgroundBlur(true)

    editingProfile?.let { profile ->
        EditModelDialog(
            title = "编辑模型",
            initial = profile,
            onDismiss = { editingProfile = null },
            onConfirm = { updated ->
                quickSave(local.copy(
                    profiles = local.profiles.map { if (it.id == updated.id) updated else it }
                ))
                editingProfile = null
            }
        )
    }
    if (showAddModel) {
        EditModelDialog(
            title = "添加自定义模型",
            initial = ModelProfile(
                id = UUID.randomUUID().toString(),
                name = "", apiKey = "", baseUrl = "", model = "", isBuiltIn = false
            ),
            onDismiss = { showAddModel = false },
            onConfirm = { newProfile ->
                quickSave(local.copy(
                    profiles = local.profiles + newProfile,
                    currentProfileId = newProfile.id
                ))
                showAddModel = false
            }
        )
    }
    if (showAddImageModel) {
        EditModelDialog(
            title = "添加文生图模型",
            initial = ModelProfile(
                id = "image_${UUID.randomUUID()}",
                name = "", apiKey = "", baseUrl = "", model = "",
                isBuiltIn = false, kind = "image"
            ),
            onDismiss = { showAddImageModel = false },
            onConfirm = { newProfile ->
                quickSave(local.copy(
                    profiles = local.profiles + newProfile,
                    imageProfileId = newProfile.id
                ))
                showAddImageModel = false
            }
        )
    }

    pendingDeleteProfileId?.let { id ->
        val p = local.profiles.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { pendingDeleteProfileId = null },
            shape = RoundedCornerShape(AppRadius.DIALOG),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("删除模型") },
            text = { Text("确定删除「${p?.name ?: ""}」？") },
            confirmButton = {
                TextButton(onClick = {
                    if (local.profiles.size <= 1) {
                        notifyToast(context, "至少保留一个模型")
                    } else {
                        val newProfiles = local.profiles.filter { it.id != id }
                        val newCurrentId = if (id == local.currentProfileId) newProfiles.first().id
                        else local.currentProfileId
                        quickSave(local.copy(
                            profiles = newProfiles,
                            currentProfileId = newCurrentId
                        ))
                        if (expandedProfileId == id) expandedProfileId = null
                    }
                    pendingDeleteProfileId = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteProfileId = null }) { Text("取消") }
            }
        )
    }

    testResultText?.let { msg ->
        AlertDialog(
            onDismissRequest = { testResultText = null },
            shape = RoundedCornerShape(AppRadius.DIALOG),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(if (testResultOk) "连接成功" else "连接失败") },
            text = {
                Text(
                    if (testResultOk) "模型服务器可用。" else msg,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = { testResultText = null }) { Text("关闭") }
            }
        )
    }

    Scaffold(
        topBar = {
            PocoPageTopBar(
                title = "快捷设置",
                subtitle = "模型、对话、图片、通知与语音",
                onBack = { flushAndBack() }
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
                // ============ 外观 ============
                if (isVisible("appearance")) {
                item { PocoGroupTitle("外观") }
                item {
                    PocoCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "主题",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ThemeOption("跟随系统", "system", themeMode, onThemeModeChanged)
                                ThemeOption("浅色", "light", themeMode, onThemeModeChanged)
                                ThemeOption("深色", "dark", themeMode, onThemeModeChanged)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
                }  // isVisible("appearance")

                // ============ 文件 ============
                if (isVisible("files")) {
                item { PocoGroupTitle("文件") }
                item {
                    PocoCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "下载目录",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "电脑推过来的文件、AI 下载的文件都落在这里。留空则用默认目录。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            var dirInput by remember { mutableStateOf(local.pcReceiveDir) }
                            LaunchedEffect(local.pcReceiveDir) {
                                if (dirInput != local.pcReceiveDir) dirInput = local.pcReceiveDir
                            }
                            OutlinedTextField(
                                value = dirInput,
                                onValueChange = { dirInput = it },
                                placeholder = { Text("/sdcard/FurAgent/downloads") },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(AppRadius.SM),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                )
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    val v = dirInput.trim().ifBlank { "/sdcard/FurAgent/downloads" }
                                    dirInput = v
                                    quickSave(local.copy(pcReceiveDir = v))
                                    notifyToast(context, "下载目录已保存")
                                }) { Text("应用") }
                                TextButton(onClick = {
                                    val v = "/sdcard/FurAgent/downloads"
                                    dirInput = v
                                    quickSave(local.copy(pcReceiveDir = v))
                                }) { Text("默认") }
                            }
                            Text(
                                "当前：${local.pcReceiveDir}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
                }  // isVisible("files")

                // ============ 模型 ============
                if (isVisible("model")) {
                item { PocoGroupTitle("模型") }
                items(local.profiles, key = { it.id }) { profile ->
                    ModelProfileCard(
                        profile = profile,
                        isCurrent = profile.kind != "image" && profile.id == local.currentProfileId,
                        expanded = expandedProfileId == profile.id,
                        testing = testingProfileId == profile.id,
                        onToggleExpand = {
                            expandedProfileId = if (expandedProfileId == profile.id) null else profile.id
                        },
                        onSetCurrent = {
                            if (profile.kind != "image") {
                                quickSave(local.copy(currentProfileId = profile.id))
                            }
                        },
                        onEdit = { editingProfile = profile },
                        onDelete = { pendingDeleteProfileId = profile.id },
                        onTest = {
                            testingProfileId = profile.id
                            scope.launch {
                                val err = testModelConnection(profile)
                                testingProfileId = null
                                testResultOk = err == null
                                testResultText = err ?: "OK"
                            }
                        }
                    )
                }
                item { AddModelButton(onClick = { showAddModel = true }) }
                item { AddModelButton(text = "添加文生图模型", onClick = { showAddImageModel = true }) }
                item { Spacer(Modifier.height(8.dp)) }

                // ============ 文生图模型选择 ============
                item {
                    PocoCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "文生图模型",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "选中后，AI 生图走该模型（只能生图，不参与对话）。不选则不启用生图。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            var imageMenuOpen by remember { mutableStateOf(false) }
                            val imageName = local.profiles
                                .firstOrNull { it.id == local.imageProfileId && it.kind == "image" }?.name
                                ?: "不启用"
                            Box {
                                OutlinedButton(
                                    onClick = { imageMenuOpen = true },
                                    shape = RoundedCornerShape(AppRadius.SM)
                                ) {
                                    Text(imageName)
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = null
                                    )
                                }
                                DropdownMenu(
                                    expanded = imageMenuOpen,
                                    onDismissRequest = { imageMenuOpen = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("不启用") },
                                        onClick = {
                                            imageMenuOpen = false
                                            quickSave(local.copy(imageProfileId = ""))
                                        }
                                    )
                                    local.profiles.filter { it.kind == "image" }.forEach { p ->
                                        DropdownMenuItem(
                                            text = { Text(p.name.ifBlank { "未命名" }) },
                                            onClick = {
                                                imageMenuOpen = false
                                                quickSave(local.copy(imageProfileId = p.id))
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))
                            Text(
                                "子代理模型",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "AI 派发子代理（run_sub_session）时用该模型。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            var subAgentMenuOpen by remember { mutableStateOf(false) }
                            val subAgentName = local.profiles
                                .firstOrNull { it.id == local.subAgentProfileId && it.kind != "image" }?.name
                                ?: "跟随主模型"
                            Box {
                                OutlinedButton(
                                    onClick = { subAgentMenuOpen = true },
                                    shape = RoundedCornerShape(AppRadius.SM)
                                ) {
                                    Text(subAgentName)
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = null
                                    )
                                }
                                DropdownMenu(
                                    expanded = subAgentMenuOpen,
                                    onDismissRequest = { subAgentMenuOpen = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("跟随主模型") },
                                        onClick = {
                                            subAgentMenuOpen = false
                                            quickSave(local.copy(subAgentProfileId = ""))
                                        }
                                    )
                                    local.profiles.filter { it.kind != "image" }.forEach { p ->
                                        DropdownMenuItem(
                                            text = { Text(p.name.ifBlank { "未命名" }) },
                                            onClick = {
                                                subAgentMenuOpen = false
                                                quickSave(local.copy(subAgentProfileId = p.id))
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
                }  // isVisible("model")

                // ============ 对话 ============
                if (isVisible("agent")) {
                item { PocoGroupTitle("对话") }
                item {
                    PocoCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            PocoTextField(
                                turnsInput,
                                { turnsInput = it.filter { c -> c.isDigit() }.take(10) },
                                "最多上下文轮数"
                            )
                        }
                    }
                }
                item {
                    PocoCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "最大工具调用轮数",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "数值越大 AI 能连续完成的步骤越多，但消耗的 Token 也越多。建议 20~50。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                var loopsInput by remember {
                                    mutableStateOf(local.maxToolCallLoops.toString())
                                }
                                LaunchedEffect(local.maxToolCallLoops) {
                                    if (loopsInput != local.maxToolCallLoops.toString()) {
                                        loopsInput = local.maxToolCallLoops.toString()
                                    }
                                }
                                OutlinedTextField(
                                    value = loopsInput,
                                    onValueChange = { s ->
                                        loopsInput = s.filter { it.isDigit() }.take(10)
                                        val v = loopsInput.toIntOrNull()
                                        if (v != null && v >= 1) {
                                            quickSave(local.copy(maxToolCallLoops = v))
                                        }
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(AppRadius.SM),
                                    modifier = Modifier.width(120.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                    )
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "当前：${local.maxToolCallLoops} 轮",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "上下文 token 上限（千）：超出后从最旧的工具过程开始省略，保留对话内容；0 = 不限制",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                var budgetInput by remember {
                                    mutableStateOf(local.contextTokenBudgetK.toString())
                                }
                                LaunchedEffect(local.contextTokenBudgetK) {
                                    if (budgetInput != local.contextTokenBudgetK.toString()) {
                                        budgetInput = local.contextTokenBudgetK.toString()
                                    }
                                }
                                OutlinedTextField(
                                    value = budgetInput,
                                    onValueChange = { s ->
                                        budgetInput = s.filter { it.isDigit() }.take(6)
                                        val v = budgetInput.toIntOrNull()
                                        if (v != null) {
                                            quickSave(local.copy(contextTokenBudgetK = v))
                                        }
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(AppRadius.SM),
                                    modifier = Modifier.width(120.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                    )
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "当前：${local.contextTokenBudgetK} 千 token",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
                }  // isVisible("agent")

                // ============ 安全确认（与桌面端对齐） ============
                if (isVisible("security")) {
                item {
                    PocoCard {
                        PocoSwitchRow(
                            title = "删除文件前确认",
                            subtitle = "AI 删除时弹窗",
                            checked = local.requireConfirmDelete
                        ) { quickSave(local.copy(requireConfirmDelete = it)) }
                        PocoDivider(startIndent = 16.dp)
                        PocoSwitchRow(
                            title = "写入文件前确认",
                            subtitle = "覆盖 / 追加时弹窗",
                            checked = local.requireConfirmWrite
                        ) { quickSave(local.copy(requireConfirmWrite = it)) }
                        PocoDivider(startIndent = 16.dp)
                        PocoSwitchRow(
                            title = "Shell 命令前确认",
                            subtitle = "执行系统命令时弹窗",
                            checked = local.requireConfirmShell
                        ) { quickSave(local.copy(requireConfirmShell = it)) }
                        PocoDivider(startIndent = 16.dp)
                        PocoSwitchRow(
                            title = "执行 Python 前确认",
                            subtitle = "运行代码 / 脚本 / 创建删除脚本前弹窗。关掉后 AI 可自动执行",
                            checked = local.requireConfirmPython
                        ) { quickSave(local.copy(requireConfirmPython = it)) }
                        PocoDivider(startIndent = 16.dp)
                        PocoSwitchRow(
                            title = "FFmpeg 覆盖前确认",
                            subtitle = "输出文件已存在时弹窗",
                            checked = local.requireConfirmFfmpegOverwrite
                        ) { quickSave(local.copy(requireConfirmFfmpegOverwrite = it)) }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
                }  // isVisible("security")

                // ============ 图片压缩 ============
                if (isVisible("image")) {
                item {
                    PocoCard {
                        PocoSwitchRow(
                            title = "发送前自动压缩",
                            subtitle = "缩小尺寸并降低 JPEG 质量，显著降低 Token 消耗",
                            checked = local.imageCompress
                        ) { quickSave(local.copy(imageCompress = it)) }
                        PocoDivider(startIndent = 16.dp)
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "压缩质量：${local.imageCompressQuality}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (local.imageCompress)
                                    MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(Modifier.height(4.dp))
                            GradientSlider(
                                value = local.imageCompressQuality.toFloat(),
                                onValueChange = { v ->
                                    quickSave(local.copy(imageCompressQuality = v.toInt()))
                                },
                                valueRange = 30f..100f,
                                enabled = local.imageCompress
                            )
                            Text(
                                "30 = 模糊省 token，100 = 高清但很贵",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(14.dp))
                            Text(
                                "最长边上限：${local.imageCompressMaxDim}px",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (local.imageCompress)
                                    MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(Modifier.height(4.dp))
                            GradientSlider(
                                value = local.imageCompressMaxDim.toFloat(),
                                onValueChange = { v ->
                                    val aligned = ((v / 64).toInt() * 64).coerceIn(320, 4096)
                                    quickSave(local.copy(imageCompressMaxDim = aligned))
                                },
                                valueRange = 320f..4096f,
                                enabled = local.imageCompress
                            )
                            Text(
                                "320 = 极省，1280 = 平衡，4096 = 原图",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }

                }  // isVisible("image")
                // ============ 通知 ============
                if (isVisible("notification")) {
                item { PocoGroupTitle("通知与语音") }
                item {
                    PocoCard {
                        Column(modifier = Modifier.padding(16.dp).animateContentSize()) {
                            Text(
                                "工具调用通知模式",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "关闭：不发工具通知。\n" +
                                        "普通：每次工具调用发一条独立通知。\n" +
                                        "实时通知：用一条持续通知实时显示当前工具。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NotifyModeOption("关闭", "off", local.notificationMode) {
                                    quickSave(local.copy(notificationMode = it))
                                }
                                NotifyModeOption("普通", "normal", local.notificationMode) {
                                    quickSave(local.copy(notificationMode = it))
                                }
                                NotifyModeOption("实时通知", "live", local.notificationMode) {
                                    quickSave(local.copy(notificationMode = it))
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "提示：Android 16 以下设备会自动降级为普通持续通知。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }

                // ============ 语音 ============
                item {
                    PocoCard {
                        PocoSwitchRow(
                            "自动朗读 AI 回复",
                            "AI 每次回复完成后用 TTS 朗读内容，切后台也能读",
                            local.autoSpeakReplies
                        ) { quickSave(local.copy(autoSpeakReplies = it)) }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
                }  // isVisible("notification")

                // ============ 新功能 ============
                // 「底部状态栏」「修改文件列表」已移除开关：功能强制常开；
                // 「AI 建议新对话」已废弃（由子代理 run_sub_session 取代）；
                // 「修改历史」入口已移到聊天页底栏。
                if (isVisible("new_features")) {
                item { PocoGroupTitle("新功能") }
                item {
                    PocoCard {
                        PocoSwitchRow(
                            "写前备份与回档",
                            "覆盖已有文件前自动留档，可从聊天页「修改历史」一键回档",
                            local.fileBackupEnabled
                        ) { quickSave(local.copy(fileBackupEnabled = it)) }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
                }  // isVisible("new_features")

                // ============ 关于我们 ============
                // 与桌面端 SettingsPage 的「关于我们」分组结构对齐：
                // 标题（有更新时点红点）→ 产品名 + 版本状态 → 分隔线 → 按钮行
                item {
                    Row(
                        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "关于我们",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.3.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                        )
                        if (hasUpdate) {
                            Spacer(Modifier.width(6.dp))
                            // 小红点：检测到新版本时提示
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(MaterialTheme.colorScheme.error, CircleShape)
                            )
                        }
                    }
                }
                item {
                    PocoCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "FurAgent v$appVersion",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (hasUpdate) "检测到新版本，点下方按钮查看" else "已经是最新版本",
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = if (hasUpdate) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(12.dp))
                            // 按钮行：有更新时主按钮直接下安装包
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (hasUpdate) {
                                    Button(
                                        onClick = onDownloadUpdate,
                                        shape = RoundedCornerShape(AppRadius.SM)
                                    ) {
                                        Text("下载更新")
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        openUrlSafely(
                                            context,
                                            UpdateChecker.QQ_GROUP_URL,
                                            "QQ 群"
                                        )
                                    },
                                    shape = RoundedCornerShape(AppRadius.SM)
                                ) {
                                    Text("QQ 群")
                                }
                                OutlinedButton(
                                    onClick = {
                                        openUrlSafely(
                                            context,
                                            if (hasUpdate) UpdateChecker.RELEASES_URL
                                            else UpdateChecker.PROJECT_URL,
                                            if (hasUpdate) "更新页面" else "GitHub"
                                        )
                                    },
                                    shape = RoundedCornerShape(AppRadius.SM)
                                ) {
                                    Text(if (hasUpdate) "更新页面" else "GitHub")
                                }
                                OutlinedButton(
                                    onClick = onCheckUpdate,
                                    shape = RoundedCornerShape(AppRadius.SM)
                                ) {
                                    Text("检查更新")
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }

                // ============ 高级（分组可见性，此分组自身不可隐藏） ============
                item { PocoGroupTitle("高级") }
                item {
                    PocoCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "勾选后，对应分组会在上方隐藏。此分组本身不可隐藏。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (local.hiddenSettings.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = {
                                        quickSave(local.copy(hiddenSettings = emptySet()))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(AppRadius.SM)
                                ) {
                                    Text("全部恢复显示")
                                }
                            }
                        }
                    }
                }
                HIDDEN_GROUPS.forEach { (key, label) ->
                    item(key = "hidden_group_$key") {
                        PocoCard {
                            PocoSwitchRow(
                                title = label,
                                subtitle = null,
                                checked = key in local.hiddenSettings
                            ) { checked ->
                                val newSet = if (checked) {
                                    local.hiddenSettings + key
                                } else {
                                    local.hiddenSettings - key
                                }
                                quickSave(local.copy(hiddenSettings = newSet))
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

/**
 * 安全打开外部链接。
 *
 * 设备上没有能处理该 scheme 的应用时 startActivity 会抛
 * ActivityNotFoundException，这里统一兜住，避免点按钮直接崩。
 */
private fun openUrlSafely(context: Context, url: String, label: String) {
    try {
        context.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                url.toUri()
            )
        )
    } catch (e: Exception) {
        AgentLogger.log(
            "ERR",
            "打开 $label 失败：${e.javaClass.simpleName}: ${e.message}"
        )
    }
}

// 主题选项

@Composable
private fun RowScope.ThemeOption(
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
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp
            )
        }
    }
}

// 通知模式选项

@Composable
private fun RowScope.NotifyModeOption(
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
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 12.sp,
                maxLines = 2
            )
        }
    }
}

// 添加模型按钮

@Composable
private fun AddModelButton(text: String = "添加自定义模型", onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
        label = "addBtnScale"
    )
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        shape = RoundedCornerShape(AppRadius.MD),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "+",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// 模型卡片

// UI 统一 用 PocoCard 承载，不再手写 GlassShadow + Surface。
// 选中态用 primary 描边 + primary 半透明底。

@Composable
private fun ModelProfileCard(
    profile: ModelProfile,
    isCurrent: Boolean,
    expanded: Boolean,
    testing: Boolean,
    onToggleExpand: () -> Unit,
    onSetCurrent: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit
) {
    // 批次 22 UI 收口 用 PocoCard(selectedBorder) 替代「外层 Box 描边 + 内层 PocoCard」双层结构。
    PocoCard(selectedBorder = isCurrent) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = 0.75f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggleExpand() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isCurrent,
                    onClick = onSetCurrent,
                    enabled = profile.kind != "image",
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary
                    )
                )
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            profile.name.ifBlank { "未命名" },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (profile.kind == "image") {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(AppRadius.TINY)
                            ) {
                                Text(
                                    "生图",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        when {
                            profile.apiKey.isBlank() -> "未配置 API Key"
                            isCurrent -> "当前使用中"
                            else -> "已配置"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (profile.apiKey.isBlank()) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
                    label = "profileArrow"
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer { rotationZ = rotation }
                )
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                InfoRow("API Key", maskKey(profile.apiKey))
                Spacer(Modifier.height(6.dp))
                InfoRow("Base URL", profile.baseUrl.ifBlank { "未设置" })
                Spacer(Modifier.height(6.dp))
                InfoRow("Model", profile.model.ifBlank { "未设置" })
                Spacer(Modifier.height(6.dp))
                InfoRow(
                    "关闭深度思考",
                    if (profile.supportsDisableThinking) "支持" else "不支持"
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onTest,
                        enabled = !testing,
                        shape = RoundedCornerShape(AppRadius.SM),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("测试中")
                        } else {
                            Text("测试连接")
                        }
                    }
                    OutlinedButton(
                        onClick = onEdit,
                        shape = RoundedCornerShape(AppRadius.SM),
                        modifier = Modifier.weight(1f)
                    ) { Text("编辑") }
                    OutlinedButton(
                        onClick = onDelete,
                        shape = RoundedCornerShape(AppRadius.SM),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("删除") }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun maskKey(key: String): String {
    if (key.isBlank()) return "未设置"
    if (key.length <= 8) return ".".repeat(key.length)
    return key.take(4) + ".".repeat((key.length - 8).coerceAtMost(20)) + key.takeLast(4)
}

// 修改历史 + 一键回档（已移到聊天页 MainActivity 底栏）
