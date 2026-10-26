package net.xinkong.furagent.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.xinkong.furagent.desktop.ui.theme.Radius
import net.xinkong.furagent.desktop.ui.theme.Shadow
import net.xinkong.furagent.shared.icons.Icons
import net.xinkong.furagent.shared.ui.applyStreamingGradient
import net.xinkong.furagent.shared.util.ComposeMarkdownRenderer
import net.xinkong.furagent.shared.util.MarkdownColors
import java.awt.Point

// 颜色访问器

@Composable fun BgColor() = furColors().bg
@Composable fun BgGradientTop() = furColors().bgGradientTop
@Composable fun BgGradientBottom() = furColors().bgGradientBottom

@Composable fun SidebarBg() = furColors().sidebarBg
@Composable fun SidebarItemHover() = furColors().sidebarItemHover
@Composable fun SidebarItemActive() = furColors().sidebarItemActive

@Composable fun CardColor() = furColors().card
@Composable fun CardHoverColor() = furColors().cardHover
@Composable fun CardStrongColor() = furColors().cardStrong

@Composable fun BorderColor() = furColors().border
@Composable fun BorderSubtle() = furColors().borderSubtle

@Composable fun TextPrimary() = furColors().textPrimary
@Composable fun TextSecondary() = furColors().textSecondary
@Composable fun TextMuted() = furColors().textMuted

@Composable fun AccentColor() = furColors().accent
@Composable fun AccentHover() = furColors().accentHover
@Composable fun AccentSoft() = furColors().accentSoft
@Composable fun OnAccent() = furColors().onAccent

@Composable fun ToolColor() = furColors().tool
@Composable fun ResultColor() = furColors().result
@Composable fun ErrorColor() = furColors().error
@Composable fun WarningColor() = furColors().warning

@Composable fun ShadowColor() = furColors().shadow

// 玻璃高光
@Composable
fun GlassSheen(
    modifier: Modifier = Modifier,
    height: Dp = 80.dp,
    alpha: Float = 0.06f
) = net.xinkong.furagent.shared.ui.GlassSheen(modifier, height, alpha)

@Composable
fun glassShadowColor() = net.xinkong.furagent.shared.ui.glassShadowColor()

// 背景
@Composable
fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    val top = BgGradientTop()
    val bottom = BgGradientBottom()
    val accent = AccentColor()
    val violet = Color(0xFF7C5CFF)
    val mint = Color(0xFF3ECFB2)
    val isDark = furColors().isDark

    val a1 = if (isDark) 0.14f else 0.13f
    val a2 = if (isDark) 0.10f else 0.09f
    val a3 = if (isDark) 0.075f else 0.065f
    val a4 = if (isDark) 0.055f else 0.050f

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to top,
                    0.55f to bottom,
                    1f to bottom
                )
            )
    ) {
        val rawW = constraints.maxWidth
        val rawH = constraints.maxHeight
        val w = if (rawW !in 1..<100_000) 1280f else rawW.toFloat()
        val h = if (rawH !in 1..<100_000) 820f else rawH.toFloat()
        val diag = kotlin.math.sqrt(w * w + h * h)

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to accent.copy(alpha = a1),
                            0.30f to accent.copy(alpha = a1 * 0.5f),
                            0.55f to accent.copy(alpha = a1 * 0.15f),
                            1.00f to Color.Transparent
                        ),
                        center = Offset(-w * 0.10f, -h * 0.10f),
                        radius = diag * 0.75f
                    )
                )
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to accent.copy(alpha = a2 * 0.6f),
                            0.40f to accent.copy(alpha = a2 * 0.25f),
                            0.75f to accent.copy(alpha = a2 * 0.05f),
                            1.00f to Color.Transparent
                        ),
                        center = Offset(w * 1.10f, h * 1.10f),
                        radius = diag * 0.85f
                    )
                )
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to violet.copy(alpha = a3),
                            0.40f to violet.copy(alpha = a3 * 0.35f),
                            1.00f to Color.Transparent
                        ),
                        center = Offset(w * 1.05f, -h * 0.05f),
                        radius = diag * 0.55f
                    )
                )
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to mint.copy(alpha = a4),
                            0.50f to mint.copy(alpha = a4 * 0.25f),
                            1.00f to Color.Transparent
                        ),
                        center = Offset(-w * 0.05f, h * 1.05f),
                        radius = diag * 0.65f
                    )
                )
        )

        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (isDark) 0.030f else 0.055f),
                        1f to Color.Transparent
                    )
                )
        )

        content()
    }
}

