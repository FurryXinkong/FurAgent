@file:OptIn(ExperimentalMaterial3Api::class)

package net.xinkong.furagent.ui.pages

import net.xinkong.furagent.shared.util.DateFormats
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.xinkong.furagent.data.HistoryStore
import net.xinkong.furagent.tools.ToolRegistry.CurrentTaskSession
import net.xinkong.furagent.shared.tools.BackupHelper
import net.xinkong.furagent.ui.*
import net.xinkong.furagent.ui.theme.AppRadius
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 修改历史 —— 手机友好布局（Android 端）。
 *
 * LIST：垂直时间线（圆点 + 连线 + 卡片），顶部筛选。
 * DETAIL：全屏详情（单栏 diff + 变更导航 + 底部固定操作栏）。
 * 数据层：HistoryStore（SQLite）。
 */
@Composable
fun HistoryPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { HistoryStore.getInstance(context) }
    val backupDir = remember {
        val trashDir = File(context.filesDir, "temp/trash")
        BackupHelper.backupDirFor(trashDir.absolutePath)
    }
    val scope = rememberCoroutineScope()
    val currentSessionId = CurrentTaskSession.id

    var entries by remember { mutableStateOf(emptyList<BackupHelper.BackupEntry>()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var searchText by remember { mutableStateOf("") }
    var dateFrom by remember { mutableStateOf("") }
    var dateTo by remember { mutableStateOf("") }
    var scopeAll by remember { mutableStateOf(false) }
    var detailEntry by remember { mutableStateOf<BackupHelper.BackupEntry?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var undoTarget by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }

    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showRestoreAllConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(backupDir, refreshKey) {
        entries = withContext(Dispatchers.IO) { store.listHistory(backupDir, 500) }
    }

    val scoped = remember(entries, scopeAll, currentSessionId) {
        if (scopeAll || currentSessionId.isBlank()) entries
        else entries.filter { it.sessionId == currentSessionId }
    }
    val filtered = remember(scoped, searchText, dateFrom, dateTo) {
        var list = if (searchText.isBlank()) scoped
        else scoped.filter { it.path.contains(searchText, ignoreCase = true) }
        val sdf = SimpleDateFormat("yyyy-MM-dd")
        val fromTs = if (dateFrom.isBlank()) null
        else try { sdf.parse(dateFrom)?.time } catch (_: Exception) { null }
        val toTs = if (dateTo.isBlank()) null
        else try {
            val d = sdf.parse(dateTo) ?: return@remember list
            d.time + 24L * 60 * 60 * 1000
        } catch (_: Exception) { null }
        if (fromTs != null) list = list.filter { it.ts >= fromTs }
        if (toTs != null) list = list.filter { it.ts < toTs }
        list
    }
    val fileCount = remember(filtered) { filtered.map { it.path }.distinct().size }

    // ── 详情视图 ──
    if (detailEntry != null) {
        val entry = detailEntry!!
        HistoryDetailView(
            entry = entry,
            backupDir = backupDir,
            store = store,
            sessionId = currentSessionId,
            onBack = { detailEntry = null },
            onUndoAvailable = { undoTarget = it },
            onRestoreRequest = { showRestoreConfirm = true },
            onRestoreAllRequest = { showRestoreAllConfirm = true }
        )
    } else {
        // ── 列表视图：垂直时间线 ──
        ApplyBackgroundBlur(true)
        Scaffold(
            topBar = {
                PocoPageTopBar(
                    title = "修改历史",
                    subtitle = if (filtered.isEmpty()) "暂无记录"
                    else "${filtered.size} 次 · $fileCount 文件",
                    onBack = onBack,
                    actions = {
                        if (undoTarget != null) {
                            TextButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val (path, bytes) = undoTarget!!
                                    try {
                                        File(path).writeBytes(bytes)
                                        status = "已撤销回档"; statusIsError = false
                                        undoTarget = null; refreshKey++
                                    } catch (e: Exception) {
                                        status = "撤销失败：${e.message}"; statusIsError = true
                                    }
                                }
                            }) { Text("撤销回档", color = MaterialTheme.colorScheme.primary) }
                        }
                    }
                )
            },
            containerColor = net.xinkong.furagent.ui.glassPageBg()
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
            ) {
                // 筛选行
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = !scopeAll,
                        onClick = { scopeAll = false },
                        label = { Text("本会话", fontSize = 12.sp) },
                        shape = RoundedCornerShape(AppRadius.MD)
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = scopeAll,
                        onClick = { scopeAll = true },
                        label = { Text("全部", fontSize = 12.sp) },
                        shape = RoundedCornerShape(AppRadius.MD)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        placeholder = { Text("搜索路径", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).height(40.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                        shape = RoundedCornerShape(AppRadius.SM)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = dateFrom,
                        onValueChange = { dateFrom = it },
                        placeholder = { Text("起始日期", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).height(40.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                        shape = RoundedCornerShape(AppRadius.SM)
                    )
                    Spacer(Modifier.width(6.dp))
                    OutlinedTextField(
                        value = dateTo,
                        onValueChange = { dateTo = it },
                        placeholder = { Text("截止日期", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).height(40.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                        shape = RoundedCornerShape(AppRadius.SM)
                    )
                    Spacer(Modifier.width(4.dp))
                    val sdf = remember { SimpleDateFormat("yyyy-MM-dd") }
                    TextButton(onClick = {
                        dateFrom = sdf.format(Date())
                        dateTo = ""
                    }) { Text("今天", fontSize = 11.sp) }
                    TextButton(onClick = {
                        dateFrom = sdf.format(Date(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000))
                        dateTo = ""
                    }) { Text("7天", fontSize = 11.sp) }
                }

                status?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = if (statusIsError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                    )
                }

                if (filtered.isEmpty()) {
                    PocoEmptyState(
                        icon = Icons.Default.History,
                        title = "暂无修改记录",
                        subtitle = "AI 每次写入/编辑文件都会留下记录"
                    )
                } else {
                    // 垂直时间线
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(filtered) { i, e ->
                            TimelineRow(
                                entry = e,
                                isLast = i == filtered.lastIndex,
                                onClick = { detailEntry = e }
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 单文件回档确认 ──
    if (showRestoreConfirm && detailEntry != null) {
        val target = detailEntry!!
        val laterCount = entries.count { it.path == target.path && it.ts > target.ts }
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm")
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("回档确认") },
            text = {
                Text(
                    "将把「${target.path.substringAfterLast('/')}」恢复到「${
                        timeFmt.format(Date(target.ts))
                    }」这条修改发生之前的内容。\n" +
                    if (laterCount > 0)
                        "该文件在此之后还有 $laterCount 次修改，本次回档会覆盖它们（历史保留，可再回档）。\n\n回档前自动备份当前版本，可随时撤销。"
                    else "\n回档前自动备份当前版本，可随时撤销。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    scope.launch(Dispatchers.IO) {
                        val bytes = store.readBackup(target)
                        if (bytes == null) {
                            status = "备份缺失，无法回档"; statusIsError = true
                            return@launch
                        }
                        try {
                            val f = File(target.path)
                            val currentBytes = try {
                                if (f.exists()) f.readBytes() else null
                            } catch (_: Exception) { null }
                            f.parentFile?.mkdirs()
                            f.writeBytes(bytes)
                            store.recordEdit(
                                backupDir, target.path, "restore",
                                currentBytes, bytes, "history_ui",
                                sessionId = currentSessionId
                            )
                            undoTarget = target.path to (currentBytes ?: ByteArray(0))
                            status = "已回档：${target.path}（可撤销）"
                            statusIsError = false
                            detailEntry = null
                            refreshKey++
                        } catch (e: Exception) {
                            status = "回档失败：${e.message}"; statusIsError = true
                        }
                    }
                }) {
                    Text("确认回档", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) { Text("取消") }
            },
            shape = RoundedCornerShape(AppRadius.DIALOG)
        )
    }

    // ── 整体回档确认 ──
    if (showRestoreAllConfirm && detailEntry != null) {
        val target = detailEntry!!
        val restoreFileCount = remember(target, entries) {
            entries.filter { it.ts > target.ts }.map { it.path }.distinct().size
        }
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm")
        AlertDialog(
            onDismissRequest = { showRestoreAllConfirm = false },
            title = { Text("整体回档确认") },
            text = {
                Text(
                    "将把 $restoreFileCount 个文件回档到 ${
                        timeFmt.format(Date(target.ts))
                    } 之前的状态。\n\n该条之后的所有修改记录保留不变（可再回档）。\n回档前自动备份当前版本。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreAllConfirm = false
                    scope.launch(Dispatchers.IO) {
                        status = "正在整体回档…"; statusIsError = false
                        val targetEntries = entries
                            .filter { it.ts > target.ts }
                            .groupBy { it.path }
                            .mapNotNull { (_, list) -> list.minByOrNull { it.ts } }
                        var restored = 0
                        var deleted = 0
                        targetEntries.forEach { e ->
                            try {
                                val bytes = store.readBackup(e)
                                val f = File(e.path)
                                val currentBytes = try {
                                    if (f.exists()) f.readBytes() else null
                                } catch (_: Exception) { null }
                                if (bytes != null) {
                                    f.parentFile?.mkdirs()
                                    f.writeBytes(bytes)
                                    restored++
                                } else {
                                    if (f.exists()) { f.delete(); deleted++ }
                                }
                                store.recordEdit(
                                    backupDir, e.path, "restore",
                                    currentBytes, bytes, "history_ui_bulk",
                                    sessionId = currentSessionId
                                )
                            } catch (_: Exception) { }
                        }
                        status = "整体回档完成：恢复 $restored，删除 $deleted（可撤销）"
                        statusIsError = false
                        detailEntry = null
                        refreshKey++
                    }
                }) {
                    Text("确认整体回档", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreAllConfirm = false }) { Text("取消") }
            },
            shape = RoundedCornerShape(AppRadius.DIALOG)
        )
    }
}

