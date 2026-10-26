@file:OptIn(ExperimentalMaterial3Api::class)

package net.xinkong.furagent.ui.pages
import net.xinkong.furagent.ui.theme.AppRadius

import net.xinkong.furagent.shared.util.DateFormats
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.*
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.data.CrashStore
import net.xinkong.furagent.data.LogEntry
import net.xinkong.furagent.data.TokenStats
import net.xinkong.furagent.system.notifyToast
import net.xinkong.furagent.ui.ApplyBackgroundBlur
import net.xinkong.furagent.ui.ChipSurface
import net.xinkong.furagent.ui.GlassSheen
import net.xinkong.furagent.ui.PocoCard
import net.xinkong.furagent.ui.SuccessColor
import net.xinkong.furagent.ui.WarningColor
import net.xinkong.furagent.ui.PocoPageTopBar
import net.xinkong.furagent.ui.glassPageBg
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 日志控制台 —— 整屏页面。
 *
 *  - 新增：级别多选过滤（调试 / 信息 / 警告 / 错误）
 *    点任意 chip 就是 toggle 该级别，默认全部启用。
 *  - 新增：「复制可见」按钮（把当前过滤后可见的日志拼成文本复制到剪贴板）。
 *  - 保留：日期 chip、导出（FileProvider 分享）、刷新、清空、崩溃提示、Token 详情。
 */
