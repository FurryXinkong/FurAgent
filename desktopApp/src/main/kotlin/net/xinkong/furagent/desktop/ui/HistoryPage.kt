package net.xinkong.furagent.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.xinkong.furagent.desktop.data.HistoryStore
import net.xinkong.furagent.desktop.tools.DesktopTools
import net.xinkong.furagent.desktop.ui.theme.Radius
import net.xinkong.furagent.desktop.ui.theme.Spacing
import net.xinkong.furagent.shared.icons.Icons
import net.xinkong.furagent.shared.tools.BackupHelper
import net.xinkong.furagent.shared.tools.TrashHelper
import net.xinkong.furagent.shared.util.DateFormats
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 修改历史 —— IntelliJ Local History 式布局。
 *
 * 左栏：时间线（上，占大头）+ 修改的文件分组（下）。
 * 右栏：详情工具栏（变更导航 + 操作按钮）+ 双栏 diff（「在 XX 之前」←→「当前」）。
 *
 * 双栏 diff 数据：左 = 该条记录的改前备份（HistoryStore.readBackup），
 * 右 = 磁盘当前文件内容；前缀/后缀裁剪 + 中段 LCS 对齐，行号各自独立。
 * 大文件超阈值时中段退化为「先左块后右块」，保证不卡。
 */