@Composable
fun GlassDragHandle() {
    Box(
        modifier = Modifier
            .padding(vertical = 12.dp)
            .width(36.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(Radius.HAIRLINE))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
    )
}

// 【标准卡片】GlassCard / PocoCard

// 桌面端所有页面级卡片统一走这一个实现：
//   - 底色  CardColor()       (#242629 深色不透明)
//   - 边框  1dp BorderColor() (#383B40)
//   - 圆角  Radius.MD (16dp)
//   - 阴影  hover 4dp / 常态 2dp / 按下 0dp

// PocoCard 是历史遗留名（早期来自 Android 端），已改为转发到 GlassCard，
// 保证两端视觉一致。所有新代码统一写 GlassCard。

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    /** 即使没有 onClick，也响应 hover（用于静态信息卡，如记忆列表项）。 */
    hoverable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val effectiveHover = hovered && (onClick != null || hoverable)
    val bg by animateColorAsState(
        targetValue = if (effectiveHover) CardHoverColor() else CardColor(),
        animationSpec = tween(140),
        label = "cardBg"
    )
    val elevation by animateDpAsState(
        targetValue = when {
            pressed && onClick != null -> Shadow.NONE
            hovered && onClick != null -> Shadow.MD
            else -> Shadow.SM
        },
        animationSpec = tween(150),
        label = "cardElevation"
    )
    val shadowColor = glassShadowColor()

    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = RoundedCornerShape(Radius.MD),
                clip = false,
                ambientColor = shadowColor,
                spotColor = shadowColor
            )
            .clip(RoundedCornerShape(Radius.MD))
            .background(bg)
            .border(1.dp, BorderColor(), RoundedCornerShape(Radius.MD))
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = interaction, indication = null, onClick = onClick
                ) else Modifier
            )
    ) {
        Column(Modifier.fillMaxWidth().padding(padding), content = content)
    }
}

/**
 * 右上角浮层卡片的统一外壳。
 *
 * 用于 ToastHost / TaskFloatingBar / DownloadToast 这类右上角浮层：
 *   - 底色 CardColor()
 *   - 圆角 RoundedCornerShape(Radius.MD)
 *   - 边框 1dp（默认 BorderColor，可自定义）
 *   - 阴影 Shadow.LG
 *
 * 【为什么收口】三处曾各写一份 Surface(...) 参数，改一处要动三处。
 */
@Composable
fun GlassFloatingCard(
    modifier: Modifier = Modifier,
    borderColor: Color = BorderColor(),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = CardColor(),
        shape = RoundedCornerShape(Radius.MD),
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = Shadow.LG,
        modifier = modifier
    ) {
        Column(content = content)
    }
}

@Composable
fun GlassDivider(modifier: Modifier = Modifier, startIndent: Dp = 0.dp, color: Color = BorderColor()) {
    Box(Modifier.fillMaxWidth().padding(start = startIndent).height(1.dp).background(color).then(modifier))
}

// 【通用阴影包装】GlassShadow

// 用途：给任意 content 套一层统一阴影（不改变底色/边框）。
// 典型场景：空状态提示卡片、对话空状态里的 "未配置 API Key" 卡片。

// 与 GlassCard 的区别：
//   GlassCard 提供完整卡片（底色 + 边框 + 阴影 + 圆角）
//   GlassShadow 只提供阴影 + 圆角裁剪，内层自己决定底色和边框

@Composable
fun GlassShadow(
    elevation: Dp = Shadow.SM,
    shape: Shape = RoundedCornerShape(Radius.MD),
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shadowColor = glassShadowColor()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = elevation,
                shape = shape,
                clip = false,
                ambientColor = shadowColor,
                spotColor = shadowColor
            )
    ) {
        content()
    }
}

// 分组标题

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 4.dp, top = 6.dp, bottom = 6.dp),
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AccentColor(), letterSpacing = 0.3.sp)
}