@Composable
fun LogConsolePage(
    logs: List<LogEntry>,
    onClear: () -> Unit,
    onClearCrash: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    ApplyBackgroundBlur(true)

    DisposableEffect(Unit) {
        AgentLogger.panelOpen = true
        onDispose { AgentLogger.panelOpen = false }
    }

    val crashSnapshot = remember { CrashStore.load(context) }
    val crashTime = remember { CrashStore.loadTime(context) }
    var showTokenDetail by remember { mutableStateOf(false) }

    var selectedDate by remember { mutableStateOf<String?>(null) }
    var fileLogs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var availableDates by remember { mutableStateOf<List<String>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    // 体验优化 清空按钮加二次确认，区分实时视图 / 文件视图
    var showClearConfirm by remember { mutableStateOf(false) }

    // 级别多选：默认全部启用

    val allLevels = remember {
        listOf(
            "DEBUG" to "调试",
            "INFO" to "信息",
            "WARN" to "警告",
            "ERROR" to "错误"
        )
    }
    var enabledLevels by remember { mutableStateOf(allLevels.map { it.first }.toSet()) }

    LaunchedEffect(refreshKey) {
        availableDates = AgentLogger.listLogDates()
    }

    LaunchedEffect(selectedDate, refreshKey) {
        fileLogs = if (selectedDate != null) AgentLogger.loadFromFile(selectedDate!!) else emptyList()
    }

    val sourceLogs = if (selectedDate == null) logs else fileLogs

    // 按级别过滤（type → 级别归类后再判断）
    val displayLogs = remember(sourceLogs, enabledLevels) {
        sourceLogs.filter { levelOf(it.type) in enabledLevels }
    }

    // 复制可见日志到剪贴板
    fun copyVisibleLogs() {
        try {
            if (displayLogs.isEmpty()) {
                notifyToast(context, "当前没有可见日志")
                return
            }
            val fmt = DateFormats.fullMsFormatter()
            val text = displayLogs.joinToString("\n") { e ->
                "${fmt.format(Date(e.timestamp))} [${e.type}] ${e.content}"
            }
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("furagent_logs", text))
            notifyToast(context, "已复制 ${displayLogs.size} 条日志")
        } catch (e: Exception) {
            notifyToast(context, "复制失败：${e.message}")
        }
    }

    // 体验优化 清空二次确认
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            shape = RoundedCornerShape(AppRadius.DIALOG),
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    if (selectedDate == null) "清空实时日志"
                    else "清空 $selectedDate 的日志文件"
                )
            },
            text = {
                Text(
                    if (selectedDate == null) {
                        "只清空内存里显示的实时日志（不影响已落盘的文件）。"
                    } else {
                        "会删除 $selectedDate 的日志文件，其他日期不受影响。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (selectedDate == null) {
                        onClear()
                    } else {
                        AgentLogger.clearFiles()
                        refreshKey++
                        notifyToast(context, "已清空日志文件")
                    }
                    showClearConfirm = false
                }) { Text("确认清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }

    if (showTokenDetail) {
        AlertDialog(
            onDismissRequest = { showTokenDetail = false },
            shape = RoundedCornerShape(AppRadius.DIALOG),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Token 明细") },
            text = {
                Column {
                    Text("Prompt：${TokenStats.prompt}", fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(6.dp))
                    Text("Completion：${TokenStats.completion}", fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(6.dp))
                    Text("Total ≈ ${TokenStats.total}", fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(onClick = { showTokenDetail = false }) { Text("关闭") }
            },
            dismissButton = {
                TextButton(onClick = {
                    TokenStats.reset()
                    notifyToast(context, "Token 已重置")
                    showTokenDetail = false
                }) { Text("重置", color = MaterialTheme.colorScheme.error) }
            }
        )
    }

    Scaffold(
        topBar = {
            PocoPageTopBar(
                title = "日志",
                subtitle = "${sourceLogs.size} 条 · 显示 ${displayLogs.size} 条 · Token ≈ ${TokenStats.total}",
                onBack = onBack,
                subtitleColor = MaterialTheme.colorScheme.primary,
                onSubtitleClick = { showTokenDetail = true },
                actions = {
                    TextButton(onClick = { showClearConfirm = true }) {
                        Text(
                            if (selectedDate == null) "清空" else "清空文件",
                            color = MaterialTheme.colorScheme.error
                        )
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

                // ============ 日期 chip 行 ============
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ChipSurface("实时", selectedDate == null) { selectedDate = null }
                    availableDates.forEach { date ->
                        Spacer(Modifier.width(6.dp))
                        ChipSurface(date, selectedDate == date) { selectedDate = date }
                    }
                    if (availableDates.isEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "暂无历史日志",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 级别多选行 ============
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    allLevels.forEach { (key, label) ->
                        ChipSurface(
                            label,
                            key in enabledLevels
                        ) {
                            enabledLevels = if (key in enabledLevels) {
                                enabledLevels - key
                            } else {
                                enabledLevels + key
                            }
                        }
                    }

                    Spacer(Modifier.width(4.dp))

                    InlineTextButton(
                        text = "全选",
                        enabled = enabledLevels.size < allLevels.size
                    ) {
                        enabledLevels = allLevels.map { it.first }.toSet()
                    }
                    InlineTextButton(
                        text = "清空选择",
                        enabled = enabledLevels.isNotEmpty()
                    ) {
                        enabledLevels = emptySet()
                    }
                }

                // ============ 操作行：复制可见 / 导出 / 刷新 ============
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 复制可见
                    OutlinedButton(
                        onClick = { copyVisibleLogs() },
                        shape = RoundedCornerShape(AppRadius.SM),
                        enabled = displayLogs.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("复制可见", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { scope.launch { exportLogs(context, selectedDate, displayLogs) } },
                        shape = RoundedCornerShape(AppRadius.SM),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("导出", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { refreshKey++ },
                        shape = RoundedCornerShape(AppRadius.SM),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("刷新", maxLines = 1)
                    }
                }

                // ============ 崩溃提示（只在实时视图显示） ============
                if (crashSnapshot != null && selectedDate == null) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        PocoCard {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "上次崩溃",
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (crashTime > 0) {
                                        Text(
                                            DateFormats.formatShortDateTime(crashTime),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                SelectionContainer {
                                    Text(
                                        crashSnapshot.take(400),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                                as ClipboardManager
                                        cm.setPrimaryClip(
                                            ClipData.newPlainText("crash", crashSnapshot)
                                        )
                                        notifyToast(context, "已复制")
                                    }) { Text("复制") }
                                    TextButton(onClick = { onClearCrash() }) {
                                        Text("清除", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ============ 日志列表 ============
                if (displayLogs.isEmpty()) {
                    EmptyLogsState(
                        isEmpty = sourceLogs.isEmpty(),
                        allHidden = sourceLogs.isNotEmpty() && enabledLevels.isEmpty(),
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(displayLogs.asReversed()) { entry ->
                            LogEntryCard(entry)
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}

// 空状态

@Composable
private fun EmptyLogsState(
    isEmpty: Boolean,
    allHidden: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                when {
                    allHidden -> "已隐藏全部级别"
                    isEmpty -> "暂无日志"
                    else -> "该日期暂无日志"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    allHidden -> "点上方任意级别按钮可重新显示"
                    isEmpty -> "AI 运行时产生的日志会实时显示在这里"
                    else -> "换个日期看看"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

// 日期 / 级别 chip —— 统一复用公共 ChipSurface（ChatUI.kt），不再本地手搓

@Composable
private fun InlineTextButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(AppRadius.TINY))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text,
            fontSize = 11.sp,
            color = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            fontWeight = FontWeight.Medium
        )
    }
}

// 导出（走系统分享）

private suspend fun exportLogs(context: Context, date: String?, logs: List<LogEntry>) {
    try {
        val text = withContext(Dispatchers.IO) {
            if (date != null) {
                AgentLogger.logFileFor(date)?.readText(Charsets.UTF_8) ?: ""
            } else {
                val fmt = DateFormats.fullMsFormatter()
                logs.joinToString("\n") { "${fmt.format(Date(it.timestamp))} [${it.type}] ${it.content}" }
            }
        }
        if (text.isBlank()) {
            notifyToast(context, "没有可导出的日志")
            return
        }
        val name = if (date != null) "furagent_$date.log"
        else "furagent_live_${System.currentTimeMillis()}.log"
        val uri = withContext(Dispatchers.IO) {
            val tmp = File(context.cacheDir, name)
            tmp.writeText(text, Charsets.UTF_8)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tmp)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "FurAgent 日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出日志"))
    } catch (e: Exception) {
        notifyToast(context, "导出失败：${e.message}")
    }
}

// 日志级别归类

/**
 * 把 LogEntry.type（业务分类）归类到 4 个级别之一。
 *
 * 背景：type 是自由字符串，实际用到的有
 *   DEBUG / INFO / WARN / ERR / CRASH / USER / REQUEST / RESPONSE /
 *   TOOL / RESULT / SEARCH / SHELL / BRIDGE ...
 * 而级别过滤只关心「调试 / 信息 / 警告 / 错误」四档。
 *
 * 归类规则：
 *   - ERR / ERROR / CRASH  → ERROR
 *   - WARN / WARNING       → WARN
 *   - DEBUG                → DEBUG
 *   - 其余（INFO / USER / TOOL / RESULT / REQUEST / RESPONSE /
 *     SEARCH / SHELL / BRIDGE / 未知）→ INFO
 *
 * 未知 type 归到 INFO（而不是丢弃），保证「默认全选时一条不漏」。
 */
private fun levelOf(type: String): String = when (type) {
    "ERR", "ERROR", "CRASH" -> "ERROR"
    "WARN", "WARNING" -> "WARN"
    "DEBUG" -> "DEBUG"
    else -> "INFO"
}

// 单条日志卡片

@Composable
private fun LogEntryCard(entry: LogEntry) {
    val (badgeColor, badgeText) = when (entry.type) {
        "CRASH" -> MaterialTheme.colorScheme.error to "崩溃"
        "ERR" -> MaterialTheme.colorScheme.error to "错误"
        "WARN" -> WarningColor() to "警告"
        "DEBUG" -> MaterialTheme.colorScheme.onSurfaceVariant to "调试"
        "INFO" -> SuccessColor() to "信息"
        "USER" -> MaterialTheme.colorScheme.primary to "用户"
        "REQUEST" -> MaterialTheme.colorScheme.tertiary to "请求"
        "RESPONSE" -> MaterialTheme.colorScheme.secondary to "响应"
        "TOOL" -> MaterialTheme.colorScheme.secondary to "工具"
        "RESULT" -> MaterialTheme.colorScheme.primary to "结果"
        "SEARCH" -> MaterialTheme.colorScheme.tertiary to "搜索"
        "SHELL" -> MaterialTheme.colorScheme.tertiary to "命令"
        "BRIDGE" -> MaterialTheme.colorScheme.secondary to "桥接"
        else -> MaterialTheme.colorScheme.outline to "系统"
    }
    val time = DateFormats.formatTime(entry.timestamp)

    PocoCard {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = badgeColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(AppRadius.XS)
                ) {
                    Text(
                        badgeText,
                        color = badgeColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    time,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            // 体验优化 超长日志折叠：默认只显示前 2000 字符，避免 UI 卡顿
            var expanded by remember(entry.timestamp, entry.type) { mutableStateOf(false) }
            if (entry.content.length > 2000) {
                SelectionContainer {
                    Text(
                        if (expanded) entry.content else entry.content.take(2000),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 19.sp
                    )
                }
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                ) {
                    Text(
                        if (expanded) "收起" else "展开全部（共 ${entry.content.length} 字符）",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                SelectionContainer {
                    Text(
                        entry.content,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 19.sp
                    )
                }
            }
        }
    }
}