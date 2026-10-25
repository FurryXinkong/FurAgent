package net.xinkong.furagent.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.xinkong.furagent.shared.util.ChatListItem
import net.xinkong.furagent.ui.theme.AppRadius
import kotlin.math.roundToInt

/**
 * 聊天页右侧的 MiniMap（P1-14，从桌面端 ChatPage.kt 移植）。
 *
 * 显示每条消息在会话中的相对位置，用色块区分用户 / AI / 工具。
 * 点击或拖动可跳转到对应位置。
 *
 * 只在消息较多时（≥15 条）显示，避免短会话占位置。
 */
@Composable
fun MiniMap(
    listState: LazyListState,
    chatItems: List<ChatListItem>,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var trackHeightPx by remember { mutableIntStateOf(1) }
    var hovered by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }

    val visibleRange by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (chatItems.isEmpty() || info.totalItemsCount == 0) {
                return@derivedStateOf 0f to 1f
            }
            val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = chatItems.size.toFloat().coerceAtLeast(1f)
            val top = (first.toFloat() / total).coerceIn(0f, 1f)
            val bottom = ((last + 1).toFloat() / total).coerceIn(0f, 1f)
            top to bottom
        }
    }

    val targetTop = visibleRange.first * trackHeightPx
    val targetHeight = ((visibleRange.second - visibleRange.first) * trackHeightPx)
        .coerceAtLeast(16f)
    val animatedTop by animateFloatAsState(
        targetValue = targetTop,
        animationSpec = tween(140, easing = FastOutSlowInEasing),
        label = "minimapTop"
    )
    val animatedHeight by animateFloatAsState(
        targetValue = targetHeight,
        animationSpec = tween(140, easing = FastOutSlowInEasing),
        label = "minimapHeight"
    )

    val barColor = MaterialTheme.colorScheme.primary
    val userColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
    val assistantColor = MaterialTheme.colorScheme.surfaceVariant
    val toolColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.75f)

    val blockColors by remember {
        derivedStateOf {
            val userArgb = userColor.toArgb()
            val assistantArgb = assistantColor.toArgb()
            val toolArgb = toolColor.toArgb()
            chatItems.map { item ->
                when (item) {
                    is ChatListItem.Single ->
                        if (item.msg.role == "user") userArgb else assistantArgb
                    is ChatListItem.ToolsGroup -> toolArgb
                    is ChatListItem.WorkProcess -> toolArgb
                    is ChatListItem.DateSeparator -> 0
                }
            }
        }
    }

    val hitWidth = 28.dp

    Box(
        modifier = modifier
            .width(hitWidth)
            .pointerInput(chatItems.size) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        when (event.type) {
                            PointerEventType.Enter -> hovered = true
                            PointerEventType.Exit -> {
                                hovered = false
                                dragging = false
                            }
                            PointerEventType.Press -> {
                                dragging = true
                                val y = change.position.y
                                val ratio = (y / trackHeightPx).coerceIn(0f, 1f)
                                val targetIndex = (ratio * (chatItems.size - 1))
                                    .roundToInt()
                                    .coerceIn(0, chatItems.size - 1)
                                scope.launch {
                                    try {
                                        listState.scrollToItem(targetIndex)
                                    } catch (_: Exception) {
                                    }
                                }
                                change.consume()
                            }
                            PointerEventType.Move -> {
                                if (dragging) {
                                    val y = change.position.y
                                    val ratio = (y / trackHeightPx).coerceIn(0f, 1f)
                                    val targetIndex = (ratio * (chatItems.size - 1))
                                        .roundToInt()
                                        .coerceIn(0, chatItems.size - 1)
                                    scope.launch {
                                        try {
                                            listState.scrollToItem(targetIndex)
                                        } catch (_: Exception) {
                                        }
                                    }
                                    change.consume()
                                }
                            }
                            PointerEventType.Release -> {
                                dragging = false
                                change.consume()
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(
            alpha = if (hovered) 0.9f else 0.5f
        )
        val bgColor = MaterialTheme.colorScheme.surface.copy(
            alpha = if (hovered) 0.85f else 0.5f
        )

        Canvas(
            modifier = Modifier
                .width(16.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(AppRadius.XS))
                .background(bgColor)
                .border(1.dp, borderColor, RoundedCornerShape(AppRadius.XS))
                .onGloballyPositioned { coords ->
                    trackHeightPx = coords.size.height.coerceAtLeast(1)
                }
        ) {
            val total = chatItems.size.toFloat().coerceAtLeast(1f)
            val w = size.width

            blockColors.forEachIndexed { idx, argb ->
                if (argb == 0) return@forEachIndexed
                val topY = (idx / total) * trackHeightPx
                val h = ((1f / total) * trackHeightPx).coerceAtLeast(1.5f)
                drawRect(
                    color = Color(argb),
                    topLeft = Offset(0f, topY),
                    size = Size(w, h)
                )
            }

            drawRect(
                color = barColor.copy(alpha = 0.22f),
                topLeft = Offset(0f, animatedTop),
                size = Size(w, animatedHeight)
            )
            drawRect(
                color = barColor.copy(alpha = 0.9f),
                topLeft = Offset(0f, animatedTop),
                size = Size(w, animatedHeight),
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}
