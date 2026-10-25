package net.xinkong.furagent.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.xinkong.furagent.desktop.data.DesktopSettings
import net.xinkong.furagent.desktop.system.EnvDetector
import net.xinkong.furagent.desktop.system.NativeFilePicker
import java.io.File
import net.xinkong.furagent.desktop.ui.theme.Radius
import kotlin.time.Duration.Companion.milliseconds

/**
 * 环境检测组件（从 SettingsPage.kt 拆分）。
 *
 * 包含 EnvDetectSection / EnvToolRow。
 */
// 环境检测

@Composable
fun EnvDetectSection(
    settings: DesktopSettings,
    onSettingsChange: (DesktopSettings) -> Unit,
    onInfo: (String) -> Unit
) {
    var refreshKey by remember { mutableIntStateOf(0) }
    var statuses by remember { mutableStateOf<List<EnvDetector.ToolStatus>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var installingKey by remember { mutableStateOf<String?>(null) }
    var installOutput by remember { mutableStateOf("") }
    var installingDialog by remember { mutableStateOf<String?>(null) }
    var pendingClearToolKey by remember { mutableStateOf<String?>(null) }

    var visibleCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        loading = true
        statuses = EnvDetector.detectAll()
        loading = false
    }

    LaunchedEffect(statuses.size, refreshKey) {
        visibleCount = 0
        for (i in statuses.indices) {
            delay(30.milliseconds)
            visibleCount = i + 1
        }
    }

    val detectedCount = statuses.count { it.detected }

    HintText("检测本机已安装的常用开发工具。已安装的工具会告知 AI，可直接在命令中使用。")
    HintText("点\"选择路径\"可手动指定可执行文件位置；手动路径优先于自动探测。")

    if (loading) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                Modifier.size(14.dp), strokeWidth = 2.dp, color = AccentColor()
            )
            Spacer(Modifier.width(10.dp))
            Text("正在检测…", fontSize = 12.sp, color = TextSecondary())
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "已检测到 $detectedCount / ${statuses.size} 个工具",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary(),
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    EnvDetector.clearCache()
                    refreshKey++
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text("重新检测", fontSize = 11.sp, color = AccentColor())
            }
        }
        GlassDivider(color = BorderSubtle())
        statuses.forEachIndexed { i, s ->
            AnimatedVisibility(
                visible = i < visibleCount,
                enter = fadeIn(tween(180)) +
                        expandVertically(
                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                            expandFrom = Alignment.Top
                        ),
                exit = fadeOut(tween(120)) +
                        shrinkVertically(tween(160))
            ) {
                Column {
                    EnvToolRow(
                        status = s,
                        customPath = settings.customToolPaths[s.key] ?: "",
                        onInstall = {
                            installingKey = s.key
                            installOutput = ""
                            installingDialog = s.key
                        },
                        onPickPath = {
                            // 改用原生文件对话框
                            val f = NativeFilePicker.pickFile(
                                title = "选择 ${s.displayName} 可执行文件"
                            ) ?: return@EnvToolRow
                            if (!f.exists() || !f.isFile) {
                                onInfo("所选文件不存在或不是文件：${f.absolutePath}")
                                return@EnvToolRow
                            }
                            if (!f.canExecute()) {
                                onInfo("所选文件没有执行权限：${f.absolutePath}")
                                return@EnvToolRow
                            }
                            val newMap = settings.customToolPaths.toMutableMap()
                            newMap[s.key] = f.absolutePath
                            onSettingsChange(settings.copy(customToolPaths = newMap))
                            onInfo("已设置 ${s.displayName} 路径：${f.absolutePath}")
                        },
                        onClearPath = { pendingClearToolKey = s.key },
                        onUseDetected = {
                            if (s.path.isBlank()) {
                                onInfo("未检测到 ${s.displayName} 的可执行文件路径，无法填入")
                                return@EnvToolRow
                            }
                            val newMap = settings.customToolPaths.toMutableMap()
                            newMap[s.key] = s.path
                            onSettingsChange(settings.copy(customToolPaths = newMap))
                            onInfo("已填入 ${s.displayName} 路径：${s.path}")
                        }
                    )
                    if (i != statuses.lastIndex) GlassDivider(color = BorderSubtle())
                }
            }
        }
    }

    installingDialog?.let { key ->
        val def = EnvDetector.TOOLS.firstOrNull { it.key == key }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { if (installingKey == null) installingDialog = null },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false
            )
        ) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = CardColor(),
                    shape = RoundedCornerShape(Radius.MD),
                    border = BorderStroke(1.dp, BorderColor()),
                    shadowElevation = 20.dp,
                    modifier = Modifier.width(800.dp).heightIn(min = 400.dp, max = 700.dp)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            "正在安装：${def?.displayName ?: key}",
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            def?.let { EnvDetector.currentInstallCommand(it) } ?: "",
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextMuted()
                        )
                        Spacer(Modifier.height(12.dp))
                        GlassDivider(color = BorderSubtle())
                        Spacer(Modifier.height(12.dp))
                        Box(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 250.dp, max = 500.dp)
                                .clip(RoundedCornerShape(Radius.XS))
                                .background(BgColor())
                                .padding(10.dp)
                        ) {
                            val vScroll = rememberScrollState()
                            LaunchedEffect(installOutput) {
                                vScroll.animateScrollTo(vScroll.maxValue)
                            }
                            Text(
                                installOutput.ifBlank { "等待输出…" },
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                color = TextPrimary(), lineHeight = 15.sp,
                                modifier = Modifier.verticalScroll(vScroll)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (installingKey != null) {
                                Text(
                                    "安装中，请不要关闭窗口…",
                                    fontSize = 11.sp, color = WarningColor(),
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            SecondaryButton(
                                text = "关闭",
                                enabled = installingKey == null
                            ) {
                                installingDialog = null
                                EnvDetector.clearCache()
                                refreshKey++
                            }
                        }
                    }
                }
            }
        }

        LaunchedEffect(installingDialog) {
            if (installingKey == key) {
                val (ok, _) = EnvDetector.install(key) { line ->
                    installOutput = (installOutput + line + "\n").takeLast(20000)
                }
                installOutput += if (ok) "\n安装完成" else "\n安装失败"
                installingKey = null
            }
        }
    }

    pendingClearToolKey?.let { key ->
        val def = EnvDetector.TOOLS.firstOrNull { it.key == key }
        ConfirmDialog(
            title = "清除手动路径",
            message = "确定清除 ${def?.displayName ?: key} 的手动路径？清除后将回退到自动探测。",
            confirmText = "清除",
            onConfirm = {
                val newMap = settings.customToolPaths.toMutableMap()
                newMap.remove(key)
                onSettingsChange(settings.copy(customToolPaths = newMap))
                pendingClearToolKey = null
            },
            onDismiss = { pendingClearToolKey = null }
        )
    }
}

