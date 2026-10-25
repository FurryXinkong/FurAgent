@file:OptIn(ExperimentalMaterial3Api::class)

package net.xinkong.furagent.ui.pages

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.system.notifyToast
import net.xinkong.furagent.ui.ApplyBackgroundBlur
import net.xinkong.furagent.ui.GlassSheen
import net.xinkong.furagent.ui.PocoCard
import net.xinkong.furagent.ui.PocoEmptyState
import net.xinkong.furagent.ui.PocoPageTopBar
import net.xinkong.furagent.ui.glassPageBg
import net.xinkong.furagent.ui.pocoFieldColors
import net.xinkong.furagent.ui.theme.AppRadius

/**
 * env_store 变量管理页面。
 *
 * 对应 AndroidToolHost 里的 SharedPreferences("FurAgentEnv")，key 前缀 env_。
 *
 * 【UI 统一约定】
 *   - 卡片统一 PocoCard
 *   - 页背景 glassPageBg() + 顶部 GlassSheen
 *   - 空状态图标 56dp
 */
@Composable
fun EnvStorePage(onBack: () -> Unit) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }

    val entries = remember(refreshKey) {
        try {
            val prefs = context.getSharedPreferences("FurAgentEnv", Context.MODE_PRIVATE)
            prefs.all
                .filterKeys { it.startsWith("env_") }
                .mapKeys { it.key.removePrefix("env_") }
                .mapValues { it.value?.toString() ?: "" }
                .toList()
                .sortedBy { it.first }
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "读取 env_store 失败：${e.javaClass.simpleName}: ${e.message}"
            )
            emptyList()
        }
    }

    var editingKey by remember { mutableStateOf<String?>(null) }
    var editingValue by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    var pendingDeleteKey by remember { mutableStateOf<String?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    fun save(key: String, value: String): Boolean {
        return key.isNotBlank() && try {
            context.getSharedPreferences("FurAgentEnv", Context.MODE_PRIVATE)
                .edit {
                    putString("env_$key", value)
                }
            true
        } catch (e: Exception) {
            AgentLogger.log("ERR", "写入 env_store 失败：${e.message}")
            false
        }
    }

    fun remove(key: String): Boolean {
        return try {
            context.getSharedPreferences("FurAgentEnv", Context.MODE_PRIVATE)
                .edit {
                    remove("env_$key")
                }
            true
        } catch (e: Exception) {
            AgentLogger.log("ERR", "删除 env_store 失败：${e.message}")
            false
        }
    }

    fun clearAll(): Boolean {
        return try {
            val prefs = context.getSharedPreferences("FurAgentEnv", Context.MODE_PRIVATE)
            prefs.edit {
                prefs.all.keys.filter { it.startsWith("env_") }.forEach { remove(it) }
            }
            true
        } catch (e: Exception) {
            AgentLogger.log("ERR", "清空 env_store 失败：${e.message}")
            false
        }
    }

    ApplyBackgroundBlur(true)

    // 编辑对话框
    editingKey?.let { key ->
        AlertDialog(
            onDismissRequest = { editingKey = null },
            shape = RoundedCornerShape(AppRadius.DIALOG),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("编辑变量") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "key：$key",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = editingValue,
                        onValueChange = { editingValue = it },
                        label = { Text("值") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                        minLines = 3,
                        shape = RoundedCornerShape(AppRadius.MD_MINUS),
                        colors = pocoFieldColors()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (save(key, editingValue)) {
                        refreshKey++
                        editingKey = null
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingKey = null }) { Text("取消") }
            }
        )
    }

    // 新增对话框
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            shape = RoundedCornerShape(AppRadius.DIALOG),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("添加变量") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newKey,
                        onValueChange = { newKey = it },
                        label = { Text("key") },
                        placeholder = { Text("例如：user_name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(AppRadius.MD_MINUS),
                        colors = pocoFieldColors()
                    )
                    OutlinedTextField(
                        value = newValue,
                        onValueChange = { newValue = it },
                        label = { Text("值") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                        minLines = 3,
                        shape = RoundedCornerShape(AppRadius.MD_MINUS),
                        colors = pocoFieldColors()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val k = newKey.trim()
                    if (k.isBlank()) {
                        notifyToast(context, "key 不能为空")
                        return@TextButton
                    }
                    if (save(k, newValue)) {
                        refreshKey++
                        showAdd = false
                        newKey = ""
                        newValue = ""
                    }
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAdd = false
                    newKey = ""
                    newValue = ""
                }) { Text("取消") }
            }
        )
    }

    // 删除确认
    pendingDeleteKey?.let { key ->
        AlertDialog(
            onDismissRequest = { pendingDeleteKey = null },
            shape = RoundedCornerShape(AppRadius.DIALOG),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("删除变量") },
            text = { Text("确定删除「$key」？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    if (remove(key)) {
                        refreshKey++
                    }
                    pendingDeleteKey = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteKey = null }) { Text("取消") }
            }
        )
    }

    // 清空确认
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            shape = RoundedCornerShape(AppRadius.DIALOG),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("清空全部变量") },
            text = { Text("将删除 ${entries.size} 个变量，此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    if (clearAll()) {
                        refreshKey++
                    }
                    showClearConfirm = false
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            PocoPageTopBar(
                title = "环境变量",
                subtitle = "${entries.size} 个变量 · 供 AI 通过 env_store 读写",
                onBack = onBack,
                actions = {
                    if (entries.isNotEmpty()) {
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
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加变量")
                }
            }
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

            if (entries.isEmpty()) {
                PocoEmptyState(
                    icon = Icons.Default.Storage,
                    title = "暂无变量",
                    subtitle = "AI 可以通过 env_store 工具在不同对话轮次间传递数据。\n也可以在这里手动添加。",
                    modifier = Modifier.fillMaxSize(),
                    action = {
                        Button(
                            onClick = { showAdd = true },
                            shape = RoundedCornerShape(AppRadius.MD_MINUS)
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("添加变量")
                        }
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(entries, key = { it.first }) { (key, value) ->
                        EnvEntryCard(
                            key = key,
                            value = value,
                            onCopy = {
                                try {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                            as android.content.ClipboardManager
                                    cm.setPrimaryClip(
                                        android.content.ClipData.newPlainText(key, value)
                                    )
                                    notifyToast(context, "已复制")
                                } catch (_: Exception) { }
                            },
                            onEdit = {
                                editingKey = key
                                editingValue = value
                            },
                            onDelete = { pendingDeleteKey = key }
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}


// 变量卡片

// 【UI 统一】用 PocoCard 承载。

@Composable
private fun EnvEntryCard(
    key: String,
    value: String,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    PocoCard {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    key,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.ContentCopy, "复制",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Edit, "编辑",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Delete, "删除",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Text(
                    value.ifBlank { "(空)" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}