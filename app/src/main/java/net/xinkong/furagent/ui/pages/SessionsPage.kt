@file:OptIn(ExperimentalMaterial3Api::class)

package net.xinkong.furagent.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.xinkong.furagent.data.SessionMeta
import net.xinkong.furagent.ui.ApplyBackgroundBlur
import net.xinkong.furagent.ui.GlassSheen
import net.xinkong.furagent.ui.PocoCard
import net.xinkong.furagent.ui.PocoEmptyState
import net.xinkong.furagent.ui.PocoPageTopBar
import net.xinkong.furagent.ui.glassPageBg
import java.text.SimpleDateFormat
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * 会话列表 —— 整屏页面。
 *
 * 【UI 统一约定】
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
    onBack: () -> Unit
) {
    ApplyBackgroundBlur(true)
    var searchQuery by remember { mutableStateOf("") }
    var headerMenuOpen by remember { mutableStateOf(false) }

    val filtered = remember(sessions, searchQuery) {
        if (searchQuery.isBlank()) sessions
        else sessions.filter {
            it.title.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            PocoPageTopBar(
                title = "会话",
                subtitle = "${sessions.size} 个会话",
                onBack = onBack,
                actions = {
                    Box {
                        IconButton(onClick = { headerMenuOpen = true }) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "更多操作",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        DropdownMenu(
                            expanded = headerMenuOpen,
                            onDismissRequest = { headerMenuOpen = false },
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            DropdownMenuItem(
                                text = { Text("新建会话") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.AddComment,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                onClick = {
                                    headerMenuOpen = false
                                    onCreate()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("导入会话（JSON / Markdown）…") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.FileOpen,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                onClick = {
                                    headerMenuOpen = false
                                    onImport()
                                }
                            )
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
                    shape = RoundedCornerShape(14.dp),
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
                                enter = fadeIn(tween(200)) + slideInVertically(
                                    animationSpec = tween(240),
                                    initialOffsetY = { it / 3 }
                                )
                            ) {
                                SessionRow(
                                    meta = s,
                                    isCurrent = s.id == currentId,
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

// 【批次 20 UI 收口】EmptySessionsState 已合并到 PocoEmptyState（Glass.kt）。

// ============================================================
// 会话行
// ============================================================
//
// 【UI 统一】用 PocoCard 承载。选中态用 primary 描边包裹。

@Composable
private fun SessionRow(
    meta: SessionMeta,
    isCurrent: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDuplicate: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(meta.updatedAt))

    // 【批次 22 UI 收口】用 PocoCard(selectedBorder) 替代「外层 Box 描边 + 内层 PocoCard」双层结构。
    PocoCard(selectedBorder = isCurrent) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    meta.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "$time · ${meta.messageCount} 条",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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