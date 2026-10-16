package net.xinkong.furagent.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.desktop.ui.theme.Radius
import net.xinkong.furagent.desktop.ui.theme.Spacing
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.text.SimpleDateFormat
import java.util.*

/**
 * 日志页。
 *
 * 【本次改动】
 *  - 工具栏从 4 行压缩到 2 行：
 *      原 Row 3（复制可见 / 导出 / 刷新）拆解：
 *        · 导出 / 刷新 → 合并到 PageTitleBar.actions
 *        · 复制可见     → 合并到"选中状态条"（没选中时本来也用不上）
 *  - 水平 padding 从 28dp 统一到 Spacing.PAGE_H（32dp）
 *  - 日志行支持选中：单击 toggle；右键打开菜单；悬停高亮。
 *  - Ctrl+C：主窗口聚焦且当前有选中时，把选中日志（按显示顺序）复制到剪贴板。
 *
 * 其余不变：级别多选过滤、日期切换、自动滚动、清空、空状态。
 */
@Composable
fun LogsPage() {
    val liveEntries by DesktopLogger.entries.collectAsState()

    // 所有可选的日志级别（有序，决定 chip 显示顺序）
    val allLevels = remember {
        listOf(
            "DEBUG" to "调试",
            "INFO" to "信息",
            "WARN" to "警告",
            "ERROR" to "错误"
        )
    }
    // 多选：默认所有级别都开
    var enabledLevels by remember {
        mutableStateOf(allLevels.map { it.first }.toSet())
    }

    var autoScroll by remember { mutableStateOf(true) }

    var selectedDate by remember { mutableStateOf<String?>(null) }
    var fileEntries by remember { mutableStateOf<List<DesktopLogger.Entry>>(emptyList()) }
    var availableDates by remember { mutableStateOf<List<String>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }

    // 【新增】选中的日志行 id
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 【新增】右键菜单归属的行 id
    var contextMenuFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey) {
        availableDates = DesktopLogger.listLogDates()
    }

    LaunchedEffect(selectedDate, refreshKey) {
        fileEntries = if (selectedDate != null) DesktopLogger.loadFromFile(selectedDate!!) else emptyList()
    }

    val sourceEntries = if (selectedDate == null) liveEntries else fileEntries

    // 按 enabledLevels 过滤
    val filtered = remember(sourceEntries, enabledLevels) {
        sourceEntries.filter { it.level in enabledLevels }
    }

    // 数据源 / 筛选变化时清空选中，避免索引错位
    LaunchedEffect(selectedDate, enabledLevels, refreshKey) {
        selectedIds = emptySet()
        contextMenuFor = null
    }

    val src = selectedDate ?: "live"

    /**
     * 行 id。
     *
     * 用 数据源 + 显示索引 + 时间戳 + 级别 + tag 拼。
     * 索引保证同一毫秒内两条完全相同日志也能区分。
     */
    fun rowIdOf(index: Int, e: DesktopLogger.Entry): String =
        "$src|$index|${e.timestamp}|${e.level}|${e.tag}"

    /**
     * 当前可见列表里被选中的日志（按显示顺序）。
     *
     * 【性能】用 remember 缓存：selectedIds / filtered 不变时直接复用，
     * 避免每次重组都全表扫描（日志 1000 条时每帧扫 1000 次字符串拼接）。
     */
    val selectedEntries by remember(filtered, selectedIds) {
        derivedStateOf {
            filtered.filterIndexed { index, e -> rowIdOf(index, e) in selectedIds }
        }
    }

    fun allVisibleIds(): Set<String> =
        filtered.indices.mapTo(mutableSetOf()) { rowIdOf(it, filtered[it]) }

    fun copySelected(): Boolean {
        val picked = selectedEntries
        if (picked.isEmpty()) return false
        val ok = SafeClipboard.setText(picked.joinToString("\n") { DesktopLogger.formatLine(it) })
        if (ok) ToastBus.info("已复制 ${picked.size} 条日志")
        else ToastBus.error("复制失败，剪贴板被占用")
        return ok
    }

    // 复制当前筛选后可见的全部日志
    fun copyVisible(): Boolean {
        if (filtered.isEmpty()) return false
        val ok = SafeClipboard.setText(filtered.joinToString("\n") { DesktopLogger.formatLine(it) })
        if (ok) ToastBus.info("已复制 ${filtered.size} 条日志")
        else ToastBus.error("复制失败，剪贴板被占用")
        return ok
    }

    // 导出当前数据源的全部日志为 Markdown
    fun exportMarkdown() {
        val label = selectedDate ?: "全部日志"
        val entries = if (selectedDate == null) DesktopLogger.snapshot() else fileEntries
        if (entries.isEmpty()) {
            ToastBus.warn("没有可导出的日志")
            return
        }
        val f = DesktopLogger.exportMarkdown(label, entries)
        if (f != null) ToastBus.info("已导出：${f.absolutePath}")
        else ToastBus.error("导出失败")
    }

    // ---- 全局 Ctrl+C：复制选中日志 ----
    val hasSelection by rememberUpdatedState(selectedIds.isNotEmpty())
    val copyAction by rememberUpdatedState<() -> Boolean> { copySelected() }
    DisposableEffect(Unit) {
        val fkm = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = object : java.awt.KeyEventDispatcher {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.id != KeyEvent.KEY_PRESSED) return false
                if (e.keyCode != KeyEvent.VK_C) return false
                if ((e.modifiersEx and InputEvent.CTRL_DOWN_MASK) == 0) return false
                if (!hasSelection) return false
                // 只在主窗口处于激活状态时接管，避免抢别处的 Ctrl+C
                val active = fkm.activeWindow
                if (active == null || active !== MainWindowHolder.currentWindow()) return false
                return copyAction()
            }
        }
        fkm.addKeyEventDispatcher(dispatcher)
        onDispose { fkm.removeKeyEventDispatcher(dispatcher) }
    }

    Column(Modifier.fillMaxSize()) {
        // 【改动】导出 / 刷新 移到标题栏；原来的 Row 3 删除
        PageTitleBar(
            title = "日志",
            subtitle = "${sourceEntries.size} 条 · 显示 ${filtered.size} 条" +
                    if (selectedDate == null) " · 实时" else " · $selectedDate",
            actions = {
                SecondaryButton("导出 MD") { exportMarkdown() }
                SecondaryButton("刷新") { refreshKey++ }
                DangerButton("清空", onClick = {
                    if (selectedDate == null) DesktopLogger.clear()
                    else {
                        DesktopLogger.clearFiles()
                        refreshKey++
                        selectedDate = null
                    }
                    selectedIds = emptySet()
                    contextMenuFor = null
                })
            }
        )

        // ===== 日期 chip 行 =====
        Row(
            Modifier.fillMaxWidth()
                .padding(
                    horizontal = Spacing.PAGE_H,
                    vertical = Spacing.SM,
                )
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LogDateChip("实时", selectedDate == null) { selectedDate = null }
            availableDates.forEach { date ->
                LogDateChip(date, selectedDate == date) { selectedDate = date }
            }
            if (availableDates.isEmpty()) {
                Text(
                    "暂无历史日志",
                    fontSize = 11.sp,
                    color = TextMuted(),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        // ===== 级别多选 + 自动滚动（合并 1 行）=====
        Row(
            Modifier.fillMaxWidth().padding(
                horizontal = Spacing.PAGE_H,
                vertical = Spacing.XS,
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：级别 chips + 全选/清空选择（横向可滚动，防止窄窗口溢出）
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                allLevels.forEach { (key, label) ->
                    LevelToggleChip(
                        label = label,
                        selected = key in enabledLevels
                    ) {
                        enabledLevels = if (key in enabledLevels) {
                            enabledLevels - key
                        } else {
                            enabledLevels + key
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                TextButtonInline(
                    text = "全选",
                    enabled = enabledLevels.size < allLevels.size
                ) {
                    enabledLevels = allLevels.map { it.first }.toSet()
                }
                TextButtonInline(
                    text = "清空选择",
                    enabled = enabledLevels.isNotEmpty()
                ) {
                    enabledLevels = emptySet()
                }
            }

            Spacer(Modifier.width(12.dp))
            Text("自动滚动", fontSize = 12.sp, color = TextSecondary())
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = autoScroll,
                onCheckedChange = { autoScroll = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = OnAccent(),
                    checkedTrackColor = AccentColor(),
                    uncheckedThumbColor = TextSecondary(),
                    uncheckedTrackColor = CardStrongColor(),
                    uncheckedBorderColor = BorderColor()
                )
            )
        }

        // ===== 选中状态条（有选中时才显示）=====
        // 【改动】"复制可见"从删除的 Row 3 挪到这里。
        if (selectedIds.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(
                    horizontal = Spacing.PAGE_H,
                    vertical = Spacing.XS,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "已选中 ${selectedEntries.size} 条 · Ctrl+C 复制",
                    fontSize = 11.sp,
                    color = AccentColor(),
                    modifier = Modifier.padding(end = 8.dp)
                )
                TextButtonInline(text = "复制", enabled = true) { copySelected() }
                TextButtonInline(text = "复制全部可见", enabled = filtered.isNotEmpty()) { copyVisible() }
                TextButtonInline(text = "全选可见", enabled = filtered.isNotEmpty()) {
                    selectedIds = allVisibleIds()
                }
                TextButtonInline(text = "取消选择", enabled = true) { selectedIds = emptySet() }
            }
        }

        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            if (filtered.isEmpty()) {
                EmptyLogsState(
                    isEmpty = sourceEntries.isEmpty(),
                    allHidden = sourceEntries.isNotEmpty() && enabledLevels.isEmpty()
                )
            } else {
                // 外层 key 保证切数据源时整体重建
                key(selectedDate, enabledLevels, refreshKey) {
                    val listState = rememberLazyListState()

                    LaunchedEffect(selectedDate, enabledLevels, filtered.size) {
                        if (autoScroll && filtered.isNotEmpty()) {
                            try {
                                listState.scrollToItem(filtered.size - 1)
                            } catch (_: Exception) { }
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = Spacing.PAGE_H,
                            vertical = Spacing.SM,
                        ),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // 【关键】用 count + 索引作为 key。
                        // 同一毫秒内两条完全相同日志会撞 key，加 index 保证 100% 唯一。
                        items(
                            count = filtered.size,
                            key = { index -> rowIdOf(index, filtered[index]) }
                        ) { index ->
                            val entry = filtered[index]
                            val id = rowIdOf(index, entry)
                            Box {
                                LogRow(
                                    entry = entry,
                                    selected = id in selectedIds,
                                    onToggleSelect = {
                                        selectedIds = if (id in selectedIds) selectedIds - id
                                        else selectedIds + id
                                    },
                                    onRightClick = {
                                        // 右键未选中的行：选中收敛到这一行（与文件列表一致）
                                        if (id !in selectedIds) selectedIds = setOf(id)
                                        contextMenuFor = id
                                    }
                                )

                                if (contextMenuFor == id) {
                                    var menuOpen by remember { mutableStateOf(true) }
                                    DropdownMenu(
                                        expanded = menuOpen,
                                        onDismissRequest = {
                                            menuOpen = false
                                            contextMenuFor = null
                                        },
                                        containerColor = CardColor()
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("复制", fontSize = 13.sp, color = TextPrimary()) },
                                            onClick = {
                                                menuOpen = false
                                                contextMenuFor = null
                                                copySelected()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("复制全部可见", fontSize = 13.sp, color = TextPrimary()) },
                                            onClick = {
                                                menuOpen = false
                                                contextMenuFor = null
                                                copyVisible()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("全选可见", fontSize = 13.sp, color = TextPrimary()) },
                                            onClick = {
                                                menuOpen = false
                                                contextMenuFor = null
                                                selectedIds = allVisibleIds()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("取消选择", fontSize = 13.sp, color = TextSecondary()) },
                                            onClick = {
                                                menuOpen = false
                                                contextMenuFor = null
                                                selectedIds = emptySet()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// 空状态
// ============================================================

@Composable
private fun EmptyLogsState(isEmpty: Boolean, allHidden: Boolean = false) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Terminal,
            contentDescription = null,
            tint = TextMuted(),
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            when {
                allHidden -> "已隐藏全部级别"
                isEmpty -> "暂无日志"
                else -> "无匹配日志"
            },
            fontSize = 15.sp,
            color = TextSecondary()
        )
        if (allHidden) {
            Spacer(Modifier.height(8.dp))
            Text(
                "点上方任意级别按钮可重新显示",
                fontSize = 11.sp,
                color = TextMuted()
            )
        }
    }
}

// ============================================================
// Chip 组件
// ============================================================

@Composable
private fun LogDateChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(Radius.SM))
            .background(if (selected) AccentColor() else CardColor())
            .border(
                1.dp,
                if (selected) Color.Transparent else BorderColor(),
                RoundedCornerShape(Radius.SM)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = if (selected) OnAccent() else TextPrimary(),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/**
 * 多选级别 chip。
 *
 * 语义是"多选开关"：
 *   - 选中：AccentColor 高亮 + 白字，表示"显示该级别"
 *   - 未选：CardColor 灰底 + 灰字，表示"隐藏该级别"
 *   - 点一下就是 toggle
 */
@Composable
private fun LevelToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(Radius.XS))
            .background(if (selected) AccentColor() else CardColor())
            .border(
                1.dp,
                if (selected) Color.Transparent else BorderColor(),
                RoundedCornerShape(Radius.XS)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = if (selected) OnAccent() else TextSecondary(),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun TextButtonInline(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(Radius.XS))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text,
            fontSize = 11.sp,
            color = if (enabled) AccentColor() else TextMuted(),
            fontWeight = FontWeight.Medium
        )
    }
}

// ============================================================
// 日志行
// ============================================================

/**
 * 单行日志。
 *
 * 交互（与状态面板的本机文件行一致）：
 *   - 左键单击：toggle 选中
 *   - 右键：打开右键菜单
 *   - 悬停：轻微高亮
 */
@Composable
private fun LogRow(
    entry: DesktopLogger.Entry,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onRightClick: () -> Unit
) {
    val time = remember(entry.timestamp) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(entry.timestamp))
    }
    val (color, levelBg) = when (entry.level) {
        "ERROR" -> ErrorColor() to ErrorColor().copy(alpha = 0.08f)
        "WARN" -> WarningColor() to WarningColor().copy(alpha = 0.08f)
        "INFO" -> ResultColor() to ResultColor().copy(alpha = 0.05f)
        "DEBUG" -> TextMuted() to Color.Transparent
        else -> TextSecondary() to Color.Transparent
    }

    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = when {
            selected -> AccentSoft()
            hovered -> CardHoverColor()
            else -> levelBg
        },
        animationSpec = tween(120),
        label = "logRowBg"
    )

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.XS))
            .background(bg)
            .hoverable(interaction)
            .pointerInput(entry.timestamp, entry.message) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Press) continue
                        val isRight = try {
                            event.buttons.isSecondaryPressed
                        } catch (_: Throwable) {
                            false
                        }
                        if (isRight) onRightClick() else onToggleSelect()
                    }
                }
            }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            time,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = TextMuted(),
            modifier = Modifier.width(90.dp)
        )
        Text(
            entry.level.padEnd(5),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = color,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(55.dp)
        )
        Text(
            "[${entry.tag}]",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = AccentColor(),
            modifier = Modifier.width(90.dp)
        )
        Spacer(Modifier.width(6.dp))
        // 【体验优化 B4】超长日志折叠
        val message = entry.message
        if (message.length > 2000) {
            var expanded by remember(entry.timestamp, entry.tag) { mutableStateOf(false) }
            Column(Modifier.weight(1f)) {
                Text(
                    if (expanded) message else message.take(2000),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextPrimary(),
                    lineHeight = 16.sp
                )
                Text(
                    if (expanded) "收起" else "展开全部（共 ${message.length} 字符）",
                    fontSize = 11.sp,
                    color = AccentColor(),
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clickable { expanded = !expanded }
                )
            }
        } else {
            Text(
                message,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = TextPrimary(),
                lineHeight = 16.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}