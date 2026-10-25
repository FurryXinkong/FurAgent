package net.xinkong.furagent.ui.pages
import net.xinkong.furagent.ui.theme.AppRadius

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import net.xinkong.furagent.ui.SuccessColor
import net.xinkong.furagent.shared.util.formatFileSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.xinkong.furagent.bridge.PcBridgeClient
import net.xinkong.furagent.ui.PocoCard
import java.io.File

/**
 * PcHub Tab 1：文件传输（从 PcHubPage.kt 拆分）。
 */
// Tab 1：文件传输

@Composable
fun PcFileTransferTab(
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

    // 转发给另一台设备（通过电脑中转 relay）
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
                            shape = RoundedCornerShape(AppRadius.MD_MINUS),
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
                        shape = RoundedCornerShape(AppRadius.SM),
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
                        shape = RoundedCornerShape(AppRadius.SM),
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
                            color = if (sendOk) SuccessColor() else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // 转发给另一台设备（relay）
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
                        shape = RoundedCornerShape(AppRadius.SM),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(16.dp))
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

    // relay 目标选择对话框
    if (showRelayDialog) {
        val file = relayFile
        AlertDialog(
            onDismissRequest = { if (!relaySending) showRelayDialog = false },
            shape = RoundedCornerShape(AppRadius.DIALOG),
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
                                        shape = RoundedCornerShape(AppRadius.SM),
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