@Composable
fun HintText(text: String, modifier: Modifier = Modifier, color: Color = TextMuted()) {
    Text(text, modifier, fontSize = 11.sp, lineHeight = 16.sp, color = color)
}

// 按钮

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick, enabled = enabled && !loading, shape = RoundedCornerShape(Radius.SM),
        colors = ButtonDefaults.buttonColors(
            containerColor = AccentColor(), contentColor = OnAccent(),
            disabledContainerColor = CardStrongColor(), disabledContentColor = TextMuted()
        ),
        modifier = modifier
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = OnAccent())
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(Radius.SM),
        border = BorderStroke(1.dp, BorderColor()),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary()),
        modifier = modifier
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 13.sp)
    }
}

@Composable
fun DangerButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(Radius.SM),
        border = BorderStroke(1.dp, ErrorColor().copy(alpha = 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorColor()),
        modifier = modifier
    ) {
        Text(text, fontSize = 13.sp)
    }
}

/**
 * 桌面悬停提示（tooltip）。
 *
 * 包住纯图标按钮，悬停片刻后弹出功能说明——解决"图标认不出"的问题。
 * 全站纯图标按钮统一走它。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoverTip(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            TooltipAnchorPosition.Above
        ),
        tooltip = {
            // PlainTooltip 是 TooltipScope 扩展，依赖 lambda 的 receiver 直接调用
            PlainTooltip {
                Text(label, fontSize = 11.sp)
            }
        },
        state = rememberTooltipState(),
        content = content
    )
}

@Composable
fun IconActionButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = TextSecondary(),
    size: Dp = 36.dp
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()

    val bg by animateColorAsState(
        targetValue = when {
            hovered -> CardHoverColor()
            else -> Color.Transparent
        },
        animationSpec = tween(140),
        label = "iconBtnBg"
    )
    val resolvedTint by animateColorAsState(
        targetValue = when {
            hovered && (tint == TextSecondary() || tint == TextMuted()) -> AccentColor()
            else -> tint
        },
        animationSpec = tween(140),
        label = "iconBtnTint"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = tween(120),
        label = "iconBtnScale"
    )

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(Radius.XS))
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription,
            tint = resolvedTint,
            modifier = Modifier
                .size(size * 0.55f)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
        )
    }
}

// 输入框

@Composable
fun GlassTextField(
    value: String, onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier, placeholder: String = "", label: String? = null,
    enabled: Boolean = true, singleLine: Boolean = true,
    minLines: Int = 1, maxLines: Int = if (singleLine) 1 else 8,
    monospace: Boolean = false, leadingIcon: ImageVector? = null,
    trailingContent: @Composable (() -> Unit)? = null
) {
    Column(modifier) {
        if (label != null) {
            Text(label, fontSize = 12.sp, color = TextSecondary(), modifier = Modifier.padding(bottom = 6.dp))
        }
        OutlinedTextField(
            value = value, onValueChange = onValueChange, enabled = enabled,
            singleLine = singleLine, minLines = minLines, maxLines = maxLines,
            shape = RoundedCornerShape(Radius.SM),
            placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, fontSize = 12.sp, color = TextMuted()) },
            leadingIcon = leadingIcon?.let { { Icon(it, null, tint = TextSecondary(), modifier = Modifier.size(16.dp)) } },
            trailingIcon = trailingContent,
            textStyle = TextStyle(color = TextPrimary(), fontSize = 13.sp,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = CardColor(), unfocusedContainerColor = CardColor(),
                disabledContainerColor = CardStrongColor(),
                focusedBorderColor = AccentColor(), unfocusedBorderColor = BorderColor(),
                disabledBorderColor = BorderSubtle(),
                cursorColor = AccentColor(),
                focusedTextColor = TextPrimary(), unfocusedTextColor = TextPrimary(),
                disabledTextColor = TextMuted()
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// 开关

/**
 * 统一的 Switch 配色。全项目所有 Switch 都走这里。
 *
 * 选中 = AccentColor 轨道 + OnAccent 滑块
 * 未选 = CardStrongColor 轨道 + TextSecondary 滑块 + BorderColor 描边
 */
