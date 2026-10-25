package net.xinkong.furagent.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 统一圆角常量（与桌面端 Radius 对齐）。
 *
 * 以前散落 8/10/12/14/16/18/20 七种圆角，视觉上"乱"。
 * 现在统一到 XS / SM / MD / LG 四档。
 */
object AppRadius {
    /** 微型圆角：内联标签、Badge、进度条、内嵌装饰 */
    val MICRO: Dp = 4.dp

    /** 极小圆角：内嵌 Chip、紧凑元素 */
    val TINY: Dp = 6.dp

    /** 图标按钮、Badge、小 Chip */
    val XS: Dp = 8.dp

    /** 小卡片、列表项圆角 */
    val SM_PLUS: Dp = 10.dp

    /** 所有按钮、输入框、NavItem、Chip */
    val SM: Dp = 12.dp

    /** 中等卡片、内嵌元素 */
    val MD_MINUS: Dp = 14.dp

    /** 所有卡片、所有对话框、Toast */
    val MD: Dp = 16.dp

    /** 输入卡片、悬浮窗 */
    val LG: Dp = 20.dp

    /** 底部抽屉、Drawer */
    val XL: Dp = 24.dp

    /** 所有弹窗、底部面板 */
    val DIALOG: Dp = 28.dp

    /** 药丸形状：头像、圆形装饰 */
    val PILL: Dp = 50.dp

    /** 极细分隔线、细线装饰 */
    val HAIRLINE: Dp = 2.dp
}