package net.xinkong.furagent.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import net.xinkong.furagent.desktop.ui.theme.Radius
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.xinkong.furagent.shared.ui.rememberMiSansFontFamily

data class FurColors(
    val bg: Color,
    val bgGradientTop: Color,
    val bgGradientBottom: Color,

    val sidebarBg: Color,
    val sidebarItemHover: Color,
    val sidebarItemActive: Color,

    val card: Color,
    val cardHover: Color,
    val cardStrong: Color,

    val border: Color,
    val borderSubtle: Color,

    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,

    val accent: Color,
    val accentHover: Color,
    val accentSoft: Color,
    val onAccent: Color,

    val tool: Color,
    val result: Color,
    val error: Color,
    val warning: Color,

    val shadow: Color,
    val isDark: Boolean
)

// 浅色（亮白 + 冷灰）
val FurLightColors = FurColors(
    bg = Color(0xFFFAFAFA),
    bgGradientTop = Color(0xFFFFFFFF),
    bgGradientBottom = Color(0xFFF2F3F5),

    // 半透明侧边栏：透出背景渐变
    sidebarBg = Color(0xE8F5F6F7),
    sidebarItemHover = Color(0xFFEAEBEE),
    sidebarItemActive = Color(0xFFEBEDFE),

    card = Color(0xFFFFFFFF),
    cardHover = Color(0xFFF7F8FA),
    cardStrong = Color(0xFFEDEFF2),

    border = Color(0xFFE4E6EA),
    borderSubtle = Color(0xFFEDEFF2),

    textPrimary = Color(0xFF1A1D21),
    textSecondary = Color(0xFF6B7280),
    textMuted = Color(0xFF9CA3AF),

    // 【改】主题色从纯蓝换成蓝紫，与流体渐变（#4A9EFF -> #A78BFA）同一色系
    accent = Color(0xFF6E7CFA),
    accentHover = Color(0xFF5866E8),
    accentSoft = Color(0x146E7CFA),
    onAccent = Color.White,

    tool = Color(0xFFF59E0B),
    // 【改】和 Android 端对齐
    result = Color(0xFF34C759),
    error = Color(0xFFEF4444),
    warning = Color(0xFFF59E0B),

    shadow = Color(0x14000000),
    isDark = false
)

// 深色（中性灰，参考 Codex / VS Code Dark）
val FurDarkColors = FurColors(
    bg = Color(0xFF1B1D21),
    bgGradientTop = Color(0xFF1F2125),
    bgGradientBottom = Color(0xFF16181C),

    // 半透明侧边栏：透出背景渐变
    sidebarBg = Color(0xE0202225),
    sidebarItemHover = Color(0xFF2A2D31),
    sidebarItemActive = Color(0xFF2E3050),

    card = Color(0xFF242629),
    cardHover = Color(0xFF2C2F33),
    cardStrong = Color(0xFF33363B),

    border = Color(0xFF383B40),
    // 【改】原来 #2C2F33 和 CardHover 一样，看不出分隔
    borderSubtle = Color(0xFF33363B),

    textPrimary = Color(0xFFE6E7E9),
    textSecondary = Color(0xFF9BA1A8),
    textMuted = Color(0xFF6B7076),

    // 【改】主题色从纯蓝换成蓝紫（提亮版），与流体渐变深色版同色系
    accent = Color(0xFF8B93FF),
    accentHover = Color(0xFFA2A9FF),
    accentSoft = Color(0x288B93FF),
    onAccent = Color(0xFF0F1216),

    // 【改】原来 #F5A524 偏橙，改成和 Android 端一致的琥珀色
    tool = Color(0xFFF59E0B),
    // 【改】原来 #4ADE80 太亮，改成 iOS 系统绿，和 Android 端对齐
    result = Color(0xFF34C759),
    error = Color(0xFFEF4444),
    // 【改】和 tool 保持一致
    warning = Color(0xFFF59E0B),

    shadow = Color(0x99000000),
    isDark = true
)

val LocalFurColors = staticCompositionLocalOf { FurDarkColors }

@Composable
fun furColors(): FurColors = LocalFurColors.current

