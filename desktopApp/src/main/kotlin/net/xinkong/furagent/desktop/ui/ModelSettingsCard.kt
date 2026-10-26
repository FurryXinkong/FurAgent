package net.xinkong.furagent.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.xinkong.furagent.desktop.ui.theme.Radius
import net.xinkong.furagent.shared.ThinkingLevel
import net.xinkong.furagent.shared.icons.Icons
import kotlin.math.roundToInt

/**
 * 模型设置按钮 + 弹出面板。
 *
 * 设计参考 常见 AI 客户端的做法：
 *  - 常驻状态下，输入框底部左侧只有一个胶囊按钮：`模型名 档位 ▾`
 *  - 点开后才弹出面板，面板里是「模型选择」+「推理强度滑块」
 *  - 不占用常驻空间，输入框保持干净
 *
 * ```
 * 常驻： [+ ] [ GPT-5.6 Terra Max ▾ ]        [发送]
 *                     ↓ 点击
 * 面板：┌────────────────────────────┐
 *       │ 模型          GPT-5.6 Terra › │
 *       ├────────────────────────────┤
 *       │ 推理强度                标准 │
 *       │ ●━━━━━━━━━━━━━━━━━━━━━━━━● │
 *       └────────────────────────────┘
 * ```
 *
 * 为什么用 Material3 Slider
 * 之前用手写 pointerInput（tap + drag 两套手势）会互相抢事件，导致拖不动。
 * Slider 自带拖拽、点击跳转、键盘控制、无障碍支持，配合 steps 直接吸附档位。
 */
@Composable
fun ModelSettingsButton(
    modelName: String,
    profiles: List<Pair<String, String>>,
    currentProfileId: String,
    onSelectProfile: (String) -> Unit,
    thinkingLevel: ThinkingLevel,
    supportsThinking: Boolean,
    onThinkingLevelChange: (ThinkingLevel) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }

    Box(modifier) {
        // ── 常驻胶囊按钮 ─────────────────────────────
        Row(
            Modifier
                .clip(RoundedCornerShape(Radius.SM))
                .background(
                    if (open) FluidGradient.horizontal(alpha = 0.12f)
                    else SolidColor(Color.Transparent)
                )
                .clickableNoRipple { open = true }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                buildString {
                    append(modelName)
                    if (supportsThinking && thinkingLevel != ThinkingLevel.OFF) {
                        append(" ")
                        append(thinkingLevel.displayName)
                    }
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (open) AccentColor() else TextSecondary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp)
            )
            Spacer(Modifier.width(3.dp))
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = if (open) AccentColor() else TextMuted(),
                modifier = Modifier.size(15.dp)
            )
        }

        // ── 弹出面板 ────────────────────────────────
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = CardColor()
        ) {
            Column(Modifier.width(300.dp).padding(4.dp)) {

                // 模型行
                Text(
                    "模型",
                    fontSize = 11.sp,
                    color = TextMuted(),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 6.dp)
                )
                ModelPickerRow(
                    modelName = modelName,
                    profiles = profiles,
                    currentProfileId = currentProfileId,
                    onSelect = onSelectProfile
                )

                if (supportsThinking) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "推理强度",
                            fontSize = 11.sp,
                            color = TextMuted(),
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            thinkingLevel.displayName,
                            fontSize = 12.sp,
                            color = AccentColor(),
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    ReasoningSlider(
                        level = thinkingLevel,
                        onChange = onThinkingLevelChange
                    )
                }

                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

// ── 面板内的模型选择行 ──────────────────────────────────

@Composable
private fun ModelPickerRow(
    modelName: String,
    profiles: List<Pair<String, String>>,
    currentProfileId: String,
    onSelect: (String) -> Unit
) {
    var subOpen by remember { mutableStateOf(false) }

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.SM))
                .clickableNoRipple(enabled = profiles.isNotEmpty()) { subOpen = true }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                modelName,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextMuted(),
                modifier = Modifier.size(15.dp)
            )
        }

        DropdownMenu(
            expanded = subOpen,
            onDismissRequest = { subOpen = false },
            containerColor = CardColor()
        ) {
            profiles.forEach { (id, name) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            name,
                            fontSize = 13.sp,
                            color = if (id == currentProfileId) AccentColor() else TextPrimary(),
                            fontWeight = if (id == currentProfileId) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        subOpen = false
                        onSelect(id)
                    }
                )
            }
        }
    }
}

// ── 推理强度滑块 ────────────────────────────────────────

/**
 * 推理强度滑块。
 *
 * 轨道用流体渐变（蓝 -> 紫），填充宽度随档位平滑动画过渡。
 *
 * 实现说明
 * Material3 Slider 的 activeTrackColor 只接受纯色，无法直接给渐变。
 * 这里用 track lambda 自定义轨道：底层画灰色轨道，上层画渐变填充。
 * 滑块本身仍由 Slider 管理（拖拽、点击、键盘、无障碍全部保留）。
 *
 * 动画
 * 填充宽度用 animateFloatAsState 平滑过渡（180ms），
 * 档位切换时不会突兀跳变。
 */
@Composable
private fun ReasoningSlider(
    level: ThinkingLevel,
    onChange: (ThinkingLevel) -> Unit
) {
    val levels = ThinkingLevel.uiOrder()
    val lastIndex = (levels.size - 1).coerceAtLeast(1)
    val currentIndex = levels.indexOf(level).coerceIn(0, lastIndex).toFloat()

    // animated = true：档位切换时填充宽度平滑过渡
    GradientSlider(
        value = currentIndex,
        onValueChange = { v ->
            val idx = v.roundToInt().coerceIn(0, lastIndex)
            if (levels[idx] != level) onChange(levels[idx])
        },
        valueRange = 0f..lastIndex.toFloat(),
        steps = (levels.size - 2).coerceAtLeast(0),
        trackHeight = 8.dp,
        thumbSize = 18.dp,
        animated = true,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

// ── 无涟漪点击（避免 Material 默认涟漪破坏渐变观感） ──────

@Composable
private fun Modifier.clickableNoRipple(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.then(
        Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
    )
}
