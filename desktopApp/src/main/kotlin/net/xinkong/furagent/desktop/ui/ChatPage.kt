@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package net.xinkong.furagent.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.xinkong.furagent.desktop.data.DesktopMessage
import net.xinkong.furagent.desktop.system.DesktopSpeechHelper
import net.xinkong.furagent.desktop.ui.theme.Radius
import net.xinkong.furagent.desktop.ui.theme.Shadow
import net.xinkong.furagent.shared.util.formatDuration
import net.xinkong.furagent.shared.util.ChatListItem
import net.xinkong.furagent.shared.util.buildChatItems
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

data class PendingAttachment(
    val path: String,
    val name: String,
    val isImage: Boolean
)

private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
private val UNSUPPORTED_IMAGE_EXTS = setOf("heic", "heif", "avif", "tiff", "tif")
// 【2026-10-15 去重】图片扩展名正则已抽到 shared ImagePathExtractor，这里不再使用

object ChatFocusTracker {
    @Volatile var chatPageVisible: Boolean = false
}

/**
 * 送给 TTS 前清理文本。
 */
internal fun cleanForSpeech(text: String): String {
    var t = text
    t = t.replace(Regex("```[\\s\\S]*?```"), "")
    t = t.replace(Regex("`([^`\n]+)`"), "$1")
    t = t.replace(Regex("!\\[[^\\]]*]\\([^)]+\\)"), "")
    t = t.replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
    t = t.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
    t = t.replace(Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)"), "$1")
    t = t.replace(Regex("__([^_]+)__"), "$1")
    t = t.replace(Regex("_([^_]+)_"), "$1")
    t = t.replace(Regex("(?m)^\\s*#{1,6}\\s*"), "")
    t = t.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
    t = t.replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "")
    t = t.replace(Regex("https?://\\S+"), "")
    t = t.replace(Regex("(?:[A-Za-z]:[\\\\/]|/)(?:[^\\s/]+/)*[^\\s/]*"), "")
    t = t.replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+"), "")
    t = t.replace(Regex("[ \\t]+"), " ")
    t = t.replace(Regex("\\n{2,}"), "\n")
    t = t.trim()
    return if (t.length > 800) t.take(800) else t
}

// 【2026-10-15 去重】实现已抽到 shared ImagePathExtractor
private fun extractImagePathsFromContent(content: String): List<String> =
    net.xinkong.furagent.shared.util.ImagePathExtractor.extract(content)

private fun needsCollapse(content: String): Boolean {
    return content.isNotBlank() && (content.count { it == '\n' } >= 3 || content.length > 200)
}

// 【冗余清理 Batch 10】ChatListItem / buildChatItems 抽到 shared/util/ChatListItem.kt。
// Android 端（ChatUI.kt）有同样的结构，已一并合并。

@Composable
fun ChatPage(
    sessionId: String?,
    messages: List<DesktopMessage>,
    streamingText: String,
    thinking: String,
    isLoading: Boolean,
    inputText: String,
    hasApiKey: Boolean,
    modelName: String,
    scrollToMessageId: String? = null,
    onScrollToMessageHandled: () -> Unit = {},
    onInputChange: (String) -> Unit,
    onSend: (List<PendingAttachment>) -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    onCopyMessage: (DesktopMessage) -> Unit = {},
    onRegenerateMessage: (DesktopMessage) -> Unit = {},
    onEditAndRegenerate: (DesktopMessage) -> Unit = {},
    onReExecuteTool: (DesktopMessage) -> Unit = {},
    onDeleteMessage: (DesktopMessage) -> Unit = {}
) {
    var attachments by remember { mutableStateOf<List<PendingAttachment>>(emptyList()) }
    var bottomReservedPx by remember { mutableIntStateOf(140) }
    var viewingImage by remember { mutableStateOf<String?>(null) }

    val speechState by DesktopSpeechHelper.state.collectAsState()

    DisposableEffect(Unit) {
        ChatFocusTracker.chatPageVisible = true
        onDispose { ChatFocusTracker.chatPageVisible = false }
    }

    Box(Modifier.fillMaxSize()) {
        ChatMessageList(
            sessionId = sessionId,
            messages = messages,
            streamingText = streamingText,
            thinking = thinking,
            hasApiKey = hasApiKey,
            onOpenSettings = onOpenSettings,
            onCopyMessage = onCopyMessage,
            onRegenerateMessage = onRegenerateMessage,
            onEditAndRegenerate = onEditAndRegenerate,
            onReExecuteTool = onReExecuteTool,
            onDeleteMessage = onDeleteMessage,
            onImageClick = { viewingImage = it },
            bottomReservedPx = bottomReservedPx,
            scrollToMessageId = scrollToMessageId,
            onScrollToMessageHandled = onScrollToMessageHandled,
            modifier = Modifier.fillMaxSize()
        )

        val bgBottom = BgGradientBottom()
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(220.dp)
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.35f to bgBottom.copy(alpha = 0.35f),
                            0.65f to bgBottom.copy(alpha = 0.80f),
                            1.00f to bgBottom
                        )
                    )
                )
        )

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    if (bottomReservedPx != coords.size.height) {
                        bottomReservedPx = coords.size.height
                    }
                }
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.fillMaxWidth(0.75f).widthIn(max = 1800.dp)) {
                    ChatInputCard(
                        inputText = inputText,
                        onInputChange = onInputChange,
                        onSend = {
                            val current = attachments
                            attachments = emptyList()
                            onSend(current)
                        },
                        onStop = onStop,
                        isLoading = isLoading,
                        enabled = hasApiKey,
                        attachments = attachments,
                        onAttachmentsChange = { attachments = it },
                        onImageClick = { viewingImage = it }
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = speechState.speaking || speechState.error != null,
            enter = fadeIn(tween(180)) + slideInVertically(
                animationSpec = tween(240, easing = FastOutSlowInEasing),
                initialOffsetY = { -it }
            ),
            exit = fadeOut(tween(140)) + slideOutVertically(
                animationSpec = tween(200, easing = FastOutSlowInEasing),
                targetOffsetY = { -it }
            ),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 8.dp)
        ) {
            SpeechProgressBar(
                state = speechState,
                onStop = { DesktopSpeechHelper.stop() },
                onClearError = { DesktopSpeechHelper.clearError() }
            )
        }
    }

    viewingImage?.let { path ->
        ImageViewerDialog(path = path, onDismiss = { viewingImage = null })
    }
}

