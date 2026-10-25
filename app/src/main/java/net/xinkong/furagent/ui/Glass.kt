package net.xinkong.furagent.ui

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.xinkong.furagent.ui.theme.DarkSuccess
import net.xinkong.furagent.ui.theme.AppRadius
import net.xinkong.furagent.ui.theme.LightSuccess

// 玻璃色

// 【统一代码】语义状态色收口：页面不再硬编码 0x34C759/0xF59E0B/0xEF4444，
// 与桌面端 ErrorColor/WarningColor/ResultColor 命名对称。

/** 成功/在线绿：随明暗取主题 Success 值。 */
@Composable
fun SuccessColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) DarkSuccess else LightSuccess

/** 警告琥珀（与桌面端双主题同值对齐）。 */
@Composable
fun WarningColor(): Color = Color(0xFFF59E0B)

/** 状态错误红（与桌面端 ErrorColor 同值；区别于 colorScheme.error 的系统红）。 */
@Composable
fun ErrorColor(): Color = Color(0xFFEF4444)

// 玻璃色委托到 shared（本地保留同名函数保持向后兼容，调用方无需改 import）

@Composable fun glassSheetBg() = net.xinkong.furagent.shared.ui.glassSheetBg()
@Composable fun glassCardBg() = net.xinkong.furagent.shared.ui.glassCardBg()
@Composable fun glassCardBorder() = net.xinkong.furagent.shared.ui.glassCardBorder()
@Composable fun glassInputBg() = net.xinkong.furagent.shared.ui.glassInputBg()
@Composable fun glassInputBorder() = net.xinkong.furagent.shared.ui.glassInputBorder()
@Composable fun glassDivider() = net.xinkong.furagent.shared.ui.glassDivider()
@Composable fun glassTopBarBg() = net.xinkong.furagent.shared.ui.glassTopBarBg()
@Composable fun glassFloatingBg() = net.xinkong.furagent.shared.ui.glassFloatingBg()
@Composable fun glassPageBg() = net.xinkong.furagent.shared.ui.glassPageBg()

// 玻璃高光
@Composable
fun GlassSheen(
    modifier: Modifier = Modifier,
    height: Dp = 80.dp,
    alpha: Float = 0.06f
) = net.xinkong.furagent.shared.ui.GlassSheen(modifier, height, alpha)

// 阴影颜色
@Composable
fun glassShadowColor() = net.xinkong.furagent.shared.ui.glassShadowColor()

