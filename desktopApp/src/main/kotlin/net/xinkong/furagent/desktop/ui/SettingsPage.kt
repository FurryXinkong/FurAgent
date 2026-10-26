package net.xinkong.furagent.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.xinkong.furagent.desktop.data.*
import net.xinkong.furagent.desktop.system.EnvDetector
import net.xinkong.furagent.shared.tools.BackupHelper
import net.xinkong.furagent.desktop.system.DesktopVersion
import net.xinkong.furagent.shared.update.UpdateChecker
import net.xinkong.furagent.desktop.system.openExternalUrl
import net.xinkong.furagent.shared.tools.TrashHelper
import net.xinkong.furagent.desktop.system.TempCleaner
import net.xinkong.furagent.desktop.tools.DesktopTools
import net.xinkong.furagent.desktop.ui.theme.Radius
import net.xinkong.furagent.desktop.ui.theme.Spacing
import java.io.File
import net.xinkong.furagent.desktop.system.NativeFilePicker
import kotlin.time.Duration.Companion.milliseconds

val HIDDEN_GROUPS: List<Pair<String, String>> = listOf(
    "appearance" to "外观",
    "files" to "文件",
    "model" to "模型",
    "agent" to "智能体",
    "image" to "图片压缩",
    "notification" to "通知与语音",
    "security" to "安全确认",
    "env" to "环境检测",
    "tools" to "工具",
    "storage" to "文件存储位置",
    "workflow" to "工作流",
    "backup" to "备份与恢复",
    "service" to "服务"
)

