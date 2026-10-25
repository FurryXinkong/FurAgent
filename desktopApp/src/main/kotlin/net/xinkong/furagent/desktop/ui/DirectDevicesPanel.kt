package net.xinkong.furagent.desktop.ui

import net.xinkong.furagent.shared.util.DateFormats
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import net.xinkong.furagent.desktop.bridge.HttpFileServer
import net.xinkong.furagent.desktop.data.DirectDeviceStore
import net.xinkong.furagent.desktop.data.DesktopSettingsHolder
import net.xinkong.furagent.desktop.system.NativeFilePicker
import net.xinkong.furagent.desktop.tools.DesktopTools
import net.xinkong.furagent.desktop.tools.PhoneTools
import net.xinkong.furagent.desktop.ui.theme.Radius
import net.xinkong.furagent.desktop.ui.theme.Spacing
import net.xinkong.furagent.shared.EndpointId
import net.xinkong.furagent.shared.tools.ToolResult
import net.xinkong.furagent.shared.util.DirectDevice
import net.xinkong.furagent.shared.util.DirectDeviceCodec
import net.xinkong.furagent.shared.util.DirectTransfer
import net.xinkong.furagent.shared.util.FileNaming
import net.xinkong.furagent.shared.util.formatFileSize
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*

// 直连设备 section

