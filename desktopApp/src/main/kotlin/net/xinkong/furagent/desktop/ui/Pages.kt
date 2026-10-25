package net.xinkong.furagent.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.xinkong.furagent.desktop.bridge.BridgeServer
import net.xinkong.furagent.desktop.bridge.DesktopNotificationManager
import net.xinkong.furagent.desktop.data.getAllKnownFolders
import net.xinkong.furagent.desktop.tools.DesktopTools
import net.xinkong.furagent.desktop.tools.PhoneTools
import net.xinkong.furagent.desktop.ui.theme.Radius
import net.xinkong.furagent.desktop.ui.theme.Spacing
import net.xinkong.furagent.shared.EndpointId
import net.xinkong.furagent.shared.icons.Icons
import net.xinkong.furagent.shared.util.formatFileSize
import java.io.File
import java.util.*

// 定时任务页面 → 已拆到 SchedulesPage.kt

// 状态面板（路径工具正则）

private val RE_MULTI_SLASH = Regex("/+")
private val RE_EMULATED_PATH = Regex("""^/storage/emulated/\d+$""")

// 状态面板

private data class LocalFileEntry(
    val name: String, val path: String,
    val isDirectory: Boolean, val size: Long, val lastModified: Long
)

internal data class RemoteEntry(
    val name: String, val path: String,
    val isDirectory: Boolean, val sizeText: String
)

const val INTERNAL_STORAGE_LABEL = "内部存储"

private val INTERNAL_STORAGE_PREFIXES = listOf(
    "/storage/emulated/0",
    "/sdcard"
)

fun displayRemotePath(raw: String, isPhone: Boolean = true): String {
    if (raw.isBlank()) return raw
    var p = raw.replace(RE_MULTI_SLASH, "/")
    if (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)

    if (isPhone && p == "/") return INTERNAL_STORAGE_LABEL

    for (prefix in INTERNAL_STORAGE_PREFIXES) {
        if (p == prefix) return INTERNAL_STORAGE_LABEL
        if (p.startsWith("$prefix/")) {
            return INTERNAL_STORAGE_LABEL + p.substring(prefix.length)
        }
    }

    val m = Regex("""^/storage/emulated/\d+""").find(p)
    if (m != null) {
        val head = m.value
        if (p == head) return INTERNAL_STORAGE_LABEL
        if (p.startsWith("$head/")) {
            return INTERNAL_STORAGE_LABEL + p.substring(head.length)
        }
    }

    return p
}

fun toRealRemotePath(displayed: String): String {
    val t = displayed.trim()
    if (t.isEmpty()) return t
    if (t == INTERNAL_STORAGE_LABEL) return "/sdcard"
    if (t.startsWith("$INTERNAL_STORAGE_LABEL/")) {
        val rest = t.substring(INTERNAL_STORAGE_LABEL.length)
        return "/sdcard$rest"
    }
    return t
}

fun isInternalStorageRoot(p: String): Boolean {
    val n = p.replace(RE_MULTI_SLASH, "/").trimEnd('/').ifEmpty { "/" }
    if (n == "/") return true
    if (n == "/sdcard") return true
    if (n == "/storage/emulated/0") return true
    if (RE_EMULATED_PATH.matches(n)) return true
    return false
}

fun normalizeRemotePathForBackend(p: String, isPhone: Boolean): String {
    if (!isPhone) return p
    val n = p.replace(RE_MULTI_SLASH, "/")
    if (n == "/") return "/sdcard"
    return n
}