@Composable
fun HistoryPage() {
    val backupDir = remember {
        BackupHelper.backupDirFor(TrashHelper.defaultTrashDir().absolutePath)
    }
    var entries by remember { mutableStateOf(emptyList<BackupHelper.BackupEntry>()) }
    var selectedIdx by remember { mutableStateOf(0) }
    var searchText by remember { mutableStateOf("") }
    var dateFrom by remember { mutableStateOf("") }
    var dateTo by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var scopeAll by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val currentSessionId = DesktopTools.CurrentTaskSession.id

    LaunchedEffect(backupDir, refreshKey) {
        entries = withContext(Dispatchers.IO) {
            HistoryStore.listHistory(backupDir, 500)
        }
        if (selectedIdx >= entries.size) selectedIdx = 0
    }

    val scoped = remember(entries, scopeAll, currentSessionId) {
        if (scopeAll || currentSessionId.isBlank()) entries
        else entries.filter { it.sessionId == currentSessionId }
    }
    val filtered = remember(scoped, searchText, dateFrom, dateTo) {
        var list = if (searchText.isBlank()) scoped
        else scoped.filter { it.path.contains(searchText, ignoreCase = true) }
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd")
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
    val selected = filtered.getOrNull(selectedIdx)

    val fileCount = remember(filtered) { filtered.map { it.path }.distinct().size }
    val addTotal = remember(filtered) { filtered.sumOf { it.added } }
    val delTotal = remember(filtered) { filtered.sumOf { it.removed } }

    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showRestoreAllConfirm by remember { mutableStateOf(false) }
    var undoTarget by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    // 左下角文件区的时间锚：= 左上角选中记录的时间，列「该时间至今」修改的文件；-1 未锚定（列全部）
    var anchorTs by remember { mutableLongStateOf(-1L) }

    Column(Modifier.fillMaxSize()) {
        PageTitleBar(
            title = "修改历史",
            subtitle = if (filtered.isEmpty()) "暂无记录"
            else "${filtered.size} 次修改 · $fileCount 个文件 · +$addTotal / -$delTotal",
            actions = {
                if (undoTarget != null) {
                    SecondaryButton(text = "撤销回档", icon = Icons.Default.Undo) {
                        scope.launch(Dispatchers.IO) {
                            val (path, bytes) = undoTarget!!
                            try {
                                File(path).writeBytes(bytes)
                                status = "已撤销回档：$path"
                                undoTarget = null
                                refreshKey++
                            } catch (ex: Exception) {
                                status = "撤销失败：${ex.message}"
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                SecondaryButton(text = "刷新", icon = Icons.Default.Refresh) { refreshKey++ }
            }
        )

        // ── 筛选行 ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.PAGE_H, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(Radius.SM))
                    .background(CardStrongColor())
                    .padding(2.dp)
            ) {
                ScopeChip("本会话", !scopeAll) { scopeAll = false; selectedIdx = 0 }
                ScopeChip("全部", scopeAll) { scopeAll = true; selectedIdx = 0 }
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.width(320.dp)) {
                GlassTextField(
                    searchText,
                    { searchText = it; selectedIdx = 0 },
                    singleLine = true,
                    placeholder = "按文件名搜索",
                    leadingIcon = Icons.Default.Search
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.width(120.dp)) {
                GlassTextField(dateFrom, { dateFrom = it; selectedIdx = 0 }, singleLine = true, placeholder = "起始日期")
            }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.width(120.dp)) {
                GlassTextField(dateTo, { dateTo = it; selectedIdx = 0 }, singleLine = true, placeholder = "截止日期")
            }
        }

        if (scoped.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (scopeAll) "暂无修改记录。\n开启「写前备份与回档」后，AI 每次写入/编辑文件都会在这里留下记录。"
                    else "本会话暂无修改记录。\n切到「全部」可查看其它会话的历史。",
                    fontSize = 13.sp, color = TextSecondary(), lineHeight = 20.sp
                )
            }
            return@Column
        }

        // ── 主体：左栏 + 右栏 ──
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.PAGE_H)
                .padding(bottom = Spacing.PAGE_V)
        ) {
            // ============ 左栏：时间线 + 文件分组 ============
            Column(
                Modifier
                    .width(340.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Radius.MD))
                    .background(CardColor())
                    .border(1.dp, BorderSubtle(), RoundedCornerShape(Radius.MD))
            ) {
                // 时间线（IntelliJ 左上：动作 + 时间 两行式）
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(filtered) { idx, e ->
                        HistoryTimelineItem(
                            entry = e,
                            selected = idx == selectedIdx,
                            onClick = {
                                selectedIdx = idx
                                anchorTs = e.ts
                            }
                        )
                    }
                }
                GlassDivider(color = BorderSubtle())
                // 文件聚合：锚定到选中时间，只列「该时间至今」修改过的文件（与左上角时间线互补，不重复）
                val anchorFiltered = remember(filtered, anchorTs) {
                    if (anchorTs < 0) filtered else filtered.filter { it.ts >= anchorTs }
                }
                val fileGroups = remember(anchorFiltered) {
                    anchorFiltered.groupBy { it.path }.map { (path, list) -> path to list.size }
                }
                Text(
                    if (anchorTs < 0) "全部修改的文件 · ${fileGroups.size}"
                    else "自 ${DateFormats.formatShortDateTime(anchorTs)} 起修改 · ${fileGroups.size} 个文件",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextMuted(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
                LazyColumn(Modifier.weight(0.7f)) {
                    itemsIndexed(fileGroups) { _, (path, count) ->
                        val fname = File(path).name
                        val hit = selected?.path == path
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (hit) AccentColor().copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable {
                                    // 跳到锚定范围内该文件最新的一条（不改锚，避免左下角范围自跳）
                                    val i = filtered.indexOfFirst {
                                        it.path == path && (anchorTs < 0 || it.ts >= anchorTs)
                                    }
                                    if (i >= 0) selectedIdx = i
                                }
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                fname,
                                fontSize = 12.sp,
                                fontWeight = if (hit) FontWeight.Medium else FontWeight.Normal,
                                color = if (hit) AccentColor() else TextPrimary(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "$count 次",
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextMuted()
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // ============ 右栏：详情 + 双栏 diff ============
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Radius.MD))
                    .background(CardColor())
                    .border(1.dp, BorderSubtle(), RoundedCornerShape(Radius.MD))
            ) {
                if (selected == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("选择左侧一条记录查看改动", fontSize = 13.sp, color = TextSecondary())
                    }
                } else {
                    // 详情头：文件信息
                    HistoryDetailHeader(entry = selected)

                    // 工具栏：变更导航 + 操作
                    DetailToolbar(
                        entry = selected,
                        onOpenFile = { openFileWithSystem(selected.path) },
                        onRestore = { showRestoreConfirm = true },
                        onRestoreAll = { showRestoreAllConfirm = true }
                    )

                    GlassDivider(color = BorderSubtle())

                    // 双栏 diff 主体
                    SideBySideDiff(
                        entry = selected,
                        modifier = Modifier.weight(1f)
                    )

                    if (showRestoreConfirm) {
                        val laterCount = entries.count { it.path == selected.path && it.ts > selected.ts }
                        AlertDialog(
                            onDismissRequest = { showRestoreConfirm = false },
                            title = { Text("确认回档") },
                            text = {
                                Text(
                                    "将把「${selected.path.substringAfterLast('/')}」恢复到「${
                                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(selected.ts))
                                    }」这条修改发生之前的内容。\n" +
                                    if (laterCount > 0)
                                        "该文件在此之后还有 $laterCount 次修改，本次回档会覆盖它们（历史保留，可再回档）。\n\n回档前自动备份当前版本，可随时撤销。"
                                    else
                                        "\n回档前自动备份当前版本，可随时撤销。"
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showRestoreConfirm = false
                                    scope.launch(Dispatchers.IO) {
                                        status = "正在回档…"
                                        val bytes = HistoryStore.readBackup(selected)
                                        if (bytes == null) {
                                            status = "回档失败：备份文件缺失"
                                            return@launch
                                        }
                                        try {
                                            val target = File(selected.path)
                                            val currentBytes = if (target.exists()) {
                                                val b = target.readBytes()
                                                undoTarget = selected.path to b
                                                b
                                            } else null
                                            target.parentFile?.mkdirs()
                                            target.writeBytes(bytes)
                                            HistoryStore.recordEdit(
                                                backupDir, selected.path, "restore",
                                                currentBytes, bytes, "history_ui",
                                                sessionId = DesktopTools.CurrentTaskSession.id
                                            )
                                            status = "已回档：${selected.path}（可点「撤销回档」恢复，历史已记录）"
                                            refreshKey++
                                        } catch (ex: Exception) {
                                            status = "回档失败：${ex.message}"
                                        }
                                    }
                                }) { Text("确认回档", color = AccentColor(), fontWeight = FontWeight.SemiBold) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showRestoreConfirm = false }) { Text("取消", color = TextSecondary()) }
                            },
                            shape = RoundedCornerShape(Radius.DIALOG),
                            containerColor = CardColor()
                        )
                    }
                    if (showRestoreAllConfirm) {
                        val restoreFileCount = remember(selected, entries) {
                            entries.filter { it.ts > selected.ts }.map { it.path }.distinct().size
                        }
                        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        AlertDialog(
                            onDismissRequest = { showRestoreAllConfirm = false },
                            title = { Text("整体回档确认") },
                            text = {
                                Text(
                                    "将把 $restoreFileCount 个文件回档到 ${
                                        timeFmt.format(Date(selected.ts))
                                    } 之前的状态。\n\n该条之后的所有修改记录保留不变（可再回档）。\n回档前自动备份当前版本。"
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showRestoreAllConfirm = false
                                    scope.launch(Dispatchers.IO) {
                                        status = "正在整体回档…"
                                        val targetEntries = entries
                                            .filter { it.ts > selected.ts }
                                            .groupBy { it.path }
                                            .mapNotNull { (_, list) -> list.minByOrNull { it.ts } }
                                        var restored = 0
                                        var deleted = 0
                                        targetEntries.forEach { e ->
                                            try {
                                                val bytes = HistoryStore.readBackup(e)
                                                val target = File(e.path)
                                                val currentBytes = try {
                                                    if (target.exists()) target.readBytes() else null
                                                } catch (_: Exception) { null }
                                                if (bytes != null) {
                                                    target.parentFile?.mkdirs()
                                                    target.writeBytes(bytes)
                                                    restored++
                                                } else {
                                                    if (target.exists()) { target.delete(); deleted++ }
                                                }
                                                HistoryStore.recordEdit(
                                                    backupDir, e.path, "restore",
                                                    currentBytes, bytes, "history_ui_bulk",
                                                    sessionId = DesktopTools.CurrentTaskSession.id
                                                )
                                            } catch (_: Exception) { }
                                        }
                                        status = "整体回档完成：恢复 $restored 个文件，删除 $deleted 个文件（历史已记录，可再回档）"
                                        refreshKey++
                                    }
                                }) { Text("确认整体回档", color = AccentColor(), fontWeight = FontWeight.SemiBold) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showRestoreAllConfirm = false }) {
                                    Text("取消", color = TextSecondary())
                                }
                            },
                            shape = RoundedCornerShape(Radius.DIALOG),
                            containerColor = CardColor()
                        )
                    }
                }
            }
        }

        status?.let {
            Text(
                it,
                fontSize = 12.sp,
                color = TextPrimary(),
                modifier = Modifier.padding(horizontal = Spacing.PAGE_H, vertical = 6.dp)
            )
        }
    }
}

