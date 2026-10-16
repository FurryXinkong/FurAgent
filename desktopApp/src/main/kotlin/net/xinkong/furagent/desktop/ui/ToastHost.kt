package net.xinkong.furagent.desktop.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.xinkong.furagent.desktop.ui.theme.Radius
import net.xinkong.furagent.desktop.ui.theme.Shadow
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * 全局轻提示（右上角浮层）。
 *
 * 3 秒自动消失，也可点 × 提前关闭，最多同时堆叠 3 条。
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
        // 【体验优化 B24】5 秒内相同内容去重，避免密集报错时同一消息堆 3 条
        val now = System.currentTimeMillis()
        if (_messages.value.any { it.text == text && now - it.timestamp < 5000L }) {
            return
        }
        val msg = Message(UUID.randomUUID().toString(), text, level)
        val newList = (_messages.value + msg).takeLast(MAX_VISIBLE)
        _messages.value = newList
    }

    fun dismiss(id: String) {
        _messages.value = _messages.value.filterNot { it.id == id }
    }

    fun clear() {
        _messages.value = emptyList()
    }
}

@Composable
fun ToastHost(modifier: Modifier = Modifier) {
    val messages by ToastBus.messages.collectAsState()

    LazyColumn(
        modifier = modifier.widthIn(min = 240.dp, max = 380.dp),
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

@Composable
private fun ToastItem(msg: ToastBus.Message, onDismiss: () -> Unit) {
    LaunchedEffect(msg.id) {
        // 【体验优化 B12】错误级不自动消失（15s 兜底，避免永远堆着），
        // 用户手动点 × 才会关，这样重要错误不会被后续消息顶掉。
        val ttl = if (msg.level == ToastBus.Level.ERROR) 15_000L else 3_000L
        delay(ttl.milliseconds)
        onDismiss()
    }

    val (icon, tint) = when (msg.level) {
        ToastBus.Level.INFO -> Icons.Default.Info to AccentColor()
        ToastBus.Level.WARN -> Icons.Default.Warning to WarningColor()
        ToastBus.Level.ERROR -> Icons.Default.Error to ErrorColor()
    }

    Surface(
        color = CardColor(),
        shape = RoundedCornerShape(Radius.MD),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.4f)),
        shadowElevation = Shadow.LG,
        modifier = Modifier.widthIn(min = 240.dp, max = 380.dp)
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
                color = TextPrimary(),
                modifier = Modifier.weight(1f).padding(top = 1.dp)
            )
            Spacer(Modifier.width(8.dp))
            IconActionButton(
                Icons.Default.Close,
                onClick = onDismiss,
                tint = TextSecondary(),
                size = 20.dp
            )
        }
    }
}