@Composable
fun glassSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = OnAccent(),
    checkedTrackColor = AccentColor(),
    uncheckedThumbColor = TextSecondary(),
    uncheckedTrackColor = CardStrongColor(),
    uncheckedBorderColor = BorderColor()
)

@Composable
fun GlassSwitchRow(
    title: String, subtitle: String? = null, checked: Boolean,
    enabled: Boolean = true, onChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = if (enabled) TextPrimary() else TextMuted())
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 11.sp, lineHeight = 16.sp, color = TextSecondary())
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = glassSwitchColors())
    }
}

// Chip

// 两种 chip 语义不同，但视觉统一（12dp 圆角、相同内边距）：
//   GlassChip         — toggle 语义（选中 / 未选中）
//   QuickActionChip   — action 语义（点击触发，带可选图标）

@Composable
fun GlassChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(if (selected) AccentColor() else CardColor(), tween(140), label = "chipBg")
    val fg by animateColorAsState(if (selected) OnAccent() else TextPrimary(), tween(140), label = "chipFg")
    Box(
        modifier.clip(RoundedCornerShape(Radius.SM)).background(bg)
            .border(1.dp, if (selected) Color.Transparent else BorderColor(), RoundedCornerShape(Radius.SM))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = fg)
    }
}

// 侧边栏项

// 页面标题栏

@Composable
fun PageTitleBar(
    title: String, subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary())
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, fontSize = 12.sp, color = TextSecondary())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        GlassDivider(color = BorderSubtle())
    }
}

// 折叠按钮

@Composable
fun SidebarToggleButton(expanded: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(Radius.XS))
            .background(if (hovered) CardHoverColor() else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (expanded) Icons.AutoMirrored.Filled.MenuOpen else Icons.Default.Menu,
            contentDescription = if (expanded) "折叠侧边栏" else "展开侧边栏",
            tint = TextSecondary(), modifier = Modifier.size(18.dp)
        )
    }
}

// 窗口标题栏

@Composable
fun WindowTitleBar(
    title: String,
    window: java.awt.Window,
    onMinimize: () -> Unit,
    onMaximize: () -> Unit,
    onClose: () -> Unit,
    // 【P1-10 修复】最大化状态下拖拽，先还原再拖，避免无边框窗口乱跳
    isMaximized: Boolean = false,
    onRestoreIfMaximized: (() -> Unit)? = null
) {
    var dragStartMouse: Point? = null
    var dragStartWin: Point? = null

    Row(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(SidebarBg())
            .pointerInput(window, isMaximized) {
                detectDragGestures(
                    onDragStart = {
                        if (isMaximized) onRestoreIfMaximized?.invoke()
                        dragStartMouse = java.awt.MouseInfo.getPointerInfo()?.location
                        dragStartWin = window.location
                    },
                    onDragEnd = { dragStartMouse = null; dragStartWin = null },
                    onDragCancel = { dragStartMouse = null; dragStartWin = null },
                    onDrag = { _, _ ->
                        val m0 = dragStartMouse ?: return@detectDragGestures
                        val w0 = dragStartWin ?: return@detectDragGestures
                        val cur = java.awt.MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                        window.location = Point(w0.x + (cur.x - m0.x), w0.y + (cur.y - m0.y))
                    }
                )
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextSecondary())
        Spacer(Modifier.weight(1f))
        WindowControlButton(WindowControlKind.MINIMIZE, onClick = onMinimize)
        WindowControlButton(WindowControlKind.MAXIMIZE, onClick = onMaximize)
        WindowControlButton(WindowControlKind.CLOSE, onClick = onClose)
    }
}

enum class WindowControlKind { MINIMIZE, MAXIMIZE, CLOSE }

@Composable
private fun WindowControlButton(kind: WindowControlKind, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        hovered && kind == WindowControlKind.CLOSE -> Color(0xFFE53935)
        hovered -> CardHoverColor()
        else -> Color.Transparent
    }
    val fg = if (hovered && kind == WindowControlKind.CLOSE) Color.White else TextSecondary()

    Box(
        Modifier.size(46.dp, 38.dp).background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when (kind) {
            WindowControlKind.MINIMIZE -> Box(Modifier.width(10.dp).height(1.dp).background(fg))
            WindowControlKind.MAXIMIZE -> Box(Modifier.size(9.dp).border(1.dp, fg, RoundedCornerShape(Radius.HAIRLINE)))
            WindowControlKind.CLOSE -> Icon(Icons.Default.Close, "关闭", tint = fg, modifier = Modifier.size(14.dp))
        }
    }
}

