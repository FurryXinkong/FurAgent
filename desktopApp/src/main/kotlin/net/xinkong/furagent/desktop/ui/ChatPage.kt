@file:OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package net.xinkong.furagent.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
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
import net.xinkong.furagent.desktop.data.DesktopSessionStore
import net.xinkong.furagent.desktop.system.DesktopSpeechHelper
import net.xinkong.furagent.desktop.system.NativeFilePicker
import net.xinkong.furagent.desktop.tools.DesktopTools.SubSessionProgress
import net.xinkong.furagent.desktop.ui.theme.Radius
import net.xinkong.furagent.desktop.ui.theme.Shadow
import net.xinkong.furagent.shared.tools.BackupHelper
import net.xinkong.furagent.shared.ThinkingLevel
import net.xinkong.furagent.shared.tools.TrashHelper
import net.xinkong.furagent.shared.util.*
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

data class PendingAttachment(
    val path: String,
    val name: String,
    val isImage: Boolean
)

internal val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
internal val UNSUPPORTED_IMAGE_EXTS = setOf("heic", "heif", "avif", "tiff", "tif")
// 图片扩展名正则已抽到 shared ImagePathExtractor，这里不再使用

object ChatFocusTracker {
    @Volatile var chatPageVisible: Boolean = false
}

/**
 * 送给 TTS 前清理文本。
 */

private val RE_CODE_BLOCK = Regex("```[\\s\\S]*?```")
private val RE_INLINE_CODE = Regex("(`[^`\n]+`)")
private val RE_IMAGE = Regex("!\\[[^\\]]*]\\([^)]+\\)")
private val RE_LINK = Regex("\\[([^\\]]+)]\\([^)]+\\)")
private val RE_BOLD = Regex("""\*\*([^*]+)\*\*""")
private val RE_ITALIC = Regex("""(?!\*)\*([^*]+)\*(?!\*)""")
private val RE_BOLD_UL = Regex("__([^_]+)__")
private val RE_ITALIC_UL = Regex("_([^_]+)_")
private val RE_HEADING = Regex("(?m)^\\s*#{1,6}\\s*")
private val RE_UL_LIST = Regex("(?m)^\\s*[-*+]\\s+")
private val RE_OL_LIST = Regex("""(?m)^\s*\d+\.\s+""")
private val RE_URL = Regex("https?://\\S+")
private val RE_PATH = Regex("(?:[A-Za-z]:[\\\\/]|/)(?:[^\\s/]+/)*[^\\s/]*")
private val RE_EMOJI = Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+")
private val RE_WHITESPACE = Regex("[ \\t]+")
private val RE_MULTI_NL = Regex("\\n{2,}")

internal fun cleanForSpeech(text: String): String =
    // 【P1-抽 shared】完整 15 正则清洗已移入 shared/util/SpeechCleaner
    net.xinkong.furagent.shared.util.SpeechCleaner.cleanForSpeech(text)


internal fun needsCollapse(content: String): Boolean =
    shouldCollapseContent(content, maxLines = 3, maxChars = 200)


