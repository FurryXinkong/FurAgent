package net.xinkong.furagent.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.xinkong.furagent.desktop.data.BUILTIN_MODEL_PRESETS
import net.xinkong.furagent.desktop.data.DesktopModelProfile
import net.xinkong.furagent.desktop.ui.theme.Radius
import net.xinkong.furagent.shared.icons.Icons

/**
 * 模型配置卡片组件（从 SettingsPage.kt 拆分）。
 *
 * 包含 ModelProfileCard / EditModelProfileDialog / PresetChip。
 */
// 模型卡片

@Composable
fun ModelProfileCard(
    profile: DesktopModelProfile,
    isCurrent: Boolean,
    /** 文生图模型是否被选为当前生图模型（仅 kind=="image" 时有意义）。 */
    isImageSelected: Boolean = false,
    onSetCurrent: () -> Unit,
    /** 设为生图模型； 表示该卡片不提供此操作（对话模型）。 */
    onSetImage: (() -> Unit)? = null,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val selected = isCurrent || isImageSelected

    val bg by animateColorAsState(
        targetValue = when {
            selected -> AccentSoft()
            hovered -> CardHoverColor()
            else -> CardStrongColor()
        },
        animationSpec = tween(140),
        label = "modelCardBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) AccentColor().copy(alpha = 0.5f) else BorderColor(),
        animationSpec = tween(140),
        label = "modelCardBorder"
    )

    Surface(
        color = bg,
        shape = RoundedCornerShape(Radius.SM),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { if (onSetImage != null) onSetImage() else onSetCurrent() }
            )
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = { if (onSetImage != null) onSetImage() else onSetCurrent() },
                enabled = true,
                colors = RadioButtonDefaults.colors(selectedColor = AccentColor())
            )
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile.name.ifBlank { "未命名" },
                        fontSize = 13.sp,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        color = TextPrimary(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isCurrent) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(Radius.XS))
                                .background(AccentColor().copy(alpha = 0.18f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                "使用中",
                                fontSize = 9.sp,
                                color = AccentColor(),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    if (isImageSelected) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(Radius.XS))
                                .background(AccentColor().copy(alpha = 0.18f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                "生图中",
                                fontSize = 9.sp,
                                color = AccentColor(),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    if (profile.kind == "image") {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(Radius.XS))
                                .background(WarningColor().copy(alpha = 0.18f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                "生图",
                                fontSize = 9.sp,
                                color = WarningColor(),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                }
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(profile.model.ifBlank { "未设置模型" })
                        if (profile.apiKey.isBlank()) {
                            append(" · 未配置 Key")
                        }
                    },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (profile.apiKey.isBlank()) WarningColor() else TextMuted(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 按钮行常驻占位，悬停/选中只改透明度——
            // 左侧文字宽度恒定，鼠标移入不再因按钮插入而挤压重排。
            // 未显示时鼠标不在行上，透明按钮不会被误触。
            Row(
                Modifier.graphicsLayer {
                    alpha = if (hovered || isCurrent) 1f else 0f
                }
            ) {
                IconActionButton(
                    Icons.Default.Edit,
                    onClick = onEdit,
                    tint = TextSecondary(),
                    size = 28.dp
                )
                IconActionButton(
                    Icons.Default.Delete,
                    onClick = onDelete,
                    tint = ErrorColor(),
                    size = 28.dp
                )
            }
        }
    }
}

@Composable
fun EditModelProfileDialog(
    title: String,
    initial: DesktopModelProfile,
    onDismiss: () -> Unit,
    onConfirm: (DesktopModelProfile) -> Unit
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
    var selectedPresetId by remember { mutableStateOf<String?>(null) }
    var protocol by remember { mutableStateOf(initial.protocol.ifBlank { "openai" }) }
    val isImageModel = initial.kind == "image"

    GlassDialog(
        title = title,
        onDismiss = onDismiss,
        width = 640.dp,
        content = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isImageModel) {
                    // ===== 文生图模型：只填 名称 / 接口地址 / 模型名 / Key =====
                    Text(
                        "文生图模型走 /images/generations 接口，不参与对话。",
                        fontSize = 12.sp, color = TextSecondary()
                    )
                    Spacer(Modifier.height(4.dp))
                    GlassTextField(name, { name = it }, label = "显示名称", placeholder = "例如：豆包生图")
                    GlassTextField(
                        baseUrl, { baseUrl = it },
                        label = "接口地址",
                        placeholder = "https://ark.cn-beijing.volces.com/api/v3/images/generations"
                    )
                    GlassTextField(
                        model, { model = it },
                        label = "模型名",
                        placeholder = "doubao-seedream-5.0-pro"
                    )
                    GlassTextField(apiKey, { apiKey = it }, label = "API Key", placeholder = "sk-...")
                } else {
                    // ===== 对话模型 =====
                    Text("快速填充预设", fontSize = 12.sp, color = TextSecondary())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BUILTIN_MODEL_PRESETS.forEach { p ->
                            val sel = selectedPresetId == p.id
                            PresetChip(p, sel, Modifier.weight(1f)) {
                                selectedPresetId = p.id
                                if (name.isBlank()) name = p.name
                                baseUrl = p.baseUrl
                                model = p.model
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    GlassTextField(name, { name = it }, label = "显示名称", placeholder = "例如：DeepSeek")
                    GlassTextField(baseUrl, { baseUrl = it }, label = "Base URL", placeholder = "https://api.xiaomimimo.com/v1")
                    // 协议选择：同一个服务商可能同时提供两种兼容协议
                    Column(Modifier.fillMaxWidth()) {
                        Text("API 协议", fontSize = 13.sp, color = TextPrimary())
                        Text(
                            "OpenAI 走 /chat/completions，Anthropic 走 /v1/messages。" +
                                    "只填到 /v1 这类版本号会自动补全路径。",
                            fontSize = 11.sp, color = TextSecondary()
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("openai" to "OpenAI 格式", "anthropic" to "Anthropic 格式")
                                .forEach { (value, label) ->
                                    val selected = protocol == value
                                    TextButton(
                                        onClick = { protocol = value },
                                        colors = if (selected) {
                                            ButtonDefaults.textButtonColors(
                                                contentColor = AccentColor()
                                            )
                                        } else {
                                            ButtonDefaults.textButtonColors(
                                                contentColor = TextSecondary()
                                            )
                                        }
                                    ) { Text(if (selected) "✓ $label" else label) }
                                }
                        }
                    }
                    GlassTextField(model, { model = it }, label = "模型名", placeholder = "deepseek-chat")
                    GlassTextField(apiKey, { apiKey = it }, label = "API Key", placeholder = "sk-...")
                    GlassSwitchRow(
                        title = "支持深度思考",
                        subtitle = "关闭后聊天页不显示「深度思考」开关（如 DeepSeek-V3 非思考版）",
                        checked = supportsThinking
                    ) { supportsThinking = it }
                    GlassSwitchRow(
                        title = "支持图片输入（多模态）",
                        subtitle = "关闭后禁用「截图」「查看图片」工具，并拒绝手动发送的图片",
                        checked = supportsVision
                    ) { supportsVision = it }

                    // 采样温度
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("采样温度", fontSize = 13.sp, color = TextPrimary())
                            Spacer(Modifier.weight(1f))
                            Text(
                                String.format(java.util.Locale.US, "%.2f", temperature),
                                fontSize = 13.sp, color = AccentColor(), fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            "越低越稳定（适合调工具），越高越有创意（适合写作）。默认 0.1",
                            fontSize = 11.sp, color = TextSecondary()
                        )
                        GradientSlider(
                            value = temperature,
                            onValueChange = { temperature = it },
                            valueRange = 0f..1.5f,
                            steps = 29
                        )
                    }

                    // 存在惩罚
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("存在惩罚", fontSize = 13.sp, color = TextPrimary())
                            Spacer(Modifier.weight(1f))
                            Text(
                                String.format(java.util.Locale.US, "%.2f", presencePenalty),
                                fontSize = 13.sp, color = AccentColor(), fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            "正值鼓励引入新话题，负值鼓励重复。默认 0（不干预）",
                            fontSize = 11.sp, color = TextSecondary()
                        )
                        GradientSlider(
                            value = presencePenalty,
                            onValueChange = { presencePenalty = it },
                            valueRange = -2f..2f,
                            steps = 39
                        )
                    }

                    // 频率惩罚
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("频率惩罚", fontSize = 13.sp, color = TextPrimary())
                            Spacer(Modifier.weight(1f))
                            Text(
                                String.format(java.util.Locale.US, "%.2f", frequencyPenalty),
                                fontSize = 13.sp, color = AccentColor(), fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            "正值抑制重复用词，负值鼓励重复。默认 0（不干预）",
                            fontSize = 11.sp, color = TextSecondary()
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
        actions = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary()) }
            TextButton(onClick = {
                // 模型名允许为空：只填裸 base 时保存后会自动探测 /models
                if (name.isNotBlank() && baseUrl.isNotBlank()) {
                    onConfirm(
                        initial.copy(
                            name = name.trim(), baseUrl = baseUrl.trim(),
                            model = model.trim(), apiKey = apiKey.trim(),
                            supportsThinking = supportsThinking,
                            supportsVision = supportsVision,
                            temperature = temperature.toDouble(),
                            frequencyPenalty = frequencyPenalty.toDouble(),
                            kind = initial.kind,
                            protocol = protocol
                        )
                        )
                }
            }) { Text("保存", color = AccentColor(), fontWeight = FontWeight.SemiBold) }
        }
    )
}