// 对话框

// 【复用收口】弹入动画（0.92→1 缩放 + 淡入）三处对话框共用一份，

/**
 * 对话框弹入动画 Modifier：挂到对话框内容上即可获得统一的缩放淡入进场。
 */
@Composable
fun rememberDialogEnter(): Modifier {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.92f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "dialogEnterScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "dialogEnterAlpha"
    )
    return Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
    }
}

@Composable
fun GlassDialog(
    title: String, onDismiss: () -> Unit, width: Dp = 480.dp,
    content: @Composable ColumnScope.() -> Unit, actions: @Composable RowScope.() -> Unit
) {
    val enter = rememberDialogEnter()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardColor(), titleContentColor = TextPrimary(), textContentColor = TextSecondary(),
        shape = RoundedCornerShape(Radius.MD),
        title = {
            Box(enter) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            // 【P2-19 修复】长内容可滚动，防超长内容超出屏幕。
            // 【批次 2 修复】桌面端 Compose 没有 LocalConfiguration（那是 Android 专属 API），
            // 用固定 600dp 上限：1080p / 1440p 都能容纳，不会溢出；
            // AlertDialog 自身还有平台最大宽度限制，双保险。
            Box(
                Modifier
                    .width(width)
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .then(enter)
            ) {
                Column(content = content)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
        }
    )
}

@Composable
fun ConfirmDialog(
    title: String, message: String, confirmText: String = "确定",
    dismissText: String = "取消", danger: Boolean = false,
    onConfirm: () -> Unit, onDismiss: () -> Unit
) {
    val enter = rememberDialogEnter()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardColor(), titleContentColor = TextPrimary(), textContentColor = TextSecondary(),
        shape = RoundedCornerShape(Radius.MD),
        title = {
            Box(enter) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            Box(enter) {
                Text(message, fontSize = 13.sp, lineHeight = 20.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = if (danger) ErrorColor() else AccentColor(), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissText, color = TextSecondary()) } }
    )
}

@Composable
fun InfoDialog(title: String, message: String, onDismiss: () -> Unit) {
    val enter = rememberDialogEnter()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardColor(), titleContentColor = TextPrimary(), textContentColor = TextSecondary(),
        shape = RoundedCornerShape(Radius.MD),
        title = {
            Box(enter) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            Box(enter) {
                Text(message, fontSize = 13.sp, lineHeight = 20.sp, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了", color = AccentColor(), fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier, text: String = "") {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = AccentColor())
        if (text.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(text, fontSize = 12.sp, color = TextSecondary())
        }
    }
}

// Markdown 渲染
@Composable
fun MarkdownText(
    text: String, color: Color, modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    lineHeight: androidx.compose.ui.unit.TextUnit = 15.sp * 1.4f,
    onLinkClick: ((String) -> Unit)? = null,
    maxLines: Int = Int.MAX_VALUE,
    plainText: Boolean = false
) {
    val linkColor = AccentColor()
    val codeBg = CardStrongColor()

    if (plainText) {
        // 流式输出：渐变纯文本
        val annotated = remember(text, color) { applyStreamingGradient(text, color) }
        Text(
            annotated,
            modifier,
            fontSize = fontSize,
            lineHeight = lineHeight,
            color = color,
            maxLines = maxLines,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    } else {
        // 富文本：组件化渲染
        ComposeMarkdownRenderer(
            text = text,
            colors = MarkdownColors(
                textColor = color,
                linkColor = linkColor,
                codeBg = codeBg,
                quoteColor = color.copy(alpha = 0.4f),
                dividerColor = BorderSubtle(),
                tableBorderColor = BorderColor()
            ),
            modifier = modifier,
            onLinkClick = onLinkClick,
            maxLines = maxLines
        )
    }
}