@Composable
fun ChatPage(
    sessionId: String?,
    messages: List<DesktopMessage>,
    streamingText: String,
    thinking: String,
    reasoning: String,
    /** 【错误状态】API 错误（如「HTTP 429 · 用量配额已用尽」），渲染成状态小气泡。 */
    errorText: String = "",
    isLoading: Boolean,
    inputText: String,
    hasApiKey: Boolean,
    modelName: String,
    thinkingLevel: ThinkingLevel = ThinkingLevel.DEFAULT,
    supportsThinking: Boolean = true,
    chatProfiles: List<Pair<String, String>> = emptyList(),
    currentProfileId: String = "",
    onThinkingLevelChange: (ThinkingLevel) -> Unit = {},
    onSelectProfile: (String) -> Unit = {},
    scrollToMessageId: String? = null,
    onScrollToMessageHandled: () -> Unit = {},
    onInputChange: (String) -> Unit,
    onSend: (List<PendingAttachment>) -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit = {},
    onCopyMessage: (DesktopMessage) -> Unit = {},
    onRegenerateMessage: (DesktopMessage) -> Unit = {},
    onEditAndRegenerate: (DesktopMessage) -> Unit = {},
    onReExecuteTool: (DesktopMessage) -> Unit = {},
    onDeleteMessage: (DesktopMessage) -> Unit = {},
    onRetryError: (() -> Unit)? = null
) {
    var attachments by remember { mutableStateOf<List<PendingAttachment>>(emptyList()) }
    var bottomReservedPx by remember { mutableIntStateOf(140) }
    var viewingImage by remember { mutableStateOf<String?>(null) }
    // 【子代理只读】子会话（sub_ 开头）是 AI 派发的任务上下文，用户只能看不能发
    val isSubSession = sessionId?.startsWith("sub_") == true

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
            reasoning = reasoning,
            isLoading = isLoading,
            errorText = errorText,
            hasApiKey = hasApiKey,
            onOpenSettings = onOpenSettings,
            onOpenHistory = onOpenHistory,
            onCopyMessage = onCopyMessage,
            onRegenerateMessage = onRegenerateMessage,
            onEditAndRegenerate = onEditAndRegenerate,
            onReExecuteTool = onReExecuteTool,
            onDeleteMessage = onDeleteMessage,
            onImageClick = { viewingImage = it },
            bottomReservedPx = bottomReservedPx,
            scrollToMessageId = scrollToMessageId,
            onScrollToMessageHandled = onScrollToMessageHandled,
            onRetryError = onRetryError,
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
                    Column {
                        // 【子代理页不要输入框】不是禁用，是根本不渲染。
                        // 子会话是 AI 派发的任务上下文，用户没有发消息的场景，
                        // 留个灰掉的输入框只会让人一直想点。
                        if (!isSubSession) {
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
                                readOnlyHint = null,
                                attachments = attachments,
                                onAttachmentsChange = { attachments = it },
                                onImageClick = { viewingImage = it },
                                modelName = modelName,
                                thinkingLevel = thinkingLevel,
                                supportsThinking = supportsThinking,
                                chatProfiles = chatProfiles,
                                currentProfileId = currentProfileId,
                                onThinkingLevelChange = onThinkingLevelChange,
                                onSelectProfile = onSelectProfile
                            )
                        }
                        TokenStatusBar(messages)
                    }
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

// 朗读进度条

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
                                .size(28.dp)
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
                                .size(28.dp)
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

// 消息列表

