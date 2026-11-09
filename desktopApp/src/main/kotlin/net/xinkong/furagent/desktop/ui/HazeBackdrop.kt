package net.xinkong.furagent.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur

/**
 * 桌面端真·毛玻璃（Haze 2）。
 *
 * 之前桌面端所有「玻璃」都是不透明纯色 + 静态渐变，没有一次真正的模糊。
 * 这里把 GlassBackground 的渐变层注册为 hazeSource，卡片通过 hazeBlur
 * 采样它并做高斯模糊，得到随主题/背景实时变化的磨砂玻璃。
 *
 * LocalHazeState 为 null 时（例如独立 DialogWindow 不在主背景之下）
 * 自动降级为原来的纯色卡片，不会出现半透明漏底或崩溃。
 */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/** 卡片用的模糊样式：半径适中、噪点很低，避免文字发虚。 */
@Composable
fun rememberCardBlurStyle(): HazeBlurStyle = remember {
    HazeBlurStyle {
        blurRadius(14.dp)
        noiseFactor(0.05f)
    }
}

/**
 * 给卡片叠加背景模糊。没有 hazeSource 时原样返回，保持向后兼容。
 * 样式用 remember 缓存，避免每次重组都重建 Style 导致模糊层反复失效。
 */
@Composable
fun Modifier.glassCardBlur(): Modifier {
    val state = LocalHazeState.current ?: return this
    val style = rememberCardBlurStyle()
    return this.hazeBlur(
        input = HazeInput.Sources(state),
        style = style
    )
}
