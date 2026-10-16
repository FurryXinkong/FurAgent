@file:OptIn(ExperimentalMaterial3Api::class)

package net.xinkong.furagent.ui.pages

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.xinkong.furagent.bridge.PcBridgeClient
import net.xinkong.furagent.data.*
import net.xinkong.furagent.shared.util.DirectDevice
import net.xinkong.furagent.shared.util.DirectDeviceCodec
import net.xinkong.furagent.shared.util.DirectTransfer
import net.xinkong.furagent.system.LocalNetworkPermission
import net.xinkong.furagent.system.notifyToast
import net.xinkong.furagent.ui.ApplyBackgroundBlur
import net.xinkong.furagent.ui.GlassSheen
import net.xinkong.furagent.ui.PocoCard
import net.xinkong.furagent.ui.glassPageBg
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * 电脑互联 —— 整屏页面。
 *
 * 【UI 统一约定】
 *   - 卡片统一 PocoCard
 *   - 页背景 glassPageBg() + 顶部 GlassSheen
 *   - 空状态图标 56dp
 *   - 主色 / 危险色走 MaterialTheme.colorScheme
 */
@Composable
fun PcHubPage(
    settings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var currentTab by remember { mutableIntStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var showConfigDialog by remember { mutableStateOf(false) }

    val status by PcBridgeClient.status.collectAsState()
    val deviceName by PcBridgeClient.pcDeviceName.collectAsState()
    val pcMac by PcBridgeClient.pcMac.collectAsState()

    ApplyBackgroundBlur(true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "电脑互联",
                            fontWeight = FontWeight.Black,
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val dotColor = when (status) {
                                PcBridgeClient.Status.CONNECTED -> Color(0xFF34C759)
                                PcBridgeClient.Status.CONNECTING -> Color(0xFFF59E0B)
                                PcBridgeClient.Status.DISCONNECTED -> MaterialTheme.colorScheme.error
                            }
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                buildString {
                                    append(
                                        when (status) {
                                            PcBridgeClient.Status.CONNECTED ->
                                                "已连接：${deviceName.ifBlank { "电脑" }}"
                                            PcBridgeClient.Status.CONNECTING -> "连接中…"
                                            PcBridgeClient.Status.DISCONNECTED -> "未连接"
                                        }
                                    )
                                    if (pcMac.isNotBlank()) append(" · $pcMac")
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showConfigDialog = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "配置连接",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
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
                // ============ Tab 行 ============
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HubTab("文件传输", 0, currentTab) { currentTab = 0 }
                    HubTab("浏览文件", 1, currentTab) { currentTab = 1 }
                    HubTab("已知设备", 2, currentTab) { currentTab = 2 }
                    HubTab("直连传输", 3, currentTab) { currentTab = 3 }
                }

                // ============ 内容区 ============
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (currentTab) {
                        0 -> PcFileTransferTab(
                            modifier = Modifier.fillMaxSize(),
                            connected = status == PcBridgeClient.Status.CONNECTED,
                            onConfigure = { showConfigDialog = true },
                            onInfo = { notifyToast(context, it) }
                        )
                        1 -> PcBrowserTab(
                            modifier = Modifier.fillMaxSize(),
                            onInfo = { notifyToast(context, it) }
                        )
                        2 -> PcDevicesTab(
                            modifier = Modifier.fillMaxSize(),
                            refreshKey = refreshKey,
                            onRefresh = { refreshKey++ },
                            onInfo = { notifyToast(context, it) }
                        )
                        3 -> DirectTransferTab(
                            modifier = Modifier.fillMaxSize(),
                            onInfo = { notifyToast(context, it) }
                        )
                    }
                }
            }
        }
    }

    if (showConfigDialog) {
        PcConnectionDialog(
            initial = settings,
            onDismiss = { showConfigDialog = false },
            onSave = { newSettings ->
                onSettingsChanged(newSettings)
                showConfigDialog = false
                if (newSettings.pcBridgeEnabled && newSettings.pcBridgeHost.isNotBlank()) {
                    try {
                        if (!LocalNetworkPermission.isGranted(context)) {
                            LocalNetworkPermission.ensureFromContext(context)
                            notifyToast(context, "请先授予本地网络权限，然后重新点击连接")
                            return@PcConnectionDialog
                        }
                        PcBridgeClient.connect(
                            context,
                            newSettings.pcBridgeHost,
                            newSettings.pcBridgePort,
                            newSettings
                        )
                        notifyToast(context, "正在连接 ${newSettings.pcBridgeHost}:${newSettings.pcBridgePort}")
                    } catch (e: Exception) {
                        notifyToast(context, "连接失败：${e.message}")
                    }
                } else {
                    try {
                        PcBridgeClient.disconnect()
                    } catch (_: Exception) { }
                }
            }
        )
    }
}

// ============================================================
// 连接配置对话框
// ============================================================

