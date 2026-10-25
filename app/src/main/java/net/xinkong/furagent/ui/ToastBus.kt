package net.xinkong.furagent.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.xinkong.furagent.ui.theme.AppRadius
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * 全局轻提示（右上角浮层），从桌面端 ToastBus 移植（P1-13）。
 *
 * 为什么 Android 端要这个：
 *  - 系统 Toast 位置固定（底部）、不可堆叠、不支持 error 级差异化
 *  - Snackbar 需要 Scaffold 承载，无法覆盖整屏 Page
 *  - 桌面端已经验证了浮层模式，双端统一体验
 *
 * 3 秒自动消失（ERROR 级 15 秒），可点 × 提前关闭，最多同时堆叠 3 条。
 * 5 秒内相同内容去重，避免密集报错时同一消息堆 3 条。
 */
object ToastBus {

    enum class Level { INFO, WARN, ERROR }

    data class Message(
        val id: String,
        val text: String,
        val level: Level,
        val timestamp: Long = System.currentTimeMillis()
    )

    private const val MAX_VISIBLE = 3

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    fun info(text: String) = push(text, Level.INFO)
    fun warn(text: String) = push(text, Level.WARN)
    fun error(text: String) = push(text, Level.ERROR)

    fun push(text: String, level: Level = Level.INFO) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        if (_messages.value.any { it.text == text && now - it.timestamp < 5000L }) {
            return
        }
        val msg = Message(UUID.randomUUID().toString(), text, level)
        _messages.value = (_messages.value + msg).takeLast(MAX_VISIBLE)
    }

    fun dismiss(id: String) {
        _messages.value = _messages.value.filterNot { it.id == id }
    }

    fun clear() {
        _messages.value = emptyList()
    }
}

/**
 * 浮层宿主。挂在 MainActivity 最外层 Box 的右上角，`Modifier.align(Alignment.TopEnd)`。
 */
@Composable
fun ToastHost(
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopEnd
) {
    val messages by ToastBus.messages.collectAsState()
    if (messages.isEmpty()) return

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = alignment
    ) {
        LazyColumn(
            modifier = modifier.widthIn(min = 220.dp, max = 340.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            items(
                count = messages.size,
                key = { idx -> messages[idx].id }
            ) { idx ->
                val msg = messages[idx]
                Box(
                    Modifier.animateItem(
                        fadeInSpec = tween(220),
                        placementSpec = tween(260),
                        fadeOutSpec = tween(180)
                    )
                ) {
                    ToastItem(msg, onDismiss = { ToastBus.dismiss(msg.id) })
                }
            }
        }
    }
}

@Composable
private fun ToastItem(msg: ToastBus.Message, onDismiss: () -> Unit) {
    LaunchedEffect(msg.id) {
        val ttl = if (msg.level == ToastBus.Level.ERROR) 15_000L else 3_000L
        delay(ttl.milliseconds)
        onDismiss()
    }

    val (icon, tint) = when (msg.level) {
        ToastBus.Level.INFO -> Icons.Default.Info to MaterialTheme.colorScheme.primary
        ToastBus.Level.WARN -> Icons.Default.Warning to WarningColor()
        ToastBus.Level.ERROR -> Icons.Default.Error to MaterialTheme.colorScheme.error
    }

    Surface(
        color = glassFloatingBg(),
        shape = RoundedCornerShape(AppRadius.MD),
        border = BorderStroke(0.5.dp, tint.copy(alpha = 0.45f)),
        shadowElevation = 6.dp,
        modifier = Modifier.widthIn(min = 220.dp, max = 340.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                msg.text,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(top = 1.dp)
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(AppRadius.TINY))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