@Composable
fun StatusPage() {
    val scope = rememberCoroutineScope()

    val bridgeStatus by BridgeServer.status.collectAsState()
    val port by BridgeServer.port.collectAsState()
    val clients by BridgeServer.clients.collectAsState()
    val lastError by BridgeServer.lastError.collectAsState()
    val toolCallCount by BridgeServer.toolCallCount.collectAsState()
    val localIp = remember { getLocalIpAddress() }

    val errorType by BridgeServer.errorType.collectAsState()
    val restartCountdown by BridgeServer.restartCountdown.collectAsState()

    val (statusText, statusColor, statusSubtitle) = when (bridgeStatus) {
        BridgeServer.Status.RUNNING -> Triple("服务运行中", ResultColor(), null)
        BridgeServer.Status.STARTING -> Triple("正在启动…", WarningColor(), null)
        BridgeServer.Status.ERROR -> {
            val msg = when (errorType) {
                BridgeServer.ErrorType.PORT_IN_USE -> "启动失败：端口被占用"
                BridgeServer.ErrorType.PERMISSION_DENIED -> "启动失败：权限不足"
                BridgeServer.ErrorType.ADDRESS_UNAVAILABLE -> "启动失败：地址不可用"
                BridgeServer.ErrorType.UNKNOWN -> {
                    val detail = lastError.take(40)
                    detail.ifBlank { "启动失败" }
                }
                else -> "启动失败"
            }
            val sub = if (restartCountdown > 0)
                "距离自动重启：$restartCountdown 秒"
            else "已停止自动重启"
            Triple(msg, ErrorColor(), sub)
        }
        BridgeServer.Status.STOPPED -> Triple("已停止", TextMuted(), null)
    }

    var localFilesExpanded by rememberSaveable { mutableStateOf(false) }
    var currentPath by rememberSaveable { mutableStateOf(System.getProperty("user.home") ?: ".") }
    var rawEntries by remember { mutableStateOf<List<LocalFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchText by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf("name") }
    var sortAsc by remember { mutableStateOf(true) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var contextMenuFor by remember { mutableStateOf<String?>(null) }
    var renamingEntry by remember { mutableStateOf<LocalFileEntry?>(null) }
    var deletingEntry by remember { mutableStateOf<LocalFileEntry?>(null) }

    var expandedDeviceId by remember { mutableStateOf<String?>(null) }
    var pendingSendPath by remember { mutableStateOf<String?>(null) }

    val knownFolders = remember { getAllKnownFolders() }
    val roots = remember { File.listRoots().map { it.absolutePath } }
    val quickPaths = remember(knownFolders) {
        listOfNotNull(
            knownFolders["home"]?.let { "主目录" to it },
            knownFolders["desktop"]?.let { "桌面" to it },
            knownFolders["downloads"]?.let { "下载" to it },
            knownFolders["documents"]?.let { "文档" to it },
            knownFolders["pictures"]?.let { "图片" to it }
        ).filter { File(it.second).exists() }
    }

    LaunchedEffect(localFilesExpanded, currentPath) {
        if (!localFilesExpanded) return@LaunchedEffect
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) {
            try {
                val dir = File(currentPath)
                if (!dir.exists()) return@withContext Result.failure<List<LocalFileEntry>>(Exception("路径不存在"))
                if (!dir.isDirectory) return@withContext Result.failure<List<LocalFileEntry>>(Exception("不是目录"))
                val files = dir.listFiles() ?: return@withContext Result.success(emptyList())
                Result.success(files.map { f ->
                    LocalFileEntry(
                        name = f.name, path = f.absolutePath,
                        isDirectory = f.isDirectory,
                        size = if (f.isFile) f.length() else 0L,
                        lastModified = f.lastModified()
                    )
                })
            } catch (e: Exception) { Result.failure(e) }
        }
        result.onSuccess { rawEntries = it }.onFailure { error = it.message ?: "读取失败" }
        loading = false
    }

    val entries = remember(rawEntries, searchText, sortMode, sortAsc) {
        val filtered = if (searchText.isBlank()) rawEntries
        else rawEntries.filter { it.name.contains(searchText, ignoreCase = true) }
        val comparator = when (sortMode) {
            "size" -> compareBy<LocalFileEntry> { it.size }
            "time" -> compareBy { it.lastModified }
            else -> compareBy { it.name.lowercase() }
        }
        val dirSorted = filtered.sortedWith(
            compareByDescending<LocalFileEntry> { it.isDirectory }.then(comparator)
        )
        if (sortAsc) dirSorted else dirSorted.reversed()
    }

    fun openFile(entry: LocalFileEntry) {
        try {
            val os = System.getProperty("os.name").lowercase()
            val cmd = when {
                os.contains("win") -> arrayOf("rundll32", "url.dll,FileProtocolHandler", entry.path)
                os.contains("mac") -> arrayOf("open", entry.path)
                else -> arrayOf("xdg-open", entry.path)
            }
            ProcessBuilder(*cmd).start()
        } catch (e: Exception) {
            ToastBus.error("打开文件失败：${e.message ?: "未知错误"}")
        }
    }

    fun sendToDevice(clientId: String, path: String) {
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            ToastBus.warn("只支持发送文件（不支持目录）")
            return
        }
        scope.launch {
            ToastBus.info("正在发送 ${file.name}…")
            val result = PhoneTools.uploadFileToClient(clientId, file, "")
            if (result.first) ToastBus.info(result.second)
            else ToastBus.error(result.second)
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageTitleBar(
            title = "状态面板",
            subtitle = "本机文件 · 已连接设备 · 直连设备"
        )

        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = Spacing.PAGE_H,
                    vertical = Spacing.PAGE_V,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.CARD_GAP)
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.CARD_GAP)) {
                        StatCard(
                            label = "服务状态",
                            value = statusText,
                            valueColor = statusColor,
                            subtitle = statusSubtitle,
                            modifier = Modifier.weight(1f)
                        )
                        StatCard("本地工具", DesktopTools.TOOLS.size.toString(), AccentColor(),
                            modifier = Modifier.weight(1f))
                        StatCard("已连接设备", clients.size.toString(), AccentColor(),
                            modifier = Modifier.weight(1f))
                        StatCard("工具调用", toolCallCount.toString(), AccentColor(),
                            modifier = Modifier.weight(1f))
                    }
                }

                if (lastError.isNotBlank()) {
                    item {
                        GlassCard(padding = PaddingValues(Spacing.CARD_PADDING)) {
                            Text(
                                "错误",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ErrorColor()
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(lastError, fontSize = 12.sp, color = TextPrimary())
                        }
                    }
                }

                if (localIp != null) {
                    item {
                        GlassCard(padding = PaddingValues(Spacing.CARD_PADDING)) {
                            Text(
                                "连接提示",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary()
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "确保手机和电脑在同一局域网下",
                                fontSize = 12.sp,
                                color = TextSecondary()
                            )
                            Spacer(Modifier.height(10.dp))
                            GlassDivider(color = BorderSubtle())
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                                Column {
                                    Text("IP 地址", fontSize = 11.sp, color = TextMuted())
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        localIp,
                                        fontSize = 16.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Medium,
                                        color = AccentColor()
                                    )
                                }
                                Column {
                                    Text("端口", fontSize = 11.sp, color = TextMuted())
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        port.toString(),
                                        fontSize = 16.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Medium,
                                        color = AccentColor()
                                    )
                                }
                            }
                        }
                    }
                }

                // ============ 直连设备 ============
                item {
                    DirectDevicesSection(onInfo = { ToastBus.info(it) })
                }

                item {
                    Text(
                        "已连接设备（${clients.size}）",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary()
                    )
                }

                if (clients.isEmpty()) {
                    item {
                        GlassCard(padding = PaddingValues(20.dp)) {
                            Column(
                                Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.DevicesOther,
                                    contentDescription = null,
                                    tint = TextMuted(),
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(Modifier.height(12.dp))
                                Text("暂无设备连接", fontSize = 13.sp, color = TextSecondary())
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "在手机端配置电脑 IP 并连接",
                                    fontSize = 11.sp,
                                    color = TextMuted()
                                )
                            }
                        }
                    }
                } else {
                    items(clients, key = { it.id }) { c ->
                        Box(
                            Modifier.animateItem(
                                fadeInSpec = tween(220),
                                placementSpec = tween(260),
                                fadeOutSpec = tween(160)
                            )
                        ) {
                            DeviceCard(
                                client = c,
                                isExpanded = expandedDeviceId == c.id,
                                onToggleExpand = {
                                    expandedDeviceId = if (expandedDeviceId == c.id) null else c.id
                                },
                                onInfo = { msg -> ToastBus.info(msg) }
                            )
                        }
                    }
                }

                item {
                    NotificationSyncCard()
                }

                item {
                    LocalFilesHeader(
                        expanded = localFilesExpanded,
                        fileCount = if (localFilesExpanded) entries.size else null,
                        onToggle = { localFilesExpanded = !localFilesExpanded }
                    )
                }

                item {
                    Column {
                        AnimatedVisibility(
                            visible = localFilesExpanded,
                            enter = expandVertically(
                                animationSpec = tween(260, easing = FastOutSlowInEasing),
                                expandFrom = Alignment.Top
                            ) + fadeIn(tween(200)),
                            exit = shrinkVertically(
                                animationSpec = tween(220, easing = FastOutSlowInEasing),
                                shrinkTowards = Alignment.Top
                            ) + fadeOut(tween(150))
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    roots.take(6).forEach { r ->
                                        val label = r.trimEnd('\\', '/')
                                        GlassChip(label, currentPath == r) { currentPath = r }
                                    }
                                    if (roots.isNotEmpty() && quickPaths.isNotEmpty()) {
                                        Box(Modifier.width(1.dp).height(16.dp).background(BorderColor()))
                                    }
                                    quickPaths.forEach { (label, path) ->
                                        GlassChip(label, currentPath == path) { currentPath = path }
                                    }
                                }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(Modifier.weight(1f)) {
                                        GlassTextField(
                                            currentPath, { currentPath = it },
                                            singleLine = true, monospace = true,
                                            leadingIcon = Icons.Default.Folder
                                        )
                                    }
                                    Box(Modifier.width(200.dp)) {
                                        GlassTextField(
                                            searchText, { searchText = it },
                                            singleLine = true, placeholder = "搜索文件名",
                                            leadingIcon = Icons.Default.Search
                                        )
                                    }
                                    Box {
                                        var sortOpen by remember { mutableStateOf(false) }
                                        SecondaryButton(text = when (sortMode) {
                                            "size" -> "按大小"; "time" -> "按时间"; else -> "按名称"
                                        } + if (sortAsc) " ↑" else " ↓") { sortOpen = true }
                                        DropdownMenu(
                                            expanded = sortOpen,
                                            onDismissRequest = { sortOpen = false },
                                            containerColor = CardColor()
                                        ) {
                                            listOf("name" to "按名称", "size" to "按大小", "time" to "按时间").forEach { (k, l) ->
                                                DropdownMenuItem(
                                                    text = { Text(l, fontSize = 13.sp, color = TextPrimary()) },
                                                    onClick = {
                                                        if (sortMode == k) sortAsc = !sortAsc else { sortMode = k; sortAsc = true }
                                                        sortOpen = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                if (selectedPaths.isNotEmpty()) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            "已选中 ${selectedPaths.size} 项",
                                            fontSize = 12.sp,
                                            color = AccentColor(),
                                            modifier = Modifier.weight(1f)
                                        )
                                        SecondaryButton(text = "复制路径") {
                                            SafeClipboard.setText(selectedPaths.joinToString("\n"))
                                            ToastBus.info("已复制 ${selectedPaths.size} 条路径")
                                        }
                                        DangerButton(text = "删除") {
                                            val paths = selectedPaths.toList()
                                            if (paths.isNotEmpty()) {
                                                deletingEntry = LocalFileEntry(
                                                    name = "（${paths.size} 个）",
                                                    path = paths.joinToString("\n"),
                                                    isDirectory = false, size = 0, lastModified = 0
                                                )
                                            }
                                        }
                                        SecondaryButton(text = "取消选择") { selectedPaths = emptySet() }
                                    }
                                }
                                Box(
                                    Modifier.fillMaxWidth().height(420.dp)
                                        .clip(RoundedCornerShape(Radius.MD))
                                        .background(CardColor())
                                        .border(1.dp, BorderColor(), RoundedCornerShape(Radius.MD))
                                ) {
                                    when {
                                        loading -> Box(
                                            Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            LoadingBox(text = "读取中…")
                                        }
                                        error != null -> Column(
                                            Modifier.fillMaxSize().padding(40.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                Icons.Default.Warning,
                                                contentDescription = null,
                                                tint = ErrorColor(),
                                                modifier = Modifier.size(48.dp)
                                            )
                                            Spacer(Modifier.height(12.dp))
                                            Text(error!!, fontSize = 13.sp, color = ErrorColor())
                                        }
                                        entries.isEmpty() -> Column(
                                            Modifier.fillMaxSize().padding(40.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                Icons.Default.FolderOpen,
                                                contentDescription = null,
                                                tint = TextMuted(),
                                                modifier = Modifier.size(48.dp)
                                            )
                                            Spacer(Modifier.height(12.dp))
                                            Text(
                                                if (searchText.isBlank()) "空目录" else "无匹配项",
                                                fontSize = 13.sp,
                                                color = TextSecondary()
                                            )
                                        }
                                        else -> LazyColumn(
                                            Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            items(entries, key = { it.path }) { entry ->
                                                Box(
                                                    Modifier.animateItem(
                                                        fadeInSpec = tween(180),
                                                        placementSpec = tween(220),
                                                        fadeOutSpec = tween(140)
                                                    )
                                                ) {
                                                    val selected = entry.path in selectedPaths
                                                    LocalFileRow(
                                                        entry = entry,
                                                        selected = selected,
                                                        onSelect = {
                                                            selectedPaths = if (selected) selectedPaths - entry.path
                                                            else selectedPaths + entry.path
                                                        },
                                                        onOpen = {
                                                            if (entry.isDirectory) currentPath = entry.path else openFile(entry)
                                                        },
                                                        onRightClick = { contextMenuFor = entry.path }
                                                    )
                                                }

                                                if (contextMenuFor == entry.path) {
                                                    var menuOpen by remember { mutableStateOf(true) }
                                                    DropdownMenu(
                                                        expanded = menuOpen,
                                                        onDismissRequest = { menuOpen = false; contextMenuFor = null },
                                                        containerColor = CardColor()
                                                    ) {
                                                        DropdownMenuItem(
                                                            text = { Text("打开", fontSize = 13.sp) },
                                                            onClick = {
                                                                menuOpen = false; contextMenuFor = null
                                                                if (entry.isDirectory) currentPath = entry.path else openFile(entry)
                                                            }
                                                        )
                                                        if (!entry.isDirectory) {
                                                            DropdownMenuItem(
                                                                text = { Text("发送给设备…", fontSize = 13.sp, color = AccentColor()) },
                                                                onClick = {
                                                                    menuOpen = false; contextMenuFor = null
                                                                    pendingSendPath = entry.path
                                                                }
                                                            )
                                                        }
                                                        DropdownMenuItem(
                                                            text = { Text("复制路径", fontSize = 13.sp) },
                                                            onClick = {
                                                                menuOpen = false; contextMenuFor = null
                                                                SafeClipboard.setText(entry.path)
                                                                ToastBus.info("已复制：${entry.path}")
                                                            }
                                                        )
                                                        if (entry.isDirectory) {
                                                            DropdownMenuItem(
                                                                text = { Text("在此打开终端", fontSize = 13.sp) },
                                                                onClick = {
                                                                    menuOpen = false; contextMenuFor = null
                                                                    try {
                                                                        val os = System.getProperty("os.name").lowercase()
                                                                        val cmd = when {
                                                                            os.contains("win") -> arrayOf("cmd.exe", "/c", "start", "cmd.exe", "/k", "cd", "/d", entry.path)
                                                                            os.contains("mac") -> arrayOf("open", "-a", "Terminal", entry.path)
                                                                            else -> arrayOf("x-terminal-emulator", "--working-directory", entry.path)
                                                                        }
                                                                        ProcessBuilder(*cmd).start()
                                                                    } catch (e: Exception) {
                                                                        ToastBus.error("打开终端失败：${e.message}")
                                                                    }
                                                                }
                                                            )
                                                        }
                                                        DropdownMenuItem(
                                                            text = { Text("重命名", fontSize = 13.sp) },
                                                            onClick = { menuOpen = false; contextMenuFor = null; renamingEntry = entry }
                                                        )
                                                        DropdownMenuItem(
                                                            text = { Text("删除", fontSize = 13.sp, color = ErrorColor()) },
                                                            onClick = { menuOpen = false; contextMenuFor = null; deletingEntry = entry }
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

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    renamingEntry?.let { entry ->
        InputDialog(
            title = "重命名", initial = entry.name,
            onDismiss = { renamingEntry = null },
            onConfirm = { newName ->
                try {
                    val oldFile = File(entry.path)
                    val newFile = File(oldFile.parentFile, newName)
                    if (newFile.exists()) {
                        ToastBus.warn("目标已存在：$newName")
                    } else {
                        val ok = oldFile.renameTo(newFile)
                        if (ok) ToastBus.info("已重命名为 $newName")
                        else ToastBus.error("重命名失败")
                    }
                } catch (e: Exception) {
                    ToastBus.error("重命名失败：${e.message}")
                }
                renamingEntry = null
            }
        )
    }

    deletingEntry?.let { entry ->
        ConfirmDialog(
            title = "删除",
            message = if (entry.path.contains("\n"))
                "确定删除选中的 ${entry.path.split("\n").size} 个文件/文件夹？此操作不可恢复。"
            else "确定删除「${entry.name}」？此操作不可恢复。",
            confirmText = "删除", danger = true,
            onConfirm = {
                try {
                    entry.path.split("\n").forEach { p ->
                        val f = File(p)
                        if (f.exists()) { if (f.isDirectory) f.deleteRecursively() else f.delete() }
                    }
                } catch (_: Exception) { }
                deletingEntry = null
                selectedPaths = emptySet()
            },
            onDismiss = { deletingEntry = null }
        )
    }

    pendingSendPath?.let { path ->
        val file = File(path)
        AlertDialog(
            onDismissRequest = { pendingSendPath = null },
            containerColor = CardColor(),
            titleContentColor = TextPrimary(),
            textContentColor = TextSecondary(),
            shape = RoundedCornerShape(Radius.MD),
            title = { Text("发送到哪台设备", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(Modifier.width(400.dp)) {
                    Text(
                        "文件：${file.name}",
                        fontSize = 12.sp,
                        color = TextMuted(),
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(12.dp))
                    if (clients.isEmpty()) {
                        Text("暂无已连接设备", fontSize = 13.sp, color = TextSecondary())
                    } else {
                        clients.forEach { c ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(Radius.SM))
                                    .clickable {
                                        sendToDevice(c.id, path)
                                        pendingSendPath = null
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (c.endpoint == EndpointId.PHONE) Icons.Default.Smartphone
                                    else Icons.Default.Computer,
                                    contentDescription = null,
                                    tint = AccentColor(),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        c.deviceName.ifBlank { c.remote },
                                        fontSize = 13.sp,
                                        color = TextPrimary(),
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        c.remote.trimStart('/'),
                                        fontSize = 11.sp,
                                        color = TextMuted(),
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingSendPath = null }) {
                    Text("取消", color = TextSecondary())
                }
            }
        )
    }
}

// 通用输入对话框

@Composable
fun InputDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(Radius.DIALOG),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(Radius.SM),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// StatCard

/**
 * 通知同步开关卡片。
 *
 * 单向：手机通知 → 电脑。
 * 开关打开后，手机端每收到一条通知就转发过来，这里用系统托盘气泡弹出。
 */
@Composable
private fun NotificationSyncCard() {
    val enabled by DesktopNotificationManager.phoneToPc.collectAsState()

    GlassCard(padding = PaddingValues(Spacing.CARD_PADDING)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "接收手机通知",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "手机收到消息时，在电脑上弹出通知",
                    fontSize = 11.sp,
                    color = TextSecondary()
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = { DesktopNotificationManager.setPhoneToPc(it) }
            )
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    valueColor: Color,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    GlassCard(modifier = modifier, padding = PaddingValues(Spacing.CARD_PADDING)) {
        Text(label, fontSize = 11.sp, color = TextMuted())
        Spacer(Modifier.height(8.dp))
        Text(
            value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 19.sp
        )
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, fontSize = 11.sp, color = TextMuted(), maxLines = 1)
        }
    }
}

// 本机文件折叠头

@Composable
private fun LocalFilesHeader(expanded: Boolean, fileCount: Int?, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        if (hovered) CardHoverColor() else CardColor(),
        tween(140), label = "localFilesHeaderBg"
    )

    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Radius.MD))
            .background(bg)
            .border(1.dp, BorderColor(), RoundedCornerShape(Radius.MD))
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = null,
                tint = AccentColor(),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "本机文件",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary()
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (expanded && fileCount != null) "共 $fileCount 项 · 点击折叠"
                    else "点击展开浏览电脑上的文件",
                    fontSize = 11.sp,
                    color = TextMuted()
                )
            }
            val rotation by animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                animationSpec = tween(200, easing = FastOutSlowInEasing),
                label = "localFilesArrow"
            )
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = null,
                tint = TextSecondary(),
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = rotation }
            )
        }
    }
}

