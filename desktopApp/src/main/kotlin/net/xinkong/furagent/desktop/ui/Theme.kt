package net.xinkong.furagent.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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

// ============================================================
// 浅色（亮白 + 冷灰）
// ============================================================
val FurLightColors = FurColors(
    bg = Color(0xFFFAFAFA),
    bgGradientTop = Color(0xFFFFFFFF),
    bgGradientBottom = Color(0xFFF2F3F5),

    // 半透明侧边栏：透出背景渐变
    sidebarBg = Color(0xE8F5F6F7),
    sidebarItemHover = Color(0xFFEAEBEE),
    sidebarItemActive = Color(0xFFE6EEFE),

    card = Color(0xFFFFFFFF),
    cardHover = Color(0xFFF7F8FA),
    cardStrong = Color(0xFFEDEFF2),

    border = Color(0xFFE4E6EA),
    borderSubtle = Color(0xFFEDEFF2),

    textPrimary = Color(0xFF1A1D21),
    textSecondary = Color(0xFF6B7280),
    textMuted = Color(0xFF9CA3AF),

    accent = Color(0xFF3B82F6),
    accentHover = Color(0xFF2563EB),
    accentSoft = Color(0x143B82F6),
    onAccent = Color.White,

    tool = Color(0xFFF59E0B),
    // 【改】和 Android 端对齐
    result = Color(0xFF34C759),
    error = Color(0xFFEF4444),
    warning = Color(0xFFF59E0B),

    shadow = Color(0x14000000),
    isDark = false
)

// ============================================================
// 深色（中性灰，参考 Codex / VS Code Dark）
// ============================================================
val FurDarkColors = FurColors(
    bg = Color(0xFF1B1D21),
    bgGradientTop = Color(0xFF1F2125),
    bgGradientBottom = Color(0xFF16181C),

    // 半透明侧边栏：透出背景渐变
    sidebarBg = Color(0xE0202225),
    sidebarItemHover = Color(0xFF2A2D31),
    sidebarItemActive = Color(0xFF2A3548),

    card = Color(0xFF242629),
    cardHover = Color(0xFF2C2F33),
    cardStrong = Color(0xFF33363B),

    border = Color(0xFF383B40),
    // 【改】原来 #2C2F33 和 CardHover 一样，看不出分隔
    borderSubtle = Color(0xFF33363B),

    textPrimary = Color(0xFFE6E7E9),
    textSecondary = Color(0xFF9BA1A8),
    textMuted = Color(0xFF6B7076),

    accent = Color(0xFF5A9BFF),
    accentHover = Color(0xFF7BAFFF),
    accentSoft = Color(0x285A9BFF),
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
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

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
            content = content
        )
    }
}