@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class
)
package net.xinkong.furagent.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.xinkong.furagent.desktop.data.DesktopMessage
import net.xinkong.furagent.desktop.data.DesktopSessionStore
import net.xinkong.furagent.desktop.data.HistoryStore
import net.xinkong.furagent.desktop.system.DesktopSpeechHelper
import net.xinkong.furagent.desktop.tools.DesktopTools.SubSessionProgress
import net.xinkong.furagent.desktop.ui.theme.Radius
import net.xinkong.furagent.desktop.ui.theme.Shadow
import net.xinkong.furagent.shared.icons.Icons
import net.xinkong.furagent.shared.tools.BackupHelper
import net.xinkong.furagent.shared.tools.TrashHelper
import net.xinkong.furagent.shared.util.*
import java.awt.Desktop
import java.io.File

@Composable
internal fun MessageItemWithMenu(
    msg: DesktopMessage,
    onCopy: (DesktopMessage) -> Unit,
    onRegenerate: (DesktopMessage) -> Unit,
    onEditAndRegenerate: (DesktopMessage) -> Unit,
    onReExecute: (DesktopMessage) -> Unit,
    onDelete: (DesktopMessage) -> Unit,
    onImageClick: (String) -> Unit,
    toolStatus: Boolean? = null,
    /** 与上一条消息同分钟时传 false：不渲染时间戳行，气泡间不再被重复时间戳撑开。 */
    showTime: Boolean = true
) {
    // 思考过程独立成块渲染（一层折叠），不套消息菜单
    if (msg.type == "reasoning") {
        Box(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            ReasoningStep(msg.content)
        }
        return
    }
    var menuOpen by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(Offset.Zero) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

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
            MessageRow(msg, onImageClick, toolStatus, showTime)
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
                                val ok = DesktopSpeechHelper.speak(clean)
                                if (ok) ToastBus.info("正在朗读")
                                else ToastBus.warn("朗读失败，当前系统不支持")
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
                    onClick = { menuOpen = false; showDeleteConfirm = true }
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除消息") },
            text = { Text("确定要删除这条消息吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete(msg) }) {
                    Text("删除", color = ErrorColor(), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消", color = TextSecondary())
                }
            },
            shape = RoundedCornerShape(Radius.DIALOG),
            containerColor = CardColor()
        )
    }
}

// 空状态

@Composable
internal fun EmptyChatState(hasApiKey: Boolean, onOpenSettings: () -> Unit, onPromptClick: ((String) -> Unit)? = null) {
    // 【动画清理】标题不再做呼吸缩放，静态展示
    Column(
        Modifier.fillMaxSize().padding(vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "FurAgent",
            fontSize = 42.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-1.5).sp,
            color = TextPrimary()
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
        }
    }
}

// 消息分发

@Composable
internal fun MessageRow(msg: DesktopMessage, onImageClick: (String) -> Unit, toolStatus: Boolean? = null, showTime: Boolean = true) {
    when (msg.type) {
        "tool_call" -> ToolCallRow(msg, toolStatus)
        "tool_result" -> {
            // 子会话卡片：result 首行 SUB_SESSION:{id} 时渲染为子代理入口卡
            if (SubSessionInfo.isSubSession(msg.content)) SubSessionCard(msg)
            else ToolResultRow(msg, onImageClick)
        }
        "system_note" -> SystemNoteRow(msg)
        else -> TextMessageRow(msg, onImageClick = onImageClick, showTime = showTime)
    }
}

// 子会话卡片：AI 派生的独立子会话入口（不带历史的子代理）

object SubSessionNavigator {
    /** Main.kt 注册：打开子会话 */
    var open: ((String) -> Unit)? = null
    /** 从哪个会话点进来的（子会话顶部「返回」用；非子会话时为 null） */
    var originId: String? = null
}