@Composable
fun EnvToolRow(
    status: EnvDetector.ToolStatus,
    customPath: String,
    onInstall: () -> Unit,
    onPickPath: () -> Unit,
    onClearPath: () -> Unit,
    onUseDetected: () -> Unit
) {
    val hasCustom = customPath.isNotBlank()
    val customValid = remember(customPath) {
        if (customPath.isBlank()) false
        else {
            val f = File(customPath)
            f.exists() && f.isFile && f.canExecute()
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dotColor = when {
                hasCustom && customValid -> AccentColor()
                hasCustom && !customValid -> ErrorColor()
                status.detected -> ResultColor()
                else -> TextMuted()
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        status.displayName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary()
                    )
                    if (hasCustom) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(Radius.XS))
                                .background(
                                    if (customValid) AccentSoft()
                                    else ErrorColor().copy(alpha = 0.15f)
                                )
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(
                                if (customValid) "已自定义" else "自定义路径无效",
                                fontSize = 9.sp,
                                color = if (customValid) AccentColor() else ErrorColor(),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        if (hasCustom) {
                            append(customPath)
                            if (!customValid) append("  （无效）")
                        } else if (status.detected) {
                            append(status.version.ifBlank { status.path })
                        } else {
                            append(status.description)
                        }
                    },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (hasCustom && !customValid) ErrorColor()
                    else if (status.detected || hasCustom) TextSecondary()
                    else TextMuted(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SecondaryButton(text = "选择路径", onClick = onPickPath)
            if (hasCustom) {
                DangerButton(text = "清除", onClick = onClearPath)
            } else if (status.detected && status.path.isNotBlank()) {
                SecondaryButton(text = "使用检测到的路径", onClick = onUseDetected)
            }
            if (!status.detected) {
                SecondaryButton(text = "一键安装", onClick = onInstall)
            }
        }
    }
}
