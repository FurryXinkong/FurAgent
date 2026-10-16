@file:OptIn(ExperimentalMaterial3Api::class)

package net.xinkong.furagent.ui.pages

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.xinkong.furagent.core.testModelConnection
import net.xinkong.furagent.data.AppSettings
import net.xinkong.furagent.data.ModelProfile
import net.xinkong.furagent.data.SettingsStore
import net.xinkong.furagent.system.notifyToast
import net.xinkong.furagent.ui.ApplyBackgroundBlur
import net.xinkong.furagent.ui.EditModelDialog
import net.xinkong.furagent.ui.GlassSheen
import net.xinkong.furagent.ui.PocoCard
import net.xinkong.furagent.ui.PocoDivider
import net.xinkong.furagent.ui.PocoGroupTitle
import net.xinkong.furagent.ui.PocoPageTopBar
import net.xinkong.furagent.ui.PocoSwitchRow
import net.xinkong.furagent.ui.PocoTextField
import net.xinkong.furagent.ui.glassPageBg
import java.util.*

/**
 * 快捷设置 —— 整屏页面。
 *
 * 【UI 统一约定】
 *   - 卡片统一 PocoCard
 *   - 页背景 glassPageBg() + 顶部 GlassSheen
 *   - 主色 / 危险色走 MaterialTheme.colorScheme
 */
@Composable
fun SettingsPage(
    settings: AppSettings,
    maxTurns: Int,
    themeMode: String,
    onThemeModeChanged: (String) -> Unit,
    onSettingsChanged: (Int, AppSettings) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("FurAgentPrefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var turnsInput by remember { mutableStateOf(maxTurns.toString()) }
    var local by remember { mutableStateOf(settings) }
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
            shape = RoundedCornerShape(28.dp),
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
            shape = RoundedCornerShape(28.dp),
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

                // ============ 模型 ============
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
                item { PocoGroupTitle("文生图") }
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
                                    shape = RoundedCornerShape(12.dp)
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
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }

                // ============ 对话 ============
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
                                    shape = RoundedCornerShape(12.dp),
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
                        }
                    }
                }
                item {
                    val currentProf = local.profiles.firstOrNull { it.id == local.currentProfileId }
                    val canDisable = currentProf?.supportsDisableThinking ?: true
                    val isOn = local.disableThinking && canDisable
                    val subtitle = if (canDisable) {
                        "直接回答，节省 token"
                    } else {
                        "当前模型（${currentProf.name}）不支持关闭"
                    }
                    PocoCard {
                        PocoSwitchRow(
                            title = "关闭深度思考",
                            subtitle = subtitle,
                            checked = isOn,
                            enabled = canDisable
                        ) { enabled ->
                            if (canDisable) quickSave(local.copy(disableThinking = enabled))
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }

                // ============ 图片压缩 ============
                item { PocoGroupTitle("图片压缩") }
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
                            Slider(
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
                            Slider(
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

                // ============ 通知 ============
                item { PocoGroupTitle("通知") }
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
                item { PocoGroupTitle("语音") }
                item {
                    PocoCard {
                        PocoSwitchRow(
                            "自动朗读 AI 回复",
                            "AI 每次回复完成后用 TTS 朗读内容，切后台也能读",
                            local.autoSpeakReplies
                        ) { quickSave(local.copy(autoSpeakReplies = it)) }
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

// ============================================================
// 主题选项
// ============================================================

@Composable
private fun RowScope.ThemeOption(
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

// ============================================================
// 通知模式选项
// ============================================================

@Composable
private fun RowScope.NotifyModeOption(
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

// ============================================================
// 添加模型按钮
// ============================================================

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
        shape = RoundedCornerShape(16.dp),
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

// ============================================================
// 模型卡片
// ============================================================
//
// 【UI 统一】用 PocoCard 承载，不再手写 GlassShadow + Surface。
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
    // 【批次 22 UI 收口】用 PocoCard(selectedBorder) 替代「外层 Box 描边 + 内层 PocoCard」双层结构。
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
                                shape = RoundedCornerShape(6.dp)
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
                        shape = RoundedCornerShape(12.dp),
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
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("编辑") }
                    OutlinedButton(
                        onClick = onDelete,
                        shape = RoundedCornerShape(12.dp),
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