@Composable
fun SettingsPage(
    settings: DesktopSettings,
    onSettingsChange: (DesktopSettings) -> Unit,
    onExportAll: () -> Unit,
    onImport: () -> Unit,
    /** 是否检测到新版本，用于在「关于我们」分组标题上点红点 */
    hasUpdate: Boolean = false,
    /** 手动检查更新（忽略缓存） */
    onCheckUpdate: () -> Unit = {},
    /** 一键下载更新包（检测到更新时可用） */
    onDownloadUpdate: () -> Unit = {}
) {
    var wfRefreshKey by remember { mutableIntStateOf(0) }
    var showWorkflowEditor by remember { mutableStateOf(false) }
    var editingWorkflow by remember { mutableStateOf<Workflow?>(null) }
    var editingProfile by remember { mutableStateOf<DesktopModelProfile?>(null) }
    var showAddProfile by remember { mutableStateOf(false) }
    var showAddImageProfile by remember { mutableStateOf(false) }
    var pendingDeleteProfileId by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }

    val hidden = settings.hiddenSettings
    fun isVisible(key: String) = key !in hidden

    fun toggleGroup(key: String) {
        val newCollapsed = if (key in settings.collapsedGroups) {
            settings.collapsedGroups - key
        } else {
            settings.collapsedGroups + key
        }
        onSettingsChange(settings.copy(collapsedGroups = newCollapsed))
    }

    fun isExpanded(key: String) = key !in settings.collapsedGroups

    Column(Modifier.fillMaxSize()) {
        PageTitleBar(title = "设置", subtitle = "模型、外观、安全、工具、环境检测、备份")

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 1400.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = Spacing.PAGE_H,
                        vertical = Spacing.PAGE_V,
                    )
                    .animateContentSize(
                        animationSpec = tween(260, easing = FastOutSlowInEasing)
                    ),
                verticalArrangement = Arrangement.spacedBy(Spacing.CARD_GAP)
            ) {
                // 外观
                if (isVisible("appearance")) {
                    SettingGroup(
                        title = "外观",
                        groupKey = "appearance",
                        expanded = isExpanded("appearance"),
                        onToggle = { toggleGroup("appearance") }
                    ) {
                        Text("主题", fontSize = 13.sp, color = TextSecondary())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeOption("跟随系统", "system", settings.themeMode) {
                                onSettingsChange(settings.copy(themeMode = it))
                            }
                            ThemeOption("浅色", "light", settings.themeMode) {
                                onSettingsChange(settings.copy(themeMode = it))
                            }
                            ThemeOption("深色", "dark", settings.themeMode) {
                                onSettingsChange(settings.copy(themeMode = it))
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        GlassDivider(color = BorderSubtle())
                        Spacer(Modifier.height(4.dp))

                        GlassSwitchRow(
                            title = "桌面悬浮窗",
                            subtitle = "永久置顶显示 AI 状态，可拖动、可吸附到屏幕边缘。" +
                                    "关闭后只能在本设置里重新打开。",
                            checked = settings.floatingWindowEnabled
                        ) { onSettingsChange(settings.copy(floatingWindowEnabled = it)) }
                    }
                }

                // 文件
                if (isVisible("files")) {
                    SettingGroup(
                        title = "文件",
                        groupKey = "files",
                        expanded = isExpanded("files"),
                        onToggle = { toggleGroup("files") }
                    ) {
                        SubSection("下载目录") {
                            HintText("手机推过来的文件、AI 下载的文件都落在这里。留空则用默认目录。")
                            var dirInput by remember { mutableStateOf(settings.receiveDir) }
                            LaunchedEffect(settings.receiveDir) {
                                if (dirInput != settings.receiveDir) dirInput = settings.receiveDir
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                GlassTextField(
                                    value = dirInput,
                                    onValueChange = { dirInput = it },
                                    label = "下载目录",
                                    placeholder = DesktopPaths.receivedDir().absolutePath,
                                    monospace = true,
                                    modifier = Modifier.weight(1f)
                                )
                                SecondaryButton(text = "应用") {
                                    val v = dirInput.trim().ifBlank { DesktopPaths.receivedDir().absolutePath }
                                    onSettingsChange(settings.copy(receiveDir = v))
                                }
                                SecondaryButton(text = "默认") {
                                    val v = DesktopPaths.receivedDir().absolutePath
                                    dirInput = v
                                    onSettingsChange(settings.copy(receiveDir = v))
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "当前：${settings.receiveDir}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextMuted()
                            )
                        }
                    }
                }

                // 模型
                if (isVisible("model")) {
                    SettingGroup(
                        title = "模型",
                        groupKey = "model",
                        expanded = isExpanded("model"),
                        onToggle = { toggleGroup("model") }
                    ) {
                        HintText("点击卡片切换当前对话模型。带「生图」标签的模型不参与对话，仅用于 AI 生图。")

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            settings.profiles.forEach { profile ->
                                key(profile.id) {
                                    ModelProfileCard(
                                        profile = profile,
                                        isCurrent = profile.kind != "image" &&
                                                profile.id == settings.currentProfileId,
                                        onSetCurrent = {
                                            if (profile.kind != "image") {
                                                onSettingsChange(settings.copy(currentProfileId = profile.id))
                                            }
                                        },
                                        onEdit = { editingProfile = profile },
                                        onDelete = { pendingDeleteProfileId = profile.id }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton(
                                text = "添加对话模型",
                                icon = Icons.Default.Add,
                                modifier = Modifier.weight(1f)
                            ) { showAddProfile = true }
                            SecondaryButton(
                                text = "添加文生图模型",
                                icon = Icons.Default.Add,
                                modifier = Modifier.weight(1f)
                            ) { showAddImageProfile = true }
                        }

                        Spacer(Modifier.height(8.dp))
                        GlassDivider(color = BorderSubtle())
                        Spacer(Modifier.height(8.dp))

                        ApiKeySourceSection(
                            settings = settings,
                            onSettingsChange = onSettingsChange
                        )
                    }
                }

                // 智能体
                if (isVisible("agent")) {
                    SettingGroup(
                        title = "智能体",
                        groupKey = "agent",
                        expanded = isExpanded("agent"),
                        onToggle = { toggleGroup("agent") }
                    ) {
                        SubSection("上下文") {
                            var turnsInput by remember { mutableStateOf(settings.maxTurns.toString()) }
                            LaunchedEffect(settings.maxTurns) {
                                if (turnsInput != settings.maxTurns.toString()) {
                                    turnsInput = settings.maxTurns.toString()
                                }
                            }
                            var loopsInput by remember { mutableStateOf(settings.maxToolCallLoops.toString()) }
                            LaunchedEffect(settings.maxToolCallLoops) {
                                if (loopsInput != settings.maxToolCallLoops.toString()) {
                                    loopsInput = settings.maxToolCallLoops.toString()
                                }
                            }

                            var budgetInput by remember {
                                mutableStateOf(settings.contextTokenBudgetK.toString())
                            }
                            LaunchedEffect(settings.contextTokenBudgetK) {
                                if (budgetInput != settings.contextTokenBudgetK.toString()) {
                                    budgetInput = settings.contextTokenBudgetK.toString()
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("最多上下文轮数", fontSize = 13.sp, color = TextPrimary())
                                    Text(
                                        "保留最近 N 轮对话发给模型，设大不设小",
                                        fontSize = 11.sp,
                                        color = TextMuted()
                                    )
                                }
                                OutlinedTextField(
                                    value = turnsInput,
                                    onValueChange = { s ->
                                        val filtered = s.filter { it.isDigit() }.take(10)
                                        turnsInput = filtered
                                        val v = filtered.toIntOrNull()
                                        if (v != null && v >= 1) {
                                            onSettingsChange(settings.copy(maxTurns = v))
                                        }
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(Radius.SM),
                                    modifier = Modifier.width(110.dp),
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        color = TextPrimary(),
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = CardColor(),
                                        unfocusedContainerColor = CardColor(),
                                        focusedBorderColor = AccentColor(),
                                        unfocusedBorderColor = BorderColor()
                                    )
                                )
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("上下文 token 上限（千）", fontSize = 13.sp, color = TextPrimary())
                                    Text(
                                        "超出后从最旧的工具过程开始省略，保留对话内容；0 = 不限制",
                                        fontSize = 11.sp,
                                        color = TextMuted()
                                    )
                                }
                                OutlinedTextField(
                                    value = budgetInput,
                                    onValueChange = { s ->
                                        val filtered = s.filter { it.isDigit() }.take(6)
                                        budgetInput = filtered
                                        val v = filtered.toIntOrNull()
                                        if (v != null) {
                                            onSettingsChange(settings.copy(contextTokenBudgetK = v))
                                        }
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(Radius.SM),
                                    modifier = Modifier.width(110.dp),
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        color = TextPrimary(),
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = CardColor(),
                                        unfocusedContainerColor = CardColor(),
                                        focusedBorderColor = AccentColor(),
                                        unfocusedBorderColor = BorderColor()
                                    )
                                )
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("最多工具调用轮数", fontSize = 13.sp, color = TextPrimary())
                                    Text(
                                        "数值越大能连续完成的步骤越多，建议 20~50",
                                        fontSize = 11.sp,
                                        color = TextMuted()
                                    )
                                }
                                OutlinedTextField(
                                    value = loopsInput,
                                    onValueChange = { s ->
                                        val filtered = s.filter { it.isDigit() }.take(10)
                                        loopsInput = filtered
                                        val v = filtered.toIntOrNull()
                                        if (v != null && v >= 1) {
                                            onSettingsChange(settings.copy(maxToolCallLoops = v))
                                        }
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(Radius.SM),
                                    modifier = Modifier.width(110.dp),
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        color = TextPrimary(),
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = CardColor(),
                                        unfocusedContainerColor = CardColor(),
                                        focusedBorderColor = AccentColor(),
                                        unfocusedBorderColor = BorderColor()
                                    )
                                )
                            }

                            // 会话摘要模型选择
                            Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("会话摘要模型", fontSize = 13.sp, color = TextPrimary())
                                    Text(
                                        "每天首次打开时，用该模型把历史会话压成一句标签存入记忆。不选则不启用。",
                                        fontSize = 11.sp,
                                        color = TextMuted()
                                    )
                                }
                                var summaryMenuOpen by remember { mutableStateOf(false) }
                                val summaryName = settings.profiles
                                    .firstOrNull { it.id == settings.summaryProfileId }?.name ?: "不启用"
                                Box {
                                    SecondaryButton(
                                        text = summaryName,
                                        icon = Icons.Default.ExpandMore,
                                        modifier = Modifier.width(160.dp)
                                    ) { summaryMenuOpen = true }
                                    DropdownMenu(
                                        expanded = summaryMenuOpen,
                                        onDismissRequest = { summaryMenuOpen = false },
                                        containerColor = CardColor()
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("不启用", fontSize = 12.sp, color = TextPrimary()) },
                                            onClick = {
                                                summaryMenuOpen = false
                                                onSettingsChange(settings.copy(summaryProfileId = ""))
                                            }
                                        )
                                        settings.profiles.forEach { p ->
                                            DropdownMenuItem(
                                                text = { Text(p.name, fontSize = 12.sp, color = TextPrimary()) },
                                                onClick = {
                                                    summaryMenuOpen = false
                                                    onSettingsChange(settings.copy(summaryProfileId = p.id))
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // 文生图模型选择（同会话摘要模型样式）
                            Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("文生图模型", fontSize = 13.sp, color = TextPrimary())
                                    Text(
                                        "选中后，AI 生图走该模型（只能生图，不参与对话）。不选则不启用生图。",
                                        fontSize = 11.sp,
                                        color = TextMuted()
                                    )
                                }
                                var imageMenuOpen by remember { mutableStateOf(false) }
                                val imageName = settings.profiles
                                    .firstOrNull { it.id == settings.imageProfileId && it.kind == "image" }?.name
                                    ?: "不启用"
                                Box {
                                    SecondaryButton(
                                        text = imageName,
                                        icon = Icons.Default.ExpandMore,
                                        modifier = Modifier.width(160.dp)
                                    ) { imageMenuOpen = true }
                                    DropdownMenu(
                                        expanded = imageMenuOpen,
                                        onDismissRequest = { imageMenuOpen = false },
                                        containerColor = CardColor()
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("不启用", fontSize = 12.sp, color = TextPrimary()) },
                                            onClick = {
                                                imageMenuOpen = false
                                                onSettingsChange(settings.copy(imageProfileId = ""))
                                            }
                                        )
                                        settings.imageProfiles().forEach { p ->
                                            DropdownMenuItem(
                                                text = { Text(p.name, fontSize = 12.sp, color = TextPrimary()) },
                                                onClick = {
                                                    imageMenuOpen = false
                                                    onSettingsChange(settings.copy(imageProfileId = p.id))
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        SubSection("系统提示词") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PromptModeOption("追加默认规则", "append", settings.systemPromptMode) {
                                    onSettingsChange(settings.copy(systemPromptMode = it))
                                }
                                PromptModeOption("完全替换", "replace", settings.systemPromptMode) {
                                    onSettingsChange(settings.copy(systemPromptMode = it))
                                }
                            }

                            if (settings.systemPromptMode == "replace") {
                                HintText("完全替代内置规则。可用 {TIME} 和 {OS} 占位符。留空则回退到内置规则。")
                                GlassTextField(
                                    settings.systemPromptFull,
                                    { onSettingsChange(settings.copy(systemPromptFull = it)) },
                                    placeholder = "例如：你是一个只做文件操作的助手…",
                                    singleLine = false, minLines = 8, maxLines = 16
                                )
                            } else {
                                HintText("以下内容会追加在内置规则之后。可用 {TIME} 和 {OS} 占位符。")
                                GlassTextField(
                                    settings.systemPromptAddon,
                                    { onSettingsChange(settings.copy(systemPromptAddon = it)) },
                                    placeholder = "例如：回答尽量简短",
                                    singleLine = false, minLines = 3, maxLines = 6
                                )
                            }
                        }
                    }
                }

                // 图片压缩
                if (isVisible("image")) {
                    SettingGroup(
                        title = "图片压缩",
                        groupKey = "image",
                        expanded = isExpanded("image"),
                        onToggle = { toggleGroup("image") }
                    ) {
                        GlassSwitchRow(
                            "发送前自动压缩",
                            "缩小尺寸并降低 JPEG 质量",
                            settings.imageCompress
                        ) {
                            onSettingsChange(settings.copy(imageCompress = it))
                        }
                        Spacer(Modifier.height(4.dp))
                        Column {
                            Text(
                                "压缩质量：${settings.imageCompressQuality}",
                                fontSize = 13.sp,
                                color = if (settings.imageCompress) TextPrimary() else TextMuted()
                            )
                            GradientSlider(
                                value = settings.imageCompressQuality.toFloat(),
                                onValueChange = { v ->
                                    onSettingsChange(settings.copy(imageCompressQuality = v.toInt()))
                                },
                                valueRange = 30f..100f,
                                enabled = settings.imageCompress
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "最长边上限：${settings.imageCompressMaxDim}px",
                                fontSize = 13.sp,
                                color = if (settings.imageCompress) TextPrimary() else TextMuted()
                            )
                            GradientSlider(
                                value = settings.imageCompressMaxDim.toFloat(),
                                onValueChange = { v ->
                                    onSettingsChange(
                                        settings.copy(
                                            imageCompressMaxDim = ((v / 64).toInt() * 64).coerceIn(320, 4096)
                                        )
                                    )
                                },
                                valueRange = 320f..4096f,
                                enabled = settings.imageCompress
                            )
                        }
                    }
                }

                // 通知与语音
                if (isVisible("notification")) {
                    SettingGroup(
                        title = "通知与语音",
                        groupKey = "notification",
                        expanded = isExpanded("notification"),
                        onToggle = { toggleGroup("notification") }
                    ) {
                        GlassSwitchRow(
                            "启用工具调用通知",
                            "AI 调用工具时弹一条系统通知",
                            settings.notificationsEnabled
                        ) {
                            onSettingsChange(settings.copy(notificationsEnabled = it))
                        }
                        Spacer(Modifier.height(4.dp))
                        GlassSwitchRow(
                            "自动朗读 AI 回复",
                            "AI 每次回复完成后朗读内容",
                            settings.autoSpeak
                        ) {
                            onSettingsChange(settings.copy(autoSpeak = it))
                        }
                    }
                }

                // 安全确认
                if (isVisible("security")) {
                    SettingGroup(
                        title = "安全确认",
                        groupKey = "security",
                        expanded = isExpanded("security"),
                        onToggle = { toggleGroup("security") }
                    ) {
                        HintText("关闭后 AI 执行对应操作时不再弹窗，直接执行。")
                        GlassSwitchRow("删除文件前确认", "AI 删除时弹窗", settings.requireConfirmDelete) {
                            onSettingsChange(settings.copy(requireConfirmDelete = it))
                        }
                        GlassSwitchRow("写入文件前确认", "覆盖 / 追加时弹窗", settings.requireConfirmWrite) {
                            onSettingsChange(settings.copy(requireConfirmWrite = it))
                        }
                        GlassSwitchRow("Shell 命令前确认", "执行系统命令时弹窗", settings.requireConfirmShell) {
                            onSettingsChange(settings.copy(requireConfirmShell = it))
                        }
                        GlassSwitchRow(
                            "运行自定义脚本前确认",
                            "AI 调用 skill(op=run) 执行脚本前弹窗",
                            settings.requireConfirmSkill
                        ) {
                            onSettingsChange(settings.copy(requireConfirmSkill = it))
                        }
                        GlassSwitchRow(
                            "执行 Python 前确认",
                            "运行 Python 代码、.py 文件、pip 安装前弹窗",
                            settings.requireConfirmPython
                        ) {
                            onSettingsChange(settings.copy(requireConfirmPython = it))
                        }
                        GlassSwitchRow(
                            "FFmpeg 覆盖前确认",
                            "输出文件已存在时弹窗询问是否覆盖",
                            settings.requireConfirmFfmpegOverwrite
                        ) {
                            onSettingsChange(settings.copy(requireConfirmFfmpegOverwrite = it))
                        }

                        // —— 新功能开关 ——
                        // 「底部状态栏」「修改文件列表」已移除开关：功能强制常开；
                        // 「AI 建议新对话」已废弃（由子代理 run_sub_session 取代）；
                        // 「修改历史」入口已移到聊天页底栏。
                        GlassSwitchRow(
                            "写前备份与回档",
                            "覆盖已有文件前自动留档，可从聊天页「修改历史」一键回档",
                            settings.fileBackupEnabled
                        ) {
                            onSettingsChange(settings.copy(fileBackupEnabled = it))
                        }
                    }
                }

                // 环境检测
                if (isVisible("env")) {
                    SettingGroup(
                        title = "环境检测",
                        groupKey = "env",
                        expanded = isExpanded("env"),
                        onToggle = { toggleGroup("env") }
                    ) {
                        EnvDetectSection(
                            settings = settings,
                            onSettingsChange = onSettingsChange,
                            onInfo = { infoMsg = it }
                        )
                    }
                }

                // 工具
                if (isVisible("tools")) {
                    SettingGroup(
                        title = "工具",
                        groupKey = "tools",
                        expanded = isExpanded("tools"),
                        onToggle = { toggleGroup("tools") }
                    ) {
                        HintText("关闭不需要的工具可以减少模型误调用，并节省 token 消耗。")

                        Column {
                            val toolList = DesktopTools.getToolList()
                            toolList.forEachIndexed { i, t ->
                                ToolRow(
                                    displayName = t.displayName,
                                    toolName = t.name,
                                    description = t.description,
                                    enabled = t.name !in settings.disabledTools
                                ) { en ->
                                    val newSet = if (en) settings.disabledTools - t.name
                                    else settings.disabledTools + t.name
                                    onSettingsChange(settings.copy(disabledTools = newSet))
                                }
                                if (i != toolList.lastIndex) GlassDivider(color = BorderSubtle())
                            }
                        }
                    }
                }

                // 文件存储位置
                if (isVisible("storage")) {
                    SettingGroup(
                        title = "文件存储位置",
                        groupKey = "storage",
                        expanded = isExpanded("storage"),
                        onToggle = { toggleGroup("storage") }
                    ) {
                        HintText("以下目录都由 FurAgent 自动管理，AI 会把对应文件放到这些位置。")
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            StoragePathRow("配置文件", DesktopPaths.configDir().absolutePath)
                            GlassDivider(color = BorderSubtle())
                            StoragePathRow("会话数据", DesktopPaths.sessionDir().absolutePath)
                            GlassDivider(color = BorderSubtle())
                            StoragePathRow("自定义脚本", DesktopPaths.scriptsDir().absolutePath)
                            GlassDivider(color = BorderSubtle())
                            StoragePathRow("截图", DesktopPaths.screenshotsDir().absolutePath)
                            GlassDivider(color = BorderSubtle())
                            StoragePathRow("接收文件", DesktopPaths.receivedDir().absolutePath)
                            GlassDivider(color = BorderSubtle())
                            StoragePathRow("会话导出 / 备份", DesktopPaths.exportsDir().absolutePath)
                            GlassDivider(color = BorderSubtle())
                            StoragePathRow("导入图片", DesktopPaths.importedImagesDir().absolutePath)
                            GlassDivider(color = BorderSubtle())
                            StoragePathRow("临时文件", DesktopPaths.tempDir().absolutePath)
                            GlassDivider(color = BorderSubtle())
                            StoragePathRow("日志", DesktopPaths.logsDir().absolutePath)
                        }

                        Spacer(Modifier.height(4.dp))
                        GlassDivider(color = BorderSubtle())
                        Spacer(Modifier.height(4.dp))

                        TempCleanupSection(
                            settings = settings,
                            onSettingsChange = onSettingsChange,
                            onInfo = { infoMsg = it }
                        )

                        Spacer(Modifier.height(4.dp))
                        GlassDivider(color = BorderSubtle())
                        Spacer(Modifier.height(4.dp))

                        SecondaryButton(
                            text = "打开 .furagent 根目录",
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            try {
                                val dir = DesktopPaths.rootDir()
                                val os = System.getProperty("os.name").lowercase()
                                val cmd = when {
                                    os.contains("win") -> arrayOf("explorer", dir.absolutePath)
                                    os.contains("mac") -> arrayOf("open", dir.absolutePath)
                                    else -> arrayOf("xdg-open", dir.absolutePath)
                                }
                                ProcessBuilder(*cmd).start()
                            } catch (_: Exception) { }
                        }
                    }
                }

                // 工作流
                if (isVisible("workflow")) {
                    SettingGroup(
                        title = "工作流",
                        groupKey = "workflow",
                        expanded = isExpanded("workflow"),
                        onToggle = { toggleGroup("workflow") }
                    ) {
                        HintText("工作流把多个工具调用串成命名流程，AI 可以通过 workflow 工具执行。")
                        val workflows = remember(wfRefreshKey) { WorkflowStore.list() }
                        if (workflows.isEmpty()) {
                            Text("暂无工作流", fontSize = 12.sp, color = TextMuted())
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                workflows.forEach { w ->
                                    key(w.id) {
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(Radius.SM))
                                                .background(CardStrongColor())
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    w.name,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = TextPrimary()
                                                )
                                                if (w.description.isNotBlank())
                                                    Text(
                                                        w.description,
                                                        fontSize = 11.sp,
                                                        color = TextSecondary()
                                                    )
                                                Text(
                                                    "${w.steps.size} 步 · 参数：${
                                                        w.params.joinToString(", ").ifBlank { "无" }
                                                    }",
                                                    fontSize = 11.sp,
                                                    color = TextMuted()
                                                )
                                            }
                                            SecondaryButton(text = "编辑") {
                                                editingWorkflow = w; showWorkflowEditor = true
                                            }
                                            Spacer(Modifier.width(6.dp))
                                            DangerButton(text = "删除") {
                                                WorkflowStore.delete(w.id); wfRefreshKey++
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        SecondaryButton(
                            text = "添加工作流",
                            modifier = Modifier.fillMaxWidth()
                        ) { editingWorkflow = null; showWorkflowEditor = true }
                    }
                }

                // 备份与恢复
                if (isVisible("backup")) {
                    SettingGroup(
                        title = "备份与恢复",
                        groupKey = "backup",
                        expanded = isExpanded("backup"),
                        onToggle = { toggleGroup("backup") }
                    ) {
                        HintText("导出位置：~/.furagent/exports/（API Key 混淆后导出）")
                        PrimaryButton(text = "导出全部会话与设置", modifier = Modifier.fillMaxWidth()) { onExportAll() }
                        Spacer(Modifier.height(6.dp))
                        SecondaryButton(text = "导入备份（JSON）", modifier = Modifier.fillMaxWidth()) { onImport() }
                    }
                }

                // 服务
                if (isVisible("service")) {
                    SettingGroup(
                        title = "服务",
                        groupKey = "service",
                        expanded = isExpanded("service"),
                        onToggle = { toggleGroup("service") }
                    ) {
                        GlassTextField(
                            settings.port.toString(),
                            { s ->
                                val p = s.toIntOrNull()
                                if (p != null && p in 1..65535) onSettingsChange(settings.copy(port = p))
                            },
                            label = "监听端口", placeholder = "8425"
                        )
                        HintText("修改端口后需要重启应用生效")

                        GlassDivider(color = BorderSubtle())

                        var autostartEnabled by remember { mutableStateOf(isAutoStartEnabled()) }
                        val autostartSupported = isAutoStartSupported()

                        GlassSwitchRow(
                            title = "开机自启动",
                            subtitle = when {
                                !autostartSupported -> "当前系统暂不支持（仅 Windows）"
                                autostartEnabled -> "已启用 · 登录系统后自动运行 FurAgent"
                                else -> "关闭后需要手动启动"
                            },
                            checked = autostartEnabled,
                            enabled = autostartSupported
                        ) { want ->
                            val (ok, msg) = setAutoStartEnabled(want)
                            if (ok) {
                                autostartEnabled = isAutoStartEnabled()
                                onSettingsChange(settings.copy(launchOnBoot = autostartEnabled))
                                infoMsg = if (autostartEnabled) "已启用开机自启动" else "已禁用开机自启动"
                            } else {
                                infoMsg = msg
                                autostartEnabled = isAutoStartEnabled()
                            }
                        }

                        GlassDivider(color = BorderSubtle())

                        // 关闭行为 关闭主窗口时是真退出，还是缩到托盘继续跑。
                        // 放在「服务」组：它和开机自启动一样，都属于
                        // 「这个程序在后台怎么活着」。
                        GlassSwitchRow(
                            title = "关闭时最小化到托盘",
                            subtitle = if (settings.closeToTray) {
                                "点×只隐藏窗口，后台任务继续跑；托盘右键可真正退出"
                            } else {
                                "点×直接退出，正在跑的任务会被中断"
                            },
                            checked = settings.closeToTray
                        ) { want ->
                            // 开关本身就是结果，不再弹提示框
                            onSettingsChange(settings.copy(closeToTray = want))
                        }
                    }
                }

                // 关于我们
                SettingGroup(
                    title = "关于我们",
                    groupKey = "about",
                    expanded = isExpanded("about"),
                    onToggle = { toggleGroup("about") },
                    badge = hasUpdate
                ) {
                    Text(
                        "FurAgent v${DesktopVersion.current()}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (hasUpdate) "检测到新版本，点下方按钮查看" else "已经是最新版本",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = if (hasUpdate) AccentColor() else TextSecondary()
                    )

                    Spacer(Modifier.height(12.dp))
                    GlassDivider(color = BorderSubtle())
                    Spacer(Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 检测到更新时，主按钮直接下安装包；否则是普通项目页入口
                        if (hasUpdate) {
                            PrimaryButton(
                                text = "下载更新",
                                onClick = onDownloadUpdate
                            )
                        }
                        SecondaryButton(
                            text = "QQ 群",
                            onClick = { openExternalUrl(UpdateChecker.QQ_GROUP_URL) }
                        )
                        SecondaryButton(
                            text = if (hasUpdate) "更新页面" else "GitHub",
                            onClick = {
                                openExternalUrl(
                                    if (hasUpdate) UpdateChecker.RELEASES_URL
                                    else UpdateChecker.PROJECT_URL
                                )
                            }
                        )
                        SecondaryButton(
                            text = "检查更新",
                            onClick = onCheckUpdate
                        )
                    }
                }

                // 高级
                SettingGroup(
                    title = "高级",
                    groupKey = "advanced",
                    expanded = isExpanded("advanced"),
                    onToggle = { toggleGroup("advanced") }
                ) {
                    HintText("勾选后，对应设置分组会在上方隐藏。此分组本身不可隐藏。")
                    // 体验优化 一键恢复所有隐藏分组
                    if (settings.hiddenSettings.isNotEmpty()) {
                        SecondaryButton(
                            text = "全部恢复显示",
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            onSettingsChange(settings.copy(hiddenSettings = emptySet()))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        HIDDEN_GROUPS.forEachIndexed { i, (key, label) ->
                            GlassSwitchRow(
                                title = label,
                                subtitle = null,
                                checked = key in settings.hiddenSettings
                            ) { checked ->
                                val newSet = if (checked) settings.hiddenSettings + key
                                else settings.hiddenSettings - key
                                onSettingsChange(settings.copy(hiddenSettings = newSet))
                            }
                            if (i != HIDDEN_GROUPS.lastIndex) GlassDivider(color = BorderSubtle())
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }

    // 弹窗
    editingProfile?.let { profile ->
        EditModelProfileDialog(
            title = "编辑模型",
            initial = profile,
            onDismiss = { editingProfile = null },
            onConfirm = { updated ->
                onSettingsChange(
                    settings.copy(
                        profiles = settings.profiles.map { if (it.id == updated.id) updated else it }
                    )
                )
                editingProfile = null
            }
        )
    }

    if (showAddProfile) {
        EditModelProfileDialog(
            title = "添加对话模型",
            initial = DesktopModelProfile(
                id = "custom_${System.currentTimeMillis()}",
                name = "", baseUrl = "", model = "", kind = "chat"
            ),
            onDismiss = { showAddProfile = false },
            onConfirm = { newProfile ->
                onSettingsChange(
                    settings.copy(
                        profiles = settings.profiles + newProfile,
                        currentProfileId = newProfile.id
                    )
                )
                showAddProfile = false
            }
        )
    }

    if (showAddImageProfile) {
        EditModelProfileDialog(
            title = "添加文生图模型",
            initial = DesktopModelProfile(
                id = "image_${System.currentTimeMillis()}",
                name = "", baseUrl = "", model = "", kind = "image"
            ),
            onDismiss = { showAddImageProfile = false },
            onConfirm = { newProfile ->
                onSettingsChange(
                    settings.copy(
                        profiles = settings.profiles + newProfile,
                        imageProfileId = newProfile.id
                    )
                )
                showAddImageProfile = false
            }
        )
    }

    pendingDeleteProfileId?.let { id ->
        val p = settings.profiles.firstOrNull { it.id == id }
        val canDelete = settings.profiles.size > 1
        ConfirmDialog(
            title = "删除模型",
            message = if (!canDelete) "至少保留一个模型"
            else "确定删除「${p?.name ?: ""}」？",
            confirmText = "删除", danger = true,
            onConfirm = {
                if (canDelete) {
                    val newProfiles = settings.profiles.filter { it.id != id }
                    val newCurrent = if (settings.currentProfileId == id) newProfiles.first().id
                    else settings.currentProfileId
                    onSettingsChange(
                        settings.copy(profiles = newProfiles, currentProfileId = newCurrent)
                    )
                }
                pendingDeleteProfileId = null
            },
            onDismiss = { pendingDeleteProfileId = null }
        )
    }

    if (showWorkflowEditor) {
        WorkflowEditorDialog(
            initial = editingWorkflow,
            onDismiss = { showWorkflowEditor = false; editingWorkflow = null },
            onSave = { wf ->
                WorkflowStore.save(wf); wfRefreshKey++
                showWorkflowEditor = false; editingWorkflow = null
            }
        )
    }

    infoMsg?.let { msg -> InfoDialog(title = "提示", message = msg, onDismiss = { infoMsg = null }) }
}

// 分组组件（唯一一层折叠）

// 内部卡片从"手写 Column + background + border"
// 改为统一用 GlassCard 承载，圆角/底色/边框/阴影自动和全站一致。

@Composable
private fun SettingGroup(
    title: String,
    groupKey: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    /** true 时在标题右侧点一个小红点（如「关于我们」检测到新版本） */
    badge: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.SM))
                .clickable(onClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary(),
                modifier = Modifier.weight(1f)
            )
            if (badge) {
                Box(
                    Modifier
                        .padding(end = 8.dp)
                        .size(7.dp)
                        .background(AccentColor(), CircleShape)
                )
            }
            val rotation by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                animationSpec = tween(200, easing = FastOutSlowInEasing),
                label = "groupArrow"
            )
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = if (expanded) "折叠" else "展开",
                tint = TextSecondary(),
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = rotation }
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(180)) +
                    expandVertically(
                        animationSpec = tween(240, easing = FastOutSlowInEasing),
                        expandFrom = Alignment.Top
                    ),
            exit = fadeOut(tween(140)) +
                    shrinkVertically(
                        animationSpec = tween(200, easing = FastOutSlowInEasing),
                        shrinkTowards = Alignment.Top
                    )
        ) {
            GlassCard(padding = PaddingValues(Spacing.CARD_PADDING)) {
                content()
            }
        }
    }
}