// 本机文件行

@Composable
private fun LocalFileRow(
    entry: LocalFileEntry,
    selected: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onRightClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = when {
            selected -> AccentSoft()
            hovered -> CardHoverColor()
            else -> Color.Transparent
        },
        animationSpec = tween(120),
        label = "localFileRowBg"
    )

    val lastClickTimes = remember { mutableStateMapOf<String, Long>() }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.XS))
            .background(bg)
            .pointerInput(entry.path) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press) {
                            val isRight = try { event.buttons.isSecondaryPressed } catch (_: Throwable) { false }
                            if (isRight) {
                                onRightClick()
                                continue
                            }
                            val now = System.currentTimeMillis()
                            val last = lastClickTimes[entry.path] ?: 0L
                            if (now - last < 380) {
                                lastClickTimes.remove(entry.path)
                                onOpen()
                            } else {
                                lastClickTimes[entry.path] = now
                                onSelect()
                            }
                        }
                    }
                }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (entry.isDirectory) Icons.Default.Folder else fileIcon(entry.name),
            contentDescription = null,
            tint = if (entry.isDirectory) AccentColor() else TextSecondary(),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            entry.name,
            fontSize = 13.sp,
            color = TextPrimary(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!entry.isDirectory) {
            Text(
                formatFileSize(entry.size),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = TextMuted()
            )
        }
    }
}

