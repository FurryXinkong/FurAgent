package net.xinkong.furagent.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 流体渐变（Fluid Gradient）—— 手机端。
 *
 * 与桌面端 FluidGradient 保持完全一致的配色和用法。
 *
 * 【设计来源】
 * 参考 Gemini / Cursor 等主流 AI 客户端：只在少数关键位置用渐变，
 * 其余保持中性色。用渐变的地方：
 *  - 全部滑块轨道（推理强度、采样温度、惩罚系数、图片压缩…）
 *  - 发送按钮（可发送时）
 *  - 思考中指示器
 *  - 模型胶囊选中态
 *
 * 不用渐变的地方（主流客户端也都不用）：
 *  - 消息气泡
 *  - 输入框
 *  - 设置页背景
 *
 * 【配色】
 * 固定蓝 -> 紫，与主题色（primary）同一色系。深色主题下提亮。
 *  - 浅色：#4A9EFF -> #A78BFA
 *  - 深色：#5AAEFF -> #B79CFF
 */
object FluidGradient {

    val LightStart = Color(0xFF4A9EFF)
    val LightEnd = Color(0xFFA78BFA)
    val DarkStart = Color(0xFF5AAEFF)
    val DarkEnd = Color(0xFFB79CFF)

    @Composable
    fun start(): Color = if (isSystemInDarkTheme()) DarkStart else LightStart

    @Composable
    fun end(): Color = if (isSystemInDarkTheme()) DarkEnd else LightEnd

    /** 水平渐变（左 -> 右） */
    @Composable
    fun horizontal(alpha: Float = 1f): Brush = Brush.horizontalGradient(
        listOf(
            start().copy(alpha = alpha),
            end().copy(alpha = alpha)
        )
    )

    /**
     * 按进度压缩的水平渐变。
     *
     * 用于滑块轨道：整条渐变按比例压缩到进度宽度，
     * 这样滑块在任何位置都能看到完整的蓝->紫过渡。
     */
    @Composable
    fun horizontalProgress(fraction: Float): Brush {
        val f = fraction.coerceIn(0f, 1f)
        return Brush.horizontalGradient(
            colorStops = arrayOf(
                0f to start(),
                f.coerceAtLeast(0.001f) to end()
            )
        )
    }

    /** 对角渐变（左上 -> 右下） */
    @Composable
    fun diagonal(alpha: Float = 1f): Brush = Brush.linearGradient(
        listOf(
            start().copy(alpha = alpha),
            end().copy(alpha = alpha)
        )
    )
}

// ── 通用渐变滑块 ────────────────────────────────────────

/**
 * 带流体渐变轨道的滑块（手机端）。
 *
 * 【为什么不用 Material3 默认轨道】
 * Slider 的 activeTrackColor 只接受纯色，无法直接给 Brush。
 * 这里通过 track / thumb 两个 lambda 自定义视觉，交互（拖拽、点击跳转、
 * 键盘方向键、无障碍）全部保留 Material3 原生实现。
 *
 * @param animated 是否给填充宽度加动画。
 *                 档位选择类（steps > 0 且档位少）建议 true；
 *                 连续滑动类（温度等）建议 false，避免拖拽时滞后。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradientSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    trackHeight: Dp = 6.dp,
    thumbSize: Dp = 16.dp,
    animated: Boolean = false
) {
    val span = valueRange.endInclusive - valueRange.start
    val rawFraction = if (span > 0f) {
        ((value - valueRange.start) / span).coerceIn(0f, 1f)
    } else 0f

    val animatedFraction by animateFloatAsState(
        targetValue = rawFraction,
        animationSpec = tween(durationMillis = 180),
        label = "gradientSliderFill"
    )
    val fraction = if (animated) animatedFraction else rawFraction

    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = Color.Transparent,
            activeTrackColor = Color.Transparent,
            inactiveTrackColor = Color.Transparent,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
            disabledThumbColor = Color.Transparent,
            disabledActiveTrackColor = Color.Transparent,
            disabledInactiveTrackColor = Color.Transparent
        ),
        thumb = {
            Box(
                Modifier
                    .size(thumbSize)
                    .shadow(1.5.dp, CircleShape)
                    .background(
                        if (enabled) Color.White
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        CircleShape
                    )
            )
        },
        track = { _ ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(CircleShape)
                    .background(
                        if (enabled) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
            ) {
                if (fraction > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(FluidGradient.horizontalProgress(fraction))
                    )
                }
            }
        }
    )
}
