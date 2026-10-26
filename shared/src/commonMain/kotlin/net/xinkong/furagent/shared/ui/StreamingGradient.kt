package net.xinkong.furagent.shared.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import kotlin.math.pow

/**
 * 为流式输出的文本末尾应用渐变透明度效果。
 *
 * 最新字符最淡（alpha≈0.15），往前逐渐加深，约5个字符后完全不透明。
 * 模拟 ChatGPT / Claude 的流式打字机渐变效果。
 *
 * @param content   当前流式文本
 * @param baseColor 文本基础颜色
 * @param fadeChars 渐变覆盖的字符数（默认5）
 */
fun applyStreamingGradient(
    content: String,
    baseColor: Color,
    fadeChars: Int = 14
): AnnotatedString {
    if (content.length <= 1) return AnnotatedString(content)

    // 渐变曲线 vs 卡顿感
    // 原来 fadeChars = 5 时，字符从 0.15 → 1.0 只跨 5 个字符，
    // 每 32ms 新增一个字符，5 字符 = 160ms 走完，视觉上像"一格一格跳"。
    // 延长到 14 字符后，每字符从最淡到不透明约 450ms，明显更平滑。
    val fade = fadeChars.coerceAtLeast(1)
    val gradientStart = (content.length - fade).coerceAtLeast(0)

    return buildAnnotatedString {
        // 前面完全不透明的文本：一次性 append（不 create 任何 span）
        if (gradientStart > 0) append(content.substring(0, gradientStart))

        // 渐变尾部：整体 append 一次，再按位置加 style
        val tail = content.substring(gradientStart)
        append(tail)

        // 性能 原实现逐字符 withStyle（每个字符 create 一次 pushStyle/pop），
        // 现在改为 addStyle 一次性加 SpanStyle，span 树深度浅、渲染更轻。
        for (i in tail.indices) {
            val posFromEnd = tail.length - 1 - i
            val alpha = alphaForPositionFromEnd(posFromEnd, fade)
            if (alpha >= 1f) continue
            addStyle(
                SpanStyle(color = baseColor.copy(alpha = alpha)),
                start = gradientStart + i,
                end = gradientStart + i + 1
            )
        }
    }
}

/**
 * 渐变透明度曲线。
 *
 * @param posFromEnd 从 0（最新字符，最淡）到 fade-1（最老字符，几乎不透明）
 * @param fade       渐变总字符数
 * @return alpha 值 0.0（完全透明）~ 1.0（完全不透明）
 *
 * 曲线：`0.08 + 0.92 * norm^1.3`，norm = posFromEnd / (fade-1)。
 *  - 前段（最新几个字符）变化慢，避免最新字符淡到看不见
 *  - 后段（较老字符）变化快，避免末尾出现硬边界
 *  - 相比线性曲线，视觉上"从雾里浮出来"的过程更自然
 */
private fun alphaForPositionFromEnd(posFromEnd: Int, fade: Int): Float {
    if (fade <= 1) return 1f
    if (posFromEnd >= fade - 1) return 1f
    val norm = posFromEnd.toFloat() / (fade - 1).toFloat()
    return 0.08f + 0.92f * norm.toDouble().pow(1.3).toFloat()
}