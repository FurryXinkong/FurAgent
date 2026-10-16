@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import net.xinkong.furagent.data.ChatMessage
import net.xinkong.furagent.shared.util.ChatListItem
import java.io.File

// ============================================================
// 图片路径提取
// ============================================================
// 【2026-10-15 去重】实现已抽到 shared ImagePathExtractor
private fun extractImagePathsFromContent(content: String): List<String> =
    net.xinkong.furagent.shared.util.ImagePathExtractor.extract(content)

// ============================================================
// 消息组折叠
// ============================================================
// 【冗余清理 Batch 10】ChatListItem / buildChatItems 抽到 shared/util/ChatListItem.kt。
// 桌面端（ChatPage.kt）有同样的结构，已一并合并。

// ============================================================
// 空状态
// ============================================================
//
// 【统一标准】图标 56dp、onSurfaceVariant 标题。

@Composable
fun EmptyStatePlaceholder() {
    val infiniteTransition = rememberInfiniteTransition(label = "empty")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

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
                letterSpacing = (-1).sp,
                modifier = Modifier.scale(scale)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "试试问我：你能做什么？",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================================================
// 消息分发
// ============================================================

@Composable
fun ChatBubble(
    message: ChatMessage,
    onLongPress: () -> Unit,
    onImageClick: (String) -> Unit = {}
) {
    when (message.type) {
        "tool_call" -> ToolCallBubble(message, onLongPress)
        "tool_result" -> ToolResultBubble(message, onLongPress, onImageClick)
        "system_note" -> SystemNoteBubble(message)
        else -> TextBubble(message, onLongPress, onImageClick)
    }
}

@Composable
fun ToolsGroupBubble(
    group: ChatListItem.ToolsGroup,
    onMessageLongPress: (ChatMessage) -> Unit,
    onImageClick: (String) -> Unit = {}
) {
    var expanded by remember(group.groupKey) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(
                0.5.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
            ),
            modifier = Modifier
                .widthIn(max = 320.dp)
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = { }
                )
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
                    label = "toolsGroupArrow"
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
                    .padding(top = 8.dp, start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                group.items.forEach { msg ->
                    when (msg.type) {
                        "tool_call" -> ToolCallBubble(msg, onLongPress = { onMessageLongPress(msg) })
                        "tool_result" -> ToolResultBubble(
                            msg,
                            onLongPress = { onMessageLongPress(msg) },
                            onImageClick = onImageClick
                        )
                        else -> ChatBubble(msg, { onMessageLongPress(msg) }, onImageClick)
                    }
                }
            }
        }
    }
}

// ============================================================
// 文本气泡
// ============================================================

@Composable
fun TextBubble(
    message: ChatMessage,
    onLongPress: () -> Unit,
    onImageClick: (String) -> Unit = {}
) {
    val isUser = message.role == "user"
    val context = LocalContext.current

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val maxBubbleWidth = (screenWidthDp * 0.78f).coerceAtMost(560f).dp

    val contentImages = remember(message.content) {
        extractImagePathsFromContent(message.content)
    }
    val allImages = (message.imagePaths + contentImages).distinct()

    Column(Modifier.fillMaxWidth()) {
        if (!isUser && message.reasoning.isNotBlank()) {
            ReasoningPanel(text = message.reasoning)
            Spacer(Modifier.height(6.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            Surface(
                color = if (isUser) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(
                    topStart = 24.dp, topEnd = 24.dp,
                    bottomStart = if (isUser) 24.dp else 8.dp,
                    bottomEnd = if (isUser) 8.dp else 24.dp
                ),
                border = if (isUser) null else BorderStroke(
                    0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier.widthIn(max = maxBubbleWidth)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Row {
                        RichMessageText(
                            content = message.content,
                            textColor = if (isUser) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                            isOnPrimary = isUser,
                            context = context,
                            onLongPress = onLongPress
                        )
                        if (message.isStreaming) {
                            Spacer(Modifier.width(4.dp))
                            BlinkingCursor(
                                color = if (isUser) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (allImages.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        allImages.forEach { path ->
                            MessageImage(path = path, onClick = { onImageClick(path) })
                            Spacer(Modifier.height(6.dp))
                        }
                    }

                    if (!message.isStreaming) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            android.text.format.DateFormat.format("HH:mm", message.timestamp).toString(),
                            fontSize = 10.sp,
                            color = if (isUser) {
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                            }
                        )
                    }

                    if (message.interrupted && !message.isStreaming) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "（已中断）",
                            fontSize = 11.sp,
                            fontStyle = FontStyle.Italic,
                            color = if (isUser) {
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageImage(path: String, onClick: () -> Unit) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, path) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                android.graphics.BitmapFactory.decodeFile(path, opts)
            } catch (_: Exception) { null }
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
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        contentScale = ContentScale.Fit
    )
}

@Composable
fun SystemNoteBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            shape = RoundedCornerShape(50)
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

    // 【UI 统一】与 ReasoningPanel / ToolsGroupBubble 同款卡片风格。
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            0.5.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
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

// ============================================================
// 深度思考面板（默认折叠，可展开）
// ============================================================

/**
 * 展示模型返回的深度思考内容（reasoning_content / reasoning / thinking）。
 *
 * 默认折叠，点标题栏展开。不持久化，仅实时展示。
 */
@Composable
fun ReasoningPanel(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            0.5.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "深度思考",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
                    label = "reasoningArrow"
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
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                )
            }
        }
    }
}

// ============================================================
// 工具气泡
// ============================================================

@Composable
fun ToolCallBubble(message: ChatMessage, onLongPress: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(
                0.5.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
            ),
            modifier = Modifier
                .widthIn(max = 320.dp)
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
        extractImagePathsFromContent(message.content)
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(
                0.5.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier
                .widthIn(max = 320.dp)
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
                Text(
                    message.content,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 8,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )
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

// ============================================================
// 富文本
// ============================================================

@Composable
fun RichMessageText(
    content: String,
    textColor: Color,
    isOnPrimary: Boolean = false,
    context: Context,
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier
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

    val annotatedString = remember(content, textColor, linkColor, codeBg) {
        AndroidMarkdownRenderer.render(content, textColor, linkColor, codeBg)
    }

    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    Text(
        text = annotatedString,
        style = TextStyle(
            color = textColor,
            fontSize = 15.sp,
            lineHeight = 21.sp
        ),
        onTextLayout = { layoutResult = it },
        modifier = modifier.pointerInput(content, textColor) {
            detectTapGestures(
                onLongPress = { onLongPress() },
                onTap = { offset ->
                    val layout = layoutResult ?: return@detectTapGestures
                    val pos = try { layout.getOffsetForPosition(offset) }
                    catch (_: Exception) { return@detectTapGestures }
                    annotatedString.getStringAnnotations(
                        tag = "url", start = pos, end = pos
                    ).firstOrNull()?.let {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, it.item.toUri()).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        } catch (_: Exception) { }
                    }
                }
            )
        }
    )
}

// ============================================================
// 长按菜单
// ============================================================

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
            // 【体验优化 B7/B20】允许编辑任意 text 类型的消息：
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
            MessageMenuItem(
                text = "删除",
                icon = Icons.Default.Delete,
                onClick = onDelete,
                tint = MaterialTheme.colorScheme.error
            )
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
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text("编辑消息") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                minLines = 3, shape = RoundedCornerShape(16.dp),
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