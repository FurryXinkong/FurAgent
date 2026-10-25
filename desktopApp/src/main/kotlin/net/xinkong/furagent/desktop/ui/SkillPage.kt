package net.xinkong.furagent.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.desktop.data.*
import net.xinkong.furagent.desktop.ui.theme.Radius
import net.xinkong.furagent.desktop.ui.theme.Spacing

/**
 * 自定义脚本 —— 整屏页面。
 *
 * 【新格式 2026-10-15】脚本直接放 scripts/ 根目录，一个 .py 一个脚本，
 * 元数据写在文件头部（NAME / DESCRIPTION / PARAMS / TIMEOUT / CONFIG）。
 * 每个脚本会注册成一个独立工具 pytool_<name>，AI 可直接调用。
 *
 * 【配置 2026-10-18】每个脚本的开关 / 超时 / 配置项存在
 * scripts/<name>.config.json，不再写 settings.json 的 disabledTools。
 * 卡片上的「配置」按钮可以编辑这些值。
 */
@Composable
fun SkillPage(
    disabledTools: Set<String>,
    onDisabledToolsChange: (Set<String>) -> Unit
) {
    var refreshKey by remember { mutableIntStateOf(0) }
    var searchText by remember { mutableStateOf("") }
    // SkillStore.list() 会 spawn Python 子进程做 AST 解析，
    // 同步在 remember 里执行会卡 UI 线程（点击导航即卡一下）→ 改为异步加载。
    var skills by remember { mutableStateOf<List<SkillMeta>>(emptyList()) }
    var broken by remember { mutableStateOf<List<BrokenSkill>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // 【配置】脚本名 → 配置（异步加载，避免主线程读盘）
    var configs by remember { mutableStateOf<Map<String, SkillConfig>>(emptyMap()) }
    LaunchedEffect(refreshKey) {
        loading = true
        val loaded = withContext(Dispatchers.IO) { SkillStore.list() }
        val brokenLoaded = withContext(Dispatchers.IO) { SkillStore.listBroken() }
        val cfgLoaded = withContext(Dispatchers.IO) {
            loaded.associate { it.name to SkillStore.loadConfig(it.name) }
        }
        skills = loaded
        broken = brokenLoaded
        configs = cfgLoaded
        loading = false
    }
    // 从示例创建脚本
    fun createExample() {
        SkillRunBus.createExample { refreshKey++ }
    }
    val rootDir = remember { DesktopPaths.scriptsDir() }
    val running by SkillRunBus.running.collectAsState()

    val filtered = remember(skills, searchText) {
        if (searchText.isBlank()) skills
        else skills.filter {
            it.name.contains(searchText, ignoreCase = true) ||
                    it.description.contains(searchText, ignoreCase = true)
        }
    }

    val enabledCount = skills.count { configs[it.name]?.enabled ?: true }

    Column(Modifier.fillMaxSize()) {
        PageTitleBar(
            title = "自定义脚本",
            subtitle = "共 ${skills.size} 个 · $enabledCount 个启用 · 让 AI 调用你写的 Python 脚本",
            actions = {
                SecondaryButton(
                    text = "从示例创建",
                    icon = Icons.Default.ContentCopy
                ) { createExample() }
                SecondaryButton(
                    text = "打开脚本目录",
                    icon = Icons.Default.FolderOpen
                ) { SkillRunBus.openDir(rootDir.absolutePath) }
                SecondaryButton(
                    text = "重新扫描",
                    icon = Icons.Default.Refresh
                ) { refreshKey++ }
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = Spacing.PAGE_H,
                vertical = Spacing.PAGE_V,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (skills.isNotEmpty()) {
                item {
                    GlassTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        placeholder = "搜索脚本名或说明",
                        leadingIcon = Icons.Default.Search,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            when {
                loading -> item {
                    Box(
                        Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.5.dp,
                            color = AccentColor()
                        )
                    }
                }

                skills.isEmpty() -> item {
                    EmptySkillState { refreshKey++ }
                }

                filtered.isEmpty() -> item {
                    Box(
                        Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "没有匹配「$searchText」的脚本",
                            fontSize = 13.sp,
                            color = TextMuted()
                        )
                    }
                }

                else -> items(filtered, key = { it.name }) { meta ->
                    SkillCard(
                        meta = meta,
                        runningName = running,
                        config = configs[meta.name] ?: SkillConfig(),
                        onToggleEnabled = { en ->
                            // 【配置】开关写脚本自己的 config.json，不再动 disabledTools
                            configs = configs + (meta.name to (configs[meta.name] ?: SkillConfig()).copy(enabled = en))
                            SkillRunBus.setEnabled(meta.name, en) { err ->
                                if (err != null) refreshKey++
                            }
                        },
                        onConfigSaved = { newCfg ->
                            configs = configs + (meta.name to newCfg)
                        },
                        onDeleted = { refreshKey++ }
                    )
                }
            }

            if (broken.isNotEmpty()) {
                item {
                    BrokenSection(broken = broken, onRefresh = { refreshKey++ })
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// 无效目录区块

@Composable
private fun BrokenSection(broken: List<BrokenSkill>, onRefresh: () -> Unit) {
    var confirmClearAll by remember { mutableStateOf(false) }

    GlassCard(padding = PaddingValues(Spacing.CARD_PADDING)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = WarningColor(),
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${broken.size} 个脚本无法加载",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary(),
                modifier = Modifier.weight(1f)
            )
            SecondaryButton(text = "全部清理") { confirmClearAll = true }
        }
        Spacer(Modifier.height(4.dp))
        HintText("这些脚本有 Python 语法错误或无法解析，AI 无法使用。")
        Spacer(Modifier.height(12.dp))

        broken.forEachIndexed { idx, b ->
            BrokenRow(b = b, onDeleted = onRefresh)
            if (idx != broken.lastIndex) Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmClearAll) {
        ConfirmDialog(
            title = "清理全部无效脚本",
            message = "将删除以下 ${broken.size} 个脚本文件，无法恢复：\n\n" +
                    broken.joinToString("\n") { "· ${it.fileName}（${it.reason}）" },
            confirmText = "全部删除",
            danger = true,
            onConfirm = {
                confirmClearAll = false
                SkillRunBus.deleteBrokenAll(broken.map { it.fileName }) { onRefresh() }
            },
            onDismiss = { confirmClearAll = false }
        )
    }
}

@Composable
private fun BrokenRow(b: BrokenSkill, onDeleted: () -> Unit) {
    var pending by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Radius.SM))
            .background(CardStrongColor())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                b.fileName,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = TextPrimary()
            )
            Spacer(Modifier.height(2.dp))
            Text(b.reason, fontSize = 11.sp, color = WarningColor())
        }
        Spacer(Modifier.width(10.dp))
        DangerButton(text = "清理") { pending = true }
    }

    if (pending) {
        ConfirmDialog(
            title = "清理无效脚本",
            message = "将删除：\n\n${b.file.absolutePath}\n\n原因：${b.reason}",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                pending = false
                SkillRunBus.deleteBroken(b.fileName) { err -> if (err == null) onDeleted() }
            },
            onDismiss = { pending = false }
        )
    }
}

