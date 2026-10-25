package net.xinkong.furagent.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import furagent.shared.generated.resources.Res
import furagent.shared.generated.resources.misans_vf

// 全局字体 MiSans —— 双端共享唯一实现

// 资源只存一份：shared/src/commonMain/composeResources/font/misans_vf.ttf。
// 可变字体单文件覆盖 9 档字重（wght 轴），标题粗体是真实字形而非伪粗。

// compose resources 的 Res 是 internal，消费方模块无法直接访问，
// 故 FontFamily 构造封装在 shared 内，双端 Theme 只调本 API。

/**
 * MiSans 全局 FontFamily（9 档字重映射可变字体 wght 轴）。
 *
 * Font() 为 @Composable（内部异步加载 + 缓存），首次组合后 remember 复用。
 */
@Composable
fun rememberMiSansFontFamily(): FontFamily {
    val fonts = buildList<androidx.compose.ui.text.font.Font> {
        for (w in intArrayOf(100, 200, 300, 400, 500, 600, 700, 800, 900)) {
            add(
                org.jetbrains.compose.resources.Font(
                    resource = Res.font.misans_vf,
                    weight = FontWeight(w),
                    variationSettings = FontVariation.Settings(FontVariation.weight(w))
                )
            )
        }
    }
    return remember { FontFamily(fonts) }
}