@Composable
private fun PcConnectionDialog(
    initial: AppSettings,
    onDismiss: () -> Unit,
    onSave: (AppSettings) -> Unit
) {
    var enabled by remember { mutableStateOf(initial.pcBridgeEnabled) }
    var host by remember { mutableStateOf(initial.pcBridgeHost) }
    var portText by remember { mutableStateOf(initial.pcBridgePort.toString()) }
    var receiveDir by remember { mutableStateOf(initial.pcReceiveDir) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("配置电脑连接") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "在电脑端 FurAgent Desktop 打开后，会显示本机 IP 和端口（默认 8425）。填到下面即可连接。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { enabled = !enabled },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                    Spacer(Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "启用电脑互联",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "关闭时不会自动连接",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it; errorMsg = null },
                    label = { Text("电脑 IP 或主机名") },
                    placeholder = { Text("192.168.1.100") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )

                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("端口") },
                    placeholder = { Text("8425") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )

                OutlinedTextField(
                    value = receiveDir,
                    onValueChange = { receiveDir = it },
                    label = { Text("接收目录") },
                    placeholder = { Text("/sdcard/FurAgent/received") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )

                Text(
                    "接收目录：电脑推过来的文件会强制落在这一路径下。可留空使用默认值。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                errorMsg?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val port = portText.toIntOrNull()
                when {
                    enabled && host.isBlank() -> errorMsg = "启用时必须填写 IP"
                    enabled && (port == null || port !in 1..65535) ->
                        errorMsg = "端口需在 1~65535 之间"
                    else -> {
                        val newSettings = initial.copy(
                            pcBridgeEnabled = enabled,
                            pcBridgeHost = host.trim(),
                            pcBridgePort = port ?: 8425,
                            pcReceiveDir = receiveDir.trim().ifBlank { "/sdcard/FurAgent/received" }
                        )
                        onSave(newSettings)
                    }
                }
            }) { Text("保存", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            Row {
                if (initial.pcBridgeEnabled && initial.pcBridgeHost.isNotBlank()) {
                    TextButton(onClick = {
                        PcBridgeClient.disconnect()
                        onSave(initial.copy(pcBridgeEnabled = false))
                    }) {
                        Text("断开", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

// ============================================================
// Tab 按钮
// ============================================================

@Composable
private fun RowScope.HubTab(
    label: String,
    index: Int,
    current: Int,
    onClick: () -> Unit
) {
    val selected = index == current
    val bg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        animationSpec = tween(140),
        label = "hubTabBg"
    )
    val fg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(140),
        label = "hubTabFg"
    )
    Box(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = fg,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// ============================================================
// Tab 1：文件传输
// ============================================================

@Composable
private fun PcFileTransferTab(
    modifier: Modifier = Modifier,
    connected: Boolean,
    onConfigure: () -> Unit,
    onInfo: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val httpPort by PcBridgeClient.httpPort.collectAsState()
    val httpToken by PcBridgeClient.httpToken.collectAsState()
    val httpReady = connected && httpPort > 0 && httpToken.isNotBlank()

    // ============ 发送到电脑 ============
    var sendLocalPath by remember { mutableStateOf("") }
    var sendLocalName by remember { mutableStateOf("") }
    var sendStatus by remember { mutableStateOf("") }
    var sendOk by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var sendProgress by remember { mutableFloatStateOf(0f) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val name = queryFileName(context, uri) ?: "picked_${System.currentTimeMillis()}"
                val dst = File(context.cacheDir, name)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dst.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                if (dst.exists() && dst.length() > 0) {
                    sendLocalPath = dst.absolutePath
                    sendLocalName = name
                    sendStatus = ""
                } else {
                    sendStatus = "读取文件失败"
                    sendOk = false
                }
            } catch (e: Exception) {
                sendStatus = "读取失败：${e.message}"
                sendOk = false
            }
        }
    }

    fun doSend() {
        if (sending || !httpReady) return
        val file = File(sendLocalPath)
        if (!file.exists() || !file.isFile) {
            sendStatus = "文件不存在"
            sendOk = false
            return
        }
        sending = true
        sendStatus = "正在上传…"
        sendOk = true
        sendProgress = 0f

        scope.launch {
            val result = uploadFileViaHttp(
                file = file,
                onProgress = { read, total ->
                    sendProgress = if (total > 0) read.toFloat() / total else 0f
                }
            )
            sending = false
            sendOk = result.first
            sendStatus = result.second
        }
    }

    // ============ 【F6】转发给另一台设备（通过电脑中转 relay） ============
    var relayFile by remember { mutableStateOf<File?>(null) }
    var relayFileSize by remember { mutableLongStateOf(0L) }
    var relayTargets by remember { mutableStateOf<List<PcBridgeClient.PeerInfo>?>(null) }
    var relayTargetLoading by remember { mutableStateOf(false) }
    var relayTargetError by remember { mutableStateOf<String?>(null) }
    var relaySelectedTarget by remember { mutableStateOf<PcBridgeClient.PeerInfo?>(null) }
    var relaySending by remember { mutableStateOf(false) }
    var showRelayDialog by remember { mutableStateOf(false) }
    var lastRelayId by remember { mutableStateOf<String?>(null) }
    var lastRelayStatus by remember { mutableStateOf<String?>(null) }

    val relayPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val name = queryFileName(context, uri) ?: "picked_${System.currentTimeMillis()}"
                val dst = File(context.cacheDir, name)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dst.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                if (!dst.exists() || dst.length() == 0L) {
                    onInfo("读取文件失败")
                    return@launch
                }
                relayFile = dst
                relayFileSize = dst.length()
                relaySelectedTarget = null
                relayTargets = null
                relayTargetError = null
                showRelayDialog = true

                // 自动拉取在线设备
                relayTargetLoading = true
                val localName = PcBridgeClient.getLocalDeviceName()
                val peers = withContext(Dispatchers.IO) { PcBridgeClient.listPeers() }
                // 过滤掉自己
                relayTargets = peers.filter { it.deviceName != localName }
                relayTargetLoading = false
                if (relayTargets.isNullOrEmpty()) {
                    relayTargetError = "当前没有其他在线设备（只有本机和电脑）"
                }
            } catch (e: Exception) {
                relayTargetLoading = false
                relayTargetError = "拉取设备列表失败：${e.message}"
                onInfo("读取失败：${e.message}")
            }
        }
    }

    fun doRelay() {
        val file = relayFile ?: return
        val target = relaySelectedTarget ?: return
        if (relaySending) return
        relaySending = true
        scope.launch {
            try {
                val relayId = withContext(Dispatchers.IO) {
                    PcBridgeClient.sendRelayFile(target.id, file)
                }
                relaySending = false
                lastRelayId = relayId
                lastRelayStatus = "已发起中转（等待对方回执…）"
                onInfo("已发起中转，relayId=$relayId")
                showRelayDialog = false
                // 清理临时文件
                try { file.delete() } catch (_: Exception) { }
                relayFile = null
            } catch (e: Exception) {
                relaySending = false
                onInfo("发起中转失败：${e.message}")
            }
        }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!connected) {
            item {
                PocoCard {
                    Column(
                        modifier = Modifier.padding(20.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Computer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp)
                        )
                        Text(
                            "还没连接电脑",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "1. 在电脑上打开 FurAgent Desktop\n" +
                                    "2. 查看电脑端显示的 IP 和端口\n" +
                                    "3. 点下方按钮填到手机里",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = onConfigure,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Settings, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("配置电脑连接")
                        }
                    }
                }
            }
        }

        // ============ 发送到电脑 ============
        item {
            PocoCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "发送到电脑",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (httpReady) "HTTP 通道已就绪" else "HTTP 通道未就绪（请先连接）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedButton(
                        onClick = { picker.launch("*/*") },
                        enabled = !sending,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (sendLocalName.isBlank()) "选择文件"
                            else "已选：$sendLocalName",
                            maxLines = 1
                        )
                    }

                    if (sendLocalPath.isNotBlank()) {
                        val size = File(sendLocalPath).length()
                        Text(
                            "大小：${formatFileSize(size)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (sending) {
                        LinearProgressIndicator(
                            progress = { sendProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Button(
                        onClick = { doSend() },
                        enabled = !sending && httpReady && sendLocalPath.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (sending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (sending) "上传中…" else "发送")
                    }

                    if (sendStatus.isNotBlank()) {
                        Text(
                            sendStatus,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (sendOk) Color(0xFF34C759) else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // ============ 【F6】转发给另一台设备（relay） ============
        item {
            PocoCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "转发给另一台设备",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "选一个本地文件，通过电脑中转给另一台手机 / 电脑。" +
                                "适用于两台设备互相不在线、但都能连到同一台电脑的场景。" +
                                "文件走 HTTP 通道，大文件也支持。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 17.sp
                    )

                    Button(
                        onClick = {
                            if (!connected) {
                                onInfo("请先连接电脑")
                                return@Button
                            }
                            relayPicker.launch("*/*")
                        },
                        enabled = connected && !relaySending,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Send, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("选择文件并转发")
                    }

                    // 上一次 relay 的结果
                    lastRelayId?.let { id ->
                        Spacer(Modifier.height(2.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Info, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "relayId：$id",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                                lastRelayStatus?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            TextButton(onClick = {
                                val status = PcBridgeClient.getRelayStatus(id)
                                lastRelayStatus = if (status == null) {
                                    "尚未收到回执（对方可能还在接收）"
                                } else {
                                    if (status.first) "已完成：${status.second}"
                                    else "失败：${status.second}"
                                }
                            }) { Text("查询状态", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }

        item {
            PocoCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "提示",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "· 从电脑下载文件：到「浏览文件」标签页，单击文件名即可下载。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "· 与另一台手机 / 电脑直传文件（不经电脑中转）：到「直连传输」标签页。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    // ============================================================
    // 【F6】relay 目标选择对话框
    // ============================================================
    if (showRelayDialog) {
        val file = relayFile
        AlertDialog(
            onDismissRequest = { if (!relaySending) showRelayDialog = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("转发给另一台设备") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (file != null) {
                        Column {
                            Text(
                                "文件：${file.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2
                            )
                            Text(
                                "大小：${formatFileSize(relayFileSize)}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    when {
                        relayTargetLoading -> {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "正在拉取设备列表…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        relayTargetError != null -> {
                            Text(
                                relayTargetError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        relayTargets.isNullOrEmpty() -> {
                            Text(
                                "没有其他在线设备",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        else -> {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    "选择目标设备：",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(2.dp))
                                relayTargets!!.forEach { peer ->
                                    val selected = relaySelectedTarget?.id == peer.id
                                    val bg by animateColorAsState(
                                        targetValue = if (selected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        } else if (false) {
                                            // hover 未做
                                            Color.Transparent
                                        } else Color.Transparent,
                                        animationSpec = tween(140),
                                        label = "relayTargetBg"
                                    )
                                    val border = if (selected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    }

                                    Surface(
                                        color = bg,
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, border),
                                        onClick = { relaySelectedTarget = peer },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                if (peer.endpoint == "phone") Icons.Default.Smartphone
                                                else Icons.Default.Computer,
                                                contentDescription = null,
                                                tint = if (selected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    peer.deviceName.ifBlank { "未知设备" },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1
                                                )
                                                Text(
                                                    buildString {
                                                        if (peer.endpoint.isNotBlank()) {
                                                            append(if (peer.endpoint == "phone") "手机" else "电脑")
                                                        }
                                                        if (peer.mac.isNotBlank()) {
                                                            append(" · ")
                                                            append(peer.mac)
                                                        } else if (peer.remote.isNotBlank()) {
                                                            append(" · ")
                                                            append(peer.remote.trimStart('/'))
                                                        }
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1
                                                )
                                            }
                                            if (selected) {
                                                Icon(
                                                    Icons.Default.CheckCircle,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (relaySending) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "正在上传到电脑…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !relaySending && relaySelectedTarget != null,
                    onClick = { doRelay() }
                ) {
                    Text(
                        if (relaySending) "转发中…" else "转发",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        enabled = !relaySending,
                        onClick = {
                            relayFile?.let { f ->
                                try { f.delete() } catch (_: Exception) { }
                            }
                            relayFile = null
                            showRelayDialog = false
                        }
                    ) { Text("取消") }
                    if (relayTargets != null) {
                        TextButton(
                            enabled = !relayTargetLoading && !relaySending,
                            onClick = {
                                scope.launch {
                                    relayTargetLoading = true
                                    relayTargetError = null
                                    try {
                                        val localName = PcBridgeClient.getLocalDeviceName()
                                        val peers = withContext(Dispatchers.IO) {
                                            PcBridgeClient.listPeers()
                                        }
                                        relayTargets = peers.filter { it.deviceName != localName }
                                        if (relayTargets.isNullOrEmpty()) {
                                            relayTargetError = "当前没有其他在线设备"
                                        }
                                    } catch (e: Exception) {
                                        relayTargetError = "刷新失败：${e.message}"
                                    }
                                    relayTargetLoading = false
                                }
                            }
                        ) { Text("刷新") }
                    }
                }
            }
        )
    }
}

// ============================================================
// Tab 2：浏览文件
// ============================================================

@Composable
private fun PcBrowserTab(
    modifier: Modifier = Modifier,
    onInfo: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by PcBridgeClient.status.collectAsState()
    val connected = status == PcBridgeClient.Status.CONNECTED

    var currentPath by remember { mutableStateOf("~") }
    var entries by remember { mutableStateOf<List<PcEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloadingPath by remember { mutableStateOf<String?>(null) }

    fun load(path: String) {
        if (!connected) return
        loading = true
        error = null
        scope.launch {
            val args = JSONObject().apply { put("path", path) }
            val result = PcBridgeClient.callRemoteTool("list_files", args, 20)
            loading = false
            if (result.startsWith("失败")) {
                error = result.removePrefix("失败：").take(200)
                entries = emptyList()
            } else {
                entries = parsePcEntries(result)
                if (entries.isEmpty()) error = "空目录或无匹配"
            }
        }
    }

    fun downloadFile(remotePath: String) {
        val fileName = remotePath.substringAfterLast('/').substringAfterLast('\\')
        if (fileName.isBlank()) {
            onInfo("路径末尾没有文件名")
            return
        }
        downloadingPath = remotePath
        scope.launch {
            val args = JSONObject().apply {
                put("path", remotePath)
                put("mode", "base64")
            }
            val result = PcBridgeClient.callRemoteTool("read_file", args, 180)
            downloadingPath = null
            if (result.startsWith("失败")) {
                onInfo(result.removePrefix("失败：").take(200))
                return@launch
            }
            try {
                val bytes = android.util.Base64.decode(result.trim(), android.util.Base64.DEFAULT)
                val prefs = context.getSharedPreferences("FurAgentPrefs", Context.MODE_PRIVATE)
                val settings = SettingsStore.load(prefs)
                val target = resolveReceiveFile(
                    settings.pcReceiveDir, fileName
                )
                target.writeBytes(bytes)
                onInfo("已下载：${target.name}（${formatFileSize(bytes.size.toLong())}）")
            } catch (e: Exception) {
                onInfo("写入失败：${e.message}")
            }
        }
    }

    LaunchedEffect(currentPath, connected) {
        if (connected) load(currentPath)
    }

    Column(modifier) {
        OutlinedTextField(
            value = currentPath,
            onValueChange = { currentPath = it },
            label = { Text("路径") },
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            trailingIcon = {
                TextButton(onClick = {
                    val parent = currentPath.substringBeforeLast('/').substringBeforeLast('\\')
                    if (parent.isNotBlank() && parent != currentPath) currentPath = parent
                }) { Text("上级") }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            )
        )

        Spacer(Modifier.height(8.dp))

        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            when {
                !connected -> EmptyPcBrowserState(
                    title = "电脑未连接",
                    subtitle = "先去「文件传输」标签页配置"
                )
                loading -> {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(10.dp))
                        Text("读取中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                error != null -> {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.Warning, null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { load(currentPath) }) { Text("重试") }
                    }
                }
                else -> {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        items(entries, key = { it.path }) { entry ->
                            val downloading = downloadingPath == entry.path
                            Surface(
                                color = Color.Transparent,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (entry.isDirectory) {
                                            currentPath = entry.path
                                        } else {
                                            downloadFile(entry.path)
                                        }
                                    }
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (entry.isDirectory) Icons.Default.Folder
                                        else Icons.AutoMirrored.Filled.InsertDriveFile,
                                        null,
                                        tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        entry.name,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (entry.sizeText.isNotBlank()) {
                                        Text(
                                            entry.sizeText,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    if (downloading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    } else if (!entry.isDirectory) {
                                        Icon(
                                            Icons.Default.Download,
                                            null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.size(16.dp)
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
// 空状态（本地私有）
// ============================================================

@Composable
private fun EmptyPcBrowserState(title: String, subtitle: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Computer,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ============================================================
// 文件条目数据类（文件私有）
// ============================================================

private data class PcEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeText: String
)

private fun parsePcEntries(raw: String): List<PcEntry> {
    val out = mutableListOf<PcEntry>()
    var dir = ""
    raw.lines().forEach { line ->
        val t = line.trim()
        when {
            t.startsWith("目录：") -> dir = t.removePrefix("目录：").trim()
            t.startsWith("[目录]") -> {
                val name = t.removePrefix("[目录]").trim()
                if (name.isNotBlank()) out.add(PcEntry(name, joinPcPath(dir, name), true, ""))
            }
            t.startsWith("[文件]") -> {
                val rest = t.removePrefix("[文件]").trim()
                val m = Regex("""^(.+?)\s*\(([^)]+)\)$""").find(rest)
                val name = m?.groupValues?.get(1)?.trim() ?: rest
                val size = m?.groupValues?.get(2)?.trim() ?: ""
                if (name.isNotBlank()) out.add(PcEntry(name, joinPcPath(dir, name), false, size))
            }
        }
    }
    return out
}

private fun joinPcPath(dir: String, name: String): String {
    if (dir.isBlank()) return name
    val sep = if (dir.contains('\\')) "\\" else "/"
    return if (dir.endsWith("/") || dir.endsWith("\\")) "$dir$name" else "$dir$sep$name"
}

// ============================================================
// Tab 3：已知设备 + WOL
// ============================================================

@Composable
private fun PcDevicesTab(
    modifier: Modifier = Modifier,
    refreshKey: Int,
    onRefresh: () -> Unit,
    onInfo: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val status by PcBridgeClient.status.collectAsState()
    val currentMac by PcBridgeClient.pcMac.collectAsState()
    val connected = status == PcBridgeClient.Status.CONNECTED

    val devices = remember(refreshKey) { KnownPcStore.list(context) }

    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<KnownPc?>(null) }
    var wakingMac by remember { mutableStateOf<String?>(null) }
    var wakeCountdown by remember { mutableIntStateOf(0) }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "${devices.size} 台已知电脑",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("手动添加")
            }
        }

        if (devices.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.DevicesOther, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "暂无已知电脑",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "连接一次电脑后会自动记录 MAC\n之后就可以 WOL 远程开机",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices, key = { it.mac }) { pc ->
                    val online = connected && KnownPcStore.normalizeMac(currentMac) == pc.mac
                    val waking = wakingMac == pc.mac
                    PcDeviceCard(
                        pc = pc,
                        online = online,
                        waking = waking,
                        countdown = if (waking) wakeCountdown else 0,
                        onWake = {
                            if (waking) return@PcDeviceCard
                            wakingMac = pc.mac
                            wakeCountdown = 60
                            scope.launch {
                                val ok = PcBridgeClient.wakeAndReconnect(
                                    context = context,
                                    mac = pc.mac,
                                    timeoutSec = 60
                                )
                                wakingMac = null
                                wakeCountdown = 0
                                if (ok) {
                                    onInfo("已唤醒 ${pc.deviceName.ifBlank { pc.displayMac }}")
                                    onRefresh()
                                } else {
                                    onInfo("WOL 已发送，但 60 秒内没连上。请检查电脑 WOL 配置。")
                                }
                            }
                        },
                        onDelete = { pendingDelete = pc }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }

        LaunchedEffect(wakingMac) {
            if (wakingMac == null) return@LaunchedEffect
            wakeCountdown = 60
            while (wakeCountdown > 0 && wakingMac != null) {
                kotlinx.coroutines.delay(1000.milliseconds)
                wakeCountdown--
            }
        }
    }

    if (showAddDialog) {
        var macInput by remember { mutableStateOf("") }
        var nameInput by remember { mutableStateOf("") }
        var hostInput by remember { mutableStateOf("") }
        var errorMsg by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("手动添加电脑") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = macInput,
                        onValueChange = { macInput = it; errorMsg = null },
                        label = { Text("MAC 地址") },
                        placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("名称（可选）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                    OutlinedTextField(
                        value = hostInput,
                        onValueChange = { hostInput = it },
                        label = { Text("IP 地址（可选）") },
                        placeholder = { Text("192.168.1.100") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                    errorMsg?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val normalized = KnownPcStore.normalizeMac(macInput)
                    if (normalized.length != 12) {
                        errorMsg = "MAC 格式不正确（12 位十六进制）"
                        return@TextButton
                    }
                    KnownPcStore.upsert(
                        context,
                        KnownPc(
                            mac = normalized,
                            displayMac = KnownPcStore.formatMac(macInput),
                            deviceName = nameInput.trim(),
                            host = hostInput.trim(),
                            port = 8425,
                            lastSeenAt = 0L
                        )
                    )
                    showAddDialog = false
                    onRefresh()
                    onInfo("已添加：${KnownPcStore.formatMac(macInput)}")
                }) { Text("添加", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            }
        )
    }

    pendingDelete?.let { pc ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            shape = RoundedCornerShape(28.dp),
            title = { Text("删除设备") },
            text = {
                Text(
                    "确定从已知列表删除「${pc.deviceName.ifBlank { pc.displayMac }}」？\n" +
                            "此操作只影响本机记录。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    KnownPcStore.remove(context, pc.mac)
                    pendingDelete = null
                    onRefresh()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun PcDeviceCard(
    pc: KnownPc,
    online: Boolean,
    waking: Boolean,
    countdown: Int,
    onWake: () -> Unit,
    onDelete: () -> Unit
) {
    PocoCard {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(38.dp).clip(CircleShape)
                    .background(
                        if (online) Color(0x1834C759)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Computer, null,
                    tint = if (online) Color(0xFF34C759)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pc.deviceName.ifBlank { pc.displayMac },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (online) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(4.dp))
                                .background(Color(0x2034C759))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                "在线",
                                fontSize = 9.sp,
                                color = Color(0xFF34C759),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    pc.displayMac,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!online && pc.host.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${pc.host}:${pc.port}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))

            if (waking) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("$countdown", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                }
            } else if (!online) {
                FilledTonalButton(
                    onClick = onWake,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.PowerSettingsNew, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("唤醒", fontSize = 12.sp)
                }
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Delete, "删除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ============================================================
// Tab 4：直连传输
// ============================================================

@Composable
private fun DirectTransferTab(
    modifier: Modifier = Modifier,
    onInfo: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("FurAgentPrefs", Context.MODE_PRIVATE) }

    var devices by remember { mutableStateOf(DirectDeviceStore.list(context)) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var pendingUploadDevice by remember { mutableStateOf<DirectDevice?>(null) }
    var pendingDownloadDevice by remember { mutableStateOf<DirectDevice?>(null) }
    var pingStatus by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    // 【B4】刷新 token 确认弹窗
    var showRefreshTokenConfirm by remember { mutableStateOf(false) }

    val localIp = remember { getLocalIpForDirect() }
    val localPort = net.xinkong.furagent.bridge.AndroidHttpFileServer.getPort()
    val localToken = net.xinkong.furagent.bridge.AndroidHttpFileServer.getToken()
    val localShortCode = net.xinkong.furagent.bridge.AndroidHttpFileServer.getShortCode()
    val localConnStr = if (localIp != null && localPort > 0 && localToken.isNotBlank()) {
        DirectDeviceStore.buildConnectionString(localIp, localPort, localToken)
    } else ""

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val device = pendingUploadDevice
        pendingUploadDevice = null
        if (device == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val name = queryFileName(context, uri) ?: "picked_${System.currentTimeMillis()}"
                val cacheDir = File(context.cacheDir, "direct_upload")
                if (!cacheDir.exists()) cacheDir.mkdirs()
                val dst = File(cacheDir, name)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dst.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                if (!dst.exists() || dst.length() == 0L) {
                    onInfo("读取文件失败")
                    return@launch
                }
                val (ok, msg) = DirectTransfer.upload(device, dst)
                onInfo(msg)
                if (ok) {
                    DirectDeviceStore.touch(context, device.id)
                    devices = DirectDeviceStore.list(context)
                }
                try { dst.delete() } catch (_: Exception) { }
            } catch (e: Exception) {
                onInfo("上传失败：${e.message}")
            }
        }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ============================================================
        // 本机信息卡：连接串 + 短码
        // ============================================================
        item {
            PocoCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "本机连接信息",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "把「短码」报给对方（复制时会自动带上本机 IP），或把「连接串」复制过去。" +
                                "对方输入任意一种即可配对。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (localIp == null || localPort <= 0 || localToken.isBlank()) {
                        Text(
                            "⚠ 本机 HTTP 服务未就绪，无法生成连接信息。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Spacer(Modifier.height(4.dp))

                        // 短码区（醒目）
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "短码",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    localShortCode,
                                    fontSize = 22.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    letterSpacing = 3.sp
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "复制后为 ${localIp}:${localShortCode}，对方粘贴即可",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    try {
                                        val copyText = DirectDeviceCodec.buildShortCodeString(
                                            localIp, localShortCode
                                        )
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                                as android.content.ClipboardManager
                                        cm.setPrimaryClip(
                                            android.content.ClipData.newPlainText("shortcode", copyText)
                                        )
                                        onInfo("已复制：$copyText")
                                    } catch (e: Exception) {
                                        onInfo("复制失败：${e.message}")
                                    }
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("复制短码") }
                        }

                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(4.dp))

                        // 完整连接串区
                        Text(
                            "完整连接串",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                localConnStr,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    try {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                                as android.content.ClipboardManager
                                        cm.setPrimaryClip(
                                            android.content.ClipData.newPlainText("conn", localConnStr)
                                        )
                                        onInfo("已复制到剪贴板")
                                    } catch (e: Exception) {
                                        onInfo("复制失败：${e.message}")
                                    }
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("复制连接串") }

                            OutlinedButton(
                                onClick = { showRefreshTokenConfirm = true },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                                )
                            ) { Text("刷新 token") }
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "已知直连设备（${devices.size}）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("添加")
                }
            }
        }

        if (devices.isEmpty()) {
            item {
                PocoCard {
                    Column(
                        modifier = Modifier.padding(24.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "暂无直连设备",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "在另一台设备上打开 FurAgent，复制它的短码（形如 192.168.1.100:A3F7C1），" +
                                    "或复制它的连接串，然后在这里点「添加」。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(devices, key = { it.id }) { device ->
                DirectDeviceCard(
                    device = device,
                    pingOk = pingStatus[device.id],
                    onPing = {
                        scope.launch {
                            val ok = DirectTransfer.ping(device)
                            pingStatus = pingStatus + (device.id to ok)
                            if (ok) {
                                DirectDeviceStore.touch(context, device.id)
                                onInfo("${device.name} 在线")
                            } else {
                                onInfo("${device.name} 无法连通")
                            }
                        }
                    },
                    onUpload = {
                        pendingUploadDevice = device
                        picker.launch("*/*")
                    },
                    onDownload = { pendingDownloadDevice = device },
                    onDelete = { pendingDeleteId = device.id }
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    // ============================================================
    // 添加设备对话框（支持短码）
    // ============================================================
    if (showAddDialog) {
        var input by remember { mutableStateOf("") }
        var nameInput by remember { mutableStateOf("") }
        var errMsg by remember { mutableStateOf<String?>(null) }
        var pairing by remember { mutableStateOf(false) }
        val scopeAdd = rememberCoroutineScope()

        AlertDialog(
            onDismissRequest = { if (!pairing) showAddDialog = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("添加直连设备") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "支持两种输入：\n" +
                                "· 短码：IP:短码（例如 192.168.1.100:A3F7C1），会自动向对方换取凭证\n" +
                                "· 完整连接串：IP:端口:Token，直接可用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it; errMsg = null },
                        label = { Text("短码 或 连接串") },
                        placeholder = { Text("192.168.1.100:A3F7C1  或  192.168.1.100:8426:abc123def") },
                        singleLine = false,
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        isError = errMsg != null,
                        supportingText = errMsg?.let { { Text(it) } },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("显示名（可选）") },
                        placeholder = { Text("客厅的手机") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                    if (pairing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "正在向对方换取凭证…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !pairing,
                    onClick = {
                        val raw = input.trim()
                        if (raw.isBlank()) {
                            errMsg = "请输入短码或连接串"
                            return@TextButton
                        }

                        if (DirectDeviceCodec.isBareShortCode(raw)) {
                            errMsg = "短码需配合对方 IP，请填 IP:短码（如 192.168.1.100:${raw.uppercase()}）"
                            return@TextButton
                        }

                        if (DirectDeviceCodec.isShortCode(raw)) {
                            val triple = extractHostPortCode(raw)
                            if (triple == null) {
                                errMsg = "短码格式不正确（应为 IP:短码 或 IP:端口:短码）"
                                return@TextButton
                            }
                            val (host, port, code) = triple
                            pairing = true
                            errMsg = null
                            scopeAdd.launch {
                                val (ok, result) = DirectTransfer.pair(host, port, code)
                                pairing = false
                                if (!ok) {
                                    errMsg = result
                                    return@launch
                                }
                                val token = result
                                val device = DirectDevice(
                                    id = UUID.randomUUID().toString(),
                                    name = nameInput.trim().ifBlank { host },
                                    host = host,
                                    port = port,
                                    token = token
                                )
                                DirectDeviceStore.add(context, device)
                                devices = DirectDeviceStore.list(context)
                                showAddDialog = false
                                onInfo("已添加：${device.name}（通过短码配对）")
                            }
                        } else {
                            val device = DirectDeviceStore.parseConnectionString(raw, nameInput)
                            if (device == null) {
                                errMsg = "连接串格式不正确"
                                return@TextButton
                            }
                            DirectDeviceStore.add(context, device)
                            devices = DirectDeviceStore.list(context)
                            showAddDialog = false
                            onInfo("已添加：${device.name}")
                        }
                    }
                ) {
                    Text(
                        if (pairing) "配对中…" else "添加",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAddDialog = false },
                    enabled = !pairing
                ) { Text("取消") }
            }
        )
    }

    // ============================================================
    // 下载对话框（不变）
    // ============================================================
    pendingDownloadDevice?.let { device ->
        var remoteName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pendingDownloadDevice = null },
            shape = RoundedCornerShape(28.dp),
            title = { Text("从 ${device.name} 下载") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "输入对方接收目录里的文件名（或绝对路径），会下载到本机接收目录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = remoteName,
                        onValueChange = { remoteName = it },
                        label = { Text("远端文件名") },
                        placeholder = { Text("report.pdf") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = remoteName.trim()
                    if (name.isBlank()) return@TextButton
                    pendingDownloadDevice = null
                    scope.launch {
                        try {
                            val settings = SettingsStore.load(prefs)
                            val target = resolveReceiveFile(
                                settings.pcReceiveDir,
                                extractFileName(name)
                            )
                            val (ok, msg) = DirectTransfer.download(device, name, target)
                            onInfo(msg)
                            if (ok) {
                                DirectDeviceStore.touch(context, device.id)
                                devices = DirectDeviceStore.list(context)
                            }
                        } catch (e: Exception) {
                            onInfo("下载失败：${e.message}")
                        }
                    }
                }) { Text("下载", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDownloadDevice = null }) { Text("取消") }
            }
        )
    }

    // ============================================================
    // 删除确认（不变）
    // ============================================================
    pendingDeleteId?.let { id ->
        val device = devices.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            shape = RoundedCornerShape(28.dp),
            title = { Text("删除直连设备") },
            text = { Text("确定从本机移除「${device?.name ?: id}」？只影响本机记录。") },
            confirmButton = {
                TextButton(onClick = {
                    DirectDeviceStore.remove(context, id)
                    devices = DirectDeviceStore.list(context)
                    pendingDeleteId = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("取消") }
            }
        )
    }

    // ============================================================
    // 刷新 token 确认
    // ============================================================
    if (showRefreshTokenConfirm) {
        AlertDialog(
            onDismissRequest = { showRefreshTokenConfirm = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("刷新 token？") },
            text = {
                Text(
                    "刷新后：\n" +
                            "· 本机短码会变化\n" +
                            "· 已配对的设备全部失效，需要重新配对\n" +
                            "· 正在传输的文件会中断\n\n" +
                            "确定继续？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRefreshTokenConfirm = false
                    val newShort = net.xinkong.furagent.bridge.AndroidHttpFileServer.refreshToken()
                    onInfo("token 已刷新，新短码：$newShort")
                }) { Text("刷新", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showRefreshTokenConfirm = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 从短码输入里提取 host / port / code。
 */
private fun extractHostPortCode(raw: String): Triple<String, Int, String>? {
    val t = raw.trim()
    if (t.startsWith("http://") || t.startsWith("https://")) return null
    val parts = t.split(":").map { it.trim() }
    return when (parts.size) {
        2 -> {
            val host = parts[0]
            val code = parts[1]
            if (host.isBlank() || code.length !in 4..8) null
            else Triple(host, DirectDeviceCodec.DEFAULT_PORT, code.uppercase())
        }
        3 -> {
            val host = parts[0]
            val port = parts[1].toIntOrNull() ?: return null
            val code = parts[2]
            if (host.isBlank() || port <= 0 || code.length !in 4..8) null
            else Triple(host, port, code.uppercase())
        }
        else -> null
    }
}

@Composable
private fun DirectDeviceCard(
    device: DirectDevice,
    pingOk: Boolean?,
    onPing: () -> Unit,
    onUpload: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    PocoCard {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val statusColor = when (pingOk) {
                    true -> Color(0xFF34C759)
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Box(
                    Modifier.size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    device.name.ifBlank { device.host },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Delete, "删除",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                "${device.host}:${device.port}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onPing) { Text("检测") }
                Button(
                    onClick = onUpload,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Upload, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("上传", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onDownload,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("下载", fontSize = 12.sp)
                }
            }
        }
    }
}

// ============================================================
// 底层辅助
// ============================================================

private fun getLocalIpForDirect(): String? {
    return try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    } catch (_: Exception) { null }
}

private val httpTransferClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .build()
}

private suspend fun uploadFileViaHttp(
    file: File,
    onProgress: (Long, Long) -> Unit
): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    try {
        val baseUrl = PcBridgeClient.getBaseUrl()
        val token = PcBridgeClient.getHttpToken()
        if (baseUrl.isBlank() || token.isBlank()) {
            return@withContext false to "HTTP 通道未就绪"
        }

        val url = "$baseUrl/upload?token=${URLEncoder.encode(token, "UTF-8")}" +
                "&path=${URLEncoder.encode(file.name, "UTF-8")}"

        val body = file.asRequestBody("application/octet-stream".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        httpTransferClient.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (resp.isSuccessful) {
                true to "已上传：${file.name}（${formatFileSize(file.length())}）"
            } else {
                false to "HTTP ${resp.code}：$text"
            }
        }
    } catch (e: Exception) {
        false to "上传失败：${e.message}"
    }
}

private fun queryFileName(context: Context, uri: android.net.Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (_: Exception) { null }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1fKB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1fMB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.2fGB", bytes / 1024.0 / 1024.0 / 1024.0)
}