// 空状态

@Composable
private fun EmptySkillState(onRefresh: () -> Unit) {
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
        Text(
            "还没有自定义脚本",
            fontSize = 15.sp,
            color = TextSecondary()
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "跟 AI 说「做个脚本，以后都用这个流程」就能创建",
            fontSize = 12.sp,
            color = TextMuted()
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "或直接把 .py 放进脚本目录（头部写 NAME / DESCRIPTION / PARAMS）",
            fontSize = 12.sp,
            color = TextMuted()
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton(text = "已放好了，重新扫描", icon = Icons.Default.Refresh) { onRefresh() }
    }
}

// 单个脚本卡片

@Composable
private fun SkillCard(
    meta: SkillMeta,
    runningName: String?,
    config: SkillConfig,
    onToggleEnabled: (Boolean) -> Unit,
    onConfigSaved: (SkillConfig) -> Unit,
    onDeleted: () -> Unit
) {
    var showInfo by remember { mutableStateOf(false) }
    var showRun by remember { mutableStateOf(false) }
    var showConfig by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf(false) }

    val enabled = config.enabled
    val isRunning = runningName == meta.name

    GlassCard(padding = PaddingValues(Spacing.CARD_PADDING)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(36.dp)
                    .clip(RoundedCornerShape(Radius.SM))
                    .background(if (enabled) AccentSoft() else CardStrongColor()),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Extension,
                    contentDescription = null,
                    tint = if (enabled) AccentColor() else TextMuted(),
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        meta.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (enabled) TextPrimary() else TextSecondary()
                    )
                    if (isRunning) {
                        Spacer(Modifier.width(8.dp))
                        Text("执行中", fontSize = 11.sp, color = AccentColor())
                    }
                    if (!enabled) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(Radius.XS))
                                .background(CardStrongColor())
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(
                                "已禁用",
                                fontSize = 9.sp,
                                color = TextMuted(),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    if (meta.params.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text("${meta.params.size} 个参数", fontSize = 11.sp, color = TextMuted())
                    }
                    if (meta.configSpec.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text("${meta.configSpec.size} 个配置项", fontSize = 11.sp, color = TextMuted())
                    }
                }
                if (meta.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        meta.description,
                        fontSize = 12.sp,
                        color = if (enabled) TextSecondary() else TextMuted()
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "${meta.file.name} · 超时 ${SkillStore.effectiveTimeout(meta, config)}s",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextMuted()
                )
            }

            Spacer(Modifier.width(12.dp))

            Switch(
                checked = enabled,
                onCheckedChange = onToggleEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = OnAccent(),
                    checkedTrackColor = AccentColor(),
                    uncheckedThumbColor = TextSecondary(),
                    uncheckedTrackColor = CardStrongColor(),
                    uncheckedBorderColor = BorderColor()
                )
            )

            Spacer(Modifier.width(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(text = "详情") { showInfo = true }
                SecondaryButton(text = "配置") { showConfig = true }
                SecondaryButton(
                    text = "试运行",
                    icon = Icons.Default.PlayArrow,
                    enabled = !isRunning
                ) { showRun = true }
                DangerButton(text = "删除", enabled = !isRunning) { pendingDelete = true }
            }
        }
    }

    if (showInfo) {
        InfoDialog(
            title = meta.name,
            message = buildSkillInfo(meta, config),
            onDismiss = { showInfo = false }
        )
    }

    if (showConfig) {
        SkillConfigDialog(
            meta = meta,
            config = config,
            onDismiss = { showConfig = false },
            onSaved = { newCfg ->
                showConfig = false
                onConfigSaved(newCfg)
            }
        )
    }

    if (showRun) {
        SkillRunDialog(
            meta = meta,
            requireConfirm = DesktopSettingsHolder.current.requireConfirmSkill,
            onDismiss = { showRun = false }
        )
    }

    if (pendingDelete) {
        ConfirmDialog(
            title = "删除脚本",
            message = "将删除脚本文件，无法恢复：\n\n" +
                    "${meta.file.absolutePath}\n\n" +
                    "脚本名：${meta.name}",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                pendingDelete = false
                SkillRunBus.delete(meta.name) { err ->
                    if (err == null) onDeleted()
                }
            },
            onDismiss = { pendingDelete = false }
        )
    }
}