// ============================================================
// 朗读进度条
// ============================================================

@Composable
private fun SpeechProgressBar(
    state: DesktopSpeechHelper.State,
    onStop: () -> Unit,
    onClearError: () -> Unit
) {
    val shadowColor = glassShadowColor()
    val isError = state.error != null

    Box(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 900.dp)
            .shadow(
                elevation = Shadow.LG,
                shape = RoundedCornerShape(Radius.MD),
                clip = false,
                ambientColor = shadowColor,
                spotColor = shadowColor
            )
    ) {
        Surface(
            color = CardColor(),
            shape = RoundedCornerShape(Radius.MD),
            border = BorderStroke(
                1.dp,
                if (isError) ErrorColor().copy(alpha = 0.6f)
                else AccentColor().copy(alpha = 0.4f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isError) Icons.Default.Error else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        tint = if (isError) ErrorColor() else AccentColor(),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (isError) "朗读失败" else "正在朗读",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isError) ErrorColor() else AccentColor()
                    )
                    if (!isError) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "· ${(state.progress * 100).roundToInt()}%",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted()
                        )
                    }
                    Spacer(Modifier.weight(1f))

                    if (isError) {
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onClearError),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "关闭",
                                tint = TextSecondary(),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onStop),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "停止朗读",
                                tint = ErrorColor(),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                if (!isError) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = AccentColor(),
                        trackColor = BorderSubtle()
                    )
                }

                if (isError) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.error,
                        fontSize = 11.sp,
                        color = ErrorColor(),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                } else if (state.text.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.text,
                        fontSize = 11.sp,
                        color = TextSecondary(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ============================================================
// 消息列表
// ============================================================

@Composable
private fun ChatMessageList(
    sessionId: String?,
    messages: List<DesktopMessage>,
    streamingText: String,
    thinking: String,
    hasApiKey: Boolean,
    onOpenSettings: () -> Unit,
    onCopyMessage: (DesktopMessage) -> Unit,
    onRegenerateMessage: (DesktopMessage) -> Unit,
    onEditAndRegenerate: (DesktopMessage) -> Unit,
    onReExecuteTool: (DesktopMessage) -> Unit,
    onDeleteMessage: (DesktopMessage) -> Unit,
    onImageClick: (String) -> Unit,
    bottomReservedPx: Int,
    scrollToMessageId: String?,
    onScrollToMessageHandled: () -> Unit,
    modifier: Modifier = Modifier
) {
    key(sessionId) {
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current

        val bottomPadding = with(density) { (bottomReservedPx + 16).toDp() }

        val chatItems = remember(messages) { buildChatItems(messages) }

        val totalCount = chatItems.size +
                (if (thinking.isNotEmpty() && streamingText.isEmpty()) 1 else 0) +
                (if (streamingText.isNotEmpty()) 1 else 0)

        suspend fun jumpToBottom() {
            if (totalCount <= 0) return
            try {
                listState.scrollToItem(totalCount - 1)
                listState.scrollBy(100_000f)
            } catch (_: Exception) { }
        }

        LaunchedEffect(Unit) {
            if (messages.isNotEmpty() || totalCount > 0) {
                kotlinx.coroutines.delay(30.milliseconds)
                jumpToBottom()
            }
        }

        LaunchedEffect(scrollToMessageId, chatItems.size) {
            if (scrollToMessageId != null) {
                val idx = chatItems.indexOfFirst {
                    (it is ChatListItem.Single && it.msg.id == scrollToMessageId) ||
                            (it is ChatListItem.ToolsGroup &&
                                    it.items.any { m -> m.id == scrollToMessageId })
                }
                if (idx >= 0) {
                    try {
                        listState.animateScrollToItem(idx)
                    } catch (_: Exception) { }
                }
                onScrollToMessageHandled()
            }
        }

        var lastItemCount by remember { mutableIntStateOf(0) }

        // 【滚动修复】是否在底部：用 canScrollForward 判断更可靠。
        // 必须声明在下面的自动滚动 LaunchedEffect 之前。
        val isAtBottom by remember {
            derivedStateOf {
                val info = listState.layoutInfo
                if (info.totalItemsCount == 0) return@derivedStateOf true
                !listState.canScrollForward
            }
        }

        LaunchedEffect(chatItems.size) {
            if (chatItems.size > lastItemCount) {
                lastItemCount = chatItems.size
                // 【滚动修复】只有用户本来就在底部时才自动跟到底。
                // 否则工具调用/新消息会把正在上滑看历史的用户强行拽回底部。
                if (scrollToMessageId == null && isAtBottom) jumpToBottom()
            } else {
                lastItemCount = chatItems.size
            }
        }

        LaunchedEffect(totalCount, streamingText.length, thinking.length) {
            if (isAtBottom && totalCount > 0) {
                try { listState.scrollToItem(totalCount - 1) } catch (_: Exception) { }
                try { listState.scrollBy(100_000f) } catch (_: Exception) { }
            }
        }

        val latestKey = chatItems.lastOrNull()?.key

        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(end = 24.dp),
                contentPadding = PaddingValues(
                    start = 32.dp,
                    end = 32.dp,
                    top = 24.dp,
                    bottom = bottomPadding
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (chatItems.isEmpty() && streamingText.isEmpty() && thinking.isEmpty()) {
                    item { EmptyChatState(hasApiKey = hasApiKey, onOpenSettings = onOpenSettings) }
                }

                items(chatItems, key = { it.key }) { item ->
                    val isLatest = item.key == latestKey
                    var appeared by remember(item.key) { mutableStateOf(!isLatest) }
                    LaunchedEffect(item.key, isLatest) {
                        if (isLatest) appeared = true
                    }

                    Box(
                        Modifier.animateItem(
                            fadeInSpec = tween(220, easing = FastOutSlowInEasing),
                            placementSpec = tween(260, easing = FastOutSlowInEasing),
                            fadeOutSpec = null
                        )
                    ) {
                        if (isLatest && !appeared) {
                            Box { }
                        } else {
                            when (item) {
                                is ChatListItem.Single -> {
                                    MessageItemWithMenu(
                                        msg = item.msg,
                                        onCopy = onCopyMessage,
                                        onRegenerate = onRegenerateMessage,
                                        onEditAndRegenerate = onEditAndRegenerate,
                                        onReExecute = onReExecuteTool,
                                        onDelete = onDeleteMessage,
                                        onImageClick = onImageClick
                                    )
                                }
                                is ChatListItem.ToolsGroup -> {
                                    ToolsGroupRow(
                                        group = item,
                                        onCopy = onCopyMessage,
                                        onReExecute = onReExecuteTool,
                                        onDelete = onDeleteMessage,
                                        onImageClick = onImageClick
                                    )
                                }
                            }
                        }
                    }
                }

                if (thinking.isNotEmpty() && streamingText.isEmpty()) {
                    item {
                        Box(Modifier.animateItem(fadeOutSpec = null)) {
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(tween(180)) +
                                        slideInVertically(
                                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                                            initialOffsetY = { it / 4 }
                                        ),
                                exit = fadeOut(tween(140))
                            ) {
                                StatusRow(thinking)
                            }
                        }
                    }
                }

                if (streamingText.isNotEmpty()) {
                    item {
                        Box(Modifier.animateItem(fadeOutSpec = null)) {
                            TextMessageRow(
                                DesktopMessage(role = "assistant", content = streamingText, type = "text"),
                                isStreaming = true,
                                onImageClick = onImageClick
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = !isAtBottom && (chatItems.isNotEmpty() || streamingText.isNotEmpty()),
                enter = fadeIn(tween(180)) +
                        slideInVertically(
                            animationSpec = tween(240, easing = FastOutSlowInEasing),
                            initialOffsetY = { it / 2 }
                        ) +
                        expandVertically(tween(240)),
                exit = fadeOut(tween(140)) +
                        shrinkVertically(tween(180)) +
                        slideOutVertically(
                            animationSpec = tween(200, easing = FastOutSlowInEasing),
                            targetOffsetY = { it / 2 }
                        ),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 40.dp,
                        bottom = with(density) { (bottomReservedPx + 20).toDp() }
                    )
            ) {
                ExtendedFloatingActionButton(
                    onClick = {
                        scope.launch {
                            try {
                                if (totalCount > 0) {
                                    listState.animateScrollToItem(totalCount - 1)
                                    listState.animateScrollBy(100_000f)
                                }
                            } catch (_: Exception) { }
                        }
                    },
                    icon = { Icon(Icons.Default.VerticalAlignBottom, "回到底部") },
                    text = { Text("最新") },
                    containerColor = AccentColor(),
                    contentColor = OnAccent()
                )
            }

            if (chatItems.size >= 6) {
                MiniMap(
                    listState = listState,
                    chatItems = chatItems,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(
                            end = 6.dp,
                            top = 24.dp,
                            bottom = with(density) { (bottomReservedPx + 16).toDp() }
                        )
                )
            }
        }
    }
}

// ============================================================
// Minimap
// ============================================================

@Composable
private fun MiniMap(
    listState: androidx.compose.foundation.lazy.LazyListState,
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
            if (chatItems.isEmpty() || info.totalItemsCount == 0) return@derivedStateOf 0f to 1f
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

    val barColor = AccentColor()
    // 视觉宽度 16dp，热区宽度 28dp（左右各多 6dp）
    val visualWidth = 16.dp
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
                            PointerEventType.Exit -> { hovered = false; dragging = false }
                            PointerEventType.Press -> {
                                dragging = true
                                val y = change.position.y
                                val ratio = (y / trackHeightPx).coerceIn(0f, 1f)
                                val targetIndex = (ratio * (chatItems.size - 1)).roundToInt()
                                    .coerceIn(0, chatItems.size - 1)
                                scope.launch {
                                    try { listState.scrollToItem(targetIndex) } catch (_: Exception) { }
                                }
                                change.consume()
                            }
                            PointerEventType.Move -> {
                                // 仅在按下拖动时响应，避免悬停误触
                                if (dragging) {
                                    val y = change.position.y
                                    val ratio = (y / trackHeightPx).coerceIn(0f, 1f)
                                    val targetIndex = (ratio * (chatItems.size - 1)).roundToInt()
                                        .coerceIn(0, chatItems.size - 1)
                                    scope.launch {
                                        try { listState.scrollToItem(targetIndex) } catch (_: Exception) { }
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
        Box(
            modifier = Modifier
                .width(visualWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(Radius.XS))
                .background(CardColor().copy(alpha = if (hovered) 0.85f else 0.5f))
                .border(
                    1.dp,
                    BorderColor().copy(alpha = if (hovered) 0.9f else 0.5f),
                    RoundedCornerShape(Radius.XS)
                )
                .onGloballyPositioned { coords ->
                    trackHeightPx = coords.size.height.coerceAtLeast(1)
                }
        ) {
            val total = chatItems.size.toFloat().coerceAtLeast(1f)

            chatItems.forEachIndexed { idx, item ->
                val topRatio = idx / total
                val bottomRatio = (idx + 1) / total
                val blockHeight = ((bottomRatio - topRatio) * trackHeightPx).coerceAtLeast(1.5f)
                val blockColor = when (item) {
                    is ChatListItem.Single -> {
                        val msg = item.msg
                        when {
                            msg.role == "user" -> AccentColor().copy(alpha = 0.75f)
                            else -> CardStrongColor()
                        }
                    }
                    is ChatListItem.ToolsGroup -> ToolColor().copy(alpha = 0.75f)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .offset(y = with(LocalDensity.current) { (topRatio * trackHeightPx).toDp() })
                        .height(with(LocalDensity.current) { blockHeight.toDp() })
                        .background(blockColor)
                )
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .offset(y = with(LocalDensity.current) { animatedTop.toDp() })
                    .height(with(LocalDensity.current) { animatedHeight.toDp() })
                    .background(barColor.copy(alpha = 0.22f))
                    .border(1.dp, barColor.copy(alpha = 0.9f), RoundedCornerShape(3.dp))
            )
        }
    }
}

// ============================================================
// 消息组折叠
// ============================================================

@Composable
private fun ToolsGroupRow(
    group: ChatListItem.ToolsGroup,
    onCopy: (DesktopMessage) -> Unit,
    onReExecute: (DesktopMessage) -> Unit,
    onDelete: (DesktopMessage) -> Unit,
    onImageClick: (String) -> Unit
) {
    var expanded by remember(group.groupKey) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Surface(
            color = CardColor(),
            shape = RoundedCornerShape(Radius.MD),
            border = BorderStroke(1.dp, ToolColor().copy(alpha = 0.3f)),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.MD))
                .clickable { expanded = !expanded }
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Build,
                    contentDescription = null,
                    tint = ToolColor(),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    buildString {
                        append("${group.toolCallCount} 次工具调用")
                        if (group.toolResultCount != group.toolCallCount) {
                            append(" · ${group.toolResultCount} 条返回")
                        }
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary()
                )
                if (group.durationMs > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "· 耗时 ${formatDuration(group.durationMs)}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted()
                    )
                }
                Spacer(Modifier.weight(1f))
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
                    label = "toolsGroupArrow"
                )
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = TextSecondary(),
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { rotationZ = rotation }
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(240, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Top
            ) + fadeIn(tween(180)),
            exit = shrinkVertically(
                animationSpec = tween(200, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Top
            ) + fadeOut(tween(140))
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                group.items.forEach { msg ->
                    MessageItemWithMenu(
                        msg = msg,
                        onCopy = onCopy,
                        onRegenerate = {},
                        onEditAndRegenerate = {},
                        onReExecute = onReExecute,
                        onDelete = onDelete,
                        onImageClick = onImageClick
                    )
                }
            }
        }
    }
}

