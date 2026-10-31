@file:OptIn(ExperimentalMaterial3Api::class)

package net.xinkong.furagent.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.xinkong.furagent.data.SessionMeta
import net.xinkong.furagent.shared.icons.Icons
import net.xinkong.furagent.shared.util.DateFormats
import net.xinkong.furagent.ui.*
import net.xinkong.furagent.ui.theme.AppRadius
import kotlin.time.Duration.Companion.milliseconds

/**
 * 会话列表 —— 整屏页面。
 *
 * UI 统一约定
 *   - 卡片统一 PocoCard
 *   - 页背景 glassPageBg()
 *   - 空状态图标 56dp + onSurfaceVariant 文案
 *   - 主色 / 危险色走 MaterialTheme.colorScheme
 */
@Composable
fun SessionsPage(
    sessions: List<SessionMeta>,
    currentId: String?,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String) -> Unit,
    onExport: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onImport: () -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
    /** 批量删除：selectedIds 一次性交给上层。为 null 时回退到多次调 onDelete（不推荐）。 */
    onDeleteBatch: ((Set<String>) -> Unit)? = null
) {
    ApplyBackgroundBlur(true)
    var searchQuery by remember { mutableStateOf("") }
    var headerMenuOpen by remember { mutableStateOf(false) }
    // 排序模式：0=最新更新, 1=最旧, 2=名称A-Z, 3=消息最多
    var sortMode by remember { mutableIntStateOf(0) }
    // 批量选择模式
    var selectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    val sorted = remember(sessions, sortMode) {
        when (sortMode) {
            1 -> sessions.sortedBy { it.updatedAt }
            2 -> sessions.sortedBy { it.title.lowercase() }
            3 -> sessions.sortedByDescending { it.messageCount }
            else -> sessions.sortedByDescending { it.updatedAt }
        }
    }
    val filtered = remember(sorted, searchQuery) {
        if (searchQuery.isBlank()) sorted
        else sorted.filter {
            it.title.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            PocoPageTopBar(
                title = if (selectMode) "已选 ${selectedIds.size} 项" else "会话",
                subtitle = if (selectMode) "长按勾选会话" else "${sessions.size} 个会话",
                onBack = {
                    if (selectMode) { selectMode = false; selectedIds = emptySet() }
                    else onBack()
                },
                actions = {
                    if (selectMode) {
                        // 批量删除按钮
                        IconButton(
                            onClick = {
                                if (selectedIds.isNotEmpty()) {
                                    if (onDeleteBatch != null) {
                                        // 一次性交给上层：旧实现 selectedIds.forEach { onDelete(it) }
                                        // 而 MainActivity 的 onDelete 是 pendingDeleteSessionId = id（单变量），
                                        // 循环 5 次只留最后一个，只弹一次确认、只删一个。
                                        onDeleteBatch(selectedIds)
                                    } else {
                                        selectedIds.forEach { onDelete(it) }
                                    }
                                    selectMode = false
                                    selectedIds = emptySet()
                                }
                            },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Delete, "批量删除",
                                tint = if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // 全选/取消全选
                        IconButton(onClick = {
                            if (selectedIds.size == filtered.size) selectedIds = emptySet()
                            else selectedIds = filtered.map { it.id }.toSet()
                        }) {
                            Icon(Icons.Default.SelectAll, "全选")
                        }
                    } else {
                        Box {
                            IconButton(onClick = { headerMenuOpen = true }) {
                                Icon(Icons.Default.MoreVert, "更多操作",
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                            DropdownMenu(
                                expanded = headerMenuOpen,
                                onDismissRequest = { headerMenuOpen = false },
                                containerColor = MaterialTheme.colorScheme.surface
                            ) {
                                DropdownMenuItem(
                                    text = { Text("新建会话") },
                                    leadingIcon = { Icon(Icons.Default.AddComment, null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    onClick = { headerMenuOpen = false; onCreate() }
                                )
                                DropdownMenuItem(
                                    text = { Text("导入会话…") },
                                    leadingIcon = { Icon(Icons.Default.FileOpen, null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    onClick = { headerMenuOpen = false; onImport() }
                                )
                                // 排序子菜单
                                val sortLabels = listOf("最新更新", "最旧优先", "名称 A-Z", "消息最多")
                                DropdownMenuItem(
                                    text = { Text("排序：${sortLabels[sortMode]}") },
                                    leadingIcon = { Icon(Icons.Default.Sort, null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    onClick = {
                                        sortMode = (sortMode + 1) % 4
                                        headerMenuOpen = false
                                    }
                                )
                                // 批量管理
                                DropdownMenuItem(
                                    text = { Text("批量管理") },
                                    leadingIcon = { Icon(Icons.Default.Checklist, null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    onClick = { headerMenuOpen = false; selectMode = true }
                                )
                            }
                        }
                    }
                }
            )
        },
        containerColor = glassPageBg(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
        ) {
            GlassSheen(
                modifier = Modifier.align(Alignment.TopCenter),
                height = 100.dp,
                alpha = 0.06f
            )
            Column(Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索会话…") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            TextButton(onClick = { searchQuery = "" }) { Text("×") }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(AppRadius.MD_MINUS),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )

                if (filtered.isEmpty()) {
                    val empty = searchQuery.isBlank()
                    PocoEmptyState(
                        icon = Icons.AutoMirrored.Filled.List,
                        title = if (empty) "暂无会话" else "无匹配结果",
                        subtitle = if (empty) "点击右上角 + 新建一个会话" else null,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(filtered, key = { _, s -> s.id }) { index, s ->
                            var visible by remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) {
                                delay((40L * index.coerceAtMost(10)).milliseconds)
                                visible = true
                            }
                            AnimatedVisibility(
                                visible = visible,
                                modifier = Modifier.animateItem(),
                                enter = fadeIn(tween(200)) + slideInVertically(
                                    animationSpec = tween(240),
                                    initialOffsetY = { it / 3 }
                                )
                            ) {
                                SessionRow(
                                    meta = s,
                                    isCurrent = s.id == currentId,
                                    selectMode = selectMode,
                                    isSelected = s.id in selectedIds,
                                    onToggleSelect = {
                                        selectedIds = if (s.id in selectedIds) selectedIds - s.id
                                        else selectedIds + s.id
                                    },
                                    onSelect = { onSelect(s.id) },
                                    onDelete = { onDelete(s.id) },
                                    onRename = { onRename(s.id) },
                                    onExport = { onExport(s.id) },
                                    onDuplicate = { onDuplicate(s.id) }
                                )
                            }
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}

// 批次 20 UI 收口 EmptySessionsState 已合并到 PocoEmptyState（Glass.kt）。

// 会话行

// UI 统一 用 PocoCard 承载。选中态用 primary 描边包裹。

@Composable
private fun SessionRow(
    meta: SessionMeta,
    isCurrent: Boolean,
    selectMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDuplicate: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val time = DateFormats.formatShortDateTime(meta.updatedAt)

    PocoCard(selectedBorder = isCurrent) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (selectMode) onToggleSelect()
                    else onSelect()
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    meta.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "$time · ${meta.messageCount} 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    DropdownMenuItem(
                        text = { Text("复制会话") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.ContentCopy,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = { showMenu = false; onDuplicate() }
                    )
                    DropdownMenuItem(
                        text = { Text("导出 Markdown") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Share,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = { showMenu = false; onExport() }
                    )
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Edit,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = { showMenu = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = { showMenu = false; onDelete() }
                    )
                }
            }
        }
    }
}