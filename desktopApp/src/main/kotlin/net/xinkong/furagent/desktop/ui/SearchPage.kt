@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package net.xinkong.furagent.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import net.xinkong.furagent.desktop.data.DesktopSessionStore
import net.xinkong.furagent.desktop.ui.theme.Radius
import net.xinkong.furagent.shared.icons.Icons
import net.xinkong.furagent.shared.util.DateFormats
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

data class SearchHit(
    val sessionId: String,
    val sessionTitle: String,
    val messageId: String,
    val messageTimestamp: Long,
    val messageRole: String,
    val messageType: String,
    val content: String,
    val matchStart: Int,
    val matchEnd: Int
)

@Composable
fun SearchPage(
    onDismiss: () -> Unit,
    onJumpTo: (sessionId: String, messageId: String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    // 搜索 加 300ms debounce：每次按键触发全量扫描（SessionStore.list() + 逐个 load 读 JSON），
    // 多会话时 100~300ms 一次，用户连打三个字就是 3 次。debounce 后只有停顿 300ms 才触发。
    // collectLatest 保证新 query 到来时取消上一次未完成的搜索。
    LaunchedEffect(Unit) {
        snapshotFlow { query }
            .debounce(300)
            .collectLatest { q ->
                if (q.isBlank()) {
                    results = emptyList()
                    selectedIndex = 0
                    return@collectLatest
                }
                results = with(kotlinx.coroutines.Dispatchers.IO) {
                    searchAll(q, maxResults = 200)
                }
                selectedIndex = 0
            }
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60.milliseconds)
        try { focusRequester.requestFocus() } catch (_: Exception) { }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.TopCenter
        ) {
            Surface(
                color = CardColor(),
                shape = RoundedCornerShape(Radius.MD),
                border = BorderStroke(1.dp, BorderColor()),
                shadowElevation = 20.dp,
                modifier = Modifier
                    .padding(top = 100.dp)
                    .widthIn(min = 700.dp, max = 1000.dp)
                    .heightIn(max = 640.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* 吃掉点击 */ }
            ) {
                Column(Modifier.padding(0.dp)) {
                    // 搜索框
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Search, null,
                            tint = TextSecondary(),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = TextPrimary(),
                                fontSize = 15.sp
                            ),
                            cursorBrush = SolidColor(AccentColor()),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester)
                                .onPreviewKeyEvent { ev ->
                                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (ev.key) {
                                        Key.Escape -> { onDismiss(); true }
                                        Key.DirectionDown -> {
                                            if (results.isNotEmpty()) {
                                                selectedIndex = (selectedIndex + 1).coerceAtMost(results.size - 1)
                                            }
                                            true
                                        }
                                        Key.DirectionUp -> {
                                            if (results.isNotEmpty()) {
                                                selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                                            }
                                            true
                                        }
                                        Key.Enter -> {
                                            results.getOrNull(selectedIndex)?.let { hit ->
                                                onJumpTo(hit.sessionId, hit.messageId)
                                                onDismiss()
                                            }
                                            true
                                        }
                                        else -> false
                                    }
                                },
                            decorationBox = { inner ->
                                Box {
                                    if (query.isEmpty()) {
                                        Text(
                                            "搜索所有会话中的消息…",
                                            fontSize = 15.sp,
                                            color = TextMuted()
                                        )
                                    }
                                    inner()
                                }
                            }
                        )
                        if (query.isNotEmpty()) {
                            IconActionButton(
                                Icons.Default.Close,
                                onClick = { query = "" },
                                tint = TextSecondary(),
                                size = 24.dp
                            )
                        }
                    }

                    GlassDivider(color = BorderSubtle())

                    // 结果列表
                    Box(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 540.dp)) {
                        if (query.isBlank()) {
                            Box(
                                Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "输入关键词开始搜索",
                                    fontSize = 13.sp, color = TextMuted()
                                )
                            }
                        } else if (results.isEmpty()) {
                            Box(
                                Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "未找到匹配 \"$query\" 的消息",
                                    fontSize = 13.sp, color = TextMuted()
                                )
                            }
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(
                                    results.size,
                                    key = { i -> "${results[i].sessionId}_${results[i].messageId}_$i" }
                                ) { i ->
                                    val hit = results[i]
                                    SearchResultRow(
                                        hit = hit,
                                        query = query,
                                        selected = i == selectedIndex,
                                        onClick = {
                                            selectedIndex = i
                                            onJumpTo(hit.sessionId, hit.messageId)
                                            onDismiss()
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // 底部提示
                    if (results.isNotEmpty()) {
                        GlassDivider(color = BorderSubtle())
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "共 ${results.size} 条结果",
                                fontSize = 11.sp, color = TextMuted()
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "↑↓ 选择 · Enter 跳转 · Esc 关闭",
                                fontSize = 11.sp, color = TextMuted()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    hit: SearchHit,
    query: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        selected -> AccentSoft()
        hovered -> CardHoverColor()
        else -> Color.Transparent
    }

    val timeFmt = DateFormats.shortDateTimeFormatter()
    val roleLabel = when {
        hit.messageType == "title" -> "标题"
        hit.messageRole == "user" -> "用户"
        hit.messageType == "tool_call" -> "工具调用"
        hit.messageType == "tool_result" -> "工具返回"
        else -> "AI"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(Radius.XS))
                    .background(CardStrongColor())
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    hit.sessionTitle,
                    fontSize = 10.sp,
                    color = TextPrimary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 240.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(roleLabel, fontSize = 10.sp, color = TextMuted())
            Spacer(Modifier.weight(1f))
            Text(
                timeFmt.format(Date(hit.messageTimestamp)),
                fontSize = 10.sp, color = TextMuted(),
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(6.dp))
        // 高亮匹配片段
        val snippet = buildSnippet(hit)
        Text(
            text = highlightQuery(snippet, query, TextPrimary(), AccentColor()),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = TextPrimary(),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun buildSnippet(hit: SearchHit): String {
    val content = hit.content.replace("\n", " ")
    val contextBefore = 40
    val contextAfter = 80
    val start = (hit.matchStart - contextBefore).coerceAtLeast(0)
    val end = (hit.matchEnd + contextAfter).coerceAtMost(content.length)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < content.length) "…" else ""
    return "$prefix${content.substring(start, end)}$suffix"
}

@Composable
private fun highlightQuery(
    text: String,
    query: String,
    normalColor: Color,
    highlightColor: Color
): androidx.compose.ui.text.AnnotatedString {
    return remember(text, query, normalColor, highlightColor) {
        val builder = androidx.compose.ui.text.buildAnnotatedString {
            if (query.isBlank()) {
                append(text)
                return@buildAnnotatedString
            }
            var i = 0
            val lower = text.lowercase(Locale.ROOT)
            val q = query.lowercase(Locale.ROOT)
            while (i < text.length) {
                val found = lower.indexOf(q, i)
                if (found < 0) {
                    append(text.substring(i))
                    break
                }
                if (found > i) append(text.substring(i, found))
                pushStyle(androidx.compose.ui.text.SpanStyle(
                    color = highlightColor,
                    fontWeight = FontWeight.SemiBold
                ))
                append(text.substring(found, found + query.length))
                pop()
                i = found + query.length
            }
        }
        builder
    }
}

private fun searchAll(query: String, maxResults: Int): List<SearchHit> {
    val q = query.lowercase(Locale.ROOT)
    if (q.isBlank()) return emptyList()
    val out = mutableListOf<SearchHit>()

    // 不再用解构赋值（依赖字段顺序，脆弱）。改成显式字段访问。

    // 先匹配会话标题：命中即加一条 type=title 的结果，
    // 让用户可以直接点击跳到该会话。messageId 为空，跳转时落到会话第一条。
    // 子代理会话不进搜索结果：它们是 AI 派生的内部会话，
    // 在「历史对话」里出现只会干扰用户找自己的对话。
    val metas = DesktopSessionStore.list().filter { it.parentId == null }
    for ((id, title, _, updatedAt) in metas) {
        val titleLower = title.lowercase(Locale.ROOT)
        val idx = titleLower.indexOf(q)
        if (idx >= 0) {
            out.add(
                SearchHit(
                    sessionId = id,
                    sessionTitle = title,
                    messageId = "",
                    messageTimestamp = updatedAt,
                    messageRole = "title",
                    messageType = "title",
                    content = "【会话标题】$title",
                    matchStart = idx,
                    matchEnd = idx + query.length
                )
            )
            if (out.size >= maxResults) return out.sortedByDescending { it.messageTimestamp }
        }
    }

    // 匹配消息正文
    for ((id) in metas) {
        val session = DesktopSessionStore.load(id) ?: continue
        for ((id1, role, content, type, timestamp) in session.messages) {
            if (type == "system_note") continue
            if (content.isBlank()) continue
            val idx = content.lowercase(Locale.ROOT).indexOf(q)
            if (idx >= 0) {
                out.add(
                    SearchHit(
                        sessionId = session.id,
                        sessionTitle = session.title,
                        messageId = id1,
                        messageTimestamp = timestamp,
                        messageRole = role,
                        messageType = type,
                        content = content,
                        matchStart = idx,
                        matchEnd = idx + query.length
                    )
                )
                if (out.size >= maxResults) return out.sortedByDescending { it.messageTimestamp }
            }
        }
    }
    return out.sortedByDescending { it.messageTimestamp }
}