// ============================================================
// 消息 + 右键菜单
// ============================================================

@Composable
private fun MessageItemWithMenu(
    msg: DesktopMessage,
    onCopy: (DesktopMessage) -> Unit,
    onRegenerate: (DesktopMessage) -> Unit,
    onEditAndRegenerate: (DesktopMessage) -> Unit,
    onReExecute: (DesktopMessage) -> Unit,
    onDelete: (DesktopMessage) -> Unit,
    onImageClick: (String) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(Offset.Zero) }

    Box {
        Box(
            Modifier.pointerInput(msg.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            val pos = event.changes.firstOrNull()?.position ?: Offset.Zero
                            menuOffset = pos
                            menuOpen = true
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
        ) {
            MessageRow(msg, onImageClick)
        }

        Box(
            Modifier
                .offset {
                    IntOffset(menuOffset.x.toInt(), menuOffset.y.toInt())
                }
                .size(1.dp)
        ) {
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = CardColor()
            ) {
                DropdownMenuItem(
                    text = { Text("复制", fontSize = 12.sp, color = TextPrimary()) },
                    onClick = { menuOpen = false; onCopy(msg) }
                )

                // 朗读此消息（仅 assistant 的 text 消息）
                if (msg.role == "assistant" && msg.type == "text" && msg.content.isNotBlank()) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = null,
                                    tint = TextSecondary(),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("朗读此消息", fontSize = 12.sp, color = TextPrimary())
                            }
                        },
                        onClick = {
                            menuOpen = false
                            val clean = cleanForSpeech(msg.content)
                            if (clean.isNotBlank()) {
                                DesktopSpeechHelper.speak(clean)
                            }
                        }
                    )
                }

                if (msg.type == "tool_call") {
                    DropdownMenuItem(
                        text = { Text("再次执行", fontSize = 12.sp, color = TextPrimary()) },
                        onClick = { menuOpen = false; onReExecute(msg) }
                    )
                }
                // 【体验优化 B7/B20】任意 text 类型都可编辑
                if (msg.type == "text") {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (msg.role == "user") "编辑并重新生成" else "编辑此消息内容",
                                fontSize = 12.sp, color = TextPrimary()
                            )
                        },
                        onClick = { menuOpen = false; onEditAndRegenerate(msg) }
                    )
                }
                if (msg.role == "assistant" && msg.type == "text") {
                    DropdownMenuItem(
                        text = { Text("重新生成", fontSize = 12.sp, color = TextPrimary()) },
                        onClick = { menuOpen = false; onRegenerate(msg) }
                    )
                }
                DropdownMenuItem(
                    text = { Text("删除", fontSize = 12.sp, color = ErrorColor()) },
                    onClick = { menuOpen = false; onDelete(msg) }
                )
            }
        }
    }
}

