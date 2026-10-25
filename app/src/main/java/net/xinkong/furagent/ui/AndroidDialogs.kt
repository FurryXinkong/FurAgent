package net.xinkong.furagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.data.SessionStore
import net.xinkong.furagent.data.HistoryStore
import net.xinkong.furagent.shared.tools.BackupHelper
import net.xinkong.furagent.shared.util.DateFormats
import java.io.File
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.shape.RoundedCornerShape
import net.xinkong.furagent.ui.theme.AppRadius

/**
 * Android 端对话框组件（从 MainActivity.kt 拆分）。
 *
 * 包含 AndroidSubSessionListDialog / AndroidBackupHistoryDialog。
 */

/** 子代理入口：历史子会话列表（title 以 ↳ 开头），点击切入。 */
@Composable
fun AndroidSubSessionListDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf(emptyList<Pair<String, String>>()) }

    LaunchedEffect(Unit) {
        items = withContext(Dispatchers.IO) {
            SessionStore.list(context)
                // 【子会话识别】按 parentId 判定（不再靠标题前缀）：
                // 标题被 persist 重算后会丢「↳」，子代理列表就再也找不到它了。
                .filter { it.parentId != null }
                .map { it.id to it.title }
        }
    }

    PocoAlertDialog(
        onDismiss = onDismiss,
        title = "子代理 · 子会话列表",
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (items.isEmpty()) {
                    Text(
                        "暂无子会话。AI 需要以全新上下文执行子任务时会自动派生子会话（不带本对话历史），执行进度显示在顶部功能区。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            .padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            title,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "→",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (i != items.lastIndex) PocoDivider()
                }
            }
        },
        confirmText = "关闭",
        onConfirm = onDismiss,
        dismissText = null
    )
}

/** 修改历史 + 一键回档（聊天页底栏入口）。 */
@Composable
fun AndroidBackupHistoryDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val backupDir = remember {
        val trashDir = File(context.filesDir, "temp/trash")
        BackupHelper.backupDirFor(trashDir.absolutePath)
    }
    val store = remember { HistoryStore.getInstance(context) }
    var entries by remember { mutableStateOf(emptyList<BackupHelper.BackupEntry>()) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(backupDir) {
        entries = store.listHistory(backupDir, 100)
    }

    PocoAlertDialog(
        onDismiss = onDismiss,
        title = "修改历史",
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                if (entries.isEmpty()) {
                    Text(
                        "暂无备份记录。开启「写前备份与回档」后，AI 覆盖已有文件时会自动留档。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                for ((i, e) in entries.withIndex()) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            val actionLabel = when (e.action) {
                                "write" -> "写入"
                                "append" -> "追加"
                                "code_edit" -> "编辑"
                                else -> e.action
                            }
                            Text(
                                "${File(e.path).name} · $actionLabel",
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${DateFormats.formatShortDateTime(e.ts)} · ${e.path}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                status = "正在回档…"
                                statusIsError = false
                                val bytes = store.readBackup(e)
                                if (bytes == null) {
                                    status = "备份文件缺失"
                                    statusIsError = true
                                    return@launch
                                }
                                try {
                                    val target = File(e.path)
                                    target.parentFile?.mkdirs()
                                    target.writeBytes(bytes)
                                    status = "已回档：${e.path}"
                                    statusIsError = false
                                } catch (ex: Exception) {
                                    status = "回档失败：${ex.message}"
                                    statusIsError = true
                                }
                            }
                        }) {
                            Text("回档")
                        }
                    }
                    if (i != entries.lastIndex) PocoDivider()
                }
                status?.let {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Icon(
                            if (statusIsError) Icons.Default.Error else Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(it, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmText = "关闭",
        onConfirm = onDismiss,
        dismissText = null
    )
}

/**
 * 会话专用提示词编辑（P1-1）。
 *
 * 与桌面端 SessionPromptDialog 对齐：
 *  - 只作用于当前会话，追加在全局系统提示词之后
 *  - 留空保存 = 清除本会话的专用提示词
 *  - 保存后下一条消息生效（AgentRunner 每轮实时读盘）
 */
@Composable
fun AndroidSessionPromptDialog(
    sessionId: String,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var loaded by remember(sessionId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(sessionId) {
        scope.launch(Dispatchers.IO) {
            val sp = SessionStore.load(context, sessionId)?.systemPrompt ?: ""
            text = sp
            loaded = true
        }
    }

    PocoAlertDialog(
        onDismiss = onDismiss,
        title = "会话专用提示词",
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "只作用于当前会话，追加在全局系统提示词之后；留空即不使用。保存后下一条消息生效。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!loaded) {
                    Text(
                        "加载中…",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = {
                            Text(
                                "例如：本会话始终用中文简洁回复；讨论代码时先给结论再给过程",
                                fontSize = 12.sp
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        minLines = 6,
                        shape = RoundedCornerShape(AppRadius.MD_MINUS),
                        colors = pocoFieldColors()
                    )
                }
            }
        },
        confirmText = "保存",
        onConfirm = {
            val trimmed = text.trim()
            val session = SessionStore.load(context, sessionId)
            if (session != null) {
                SessionStore.save(context, session.copy(systemPrompt = trimmed))
            }
            onSaved(trimmed)
            onDismiss()
        },
        dismissText = "取消",
        onDismissClick = onDismiss
    )
}