@Composable
internal fun SubSessionCard(msg: DesktopMessage) {
    // 解析逻辑统一走 shared SubSessionInfo
    val info = remember(msg.content) { SubSessionInfo.parse(msg.content) } ?: return
    val subId = info.id
    val title = info.title
    val summary = info.summary
    // 卡片原先固定显示「消息数：1」（派发瞬间写死的旧文本），永远不准。
    // 改为订阅子会话进度：运行中显示实时步数，结束后提示点击查看。
    val progressState by SubSessionProgress.state.collectAsState()
    val live = remember(progressState) { SubSessionProgress.snapshot()[subId] }
    val metaText = if (live != null) {
        "子会话 · 进行中 · 已 ${live.steps} 步" +
                (if (live.lastTool.isNotBlank()) " · 当前 ${live.lastTool}" else "")
    } else {
        "子会话 · 已结束 · 点击查看"
    }

    Surface(
        color = CardColor(),
        shape = RoundedCornerShape(Radius.MD_MINUS),
        border = BorderStroke(1.dp, BorderSubtle()),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { SubSessionNavigator.open?.invoke(subId) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(20.dp), tint = TextSecondary())
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
                        color = TextSecondary()
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("→", fontSize = 15.sp, color = TextSecondary())
            }
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    summary.take(300),
                    fontSize = 12.sp,
                    color = TextSecondary(),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// 文本气泡

@Composable
internal fun TextMessageRow(
    msg: DesktopMessage,
    isStreaming: Boolean = false,
    onImageClick: (String) -> Unit = {},
    showTime: Boolean = true
) {
    val isUser = msg.role == "user"
    val shadowColor = glassShadowColor()

    val contentImages = remember(msg.content) {
        ImagePathExtractor.extract(msg.content)
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
            // 【思考归位】reasoning 已作为 type="reasoning" 消息收进「工作过程」面板，
            // 不再在普通气泡上方重复流派一份。
            if (isUser) {
                // 用户消息：柔和浅色气泡
                Surface(
                    color = AccentColor().copy(alpha = 0.12f),
                    shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                    contentColor = TextPrimary()
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 9.dp)) {
                        if (displayContent.isNotBlank()) {
                            SelectionContainer {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    MarkdownText(
                                        text = displayContent,
                                        color = TextPrimary(),
                                        plainText = isStreaming
                                    )
                                    if (isStreaming) {
                                        Spacer(Modifier.width(4.dp))
                                        BlinkingCursor(AccentColor())
                                    }
                                }
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
            } else {
                // AI 消息：柔和气泡
                Surface(
                    color = CardColor().copy(alpha = 0.4f),
                    shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
                    contentColor = TextPrimary()
                ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 9.dp)) {
                        if (displayContent.isNotBlank()) {
                            SelectionContainer {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    MarkdownText(
                                        text = displayContent,
                                        color = TextPrimary(),
                                        plainText = isStreaming
                                    )
                                    if (isStreaming) {
                                        Spacer(Modifier.width(4.dp))
                                        BlinkingCursor(AccentColor())
                                    }
                                }
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
            if (!isStreaming && showTime) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatTime(msg.timestamp),
                        fontSize = 11.sp,
                        color = TextMuted(),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    if (msg.interrupted) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = ErrorColor().copy(alpha = 0.12f),
                            shape = RoundedCornerShape(Radius.MICRO)
                        ) {
                            Text(
                                "已中断",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = ErrorColor(),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// 流式输出闪烁光标
@Composable
private fun BlinkingCursor(color: Color) {
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

// 图片展示

// 图片位图缓存：列表条目离屏后状态丢弃，回屏时避免重复读盘解码（约 24 张）
private val imageBitmapCache = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
    override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<String, ImageBitmap>
    ): Boolean = size > 24
}

@Composable
internal fun MessageImage(path: String, onClick: () -> Unit = {}) {
    val ext = path.substringAfterLast('.', "").lowercase()
    val unsupported = ext in UNSUPPORTED_IMAGE_EXTS
    val bitmap by produceState(
        synchronized(imageBitmapCache) { imageBitmapCache[path] },
        path
    ) {
        if (value == null) {
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
                    val result = buffered.toComposeImageBitmap()
                    synchronized(imageBitmapCache) { imageBitmapCache[path] = result }
                    result
                } catch (_: Exception) { null }
            }
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

// 工具调用气泡

// 【B1 修复】原来外层 Row 上挂 clickable { expanded = !expanded }，
// 会拦截所有 pointer 事件，SelectionContainer 拿不到手势/键盘焦点，
// 导致"能选中但复制不了、Ctrl+C 无效、右键菜单也失效"。
// 现在把展开/收起改到右上角的箭头 Box 上独立可点，外层不再拦截。

@Composable
internal fun ToolCallRow(msg: DesktopMessage, status: Boolean? = null) {
    val shadowColor = glassShadowColor()
    var expanded by remember(msg.id) { mutableStateOf(false) }
    val collapsible = remember(msg.content) { needsCollapse(msg.content) }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
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
                                    .size(28.dp)
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
                            maxLines = if (expanded || !collapsible) 500 else 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
    }
}

@Composable
internal fun ToolResultRow(msg: DesktopMessage, onImageClick: (String) -> Unit) {
    val shadowColor = glassShadowColor()
    var expanded by remember(msg.id) { mutableStateOf(false) }

    val images = remember(msg.content) { ImagePathExtractor.extract(msg.content) }
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
                                    .size(28.dp)
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
                        // 【P1-16】大文本分段统一：与 Android 端一致
                        // 未展开：前 200 行 + "..."；展开：前 2000 行 + 尾注
                        val maxInitialLines = 200
                        val lines = remember(displayContent) { displayContent.lines() }
                        val shownText = when {
                            !expanded && lines.size > maxInitialLines ->
                                lines.take(maxInitialLines).joinToString("\n")
                            expanded && lines.size > 2000 ->
                                lines.take(2000).joinToString("\n")
                            else -> displayContent
                        }
                        SelectionContainer {
                            Text(
                                shownText,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimary(),
                                lineHeight = 18.sp,
                                maxLines = if (expanded) Int.MAX_VALUE else 8,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (expanded && lines.size > 2000) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "... 仅显示前 2000 行，共 ${lines.size} 行",
                                fontSize = 11.sp,
                                color = TextMuted().copy(alpha = 0.6f)
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

// 系统提示胶囊

@Composable
internal fun SystemNoteRow(msg: DesktopMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Box(
            Modifier.clip(RoundedCornerShape(Radius.PILL)).background(CardStrongColor())
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(msg.content, fontSize = 11.sp, color = TextSecondary())
        }
    }
}

// 深度思考面板（默认折叠，可展开）

/**
 * 展示模型返回的深度思考内容（reasoning_content / reasoning / thinking）。
 * 默认折叠，点标题栏展开。不持久化，仅实时展示。
 *
 * 【体验修复】原先用带背景的 CollapsiblePanel，内容少时显得很空；
 * 改为与工作过程内「思考 N 行」一致的无背景紧凑行。
 */
@Composable
internal fun ReasoningPanel(text: String) {
    val accent = Color(0xFF7C5CFF)
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
                color = TextMuted()
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
            enter = expandVertically(tween(200)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120))
        ) {
            Text(
                text,
                fontSize = 12.sp,
                color = TextSecondary(),
                modifier = Modifier.padding(start = 20.dp, top = 4.dp)
            )
        }
    }
}

// 思考状态行

@Composable
internal fun DateSeparatorChip(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        // 与 Android 端同名组件同构：无边框全圆角胶囊
        Surface(
            color = CardColor().copy(alpha = 0.6f),
            shape = RoundedCornerShape(Radius.PILL)
        ) {
            Text(
                text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = TextMuted(),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
internal fun ThinkingBubble(text: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking_desktop")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "thinkingAlpha"
    )
    Surface(
        color = CardColor(),
        shape = RoundedCornerShape(Radius.MD),
        border = BorderStroke(0.5.dp, ToolColor().copy(alpha = 0.3f))
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 流体渐变呼吸点：替代原来的纯色转圈，与发送按钮/滑块同一套视觉语言
                Box(
                    Modifier
                        .size(10.dp)
                        .alpha(alpha)
                        .clip(CircleShape)
                        .background(FluidGradient.diagonal())
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "思考中...",
                    fontSize = 12.sp,
                    color = TextSecondary().copy(alpha = alpha),
                    fontWeight = FontWeight.Medium
                )
            }
            if (text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text,
                    fontSize = 12.sp,
                    color = TextMuted(),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * API 错误状态气泡。
 *
 * 【为什么不用 AI 聊天气泡】
 * 「HTTP 429 · 用量配额已用尽」不是 AI 说的话，是系统状态。
 * 用聊天气泡会让用户以为 AI 在回答，而且会污染对话历史。
 *
 * 样式与 [ThinkingBubble] 一致（卡片色 + 细边框），但：
 *   - 图标用警告三角，不用转圈（错误不是进行中）
 *   - 边框用警告橙（WarningColor），与限速提示同色，不用刺眼的红
 *   - 文案单行，不展开（详细信息看日志）
 */
@Composable
internal fun ErrorBubble(text: String, onRetry: (() -> Unit)? = null) {
    Surface(
        color = CardColor(),
        shape = RoundedCornerShape(Radius.MD),
        border = BorderStroke(0.5.dp, WarningColor().copy(alpha = 0.45f))
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = WarningColor(),
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                fontSize = 12.sp,
                color = WarningColor().copy(alpha = 0.9f),
                fontWeight = FontWeight.Medium,
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
                        tint = AccentColor(),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun StatusRow(text: String) {
    // 【UI 统一】与 ReasoningPanel / ToolCallRow 同款卡片风格。
    Surface(
        color = CardColor(),
        shape = RoundedCornerShape(Radius.MD),
        border = BorderStroke(1.dp, ToolColor().copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = AccentColor()
            )
            Spacer(Modifier.width(10.dp))
            Text(text, fontSize = 12.sp, color = TextSecondary())
        }
    }
}

// 输入区上方：新对话横幅 / 修改文件卡 · 输入区下方：token 状态栏

/** 用系统默认程序打开本地文件（点击修改文件卡里的文件/路径时用）。 */
internal fun openLocalFile(path: String) {
    try {
        val f = File(path)
        if (!f.exists()) {
            net.xinkong.furagent.desktop.system.DesktopLogger.warn("Chat", "打开文件失败，文件不存在：$path")
            return
        }
        if (Desktop.isDesktopSupported() &&
            Desktop.getDesktop().isSupported(Desktop.Action.OPEN)
        ) {
            Desktop.getDesktop().open(f)
        } else {
            ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", f.absolutePath).start()
        }
    } catch (e: Exception) {
        net.xinkong.furagent.desktop.system.DesktopLogger.warn("Chat", "打开文件失败：${e.message}")
    }
}
@Composable
internal fun ModifiedFilesCard(files: List<SessionAnalytics.ModifiedFile>) {
    // 【体验】默认展开路径列表（不再需要先点一下才看到文件）
    var showPaths by remember { mutableStateOf(true) }
    // 展开的路径列表在 LazyColumn 里，滚轮会被外层吃掉；
    // 这里自己接管滚轮事件，保证列表能滚。
    val fileScroll = rememberScrollState()
    val fileScope = rememberCoroutineScope()
    val totalSize = remember(files) { SessionAnalytics.totalSizeBytes(files) }
    val sizeStr = when {
        totalSize >= 1024L * 1024 -> "%.1f MB".format(totalSize / 1024.0 / 1024)
        totalSize >= 1024 -> "%.1f KB".format(totalSize / 1024.0)
        else -> "$totalSize B"
    }
    Surface(
        color = CardColor(),
        shape = RoundedCornerShape(Radius.MD_MINUS),
        border = BorderStroke(1.dp, BorderSubtle()),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            // 头部：{N} 个文件 > · 总大小
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showPaths = !showPaths },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${files.size} 个文件",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary()
                )
                Spacer(Modifier.weight(1f))
                Text(sizeStr, fontSize = 12.sp, color = TextSecondary())
            }
            Spacer(Modifier.height(10.dp))

            // 【体验】不再显示横滑文件方块（占地方且与下方列表重复），
            // 直接展开完整路径列表，每行可点击打开文件。
            if (showPaths) {
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .onPointerEvent(PointerEventType.Scroll) { ev ->
                            val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (dy != 0f) {
                                fileScope.launch { fileScroll.scrollBy(dy * 80f) }
                            }
                        }
                        .verticalScroll(fileScroll)
                ) {
                    files.forEach { f ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.MICRO))
                                .clickable { openLocalFile(f.path) }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                f.path,
                                fontSize = 12.sp,
                                color = TextSecondary(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            val actionLabel = when (f.lastAction) {
                                "write" -> "写入"
                                "edit" -> "编辑"
                                "delete" -> "删除"
                                "move" -> "移动"
                                else -> f.lastAction
                            }
                            Text(
                                "$actionLabel ×${f.count}",
                                fontSize = 11.sp,
                                color = TextSecondary().copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

// 顶部功能区：子会话进度 + 子代理/修改历史入口（带边框，固定于消息区顶部）

@Composable
internal fun ChatTopBar(
    modifier: Modifier = Modifier,
    sessionId: String? = null,
    onOpenHistory: () -> Unit = {}
) {
    var subListOpen by remember { mutableStateOf(false) }
    var promptOpen by remember { mutableStateOf(false) }
    var hasPrompt by remember(sessionId) { mutableStateOf(false) }
    val progress by SubSessionProgress.state.collectAsState()
    val topBarScope = rememberCoroutineScope()

    // 会话专用提示词已配置标记（按钮上的 ●）
    LaunchedEffect(sessionId) {
        val sid = sessionId ?: return@LaunchedEffect
        topBarScope.launch(Dispatchers.IO) {
            hasPrompt = !DesktopSessionStore.load(sid)?.systemPrompt.isNullOrBlank()
        }
    }

    // 贴顶通栏：背景用侧边栏同款色 token（SidebarBg），底线用侧边栏分隔线同款（BorderColor）
    Column(modifier = modifier.background(SidebarBg())) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 【三个入口固定在最左边】子代理 / 修改历史 / 提示词
            // 以前子会话进度段在最前面，一跑子代理就把这三个按钮顶到右边、
            // 甚至被弹窗盖住点不到。现在固定顺序：按钮在左，进度在右。
            // 在子会话内：一键返回来路会话（标准带边框按钮）
            if (sessionId?.startsWith("sub_") == true && SubSessionNavigator.originId != null) {
                OutlinedButton(
                    onClick = {
                        SubSessionNavigator.originId?.let { SubSessionNavigator.open?.invoke(it) }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    border = BorderStroke(1.dp, BorderSubtle()),
                    shape = RoundedCornerShape(Radius.TINY),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text("← 返回", fontSize = 12.sp, color = AccentColor())
                }
                Spacer(Modifier.width(10.dp))
            }
            OutlinedButton(
                onClick = { subListOpen = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                border = BorderStroke(1.dp, BorderSubtle()),
                shape = RoundedCornerShape(Radius.TINY),
                modifier = Modifier.height(26.dp)
            ) {
                Text("子代理 ▾", fontSize = 12.sp, color = TextSecondary())
            }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(
                onClick = { onOpenHistory() },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                border = BorderStroke(1.dp, BorderSubtle()),
                shape = RoundedCornerShape(Radius.TINY),
                modifier = Modifier.height(26.dp)
            ) {
                Text("修改历史", fontSize = 12.sp, color = TextSecondary())
            }
            // 会话专用提示词入口：已配置时高亮 ●，一眼可见当前会话带指令
            if (sessionId != null) {
                Spacer(Modifier.width(6.dp))
                OutlinedButton(
                    onClick = { promptOpen = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    border = BorderStroke(1.dp, if (hasPrompt) AccentColor().copy(alpha = 0.5f) else BorderSubtle()),
                    shape = RoundedCornerShape(Radius.TINY),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text(
                        if (hasPrompt) "提示词 ●" else "提示词",
                        fontSize = 12.sp,
                        color = if (hasPrompt) AccentColor() else TextSecondary()
                    )
                }
            }

            // 【子会话进度】放在最右边：占剩余空间，永远不会挤掉左边的按钮
            if (progress.running) {
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).padding(end = 8.dp), contentAlignment = Alignment.CenterEnd) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f, fill = false)) {
                            Text(
                                if (progress.activeCount > 1) "并行 ${progress.activeCount} 个子会话执行中"
                                else "子代理 · ${progress.title}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                buildString {
                                    if (progress.activeCount > 1) {
                                        val snap = SubSessionProgress.snapshot()
                                        append("共 ${snap.values.sumOf { it.steps }} 步")
                                        val tools = snap.values.map { it.lastTool }
                                            .filter { it.isNotBlank() }.distinct()
                                        if (tools.isNotEmpty()) append(" · " + tools.joinToString("、"))
                                    } else {
                                        append("${progress.steps} 步")
                                        if (progress.phase.isNotBlank()) append(" · ${progress.phase}")
                                        if (progress.lastTool.isNotEmpty()) append(" · ${progress.lastTool}")
                                    }
                                },
                                fontSize = 10.5.sp,
                                color = TextSecondary(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // 【关键】最新一行内容：不开子会话也能看到它在写什么
                            if (progress.snippet.isNotBlank()) {
                                Text(
                                    progress.snippet,
                                    fontSize = 10.sp,
                                    color = TextMuted(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(
                            Modifier.size(13.dp),
                            strokeWidth = 2.dp,
                            color = AccentColor()
                        )
                    }
                }
            }
        }
        // 唯一边框：通栏底线（基础组件 HorizontalDivider，颜色与侧边栏分隔线一致）
        HorizontalDivider(
            thickness = 1.dp,
            color = BorderColor()
        )
    }
    if (subListOpen) SubSessionListDialog(onDismiss = { subListOpen = false })
    if (promptOpen && sessionId != null) {
        val sid = sessionId
        SessionPromptDialog(
            sessionId = sid,
            onDismiss = { promptOpen = false },
            onSaved = { saved -> hasPrompt = saved.isNotBlank() }
        )
    }
}

/**
 * 会话专用提示词编辑：只作用于当前会话，追加在全局系统提示词之后。
 * 空文本保存 = 清除本会话的专用提示词。
 */
@Composable
internal fun SessionPromptDialog(
    sessionId: String,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var loaded by remember(sessionId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(sessionId) {
        scope.launch(Dispatchers.IO) {
            val sp = DesktopSessionStore.load(sessionId)?.systemPrompt ?: ""
            text = sp
            loaded = true
        }
    }
    GlassDialog(
        title = "会话专用提示词",
        onDismiss = onDismiss,
        width = 560.dp,
        content = {
            Text(
                "只作用于当前这个会话，追加在全局系统提示词之后；留空即不使用。保存后下一条消息生效。",
                fontSize = 12.sp,
                color = TextSecondary(),
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(10.dp))
            if (!loaded) {
                Text("加载中…", fontSize = 12.sp, color = TextMuted())
            } else {
                GlassTextField(
                    text, { text = it },
                    placeholder = "例如：本会话始终用中文简洁回复；讨论代码时先给结论再给过程",
                    singleLine = false, minLines = 8, maxLines = 16
                )
            }
        },
        actions = {
            OutlinedButton(
                onClick = onDismiss,
                border = BorderStroke(1.dp, BorderSubtle()),
                shape = RoundedCornerShape(Radius.TINY)
            ) {
                Text("取消", fontSize = 13.sp, color = TextSecondary())
            }
            OutlinedButton(
                onClick = {
                    val trimmed = text.trim()
                    // 同步保存：load → copy → save，避免与紧随其后的发消息 persist 竞态
                    runCatching {
                        DesktopSessionStore.load(sessionId)?.let { s ->
                            DesktopSessionStore.save(s.copy(systemPrompt = trimmed))
                        }
                    }
                    onSaved(trimmed)
                    onDismiss()
                },
                border = BorderStroke(1.dp, AccentColor().copy(alpha = 0.6f)),
                shape = RoundedCornerShape(Radius.TINY)
            ) {
                Text("保存", fontSize = 13.sp, color = AccentColor())
            }
        }
    )
}

/** 子代理入口：历史子会话列表（title 以 ↳ 开头），点击切入。 */
@Composable
internal fun SubSessionListDialog(onDismiss: () -> Unit) {
    var items by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            items = DesktopSessionStore.list()
                .filter { it.parentId != null }
                .map { it.id to it.title }
        }
    }

    GlassDialog(
        title = "子代理 · 子会话列表",
        onDismiss = onDismiss,
        width = 560.dp,
        actions = {
            OutlinedButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        DesktopSessionStore.list()
                            .filter { it.parentId != null }
                            .forEach { DesktopSessionStore.delete(it.id) }
                        items = DesktopSessionStore.list()
                            .filter { it.parentId != null }
                            .map { it.id to it.title }
                    }
                },
                border = BorderStroke(1.dp, BorderSubtle()),
                shape = RoundedCornerShape(Radius.TINY)
            ) {
                Text("清空子会话", fontSize = 12.sp, color = ErrorColor())
            }
        },
        content = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (items.isEmpty()) {
                    Text(
                        "暂无子会话。AI 需要以全新上下文执行子任务时会自动派生子会话（不带本对话历史），执行进度显示在顶部功能区。",
                        fontSize = 12.sp,
                        color = TextSecondary(),
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                for ((i, pair) in items.withIndex()) {
                    val (id, title) = pair
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                SubSessionNavigator.open?.invoke(id)
                                onDismiss()
                            }
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextSecondary())
                        Spacer(Modifier.width(8.dp))
                        Text(
                            title,
                            fontSize = 13.sp,
                            color = TextPrimary(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text("→", fontSize = 13.sp, color = TextSecondary())
                    }
                    if (i != items.lastIndex) GlassDivider(color = BorderSubtle())
                }
            }
        }
    )
}

@Composable
internal fun TokenStatusBar(messages: List<DesktopMessage>) {
    val stats = remember(messages) { SessionAnalytics.sessionStats(messages) }
    // 真实开销：按 Agent 每轮实际请求重放（输入 = 每轮整段历史之和）
    val cost = remember(messages) { SessionAnalytics.costStats(messages) }
    // 修改历史入口已挪到聊天页顶部功能区（ChatTopBar）
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${stats.rounds} 轮 · ${stats.toolSteps} 步 · ${cost.requests} 次请求 · 开销 ~${fmtTok(cost.totalTokens)} tok（输入 ${fmtTok(cost.promptTokens)} / 输出 ${fmtTok(cost.completionTokens)}）",
            fontSize = 11.sp,
            color = TextSecondary().copy(alpha = 0.75f)
        )
    }
}

/** token 数展示：万以下直出，以上用 k / M 缩写。 */
private fun fmtTok(v: Long): String = when {
    v >= 1_000_000 -> "%.2fM".format(v / 1_000_000.0)
    v >= 10_000 -> "%.1fk".format(v / 1000.0)
    else -> v.toString()
}

// 修改历史 + 一键回档（聊天页底栏入口）

@Composable
internal fun BackupHistoryDialog(onDismiss: () -> Unit) {
    val backupDir = remember {
        BackupHelper.backupDirFor(TrashHelper.defaultTrashDir().absolutePath)
    }
    var entries by remember { mutableStateOf(emptyList<BackupHelper.BackupEntry>()) }
    var status by remember { mutableStateOf<String?>(null) }
    var expandedIdx by remember { mutableStateOf(-1) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(backupDir) {
        entries = HistoryStore.listHistory(backupDir, 200)
    }

    GlassDialog(
        title = "修改历史",
        onDismiss = onDismiss,
        width = 860.dp,
        actions = {},
        content = {
            Column(Modifier.heightIn(max = 580.dp)) {
                // 概览：多少次修改、涉及多少文件、总增删行
                val fileCount = entries.map { it.path }.distinct().size
                val addTotal = entries.sumOf { it.added }
                val delTotal = entries.sumOf { it.removed }
                Text(
                    if (entries.isEmpty()) "暂无修改记录"
                    else "共 ${entries.size} 次修改 · $fileCount 个文件 · +$addTotal / -$delTotal",
                    fontSize = 12.sp,
                    color = TextSecondary(),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                if (entries.isEmpty()) {
                    Text(
                        "开启「写前备份与回档」后，AI 每次写入/编辑文件都会在这里留下 patch 记录：" +
                                "能看到改了什么（+/- 行）、什么时候改的，也能一键回档。",
                        fontSize = 12.sp,
                        color = TextSecondary(),
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    for ((i, e) in entries.withIndex()) {
                        HistoryEntryRow(
                            entry = e,
                            expanded = expandedIdx == i,
                            onToggle = { expandedIdx = if (expandedIdx == i) -1 else i },
                            onRestore = {
                                scope.launch(Dispatchers.IO) {
                                    status = "正在回档…"
                                    val bytes = HistoryStore.readBackup(e)
                                    if (bytes == null) {
                                        status = "回档失败：备份文件缺失"
                                        return@launch
                                    }
                                    try {
                                        val target = File(e.path)
                                        target.parentFile?.mkdirs()
                                        target.writeBytes(bytes)
                                        status = "已回档：${e.path}"
                                    } catch (ex: Exception) {
                                        status = "回档失败：${ex.message}"
                                    }
                                }
                            }
                        )
                        if (i != entries.lastIndex) GlassDivider(color = BorderSubtle())
                    }
                }
                status?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = TextPrimary(),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    )
}

/** 修改历史的一行：时间 + 文件 + 动作 + 增删行数，点击展开 patch。 */
@Composable
internal fun HistoryEntryRow(
    entry: BackupHelper.BackupEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRestore: () -> Unit
) {
    val actionLabel = when (entry.action) {
        "write" -> "写入"
        "append" -> "追加"
        "code_edit" -> "编辑"
        else -> entry.action
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                DateFormats.shortDateTimeSecFormatter()
                    .format(java.util.Date(entry.ts)),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = TextMuted()
            )
            Spacer(Modifier.width(10.dp))
            Text(
                File(entry.path).name,
                fontSize = 13.sp,
                color = TextPrimary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (entry.added > 0) {
                Text(
                    "+${entry.added}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ResultColor()
                )
                Spacer(Modifier.width(6.dp))
            }
            if (entry.removed > 0) {
                Text(
                    "-${entry.removed}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ErrorColor()
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(actionLabel, fontSize = 11.sp, color = TextSecondary())
            Spacer(Modifier.width(10.dp))
            Text(
                "回档",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = AccentColor(),
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.MICRO))
                    .clickable { onRestore() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Text(
            entry.path,
            fontSize = 10.5.sp,
            color = TextMuted(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 1.dp)
        )
        if (expanded) {
            if (entry.patch.isBlank()) {
                Text(
                    "（无文本差异：二进制文件，或新建/删除文件）",
                    fontSize = 11.sp,
                    color = TextMuted(),
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                PatchView(entry.patch)
            }
        }
    }
}

/** patch 渲染：+ 绿 / - 红 / @@ 蓝 / 上下文灰。 */
@Composable
internal fun PatchView(patch: String) {
    val lines = remember(patch) { patch.lines().take(300) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(Radius.TINY))
            .background(CardStrongColor())
            .padding(8.dp)
    ) {
        lines.forEach { line ->
            val color = when {
                line.startsWith("+++") || line.startsWith("---") -> TextMuted()
                line.startsWith("@@") -> AccentColor()
                line.startsWith("+") -> ResultColor()
                line.startsWith("-") -> ErrorColor()
                else -> TextSecondary()
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

// 输入卡片 / ChipSurface / ThinkingToggleChip / ModelQuickSelector / AttachmentThumb
// → 已拆到 ChatInputCard.kt

private fun formatTime(ts: Long): String =
    DateFormats.formatTimeShort(ts)