// ============================================================
// 空状态
// ============================================================

@Composable
private fun EmptyChatState(hasApiKey: Boolean, onOpenSettings: () -> Unit) {
    val infinite = rememberInfiniteTransition(label = "empty")
    val scale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.035f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "emptyScale"
    )

    Column(
        Modifier.fillMaxWidth().padding(vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(80.dp))
        Text(
            "FurAgent",
            fontSize = 42.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-1.5).sp,
            color = TextPrimary(),
            modifier = Modifier.scale(scale)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "办公、创作、查资料，交给我就好",
            fontSize = 14.sp,
            color = TextSecondary()
        )

        if (!hasApiKey) {
            Spacer(Modifier.height(32.dp))
            GlassShadow(elevation = Shadow.MD, shape = RoundedCornerShape(Radius.LG)) {
                Surface(
                    color = CardColor(),
                    shape = RoundedCornerShape(Radius.LG),
                    border = BorderStroke(1.dp, BorderColor()),
                    modifier = Modifier.width(400.dp)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            "尚未配置 API Key",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = WarningColor()
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "请先在设置中填入模型服务的 API Key",
                            fontSize = 12.sp,
                            color = TextSecondary()
                        )
                        Spacer(Modifier.height(14.dp))
                        PrimaryButton(text = "打开设置") { onOpenSettings() }
                    }
                }
            }
        } else {
            Spacer(Modifier.height(16.dp))
            Text(
                "试试问我：你能做什么？",
                fontSize = 13.sp,
                color = TextMuted()
            )
        }
    }
}