// 全局背景（多 stop 径向渐变，不用 blur）
@Composable
fun GlassBackground(content: @Composable () -> Unit = {}) {
    val config = LocalConfiguration.current
    val screenW = config.screenWidthDp.dp
    val screenH = config.screenHeightDp.dp

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val baseTop = if (isDark) Color(0xFF0B0F16) else Color(0xFFF8F9FC)
    val baseMid = if (isDark) Color(0xFF090C12) else Color(0xFFF3F5FA)
    val baseBottom = if (isDark) Color(0xFF06080D) else Color(0xFFEFF2F8)

    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val violet = Color(0xFF7C5CFF)
    val mint = Color(0xFF3ECFB2)

    val a1 = if (isDark) 0.14f else 0.13f
    val a2 = if (isDark) 0.11f else 0.10f
    val a3 = if (isDark) 0.085f else 0.075f
    val a4 = if (isDark) 0.060f else 0.055f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to baseTop,
                    0.55f to baseMid,
                    1f to baseBottom
                )
            )
    ) {
        // 左上主色晕（5 段色标，自然衰减）
        Box(
            modifier = Modifier
                .size(screenW * 1.5f)
                .offset(x = -screenW * 0.55f, y = -screenH * 0.20f)
                .background(
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to primary.copy(alpha = a1),
                            0.22f to primary.copy(alpha = a1 * 0.62f),
                            0.45f to primary.copy(alpha = a1 * 0.28f),
                            0.72f to primary.copy(alpha = a1 * 0.08f),
                            1.00f to Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )
        // 右下 tertiary 晕
        Box(
            modifier = Modifier
                .size(screenW * 1.4f)
                .align(Alignment.BottomEnd)
                .offset(x = screenW * 0.45f, y = screenH * 0.25f)
                .background(
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to tertiary.copy(alpha = a2),
                            0.30f to tertiary.copy(alpha = a2 * 0.5f),
                            0.60f to tertiary.copy(alpha = a2 * 0.15f),
                            1.00f to Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )
        // 右上紫色晕
        Box(
            modifier = Modifier
                .size(screenW * 1.0f)
                .align(Alignment.TopEnd)
                .offset(x = screenW * 0.30f, y = -screenH * 0.08f)
                .background(
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to violet.copy(alpha = a3),
                            0.40f to violet.copy(alpha = a3 * 0.30f),
                            1.00f to Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )
        // 左下薄荷色晕（让底部不空）
        Box(
            modifier = Modifier
                .size(screenW * 1.2f)
                .align(Alignment.BottomStart)
                .offset(x = -screenW * 0.30f, y = screenH * 0.30f)
                .background(
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to mint.copy(alpha = a4),
                            0.50f to mint.copy(alpha = a4 * 0.20f),
                            1.00f to Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        content()
    }
}

// 背景模糊
@Composable
fun ApplyBackgroundBlur(enabled: Boolean = true) {
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> foreground = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> foreground = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (!view.isInEditMode) {
        DisposableEffect(enabled, foreground) {
            val window = (view.context as? Activity)?.window
            val radius = if (enabled && foreground) 80 else 0
            try { window?.setBackgroundBlurRadius(radius) } catch (_: Exception) { }
            onDispose {
                try { window?.setBackgroundBlurRadius(0) } catch (_: Exception) { }
            }
        }
    }
}

@Composable
fun GlassDragHandle() {
    Box(
        modifier = Modifier
            .padding(vertical = 12.dp)
            .width(36.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(AppRadius.HAIRLINE))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
    )
}

// 【页面模板】PocoPageTopBar

// 所有整屏 Page 的顶部标题栏统一走这一个实现：
//   - 标题：20sp Black + onBackground
//   - 副标题：11sp + onSurfaceVariant
//   - 返回按钮：ArrowBack + onBackground
//   - actions：可选（RowScope）
//   - 背景透明（透出 glassPageBg）

// 【为什么收口】10+ 个 Page 各写一份几乎一样的 TopAppBar，改一处要动 10+ 个文件。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocoPageTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    /** 副标题颜色，默认 onSurfaceVariant。日志页副标题是 primary，需要覆盖。 */
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /** 副标题可点击（如日志页点 Token 数看明细）。 */
    onSubtitleClick: (() -> Unit)? = null,
    /** 自定义副标题插槽（如状态圆点 + 文本），非 null 时优先于 subtitle 文本。 */
    subtitleContent: (@Composable () -> Unit)? = null
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    title,
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(2.dp))
                if (subtitleContent != null) {
                    subtitleContent()
                } else {
                    Text(
                        subtitle,
                        fontSize = 11.sp,
                        color = subtitleColor,
                        modifier = if (onSubtitleClick != null) {
                            Modifier.clickable(onClick = onSubtitleClick)
                        } else Modifier
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

// 【标准卡片】PocoCard

// Android 端所有页面级卡片统一走这一个实现：
//   - 底色  glassCardBg()       (surfaceVariant 60% 半透明)
//   - 边框  0.5dp glassCardBorder()
//   - 圆角  24dp
//   - 阴影  3dp

// 禁止再手写 GlassShadow + Surface(color=..., shape=...)，
// 统一调用 PocoCard { ... }。若需特殊底色（警告/错误），
// 直接内层 Column 加背景色，或明确注释原因手写 Surface。

// 分组的折叠标题用 PocoGroupTitle，分割线用 PocoDivider，
// 输入框用 PocoTextField，开关行用 PocoSwitchRow。

@Composable
fun PocoCard(
    modifier: Modifier = Modifier,
    /** true 时用 primary 描边，表示”选中/当前”状态。 */
    selectedBorder: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val shadowColor = glassShadowColor()
    val borderColor = if (selectedBorder) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    } else glassCardBorder()
    val borderWidth = if (selectedBorder) 1.5.dp else 1.dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 1.5.dp,
                shape = RoundedCornerShape(AppRadius.XL),
                clip = false,
                ambientColor = shadowColor,
                spotColor = shadowColor
            )
    ) {
        Surface(
            color = glassCardBg(),
            shape = RoundedCornerShape(AppRadius.XL),
            border = BorderStroke(borderWidth, borderColor),
            modifier = Modifier.fillMaxWidth()
        ) { Column(content = content) }
    }
}

// 【标准弹窗】PocoAlertDialog

// 全项目 41 处 AlertDialog 都手写
//   shape = RoundedCornerShape(AppRadius.DIALOG),
//   containerColor = MaterialTheme.colorScheme.surface,
// 现统一走这个包装，只传业务参数。

@Composable
fun PocoAlertDialog(
    onDismiss: () -> Unit,
    title: String,
    text: @Composable (() -> Unit)? = null,
    confirmText: String = "确定",
    confirmColor: Color = MaterialTheme.colorScheme.primary,
    onConfirm: () -> Unit,
    dismissText: String? = "取消",
    dismissColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onDismissClick: (() -> Unit)? = null,
    /** 可选的第三个按钮（放在 dismiss 左侧），用于「追加/覆盖」这类三选一场景。 */
    extraDismissText: String? = null,
    extraDismissColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onExtraDismissClick: (() -> Unit)? = null,
    titleContentColor: Color = MaterialTheme.colorScheme.onSurface,
    textContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /** 确定按钮是否可点（如输入框为空时置 false）。 */
    confirmEnabled: Boolean = true
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(AppRadius.DIALOG),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        title = { Text(title) },
        text = text,
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(
                    confirmText,
                    color = if (confirmEnabled) confirmColor
                    else confirmColor.copy(alpha = 0.38f),
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = if (dismissText != null || extraDismissText != null) {
            {
                Row {
                    if (extraDismissText != null) {
                        TextButton(onClick = { onExtraDismissClick?.invoke() }) {
                            Text(extraDismissText, color = extraDismissColor)
                        }
                    }
                    if (dismissText != null) {
                        TextButton(onClick = { onDismissClick?.invoke() ?: onDismiss() }) {
                            Text(dismissText, color = dismissColor)
                        }
                    }
                }
            }
        } else null
    )
}

/**
 * 统一的空状态组件。
 *
 * 全项目所有页面级空状态（无会话 / 无记忆 / 无定时任务 / 无 MCP / 无变量）统一走这里：
 *   - 图标 56dp
 *   - 标题 bodyLarge + onSurfaceVariant
 *   - 副标题 bodySmall + onSurfaceVariant，居中
 *   - 可选 action（主按钮）
 *
 * 【为什么收口】之前 5 个页面各写一份几乎一样的空状态，改一处要动 5 个文件。
 */
@Composable
fun PocoEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (subtitle != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            }
            if (action != null) {
                Spacer(Modifier.height(20.dp))
                action()
            }
        }
    }
}

