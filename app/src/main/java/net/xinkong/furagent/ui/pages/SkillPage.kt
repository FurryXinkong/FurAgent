@file:OptIn(ExperimentalMaterial3Api::class)

package net.xinkong.furagent.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.xinkong.furagent.shared.icons.Icons
import net.xinkong.furagent.shared.skill.BrokenSkill
import net.xinkong.furagent.shared.skill.SkillConfig
import net.xinkong.furagent.shared.skill.SkillMeta
import net.xinkong.furagent.shared.skill.SkillStore
import net.xinkong.furagent.system.notifyToast
import net.xinkong.furagent.ui.*
import net.xinkong.furagent.ui.theme.AppRadius

/**
 * 自定义脚本 —— 整屏页面（安卓端）。
 *
 * 与桌面端 SkillPage 功能一致：
 *   - 列出 scripts/ 下所有 .py 脚本（元数据来自文件头部 NAME/DESCRIPTION/PARAMS/CONFIG）
 *   - 每个脚本可启用/禁用（写 scripts/<name>.config.json）
 *   - 可编辑配置项（API Key 等，敏感项打码）
 *   - 可试运行（填参数直接跑，看返回）
 *   - 可删除、可创建示例脚本、可清理无效脚本
 *
 * 脚本格式与桌面端完全一致，同一份 .py 两端通用。
 */