/** 范围切换小胶囊。 */
@Composable
private fun ScopeChip(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
        color = if (active) AccentColor() else TextSecondary(),
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.XS))
            .background(if (active) CardColor() else androidx.compose.ui.graphics.Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 5.dp)
    )
}

private fun openFileWithSystem(path: String) {
    try {
        val f = File(path)
        if (!f.exists()) return
        if (java.awt.Desktop.isDesktopSupported() &&
            java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)
        ) {
            java.awt.Desktop.getDesktop().open(f)
        } else {
            ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", f.absolutePath).start()
        }
    } catch (_: Exception) {
    }
}

/** 左栏时间线一条（IntelliJ 两行式：动作描述 + 时间）。 */
@Composable
private fun HistoryTimelineItem(
    entry: BackupHelper.BackupEntry,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        selected -> AccentColor().copy(alpha = 0.14f)
        hovered -> CardHoverColor()
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    val actionLabel = when (entry.action) {
        "write" -> "写入"
        "append" -> "追加"
        "code_edit" -> "修改"
        "restore" -> "回档"
        else -> entry.action
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$actionLabel ${File(entry.path).name}",
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) AccentColor() else TextPrimary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.weight(1f))
            if (entry.added > 0) {
                Text("+${entry.added}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = ResultColor())
                Spacer(Modifier.width(5.dp))
            }
            if (entry.removed > 0) {
                Text("-${entry.removed}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = ErrorColor())
            }
        }
        Text(
            DateFormats.formatShortDateTime(entry.ts),
            fontSize = 11.sp,
            color = TextMuted(),
            modifier = Modifier.padding(top = 1.dp)
        )
    }
}