/**
 * 统一的 OutlinedTextField 配色。
 *
 * 全项目所有 dialog / page 的输入框统一走这里：
 *   - 底色 surfaceVariant 50%
 *   - 主色描边走 primary，非主色描边 outlineVariant
 *
 * 【为什么收口】30+ 处重复的 OutlinedTextFieldDefaults.colors(...) 分散在 5 个文件里，
 * 改一处配色要动 30 处。现在只有这一个来源。
 */
@Composable
fun pocoFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
)

@Composable
fun PocoDivider(startIndent: Dp = 0.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(0.5.dp)
            .background(glassDivider())
    )
}

@Composable
fun PocoGroupTitle(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.3.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp)
    )
}

@Composable
fun PocoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String? = null,
    minLines: Int = 1
) {
    Column {
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
            )
        }
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (minLines > 1) 88.dp else 0.dp),
            minLines = minLines, shape = RoundedCornerShape(AppRadius.MD),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = glassInputBorder(),
                focusedContainerColor = glassInputBg(),
                unfocusedContainerColor = glassInputBg()
            )
        )
    }
}

/**
 * 统一的 Switch 配色。全项目所有 Switch 都走这里。
 *
 * 选中 = primary 轨道 + onPrimary 滑块
 * 未选 = surfaceVariant 轨道 + onSurfaceVariant 滑块 + outlineVariant 描边
 */
@Composable
fun pocoSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant
)

@Composable
fun PocoSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = pocoSwitchColors()
        )
    }
}

@Composable
fun GlassShadow(
    elevation: Dp = 3.dp,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(18.dp),
    content: @Composable () -> Unit
) {
    val shadowColor = glassShadowColor()
    Box(
        modifier = Modifier
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