// ── 时间线列表项：圆点 + 连线 + 卡片 ──
@Composable
private fun TimelineRow(
    entry: BackupHelper.BackupEntry,
    isLast: Boolean,
    onClick: () -> Unit
) {
    val actionLabel = when (entry.action) {
        "write" -> "写入"
        "append" -> "追加"
        "code_edit" -> "修改"
        "restore" -> "回档"
        else -> entry.action
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        // 圆点 + 连线
        Column(
            Modifier
                .width(24.dp)
                .padding(top = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            if (!isLast) {
                Box(
                    Modifier
                        .width(1.5.dp)
                        .height(56.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        // 卡片
        PocoCard(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 6.dp)
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$actionLabel ${File(entry.path).name}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.weight(1f))
                    if (entry.added > 0) {
                        Text(
                            "+${entry.added}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    if (entry.removed > 0) {
                        Text(
                            "-${entry.removed}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    DateFormats.formatShortDateTime(entry.ts),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

// ── 详情视图：单栏 diff + 变更导航 + 底部操作栏 ──
@Composable
private fun HistoryDetailView(
    entry: BackupHelper.BackupEntry,
    backupDir: File?,
    store: HistoryStore,
    sessionId: String,
    onBack: () -> Unit,
    onUndoAvailable: (Pair<String, ByteArray>) -> Unit,
    onRestoreRequest: () -> Unit,
    onRestoreAllRequest: () -> Unit
) {
    var oldText by remember(entry.ts, entry.path) { mutableStateOf("") }
    var newText by remember(entry.ts, entry.path) { mutableStateOf("") }
    var binary by remember(entry.ts, entry.path) { mutableStateOf(false) }
    var loaded by remember(entry.ts, entry.path) { mutableStateOf(false) }

    LaunchedEffect(entry.ts, entry.path) {
        val result = withContext(Dispatchers.IO) {
            val old = try { store.readBackup(entry)?.toString(Charsets.UTF_8) ?: "" }
            catch (_: Exception) { "" }
            val cur = try {
                val f = File(entry.path)
                if (f.exists()) f.readBytes().toString(Charsets.UTF_8) else null
            } catch (_: Exception) { null }
            val bin = old.contains('\u0000') || (cur?.contains('\u0000') == true)
            Triple(old, cur ?: "", bin)
        }
        oldText = result.first
        newText = result.second
        binary = result.third
        loaded = true
    }

    // 【P1-抽 shared】调用点统一走 LcsDiff.alignRows
    val rows = remember(oldText, newText) {
        net.xinkong.furagent.shared.util.LcsDiff.alignRows(oldText, newText)
    }
    // 变更按「块」导航：连续变更行归一块（与桌面端一致，大文件按行跳没有意义）
    val rowBlock = remember(rows) {
        IntArray(rows.size) { -1 }.also { arr ->
            var blk = -1
            var inBlock = false
            rows.forEachIndexed { i, r ->
                if (r.kind != '=') {
                    if (!inBlock) { blk++; inBlock = true }
                    arr[i] = blk
                } else inBlock = false
            }
        }
    }
    val changeBlocks = remember(rows) {
        rows.indices.filter { i -> rows[i].kind != '=' && (i == 0 || rows[i - 1].kind == '=') }
    }
    var changePos by remember(entry.ts, entry.path) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()

    ApplyBackgroundBlur(true)
    Scaffold(
        topBar = {
            PocoPageTopBar(
                title = File(entry.path).name,
                subtitle = "${DateFormats.formatShortDateTime(entry.ts)} · " +
                        if (entry.added > 0 || entry.removed > 0) "+${entry.added} / -${entry.removed}"
                        else entry.action,
                onBack = onBack
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onRestoreAllRequest,
                        shape = RoundedCornerShape(AppRadius.MD),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("全部回档到此条之前", fontSize = 13.sp, maxLines = 1)
                    }
                    Button(
                        onClick = onRestoreRequest,
                        shape = RoundedCornerShape(AppRadius.MD),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("回档此文件", fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        },
        containerColor = net.xinkong.furagent.ui.glassPageBg()
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                entry.path,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val canNav = changeBlocks.isNotEmpty() && loaded && !binary
                NavButton(Icons.Default.KeyboardArrowUp, enabled = canNav) {
                    changePos = if (changePos <= 0) changeBlocks.size - 1 else changePos - 1
                    val t = changeBlocks[changePos]
                    scrollScope.launch { listState.animateScrollToItem(t) }
                }
                Spacer(Modifier.width(4.dp))
                NavButton(Icons.Default.KeyboardArrowDown, enabled = canNav) {
                    changePos = (changePos + 1) % changeBlocks.size
                    val t = changeBlocks[changePos]
                    scrollScope.launch { listState.animateScrollToItem(t) }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        !loaded -> "加载中…"
                        binary -> "二进制文件"
                        changeBlocks.isEmpty() -> "内容一致"
                        else -> "${changePos + 1}/${changeBlocks.size} 处变更 · ${rows.size} 行"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            if (!loaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else if (binary) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("二进制文件，无文本对比", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("内容一致，无差异", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    itemsIndexed(rows) { i, r ->
                        val isCurrent = changeBlocks.isNotEmpty() && rowBlock[i] == changePos
                        when (r.kind) {
                            '=' -> DiffLine(
                                no = r.newNo,
                                text = r.newText ?: "",
                                bg = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                else Color.Transparent,
                                fg = MaterialTheme.colorScheme.onSurface
                            )
                            '!' -> {
                                DiffLine(
                                    no = r.oldNo, text = r.oldText ?: "",
                                    bg = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                    fg = MaterialTheme.colorScheme.error
                                )
                                DiffLine(
                                    no = r.newNo, text = r.newText ?: "",
                                    bg = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                    fg = MaterialTheme.colorScheme.primary
                                )
                            }
                            '-' -> DiffLine(
                                no = r.oldNo, text = r.oldText ?: "",
                                bg = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                                fg = MaterialTheme.colorScheme.error
                            )
                            '+' -> DiffLine(
                                no = r.newNo, text = r.newText ?: "",
                                bg = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                fg = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── 单栏 diff 行 ──
@Composable
private fun DiffLine(no: Int?, text: String, bg: Color, fg: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(vertical = 1.dp)
    ) {
        Text(
            no?.toString() ?: "",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.width(36.dp).padding(end = 5.dp, top = 1.dp)
        )
        Text(
            text,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = fg,
            lineHeight = 16.sp,
            modifier = Modifier.weight(1f).padding(end = 6.dp)
        )
    }
}

// ── 导航小按钮 ──
@Composable
private fun NavButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(AppRadius.SM))
            .background(
                if (enabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                else Color.Transparent
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}

// ── 对齐算法（与桌面端同源：前缀/后缀裁剪 + 中段 LCS + 行配对） ──
// 【P1-抽 shared】对齐行定义移入 shared/util/LcsDiff.Row
private typealias AlignRow = net.xinkong.furagent.shared.util.LcsDiff.Row

private fun alignRows(oldText: String, newText: String): List<AlignRow> {
    val a = oldText.split('\n')
    val b = newText.split('\n')
    var pre = 0
    while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
    var suf = 0
    while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
    val midA = a.subList(pre, a.size - suf)
    val midB = b.subList(pre, b.size - suf)
    val out = ArrayList<AlignRow>(maxOf(a.size, b.size))
    var oldNo = 1
    var newNo = 1
    for (i in 0 until pre) out.add(AlignRow(oldNo++, a[i], newNo++, b[i], '='))
    val n = midA.size
    val m = midB.size
    if (n.toLong() * m > 4_000_000L) {
        for (i in 0 until n) out.add(AlignRow(oldNo++, midA[i], null, null, '-'))
        for (j in 0 until m) out.add(AlignRow(null, null, newNo++, midB[j], '+'))
    } else if (n == 0) {
        for (j in 0 until m) out.add(AlignRow(null, null, newNo++, midB[j], '+'))
    } else if (m == 0) {
        for (i in 0 until n) out.add(AlignRow(oldNo++, midA[i], null, null, '-'))
    } else {
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (midA[i] == midB[j]) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                midA[i] == midB[j] -> {
                    out.add(AlignRow(oldNo++, midA[i], newNo++, midB[j], '='))
                    i++; j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    out.add(AlignRow(oldNo++, midA[i], null, null, '-')); i++
                }
                else -> {
                    out.add(AlignRow(null, null, newNo++, midB[j], '+')); j++
                }
            }
        }
        while (i < n) { out.add(AlignRow(oldNo++, midA[i], null, null, '-')); i++ }
        while (j < m) { out.add(AlignRow(null, null, newNo++, midB[j], '+')); j++ }
    }
    for (k in 0 until suf) {
        out.add(AlignRow(oldNo++, a[a.size - suf + k], newNo++, b[b.size - suf + k], '='))
    }
    // 配对相邻 -/+ 为 '!'
    val paired = ArrayList<AlignRow>(out.size)
    var x = 0
    while (x < out.size) {
        val r = out[x]
        if (r.kind == '-') {
            val dels = ArrayList<AlignRow>()
            while (x < out.size && out[x].kind == '-') { dels.add(out[x]); x++ }
            val adds = ArrayList<AlignRow>()
            while (x < out.size && out[x].kind == '+') { adds.add(out[x]); x++ }
            if (adds.isEmpty()) { paired.addAll(dels); continue }
            val k = maxOf(dels.size, adds.size)
            for (t in 0 until k) {
                val d = dels.getOrNull(t)
                val ad = adds.getOrNull(t)
                when {
                    d != null && ad != null ->
                        paired.add(AlignRow(d.oldNo, d.oldText, ad.newNo, ad.newText, '!'))
                    d != null -> paired.add(d)
                    else -> if (ad != null) paired.add(ad)
                }
            }
        } else {
            paired.add(r); x++
        }
    }
    return paired
}
