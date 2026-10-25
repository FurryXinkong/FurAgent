package net.xinkong.furagent.ui
import net.xinkong.furagent.AppPage
import net.xinkong.furagent.ui.theme.AppRadius

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.xinkong.furagent.data.ChatMessage
import net.xinkong.furagent.data.TokenStats
import net.xinkong.furagent.shared.util.SessionAnalytics
import net.xinkong.furagent.system.notifyToast

/**
 * 聊天页的独立 UI 组件。
 *
 * 与 FurAgentMainScreen 这个 2000 行的上帝函数混在一起，难以定位和维护。
 * 它们本身只依赖参数和全局单例，与主屏状态无耦合，故抽到本文件。
 */

// 更多功能面板项

// 【UI 统一】图标容器圆角保留 18dp（面板内专用尺寸）。
// 高亮态走 primary，常态走 surfaceVariant。

@Composable
fun MoreActionItem(
    icon: ImageVector,
    label: String,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "moreActionScale"
    )
    val alpha = if (enabled) 1f else 0.4f

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.MD))
            .scale(scale)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .graphicsLayer { this.alpha = alpha },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            color = when {
                highlighted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            },
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.size(52.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = if (highlighted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// 侧边抽屉内容

// 【UI 统一】抽屉背景走 glassSheetBg（ModalDrawerSheet 传入）。
// 项颜色走 colorScheme。

@Composable
fun SideDrawerContent(
    progress: Float,
    currentProfileName: String,
    /** 当前所在页：用于抽屉项高亮，聊天页传 null */
    currentPage: AppPage? = null,
    floatingOn: Boolean,
    onNewChat: () -> Unit,
    onOpenPcHub: () -> Unit,
    onOpenSchedules: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenSkill: () -> Unit = {},
    onOpenMemory: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onToggleFloating: () -> Unit,
    onOpenAbout: () -> Unit,
    // 【手机端排版】从顶栏挪进来的入口
    onOpenSubList: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenLog: () -> Unit = {},
    onOpenSessions: () -> Unit = {},
    // 【P1-1】会话专用提示词入口
    onOpenSessionPrompt: () -> Unit = {}
) {
    val density = LocalDensity.current
    val offsetPx = with(density) { 28.dp.toPx() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .graphicsLayer {
                val p = progress.coerceIn(0f, 1f)
                alpha = 0.35f + 0.65f * p
                translationX = -offsetPx * (1f - p)
            }
    ) {
        Spacer(Modifier.height(28.dp))
        Text(
            "FurAgent",
            fontWeight = FontWeight.ExtraBold,
            fontSize = 20.sp,
            letterSpacing = (-0.5).sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
        Spacer(Modifier.height(20.dp))
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(AppRadius.MD_MINUS),
            modifier = Modifier.fillMaxWidth().clickable { onNewChat() }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Add, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "新对话", color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        // 【滚动区】导航项可滚动：矮屏上也不会把底部版本信息挤掉
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(4.dp))
            DrawerSectionLabel("对话")
            DrawerItem(
                Icons.Default.History, "对话历史", onOpenHistory,
                selected = currentPage == AppPage.HISTORY
            )
            DrawerItem(
                Icons.AutoMirrored.Filled.List, "会话列表", onOpenSessions,
                selected = currentPage == AppPage.SESSIONS
            )
            // 【P1-1】会话专用提示词：只作用于当前会话的额外指令
            DrawerItem(
                Icons.Default.Edit, "会话提示词", onOpenSessionPrompt
            )
            DrawerItem(
                Icons.Default.Schedule, "定时任务", onOpenSchedules,
                selected = currentPage == AppPage.SCHEDULES
            )

            DrawerSectionLabel("能力")
            DrawerItem(
                Icons.Default.Devices, "电脑互联", onOpenPcHub,
                selected = currentPage == AppPage.PC_HUB
            )
            DrawerItem(
                Icons.Default.Extension, "MCP 服务器", onOpenMcp,
                selected = currentPage == AppPage.MCP
            )
            DrawerItem(
                Icons.Default.Code, "自定义脚本", onOpenSkill,
                selected = currentPage == AppPage.SKILL
            )
            DrawerItem(
                Icons.Default.Psychology, "长期记忆", onOpenMemory,
                selected = currentPage == AppPage.MEMORY
            )
            DrawerItem(Icons.Default.Share, "子代理", onOpenSubList)

            DrawerSectionLabel("系统")
            DrawerItem(
                Icons.Default.Tune, "高级设置", onOpenAdvancedSettings,
                selected = currentPage == AppPage.ADVANCED_SETTINGS
            )
            DrawerItem(
                Icons.Default.Terminal, "日志", onOpenLog,
                selected = currentPage == AppPage.LOG
            )
            DrawerItem(
                Icons.Default.BubbleChart,
                if (floatingOn) "关闭悬浮助手" else "开启悬浮助手",
                onToggleFloating
            )
            Spacer(Modifier.height(6.dp))
        }

        DrawerItem(Icons.Default.Favorite, "关于我们", onOpenAbout)
        Spacer(Modifier.height(20.dp))
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp)
        ) {
            // 【精简】原来堆了 6 行（当前模型 / Token / 版本），收成 2 行
            Text(
                currentProfileName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Token ≈ ${TokenStats.total}  ·  v1.0",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

/** 抽屉分组标题 */
@Composable
private fun DrawerSectionLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 2.dp)
    )
}