@Composable
fun SkillPage(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 用 Composable 作用域的 scope，避免每次执行脚本新建 MainScope() 泄漏
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var skills by remember { mutableStateOf<List<SkillMeta>>(emptyList()) }
    var broken by remember { mutableStateOf<List<BrokenSkill>>(emptyList()) }
    var configs by remember { mutableStateOf<Map<String, SkillConfig>>(emptyMap()) }

    var runningName by remember { mutableStateOf<String?>(null) }
    var runResult by remember { mutableStateOf<String?>(null) }
    var configEditing by remember { mutableStateOf<SkillMeta?>(null) }
    var runEditing by remember { mutableStateOf<SkillMeta?>(null) }
    var deleteTarget by remember { mutableStateOf<SkillMeta?>(null) }
    // 软超时警告：安卓端 Python 是进程内执行，超时只返回失败，
    // 线程还在后台跑。这里给用户一个持续警告 banner。
    var backgroundWarning by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey) {
        loading = true
        val loaded = withContext(Dispatchers.IO) {
            try { SkillStore.list() } catch (_: Throwable) { emptyList() }
        }
        val brokenLoaded = withContext(Dispatchers.IO) {
            try { SkillStore.listBroken() } catch (_: Throwable) { emptyList() }
        }
        val cfgLoaded = withContext(Dispatchers.IO) {
            loaded.associate { it.name to SkillStore.loadConfig(it.name) }
        }
        skills = loaded
        broken = brokenLoaded
        configs = cfgLoaded
        loading = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(glassPageBg())
    ) {
        Column(Modifier.fillMaxSize()) {
            PocoPageTopBar(
                title = "自定义脚本",
                subtitle = "共 ${skills.size} 个 · 与桌面端通用",
                onBack = onBack,
                actions = {
                    IconButton(onClick = {
                        // 创建示例脚本
                        val err = SkillStore.createExample()
                        notifyToast(context, err ?: "已创建示例脚本 example_hello")
                        refreshKey++
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "创建示例")
                    }
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )

            // 软超时警告 banner
            backgroundWarning?.let { warn ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(AppRadius.SM),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            warn,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                            lineHeight = 18.sp
                        )
                        IconButton(
                            onClick = { backgroundWarning = null },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "关闭警告",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (skills.isEmpty() && broken.isEmpty()) {
                PocoEmptyState(
                    icon = Icons.Default.Extension,
                    title = "还没有自定义脚本",
                    subtitle = "脚本放在 App 私有目录 scripts/ 下，一个 .py 一个脚本。\n" +
                            "点右上角 + 创建示例脚本，或让 AI 用 pytool 工具创建。",
                    action = {
                        Button(onClick = {
                            val err = SkillStore.createExample()
                            notifyToast(context, err ?: "已创建示例脚本 example_hello")
                            refreshKey++
                        }) { Text("创建示例脚本") }
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(skills, key = { it.name }) { meta ->
                        Box(Modifier.animateItem()) {
                            SkillCard(
                                meta = meta,
                                config = configs[meta.name] ?: SkillConfig(),
                                running = runningName == meta.name,
                                onToggleEnabled = { en ->
                                    val updated = (configs[meta.name] ?: SkillConfig()).copy(enabled = en)
                                    configs = configs + (meta.name to updated)
                                    SkillStore.saveConfig(meta.name, updated)
                                },
                                onConfig = { configEditing = meta },
                                onRun = { runEditing = meta },
                                onDelete = { deleteTarget = meta }
                            )
                        }
                    }
                    if (broken.isNotEmpty()) {
                        item {
                            Text(
                                "无效脚本（${broken.size}）",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                            )
                        }
                        items(broken, key = { "broken_" + it.fileName }) { b ->
                            Box(Modifier.animateItem()) {
                                PocoCard {
                                    Row(
                                        Modifier.fillMaxWidth().padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.ErrorOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(b.fileName, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                            Text(
                                                b.reason,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        TextButton(onClick = {
                                            SkillStore.deleteBroken(b.fileName)
                                            refreshKey++
                                        }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 配置弹窗
    configEditing?.let { meta ->
        SkillConfigDialog(
            meta = meta,
            config = configs[meta.name] ?: SkillConfig(),
            onDismiss = { configEditing = null },
            onSaved = { cfg ->
                configs = configs + (meta.name to cfg)
                SkillStore.saveConfig(meta.name, cfg)
                configEditing = null
                refreshKey++
            }
        )
    }

    // 试运行弹窗
    runEditing?.let { meta ->
        SkillRunDialog(
            meta = meta,
            onDismiss = { runEditing = null; runResult = null },
            onRun = { values ->
                runningName = meta.name
                runResult = null
                // 后台执行
                scope.launch {
                    val args = buildArgs(meta, values)
                    val cfg = SkillStore.loadConfig(meta.name)
                    val timeout = SkillStore.effectiveTimeout(meta, cfg)
                    val r = withContext(Dispatchers.IO) {
                        try {
                            net.xinkong.furagent.shared.skill.SkillTools.doRunByName(meta.name, args)
                        } catch (e: Throwable) {
                            net.xinkong.furagent.shared.tools.ToolResult.fail(
                                "执行异常：${e.javaClass.simpleName}: ${e.message}"
                            )
                        }
                    }
                    runResult = r.output
                    runningName = null
                    // 软超时提示：安卓端是进程内执行，超时只返回失败，
                    // 线程还在后台跑。给一个持续警告，避免用户误以为已终止。
                    if (!r.success && r.output.contains("后台继续运行")) {
                        backgroundWarning =
                            "脚本「${meta.name}」执行超时，Python 线程可能仍在后台运行。\n" +
                            "请确认脚本没有死循环；如已卡死，重启 App 可释放。"
                    }
                }
            },
            running = runningName == meta.name,
            result = runResult
        )
    }

    // 删除确认
    deleteTarget?.let { meta ->
        PocoAlertDialog(
            onDismiss = { deleteTarget = null },
            title = "删除脚本",
            text = { Text("确定删除「${meta.name}」？脚本文件和配置会一起删除，不可恢复。") },
            confirmText = "删除",
            confirmColor = MaterialTheme.colorScheme.error,
            onConfirm = {
                SkillStore.delete(meta.name)
                deleteTarget = null
                refreshKey++
            }
        )
    }
}

@Composable
private fun SkillCard(
    meta: SkillMeta,
    config: SkillConfig,
    running: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onConfig: () -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit
) {
    PocoCard {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        meta.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "工具名 pytool_${meta.name} · 超时 ${SkillStore.effectiveTimeout(meta, config)}s",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.enabled,
                    onCheckedChange = onToggleEnabled
                )
            }

            if (meta.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    meta.description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (meta.params.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "参数：" + meta.params.entries.joinToString(", ") { (k, p) ->
                        k + if (p.required) "(必填)" else ""
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRun,
                    enabled = !running,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    if (running) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (running) "执行中" else "试运行", fontSize = 12.sp)
                }
                if (meta.configSpec.isNotEmpty()) {
                    OutlinedButton(
                        onClick = onConfig,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) { Text("配置", fontSize = 12.sp) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onDelete,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text("删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun SkillConfigDialog(
    meta: SkillMeta,
    config: SkillConfig,
    onDismiss: () -> Unit,
    onSaved: (SkillConfig) -> Unit
) {
    var values by remember {
        mutableStateOf(
            meta.configSpec.keys.associateWith { k ->
                (config.values[k] ?: meta.configSpec[k]?.default)?.toString() ?: ""
            }
        )
    }
    var timeoutText by remember { mutableStateOf(config.timeout?.toString() ?: "") }

    PocoAlertDialog(
        onDismiss = onDismiss,
        title = "配置 ${meta.name}",
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "这些值存在 ${SkillStore.configFile(meta.name).name}，运行时自动传给脚本。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                meta.configSpec.forEach { (k, spec) ->
                    // 原来用 k + if(...) + if(...) 链，因 if 表达式优先级低于 +，
                    // secret=true 时后面 description 丢失（label 只有 "k（敏感）"）。
                    val labelText = buildString {
                        append(k)
                        if (spec.secret) append("（敏感）")
                        if (spec.description.isNotBlank()) {
                            append(" · ").append(spec.description)
                        }
                    }
                    PocoTextField(
                        value = values[k] ?: "",
                        onValueChange = { values = values + (k to it) },
                        label = labelText
                    )
                    Spacer(Modifier.height(8.dp))
                }
                PocoTextField(
                    value = timeoutText,
                    onValueChange = { timeoutText = it },
                    label = "超时秒数（留空用脚本默认 ${meta.timeout}s）"
                )
            }
        },
        confirmText = "保存",
        onConfirm = {
            val newValues = mutableMapOf<String, Any?>()
            meta.configSpec.forEach { (k, spec) ->
                val raw = values[k]?.trim().orEmpty()
                if (raw.isNotEmpty()) {
                    newValues[k] = when (spec.type) {
                        "integer" -> raw.toLongOrNull() ?: raw
                        "number" -> raw.toDoubleOrNull() ?: raw
                        "boolean" -> raw.equals("true", ignoreCase = true)
                        else -> raw
                    }
                }
            }
            onSaved(
                config.copy(
                    values = newValues,
                    timeout = timeoutText.trim().toIntOrNull()?.takeIf { it > 0 }
                )
            )
        }
    )
}

@Composable
private fun SkillRunDialog(
    meta: SkillMeta,
    onDismiss: () -> Unit,
    onRun: (Map<String, String>) -> Unit,
    running: Boolean,
    result: String?
) {
    var values by remember { mutableStateOf(meta.params.keys.associateWith { "" }) }

    PocoAlertDialog(
        onDismiss = onDismiss,
        title = "试运行 ${meta.name}",
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (meta.params.isEmpty()) {
                    Text("该脚本没有参数，直接运行即可。", fontSize = 12.sp)
                } else {
                    meta.params.forEach { (k, p) ->
                        // 同上，required=true 时 description 丢失
                        val labelText = buildString {
                            append(k)
                            if (p.required) append("（必填）")
                            if (p.description.isNotBlank()) {
                                append(" · ").append(p.description)
                            }
                        }
                        PocoTextField(
                            value = values[k] ?: "",
                            onValueChange = { values = values + (k to it) },
                            label = labelText
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (result != null) {
                    Spacer(Modifier.height(12.dp))
                    Text("执行结果：", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(AppRadius.XS))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(8.dp)
                    ) {
                        Text(
                            result,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmText = if (running) "执行中…" else "运行",
        confirmEnabled = !running,
        onConfirm = { onRun(values) }
    )
}

/** 把界面上的字符串按声明的类型转成 JSON。 */
private fun buildArgs(meta: SkillMeta, values: Map<String, String>): org.json.JSONObject {
    val o = org.json.JSONObject()
    meta.params.forEach { (k, p) ->
        val raw = values[k]?.trim().orEmpty()
        when (p.type) {
            "integer" -> {
                val n = raw.toLongOrNull()
                if (n != null) o.put(k, n) else if (raw.isNotEmpty()) o.put(k, raw)
            }
            "number" -> {
                val n = raw.toDoubleOrNull()
                if (n != null) o.put(k, n) else if (raw.isNotEmpty()) o.put(k, raw)
            }
            "boolean" -> o.put(k, raw.equals("true", ignoreCase = true))
            else -> o.put(k, raw)
        }
    }
    return o
}