/** 右栏详情头：文件名 + 完整路径 + 时间。 */
@Composable
private fun HistoryDetailHeader(entry: BackupHelper.BackupEntry) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                File(entry.path).name,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                DateFormats.formatDateTime(entry.ts),
                fontSize = 11.sp,
                color = TextMuted()
            )
        }
        Text(
            entry.path,
            fontSize = 11.sp,
            color = TextMuted(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 工具栏：变更导航（↑↓ + 计数） + 操作按钮。 */
@Composable
private fun DetailToolbar(
    entry: BackupHelper.BackupEntry,
    onOpenFile: () -> Unit,
    onRestore: () -> Unit,
    onRestoreAll: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("操作", fontSize = 11.sp, color = TextMuted())
        Spacer(Modifier.width(14.dp))
        ToolAction(Icons.Default.FileOpen, "打开文件", onOpenFile)
        Spacer(Modifier.width(10.dp))
        ToolAction(Icons.Default.Undo, "回档", onRestore)
        Spacer(Modifier.width(10.dp))
        ToolAction(Icons.Default.RestartAlt, "全部回档到此条之前", onRestoreAll)
    }
}

@Composable
private fun ToolAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .clip(RoundedCornerShape(Radius.XS))
            .background(if (hovered) AccentColor().copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = AccentColor(), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 12.sp, color = AccentColor())
    }
}

// ============ 双栏 diff ============

/** 对齐后的双栏行：old/new 各自独立行号，kind 决定着色。 */
// 对齐行定义移入 shared/util/LcsDiff.Row
private typealias SbsRow = net.xinkong.furagent.shared.util.LcsDiff.Row

/**
 * 旧文本 vs 新文本 → 对齐行。
 * 前缀/后缀裁剪公共行，中段 LCS；乘积超阈值时中段退化为左块+右块。
 */
