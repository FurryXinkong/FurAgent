package net.xinkong.furagent.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import net.xinkong.furagent.desktop.data.*
import net.xinkong.furagent.desktop.ui.theme.Radius
import net.xinkong.furagent.desktop.ui.theme.Spacing

/**
 * 自定义脚本 —— 整屏页面。
 *
 * 【新格式 2026-10-15】脚本直接放 scripts/ 根目录，一个 .py 一个脚本，
 * 元数据写在文件头部（NAME / DESCRIPTION / PARAMS / TIMEOUT）。
 * 每个脚本会注册成一个独立工具 pytool_<name>，AI 可直接调用。
 */
@Composable
fun SkillPage(
    disabledTools: Set<String>,
    onDisabledToolsChange: (Set<String>) -> Unit
) {
    var refreshKey by remember { mutableIntStateOf(0) }
    var searchText by remember { mutableStateOf("") }
    val skills = remember(refreshKey) { SkillStore.list() }
    // 【B21】从示例创建脚本
    fun createExample() {
        SkillRunBus.createExample { refreshKey++ }
    }
    val broken = remember(refreshKey) { SkillStore.listBroken() }
    val rootDir = remember { DesktopPaths.scriptsDir() }
    val running by SkillRunBus.running.collectAsState()

    val filtered = remember(skills, searchText) {
        if (searchText.isBlank()) skills
        else skills.filter {
            it.name.contains(searchText, ignoreCase = true) ||
                    it.description.contains(searchText, ignoreCase = true)
        }
    }

    val enabledCount = skills.count { (SkillStore.TOOL_PREFIX + it.name) !in disabledTools }

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
                        enabled = (SkillStore.TOOL_PREFIX + meta.name) !in disabledTools,
                        onToggleEnabled = { en ->
                            val toolName = SkillStore.TOOL_PREFIX + meta.name
                            val newSet = if (en) disabledTools - toolName else disabledTools + toolName
                            onDisabledToolsChange(newSet)
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

// ============================================================
// 无效目录区块
// ============================================================

@Composable
private fun BrokenSection(broken: List<BrokenSkill>, onRefresh: () -> Unit) {
    var confirmClearAll by remember { mutableStateOf(false) }

    GlassCard(padding = PaddingValues(16.dp)) {
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

// ============================================================
// 空状态
// ============================================================

@Composable
private fun EmptySkillState(onRefresh: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(240.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                fontWeight = FontWeight.Medium,
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
                fontSize = 11.sp,
                color = TextMuted()
            )
            Spacer(Modifier.height(20.dp))
            SecondaryButton(text = "已放好了，重新扫描", icon = Icons.Default.Refresh) { onRefresh() }
        }
    }
}

// ============================================================
// 单个脚本卡片
// ============================================================

@Composable
private fun SkillCard(
    meta: SkillMeta,
    runningName: String?,
    enabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onDeleted: () -> Unit
) {
    var showInfo by remember { mutableStateOf(false) }
    var showRun by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf(false) }

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
                    "${meta.file.name} · 超时 ${meta.timeout}s",
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
            message = buildSkillInfo(meta),
            onDismiss = { showInfo = false }
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

private fun buildSkillInfo(meta: SkillMeta): String {
    val sb = StringBuilder()
    sb.append("说明：").append(meta.description.ifBlank { "(无)" }).append("\n")
    sb.append("文件：").append(meta.file.absolutePath).append("\n")
    sb.append("工具名：").append(SkillStore.TOOL_PREFIX).append(meta.name).append("\n")
    sb.append("超时：").append(meta.timeout).append(" 秒\n")
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

// ============================================================
// 试运行弹窗
// ============================================================

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