@Composable
private fun SubSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(12.dp).background(AccentColor()))
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 12.sp, color = TextSecondary(), fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

// 工具行

@Composable
private fun ToolRow(
    displayName: String,
    toolName: String,
    description: String,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                displayName,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) TextPrimary() else TextMuted()
            )
            Spacer(Modifier.height(2.dp))
            Text(
                toolName,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = TextMuted(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = TextSecondary(),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = enabled,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = OnAccent(),
                checkedTrackColor = AccentColor(),
                uncheckedThumbColor = TextSecondary(),
                uncheckedTrackColor = CardStrongColor(),
                uncheckedBorderColor = BorderColor()
            )
        )
    }
}


// 环境检测 → 已拆到 EnvDetectSection.kt

// API Key 来源

@Composable
private fun ApiKeySourceSection(
    settings: DesktopSettings,
    onSettingsChange: (DesktopSettings) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "API Key 来源",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary()
        )
        Text(
            "使用环境变量可以在多人共用一台电脑时避免把 Key 写进配置文件。",
            fontSize = 11.sp, color = TextSecondary()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ApiKeySourceOption(
                "使用模型配置", "profile", settings.apiKeySource,
                Modifier.weight(1f)
            ) { onSettingsChange(settings.copy(apiKeySource = it)) }
            ApiKeySourceOption(
                "使用环境变量", "env", settings.apiKeySource,
                Modifier.weight(1f)
            ) { onSettingsChange(settings.copy(apiKeySource = it)) }
        }

        if (settings.apiKeySource == "env") {
            Spacer(Modifier.height(4.dp))
            EnvStatusBlock()
        }
    }
}

