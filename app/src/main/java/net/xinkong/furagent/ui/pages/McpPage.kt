@file:OptIn(ExperimentalMaterial3Api::class)

package net.xinkong.furagent.ui.pages

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.xinkong.furagent.core.mcp.McpClient
import net.xinkong.furagent.core.mcp.McpManager
import net.xinkong.furagent.data.McpConfigStore
import net.xinkong.furagent.data.McpServerConfig
import net.xinkong.furagent.ui.ApplyBackgroundBlur
import net.xinkong.furagent.ui.GlassSheen
import net.xinkong.furagent.ui.PocoCard
import net.xinkong.furagent.ui.glassPageBg

/**
 * MCP 服务器列表 —— 整屏页面。
 *
 * 【F5】每个 server 展开后，工具列表每项加 Switch。
 * 关闭单个工具会把工具全名（`mcp__<id8>__<toolName>`）加入 `disabledTools`，
 * AI 的 tools 数组会在 build 时被过滤掉该工具。
 *
 * 与「高级设置 → 工具」页写同一个 `disabledTools` 集合，两处状态同步。
 * 单工具禁用对未连接的 server 也有效（禁用状态在 disabledTools 里，
 * 只要 server id 不变，重启/重连后禁用依旧生效）。
 */
@Composable
fun McpPage(
    disabledTools: Set<String>,
    onDisabledToolsChange: (Set<String>) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    var editingServer by remember { mutableStateOf<McpServerConfig?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var expandedId by remember { mutableStateOf<String?>(null) }

    val configs = remember(refreshKey) { McpConfigStore.list(context) }
    val clientList by McpManager.clientList.collectAsState()

    ApplyBackgroundBlur(true)

    if (showAddDialog) {
        McpEditDialog(
            initial = null,
            onDismiss = { showAddDialog = false },
            onSave = { newCfg ->
                McpConfigStore.add(context, newCfg)
                if (newCfg.enabled) McpManager.startServer(newCfg)
                refreshKey++
                showAddDialog = false
            }
        )
    }

    editingServer?.let { server ->
        McpEditDialog(
            initial = server,
            onDismiss = { editingServer = null },
            onSave = { updated ->
                McpConfigStore.update(context, updated)
                McpManager.stopServer(updated.id)
                if (updated.enabled) McpManager.startServer(updated)
                refreshKey++
                editingServer = null
            }
        )
    }

    pendingDeleteId?.let { id ->
        val cfg = configs.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("删除 MCP 服务器") },
            text = { Text("确定删除「${cfg?.name ?: id}」？") },
            confirmButton = {
                TextButton(onClick = {
                    McpManager.stopServer(id)
                    McpConfigStore.delete(context, id)
                    refreshKey++
                    pendingDeleteId = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "MCP 服务器",
                            fontWeight = FontWeight.Black,
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${configs.size} 个配置 · ${clientList.count { it.state.value is McpClient.State.Ready }} 个在线",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                    TextButton(onClick = {
                        McpManager.reload()
                        refreshKey++
                    }) { Text("重载", color = MaterialTheme.colorScheme.primary) }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "添加服务器",
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

            if (configs.isEmpty()) {
                EmptyMcpState(onAdd = { showAddDialog = true })
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(configs, key = { it.id }) { cfg ->
                        val client = clientList.firstOrNull { it.id == cfg.id }
                        McpServerCard(
                            config = cfg,
                            client = client,
                            disabledTools = disabledTools,
                            onToggleTool = { toolName, enabled ->
                                val fullName = McpManager.prefixedToolName(cfg.id, toolName)
                                val newSet = if (enabled) {
                                    disabledTools - fullName
                                } else {
                                    disabledTools + fullName
                                }
                                onDisabledToolsChange(newSet)
                            },
                            expanded = expandedId == cfg.id,
                            onToggleExpand = {
                                expandedId = if (expandedId == cfg.id) null else cfg.id
                            },
                            onToggleEnabled = { enabled ->
                                McpConfigStore.update(context, cfg.copy(enabled = enabled))
                                if (enabled) McpManager.startServer(cfg.copy(enabled = true))
                                else McpManager.stopServer(cfg.id)
                                refreshKey++
                            },
                            onEdit = { editingServer = cfg },
                            onDelete = { pendingDeleteId = cfg.id },
                            onReconnect = {
                                McpManager.stopServer(cfg.id)
                                McpManager.startServer(cfg)
                                refreshKey++
                            }
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

// ============================================================
// 空状态
// ============================================================

@Composable
private fun EmptyMcpState(onAdd: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp)
        ) {
            Icon(
                Icons.Default.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "未配置 MCP 服务器",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "MCP 允许把外部工具（文件系统、数据库等）接入 FurAgent。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Android 端支持 http（Streamable HTTP）与 sse（旧版 HTTP+SSE）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAdd, shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加服务器")
            }
        }
    }
}

// ============================================================
// 服务器卡片（【F5】工具列表加 Switch）
// ============================================================

@Composable
private fun McpServerCard(
    config: McpServerConfig,
    client: McpClient?,
    disabledTools: Set<String>,
    onToggleTool: (String, Boolean) -> Unit,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReconnect: () -> Unit
) {
    val state = client?.state?.collectAsState()?.value
    val tools = client?.tools?.collectAsState()?.value ?: emptyList()

    // 【F5 补充】算出禁用的工具数
    val disabledCount = remember(tools, disabledTools, config.id) {
        tools.count { McpManager.prefixedToolName(config.id, it.name) in disabledTools }
    }

    val (stateText, stateColor) = when {
        !config.enabled -> "已禁用" to MaterialTheme.colorScheme.onSurfaceVariant
        state is McpClient.State.Ready -> "在线 · ${tools.size} 个工具" to Color(0xFF34C759)
        state is McpClient.State.Connecting -> "连接中…" to Color(0xFFF59E0B)
        state is McpClient.State.Error -> "错误：${state.msg}" to MaterialTheme.colorScheme.error
        client == null -> "未启动" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "未连接" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    PocoCard {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .animateContentSize()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Extension, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            config.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                config.transport,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        config.summary(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stateText,
                            fontSize = 11.sp,
                            color = stateColor,
                            maxLines = 1
                        )
                        // 【F5 补充】禁用数红字
                        if (disabledCount > 0 && state is McpClient.State.Ready) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "已禁用 $disabledCount 个",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                }
                IconButton(onClick = onToggleExpand, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = config.enabled,
                        onCheckedChange = onToggleEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = onReconnect,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重连", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onEdit,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("编辑", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onDelete,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(14.dp))
                    }
                }

                if (tools.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(6.dp))

                    // 【F5】单工具启用/禁用开关
                    Text(
                        "工具（关闭后 AI 无法调用）",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)
                    )

                    tools.forEach { t ->
                        val fullName = McpManager.prefixedToolName(config.id, t.name)
                        val enabled = fullName !in disabledTools

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Extension, null,
                                tint = if (enabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                },
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    t.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (enabled) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                    },
                                    maxLines = 1
                                )
                                if (t.description.isNotBlank()) {
                                    Text(
                                        t.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = enabled,
                                onCheckedChange = { onToggleTool(t.name, it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// 编辑对话框（不变）
// ============================================================

@Composable
private fun McpEditDialog(
    initial: McpServerConfig?,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var transport by remember {
        mutableStateOf(initial?.transport?.takeIf { it == "http" || it == "sse" } ?: "http")
    }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var headersText by remember {
        mutableStateOf(
            initial?.headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } ?: ""
        )
    }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(if (initial == null) "添加 MCP 服务器" else "编辑 MCP 服务器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; errorMsg = null },
                    label = { Text("名称") },
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
                    "传输类型",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransportTab("http", transport, Modifier.weight(1f)) { transport = it }
                    TransportTab("sse", transport, Modifier.weight(1f)) { transport = it }
                }

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; errorMsg = null },
                    label = { Text("URL") },
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
                    value = headersText,
                    onValueChange = { headersText = it },
                    label = { Text("Headers（每行 Key: Value）") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
                    minLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "启用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
                Text(
                    when (transport) {
                        "sse" -> "sse：旧版 HTTP+SSE，先 GET 建长连接，响应从流中推送。"
                        else -> "http：Streamable HTTP，POST 请求，支持 JSON 与 SSE 响应、会话保持。"
                    },
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
                if (name.isBlank()) { errorMsg = "名称不能为空"; return@TextButton }
                if (url.isBlank()) { errorMsg = "URL 不能为空"; return@TextButton }

                val headersMap = headersText.lines()
                    .map { it.trim() }
                    .filter { it.contains(":") && !it.startsWith("#") }
                    .associate { line ->
                        val idx = line.indexOf(':')
                        line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                    }

                val cfg = (initial ?: McpServerConfig(name = name)).copy(
                    name = name.trim(),
                    transport = transport,
                    url = url.trim(),
                    headers = headersMap,
                    enabled = enabled
                )
                onSave(cfg)
            }) { Text("保存", color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun TransportTab(
    value: String,
    current: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit
) {
    val selected = current == value
    Surface(
        onClick = { onSelect(value) },
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Box(
            Modifier.padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                value,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}