@Composable
private fun ChatMessageList(
    sessionId: String?,
    messages: List<DesktopMessage>,
    streamingText: String,
    thinking: String,
    reasoning: String,
    /** 任务是否进行中：实时思考只在运行中显示，结束后不残留 */
    isLoading: Boolean = false,
    errorText: String = "",
    hasApiKey: Boolean,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit = {},
    onCopyMessage: (DesktopMessage) -> Unit,
    onRegenerateMessage: (DesktopMessage) -> Unit,
    onEditAndRegenerate: (DesktopMessage) -> Unit,
    onReExecuteTool: (DesktopMessage) -> Unit,
    onDeleteMessage: (DesktopMessage) -> Unit,
    onImageClick: (String) -> Unit,
    bottomReservedPx: Int,
    scrollToMessageId: String?,
    onScrollToMessageHandled: () -> Unit,
    onRetryError: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    key(sessionId) {
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current

        val bottomPadding = with(density) { (bottomReservedPx + 16).toDp() }

        val chatItems = remember(messages) { buildChatItems(messages) }
        // 任务完成结果卡的数据源（remember 必须在 Composable 上下文，不能放 LazyListScope）
        val modifiedFiles = remember(messages) {
            // 【体验】只统计「本次对话」这一轮（最后一条 user 消息之后）改过的文件，
            // 而不是整个会话历史里所有改过的文件。
            val lastUserIdx = messages.indexOfLast { it.role == "user" }
            val turn = if (lastUserIdx >= 0) messages.subList(lastUserIdx, messages.size) else messages
            SessionAnalytics.collectModifiedFiles(turn)
        }

        val isAtBottom by remember {
            derivedStateOf { !listState.canScrollForward }
        }

        val totalCount = chatItems.size +
                (if (thinking.isNotEmpty() && streamingText.isEmpty()) 1 else 0) +
                (if (streamingText.isNotEmpty()) 1 else 0)

        suspend fun jumpToBottom() {
            if (totalCount <= 0) return
            try {
                listState.scrollToItem(totalCount - 1)
                // scrollBy 会被 clamp 到实际可滚动距离，不需要 repeat 循环
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
                                    it.items.any { m -> m.id == scrollToMessageId }) ||
                            (it is ChatListItem.WorkProcess &&
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

        // 【滚动修复】用 userScrolledUp 标志替代 canScrollForward 判断。
        // canScrollForward 在新内容刚加入时因布局未稳定而返回错误值，导致滚动永远不触发。
        var userScrolledUp by remember { mutableStateOf(false) }
        var programmaticScroll by remember { mutableStateOf(false) }

        // 检测用户主动上滑：滚动进行中且还能往下滚 → 用户上滑了
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
                .collect { (scrolling, canForward) ->
                    if (programmaticScroll) return@collect
                    if (scrolling && canForward) {
                        // 正在滚动且还能往下滚 → 用户主动上滑，锁定不自动滚
                        userScrolledUp = true
                    } else if (!canForward) {
                        // 已经到底 → 解锁
                        userScrolledUp = false
                    }
                }
        }

        // 新消息出现时自动滚动（chatItems.size 变化）
        var lastItemCount by remember { mutableIntStateOf(0) }
        LaunchedEffect(chatItems.size) {
            if (chatItems.size > lastItemCount) {
                lastItemCount = chatItems.size
                val last = messages.lastOrNull()
                // 用户上滑后不强制拉到底部，除非是用户刚发的消息
                val shouldFollow = !userScrolledUp || last?.role == "user"
                if (scrollToMessageId == null && shouldFollow) {
                    programmaticScroll = true
                    try { jumpToBottom() } finally { programmaticScroll = false }
                }
            } else {
                lastItemCount = chatItems.size
            }
        }

        // 流式/思考内容变化时自动滚动（snapshotFlow 替代 LaunchedEffect 频繁 key 重启）
        LaunchedEffect(Unit) {
            snapshotFlow { totalCount to streamingText.length }
                .collect { (count, _) ->
                    val last = messages.lastOrNull()
                    val shouldFollow = !userScrolledUp || last?.role == "user"
                    if (shouldFollow && count > 0) {
                        programmaticScroll = true
                        try {
                            listState.scrollToItem(count - 1)
                            listState.scrollBy(100_000f)
                        } catch (_: Exception) { } finally {
                            programmaticScroll = false
                        }
                    }
                }
        }

        val latestKey = chatItems.lastOrNull()?.key

        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxSize()) {
                // 顶部功能区（基础布局流首行，贴顶贴边零缝隙）
                ChatTopBar(
                    modifier = Modifier.fillMaxWidth(),
                    sessionId = sessionId,
                    onOpenHistory = onOpenHistory
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).padding(end = 24.dp),
                    contentPadding = PaddingValues(
                        start = 32.dp,
                        end = 32.dp,
                        top = 8.dp,
                        bottom = bottomPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                if (chatItems.isEmpty() && streamingText.isEmpty() && thinking.isEmpty()) {
                    item { EmptyChatState(hasApiKey = hasApiKey, onOpenSettings = onOpenSettings) }
                }

                itemsIndexed(chatItems, key = { _, it -> it.key }) { index, item ->
                    val isLatest = item.key == latestKey
                    // 新消息淡入 + 轻微上滑（参考 ChatGPT / Claude 的消息入场）
                    val enter = remember(item.key) {
                        Animatable(if (isLatest) 0f else 1f)
                    }
                    LaunchedEffect(item.key) {
                        if (enter.value < 1f) {
                            enter.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
                        }
                    }

                    Box(
                        Modifier.animateItem(
                            fadeInSpec = tween(220, easing = FastOutSlowInEasing),
                            placementSpec = tween(260, easing = FastOutSlowInEasing),
                            fadeOutSpec = null
                        ).graphicsLayer {
                            alpha = enter.value
                            translationY = (1f - enter.value) * 10.dp.toPx()
                        }
                    ) {
                            when (item) {
                                is ChatListItem.Single -> {
                                    // 与上一条消息同分钟 → 隐藏本条时间戳行（重复时间戳是气泡间隔的主源）
                                    val prev = chatItems.getOrNull(index - 1)
                                    val showTime = prev !is ChatListItem.Single ||
                                        item.msg.timestamp / 60_000L != prev.msg.timestamp / 60_000L
                                    MessageItemWithMenu(
                                        msg = item.msg,
                                        showTime = showTime,
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
                                is ChatListItem.WorkProcess -> {
                                    WorkProcessRow(
                                        group = item,
                                        onCopy = onCopyMessage,
                                        onReExecute = onReExecuteTool,
                                        onDelete = onDeleteMessage,
                                        onImageClick = onImageClick,
                                        // 【实时思考归位】运行中把流式思考挂到最后一条工作过程里（
                                        // 即最后一个工具调用之后）展开显示。
                                        liveReasoning = if (isLoading &&
                                            item === chatItems.lastOrNull() &&
                                            streamingText.isEmpty()
                                        ) reasoning else ""
                                    )
                                }
                                is ChatListItem.DateSeparator -> {
                                    DateSeparatorChip(item.displayText)
                                }
                            }
                    }
                }

                // 任务完成结果卡：本次会话编辑过的文件（功能强制常开）
                if (streamingText.isEmpty() && modifiedFiles.isNotEmpty()) {
                    item(key = "modified_files_result") {
                        ModifiedFilesCard(modifiedFiles)
                    }
                }

                // 【深度思考直显】还没出现工作过程气泡时（第一次工具调用之前），
                // 流式思考没有宿主可挂；而下面的 thinking 气泡又只在 reasoning 为空时显示，
                // 结果开了深度思考后，从发消息到第一次工具调用之间思考过程完全看不到。
                // 这里在没有宿主时把思考过程单独显示出来。
                val liveReasoningHost = chatItems.lastOrNull() is ChatListItem.WorkProcess
                if (isLoading && reasoning.isNotBlank() && streamingText.isEmpty() && !liveReasoningHost) {
                    item(key = "live_reasoning_standalone") {
                        Box(Modifier.animateItem(fadeOutSpec = null)) {
                            LiveReasoningStep(reasoning)
                        }
                    }
                }

                // 【实时思考】已归位到工作过程末尾（见 WorkProcessRow 的 liveReasoning），
                // 不再在气泡外面单独画一行「深度思考」。
                if (thinking.isNotEmpty() && streamingText.isEmpty() && reasoning.isBlank()) {
                    item {
                        Box(Modifier.animateItem(fadeOutSpec = null)) {
                            // 全限定顶层版：外层新增 Column 后，裸调会误解析为 ColumnScope 成员扩展
                            androidx.compose.animation.AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(tween(180)) +
                                        slideInVertically(
                                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                                            initialOffsetY = { it / 4 }
                                        ),
                                exit = fadeOut(tween(140))
                            ) {
                                ThinkingBubble(thinking)
                            }
                        }
                    }
                }

                // 【错误状态气泡】与「重试中」同款样式，不是 AI 聊天气泡。
                // 任务结束后仍保留（sessionError 不在 finally 里清），
                // 直到用户下次发消息才清。
                if (errorText.isNotEmpty() && streamingText.isEmpty()) {
                    item {
                        Box(Modifier.animateItem(fadeOutSpec = null)) {
                            ErrorBubble(errorText, onRetry = onRetryError)
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
                            // 【优化】复用 jumpToBottom 的两阶段滚动逻辑，
                            // 等布局稳定后持续滚动直到真正触底。
                            jumpToBottom()
                        }
                    },
                    icon = { Icon(Icons.Default.VerticalAlignBottom, "回到底部") },
                    text = { Text("最新") },
                    containerColor = AccentColor(),
                    contentColor = OnAccent()
                )
            }

            if (chatItems.size >= 15) {
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

// Minimap

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
    val hitWidth = 28.dp

    // 【性能优化】预计算色块数据，避免在 Canvas draw 内做 Compose 类型判断。
    // 之前用 chatItems.forEachIndexed + Box 逐项绘制，每帧 recompose 都重建 N 个 Composable；
    // 改为 derivedStateOf 只在 chatItems 变化时重算，Canvas 一次性绘制。
    // 注意：AccentColor/CardStrongColor/ToolColor 是 @Composable，不能在 remember 内调用，
    // 所以先在外面取值，再用 Color.toArgb()/Color(colorLong) 转为非 Compose 依赖。
    val userColor = AccentColor().copy(alpha = 0.75f)
    val assistantColor = CardStrongColor()
    val toolColor = ToolColor().copy(alpha = 0.75f)
    val blockColors by remember {
        derivedStateOf {
            val userArgb = userColor.toArgb()
            val assistantArgb = assistantColor.toArgb()
            val toolArgb = toolColor.toArgb()
            chatItems.map { item ->
                when (item) {
                    is ChatListItem.Single -> if (item.msg.role == "user") userArgb else assistantArgb
                    is ChatListItem.ToolsGroup -> toolArgb
                    is ChatListItem.WorkProcess -> toolArgb
                    is ChatListItem.DateSeparator -> 0 // transparent
                }
            }
        }
    }

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
        val borderColor = BorderColor().copy(alpha = if (hovered) 0.9f else 0.5f)
        val bgColor = CardColor().copy(alpha = if (hovered) 0.85f else 0.5f)

        Canvas(
            modifier = Modifier
                .width(16.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(Radius.XS))
                .background(bgColor)
                .border(1.dp, borderColor, RoundedCornerShape(Radius.XS))
                .onGloballyPositioned { coords ->
                    trackHeightPx = coords.size.height.coerceAtLeast(1)
                }
        ) {
            val total = chatItems.size.toFloat().coerceAtLeast(1f)
            val w = size.width

            // 一次性绘制所有色块（替代 N 个 Box Composable）
            blockColors.forEachIndexed { idx, argb ->
                if (argb == 0) return@forEachIndexed
                val topY = (idx / total) * trackHeightPx
                val h = ((1f / total) * trackHeightPx).coerceAtLeast(1.5f)
                drawRect(color = Color(argb), topLeft = Offset(0f, topY), size = Size(w, h))
            }

            // 视口指示器
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

// 消息组折叠

// 会话内可折叠面板（工具调用组 / 深度思考共用）

/**
 * 会话内所有可折叠列表的统一容器。
 *
 * 工具调用组、深度思考面板都基于它，保证布局、内边距、展开动画完全一致，
 * 唯一可定制的是强调色（accent）与头部内容（header）。
 *
 * 展开状态由容器自己管理：
 *  - 执行中（isRunning=true）默认展开，让用户实时看到执行内容；
 *  - 执行完成（isRunning 变为 false）自动折叠；
 *  - 用户手动点过之后不再自动干预。
 *
 * @param accent    强调色，决定边框与图标色
 * @param isRunning 是否正在执行（有调用未返回）
 * @param header    头部内容（图标 + 标题 + 右侧信息），不含展开箭头
 * @param content   展开后的内容
 */
@Composable
internal fun CollapsiblePanel(
    accent: Color,
    isRunning: Boolean = false,
    modifier: Modifier = Modifier,
    /** 默认展开，且任务结束不自动收起（工作过程用；工具组保持默认折叠）。 */
    defaultExpanded: Boolean = false,
    header: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(isRunning || defaultExpanded) }
    var userToggled by remember { mutableStateOf(false) }
    LaunchedEffect(isRunning) {
        if (!isRunning && !userToggled && !defaultExpanded) expanded = false
    }

    Column(modifier.fillMaxWidth()) {
        Surface(
            color = CardColor(),
            shape = RoundedCornerShape(Radius.MD),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.3f)),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.MD))
                .clickable {
                    expanded = !expanded
                    userToggled = true
                }
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                header()
                Spacer(Modifier.weight(1f))
                // 运行中的动态提示，让新手一眼看出「还在执行」而不是卡住
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = accent
                    )
                    Spacer(Modifier.width(8.dp))
                }
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
                    label = "collapsibleArrow"
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
                content()
            }
        }
    }
}

@Composable
private fun ToolsGroupRow(
    group: ChatListItem.ToolsGroup,
    onCopy: (DesktopMessage) -> Unit,
    onReExecute: (DesktopMessage) -> Unit,
    onDelete: (DesktopMessage) -> Unit,
    onImageClick: (String) -> Unit
) {
    CollapsiblePanel(
        accent = ToolColor(),
        isRunning = group.toolResultCount < group.toolCallCount,
        header = {
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
        }
    ) {
        // 【体验修复】此前这里对每条消息直接渲染完整卡片（tool_call / tool_result 各一张大卡），
        // 工具返回会像气泡一样“抛”在聊天流里。改为与工作过程一致的配对紧凑渲染：
        // 一次调用一行，结果折成「输出 N 行」，点击才展开详情。
        WorkProcessItemList(
            items = group.items,
            onCopy = onCopy,
            onReExecute = onReExecute,
            onDelete = onDelete,
            onImageClick = onImageClick
        )
    }
}