@Composable
private fun ApiKeySourceOption(
    label: String,
    value: String,
    current: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit
) {
    val selected = current == value
    val bg by animateColorAsState(
        if (selected) AccentColor() else CardStrongColor(),
        tween(140), label = "apiKeySourceBg"
    )
    Box(
        modifier
            .clip(RoundedCornerShape(Radius.SM))
            .background(bg)
            .clickable { onChange(value) }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = if (selected) OnAccent() else TextPrimary(),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun EnvStatusBlock() {
    var refreshKey by remember { mutableIntStateOf(0) }

    val envKey = remember(refreshKey) {
        try { System.getenv(ApiKeyResolver.ENV_NAME) } catch (_: Throwable) { null }
    }
    val configKey = remember(refreshKey) {
        try {
            val f = ApiKeyResolver.configFile()
            if (f.exists()) {
                f.readText(Charsets.UTF_8).lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            } else null
        } catch (_: Exception) { null }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!envKey.isNullOrBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(ResultColor()))
                Spacer(Modifier.width(8.dp))
                Text(
                    "已从环境变量 ${ApiKeyResolver.ENV_NAME} 加载（长度 ${envKey.trim().length}）",
                    fontSize = 12.sp, color = ResultColor()
                )
            }
        } else if (!configKey.isNullOrBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(ResultColor()))
                Spacer(Modifier.width(8.dp))
                Text(
                    "已从配置文件加载（长度 ${configKey.length}）",
                    fontSize = 12.sp, color = ResultColor()
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(ErrorColor()))
                Spacer(Modifier.width(8.dp))
                Text(
                    "未识别到环境变量或配置文件",
                    fontSize = 12.sp, color = ErrorColor()
                )
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.SM))
                    .background(BgColor())
                    .padding(12.dp)
            ) {
                Text(
                    ApiKeyResolver.guideText(),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary(),
                    lineHeight = 16.sp
                )
            }
        }

        TextButton(
            onClick = { refreshKey++ },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
        ) {
            Text("重新检测", fontSize = 11.sp, color = AccentColor())
        }
    }
}