private fun alignSideBySide(oldText: String, newText: String): List<SbsRow> {
    val a = oldText.split('\n')
    val b = newText.split('\n')
    var pre = 0
    while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
    var suf = 0
    while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
    val midA = a.subList(pre, a.size - suf)
    val midB = b.subList(pre, b.size - suf)
    val out = ArrayList<SbsRow>(maxOf(a.size, b.size))
    var oldNo = 1
    var newNo = 1
    for (i in 0 until pre) out.add(SbsRow(oldNo++, a[i], newNo++, b[i], '='))
    val n = midA.size
    val m = midB.size
    if (n.toLong() * m > 4_000_000L) {
        for (i in 0 until n) out.add(SbsRow(oldNo++, midA[i], null, null, '-'))
        for (j in 0 until m) out.add(SbsRow(null, null, newNo++, midB[j], '+'))
    } else if (n == 0) {
        for (j in 0 until m) out.add(SbsRow(null, null, newNo++, midB[j], '+'))
    } else if (m == 0) {
        for (i in 0 until n) out.add(SbsRow(oldNo++, midA[i], null, null, '-'))
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
                    out.add(SbsRow(oldNo++, midA[i], newNo++, midB[j], '='))
                    i++; j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    out.add(SbsRow(oldNo++, midA[i], null, null, '-')); i++
                }
                else -> {
                    out.add(SbsRow(null, null, newNo++, midB[j], '+')); j++
                }
            }
        }
        while (i < n) { out.add(SbsRow(oldNo++, midA[i], null, null, '-')); i++ }
        while (j < m) { out.add(SbsRow(null, null, newNo++, midB[j], '+')); j++ }
    }
    for (k in 0 until suf) {
        out.add(SbsRow(oldNo++, a[a.size - suf + k], newNo++, b[b.size - suf + k], '='))
    }
    return pairRows(out)
}

/** 把紧邻的 '-'块 与 '+'块 配对成 '!' 行（左红右绿同行），视觉对齐。 */
private fun pairRows(rows: List<SbsRow>): List<SbsRow> {
    if (rows.isEmpty()) return rows
    val out = ArrayList<SbsRow>(rows.size)
    var i = 0
    while (i < rows.size) {
        val r = rows[i]
        if (r.kind == '-') {
            val dels = ArrayList<SbsRow>()
            while (i < rows.size && rows[i].kind == '-') { dels.add(rows[i]); i++ }
            val adds = ArrayList<SbsRow>()
            while (i < rows.size && rows[i].kind == '+') { adds.add(rows[i]); i++ }
            if (adds.isEmpty()) { out.addAll(dels); continue }
            val k = maxOf(dels.size, adds.size)
            for (t in 0 until k) {
                val d = dels.getOrNull(t)
                val ad = adds.getOrNull(t)
                out.add(
                    when {
                        d != null && ad != null -> SbsRow(d.oldNo, d.oldText, ad.newNo, ad.newText, '!')
                        d != null -> d
                        else -> ad!!
                    }
                )
            }
        } else if (r.kind == '+') {
            val adds = ArrayList<SbsRow>()
            while (i < rows.size && rows[i].kind == '+') { adds.add(rows[i]); i++ }
            out.addAll(adds)
        } else {
            out.add(r); i++
        }
    }
    return out
}