private fun buildSkillInfo(meta: SkillMeta, config: SkillConfig): String {
    val sb = StringBuilder()
    sb.append("说明：").append(meta.description.ifBlank { "(无)" }).append("\n")
    sb.append("文件：").append(meta.file.absolutePath).append("\n")
    sb.append("配置文件：").append(SkillStore.configFile(meta.name).absolutePath).append("\n")
    sb.append("工具名：").append(SkillStore.TOOL_PREFIX).append(meta.name).append("\n")
    sb.append("启用：").append(if (config.enabled) "是" else "否").append("\n")
    sb.append("超时：").append(SkillStore.effectiveTimeout(meta, config)).append(" 秒\n")
    if (meta.params.isEmpty()) {
        sb.append("参数：无")
    } else {
        sb.append("参数：\n")
        meta.params.forEach { (k, p) ->
            sb.append("  · ").append(k)
            sb.append("（").append(p.type)
            if (p.required) sb.append("，必填")
            sb.append("）")
            if (p.description.isNotBlank()) sb.append("：").append(p.description)
            sb.append("\n")
        }
    }
    return sb.toString().trim()
}

// 配置弹窗

/**
 * 脚本配置编辑弹窗。
 *
 * 按脚本头部 CONFIG 声明渲染表单：
 *   - boolean → Switch
 *   - 其它 → 文本框（integer/number 提示数字）
 *   - secret:true → 密码框（打码显示，留空表示不修改）
 * 另外可以改超时（留空 = 用脚本默认）。
 */