@Composable
internal fun DirectDevicesSection(onInfo: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf(DirectDeviceStore.list()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var pingStatus by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    // 刷新 token 确认弹窗
    var showRefreshTokenConfirm by remember { mutableStateOf(false) }

    val localIp = remember {
        try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) { null }
    }
    val localPort = HttpFileServer.getPort()
    val localToken = HttpFileServer.getToken()
    val localShortCode = HttpFileServer.getShortCode()
    val localConnStr = if (localIp != null && localPort > 0 && localToken.isNotBlank()) {
        DirectDeviceStore.buildConnectionString(localIp, localPort, localToken)
    } else ""

    Column(Modifier.fillMaxWidth()) {
        Text(
            "直连设备（${devices.size}）",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextSecondary()
        )
        Spacer(Modifier.height(8.dp))

        // 本机信息卡：连接串 + 短码
        GlassCard(padding = PaddingValues(14.dp)) {
            Text(
                "本机连接信息",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "把「短码」报给对方（复制时会自动带上本机 IP），或把「连接串」复制过去。" +
                        "对方在「添加直连设备」里输入任意一种即可配对。",
                fontSize = 11.sp,
                color = TextSecondary()
            )
            Spacer(Modifier.height(12.dp))

            if (localIp == null || localPort <= 0 || localToken.isBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = ErrorColor()
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "本机 HTTP 服务未就绪，无法生成连接信息。",
                        fontSize = 11.sp,
                        color = ErrorColor()
                    )
                }
            } else {
                // 短码区（醒目）
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("短码", fontSize = 11.sp, color = TextMuted())
                        Spacer(Modifier.height(2.dp))
                        Text(
                            localShortCode,
                            fontSize = 22.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = AccentColor(),
                            letterSpacing = 3.sp
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "复制后为 ${localIp}:${localShortCode}，对方粘贴即可",
                            fontSize = 10.sp,
                            color = TextMuted()
                        )
                    }
                    SecondaryButton(text = "复制短码") {
                        val copyText = DirectDeviceCodec.buildShortCodeString(localIp, localShortCode)
                        val ok = SafeClipboard.setText(copyText)
                        onInfo(if (ok) "已复制：$copyText" else "复制失败")
                    }
                }

                Spacer(Modifier.height(12.dp))
                GlassDivider(color = BorderSubtle())
                Spacer(Modifier.height(12.dp))

                // 完整连接串区
                Text("完整连接串", fontSize = 11.sp, color = TextMuted())
                Spacer(Modifier.height(4.dp))
                Surface(
                    color = CardStrongColor(),
                    shape = RoundedCornerShape(Radius.SM),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        localConnStr,
                        modifier = Modifier.padding(10.dp),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary()
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(text = "复制连接串") {
                        val ok = SafeClipboard.setText(localConnStr)
                        onInfo(if (ok) "已复制到剪贴板" else "复制失败（系统剪贴板被占用）")
                    }
                    DangerButton(text = "刷新 token") {
                        showRefreshTokenConfirm = true
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "已知设备",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary(),
                modifier = Modifier.weight(1f)
            )
            SecondaryButton("添加") { showAddDialog = true }
        }

        Spacer(Modifier.height(8.dp))
        if (devices.isEmpty()) {
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
                    Spacer(Modifier.height(10.dp))
                    Text("暂无直连设备", fontSize = 13.sp, color = TextSecondary())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "在另一台设备上打开 FurAgent，复制它的短码（形如 192.168.1.100:A3F7C1），" +
                                "或复制它的连接串，然后在这里点「添加」。",
                        fontSize = 11.sp,
                        color = TextMuted(),
                        modifier = Modifier.padding(horizontal = 20.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                devices.forEach { device ->
                    key(device.id) {
                        DirectDeviceCardDesktop(
                            device = device,
                            pingOk = pingStatus[device.id],
                            onPing = {
                                scope.launch {
                                    val ok = DirectTransfer.ping(device)
                                    pingStatus = pingStatus + (device.id to ok)
                                    if (ok) {
                                        DirectDeviceStore.touch(device.id)
                                        onInfo("${device.name} 在线")
                                    } else {
                                        onInfo("${device.name} 无法连通")
                                    }
                                }
                            },
                            onUpload = {
                                // 改用原生文件对话框
                                val files = NativeFilePicker.pickFiles(title = "选择要发送的文件")
                                if (files.isEmpty()) return@DirectDeviceCardDesktop
                                scope.launch {
                                    var okCount = 0
                                    var failMsg = ""
                                    files.forEach { f ->
                                        if (!f.exists() || !f.isFile) return@forEach
                                        val (ok, msg) = DirectTransfer.upload(device, f)
                                        if (ok) okCount++ else failMsg = msg
                                    }
                                    if (okCount > 0) {
                                        DirectDeviceStore.touch(device.id)
                                        onInfo("已发送 $okCount 个文件到 ${device.name}")
                                    } else {
                                        onInfo(failMsg.ifBlank { "上传失败" })
                                    }
                                }
                            },
                            onDelete = { pendingDeleteId = device.id }
                        )
                    }
                }
            }
        }
    }

    // 添加设备对话框（支持短码）
    if (showAddDialog) {
        var input by remember { mutableStateOf("") }
        var nameInput by remember { mutableStateOf("") }
        var errMsg by remember { mutableStateOf<String?>(null) }
        var pairing by remember { mutableStateOf(false) }
        val scopeAdd = rememberCoroutineScope()

        GlassDialog(
            title = "添加直连设备",
            onDismiss = { if (!pairing) showAddDialog = false },
            width = 560.dp,
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HintText(
                        "支持两种输入：\n" +
                                "· 短码：IP:短码（例如 192.168.1.100:A3F7C1），会自动向对方换取凭证\n" +
                                "· 完整连接串：IP:端口:Token，直接可用"
                    )
                    GlassTextField(
                        input,
                        { input = it; errMsg = null },
                        label = "短码 或 连接串",
                        placeholder = "192.168.1.100:A3F7C1  或  192.168.1.100:8426:abc123def",
                        singleLine = false, minLines = 2, maxLines = 3, monospace = true
                    )
                    GlassTextField(
                        nameInput,
                        { nameInput = it },
                        label = "显示名（可选）",
                        placeholder = "客厅的手机"
                    )
                    if (pairing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = AccentColor()
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("正在向对方换取凭证…", fontSize = 11.sp, color = TextSecondary())
                        }
                    }
                    errMsg?.let { Text(it, color = ErrorColor(), fontSize = 12.sp) }
                }
            },
            actions = {
                TextButton(onClick = { showAddDialog = false }, enabled = !pairing) {
                    Text("取消", color = TextSecondary())
                }
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
                            // 短码流程：先解析 host/port/code，再调 /pair
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
                                DirectDeviceStore.add(device)
                                devices = DirectDeviceStore.list()
                                showAddDialog = false
                                onInfo("已添加：${device.name}（通过短码配对）")
                            }
                        } else {
                            // 完整连接串流程
                            val d = DirectDeviceStore.parseConnectionString(raw, nameInput)
                            if (d == null) {
                                errMsg = "连接串格式不正确"
                                return@TextButton
                            }
                            DirectDeviceStore.add(d)
                            devices = DirectDeviceStore.list()
                            showAddDialog = false
                            onInfo("已添加：${d.name}")
                        }
                    }
                ) {
                    Text(
                        if (pairing) "配对中…" else "添加",
                        color = AccentColor(),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        )
    }

    // 删除确认
    pendingDeleteId?.let { id ->
        val d = devices.firstOrNull { it.id == id }
        ConfirmDialog(
            title = "删除直连设备",
            message = "确定从本机移除「${d?.name ?: id}」？只影响本机记录。",
            confirmText = "删除", danger = true,
            onConfirm = {
                DirectDeviceStore.remove(id)
                devices = DirectDeviceStore.list()
                pendingDeleteId = null
            },
            onDismiss = { pendingDeleteId = null }
        )
    }

    // 刷新 token 确认
    if (showRefreshTokenConfirm) {
        ConfirmDialog(
            title = "刷新 token？",
            message = "刷新后：\n" +
                    "· 本机短码会变化\n" +
                    "· 已配对的设备全部失效，需要重新配对\n" +
                    "· 正在传输的文件会中断\n\n" +
                    "确定继续？",
            confirmText = "刷新", danger = true,
            onConfirm = {
                showRefreshTokenConfirm = false
                val newShort = HttpFileServer.refreshToken()
                onInfo("token 已刷新，新短码：$newShort")
            },
            onDismiss = { showRefreshTokenConfirm = false }
        )
    }
}