/** 双栏 diff 主体：列头（在 XX 之前 ←→ 当前）+ 变更导航 + 对齐行渲染。 */
@Composable
private fun SideBySideDiff(entry: BackupHelper.BackupEntry, modifier: Modifier = Modifier) {
    var oldText by remember(entry.ts, entry.path) { mutableStateOf("") }
    var newText by remember(entry.ts, entry.path) { mutableStateOf("") }
    var binary by remember(entry.ts, entry.path) { mutableStateOf(false) }
    var loaded by remember(entry.ts, entry.path) { mutableStateOf(false) }

    LaunchedEffect(entry.ts, entry.path) {
        val result = withContext(Dispatchers.IO) {
            val old = try {
                HistoryStore.readBackup(entry)?.toString(Charsets.UTF_8) ?: ""
            } catch (_: Exception) { "" }
            val cur = try {
                val f = File(entry.path)
                if (f.exists()) f.readBytes().toString(Charsets.UTF_8) else null
            } catch (_: Exception) { null }
            val bin = old.contains('\u0000') || (cur?.contains('\u0000') == true)
            Triple(old, cur ?: "", bin)
        }
        binary = result.third
        oldText = result.first
        newText = result.second
        loaded = true
    }

    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AccentColor())
        }
        return
    }
    if (binary) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("二进制文件，无文本对比", fontSize = 12.sp, color = TextSecondary())
        }
        return
    }

    val o = oldText
    val c = newText
    val scrollScope = rememberCoroutineScope()

    Column(modifier.fillMaxSize()) {
        // 变更导航栏
        // 调用点统一走 LcsDiff.alignRows
        val rows = remember(o, c) { net.xinkong.furagent.shared.util.LcsDiff.alignRows(o, c) }
        // 变更按「块」导航：连续变更行归为一块（大文件 1936 个变更行可能只有十几块，按行跳没有意义）
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

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavBtn(Icons.Default.KeyboardArrowUp, enabled = changeBlocks.isNotEmpty()) {
                if (changeBlocks.isNotEmpty()) {
                    changePos = if (changePos <= 0) changeBlocks.size - 1 else changePos - 1
                    val target = changeBlocks[changePos]
                    scrollScope.launch { scopeScroll(listState, target) }
                }
            }
            Spacer(Modifier.width(2.dp))
            NavBtn(Icons.Default.KeyboardArrowDown, enabled = changeBlocks.isNotEmpty()) {
                if (changeBlocks.isNotEmpty()) {
                    changePos = (changePos + 1) % changeBlocks.size
                    val target = changeBlocks[changePos]
                    scrollScope.launch { scopeScroll(listState, target) }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (changeBlocks.isEmpty()) "无变更" else "${changePos + 1}/${changeBlocks.size} 处变更",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = TextMuted()
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${rows.size} 行对照",
                fontSize = 11.sp,
                color = TextMuted()
            )
        }

        // 列头：「在 XX 之前」 ←→ 「当前」
        val timeFmt = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }
        Row(
            Modifier
                .fillMaxWidth()
                .background(CardStrongColor())
        ) {
            Text(
                "在 ${timeFmt.format(Date(entry.ts))} 之前",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 4.dp)
            )
            Box(
                Modifier
                    .width(1.dp)
                    .height(20.dp)
                    .background(BorderSubtle())
            )
            Text(
                "当前",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary(),
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        GlassDivider(color = BorderSubtle())

        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("内容完全一致", fontSize = 12.sp, color = TextSecondary())
            }
            return
        }

        // 对齐行（单列表 = 天然同步滚动）
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(rows.size) { i ->
                val r = rows[i]
                val leftBg = when (r.kind) {
                    '!' -> ErrorColor().copy(alpha = 0.12f)
                    '-' -> ErrorColor().copy(alpha = 0.08f)
                    else -> androidx.compose.ui.graphics.Color.Transparent
                }
                val rightBg = when (r.kind) {
                    '!' -> ResultColor().copy(alpha = 0.12f)
                    '+' -> ResultColor().copy(alpha = 0.08f)
                    else -> androidx.compose.ui.graphics.Color.Transparent
                }
                val curRow = if (changeBlocks.isNotEmpty() && rowBlock[i] == changePos) {
                    AccentColor().copy(alpha = 0.08f)
                } else null

                Row(Modifier.fillMaxWidth()) {
                    DiffCell(
                        no = r.oldNo, text = r.oldText,
                        bg = curRow ?: leftBg,
                        noColor = TextMuted(),
                        fg = if (r.kind == '!' || r.kind == '-') ErrorColor().copy(alpha = 0.85f) else TextPrimary(),
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(BorderSubtle())
                    )
                    DiffCell(
                        no = r.newNo, text = r.newText,
                        bg = curRow ?: rightBg,
                        noColor = TextMuted(),
                        fg = if (r.kind == '!' || r.kind == '+') ResultColor().copy(alpha = 0.9f) else TextPrimary(),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** 单侧单元格：行号 + 等宽文本；text=null 时为占位空格。 */
@Composable
private fun DiffCell(
    no: Int?,
    text: String?,
    bg: androidx.compose.ui.graphics.Color,
    noColor: androidx.compose.ui.graphics.Color,
    fg: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .background(bg)
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            no?.toString() ?: "",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = noColor,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.width(44.dp).padding(end = 6.dp, top = 1.dp)
        )
        Text(
            text ?: "",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = fg,
            lineHeight = 17.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
    }
}

@Composable
private fun NavBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tint = if (!enabled) TextMuted()
    else if (hovered) AccentColor() else TextSecondary()
    Box(
        Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(Radius.XS))
            .background(if (hovered && enabled) AccentColor().copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
    }
}

/** 滚动到指定对齐行（带一点偏移余量）。 */
private suspend fun scopeScroll(state: androidx.compose.foundation.lazy.LazyListState, index: Int) {
    val first = state.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
    val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
    if (index !in first..last) {
        state.animateScrollToItem(maxOf(0, index - 3))
    }
}
