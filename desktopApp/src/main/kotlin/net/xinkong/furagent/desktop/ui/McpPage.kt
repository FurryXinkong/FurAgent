package net.xinkong.furagent.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.xinkong.furagent.desktop.core.mcp.McpClient
import net.xinkong.furagent.desktop.core.mcp.McpManager
import net.xinkong.furagent.desktop.data.McpConfigStore
import net.xinkong.furagent.desktop.data.McpServerConfig
import net.xinkong.furagent.desktop.ui.theme.Radius
import net.xinkong.furagent.desktop.ui.theme.Spacing
import net.xinkong.furagent.desktop.ui.theme.Shadow

/**
 * MCP 服务器列表 —— 整屏页面。
 *
 * 每个 server 展开后，工具列表每项加 Switch。
 * 关闭单个工具会把工具全名（`mcp__<id8>__<toolName>`）加入 `disabledTools`，
 * AI 的 tools 数组会在 build 时被过滤掉该工具。
 *
 * 与「设置 → 工具」页写同一个 `disabledTools` 集合，两处状态同步。
 */
@Composable
fun McpPage(
    disabledTools: Set<String>,
    onDisabledToolsChange: (Set<String>) -> Unit
) {
    var configs by remember { mutableStateOf(McpConfigStore.list()) }
    var editingServer by remember { mutableStateOf<McpServerConfig?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var expandedId by remember { mutableStateOf<String?>(null) }

    val clientList by McpManager.clientList.collectAsState()

    fun refreshConfigs() {
        configs = McpConfigStore.list()
    }

    Column(Modifier.fillMaxSize()) {
        PageTitleBar(
            title = "MCP 服务器",
            subtitle = "${configs.size} 个配置 · ${clientList.count { it.state.value is McpClient.State.Ready }} 个在线",
            actions = {
                SecondaryButton(text = "重新加载全部", onClick = { McpManager.reload() })
                PrimaryButton(text = "添加服务器", icon = Icons.Default.Add) { showAddDialog = true }
            }
        )

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            if (configs.isEmpty()) {
                EmptyMcpState(onAdd = { showAddDialog = true })
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = Spacing.PAGE_H,
                        vertical = Spacing.PAGE_V,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(configs, key = { it.id }) { cfg ->
                        Box(
                            Modifier.animateItem(
                                fadeInSpec = tween(220),
                                placementSpec = tween(260),
                                fadeOutSpec = tween(160)
                            )
                        ) {
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
                                    McpConfigStore.update(cfg.copy(enabled = enabled))
                                    if (enabled) McpManager.startServer(cfg.copy(enabled = true))
                                    else McpManager.stopServer(cfg.id)
                                    refreshConfigs()
                                },
                                onEdit = { editingServer = cfg },
                                onDelete = { pendingDeleteId = cfg.id },
                                onReconnect = {
                                    McpManager.stopServer(cfg.id)
                                    McpManager.startServer(cfg)
                                    refreshConfigs()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        McpEditDialog(
            initial = null,
            onDismiss = { showAddDialog = false },
            onSave = { newCfg ->
                McpConfigStore.add(newCfg)
                if (newCfg.enabled) McpManager.startServer(newCfg)
                refreshConfigs()
                showAddDialog = false
            }
        )
    }

    editingServer?.let { server ->
        McpEditDialog(
            initial = server,
            onDismiss = { editingServer = null },
            onSave = { updated ->
                McpConfigStore.update(updated)
                McpManager.stopServer(updated.id)
                if (updated.enabled) McpManager.startServer(updated)
                refreshConfigs()
                editingServer = null
            }
        )
    }

    pendingDeleteId?.let { id ->
        val cfg = configs.firstOrNull { it.id == id }
        ConfirmDialog(
            title = "删除 MCP 服务器",
            message = "确定删除「${cfg?.name ?: id}」？",
            confirmText = "删除", danger = true,
            onConfirm = {
                McpManager.stopServer(id)
                McpConfigStore.delete(id)
                refreshConfigs()
                pendingDeleteId = null
            },
            onDismiss = { pendingDeleteId = null }
        )
    }
}

// 空状态

@Composable
private fun EmptyMcpState(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Extension,
            contentDescription = null,
            tint = TextMuted(),
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("未配置 MCP 服务器", fontSize = 15.sp, color = TextSecondary())
        Spacer(Modifier.height(8.dp))
        Text(
            "MCP 允许把外部工具（文件系统、数据库、浏览器等）接入 FurAgent。\n" +
                    "支持 stdio（本地子进程）、http（Streamable HTTP）和 sse（旧版 HTTP+SSE）三种传输方式。",
            fontSize = 12.sp,
            color = TextMuted(),
            lineHeight = 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 60.dp)
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton(text = "添加第一个服务器", icon = Icons.Default.Add) { onAdd() }
    }
}

// 服务器卡片

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
    val state by (client?.state
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow<McpClient.State>(McpClient.State.Disconnected) }).collectAsState()
    val tools by (client?.tools
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList()) }).collectAsState()

    // 【F5 补充】算出禁用的工具数
    val disabledCount = remember(tools, disabledTools, config.id) {
        tools.count { McpManager.prefixedToolName(config.id, it.name) in disabledTools }
    }

    val (stateText, stateColor) = when {
        !config.enabled -> "已禁用" to TextMuted()
        state is McpClient.State.Ready -> "在线 · ${tools.size} 个工具" to ResultColor()
        state is McpClient.State.Connecting -> "连接中…" to WarningColor()
        state is McpClient.State.Error -> "错误：${(state as McpClient.State.Error).msg}" to ErrorColor()
        client == null -> "未启动" to TextMuted()
        else -> "未连接" to TextMuted()
    }

    GlassCard(padding = PaddingValues(Spacing.CARD_PADDING)) {
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = tween(260, easing = FastOutSlowInEasing)
                )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(AccentSoft()),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Extension,
                        contentDescription = null,
                        tint = AccentColor(),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            config.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary()
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(Radius.XS))
                                .background(CardStrongColor())
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                config.transport,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextSecondary()
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        config.summary(),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary(),
                        maxLines = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stateText, fontSize = 11.sp, color = stateColor, maxLines = 1)
                        // 【F5 补充】禁用数红字
                        if (disabledCount > 0 && state is McpClient.State.Ready) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "已禁用 $disabledCount 个",
                                fontSize = 11.sp,
                                color = ErrorColor(),
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                }
                Switch(
                    checked = config.enabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = OnAccent(),
                        checkedTrackColor = AccentColor(),
                        uncheckedThumbColor = TextSecondary(),
                        uncheckedTrackColor = CardStrongColor()
                    )
                )
                IconActionButton(Icons.Default.Refresh, onReconnect, tint = TextSecondary(), size = 28.dp)
                IconActionButton(Icons.Default.Edit, onEdit, tint = TextSecondary(), size = 28.dp)
                IconActionButton(Icons.Default.Delete, onDelete, tint = ErrorColor(), size = 28.dp)
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                    label = "mcpArrow"
                )
                IconActionButton(
                    icon = Icons.Default.ExpandMore,
                    onClick = onToggleExpand,
                    tint = TextSecondary(),
                    size = 28.dp,
                    modifier = Modifier.graphicsLayer { rotationZ = rotation }
                )
            }

            AnimatedVisibility(
                visible = expanded && tools.isNotEmpty(),
                enter = fadeIn(tween(180)) +
                        expandVertically(
                            animationSpec = tween(240, easing = FastOutSlowInEasing),
                            expandFrom = Alignment.Top
                        ),
                exit = fadeOut(tween(140)) +
                        shrinkVertically(
                            animationSpec = tween(200, easing = FastOutSlowInEasing),
                            shrinkTowards = Alignment.Top
                        )
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    GlassDivider(color = BorderSubtle())
                    Spacer(Modifier.height(10.dp))

                    // 单工具启用/禁用
                    Text(
                        "工具（关闭后 AI 无法调用）",
                        fontSize = 11.sp,
                        color = TextMuted(),
                        modifier = Modifier.padding(bottom = 4.dp)
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
                                Icons.Default.Build,
                                contentDescription = null,
                                tint = if (enabled) AccentColor()
                                else TextMuted().copy(alpha = 0.6f),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    t.name,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (enabled) TextPrimary()
                                    else TextSecondary().copy(alpha = 0.75f)
                                )
                                if (t.description.isNotBlank()) {
                                    Text(
                                        t.description,
                                        fontSize = 11.sp,
                                        color = TextMuted(),
                                        maxLines = 2
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = enabled,
                                onCheckedChange = { onToggleTool(t.name, it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = OnAccent(),
                                    checkedTrackColor = AccentColor(),
                                    uncheckedThumbColor = TextSecondary(),
                                    uncheckedTrackColor = CardStrongColor(),
                                    uncheckedBorderColor = BorderColor()
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

// 编辑对话框（不变）

@Composable
private fun McpEditDialog(
    initial: McpServerConfig?,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var transport by remember {
        mutableStateOf(
            initial?.transport?.takeIf { it in listOf("stdio", "http", "sse") } ?: "stdio"
        )
    }
    var command by remember { mutableStateOf(initial?.command ?: "") }
    var argsText by remember { mutableStateOf(initial?.args?.joinToString(" ") ?: "") }
    var envText by remember {
        mutableStateOf(initial?.env?.entries?.joinToString("\n") { "${it.key}=${it.value}" } ?: "")
    }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var headersText by remember {
        mutableStateOf(initial?.headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } ?: "")
    }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    GlassDialog(
        title = if (initial == null) "添加 MCP 服务器" else "编辑 MCP 服务器",
        onDismiss = onDismiss,
        width = 640.dp,
        content = {
            Column(
                Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GlassTextField(
                    name,
                    { name = it; errorMsg = null },
                    label = "名称",
                    placeholder = "例如：filesystem"
                )

                Text("传输类型", fontSize = 12.sp, color = TextSecondary())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransportTab("stdio", transport) { transport = it }
                    TransportTab("http", transport) { transport = it }
                    TransportTab("sse", transport) { transport = it }
                }

                when (transport) {
                    "stdio" -> {
                        GlassTextField(
                            command,
                            { command = it },
                            label = "命令",
                            placeholder = "npx / python / node"
                        )
                        GlassTextField(
                            argsText,
                            { argsText = it },
                            label = "参数（空格分隔，支持引号）",
                            placeholder = "-y @modelcontextprotocol/server-filesystem /tmp",
                            singleLine = false, minLines = 2, maxLines = 3, monospace = true
                        )
                        GlassTextField(
                            envText,
                            { envText = it },
                            label = "环境变量（每行 KEY=VALUE）",
                            placeholder = "API_KEY=xxx",
                            singleLine = false, minLines = 2, maxLines = 4, monospace = true
                        )
                        HintText("示例：\nnpx -y @modelcontextprotocol/server-filesystem /tmp\nuvx mcp-server-sqlite --db-path test.db")
                    }
                    else -> {
                        GlassTextField(
                            url,
                            { url = it },
                            label = "URL",
                            placeholder = "http://localhost:3000/mcp"
                        )
                        GlassTextField(
                            headersText,
                            { headersText = it },
                            label = "Headers（每行 Key: Value）",
                            placeholder = "Authorization: Bearer xxx",
                            singleLine = false, minLines = 2, maxLines = 4, monospace = true
                        )
                        HintText(
                            if (transport == "sse")
                                "sse：旧版 HTTP+SSE。先 GET 建立 SSE 长连接，服务器通过该流推送所有响应。"
                            else
                                "http：Streamable HTTP。POST 请求，支持 JSON 与 SSE 响应，自动保持会话（Mcp-Session-Id）。"
                        )
                    }
                }

                GlassSwitchRow("启用", null, enabled) { enabled = it }

                errorMsg?.let { Text(it, color = ErrorColor(), fontSize = 12.sp) }
            }
        },
        actions = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary()) }
            TextButton(onClick = {
                if (name.isBlank()) { errorMsg = "名称不能为空"; return@TextButton }
                if (transport == "stdio" && command.isBlank()) { errorMsg = "请填写命令"; return@TextButton }
                if (transport != "stdio" && url.isBlank()) { errorMsg = "请填写 URL"; return@TextButton }

                val argsList = parseArgsLine(argsText)
                val envMap = envText.lines()
                    .map { it.trim() }
                    .filter { it.contains("=") && !it.startsWith("#") }
                    .associate { line ->
                        val idx = line.indexOf('=')
                        line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                    }
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
                    command = command.trim(),
                    args = argsList,
                    env = envMap,
                    url = url.trim(),
                    headers = headersMap,
                    enabled = enabled
                )
                onSave(cfg)
            }) { Text("保存", color = AccentColor(), fontWeight = FontWeight.SemiBold) }
        }
    )
}

private fun parseArgsLine(cmd: String): List<String> =
    net.xinkong.furagent.shared.util.CommandLine.parse(cmd)

@Composable
private fun RowScope.TransportTab(
    value: String, current: String, onSelect: (String) -> Unit
) {
    val selected = current == value
    val bg by animateColorAsState(
        if (selected) AccentColor() else CardColor(),
        tween(140), label = "transportTabBg"
    )
    val fg by animateColorAsState(
        if (selected) OnAccent() else TextPrimary(),
        tween(140), label = "transportTabFg"
    )
    Box(
        Modifier.weight(1f).clip(RoundedCornerShape(Radius.SM))
            .background(bg)
            .border(
                1.dp,
                if (selected) Color.Transparent else BorderColor(),
                RoundedCornerShape(Radius.SM)
            )
            .clickable { onSelect(value) }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            value,
            fontSize = 12.sp,
            color = fg,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}