// ============================================================
// 消息分发
// ============================================================

@Composable
private fun MessageRow(msg: DesktopMessage, onImageClick: (String) -> Unit) {
    when (msg.type) {
        "tool_call" -> ToolCallRow(msg)
        "tool_result" -> ToolResultRow(msg, onImageClick)
        "system_note" -> SystemNoteRow(msg)
        else -> TextMessageRow(msg, onImageClick = onImageClick)
    }
}

// ============================================================
// 文本气泡
// ============================================================

@Composable
private fun TextMessageRow(
    msg: DesktopMessage,
    isStreaming: Boolean = false,
    onImageClick: (String) -> Unit = {}
) {
    val isUser = msg.role == "user"
    val shadowColor = glassShadowColor()

    val contentImages = remember(msg.content) {
        extractImagePathsFromContent(msg.content)
    }
    val allImages = remember(msg.imagePaths, contentImages) {
        (msg.imagePaths + contentImages).distinct()
    }
    val displayContent = remember(msg.content, contentImages) {
        if (contentImages.isEmpty()) msg.content
        else {
            var t = msg.content
            contentImages.forEach { p ->
                t = t.replace(Regex("""!\[[^\]]*]\(${Regex.escape(p)}\)"""), "")
                t = t.lines().filter { line ->
                    line.trim().trim('`', '*', '"', '\'').removePrefix("file://") != p
                }.joinToString("\n")
            }
            t.trim()
        }
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            Modifier.widthIn(max = 1000.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Box(
                Modifier.shadow(
                    elevation = Shadow.MD,
                    shape = RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    ),
                    clip = false,
                    ambientColor = shadowColor,
                    spotColor = shadowColor
                )
            ) {
                Surface(
                    color = if (isUser) AccentColor() else CardColor(),
                    shape = RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    ),
                    border = if (isUser) null else BorderStroke(1.dp, BorderColor()),
                    contentColor = if (isUser) OnAccent() else TextPrimary()
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
                        if (displayContent.isNotBlank()) {
                            SelectionContainer {
                                MarkdownText(
                                    text = displayContent,
                                    color = if (isUser) OnAccent() else TextPrimary()
                                )
                            }
                        }
                        if (allImages.isNotEmpty()) {
                            if (displayContent.isNotBlank()) Spacer(Modifier.height(8.dp))
                            allImages.forEach { path ->
                                MessageImage(path) { onImageClick(path) }
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }
            if (!isStreaming) {
                Spacer(Modifier.height(4.dp))
                Text(
                    formatTime(msg.timestamp),
                    fontSize = 11.sp,
                    color = TextMuted(),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

// ============================================================
// 图片展示
// ============================================================

@Composable
private fun MessageImage(path: String, onClick: () -> Unit = {}) {
    val ext = path.substringAfterLast('.', "").lowercase()
    val unsupported = ext in UNSUPPORTED_IMAGE_EXTS
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            try {
                if (unsupported) return@withContext null
                val img = javax.imageio.ImageIO.read(File(path)) ?: return@withContext null
                val maxDim = 320
                val scale = minOf(maxDim.toFloat() / img.width, maxDim.toFloat() / img.height, 1f)
                val w = (img.width * scale).toInt().coerceAtLeast(1)
                val h = (img.height * scale).toInt().coerceAtLeast(1)
                val buffered = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                val g = buffered.createGraphics()
                g.drawImage(img, 0, 0, w, h, null)
                g.dispose()
                buffered.toComposeImageBitmap()
            } catch (_: Exception) { null }
        }
    }

    val bmp = bitmap
    when {
        unsupported -> Text(
            "（$ext 格式不支持，请用 JPG/PNG/WEBP）",
            fontSize = 11.sp, color = ErrorColor(), modifier = Modifier.padding(4.dp)
        )
        bmp != null -> Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = Modifier
                .heightIn(max = 320.dp)
                .clip(RoundedCornerShape(Radius.XS))
                .clickable(onClick = onClick),
            contentScale = ContentScale.Fit
        )
        else -> Text(
            "（图片加载失败：$path）",
            fontSize = 11.sp, color = ErrorColor(), modifier = Modifier.padding(4.dp)
        )
    }
}

// ============================================================
// 工具调用气泡
// ============================================================
//
// 【B1 修复】原来外层 Row 上挂 clickable { expanded = !expanded }，
// 会拦截所有 pointer 事件，SelectionContainer 拿不到手势/键盘焦点，
// 导致"能选中但复制不了、Ctrl+C 无效、右键菜单也失效"。
// 现在把展开/收起改到右上角的箭头 Box 上独立可点，外层不再拦截。

@Composable
private fun ToolCallRow(msg: DesktopMessage) {
    val shadowColor = glassShadowColor()
    var expanded by remember(msg.id) { mutableStateOf(false) }
    val collapsible = remember(msg.content) { needsCollapse(msg.content) }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            Modifier.shadow(
                elevation = Shadow.MD,
                shape = RoundedCornerShape(Radius.MD),
                clip = false,
                ambientColor = shadowColor,
                spotColor = shadowColor
            )
        ) {
            Row(
                Modifier
                    .widthIn(max = 1000.dp)
                    .clip(RoundedCornerShape(Radius.MD))
                    .background(CardColor())
                    .border(1.dp, ToolColor().copy(alpha = 0.3f), RoundedCornerShape(Radius.MD))
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = 0.78f,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
            ) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(ToolColor()))
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "工具调用",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = ToolColor()
                        )
                        if (collapsible && !expanded) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "共 ${msg.content.length} 字符",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextMuted()
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (collapsible) {
                            val rotation by animateFloatAsState(
                                targetValue = if (expanded) 180f else 0f,
                                animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
                                label = "toolCallArrow"
                            )
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(Radius.XS))
                                    .clickable { expanded = !expanded },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.ExpandMore,
                                    contentDescription = if (expanded) "收起" else "展开",
                                    tint = TextSecondary(),
                                    modifier = Modifier
                                        .size(16.dp)
                                        .graphicsLayer { rotationZ = rotation }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        Text(
                            msg.content,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary(),
                            lineHeight = 18.sp,
                            maxLines = if (expanded || !collapsible) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolResultRow(msg: DesktopMessage, onImageClick: (String) -> Unit) {
    val shadowColor = glassShadowColor()
    var expanded by remember(msg.id) { mutableStateOf(false) }

    val images = remember(msg.content) { extractImagePathsFromContent(msg.content) }
    val displayContent = remember(msg.content, images) {
        if (images.isEmpty()) msg.content
        else {
            var t = msg.content
            images.forEach { p ->
                t = t.replace(Regex("""!\[[^\]]*]\(${Regex.escape(p)}\)"""), "")
                t = t.lines().filter { line ->
                    line.trim().trim('`', '*', '"', '\'').removePrefix("file://") != p
                }.joinToString("\n")
            }
            t.trim()
        }
    }

    val collapsible = remember(displayContent) { needsCollapse(displayContent) }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            Modifier.shadow(
                elevation = Shadow.MD,
                shape = RoundedCornerShape(Radius.MD),
                clip = false,
                ambientColor = shadowColor,
                spotColor = shadowColor
            )
        ) {
            Row(
                Modifier
                    .widthIn(max = 1000.dp)
                    .clip(RoundedCornerShape(Radius.MD))
                    .background(CardColor())
                    .border(1.dp, ResultColor().copy(alpha = 0.3f), RoundedCornerShape(Radius.MD))
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = 0.78f,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
            ) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(ResultColor()))
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "工具返回",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = ResultColor()
                        )
                        if (msg.durationMs > 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                formatDuration(msg.durationMs),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextMuted()
                            )
                        }
                        if (collapsible && !expanded) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "共 ${displayContent.length} 字符",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextMuted()
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (collapsible) {
                            val rotation by animateFloatAsState(
                                targetValue = if (expanded) 180f else 0f,
                                animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
                                label = "toolResultArrow"
                            )
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(Radius.XS))
                                    .clickable { expanded = !expanded },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.ExpandMore,
                                    contentDescription = if (expanded) "收起" else "展开",
                                    tint = TextSecondary(),
                                    modifier = Modifier
                                        .size(16.dp)
                                        .graphicsLayer { rotationZ = rotation }
                                )
                            }
                        }
                    }
                    if (displayContent.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        SelectionContainer {
                            Text(
                                displayContent,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimary(),
                                lineHeight = 18.sp,
                                maxLines = if (expanded || !collapsible) Int.MAX_VALUE else 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (images.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        images.forEach { path ->
                            MessageImage(path) { onImageClick(path) }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// 系统提示胶囊
// ============================================================

@Composable
private fun SystemNoteRow(msg: DesktopMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(CardStrongColor())
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(msg.content, fontSize = 11.sp, color = TextSecondary())
        }
    }
}

// ============================================================
// 思考状态行
// ============================================================

@Composable
private fun StatusRow(text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = AccentColor()
        )
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 12.sp, color = TextSecondary())
    }
}

