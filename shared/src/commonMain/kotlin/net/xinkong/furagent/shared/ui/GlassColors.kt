package net.xinkong.furagent.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 玻璃拟态颜色工具集。
 *
 * 从 Android（Glass.kt）和 Desktop（Glass.kt）提取的完全相同的 8 个颜色函数 + 高光组件。
 * 两端不再各自实现，统一 import shared 版本。
 */

// ── 玻璃色 ──────────────────────────────────────────────

@Composable
fun glassSheetBg(): Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)

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

@Composable
fun glassPageBg(): Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)

// ── 玻璃高光 ────────────────────────────────────────────

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

// ── 阴影色 ──────────────────────────────────────────────

@Composable
fun glassShadowColor(): Color {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (isDark) Color.Black.copy(alpha = 0.55f)
    else Color.Black.copy(alpha = 0.10f)
}