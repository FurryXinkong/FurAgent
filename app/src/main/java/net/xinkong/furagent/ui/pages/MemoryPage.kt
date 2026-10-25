@file:OptIn(ExperimentalMaterial3Api::class)

package net.xinkong.furagent.ui.pages

import net.xinkong.furagent.shared.util.DateFormats
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.xinkong.furagent.data.Memory
import net.xinkong.furagent.data.MemoryStore
import net.xinkong.furagent.system.notifyToast
import net.xinkong.furagent.ui.ApplyBackgroundBlur
import net.xinkong.furagent.ui.GlassSheen
import net.xinkong.furagent.ui.PocoCard
import net.xinkong.furagent.ui.PocoEmptyState
import net.xinkong.furagent.ui.PocoPageTopBar
import net.xinkong.furagent.ui.glassPageBg
import net.xinkong.furagent.ui.pocoSwitchColors
import net.xinkong.furagent.ui.pocoFieldColors
import net.xinkong.furagent.ui.theme.AppRadius
import java.text.SimpleDateFormat
import java.util.*

/**
 * 长期记忆 —— 整屏页面。
 *
 * 每条记忆加 Switch：关闭后不注入 system prompt，但仍留在列表。
 * 新加的记忆默认开启。
 */
@Composable
fun MemoryPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }

    val allMemories = remember(refreshKey) { MemoryStore.list(context) }
    val memories = remember(allMemories, searchQuery) {
        if (searchQuery.isBlank()) allMemories
        else allMemories.filter { it.content.contains(searchQuery, ignoreCase = true) }
    }

    var editingId by remember { mutableStateOf<String?>(null) }
    var editingContent by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var newContent by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    val enabledCount = allMemories.count { it.enabled }

    ApplyBackgroundBlur(true)

    Scaffold(
        topBar = {
            PocoPageTopBar(
                title = "长期记忆",
                subtitle = "${allMemories.size} 条 · $enabledCount 条启用",
                onBack = onBack,
                actions = {
                    if (allMemories.isNotEmpty()) {
                        TextButton(onClick = { showClearConfirm = true }) {
                            Text("清空", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Button(
                    onClick = { showAdd = true },
                    shape = RoundedCornerShape(AppRadius.MD_MINUS),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("手动添加一条记忆")
                }
            }
        },
        containerColor = glassPageBg(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
        ) {
            GlassSheen(
                modifier = Modifier.fillMaxWidth(),
                height = 100.dp,
                alpha = 0.06f
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索记忆…") },
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
                colors = pocoFieldColors()
            )

            if (memories.isEmpty()) {
                val empty = searchQuery.isBlank()
                PocoEmptyState(
                    icon = Icons.Default.Psychology,
                    title = if (empty) "暂无记忆" else "无匹配结果",
                    subtitle = if (empty) "对 AI 说「记住我不吃香菜」，它就会记住" else null,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(memories, key = { it.id }) { m ->
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
                                    MemoryStore.setEnabled(context, m.id, enabled)
                                    refreshKey++
                                },
                                onCopy = {
                                    try {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                                as ClipboardManager
                                        cm.setPrimaryClip(
                                            ClipData.newPlainText("memory", m.content)
                                        )
                                        notifyToast(context, "已复制")
                                    } catch (_: Exception) { }
                                },
                                onEdit = {
                                    editingId = m.id
                                    editingContent = m.content
                                },
                                onDelete = { pendingDeleteId = m.id }
                            )
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }

    // 弹窗

    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            shape = RoundedCornerShape(AppRadius.DIALOG),
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = { Text("删除记忆") },
            text = { Text("确定删除这条记忆？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    MemoryStore.remove(context, id)
                    refreshKey++
                    pendingDeleteId = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("取消") }
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            shape = RoundedCornerShape(AppRadius.DIALOG),
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = { Text("清空全部记忆") },
            text = { Text("将删除 ${allMemories.size} 条记忆，此操作不可恢复。是否继续？") },
            confirmButton = {
                TextButton(onClick = {
                    MemoryStore.clear(context)
                    refreshKey++
                    showClearConfirm = false
                    notifyToast(context, "已清空")
                }) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }

    editingId?.let { id ->
        AlertDialog(
            onDismissRequest = { editingId = null },
            shape = RoundedCornerShape(AppRadius.DIALOG),
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = { Text("编辑记忆") },
            text = {
                OutlinedTextField(
                    value = editingContent,
                    onValueChange = { editingContent = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    minLines = 3,
                    shape = RoundedCornerShape(AppRadius.MD_MINUS),
                    colors = pocoFieldColors()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (editingContent.isNotBlank()) {
                        MemoryStore.update(context, id, editingContent.trim())
                        refreshKey++
                    }
                    editingId = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingId = null }) { Text("取消") }
            }
        )
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            shape = RoundedCornerShape(AppRadius.DIALOG),
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = { Text("添加记忆") },
            text = {
                OutlinedTextField(
                    value = newContent,
                    onValueChange = { newContent = it },
                    placeholder = { Text("例如：用户是一名 Android 开发者") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    minLines = 3,
                    shape = RoundedCornerShape(AppRadius.MD_MINUS),
                    colors = pocoFieldColors()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newContent.isNotBlank()) {
                        MemoryStore.add(context, newContent.trim())
                        refreshKey++
                        newContent = ""
                    }
                    showAdd = false
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false; newContent = "" }) { Text("取消") }
            }
        )
    }
}


// 记忆行

@Composable
private fun MemoryRow(
    memory: Memory,
    onToggleEnabled: (Boolean) -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val time = DateFormats.formatShortDateTime(memory.createdAt)

    PocoCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    memory.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (memory.enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    }
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        time,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!memory.enabled) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "已停用",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))

            Switch(
                checked = memory.enabled,
                onCheckedChange = onToggleEnabled,
                colors = pocoSwitchColors()
            )

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
                        text = { Text("复制") },
                        onClick = { showMenu = false; onCopy() }
                    )
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        onClick = { showMenu = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; onDelete() }
                    )
                }
            }
        }
    }
}