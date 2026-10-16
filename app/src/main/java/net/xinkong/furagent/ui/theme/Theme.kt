package net.xinkong.furagent.ui.theme

import android.app.Activity
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// ============================================================
// Light 色板
// ============================================================
private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightError,
    onError = Color(0xFFFFFFFF),
    errorContainer = LightError.copy(alpha = 0.12f),
    onErrorContainer = Color(0xFF7F1D1D),
    secondary = LightOnSurfaceVariant,
    onSecondary = LightOnPrimary,
    secondaryContainer = LightSurfaceVariant,
    onSecondaryContainer = LightOnSurface,
    tertiary = LightPrimary,
    onTertiary = LightOnPrimary,
    tertiaryContainer = LightPrimaryContainer,
    onTertiaryContainer = LightOnPrimaryContainer,
    // ===== 关键：显式指定，避免被 Material3 默认浅色值覆盖 =====
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDDDDDF),
    scrim = Color(0xFF000000)
)

// ============================================================
// Dark 色板
// ============================================================
private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkError,
    onError = Color(0xFF000000),
    errorContainer = DarkError.copy(alpha = 0.28f),
    onErrorContainer = Color(0xFFFFB4AB),
    secondary = DarkOnSurfaceVariant,
    onSecondary = DarkSurface,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = DarkOnSurface,
    tertiary = DarkPrimary,
    onTertiary = DarkOnPrimary,
    tertiaryContainer = DarkPrimaryContainer,
    onTertiaryContainer = DarkOnPrimaryContainer,
    // ===== 关键：显式指定，避免被 Material3 默认浅色值覆盖 =====
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    surfaceBright = Color(0xFF38383C),
    surfaceDim = Color(0xFF0A0A0C),
    scrim = Color(0xFF000000)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

// ============================================================
// 颜色插值
// ============================================================
private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val tt = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * tt,
        green = a.green + (b.green - a.green) * tt,
        blue = a.blue + (b.blue - a.blue) * tt,
        alpha = a.alpha + (b.alpha - a.alpha) * tt
    )
}

private fun lerpColorScheme(light: ColorScheme, dark: ColorScheme, t: Float): ColorScheme {
    return lightColorScheme(
        primary = lerpColor(light.primary, dark.primary, t),
        onPrimary = lerpColor(light.onPrimary, dark.onPrimary, t),
        primaryContainer = lerpColor(light.primaryContainer, dark.primaryContainer, t),
        onPrimaryContainer = lerpColor(light.onPrimaryContainer, dark.onPrimaryContainer, t),
        inversePrimary = lerpColor(light.inversePrimary, dark.inversePrimary, t),
        background = lerpColor(light.background, dark.background, t),
        onBackground = lerpColor(light.onBackground, dark.onBackground, t),
        surface = lerpColor(light.surface, dark.surface, t),
        onSurface = lerpColor(light.onSurface, dark.onSurface, t),
        surfaceVariant = lerpColor(light.surfaceVariant, dark.surfaceVariant, t),
        onSurfaceVariant = lerpColor(light.onSurfaceVariant, dark.onSurfaceVariant, t),
        surfaceTint = lerpColor(light.surfaceTint, dark.surfaceTint, t),
        inverseSurface = lerpColor(light.inverseSurface, dark.inverseSurface, t),
        inverseOnSurface = lerpColor(light.inverseOnSurface, dark.inverseOnSurface, t),
        outline = lerpColor(light.outline, dark.outline, t),
        outlineVariant = lerpColor(light.outlineVariant, dark.outlineVariant, t),
        error = lerpColor(light.error, dark.error, t),
        onError = lerpColor(light.onError, dark.onError, t),
        errorContainer = lerpColor(light.errorContainer, dark.errorContainer, t),
        onErrorContainer = lerpColor(light.onErrorContainer, dark.onErrorContainer, t),
        secondary = lerpColor(light.secondary, dark.secondary, t),
        onSecondary = lerpColor(light.onSecondary, dark.onSecondary, t),
        secondaryContainer = lerpColor(light.secondaryContainer, dark.secondaryContainer, t),
        onSecondaryContainer = lerpColor(light.onSecondaryContainer, dark.onSecondaryContainer, t),
        tertiary = lerpColor(light.tertiary, dark.tertiary, t),
        onTertiary = lerpColor(light.onTertiary, dark.onTertiary, t),
        tertiaryContainer = lerpColor(light.tertiaryContainer, dark.tertiaryContainer, t),
        onTertiaryContainer = lerpColor(light.onTertiaryContainer, dark.onTertiaryContainer, t),
        // 关键：这五个字段也要 lerp
        surfaceContainerLowest = lerpColor(light.surfaceContainerLowest, dark.surfaceContainerLowest, t),
        surfaceContainerLow = lerpColor(light.surfaceContainerLow, dark.surfaceContainerLow, t),
        surfaceContainer = lerpColor(light.surfaceContainer, dark.surfaceContainer, t),
        surfaceContainerHigh = lerpColor(light.surfaceContainerHigh, dark.surfaceContainerHigh, t),
        surfaceContainerHighest = lerpColor(light.surfaceContainerHighest, dark.surfaceContainerHighest, t),
        surfaceBright = lerpColor(light.surfaceBright, dark.surfaceBright, t),
        surfaceDim = lerpColor(light.surfaceDim, dark.surfaceDim, t),
        scrim = lerpColor(light.scrim, dark.scrim, t)
    )
}

// ============================================================
// 主题
// ============================================================
@Composable
fun FurAgentTheme(
    themeMode: String = "system",
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }

    val darkFactor by animateFloatAsState(
        targetValue = if (darkTheme) 1f else 0f,
        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing),
        label = "darkFactor"
    )

    val colorScheme = remember(darkFactor) {
        lerpColorScheme(LightColors, DarkColors, darkFactor)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = darkFactor < 0.5f
            controller.isAppearanceLightNavigationBars = darkFactor < 0.5f
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        typography = Typography,
        content = content
    )
}