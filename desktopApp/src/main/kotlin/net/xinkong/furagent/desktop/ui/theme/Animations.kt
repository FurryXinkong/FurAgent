package net.xinkong.furagent.desktop.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 全局动画规范。
 *
 * 三档：
 *   QUICK  — 交互反馈（hover / 按压 / 颜色过渡）
 *   NORMAL — 内容入场（对话框 / 气泡 / 卡片展开）
 *   SLOW   — 结构变化（页面切换 / 侧边栏 / 大面板）
 */
object Anim {

    const val QUICK_MS = 140
    const val NORMAL_MS = 220
    const val SLOW_MS = 280

    /** 标准缓动 */
    val Easing: Easing = FastOutSlowInEasing

    /** 快捷构造 — 交互反馈（Dp / Float / IntOffset / Int / Color 都可用） */
    fun <T> quick(): FiniteAnimationSpec<T> = tween(QUICK_MS, easing = Easing)

    /** 快捷构造 — 内容入场 */
    fun <T> normal(): FiniteAnimationSpec<T> = tween(NORMAL_MS, easing = Easing)

    /** 快捷构造 — 结构变化 */
    fun <T> slow(): FiniteAnimationSpec<T> = tween(SLOW_MS, easing = Easing)
}

/** 侧边栏宽度动画专用：保持和 Anim.SLOW_MS 同步 */
val SidebarAnimSpec: FiniteAnimationSpec<Dp> =
    tween(Anim.SLOW_MS, easing = Anim.Easing)

/** 侧边栏折叠时固定 60dp，展开时固定 240dp */
val SidebarExpandedWidth: Dp = 240.dp
val SidebarCollapsedWidth: Dp = 60.dp

// ============================================================
// 圆角 token
// ============================================================
//
// 4 档，不用更多。新增组件时先对照下表选档，别自己拿数字。
//
// 历史包袱清理：以前散落 8/10/12/14/16/18/20 七种圆角，
// 视觉上"乱"的根源之一。现在统一到 XS / SM / MD / LG。
object Radius {
    /** 图标按钮、Badge、小 Chip */
    val XS: Dp = 8.dp

    /** 所有按钮、输入框、SidebarItem、NavItem、Chip */
    val SM: Dp = 12.dp

    /** 所有卡片（GlassCard）、所有对话框、Toast */
    val MD: Dp = 16.dp

    /** 输入卡片、悬浮窗 */
    val LG: Dp = 20.dp
}

// ============================================================
// 阴影 token
// ============================================================
//
// 4 档 + NONE。新增组件时按语义选档。
object Shadow {
    val NONE: Dp = 0.dp

    /** 普通卡片常态 */
    val SM: Dp = 2.dp

    /** 卡片 hover、悬浮工具栏 */
    val MD: Dp = 4.dp

    /** Toast、下载浮层、悬浮窗 */
    val LG: Dp = 8.dp

    /** 模态对话框、输入卡片 */
    val XL: Dp = 20.dp
}

// ============================================================
// 间距 token
// ============================================================
//
// 页面级间距由这些常量统一。新增页面直接取用，别自己拿数字。
object Spacing {
    // 页面级
    /** 页面左右 padding */
    val PAGE_H: Dp = 32.dp

    /** 页面上下 padding */
    val PAGE_V: Dp = 16.dp

    /** 卡片之间 */
    val CARD_GAP: Dp = 12.dp

    /** 分组标题到卡片 */
    val GROUP_TITLE_TOP: Dp = 8.dp

    /** 分组之间 */
    val GROUP_GAP: Dp = 24.dp

    // 卡片内
    /** 卡片 padding */
    val CARD_PADDING: Dp = 16.dp

    /** 卡片内行间距 */
    val ROW_GAP: Dp = 12.dp

    /** 卡片内标题到内容 */
    val TITLE_CONTENT_GAP: Dp = 8.dp

    // 列表项
    /** 列表项水平 padding */
    val LIST_H: Dp = 16.dp

    /** 列表项垂直 padding */
    val LIST_V: Dp = 12.dp

    // 通用小间距
    val XS: Dp = 4.dp
    val SM: Dp = 8.dp
    val MD: Dp = 12.dp
    val LG: Dp = 16.dp
    val XL: Dp = 24.dp
}

// ============================================================
// 字号 token
// ============================================================
//
// 8 档。不用 15/16/22 这些"孤儿值"。
// 16 只给卡片组标题用，别的地方用 14 或 20。
object Type {
    /** 空状态大字 */
    val Display: TextUnit = 42.sp

    /** App 标题、PageTitleBar 主标题 */
    val TitleXL: TextUnit = 20.sp

    /** 卡片组标题 */
    val TitleL: TextUnit = 16.sp

    /** 卡片标题、Section 标题 */
    val TitleM: TextUnit = 14.sp

    /** 正文 */
    val Body: TextUnit = 13.sp

    /** 次要文本 */
    val BodyS: TextUnit = 12.sp

    /** 时间戳、ID、token 数 */
    val Caption: TextUnit = 11.sp

    /** 路径、ID（配 Monospace 字体） */
    val Mono: TextUnit = 12.sp

    /** 更小的路径显示（配 Monospace 字体） */
    val MonoSmall: TextUnit = 11.sp
}