// ============================================================
// 输入卡片
// ============================================================

@Composable
private fun ChatInputCard(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isLoading: Boolean,
    enabled: Boolean,
    attachments: List<PendingAttachment>,
    onAttachmentsChange: (List<PendingAttachment>) -> Unit,
    onImageClick: (String) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }

    var fieldValue by remember {
        mutableStateOf(
            TextFieldValue(inputText, TextRange(inputText.length))
        )
    }
    LaunchedEffect(inputText) {
        if (fieldValue.text != inputText) {
            fieldValue = TextFieldValue(inputText, TextRange(inputText.length))
        }
    }

    // 全局 Ctrl+V 图片粘贴
    //
    // 【修复】DisposableEffect(Unit) 只注册一次，闭包会捕获首次渲染时的
    // attachments / onAttachmentsChange。第二次粘贴时 attachments 还是旧值（空），
    // 导致“attachments + 新图”覆盖成一张。用 rememberUpdatedState 读最新值。
    val currentAttachments by rememberUpdatedState(attachments)
    val currentOnAttachmentsChange by rememberUpdatedState(onAttachmentsChange)
    DisposableEffect(Unit) {
        val dispatcher = object : java.awt.KeyEventDispatcher {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (!ChatFocusTracker.chatPageVisible) return false
                if (e.id == KeyEvent.KEY_PRESSED &&
                    e.keyCode == KeyEvent.VK_V &&
                    (e.modifiersEx and InputEvent.CTRL_DOWN_MASK) != 0
                ) {
                    val img = SafeClipboard.getImage() ?: return false
                    try {
                        val w = img.getWidth(null)
                        val h = img.getHeight(null)
                        if (w <= 0 || h <= 0) return false
                        val tmp = File.createTempFile("paste_", ".png")
                        val buffered = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                        val g = buffered.createGraphics()
                        g.drawImage(img, 0, 0, null)
                        g.dispose()
                        javax.imageio.ImageIO.write(buffered, "png", tmp)
                        currentOnAttachmentsChange(
                            currentAttachments + PendingAttachment(tmp.absolutePath, tmp.name, true)
                        )
                        return true
                    } catch (ex: Exception) {
                        lastError = "粘贴失败：${ex.message}"
                        return false
                    }
                }
                return false
            }
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
        onDispose {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
        }
    }

    fun pickImages() {
        val chooser = JFileChooser().apply {
            dialogTitle = "选择图片"
            isMultiSelectionEnabled = true
            fileFilter = FileNameExtensionFilter("图片 (PNG/JPG/WEBP/GIF/BMP)",
                "png", "jpg", "jpeg", "gif", "webp", "bmp")
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            val files = chooser.selectedFiles ?: return
            onAttachmentsChange(attachments + files.map {
                PendingAttachment(path = it.absolutePath, name = it.name, isImage = true)
            })
        }
    }

    fun pickFiles() {
        val chooser = JFileChooser().apply {
            dialogTitle = "选择文件"
            isMultiSelectionEnabled = true
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            val files = chooser.selectedFiles ?: return
            onAttachmentsChange(attachments + files.map {
                val ext = it.extension.lowercase()
                PendingAttachment(path = it.absolutePath, name = it.name, isImage = ext in IMAGE_EXTS)
            })
        }
    }

    val shadowColor = glassShadowColor()
    Box(
        Modifier
            .fillMaxWidth()
            .shadow(
                elevation = Shadow.XL,
                shape = RoundedCornerShape(Radius.LG),
                clip = false,
                ambientColor = shadowColor,
                spotColor = shadowColor
            )
    ) {
        Surface(
            color = CardColor(),
            shape = RoundedCornerShape(Radius.LG),
            border = BorderStroke(
                1.dp,
                if (focused) AccentColor().copy(alpha = 0.65f)
                else BorderColor()
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
                AnimatedVisibility(
                    visible = attachments.isNotEmpty(),
                    enter = fadeIn(tween(180)) +
                            expandVertically(
                                animationSpec = tween(220, easing = FastOutSlowInEasing),
                                expandFrom = Alignment.Top
                            ),
                    exit = fadeOut(tween(140)) +
                            shrinkVertically(
                                animationSpec = tween(180, easing = FastOutSlowInEasing),
                                shrinkTowards = Alignment.Top
                            )
                ) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        attachments.forEach { att ->
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(tween(160)) +
                                        expandVertically(tween(200)) +
                                        expandHorizontally(tween(200)),
                                exit = fadeOut(tween(120)) +
                                        shrinkVertically(tween(160)) +
                                        shrinkHorizontally(tween(160))
                            ) {
                                AttachmentThumb(
                                    att = att,
                                    onClick = { if (att.isImage) onImageClick(att.path) },
                                    onRemove = { onAttachmentsChange(attachments - att) }
                                )
                            }
                        }
                    }
                }

                BasicTextField(
                    value = fieldValue,
                    onValueChange = { newValue ->
                        fieldValue = newValue
                        if (enabled) onInputChange(newValue.text)
                    },
                    enabled = enabled,
                    textStyle = TextStyle(color = TextPrimary(), fontSize = 14.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(AccentColor()),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp, max = 180.dp)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            // 换行必须"按一次生效一次"。
                            //
                            // 旧实现：Shift+Enter 时手动插 \n 并返回 true（消费事件）。
                            // 但 BasicTextField 在 Desktop 平台对 Enter 有自己的处理，
                            // 返回 true 只阻止事件向上冒泡，拦不住它内部的默认行为，
                            // 结果手动插入的 \n 被覆盖 → 要按两次才生效。
                            //
                            // 新实现：
                            //   - 纯 Enter（无 Shift）→ 拦截，发送
                            //   - Shift+Enter        → 返回 false，交给 BasicTextField 自己插换行
                            //   - 其它键             → 返回 false
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                if (event.isShiftPressed) {
                                    false
                                } else {
                                    if (!isLoading &&
                                        (fieldValue.text.isNotBlank() || attachments.isNotEmpty())
                                    ) {
                                        onSend()
                                    }
                                    true
                                }
                            } else {
                                false
                            }
                        },
                    maxLines = 10,
                    decorationBox = { inner ->
                        Box {
                            if (fieldValue.text.isEmpty()) {
                                Text(
                                    if (enabled) "发消息…（Shift+Enter 换行）"
                                    else "请先在设置中配置 API Key",
                                    fontSize = 14.sp, color = TextMuted()
                                )
                            }
                            inner()
                        }
                    }
                )

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        IconActionButton(
                            Icons.Default.Add,
                            onClick = { menuOpen = true },
                            contentDescription = "更多",
                            tint = TextSecondary()
                        )
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            containerColor = CardColor()
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Image,
                                            null,
                                            tint = TextSecondary(),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text("选择图片", fontSize = 13.sp, color = TextPrimary())
                                    }
                                },
                                onClick = { menuOpen = false; pickImages() }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.InsertDriveFile,
                                            null,
                                            tint = TextSecondary(),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text("选择文件", fontSize = 13.sp, color = TextPrimary())
                                    }
                                },
                                onClick = { menuOpen = false; pickFiles() }
                            )
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    if (isLoading) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(ErrorColor())
                                .clickable(onClick = onStop),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                "停止",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        val canSend = (fieldValue.text.isNotBlank() || attachments.isNotEmpty()) && enabled
                        Box(
                            Modifier.size(36.dp).clip(CircleShape)
                                .background(if (canSend) AccentColor() else CardStrongColor())
                                .clickable(enabled = canSend) { onSend() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send, "发送",
                                tint = if (canSend) OnAccent() else TextMuted(),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                lastError?.let { msg ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            msg,
                            fontSize = 11.sp,
                            color = ErrorColor(),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "×",
                            fontSize = 14.sp,
                            color = TextSecondary(),
                            modifier = Modifier.clickable { lastError = null }
                        )
                    }
                }
            }
        }
    }
}