@Composable
fun DrawerItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    /** 选中态：当前所在页，图标与文字用主色，底色轻微高亮 */
    selected: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 500f),
        label = "drawerItemScale"
    )
    val bg = when {
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        pressed -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        else -> Color.Transparent
    }
    val iconTint = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    val textColor = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurface
    Surface(
        color = bg,
        shape = RoundedCornerShape(AppRadius.SM),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .scale(scale)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon, contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(14.dp))
            Text(
                label,
                fontSize = 15.sp,
                color = textColor,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

// 消息删除辅助

/**
 * 删除一条消息（user 消息会连带删除其后的整轮 AI 回复）。
 */
fun deleteMessagePair(history: List<ChatMessage>, index: Int): List<ChatMessage> {
    val msg = history.getOrNull(index) ?: return history
    val newList = history.toMutableList()
    if (msg.role == "user" && msg.type == "text") {
        var end = index + 1
        while (end < newList.size) {
            val m = newList[end]
            if (m.role == "user" && m.type == "text") break
            end++
        }
        for (i in (end - 1) downTo index) newList.removeAt(i)
    } else {
        newList.removeAt(index)
    }
    return newList
}

// 任务完成结果卡：本次会话编辑过的文件

@Composable
fun AndroidModifiedFilesCard(
    files: List<SessionAnalytics.ModifiedFile>,
    /** 路径点击分发（MainActivity 按扩展名路由到内置浏览器/阅读器/执行器）。缺省退回系统打开。 */
    onOpenPath: ((String) -> Unit)? = null
) {
    //   1) 默认展开路径列表（不再需要先点一下才看到文件）
    //   2) 去掉「∨ / >」冗余符号（用户明确讨厌）
    //   3) 去掉横滑文件方块（占地方且与下方列表重复）
    //   4) 路径行可点击，直接打开文件
    var showPaths by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val totalSize = remember(files) { SessionAnalytics.totalSizeBytes(files) }
    val sizeStr = when {
        totalSize >= 1024L * 1024 -> "%.1f MB".format(totalSize / 1024.0 / 1024)
        totalSize >= 1024 -> "%.1f KB".format(totalSize / 1024.0)
        else -> "$totalSize B"
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(AppRadius.SM),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            // 头部：{N} 个文件 · 总大小
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showPaths = !showPaths },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${files.size} 个文件",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                Text(
                    sizeStr,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 展开：完整路径列表（含动作与次数）——限高内部滚动，文件多时可翻页
            if (showPaths) {
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    files.forEach { f ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AppRadius.MICRO))
                                .clickable {
                                    if (onOpenPath != null) onOpenPath(f.path)
                                    else openLocalFileOnAndroid(context, f.path)
                                }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                f.path,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            val actionLabel = when (f.lastAction) {
                                "write" -> "写入"
                                "edit" -> "编辑"
                                "delete" -> "删除"
                                "move" -> "移动"
                                "other" -> "操作"
                                else -> f.lastAction
                            }
                            Text(
                                "$actionLabel ×${f.count}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 安卓端打开本地文件。
 *
 * 用 FileProvider 拿 content:// URI 再交给系统选择器，
 * 避免 Android 7+ 的 FileUriExposedException。
 */
fun openLocalFileOnAndroid(context: android.content.Context, path: String) {
    try {
        val f = java.io.File(path)
        if (!f.exists()) {
            notifyToast(context, "文件不存在：${f.name}")
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            f
        )
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        net.xinkong.furagent.core.AgentLogger.log(
            "UI",
            "打开文件失败（$path）：${e.javaClass.simpleName}: ${e.message}"
        )
        notifyToast(context, "无法打开：${e.message}")
    }
}

// 底部状态栏：轮次/步数/token/上下文

@Composable
fun AndroidTokenStatusBar(messages: List<ChatMessage>) {
    val stats = remember(messages) { SessionAnalytics.sessionStats(messages) }
    // 真实开销：按 Agent 每轮实际请求重放（输入 = 每轮整段历史之和）
    val cost = remember(messages) { SessionAnalytics.costStats(messages) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${stats.rounds} 轮 · ${stats.toolSteps} 步 · ${cost.requests} 次请求 · 开销 ~${fmtTok(cost.totalTokens)} tok（输入 ${fmtTok(cost.promptTokens)} / 输出 ${fmtTok(cost.completionTokens)}）",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        )
    }
}

/** token 数展示：万以下直出，以上用 k / M 缩写。 */
private fun fmtTok(v: Long): String = when {
    v >= 1_000_000 -> "%.2fM".format(v / 1_000_000.0)
    v >= 10_000 -> "%.1fk".format(v / 1000.0)
    else -> v.toString()
}