// 存储路径行

@Composable
private fun StoragePathRow(label: String, path: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(140.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary()
            )
        }
        Text(
            path,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = TextSecondary(),
            modifier = Modifier.weight(1f)
        )
        IconActionButton(
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            onClick = {
                try {
                    val dir = File(path)
                    if (!dir.exists()) dir.mkdirs()
                    val os = System.getProperty("os.name").lowercase()
                    val cmd = when {
                        os.contains("win") -> arrayOf("explorer", dir.absolutePath)
                        os.contains("mac") -> arrayOf("open", dir.absolutePath)
                        else -> arrayOf("xdg-open", dir.absolutePath)
                    }
                    ProcessBuilder(*cmd).start()
                } catch (_: Exception) { }
            },
            tint = TextSecondary(),
            size = 28.dp
        )
    }
}


// 模型卡片 → 已拆到 ModelProfileCard.kt

@Composable
internal fun PresetChip(
    preset: ModelPreset,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg = if (selected) AccentSoft() else CardStrongColor()
    val borderColor = if (selected) AccentColor() else BorderColor()
    Column(
        modifier.clip(RoundedCornerShape(Radius.SM)).background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(Radius.SM))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            preset.name, fontSize = 11.sp,
            color = if (selected) AccentColor() else TextPrimary(),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
        Text(preset.hint, fontSize = 9.sp, color = TextMuted(), maxLines = 1)
    }
}

