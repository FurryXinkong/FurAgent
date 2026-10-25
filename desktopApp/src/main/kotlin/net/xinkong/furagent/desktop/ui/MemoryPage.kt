package net.xinkong.furagent.desktop.ui

import net.xinkong.furagent.shared.util.DateFormats
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.xinkong.furagent.desktop.data.Memory
import net.xinkong.furagent.desktop.data.MemoryStore
import net.xinkong.furagent.desktop.ui.theme.Radius
import net.xinkong.furagent.desktop.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.*

/**
 * 长期记忆 —— 整屏页面。
 *
 * 每条记忆加 Switch：关闭后不注入 system prompt，但仍留在列表。
 * 新加的记忆默认开启。
 */
@Composable
fun MemoryPage(onOpenSession: (String) -> Unit = {}) {
    var refreshKey by remember { mutableIntStateOf(0) }
    // 进入记忆页时强制重读：会话被删除后，它的旧摘要不该继续挂在列表里。
    LaunchedEffect(Unit) { refreshKey++ }
    var searchText by remember { mutableStateOf("") }
    var editingMemory by remember { mutableStateOf<Memory?>(null) }
    var pendingDelete by remember { mutableStateOf<Memory?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    // 【P1-3】查看摘要详情
    var detailMemory by remember { mutableStateOf<Memory?>(null) }
    // 【P1-2】页签：all / summary / normal
    var tab by remember { mutableStateOf("all") }

    val allMemories = remember(refreshKey) { MemoryStore.list() }
    val summaryCount = allMemories.count { it.sessionId.isNotBlank() }
    val normalCount = allMemories.size - summaryCount
    val tabFiltered = remember(allMemories, tab) {
        when (tab) {
            "summary" -> allMemories.filter { it.sessionId.isNotBlank() }
            "normal" -> allMemories.filter { it.sessionId.isBlank() }
            else -> allMemories
        }
    }
    val filtered = remember(tabFiltered, searchText) {
        if (searchText.isBlank()) tabFiltered
        else tabFiltered.filter { it.content.contains(searchText, ignoreCase = true) }
    }

    val enabledCount = allMemories.count { it.enabled }

    Column(Modifier.fillMaxSize()) {
        PageTitleBar(
            title = "长期记忆",
            subtitle = "${allMemories.size} 条 · $enabledCount 条启用 · 每次对话自动注入",
            actions = {
                PrimaryButton(text = "添加记忆", icon = Icons.Default.Add) { showAdd = true }
                if (allMemories.isNotEmpty()) {
                    DangerButton(text = "清空全部") { showClearConfirm = true }
                }
            }
        )

        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = Spacing.PAGE_H,
                    vertical = Spacing.PAGE_V,
                ),
            verticalArrangement = Arrangement.spacedBy(Spacing.ROW_GAP)
        ) {
            // 【P1-2】页签：全部 / 会话摘要 / 普通记忆
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MemoryTab("全部", allMemories.size, tab == "all") { tab = "all" }
                MemoryTab("会话摘要", summaryCount, tab == "summary") { tab = "summary" }
                MemoryTab("普通记忆", normalCount, tab == "normal") { tab = "normal" }
            }

            Box(Modifier.width(320.dp)) {
                GlassTextField(
                    searchText,
                    { searchText = it },
                    singleLine = true,
                    placeholder = "搜索记忆内容",
                    leadingIcon = Icons.Default.Search
                )
            }

            HintText(
                if (tab == "summary") "会话摘要由摘要模型自动生成，每天首次打开时更新。关闭开关可让它不再注入上下文。"
                else "AI 会在用户透露姓名、职业、偏好、习惯、忌讳或纠正事实时自动保存。" +
                        "关闭某条的开关可让它不再注入上下文（但仍在列表里）。"
            )
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            if (filtered.isEmpty()) {
                EmptyMemoryState(isEmpty = allMemories.isEmpty())
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = Spacing.PAGE_H,
                        vertical = Spacing.PAGE_V,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.CARD_GAP)
                ) {
                    items(filtered, key = { it.id }) { m ->
                        Box(
                            Modifier.animateItem(
                                fadeInSpec = tween(220),
                                placementSpec = tween(260),
                                fadeOutSpec = tween(160)
                            )
                        ) {
                            MemoryRow(
                                memory = m,
                                onToggleEnabled = { enabled ->
                                    MemoryStore.setEnabled(m.id, enabled)
                                    refreshKey++
                                },
                                onOpenSession = { onOpenSession(m.sessionId) },
                                onViewDetail = { detailMemory = m },
                                onCopy = {
                                    SafeClipboard.setText(m.content)
                                    ToastBus.info("已复制")
                                },
                                onEdit = { editingMemory = m },
                                onDelete = { pendingDelete = m }
                            )
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    editingMemory?.let { m ->
        var draft by remember(m.id) { mutableStateOf(m.content) }
        GlassDialog(
            title = "编辑记忆",
            onDismiss = { editingMemory = null },
            width = 560.dp,
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = false,
                        minLines = 4,
                        maxLines = 12
                    )
                    HintText("修改后下一次对话会立即生效。")
                }
            },
            actions = {
                TextButton(onClick = { editingMemory = null }) {
                    Text("取消", color = TextSecondary())
                }
                TextButton(onClick = {
                    if (draft.isNotBlank()) {
                        MemoryStore.update(m.id, draft.trim())
                        refreshKey++
                    }
                    editingMemory = null
                }) {
                    Text("保存", color = AccentColor(), fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    if (showAdd) {
        var draft by remember { mutableStateOf("") }
        GlassDialog(
            title = "添加记忆",
            onDismiss = { showAdd = false },
            width = 560.dp,
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = false,
                        minLines = 4,
                        maxLines = 12,
                        placeholder = "例如：用户是一名 Android 开发者"
                    )
                }
            },
            actions = {
                TextButton(onClick = { showAdd = false }) {
                    Text("取消", color = TextSecondary())
                }
                TextButton(onClick = {
                    if (draft.isNotBlank()) {
                        MemoryStore.add(draft.trim())
                        refreshKey++
                        ToastBus.info("已添加")
                    }
                    showAdd = false
                }) {
                    Text("添加", color = AccentColor(), fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    pendingDelete?.let { m ->
        ConfirmDialog(
            title = "删除记忆",
            message = "确定删除这条记忆？\n\n${m.content.take(120)}${if (m.content.length > 120) "…" else ""}",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                MemoryStore.remove(m.id)
                refreshKey++
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }

    if (showClearConfirm) {
        ConfirmDialog(
            title = "清空全部记忆",
            message = "将删除 ${allMemories.size} 条记忆，此操作不可恢复。是否继续？",
            confirmText = "清空",
            danger = true,
            onConfirm = {
                MemoryStore.clear()
                refreshKey++
                showClearConfirm = false
                ToastBus.info("已清空")
            },
            onDismiss = { showClearConfirm = false }
        )
    }

    detailMemory?.let { m ->
        SummaryDetailDialog(memory = m, onDismiss = { detailMemory = null })
    }
}

// 页签（【P1-2】全部 / 会话摘要 / 普通记忆）

@Composable
private fun MemoryTab(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (selected) AccentColor() else CardStrongColor(),
        tween(140), label = "memoryTabBg"
    )
    Row(
        Modifier.clip(RoundedCornerShape(Radius.SM))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label, fontSize = 12.sp,
            color = if (selected) OnAccent() else TextPrimary(),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
        Spacer(Modifier.width(6.dp))
        Text(
            count.toString(), fontSize = 11.sp,
            color = if (selected) OnAccent() else TextMuted(),
            fontFamily = FontFamily.Monospace
        )
    }
}

// 空状态

@Composable
private fun EmptyMemoryState(isEmpty: Boolean) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Psychology,
            contentDescription = null,
            tint = TextMuted(),
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            if (isEmpty) "暂无记忆" else "无匹配项",
            fontSize = 15.sp,
            color = TextSecondary()
        )
        if (isEmpty) {
            Spacer(Modifier.height(8.dp))
            Text(
                "在聊天里对 AI 说「记住我不吃香菜」，它会自动存进来",
                fontSize = 12.sp,
                color = TextMuted()
            )
        }
    }
}

// 记忆行

@Composable
private fun MemoryRow(
    memory: Memory,
    onToggleEnabled: (Boolean) -> Unit,
    onOpenSession: () -> Unit,
    onViewDetail: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val time = DateFormats.formatDateTimeNoSec(memory.createdAt)
    val isSummary = memory.sessionId.isNotBlank()

    // 【批次 16 UI 统一】卡片渲染收口到 GlassCard，不再手写 Surface + border + shadow。
    // hover 效果通过 GlassCard 的 hoverable 参数保留（原来无条件 hover）。
    // isSummary 时可点击（跳转会话），其他情况传 null。
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = if (isSummary) onOpenSession else null,
        padding = PaddingValues(
            horizontal = Spacing.LIST_H,
            vertical = Spacing.LIST_V,
        ),
        hoverable = true,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                // 【P1-2】会话摘要徽章
                if (isSummary) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.clip(RoundedCornerShape(Radius.XS))
                                .background(AccentColor().copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "会话摘要",
                                fontSize = 10.sp,
                                color = AccentColor(),
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("点击跳转会话", fontSize = 10.sp, color = TextMuted())
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    memory.content,
                    fontSize = 13.sp,
                    color = if (memory.enabled) TextPrimary() else TextSecondary(),
                    lineHeight = 19.sp
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "创建于 $time · ID ${memory.id.take(8)}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted()
                    )
                    if (!memory.enabled) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "已停用",
                            fontSize = 11.sp,
                            color = TextMuted(),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            Switch(
                checked = memory.enabled,
                onCheckedChange = onToggleEnabled,
                colors = glassSwitchColors()
            )

            Box {
                IconActionButton(
                    Icons.Default.MoreVert,
                    onClick = { menuOpen = true },
                    tint = TextSecondary(),
                    size = 28.dp
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = CardColor()
                ) {
                    DropdownMenuItem(
                        text = { Text("复制", fontSize = 12.sp, color = TextPrimary()) },
                        onClick = { menuOpen = false; onCopy() }
                    )
                    if (isSummary) {
                        DropdownMenuItem(
                            text = { Text("查看摘要详情", fontSize = 12.sp, color = TextPrimary()) },
                            onClick = { menuOpen = false; onViewDetail() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("编辑", fontSize = 12.sp, color = TextPrimary()) },
                        onClick = { menuOpen = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text = { Text("删除", fontSize = 12.sp, color = ErrorColor()) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryDetailDialog(memory: Memory, onDismiss: () -> Unit) {
    val detail = remember(memory.id) {
        try { org.json.JSONObject(memory.summaryDetail) } catch (_: Exception) { null }
    }

    GlassDialog(
        title = "摘要详情",
        onDismiss = onDismiss,
        width = 720.dp,
        content = {
            if (detail == null) {
                Text("这条摘要没有保存详情（可能是旧版本生成的）。", fontSize = 13.sp, color = TextSecondary())
            } else {
                Column(
                    Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    DetailSection("摘要结果", listOf(memory.content))
                    DetailSection("高频工具", jsonList(detail, "tools"))
                    DetailSection("涉及文件", jsonList(detail, "paths"))
                    DetailSection("执行命令", jsonList(detail, "commands"))
                    DetailSection("高频词", jsonList(detail, "words"))
                    DetailSection("喂给模型的 Prompt", listOf(detail.optString("prompt", "")))
                    DetailSection("模型原始输出", listOf(detail.optString("raw", "")))
                    Text(
                        "共 ${detail.optInt("messageCount", 0)} 条消息参与统计",
                        fontSize = 11.sp, color = TextMuted()
                    )
                }
            }
        },
        actions = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = AccentColor(), fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

private fun jsonList(o: org.json.JSONObject, key: String): List<String> {
    val arr = o.optJSONArray(key) ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optString(it, "").takeIf { s -> s.isNotBlank() } }
}

@Composable
private fun DetailSection(title: String, items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AccentColor())
        if (items.isEmpty()) {
            Text("（无）", fontSize = 12.sp, color = TextMuted())
        } else {
            items.forEach { item ->
                Box(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.XS))
                        .background(CardStrongColor())
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        item,
                        fontSize = 12.sp,
                        color = TextPrimary(),
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}