private fun materialLight(c: FurColors) = lightColorScheme(
    primary = c.accent,
    onPrimary = c.onAccent,
    primaryContainer = c.accentSoft,
    onPrimaryContainer = c.accent,
    background = c.bg,
    onBackground = c.textPrimary,
    surface = c.card,
    onSurface = c.textPrimary,
    surfaceVariant = c.cardHover,
    onSurfaceVariant = c.textSecondary,
    outline = c.border,
    outlineVariant = c.borderSubtle,
    error = c.error,
    onError = Color.White,
    errorContainer = c.error.copy(alpha = 0.12f),
    onErrorContainer = Color(0xFF7F1D1D),
    // 【C3 修复】显式覆盖 surfaceContainer*，避免深色模式下弹窗用浅色底
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAFC),
    surfaceContainer = Color(0xFFF3F3F5),
    surfaceContainerHigh = Color(0xFFEDEDEF),
    surfaceContainerHighest = Color(0xFFE7E7EA),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDDDDDF),
    scrim = Color.Black
)

private fun materialDark(c: FurColors) = darkColorScheme(
    primary = c.accent,
    onPrimary = c.onAccent,
    primaryContainer = c.accentSoft,
    onPrimaryContainer = c.accent,
    background = c.bg,
    onBackground = c.textPrimary,
    surface = c.card,
    onSurface = c.textPrimary,
    surfaceVariant = c.cardHover,
    onSurfaceVariant = c.textSecondary,
    outline = c.border,
    outlineVariant = c.borderSubtle,
    error = c.error,
    onError = Color.White,
    errorContainer = c.error.copy(alpha = 0.28f),
    onErrorContainer = Color(0xFFFFB4AB),
    // 【C3 修复】显式覆盖 surfaceContainer*，避免深色模式下弹窗用浅色底
    surfaceContainerLowest = Color(0xFF0A0A0C),
    surfaceContainerLow = Color(0xFF151517),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF26262A),
    surfaceContainerHighest = Color(0xFF303034),
    surfaceBright = Color(0xFF38383C),
    surfaceDim = Color(0xFF0A0A0C),
    scrim = Color.Black
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.TINY),
    small = RoundedCornerShape(Radius.SM),
    medium = RoundedCornerShape(Radius.MD),
    large = RoundedCornerShape(Radius.LG),
    extraLarge = RoundedCornerShape(Radius.XL)
)

// 全局字体 MiSans —— 构造逻辑在 shared（rememberMiSansFontFamily），
// 字体资源只存 shared/composeResources/font 一份，双端共用。
@Composable
private fun rememberMiSansTypography(): Typography {
    val family = rememberMiSansFontFamily()
    return remember(family) {
        val base = Typography()
        Typography(
            displayLarge = base.displayLarge.copy(fontFamily = family),
            displayMedium = base.displayMedium.copy(fontFamily = family),
            displaySmall = base.displaySmall.copy(fontFamily = family),
            headlineLarge = base.headlineLarge.copy(fontFamily = family),
            headlineMedium = base.headlineMedium.copy(fontFamily = family),
            headlineSmall = base.headlineSmall.copy(fontFamily = family),
            titleLarge = base.titleLarge.copy(fontFamily = family),
            titleMedium = base.titleMedium.copy(fontFamily = family),
            titleSmall = base.titleSmall.copy(fontFamily = family),
            bodyLarge = base.bodyLarge.copy(fontFamily = family),
            bodyMedium = base.bodyMedium.copy(fontFamily = family),
            bodySmall = base.bodySmall.copy(fontFamily = family),
            labelLarge = base.labelLarge.copy(fontFamily = family),
            labelMedium = base.labelMedium.copy(fontFamily = family),
            labelSmall = base.labelSmall.copy(fontFamily = family),
        )
    }
}

@Composable
fun FurAgentTheme(
    themeMode: String = "system",
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }
    val colors = if (darkTheme) FurDarkColors else FurLightColors
    val m3 = if (darkTheme) materialDark(colors) else materialLight(colors)
    CompositionLocalProvider(LocalFurColors provides colors) {
        MaterialTheme(
            colorScheme = m3,
            shapes = AppShapes,
            typography = rememberMiSansTypography(),
            content = content
        )
    }
}