// ============================================================
// 附件缩略图
// ============================================================

@Composable
private fun AttachmentThumb(
    att: PendingAttachment,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Box(
        Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(Radius.SM))
            .background(CardStrongColor())
            .border(1.dp, BorderColor(), RoundedCornerShape(Radius.SM))
    ) {
        if (att.isImage) {
            val bitmap by produceState<ImageBitmap?>(null, att.path) {
                value = withContext(Dispatchers.IO) {
                    try {
                        val img = javax.imageio.ImageIO.read(File(att.path))
                            ?: return@withContext null
                        val maxDim = 128
                        val scale = minOf(
                            maxDim.toFloat() / img.width,
                            maxDim.toFloat() / img.height,
                            1f
                        )
                        val w = (img.width * scale).toInt().coerceAtLeast(1)
                        val h = (img.height * scale).toInt().coerceAtLeast(1)
                        val buffered = java.awt.image.BufferedImage(
                            w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB
                        )
                        val g = buffered.createGraphics()
                        g.drawImage(img, 0, 0, w, h, null)
                        g.dispose()
                        buffered.toComposeImageBitmap()
                    } catch (_: Exception) { null }
                }
            }
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = att.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clickable(onClick = onClick)
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Image,
                        null,
                        tint = TextMuted(),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.AutoMirrored.Filled.InsertDriveFile,
                        null,
                        tint = TextSecondary(),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        att.name.take(6),
                        fontSize = 8.sp, color = TextMuted(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Close,
                "移除",
                tint = Color.White,
                modifier = Modifier.size(11.dp)
            )
        }
    }
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))