/**
 * 从短码输入里提取 host / port / code。
 * 只在 DirectDeviceCodec.isShortCode(raw) == true 时调用。
 */
internal fun extractHostPortCode(raw: String): Triple<String, Int, String>? {
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
internal fun DirectDeviceCardDesktop(
    device: DirectDevice,
    pingOk: Boolean?,
    onPing: () -> Unit,
    onUpload: () -> Unit,
    onDelete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var showDownloadDialog by remember { mutableStateOf(false) }
    var remoteName by remember { mutableStateOf("") }

    GlassCard(padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val statusColor = when (pingOk) {
                true -> ResultColor()
                false -> ErrorColor()
                else -> TextMuted()
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    device.name.ifBlank { device.host },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary()
                )
                Text(
                    "${device.host}:${device.port}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextMuted()
                )
            }
            SecondaryButton("检测") { onPing() }
            Spacer(Modifier.width(6.dp))
            PrimaryButton(text = "上传", icon = Icons.Default.Upload) { onUpload() }
            Spacer(Modifier.width(6.dp))
            SecondaryButton("下载") { showDownloadDialog = true }
            Spacer(Modifier.width(6.dp))
            DangerButton("删除") { onDelete() }
        }
    }

    if (showDownloadDialog) {
        GlassDialog(
            title = "从 ${device.name} 下载",
            onDismiss = { showDownloadDialog = false },
            width = 480.dp,
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HintText("输入对方接收目录里的文件名（或绝对路径），会下载到本机接收目录。")
                    GlassTextField(
                        remoteName,
                        { remoteName = it },
                        label = "远端文件名",
                        placeholder = "report.pdf",
                        monospace = true
                    )
                }
            },
            actions = {
                TextButton(onClick = { showDownloadDialog = false }) {
                    Text("取消", color = TextSecondary())
                }
                TextButton(onClick = {
                    val name = remoteName.trim()
                    if (name.isBlank()) return@TextButton
                    showDownloadDialog = false
                    scope.launch {
                        try {
                            val target = FileNaming.resolveReceiveFile(
                                DesktopSettingsHolder.current.receiveDir,
                                FileNaming.extractFileName(name)
                            )
                            val (ok, msg) = DirectTransfer.download(device, name, target)
                            ToastBus.info(msg)
                            if (ok) DirectDeviceStore.touch(device.id)
                        } catch (e: Exception) {
                            ToastBus.error("下载失败：${e.message}")
                        }
                    }
                }) {
                    Text("下载", color = AccentColor(), fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}

// 设备卡片

@Composable
internal fun DeviceCard(
    client: BridgeServer.ClientInfo,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onInfo: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val isPhone = client.endpoint == EndpointId.PHONE
    val initialPath = if (isPhone) "/sdcard" else "/"

    var currentPath by remember(client.id) { mutableStateOf(initialPath) }
    var entries by remember(client.id) { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var loading by remember(client.id) { mutableStateOf(false) }
    var error by remember(client.id) { mutableStateOf<String?>(null) }
    var searchText by remember(client.id) { mutableStateOf("") }
    var selectedPath by remember(client.id) { mutableStateOf<String?>(null) }
    var downloadingPath by remember(client.id) { mutableStateOf<String?>(null) }
    var contextMenuFor by remember(client.id) { mutableStateOf<String?>(null) }

    var pathDraft by remember(client.id) {
        mutableStateOf(displayRemotePath(currentPath, isPhone))
    }
    var pathEditing by remember(client.id) { mutableStateOf(false) }

    LaunchedEffect(currentPath) {
        if (!pathEditing) {
            pathDraft = displayRemotePath(currentPath, isPhone)
        }
    }

    fun load(path: String) {
        loading = true
        error = null
        selectedPath = null
        val backendPath = normalizeRemotePathForBackend(path, isPhone)
        scope.launch {
            val args = JSONObject().apply { put("path", backendPath) }
            val result: ToolResult = if (isPhone) {
                PhoneTools.callRemoteTool(client.id, "list_files", args, 20)
            } else {
                ToolResult.fail("暂不支持浏览其他桌面端")
            }
            loading = false
            if (!result.success) {
                error = result.output.removePrefix("失败：")
                entries = emptyList()
            } else {
                entries = parseRemoteEntries(result.output, backendPath)
                if (entries.isEmpty()) error = "空目录"
            }
        }
    }

    fun downloadFile(remotePath: String) {
        downloadingPath = remotePath
        scope.launch {
            val fileName = remotePath.substringAfterLast('/')
            val target = FileNaming.resolveReceiveFile(
                DesktopSettingsHolder.current.receiveDir,
                fileName
            )
            val (ok, msg) = PhoneTools.downloadFileFromClient(client.id, remotePath, target)
            downloadingPath = null
            onInfo(msg)
            if (ok) {
                try {
                    val os = System.getProperty("os.name").lowercase()
                    val cmd = when {
                        os.contains("win") -> arrayOf("explorer", "/select,", target.absolutePath)
                        os.contains("mac") -> arrayOf("open", "-R", target.absolutePath)
                        else -> arrayOf("xdg-open", target.parentFile?.absolutePath ?: ".")
                    }
                    ProcessBuilder(*cmd).start()
                } catch (_: Exception) { }
            }
        }
    }

    fun downloadFolder(remotePath: String) {
        downloadingPath = remotePath
        scope.launch {
            val tmpName = "_furagent_tmp_${System.currentTimeMillis()}.zip"
            val zipPath = "/sdcard/FurAgent/temp/$tmpName"

            val zipArgs = JSONObject().apply {
                put("op", "zip")
                put("source_dir", remotePath)
                put("output_zip", zipPath)
            }
            val zipResult = PhoneTools.callRemoteTool(client.id, "zip", zipArgs, 180)
            if (!zipResult.success) {
                downloadingPath = null
                onInfo("打包失败：${zipResult.output.take(200)}")
                return@launch
            }

            val target = FileNaming.resolveReceiveFile(
                DesktopSettingsHolder.current.receiveDir,
                tmpName
            )
            val (dlOk, dlMsg) = PhoneTools.downloadFileFromClient(client.id, zipPath, target)

            if (dlOk) {
                try {
                    PhoneTools.deleteFileOnClient(client.id, zipPath)
                } catch (_: Exception) { }
            }

            downloadingPath = null
            onInfo(dlMsg)

            if (dlOk) {
                try {
                    val os = System.getProperty("os.name").lowercase()
                    val cmd = when {
                        os.contains("win") -> arrayOf("explorer", "/select,", target.absolutePath)
                        os.contains("mac") -> arrayOf("open", "-R", target.absolutePath)
                        else -> arrayOf("xdg-open", target.parentFile?.absolutePath ?: ".")
                    }
                    ProcessBuilder(*cmd).start()
                } catch (_: Exception) { }
            }
        }
    }

    fun uploadFile() {
        // 改用原生文件对话框
        val chosen = NativeFilePicker.pickFiles(title = "选择要上传的文件")
        if (chosen.isEmpty()) return

        scope.launch {
            var successCount = 0
            var failMsg = ""
            for (f in chosen) {
                if (!f.exists() || !f.isFile) {
                    failMsg = "${f.name} 不是文件"
                    continue
                }
                val (ok, _) = PhoneTools.uploadFileToClient(client.id, f, currentPath)
                if (ok) successCount++ else failMsg = "上传 ${f.name} 失败"
            }
            onInfo(
                if (successCount > 0) "已上传 $successCount 个文件"
                else failMsg.ifBlank { "上传失败" }
            )
            if (successCount > 0) load(currentPath)
        }
    }

    LaunchedEffect(isExpanded, client.id, currentPath) {
        if (isExpanded) {
            load(currentPath)
        }
    }

    Surface(
        color = CardColor(),
        shape = RoundedCornerShape(Radius.MD),
        border = BorderStroke(1.dp, BorderColor()),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = Radius.MD, topEnd = Radius.MD))
                    .clickable { onToggleExpand() }
                    .padding(Spacing.CARD_PADDING)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(AccentSoft()),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isPhone) Icons.Default.Smartphone else Icons.Default.Computer,
                            contentDescription = null,
                            tint = AccentColor(),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                client.deviceName.ifBlank { "未知设备" },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary()
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                client.remote.trimStart('/'),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextSecondary()
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "ID：${client.id}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted(),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible
                        )
                        if (client.mac.isNotBlank()) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "MAC：${client.mac}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextMuted()
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "连接于 ${formatTime(client.connectedAt)}",
                            fontSize = 11.sp,
                            color = TextMuted()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (isExpanded) "点击折叠文件列表"
                            else "点击展开文件列表",
                            fontSize = 11.sp,
                            color = TextSecondary()
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    val rotation by animateFloatAsState(
                        targetValue = if (isExpanded) 180f else 0f,
                        animationSpec = tween(200, easing = FastOutSlowInEasing),
                        label = "deviceCardArrow"
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

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(
                    animationSpec = tween(260, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Top
                ) + fadeIn(tween(200)),
                exit = shrinkVertically(
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                    shrinkTowards = Alignment.Top
                ) + fadeOut(tween(150))
            ) {
                Column(Modifier.fillMaxWidth()) {
                    GlassDivider(color = BorderSubtle())
                    Column(
                        Modifier.padding(Spacing.CARD_PADDING),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(Modifier.weight(1f)) {
                                GlassTextField(
                                    pathDraft,
                                    { input ->
                                        pathDraft = input
                                        pathEditing = true
                                    },
                                    singleLine = true,
                                    monospace = true,
                                    leadingIcon = Icons.Default.Folder
                                )
                            }
                            SecondaryButton(text = "跳转") {
                                val real = toRealRemotePath(pathDraft)
                                pathEditing = false
                                if (real.isBlank()) {
                                    load(currentPath)
                                } else if (real == currentPath) {
                                    pathDraft = displayRemotePath(real, isPhone)
                                    load(currentPath)
                                } else {
                                    currentPath = real
                                }
                            }
                            SecondaryButton(text = "上级") {
                                val normalized = normalizeRemotePathForBackend(currentPath, isPhone)
                                if (isInternalStorageRoot(normalized)) {
                                    onInfo("已在内部存储根目录")
                                } else {
                                    val parent = normalized.trimEnd('/').substringBeforeLast('/', "")
                                    if (parent.isNotBlank()) currentPath = parent
                                }
                            }
                            SecondaryButton(text = "刷新") {
                                if (pathEditing) {
                                    val real = toRealRemotePath(pathDraft)
                                    pathEditing = false
                                    if (real.isNotBlank() && real != currentPath) {
                                        currentPath = real
                                        return@SecondaryButton
                                    }
                                }
                                load(currentPath)
                            }
                            PrimaryButton(
                                text = "上传",
                                icon = Icons.Default.Upload,
                                enabled = downloadingPath == null
                            ) { uploadFile() }
                        }

                        Box(Modifier.width(220.dp)) {
                            GlassTextField(
                                searchText, { searchText = it },
                                singleLine = true, placeholder = "搜索文件名",
                                leadingIcon = Icons.Default.Search
                            )
                        }

                        val filtered = remember(entries, searchText) {
                            if (searchText.isBlank()) entries
                            else entries.filter { it.name.contains(searchText, ignoreCase = true) }
                        }

                        Box(
                            Modifier.fillMaxWidth().height(320.dp)
                                .clip(RoundedCornerShape(Radius.SM))
                                .background(CardStrongColor())
                                .border(1.dp, BorderColor(), RoundedCornerShape(Radius.SM))
                        ) {
                            when {
                                loading -> Box(
                                    Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    LoadingBox(text = "读取中…")
                                }
                                error != null && filtered.isEmpty() -> Column(
                                    Modifier.fillMaxSize().padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = ErrorColor(),
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Text(error!!, fontSize = 12.sp, color = ErrorColor())
                                }
                                filtered.isEmpty() -> Column(
                                    Modifier.fillMaxSize().padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        Icons.Default.FolderOpen,
                                        contentDescription = null,
                                        tint = TextMuted(),
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        if (searchText.isBlank()) "空目录" else "无匹配项",
                                        fontSize = 12.sp,
                                        color = TextSecondary()
                                    )
                                }
                                else -> LazyColumn(
                                    Modifier.fillMaxSize().padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    items(filtered, key = { it.path }) { entry ->
                                        Box(
                                            Modifier.animateItem(
                                                fadeInSpec = tween(180),
                                                placementSpec = tween(220),
                                                fadeOutSpec = tween(140)
                                            )
                                        ) {
                                            val selected = selectedPath == entry.path
                                            val isDownloading = downloadingPath == entry.path

                                            RemoteEntryRow(
                                                entry = entry,
                                                selected = selected,
                                                downloading = isDownloading,
                                                onSelect = {
                                                    selectedPath = if (selected) null else entry.path
                                                },
                                                onDoubleClick = {
                                                    if (entry.isDirectory) {
                                                        currentPath = entry.path
                                                    } else {
                                                        downloadFile(entry.path)
                                                    }
                                                },
                                                onRightClick = {
                                                    selectedPath = entry.path
                                                    contextMenuFor = entry.path
                                                }
                                            )
                                        }

                                        if (contextMenuFor == entry.path) {
                                            var menuOpen by remember { mutableStateOf(true) }
                                            DropdownMenu(
                                                expanded = menuOpen,
                                                onDismissRequest = {
                                                    menuOpen = false
                                                    contextMenuFor = null
                                                },
                                                containerColor = CardColor()
                                            ) {
                                                if (entry.isDirectory) {
                                                    DropdownMenuItem(
                                                        text = { Text("打开", fontSize = 13.sp, color = TextPrimary()) },
                                                        onClick = {
                                                            menuOpen = false
                                                            contextMenuFor = null
                                                            currentPath = entry.path
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text("下载此文件夹", fontSize = 13.sp, color = AccentColor()) },
                                                        onClick = {
                                                            menuOpen = false
                                                            contextMenuFor = null
                                                            downloadFolder(entry.path)
                                                        }
                                                    )
                                                } else {
                                                    DropdownMenuItem(
                                                        text = { Text("下载", fontSize = 13.sp, color = AccentColor()) },
                                                        onClick = {
                                                            menuOpen = false
                                                            contextMenuFor = null
                                                            downloadFile(entry.path)
                                                        }
                                                    )
                                                }
                                                DropdownMenuItem(
                                                    text = { Text("复制路径", fontSize = 13.sp, color = TextPrimary()) },
                                                    onClick = {
                                                        menuOpen = false
                                                        contextMenuFor = null
                                                        SafeClipboard.setText(displayRemotePath(entry.path, isPhone))
                                                        onInfo("已复制：${displayRemotePath(entry.path, isPhone)}")
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("删除", fontSize = 13.sp, color = ErrorColor()) },
                                                    onClick = {
                                                        menuOpen = false
                                                        contextMenuFor = null
                                                        scope.launch {
                                                            val (ok, msg) = PhoneTools.deleteFileOnClient(
                                                                client.id, entry.path
                                                            )
                                                            onInfo(if (ok) "已删除：${entry.name}" else msg)
                                                            if (ok) load(currentPath)
                                                        }
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
    }
}

@Composable
internal fun RemoteEntryRow(
    entry: RemoteEntry,
    selected: Boolean,
    downloading: Boolean,
    onSelect: () -> Unit,
    onDoubleClick: () -> Unit,
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
        label = "remoteEntryBg"
    )

    val lastClickTimes = remember { mutableStateMapOf<String, Long>() }

    Row(
        Modifier.fillMaxWidth()
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
                                onDoubleClick()
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
        if (entry.sizeText.isNotBlank()) {
            Text(
                entry.sizeText,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = TextMuted()
            )
            Spacer(Modifier.width(10.dp))
        }
        if (downloading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = AccentColor()
            )
        }
    }
}

internal fun parseRemoteEntries(raw: String, basePath: String): List<RemoteEntry> {
    val out = mutableListOf<RemoteEntry>()
    var dir = basePath
    raw.lines().forEach { line ->
        val t = line.trim()
        when {
            t.startsWith("目录：") -> {
                dir = t.removePrefix("目录：").trim()
            }
            t.startsWith("[目录]") -> {
                val name = t.removePrefix("[目录]").trim()
                if (name.isNotBlank()) out.add(RemoteEntry(name, joinPath(dir, name), true, ""))
            }
            t.startsWith("[文件]") -> {
                val rest = t.removePrefix("[文件]").trim()
                val m = Regex("""^(.+?)\s*\(([^)]+)\)$""").find(rest)
                val name = m?.groupValues?.get(1)?.trim() ?: rest
                val size = m?.groupValues?.get(2)?.trim() ?: ""
                if (name.isNotBlank()) out.add(RemoteEntry(name, joinPath(dir, name), false, size))
            }
        }
    }
    return out
}

internal fun joinPath(dir: String, name: String): String {
    if (dir.isBlank()) return name
    return if (dir.endsWith("/")) "$dir$name" else "$dir/$name"
}

internal fun getLocalIpAddress(): String? {
    return try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    } catch (_: Exception) { null }
}

internal fun fileIcon(name: String): ImageVector {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic" -> Icons.Default.Image
        "mp4", "mov", "avi", "mkv", "webm" -> Icons.Default.Movie
        "mp3", "wav", "flac", "ogg", "m4a" -> Icons.Default.MusicNote
        "zip", "rar", "7z", "tar", "gz" -> Icons.Default.FolderZip
        "txt", "md", "json", "xml", "kt", "java", "py", "js" -> Icons.Default.Description
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

private fun formatTime(ts: Long): String {
    return DateFormats.formatDateTime(ts)
}
