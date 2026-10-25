package net.xinkong.furagent.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.xinkong.furagent.desktop.data.DesktopMessage
import net.xinkong.furagent.desktop.ui.theme.Radius
import net.xinkong.furagent.shared.icons.Icons
import net.xinkong.furagent.shared.util.*

// ── 工作过程面板（从 ChatPage.kt 拆分）───────────────────────

@Composable
internal fun WorkProcessRow(
    group: ChatListItem.WorkProcess,
    onCopy: (DesktopMessage) -> Unit,
    onReExecute: (DesktopMessage) -> Unit,
    onDelete: (DesktopMessage) -> Unit,
    onImageClick: (String) -> Unit,
    liveReasoning: String = ""
) {
    // 标题统计缓存，避免每次重组重算
    val contentCount = remember(group.items) {
        group.items.count { it.type == "reasoning" || it.type == "text" }
    }
    CollapsiblePanel(
        accent = ToolColor(),
        isRunning = group.toolResultCount < group.toolCallCount,
        defaultExpanded = true,
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
                    append("工作过程")
                    if (group.toolCallCount > 0) {
                        append(" · ${group.toolCallCount} 次工具调用")
                    }
                    if (contentCount > 0) {
                        append(" · $contentCount 条内容")
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
        WorkProcessItemList(
            items = group.items,
            onCopy = onCopy,
            onReExecute = onReExecute,
            onDelete = onDelete,
            onImageClick = onImageClick,
            liveReasoning = liveReasoning
        )
    }
}

/**
 * 工作过程 / 工具组共用的条目渲染。
 *
 * tool_call 与配对的 tool_result 合成一行紧凑条目；中间文字、思考各成一条；
 * 未配对的结果（中断、旧数据缺调用）也采用紧凑行 + 点击展开，不再是大卡片；
 * 子会话返回渲染为可点击入口（点击切入子会话）。
 */
@Composable
internal fun WorkProcessItemList(
    items: List<DesktopMessage>,
    onCopy: (DesktopMessage) -> Unit,
    onReExecute: (DesktopMessage) -> Unit,
    onDelete: (DesktopMessage) -> Unit,
    onImageClick: (String) -> Unit,
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
                        AnimatedVisibility(
                            visible = true,
                            enter = expandVertically(tween(220)) + fadeIn(tween(180))
                        ) {
                            TreeBranch(isLast = runIndex == runs.lastIndex) {
                                WorkProcessEntryRow(
                                    e = e, onCopy = onCopy, onReExecute = onReExecute,
                                    onDelete = onDelete, onImageClick = onImageClick
                                )
                            }
                        }
                    }
                }
            } else {
                AnimatedVisibility(
                    visible = true,
                    enter = expandVertically(tween(220)) + fadeIn(tween(180))
                ) {
                    TreeBranch(isLast = runIndex == runs.lastIndex) {
                        Column(Modifier.fillMaxWidth()) {
                            ParallelGroupHeader(run.entries.size)
                            Column(Modifier.padding(start = 10.dp)) {
                                run.entries.forEach { e ->
                                    key(e.main.id) {
                                        WorkProcessEntryRow(
                                            e = e, onCopy = onCopy, onReExecute = onReExecute,
                                            onDelete = onDelete, onImageClick = onImageClick
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
    // 【实时思考】流式生成中的思考挂在工具列表末尾（最后一个工具调用之后），
    // 点开就是完整流式内容。不再丢在气泡外面单独占一行。
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
 * 默认展开、内容随流增长，标题带呼吸点表示还在跑。
 */
@Composable
internal fun LiveReasoningStep(text: String) {
    var expanded by remember { mutableStateOf(true) }
    val lineCount = remember(text) { text.count { it == '\n' } + 1 }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
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
                tint = TextSecondary(),
                modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = rotation }
            )
            Spacer(Modifier.width(6.dp))
            PulsingDot()
            Spacer(Modifier.width(6.dp))
            Text(
                "思考中 · $lineCount 行",
                fontSize = 12.sp,
                color = AccentColor().copy(alpha = 0.9f)
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
                    color = TextSecondary()
                )
            }
        }
    }
}

/** 思考中的呼吸点。 */
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
        Modifier.size(6.dp)
            .background(AccentColor().copy(alpha = alpha), CircleShape)
    )
}

/** 并行批次标题：告诉用户这 N 个工具是一起跑的，不是排队。 */
@Composable
private fun ParallelGroupHeader(count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.AutoMirrored.Filled.CallSplit,
            contentDescription = null,
            tint = AccentColor().copy(alpha = 0.85f),
            modifier = Modifier.size(13.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            "并行 $count 个",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = AccentColor().copy(alpha = 0.85f)
        )
    }
}