@Composable
private fun SkillConfigDialog(
    meta: SkillMeta,
    config: SkillConfig,
    onDismiss: () -> Unit,
    onSaved: (SkillConfig) -> Unit
) {
    // 初始值：声明里的项 + 配置里多出来的项
    val keys = remember(meta.name) {
        LinkedHashSet<String>().apply {
            addAll(meta.configSpec.keys)
            addAll(config.values.keys)
        }.toList()
    }
    val values = remember(meta.name) {
        mutableStateMapOf<String, String>().apply {
            keys.forEach { k ->
                val spec = meta.configSpec[k]
                val v = config.valueOf(k, meta.configSpec)
                put(
                    k,
                    when {
                        spec?.secret == true -> ""          // 敏感项不回显，留空=不改
                        v == null -> ""
                        else -> v.toString()
                    }
                )
            }
        }
    }
    var timeoutText by remember(meta.name) {
        mutableStateOf(config.timeout?.toString() ?: "")
    }
    var saving by remember { mutableStateOf(false) }

    GlassDialog(
        title = "配置 ${meta.name}",
        onDismiss = onDismiss,
        width = 520.dp,
        content = {
            Column(Modifier.fillMaxWidth()) {
                HintText("这些值存在 ${SkillStore.configFile(meta.name).name}，运行时自动传给脚本。")
                Spacer(Modifier.height(16.dp))

                if (keys.isEmpty()) {
                    Text(
                        "该脚本没有声明配置项（头部没有 CONFIG）。",
                        fontSize = 13.sp,
                        color = TextSecondary()
                    )
                } else {
                    keys.forEach { k ->
                        val spec = meta.configSpec[k]
                        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    k,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextPrimary()
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    (spec?.type ?: "string") + if (spec?.secret == true) " · 敏感" else "",
                                    fontSize = 11.sp,
                                    color = TextMuted()
                                )
                            }
                            if (spec != null && spec.description.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(spec.description, fontSize = 11.sp, color = TextSecondary())
                            }
                            Spacer(Modifier.height(6.dp))
                            if (spec?.type == "boolean") {
                                val cur = values[k] == "true"
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(
                                        checked = cur,
                                        onCheckedChange = { values[k] = it.toString() },
                                        colors = glassSwitchColors()
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (cur) "true" else "false",
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = TextSecondary()
                                    )
                                }
                            } else {
                                GlassTextField(
                                    value = values[k] ?: "",
                                    placeholder = when {
                                        spec?.secret == true -> "留空表示不修改"
                                        spec?.type == "integer" || spec?.type == "number" -> "数字"
                                        else -> ""
                                    },
                                    monospace = spec?.secret == true,
                                    onValueChange = { values[k] = it }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                GlassTextField(
                    value = timeoutText,
                    label = "超时（秒，留空用脚本默认 ${meta.timeout}s）",
                    placeholder = meta.timeout.toString(),
                    onValueChange = { timeoutText = it }
                )
            }
        },
        actions = {
            SecondaryButton(text = "取消", onClick = onDismiss)
            PrimaryButton(text = "保存", loading = saving) {
                // 组装新配置：敏感项留空则保留原值
                val newValues = LinkedHashMap<String, Any?>()
                keys.forEach { k ->
                    val spec = meta.configSpec[k]
                    val raw = values[k]?.trim().orEmpty()
                    if (spec?.secret == true && raw.isEmpty()) {
                        // 保留原值
                        config.values[k]?.let { newValues[k] = it }
                        return@forEach
                    }
                    if (raw.isEmpty()) return@forEach
                    newValues[k] = when (spec?.type) {
                        "integer" -> raw.toLongOrNull() ?: raw
                        "number" -> raw.toDoubleOrNull() ?: raw
                        "boolean" -> raw.equals("true", ignoreCase = true)
                        else -> raw
                    }
                }
                val newTimeout = timeoutText.trim().toIntOrNull()?.coerceIn(1, 3600)
                val newCfg = config.copy(timeout = newTimeout, values = newValues)
                saving = true
                SkillRunBus.saveConfig(meta.name, newCfg) { err ->
                    saving = false
                    if (err == null) onSaved(newCfg)
                }
            }
        }
    )
}

// 试运行弹窗

@Composable
private fun SkillRunDialog(
    meta: SkillMeta,
    requireConfirm: Boolean,
    onDismiss: () -> Unit
) {
    val values = remember {
        mutableStateMapOf<String, String>().apply {
            meta.params.forEach { (k, p) ->
                put(k, if (p.type == "boolean") "true" else "")
            }
        }
    }
    var confirming by remember { mutableStateOf(false) }

    GlassDialog(
        title = "试运行 ${meta.name}",
        onDismiss = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                HintText("填写参数后执行。执行会真实运行脚本，请确认脚本行为可控。")
                Spacer(Modifier.height(16.dp))

                if (meta.params.isEmpty()) {
                    Text("该脚本无需参数", fontSize = 13.sp, color = TextSecondary())
                } else {
                    meta.params.forEach { (k, p) ->
                        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    k,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextPrimary()
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    p.type + if (p.required) " · 必填" else "",
                                    fontSize = 11.sp,
                                    color = TextMuted()
                                )
                            }
                            if (p.description.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(p.description, fontSize = 11.sp, color = TextSecondary())
                            }
                            Spacer(Modifier.height(6.dp))
                            if (p.type == "boolean") {
                                val cur = values[k] == "true"
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(
                                        checked = cur,
                                        onCheckedChange = { values[k] = it.toString() },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = OnAccent(),
                                            checkedTrackColor = AccentColor(),
                                            uncheckedThumbColor = TextSecondary(),
                                            uncheckedTrackColor = CardStrongColor()
                                        )
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (cur) "true" else "false",
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = TextSecondary()
                                    )
                                }
                            } else {
                                GlassTextField(
                                    value = values[k] ?: "",
                                    placeholder = if (p.type == "string") "" else "数字",
                                    onValueChange = { values[k] = it }
                                )
                            }
                        }
                    }
                }
            }
        },
        actions = {
            SecondaryButton(text = "取消", onClick = onDismiss)
            PrimaryButton(text = "执行", icon = Icons.Default.PlayArrow) {
                // 二次确认受 requireConfirmSkill 控制
                if (requireConfirm) {
                    confirming = true
                } else {
                    onDismiss()
                    SkillRunBus.run(meta, values.toMap())
                }
            }
        }
    )

    if (confirming) {
        ConfirmDialog(
            title = "确认执行脚本",
            message = "将运行：${meta.file.absolutePath}\n\n" +
                    if (meta.params.isEmpty()) "无参数"
                    else meta.params.keys.joinToString("\n") { k -> "$k = ${values[k].orEmpty()}" },
            confirmText = "执行",
            danger = true,
            onConfirm = {
                confirming = false
                onDismiss()
                SkillRunBus.run(meta, values.toMap())
            },
            onDismiss = { confirming = false }
        )
    }
}