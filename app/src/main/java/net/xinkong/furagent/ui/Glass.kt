package net.xinkong.furagent.ui

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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

// ============================================================
// 玻璃色
// ============================================================
@Composable
fun glassSheetBg(): Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)

/**
 * 整屏 Page 的背景色。
 *
 * 和 glassSheetBg（半透明 96%）不同，Page 是整屏覆盖，必须完全不透明，
 * 否则会透出底下的聊天界面。
 */
@Composable
fun glassPageBg(): Color {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (isDark) Color(0xFF0B0F16)
    else Color(0xFFF8F9FC)
}
@Composable
fun glassCardBg(): Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
@Composable
fun glassCardBorder(): Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
@Composable
fun glassInputBg(): Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
@Composable
fun glassInputBorder(): Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
@Composable
fun glassDivider(): Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
@Composable
fun glassTopBarBg(): Color = MaterialTheme.colorScheme.background.copy(alpha = 0.85f)
@Composable
fun glassFloatingBg(): Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)

// ============================================================
// 玻璃高光
// ============================================================
@Composable
fun GlassSheen(
    modifier: Modifier = Modifier,
    height: Dp = 80.dp,
    alpha: Float = 0.06f
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val sheenColor = if (isDark) {
        Color.White.copy(alpha = alpha * 0.7f)
    } else {
        Color.White.copy(alpha = alpha * 1.6f)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(
                Brush.verticalGradient(
                    colors = listOf(sheenColor, Color.Transparent)
                )
            )
    )
}

// ============================================================
// 阴影颜色
// ============================================================
@Composable
fun glassShadowColor(): Color {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (isDark) Color.Black.copy(alpha = 0.55f)
    else Color.Black.copy(alpha = 0.10f)
}

// ============================================================
// 全局背景（多 stop 径向渐变，不用 blur）
// ============================================================
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

// ============================================================
// 背景模糊
// ============================================================
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
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
    )
}

// ============================================================
// 【页面模板】PocoPageTopBar
// ============================================================
//
// 所有整屏 Page 的顶部标题栏统一走这一个实现：
//   - 标题：20sp Black + onBackground
//   - 副标题：11sp + onSurfaceVariant
//   - 返回按钮：ArrowBack + onBackground
//   - actions：可选（RowScope）
//   - 背景透明（透出 glassPageBg）
//
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
    onSubtitleClick: (() -> Unit)? = null
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
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = subtitleColor,
                    modifier = if (onSubtitleClick != null) {
                        Modifier.clickable(onClick = onSubtitleClick)
                    } else Modifier
                )
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

// ============================================================
// 【标准卡片】PocoCard
// ============================================================
//
// Android 端所有页面级卡片统一走这一个实现：
//   - 底色  glassCardBg()       (surfaceVariant 60% 半透明)
//   - 边框  0.5dp glassCardBorder()
//   - 圆角  24dp
//   - 阴影  3dp
//
// 禁止再手写 GlassShadow + Surface(color=..., shape=...)，
// 统一调用 PocoCard { ... }。若需特殊底色（警告/错误），
// 直接内层 Column 加背景色，或明确注释原因手写 Surface。
//
// 分组的折叠标题用 PocoGroupTitle，分割线用 PocoDivider，
// 输入框用 PocoTextField，开关行用 PocoSwitchRow。

@Composable
fun PocoCard(
    modifier: Modifier = Modifier,
    /** true 时用 primary 描边，表示“选中/当前”状态。 */
    selectedBorder: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val shadowColor = glassShadowColor()
    val borderColor = if (selectedBorder) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    } else glassCardBorder()
    val borderWidth = if (selectedBorder) 1.dp else 0.5.dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 3.dp,
                shape = RoundedCornerShape(24.dp),
                clip = false,
                ambientColor = shadowColor,
                spotColor = shadowColor
            )
    ) {
        Surface(
            color = glassCardBg(),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(borderWidth, borderColor),
            modifier = Modifier.fillMaxWidth()
        ) { Column(content = content) }
    }
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
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
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
            minLines = minLines, shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = glassInputBorder(),
                focusedContainerColor = glassInputBg(),
                unfocusedContainerColor = glassInputBg()
            )
        )
    }
}

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
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
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