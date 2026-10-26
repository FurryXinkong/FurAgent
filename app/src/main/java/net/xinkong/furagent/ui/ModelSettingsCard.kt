package net.xinkong.furagent.ui

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
import androidx.compose.material3.MaterialTheme
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
import net.xinkong.furagent.shared.ThinkingLevel
import net.xinkong.furagent.shared.icons.Icons
import kotlin.math.roundToInt

/**
 * 模型设置按钮 + 弹出面板（手机端）。
 *
 * 与桌面端 ModelSettingsButton 同构：
 *  - 常驻状态下只是输入框底部左侧一个胶囊：`模型名 档位 ▾`
 *  - 点开后才弹面板，面板里是「模型选择」+「推理强度滑块」
 *
 * 为什么不用手写滑块
 * 之前用 pointerInput 同时挂 tap 和 drag 两套手势，事件互相抢占导致拖不动。
 * Material3 Slider 自带拖拽 / 点击跳转 / 无障碍，配合 steps 直接吸附档位。
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
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (open) FluidGradient.horizontal(alpha = 0.12f)
                    else SolidColor(Color.Transparent)
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { open = true }
                .padding(horizontal = 6.dp, vertical = 4.dp),
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
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = if (open) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp)
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = if (open) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp)
            )
        }

        // ── 弹出面板 ────────────────────────────────
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false }
        ) {
            Column(Modifier.width(260.dp).padding(4.dp)) {

                // 模型行
                Text(
                    "模型",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 4.dp)
                )
                ModelPickerRow(
                    modelName = modelName,
                    profiles = profiles,
                    currentProfileId = currentProfileId,
                    onSelect = onSelectProfile
                )

                if (supportsThinking) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "推理强度",
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            thinkingLevel.displayName,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
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
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = profiles.isNotEmpty()) { subOpen = true }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                modelName,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(15.dp)
            )
        }

        DropdownMenu(
            expanded = subOpen,
            onDismissRequest = { subOpen = false }
        ) {
            profiles.forEach { (id, name) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (id == currentProfileId)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (id == currentProfileId)
                                FontWeight.SemiBold else FontWeight.Normal
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
