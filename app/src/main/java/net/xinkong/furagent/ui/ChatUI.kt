@file:OptIn(ExperimentalMaterial3Api::class)

package net.xinkong.furagent.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import net.xinkong.furagent.data.ChatMessage
import net.xinkong.furagent.shared.icons.Icons
import net.xinkong.furagent.shared.ui.applyStreamingGradient
import net.xinkong.furagent.shared.util.*
import net.xinkong.furagent.tools.ToolRegistry
import net.xinkong.furagent.ui.theme.AppRadius

// 图片路径提取

// 消息组折叠
// 桌面端（ChatPage.kt）有同样的结构，已一并合并。

// 空状态

// 统一标准 图标 56dp、onSurfaceVariant 标题。

@Composable
fun EmptyStatePlaceholder(
    hasApiKey: Boolean = true,
    onOpenSettings: (() -> Unit)? = null,
    onPromptClick: ((String) -> Unit)? = null,
    messages: List<ChatMessage> = emptyList()
) {
    // 动画清理 标题不再做呼吸缩放，静态展示
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "FurAgent",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onBackground,
                letterSpacing = (-1.5).sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "你的 AI 助手，随时待命",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(28.dp))
            if (!hasApiKey && onOpenSettings != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(AppRadius.SM),
                    modifier = Modifier.clickable { onOpenSettings() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "未配置 API Key，点击设置",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

// 消息分发

@Composable
fun ChatBubble(
    message: ChatMessage,
    onLongPress: () -> Unit,
    onImageClick: (String) -> Unit = {},
    showTime: Boolean = true
) {
    when (message.type) {
        "tool_call" -> ToolCallBubble(message, onLongPress)
        "tool_result" -> {
            // 子会话卡片：result 首行 SUB_SESSION:{id} 时渲染为子代理入口卡
            if (SubSessionInfo.isSubSession(message.content)) {
                SubSessionCard(message)
            } else {
                ToolResultBubble(message, onLongPress, onImageClick)
            }
        }
        "system_note" -> SystemNoteBubble(message)
        "reasoning" -> ReasoningPanel(message.content)
        else -> TextBubble(message, onLongPress, onImageClick, showTime)
    }
}

// 子会话卡片：AI 派生的独立子会话入口（不带历史的子代理）

object SubSessionNavigator {
    /** MainActivity 注册：打开子会话（无打断切换） */
    var open: ((String) -> Unit)? = null
    /** 从哪个会话点进来的（子会话顶部「返回」用；非子会话时为 null） */
    var originId: String? = null
}

@Composable
private fun SubSessionCard(message: ChatMessage) {
    // 解析逻辑统一走 shared SubSessionInfo
    val info = remember(message.content) { SubSessionInfo.parse(message.content) } ?: return
    val subId = info.id
    val title = info.title
    val summary = info.summary

    // 订阅子会话实时进度（与 Desktop 对齐）
    val progressState by ToolRegistry.SubSessionProgress.state.collectAsState()
    val live = remember(progressState) { ToolRegistry.SubSessionProgress.snapshot()[subId] }
    val metaText = if (live != null) {
        "子会话 · 进行中 · 已 ${live.steps} 步" +
                (if (live.lastTool.isNotBlank()) " · 当前 ${live.lastTool}" else "")
    } else {
        "子会话 · 已结束 · 点击查看"
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(AppRadius.SM),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { SubSessionNavigator.open?.invoke(subId) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Extension,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        metaText,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "→",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    summary.take(300),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

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
 * @param accent    强调色，决定底色、边框、图标色
 * @param isRunning 是否正在执行（有调用未返回）
 * @param header    头部内容（图标 + 标题 + 右侧信息），不含展开箭头
 * @param content   展开后的内容
 */
@Composable
fun CollapsiblePanel(
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
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(AppRadius.SM),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadius.SM))
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    .padding(top = 8.dp, start = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
fun ToolsGroupBubble(
    group: ChatListItem.ToolsGroup,
    onMessageLongPress: (ChatMessage) -> Unit,
    onImageClick: (String) -> Unit = {}
) {
    CollapsiblePanel(
        accent = MaterialTheme.colorScheme.primary,
        isRunning = group.toolResultCount < group.toolCallCount,
        header = {
            Icon(
                Icons.Default.Build,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                buildString {
                    append("${group.toolCallCount} 次工具调用")
                    if (group.toolResultCount != group.toolCallCount) {
                        append(" · ${group.toolResultCount} 条返回")
                    }
                    if (group.durationMs > 0) {
                        append(" · 耗时 ${formatDuration(group.durationMs)}")
                    }
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    ) {
        // 大卡片，与桌面端（树形 + 紧凑行）完全不一致。现改为复用
        // WorkProcessItemList，与 WorkProcessBubble 走同一套渲染逻辑。
        WorkProcessItemList(
            items = group.items,
            onMessageLongPress = onMessageLongPress,
            onImageClick = onImageClick
        )
    }
}

/**
 * 工作过程 / 工具组共用的条目渲染（与桌面端 WorkProcessItemList 对齐）。
 *
 * tool_call 与配对的 tool_result 合成一行紧凑条目；中间文字、思考各成一条；
 * 未配对的结果也采用紧凑行 + 点击展开，不再是大卡片。
 */
@Composable
fun WorkProcessItemList(
    items: List<ChatMessage>,
    onMessageLongPress: (ChatMessage) -> Unit,
    onImageClick: (String) -> Unit = {},
    liveReasoning: String = ""
) {
    // 配对逻辑在 shared buildWorkProcessEntries（一次构建 + remember 缓存）
    val entries = remember(items) { buildWorkProcessEntries(items) }
    // 并行批次分段：同 parallelGroup 的条目收在一段，UI 上标出「并行 N」
    val runs = remember(entries) { buildWorkProcessRuns(entries) }
    runs.forEachIndexed { runIndex, run ->
        key(run.key) {
            if (!run.isParallel) {
                run.entries.forEach { e ->
                    key(e.main.id) {
                        // 原 AnimatedVisibility(visible = true) 永不触发动画，
                        // 只是多了一层 composition/state。直接去掉。
                        TreeBranch(isLast = runIndex == runs.lastIndex) {
                            WorkProcessEntryRow(
                                e = e,
                                onMessageLongPress = onMessageLongPress,
                                onImageClick = onImageClick
                            )
                        }
                    }
                }
            } else {
                // 并行组：一个头部 + 组内段落，视觉上明确这一批是同时跑的
                TreeBranch(isLast = runIndex == runs.lastIndex) {
                    Column(Modifier.fillMaxWidth()) {
                        ParallelGroupHeader(run.entries.size)
                        Column(Modifier.padding(start = 10.dp)) {
                            run.entries.forEach { e ->
                                key(e.main.id) {
                                    WorkProcessEntryRow(
                                        e = e,
                                        onMessageLongPress = onMessageLongPress,
                                        onImageClick = onImageClick
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // 实时思考 正在流式生成中的思考挂在工具列表末尾，
    // 也就是「最后一个工具调用」的下一行，而不是丢在气泡外面单独占一行。
    // 点开就是完整流式内容，边想边滚。
    if (liveReasoning.isNotBlank()) {
        key("live_reasoning") {
            TreeBranch(isLast = true) {
                LiveReasoningStep(liveReasoning)
            }
        }
    }
}

/**
 * 实时思考条目：工作过程里正在流式生成的那一段。
 *
 * 与已完成的 [ReasoningStep] 区别：默认展开、内容随流增长，
 * 标题带呼吸点表示还在跑。完整内容全部保留，不截成一行。
 */
@Composable
internal fun LiveReasoningStep(text: String) {
    var expanded by remember { mutableStateOf(true) }
    val lineCount = remember(text) { text.count { it == '\n' } + 1 }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            val rotation by animateFloatAsState(
                targetValue = if (expanded) 90f else 0f,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
                label = "liveReasoningArrow"
            )
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { rotationZ = rotation }
            )
            Spacer(Modifier.width(6.dp))
            PulsingDot()
            Spacer(Modifier.width(6.dp))
            Text(
                "思考中 · $lineCount 行",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(160, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Top
            ),
            exit = shrinkVertically(tween(140), shrinkTowards = Alignment.Top)
        ) {
            Column(Modifier.padding(start = 20.dp, top = 4.dp)) {
                Text(
                    text,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** 思考中的呼吸点，给“还在跑”一个可感知的信号。 */
@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    Box(
        Modifier
            .size(6.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha), CircleShape)
    )
}

/** 并行批次标题：告诉用户接下来这 N 个工具是一起跑的，不是排队。 */
@Composable
private fun ParallelGroupHeader(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.AutoMirrored.Filled.CallSplit,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            modifier = Modifier.size(13.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            "并行 $count 个",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
        )
    }
}

/** 工作过程里一条条目（与之前内联的渲染保持一致）。 */
@Composable
private fun WorkProcessEntryRow(
    e: WorkProcessEntry,
    onMessageLongPress: (ChatMessage) -> Unit,
    onImageClick: (String) -> Unit = {}
) {
    when (e.main.type) {
        "reasoning" -> ReasoningStep(e.main.content)
        "text" ->
            WorkProcessText(e.main, onLongPress = { onMessageLongPress(e.main) })
        "tool_call" -> WorkProcessToolItem(
            call = e.main, result = e.result, status = e.status,
            onLongPress = onMessageLongPress, onImageClick = onImageClick
        )
        "tool_result" if SubSessionInfo.isSubSession(e.main.content) ->
            SubSessionCard(e.main)
        "tool_result" -> WorkProcessResultItem(
            result = e.main,
            onLongPress = { onMessageLongPress(e.main) },
            onImageClick = onImageClick
        )
        else -> ChatBubble(e.main, { onMessageLongPress(e.main) }, onImageClick)
    }
}

/**
 * 工作过程里未配对的工具返回：一行紧凑展示，点击展开完整输出。
 * 与桌面端 WorkProcessResultItem 对齐。
 */
@Composable
private fun WorkProcessResultItem(
    result: ChatMessage,
    onLongPress: (ChatMessage) -> Unit,
    onImageClick: (String) -> Unit = {}
) {
    var expanded by remember(result.id) { mutableStateOf(false) }
    val failed = remember(result.content) { isToolFailure(result.content) }
    val images = remember(result.content) { ImagePathExtractor.extract(result.content) }
    Column(Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(AppRadius.XS),
            border = BorderStroke(
                0.5.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadius.XS))
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = { onLongPress(result) }
                )
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(
                            if (failed) ErrorColor() else SuccessColor(),
                            CircleShape
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (failed) {
                        val reason = extractFailureReason(result.content)
                        if (reason.isNotBlank()) "失败：$reason" else "工具返回 · 失败"
                    } else "工具返回",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(8.dp))
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "输出 ${toolResultLineCount(result.content)} 行",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (result.durationMs > 0) {
                        Text(
                            " · ${formatDuration(result.durationMs)}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            maxLines = 1
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(200)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120))
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 18.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SelectionContainer {
                    if (looksLikePatch(result.content)) {
                        PatchView(result.content)
                    } else {
                        // 大文本分段加载，避免超长输出一次性渲染卡顿
                        val maxLines = 2000
                        val lines = remember(result.content) { result.content.lines() }
                        val displayText = if (lines.size > maxLines) {
                            lines.take(maxLines).joinToString("\n")
                        } else {
                            result.content
                        }
                        Text(
                            displayText,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 18.sp
                        )
                        if (lines.size > maxLines) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "... 仅显示前 $maxLines 行，共 ${lines.size} 行",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
                if (images.isNotEmpty()) {
                    images.forEach { path ->
                        MessageImage(path) { onImageClick(path) }
                    }
                }
            }
        }
    }
}

/**
 * 工作过程折叠组。
 *
 * 一轮 assistant 响应里夹着中间文字回复时，工具调用与深度思考进这里
 * （默认展开、思考条目单独折叠），所有 AI 文字消息都平铺在组外。
 * 与工具调用组共用 [CollapsiblePanel]，保证布局与展开动画完全一致。
 */
@Composable
fun WorkProcessBubble(
    group: ChatListItem.WorkProcess,
    onMessageLongPress: (ChatMessage) -> Unit,
    onImageClick: (String) -> Unit = {},
    liveReasoning: String = ""
) {
    // 标题统计缓存，避免每次重组重算
    val contentCount = remember(group.items) {
        group.items.count { it.type == "reasoning" || it.type == "text" }
    }
    CollapsiblePanel(
        accent = MaterialTheme.colorScheme.primary,
        isRunning = group.toolResultCount < group.toolCallCount,
        defaultExpanded = true,
        header = {
            Icon(
                Icons.Default.Build,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                buildString {
                    append("工作过程")
                    if (group.toolCallCount > 0) {
                        append(" · ${group.toolCallCount} 次工具调用")
                    }
                    if (contentCount > 0) {
                        append(" · $contentCount 条内容")
                    }
                    if (group.durationMs > 0) {
                        append(" · 耗时 ${formatDuration(group.durationMs)}")
                    }
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    ) {
        // 与 ToolsGroupBubble 共用 WorkProcessItemList，
        // 避免两套渲染逻辑分叉。
        WorkProcessItemList(
            items = group.items,
            onMessageLongPress = onMessageLongPress,
            onImageClick = onImageClick
        )
    }
}

/**
 * 树形连线：条目左侧画 ├─ / └─ 线段，纵向拼成工作过程的树状结构。
 *
 * [isLast] 决定竖线是贯穿到底还是只画到本条目的分支点。
 */
@Composable
private fun TreeBranch(isLast: Boolean, content: @Composable () -> Unit) {
    val lineColor = MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = 7.dp.toPx()
                val y = 14.dp.toPx()
                val branchEnd = 15.dp.toPx()
                val stroke = 2.dp.toPx()
                drawLine(
                    color = lineColor,
                    start = Offset(x, 0f),
                    end = Offset(x, if (isLast) y else size.height),
                    strokeWidth = stroke
                )
                drawLine(
                    color = lineColor,
                    start = Offset(x, y),
                    end = Offset(branchEnd, y),
                    strokeWidth = stroke
                )
            }
            .padding(start = 18.dp)
    ) {
        content()
    }
}

/**
 * 工作过程内的单个工具调用（调用 + 返回配对），默认折叠为一行紧凑摘要。
 *
 * 折叠态：状态点 + 工具名 + 主参数摘要 + 「输出 N 行」。
 * 展开态：复用 ToolCallBubble / ToolResultBubble 显示完整内容。
 */
@Composable
private fun WorkProcessToolItem(
    call: ChatMessage,
    result: ChatMessage?,
    status: Boolean?,
    onLongPress: (ChatMessage) -> Unit,
    onImageClick: (String) -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    val (label, argSummary) = remember(call.content) { toolCallLabel(call.content) }
    Column(Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(AppRadius.XS),
            border = BorderStroke(
                0.5.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadius.XS))
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = { onLongPress(call) }
                )
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
            if (status != null) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(
                            if (status) SuccessColor() else ErrorColor(),
                            CircleShape
                        )
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (argSummary.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    argSummary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            // 失败状态加文字说明，红点对新手不自解释
            if (status == false) {
                val failReason = remember(result?.content) {
                    result?.content?.let { extractFailureReason(it) } ?: ""
                }
                Text(
                    if (failReason.isNotBlank()) "失败：$failReason" else "失败",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 6.dp).weight(1f, fill = false)
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            }
        }
        if (result != null || expanded) {
            // 子层树线：「输出 N 行」与展开详情作为工具节点的下一级
            val childLine = MaterialTheme.colorScheme.outline
            Box(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val cx = 7.dp.toPx()
                        val cy = 9.dp.toPx()
                        val stroke = 2.dp.toPx()
                        val endY = if (expanded) size.height else cy
                        drawLine(childLine, Offset(cx, 0f), Offset(cx, endY), strokeWidth = stroke)
                        drawLine(
                            childLine,
                            Offset(cx, cy),
                            Offset(15.dp.toPx(), cy),
                            strokeWidth = stroke
                        )
                    }
                    .padding(start = 18.dp)
            ) {
                Column {
                    if (result != null) {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(AppRadius.MICRO))
                                .clickable { expanded = !expanded }
                                .padding(end = 6.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "输出 ${toolResultLineCount(result.content)} 行",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                            )
                            if (result.durationMs > 0) {
                                Text(
                                    " · ${formatDuration(result.durationMs)}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically(tween(200)) + fadeIn(tween(160)),
                        exit = shrinkVertically(tween(180)) + fadeOut(tween(120))
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ToolCallBubble(call, onLongPress = { onLongPress(call) }, status = status)
                            if (result != null) {
                                ToolResultBubble(
                                    result,
                                    onLongPress = { onLongPress(result) },
                                    onImageClick = onImageClick
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 工作过程内部的中间文字回复。
 *
 * 与普通消息气泡同款样式，默认限制 2 行，点击气泡展开/收起（右下倒三角指示）。
 */
@Composable
private fun WorkProcessText(message: ChatMessage, onLongPress: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val needsCollapse = remember(message.content) {
        shouldCollapseContent(message.content)
    }
    val context = LocalContext.current
    Text(
        "中间回复",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            // 12dp 圆角 + 通栏
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(AppRadius.SM),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadius.SM))
        ) {
            Column(
                Modifier
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    // 大文本用更快 spring（近似瞬间完成），小文本保留流畅弹跳动画
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = 1f,
                            stiffness = if (message.content.length > 8000) Spring.StiffnessHigh else Spring.StiffnessMediumLow
                        )
                    )
            ) {
                if (needsCollapse) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        val rotation by animateFloatAsState(
                            targetValue = if (expanded) 180f else 0f,
                            animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
                            label = "wpChevron"
                        )
                        Icon(
                            Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "收起" else "展开",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier
                                .size(16.dp)
                                .graphicsLayer { rotationZ = rotation }
                        )
                    }
                }
                // 始终用 RichMessageText（有 remember 缓存，正则解析只跑一次）
                // 大文本的真正瓶颈是 maxLines 限制，不是 markdown 解析
                RichMessageText(
                    content = message.content,
                    textColor = MaterialTheme.colorScheme.onSurface,
                    context = context,
                    onLongPress = onLongPress,
                    // 点击文本展开/收起（外层 clickable 收不到事件）
                    onTap = if (needsCollapse) {
                        { expanded = !expanded }
                    } else null,
                    // 展开后不再限制行数（旧值 500 行，超长文本仍被截断）
                    maxLines = if (expanded) Int.MAX_VALUE else 2
                )
            }
        }
    }
}

/**
 * 思考过程（工作过程内部的一步）。
 *
 * 默认折叠，点标题展开。与截图里的「思考过程」一致。
 */
@Composable
private fun ReasoningStep(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            val rotation by animateFloatAsState(
                targetValue = if (expanded) 90f else 0f,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
                label = "reasoningStepArrow"
            )
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { rotationZ = rotation }
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "思考过程",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(200, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Top
            ) + fadeIn(tween(160)),
            exit = shrinkVertically(
                animationSpec = tween(180, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Top
            ) + fadeOut(tween(120))
        ) {
            Column(Modifier.padding(start = 20.dp, top = 4.dp)) {
                Text(
                    text,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                )
            }
        }
    }
}

// 输入区控件（深度思考 / 模型选择共用）

/**
 * 输入区小控件（chip）的统一容器。
 *
 * 深度思考开关、模型选择框都基于它，保证圆角、边框、内边距一致。
 *
 * @param active 是否处于激活态（如深度思考已开启），决定底色与边框
 * @param accent 激活时的强调色
 */
@Composable
fun ChipSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    accent: Color = MaterialTheme.colorScheme.primary,
    content: @Composable RowScope.() -> Unit
) {
    Surface(
        color = if (active) accent.copy(alpha = 0.14f) else glassInputBg(),
        shape = RoundedCornerShape(AppRadius.MD),
        border = BorderStroke(
            1.dp,
            if (active) accent.copy(alpha = 0.45f) else glassInputBorder()
        ),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

/**
 * 文本型 chip —— ChipSurface 的纯文本便捷重载。
 *
 * 日期筛选、级别多选、快捷操作等"一个词就是全部内容"的场景统一走它，
 * 不再各自手搓 Surface。
 *
 * @param selected 选中态（primary 实底 + onPrimary 字），未选中为玻璃底
 */
@Composable
fun ChipSurface(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary else glassInputBg(),
        shape = RoundedCornerShape(AppRadius.MD),
        border = BorderStroke(
            1.dp,
            if (selected) Color.Transparent else glassInputBorder()
        ),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

// 文本气泡

@Composable
fun TextBubble(
    message: ChatMessage,
    onLongPress: () -> Unit,
    onImageClick: (String) -> Unit = {},
    showTime: Boolean = true
) {
    val isUser = message.role == "user"
    val context = LocalContext.current

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val maxBubbleWidth = (screenWidthDp * 0.72f).coerceAtMost(520f).dp

    val contentImages = remember(message.content) {
        ImagePathExtractor.extract(message.content)
    }
    val allImages = (message.imagePaths + contentImages).distinct()

    Column(Modifier.fillMaxWidth()) {
        // 思考归位 reasoning 已作为 type="reasoning" 消息收进「工作过程」，
        // 不再在普通气泡上方重复流派一份。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            if (isUser) {
                // 用户消息：柔和浅色气泡
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                    modifier = Modifier.widthIn(max = maxBubbleWidth)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)) {
                        SelectionContainer {
                            Row {
                                RichMessageText(
                                    content = message.content,
                                    textColor = MaterialTheme.colorScheme.onSurface,
                                    isOnPrimary = false,
                                    context = context,
                                    onLongPress = onLongPress,
                                    plainText = message.isStreaming
                                )
                                if (message.isStreaming) {
                                    Spacer(Modifier.width(4.dp))
                                    BlinkingCursor(color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        if (allImages.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            allImages.forEach { path ->
                                MessageImage(path = path, onClick = { onImageClick(path) })
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                        if (!message.isStreaming && showTime) {
                            Spacer(Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    android.text.format.DateFormat.format("HH:mm", message.timestamp).toString(),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                                if (message.interrupted) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(AppRadius.MICRO)
                                    ) {
                                        Text(
                                            "已中断",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)) {
                        SelectionContainer {
                            Row {
                                RichMessageText(
                                    content = message.content,
                                    textColor = MaterialTheme.colorScheme.onSurface,
                                    isOnPrimary = false,
                                    context = context,
                                    onLongPress = onLongPress,
                                    plainText = message.isStreaming
                                )
                                if (message.isStreaming) {
                                    Spacer(Modifier.width(4.dp))
                                    BlinkingCursor(color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        if (allImages.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            allImages.forEach { path ->
                                MessageImage(path = path, onClick = { onImageClick(path) })
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                        if (!message.isStreaming && showTime) {
                            Spacer(Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    android.text.format.DateFormat.format("HH:mm", message.timestamp).toString(),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                                if (message.interrupted) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(AppRadius.MICRO)
                                    ) {
                                        Text(
                                            "已中断",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// 图片位图缓存：LazyColumn 条目离屏后状态丢弃，回屏时避免重复解码（约 32MB 上限）
private val imageBitmapCache = object : android.util.LruCache<String, android.graphics.Bitmap>(32 * 1024) {
    override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount / 1024
}

@Composable
private fun MessageImage(path: String, onClick: () -> Unit) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = imageBitmapCache.get(path), path) {
        if (value == null) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                    val bmp = android.graphics.BitmapFactory.decodeFile(path, opts)
                    bmp?.let { imageBitmapCache.put(path, it) }
                    bmp
                } catch (_: Exception) { null }
            }
        }
    }

    val bmp = bitmap
    if (bmp == null) {
        Text(
            "（图片加载中或失败：$path）",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.error
        )
        return
    }
    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .clip(RoundedCornerShape(AppRadius.SM))
            .clickable { onClick() },
        contentScale = ContentScale.Fit
    )
}

@Composable
fun DateSeparatorChip(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(AppRadius.PILL)
        ) {
            Text(
                text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
fun SystemNoteBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            shape = RoundedCornerShape(AppRadius.PILL)
        ) {
            Text(
                message.content,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun BlinkingCursor(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )
    Text(
        "▎",
        color = color.copy(alpha = alpha),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
fun ThinkingBubble(text: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "think")
    val dot1 by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "d1"
    )
    val dot2 by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, delayMillis = 150, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "d2"
    )
    val dot3 by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, delayMillis = 300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "d3"
    )

    // 底色从 primaryContainer 半透明改为卡片色，
    // 与 ReasoningPanel / 工具组统一，不再“一块紫一块白”。
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(AppRadius.SM),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 流体渐变呼吸点：与发送按钮、滑块同一套视觉语言
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(FluidGradient.diagonal())
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                modifier = Modifier.weight(1f)
            )
            Row {
                listOf(dot1, dot2, dot3).forEach { a ->
                    Text(
                        "·",
                        color = MaterialTheme.colorScheme.primary.copy(alpha = a),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// 错误状态气泡（API 错误，与「重试中」同款样式）

/**
 * API 错误状态气泡。
 *
 * 为什么不用 AI 聊天气泡
 * 「HTTP 429 · 用量配额已用尽」不是 AI 说的话，是系统状态。
 * 用聊天气泡会让用户以为 AI 在回答，而且会污染对话历史。
 *
 * 样式与 [ThinkingBubble] 一致（卡片色 + 细边框），但：
 *   - 图标用警告三角，不用转圈（错误不是进行中）
 *   - 边框用警告橙（WarningColor），与限速提示同色，不用刺眼的红
 *   - 文案单行，不展开（详细信息看日志）
 */
@Composable
fun ErrorBubble(
    text: String,
    onRetry: (() -> Unit)? = null,
    /** 手动关闭：用户已知悉错误（如充值后），不必靠发消息才让它消失。 */
    onDismiss: (() -> Unit)? = null
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
        shape = RoundedCornerShape(AppRadius.SM),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.error.copy(alpha = 0.25f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.9f),
                modifier = Modifier.weight(1f)
            )
            if (onRetry != null) {
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = onRetry,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "重试",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (onDismiss != null) {
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * 展示模型返回的深度思考内容（reasoning_content / reasoning / thinking）。
 *
 * 默认折叠，点标题行展开。
 *
 * 与桌面端一致 原先用带背景的 CollapsiblePanel，
 * 内容少时一块空荡荡的卡片；现在改成和工作过程里「思考 N 行」
 * 一样的无背景紧凑行。它只作为落库前的兜底，正常路径是
 * 思考作为 type="reasoning" 消息进「工作过程」面板。
 */
@Composable
fun ReasoningPanel(text: String) {
    val accent = Color(0xFF7C5CFF)
    var expanded by remember { mutableStateOf(false) }
    val lines = remember(text) { text.count { it == '\n' } + 1 }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Psychology,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "深度思考 · $lines 行",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(200, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Top
            ) + fadeIn(tween(160)),
            exit = shrinkVertically(
                animationSpec = tween(180, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Top
            ) + fadeOut(tween(120))
        ) {
            Text(
                text,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                modifier = Modifier.padding(start = 20.dp, top = 4.dp)
            )
        }
    }
}

// 工具气泡

@Composable
fun ToolCallBubble(message: ChatMessage, onLongPress: () -> Unit, status: Boolean? = null) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            // 卡片色 + 12dp 圆角 + 通栏，不再紫色半透明窄条
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(AppRadius.SM),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadius.SM))
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = onLongPress
                )
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = 1f,
                            stiffness = if (message.content.length > 4000) Spring.StiffnessHigh else Spring.StiffnessMediumLow
                        )
                    )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (status != null) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(
                                    if (status) SuccessColor() else ErrorColor(),
                                    CircleShape
                                )
                                .border(1.5.dp, if (status) SuccessColor().copy(alpha = 0.3f) else ErrorColor().copy(alpha = 0.3f), CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        "调用", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    message.content,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // 展开后不再限制行数（旧值 500 行，超长调用参数仍被截断）
                    maxLines = if (expanded) Int.MAX_VALUE else 5,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
fun ToolResultBubble(
    message: ChatMessage,
    onLongPress: () -> Unit,
    onImageClick: (String) -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }

    val contentImages = remember(message.content) {
        ImagePathExtractor.extract(message.content)
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            // 卡片色 + 12dp 圆角 + 通栏
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(AppRadius.SM),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadius.SM))
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = onLongPress
                )
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = 0.75f,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "返回", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (looksLikePatch(message.content)) {
                    PatchView(message.content)
                } else {
                    // 大文本分段加载：先显示前 N 行，避免一次性渲染导致卡顿
                    val maxInitialLines = 200
                    val lines = remember(message.content) { message.content.lines() }
                    val needsTruncation = !expanded && lines.size > maxInitialLines
                    val displayText = if (needsTruncation) {
                        lines.take(maxInitialLines).joinToString("\n")
                    } else if (expanded && lines.size > maxInitialLines) {
                        // 展开时也限制最大行数，防止超长输出卡顿
                        lines.take(2000).joinToString("\n")
                    } else {
                        message.content
                    }
                    Text(
                        displayText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 8,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 18.sp
                    )
                    if (lines.size > 2000 && expanded) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "... 仅显示前 2000 行，共 ${lines.size} 行",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
                if (contentImages.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    contentImages.forEach { path ->
                        MessageImage(path = path, onClick = { onImageClick(path) })
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

// diff 补丁高亮（与桌面端 PatchView 对齐）

/**
 * 判断一段文本是否像 unified diff。
 *
 * 条件：至少有一行以 "@@" 开头，或同时出现 "+++" 与 "---" 行。
 * 避免把普通文本误判成补丁。
 */
private fun looksLikePatch(content: String): Boolean {
    if (content.length > 200_000) return false
    var hasHunk = false
    var hasPlus = false
    var hasMinus = false
    content.lineSequence().take(400).forEach { line ->
        when {
            line.startsWith("@@") -> hasHunk = true
            line.startsWith("+++") -> hasPlus = true
            line.startsWith("---") -> hasMinus = true
        }
    }
    return hasHunk || (hasPlus && hasMinus)
}

/**
 * diff 补丁视图：按行着色（+ 绿 / - 红 / @@ 强调 / 文件头灰）。
 * 与桌面端 PatchView 一致，最多渲染 300 行避免卡顿。
 */
@Composable
private fun PatchView(patch: String) {
    val lines = remember(patch) { patch.lines().take(300) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.TINY))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(8.dp)
    ) {
        lines.forEach { line ->
            val color = when {
                line.startsWith("+++") || line.startsWith("---") ->
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                line.startsWith("@@") -> MaterialTheme.colorScheme.primary
                line.startsWith("+") -> SuccessColor()
                line.startsWith("-") -> ErrorColor()
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                line,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = color,
                lineHeight = 16.sp
            )
        }
    }
}

// 富文本
@Composable
fun RichMessageText(
    content: String,
    textColor: Color,
    isOnPrimary: Boolean = false,
    context: Context,
    onLongPress: () -> Unit = {},
    onTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    plainText: Boolean = false
) {
    val linkColor = if (isOnPrimary) {
        Color.White.copy(alpha = 0.95f)
    } else {
        MaterialTheme.colorScheme.primary
    }
    val codeBg = if (isOnPrimary) {
        Color.White.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }

    if (plainText) {
        // 流式输出：原有的 Text 渲染逻辑
        // applyStreamingGradient 可能会对大文本做复杂的 AnnotatedString 构造，
        // 这里用 remember 缓存，且依赖项更严谨。
        val annotatedString = remember(content, textColor) { applyStreamingGradient(content, textColor) }
        var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

        Text(
            text = annotatedString,
            style = TextStyle(color = textColor, fontSize = 15.sp, lineHeight = 21.sp),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layoutResult = it },
            modifier = modifier.pointerInput(content, textColor, onTap) {
                detectTapGestures(
                    onLongPress = { onLongPress() },
                    onTap = { offset ->
                        val layout = layoutResult
                        val hitLink = if (layout != null) {
                            try {
                                val pos = layout.getOffsetForPosition(offset)
                                annotatedString.getStringAnnotations("url", pos, pos).firstOrNull()
                            } catch (_: Exception) { null }
                        } else null

                        if (hitLink != null) {
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, hitLink.item.toUri()).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            } catch (_: Exception) { }
                        } else {
                            onTap?.invoke()
                        }
                    }
                )
            }
        )
    } else {
        // Markdown 渲染
        // ComposeMarkdownRenderer 内部做了大量的正则解析和树构建。
        // 如果 content 没有变化（例如只是父组件重组），不应重复解析。
        // 这里利用 key 强制：如果 content 没变，ComposeMarkdownRenderer 内部缓存命中。
        key(content) {
            ComposeMarkdownRenderer(
                text = content,
            colors = MarkdownColors(
                textColor = textColor,
                linkColor = linkColor,
                codeBg = codeBg,
                quoteColor = textColor.copy(alpha = 0.4f),
                dividerColor = MaterialTheme.colorScheme.outlineVariant,
                tableBorderColor = MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = modifier,
            maxLines = maxLines,
                onLinkClick = { url ->
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    } catch (_: Exception) { }
                },
                onLongPress = onLongPress,
                onTap = onTap
            )
        }
    }
}

// 长按菜单

@Composable
fun MessageOptionsSheet(
    message: ChatMessage, onDismiss: () -> Unit,
    onCopy: () -> Unit, onEdit: () -> Unit,
    onRegenerate: () -> Unit, onDelete: () -> Unit,
    onShare: () -> Unit,
    onReExecute: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { GlassDragHandle() }
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                text = when {
                    message.role == "user" -> "用户消息"
                    message.type == "tool_call" -> "工具调用"
                    message.type == "tool_result" -> "工具返回"
                    message.type == "system_note" -> "系统提示"
                    else -> "AI 消息"
                },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            MessageMenuItem(
                text = "复制",
                icon = Icons.Default.ContentCopy,
                onClick = {
                    onCopy()
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                }
            )

            if (message.type == "tool_call" && onReExecute != null) {
                MessageMenuItem(
                    text = "再次执行",
                    icon = Icons.Default.Refresh,
                    onClick = onReExecute
                )
            }

            MessageMenuItem(
                text = "分享",
                icon = Icons.Default.Share,
                onClick = onShare
            )
            // 体验优化 允许编辑任意 text 类型的消息：
            //   - user 消息：编辑后重新生成
            //   - assistant 消息：编辑内容后作为新的 user 消息重新生成
            //     （对话模型里 AI 不能直接发下一条，只能以用户身份追问）
            if (message.type == "text") {
                MessageMenuItem(
                    text = if (message.role == "user") "编辑并重新生成" else "编辑此消息内容",
                    icon = Icons.Default.Edit,
                    onClick = onEdit
                )
            }
            if (message.role == "assistant" && message.type == "text") {
                MessageMenuItem(
                    text = "重新生成",
                    icon = Icons.Default.Refresh,
                    onClick = onRegenerate
                )
            }
            var showDeleteConfirm by remember { mutableStateOf(false) }
            MessageMenuItem(
                text = "删除",
                icon = Icons.Default.Delete,
                onClick = { showDeleteConfirm = true },
                tint = MaterialTheme.colorScheme.error
            )
            if (showDeleteConfirm) {
                PocoAlertDialog(
                    onDismiss = { showDeleteConfirm = false },
                    title = "删除消息",
                    text = { Text("确定要删除这条消息吗？此操作不可撤销。") },
                    confirmText = "删除",
                    confirmColor = MaterialTheme.colorScheme.error,
                    onConfirm = {
                        showDeleteConfirm = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun MessageMenuItem(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = if (pressed) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

@Composable
fun EditMessageDialog(
    initialContent: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialContent) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(AppRadius.DIALOG),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text("编辑消息") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                minLines = 3, shape = RoundedCornerShape(AppRadius.MD),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onSave(text.trim()) }) {
                Text("保存并重新生成")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}