// Theme / Prompt 选项

@Composable
private fun RowScope.ThemeOption(
    label: String, value: String, current: String, onChange: (String) -> Unit
) {
    val selected = current == value
    val bg by animateColorAsState(
        if (selected) AccentColor() else CardStrongColor(),
        tween(140), label = "themeOptBg"
    )
    Box(
        Modifier.weight(1f).clip(RoundedCornerShape(Radius.SM))
            .background(bg)
            .clickable { onChange(value) }.padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, fontSize = 12.sp,
            color = if (selected) OnAccent() else TextPrimary(),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun RowScope.PromptModeOption(
    label: String, value: String, current: String, onChange: (String) -> Unit
) {
    val selected = current == value
    val bg by animateColorAsState(
        if (selected) AccentColor() else CardStrongColor(),
        tween(140), label = "promptModeBg"
    )
    Box(
        Modifier.weight(1f).clip(RoundedCornerShape(Radius.SM))
            .background(bg)
            .clickable { onChange(value) }.padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, fontSize = 12.sp,
            color = if (selected) OnAccent() else TextPrimary(),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// 临时文件清理

@Composable
private fun TempCleanupSection(
    settings: DesktopSettings,
    onSettingsChange: (DesktopSettings) -> Unit,
    onInfo: (String) -> Unit
) {
    var usageKey by remember { mutableIntStateOf(0) }
    var cleaning by remember { mutableStateOf(false) }

    val usage = remember(usageKey) { TempCleaner.generatedUsage() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "临时文件清理",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary()
        )
        Text(
            "只清理程序生成的临时文件（run_*.py / concat_*.txt / ffmpeg_* 等），" +
                    "你自己放进 temp 的文件不会被删。",
            fontSize = 11.sp, color = TextSecondary(), lineHeight = 16.sp
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "当前占用：${usage.first} 个文件 · ${TempCleaner.formatSize(usage.second)}",
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                color = TextMuted(), modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { usageKey++ },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text("刷新", fontSize = 11.sp, color = AccentColor())
            }
        }

        Text("自动清理周期", fontSize = 12.sp, color = TextSecondary())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TempCleanupOption("1 天", 1, settings.tempCleanupDays, Modifier.weight(1f)) {
                onSettingsChange(settings.copy(tempCleanupDays = it))
            }
            TempCleanupOption("3 天", 3, settings.tempCleanupDays, Modifier.weight(1f)) {
                onSettingsChange(settings.copy(tempCleanupDays = it))
            }
            TempCleanupOption("7 天", 7, settings.tempCleanupDays, Modifier.weight(1f)) {
                onSettingsChange(settings.copy(tempCleanupDays = it))
            }
            TempCleanupOption("14 天", 14, settings.tempCleanupDays, Modifier.weight(1f)) {
                onSettingsChange(settings.copy(tempCleanupDays = it))
            }
            TempCleanupOption("永久", 0, settings.tempCleanupDays, Modifier.weight(1f)) {
                onSettingsChange(settings.copy(tempCleanupDays = it))
            }
        }
        Text(
            if (settings.tempCleanupDays <= 0) "永久不清理，临时文件会一直保留。"
            else "启动时自动删除超过 ${settings.tempCleanupDays} 天未修改的临时文件。",
            fontSize = 11.sp, color = TextMuted()
        )

        SecondaryButton(
            text = if (cleaning) "清理中…" else "立即清理",
            enabled = !cleaning,
            modifier = Modifier.fillMaxWidth()
        ) {
            cleaning = true
            val days = settings.tempCleanupDays
            if (days <= 0) {
                onInfo("当前设置为「永久不清理」。请先选择一个清理周期，或直接手动清理全部程序生成的临时文件。")
                // 永久模式下「立即清理」按 0 天处理 = 删除全部程序生成的临时文件
                val r = TempCleaner.cleanup(1)
                onInfo(r.summary())
            } else {
                val r = TempCleaner.cleanup(days)
                onInfo(r.summary())
            }
            usageKey++
            cleaning = false
        }
    }
}

@Composable
private fun RowScope.TempCleanupOption(
    label: String,
    value: Int,
    current: Int,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit
) {
    val selected = current == value
    val bg by animateColorAsState(
        if (selected) AccentColor() else CardStrongColor(),
        tween(140), label = "tempCleanupBg"
    )
    Box(
        modifier.clip(RoundedCornerShape(Radius.SM))
            .background(bg)
            .clickable { onChange(value) }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, fontSize = 12.sp,
            color = if (selected) OnAccent() else TextPrimary(),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// 修改历史 + 一键回档（已移到聊天页 ChatPage 底栏）