@Composable
private fun WorkProcessEntryRow(
    e: WorkProcessEntry,
    onCopy: (DesktopMessage) -> Unit,
    onReExecute: (DesktopMessage) -> Unit,
    onDelete: (DesktopMessage) -> Unit,
    onImageClick: (String) -> Unit
) {
    when (e.main.type) {
        "tool_call" -> WorkProcessToolItem(
            call = e.main, result = e.result, status = e.status,
            onCopy = onCopy, onReExecute = onReExecute,
            onDelete = onDelete, onImageClick = onImageClick
        )
        "tool_result" -> if (SubSessionInfo.isSubSession(e.main.content)) {
            SubSessionCard(e.main)
        } else {
            WorkProcessResultItem(
                result = e.main, onDelete = onDelete, onImageClick = onImageClick
            )
        }
        "reasoning" -> ReasoningStep(e.main.content)
        else -> WorkProcessText(e.main)
    }
}

/**
 * 工作过程里未配对的工具返回：一行紧凑展示，点击展开完整输出。
 */
@Composable
internal fun WorkProcessResultItem(
    result: DesktopMessage,
    onDelete: (DesktopMessage) -> Unit,
    onImageClick: (String) -> Unit
) {
    var expanded by remember(result.id) { mutableStateOf(false) }
    val failed = remember(result.content) { isToolFailure(result.content) }
    val images = remember(result.content) { ImagePathExtractor.extract(result.content) }
    Column(Modifier.fillMaxWidth()) {
        Surface(
            color = CardColor(),
            shape = RoundedCornerShape(Radius.XS),
            border = BorderStroke(1.dp, BorderColor()),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.XS))
                // 整行点击展开；拖选文字时移动超过 slop 会 cancel tap，不冲突选择
                .clickable { expanded = !expanded }
        ) {
            SelectionContainer {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(if (failed) ErrorColor() else ResultColor(), CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (failed) {
                        val reason = extractFailureReason(result.content)
                        if (reason.isNotBlank()) "失败：$reason" else "工具返回 · 失败"
                    } else "工具返回",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary()
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "输出 ${toolResultLineCount(result.content)} 行",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = TextSecondary(),
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .clickable { expanded = !expanded }
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
                Modifier.fillMaxWidth().padding(start = 18.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SelectionContainer {
                    Text(
                        result.content,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary(),
                        lineHeight = 18.sp
                    )
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
 * 树形连线：条目左侧画 ├─ / └─ 线段，纵向拼成工作过程的树状结构。
 *
 * [isLast] 决定竖线是贯穿到底还是只画到本条目的分支点。
 */
@Composable
internal fun TreeBranch(isLast: Boolean, content: @Composable () -> Unit) {
    val lineColor = TextSecondary()
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
 * 展开态：复用 MessageItemWithMenu 显示完整内容（含右键菜单）。
 */
@Composable
internal fun WorkProcessToolItem(
    call: DesktopMessage,
    result: DesktopMessage?,
    status: Boolean?,
    onCopy: (DesktopMessage) -> Unit,
    onReExecute: (DesktopMessage) -> Unit,
    onDelete: (DesktopMessage) -> Unit,
    onImageClick: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val (label, argSummary) = remember(call.content) { toolCallLabel(call.content) }
    Column(Modifier.fillMaxWidth()) {
        Surface(
            color = CardColor(),
            shape = RoundedCornerShape(Radius.XS),
            border = BorderStroke(1.dp, BorderColor()),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.XS))
                // 整行点击展开（拖选超过 slop 会 cancel tap，与 SelectionContainer 不冲突）
                .clickable { expanded = !expanded }
        ) {
            SelectionContainer {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
            if (status != null) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(
                            if (status) ResultColor() else ErrorColor(),
                            CircleShape
                        )
                        .border(1.5.dp, if (status) ResultColor().copy(alpha = 0.3f) else ErrorColor().copy(alpha = 0.3f), CircleShape)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (argSummary.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    argSummary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary(),
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
                    color = ErrorColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 6.dp).weight(1f, fill = false)
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = TextSecondary(),
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .clickable { expanded = !expanded }
            )
            }
            }
        }
        if (result != null || expanded) {
            // 子层树线：「输出 N 行」与展开详情作为工具节点的下一级
            val childLine = TextSecondary()
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
                        Text(
                            "输出 ${toolResultLineCount(result.content)} 行",
                            fontSize = 11.sp,
                            color = TextMuted(),
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.MICRO))
                                .clickable { expanded = !expanded }
                                .padding(end = 6.dp, bottom = 2.dp)
                        )
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
                            MessageItemWithMenu(
                                msg = call,
                                onCopy = onCopy,
                                onRegenerate = {},
                                onEditAndRegenerate = {},
                                onReExecute = onReExecute,
                                onDelete = onDelete,
                                onImageClick = onImageClick,
                                toolStatus = status
                            )
                            if (result != null) {
                                MessageItemWithMenu(
                                    msg = result,
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
        }
    }
}

/**
 * 工作过程内部的中间文字回复。
 *
 * 与普通消息气泡同款样式，默认限制 2 行，点击气泡展开/收起（右下倒三角指示）。
 */
@Composable
internal fun WorkProcessText(msg: DesktopMessage) {
    var expanded by remember { mutableStateOf(false) }
    val needsCollapse = remember(msg.content) {
        shouldCollapseContent(msg.content)
    }
    Text(
        "中间回复",
        fontSize = 11.sp,
        color = TextMuted(),
        modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = CardColor(),
            shape = RoundedCornerShape(Radius.MD),
            border = BorderStroke(1.dp, BorderColor()),
            modifier = Modifier
                .widthIn(max = 1000.dp)
                .clip(RoundedCornerShape(Radius.MD))
                // 整行点击展开（needsCollapse 时）；文字靠 SelectionContainer 仍可拖选
                .clickable(enabled = needsCollapse) { expanded = !expanded }
        ) {
            Column(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 11.dp)
                    // 大文本用更快 spring（近似瞬间完成），小文本保留流畅动画
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = 1f,
                            stiffness = if (msg.content.length > 8000) Spring.StiffnessHigh else Spring.StiffnessMediumLow
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
                            tint = TextSecondary(),
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .clickable { expanded = !expanded }
                                .graphicsLayer { rotationZ = rotation }
                        )
                    }
                }
                // 始终用 MarkdownText（有 remember 缓存，解析只跑一次）
                // 大文本的真正瓶颈是 maxLines 限制，不是 markdown 解析
                SelectionContainer {
                    MarkdownText(
                        text = msg.content,
                        color = TextPrimary(),
                        maxLines = if (expanded) 500 else 2
                    )
                }
            }
        }
    }
}

/**
 * 思考过程（工作过程内部的一步）。
 *
 * 【体验修复】原先是一个带背景的折叠面板，夹在紧凑的工具行里显得很空。
 * 现在与「输出 N 行」同构：一行小字「思考 N 行」，点击展开全文，无背景。
 */
@Composable
internal fun ReasoningStep(text: String) {
    var expanded by remember { mutableStateOf(false) }
    val lines = remember(text) { text.count { it == '\n' } + 1 }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.MICRO))
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "思考 $lines 行",
                fontSize = 11.sp,
                color = TextMuted(),
                modifier = Modifier.padding(end = 6.dp, bottom = 2.dp)
            )
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = TextSecondary(),
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
                color = TextSecondary(),
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
    }
}