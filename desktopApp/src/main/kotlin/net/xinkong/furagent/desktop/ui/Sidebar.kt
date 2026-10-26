package net.xinkong.furagent.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.xinkong.furagent.desktop.data.DesktopSessionMeta
import net.xinkong.furagent.desktop.ui.theme.Anim
import net.xinkong.furagent.desktop.ui.theme.Radius

// 页面枚举

// 顺序决定侧边栏导航显示顺序（MEMORY 到 LOGS 是主功能区，SETTINGS 固定底部）。
// CHAT 是主界面，不在侧边栏展示，但保留枚举值作为路由标识。

enum class AppPage(val label: String) {
    CHAT("聊天"),
    MEMORY("长期记忆"),
    HISTORY("修改历史"),
    SCHEDULES("定时任务"),
    MCP("MCP"),
    SKILLS("自定义脚本"),
    STATUS("状态面板"),
    LOGS("日志"),
    SETTINGS("设置")
}

/** 侧边栏所有动画统一时长，避免各部件不同步 */
private val SIDEBAR_ANIM_SPEC = tween<androidx.compose.ui.unit.Dp>(Anim.SLOW_MS)

/** 侧边栏项统一圆角 */
private val ITEM_SHAPE = RoundedCornerShape(Radius.SM)

// 侧边栏

// 色板约定
//   SidebarBg()          —— 侧边栏整体底色（半透明，透出背景渐变）
//   SidebarItemActive()  —— 选中项底色
//   SidebarItemHover()   —— hover 项底色
//   AccentColor()        —— 选中项图标 / 文字
//   TextPrimary()        —— 未选中项文字
//   TextSecondary()      —— 未选中项图标
//   CardColor()          —— 下拉菜单容器色

// 圆角约定
//   所有侧边栏项统一 10dp，靠两侧 padding 14~12dp 留白，视觉对齐。

@Composable
fun Sidebar(
    expanded: Boolean,
    currentPage: AppPage,
    sessions: List<DesktopSessionMeta>,
    currentSessionId: String?,
    runningSessions: Set<String> = emptySet(),
    onToggleExpand: () -> Unit,
    onNavigate: (AppPage) -> Unit,
    onNewChat: () -> Unit,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String) -> Unit,
    onExportSession: (String) -> Unit,
    onDuplicateSession: (String) -> Unit,
    onImportSession: () -> Unit,
    onClearAllSessions: () -> Unit,
    sortMode: Int = 0,
    onSortModeChange: (Int) -> Unit = {}
) {
    val width by animateDpAsState(
        targetValue = if (expanded) 240.dp else 60.dp,
        animationSpec = SIDEBAR_ANIM_SPEC,
        label = "sidebarWidth"
    )
    val horizontalPadding by animateDpAsState(
        targetValue = if (expanded) 10.dp else 6.dp,
        animationSpec = SIDEBAR_ANIM_SPEC,
        label = "sidebarPadding"
    )

    Column(
        Modifier
            .fillMaxHeight()
            .width(width)
            .background(SidebarBg())
            .padding(horizontal = horizontalPadding, vertical = 10.dp)
    ) {
        // ============ 顶部：折叠按钮 + 品牌名 ============
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SidebarToggleButton(expanded = expanded, onClick = onToggleExpand)
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(Anim.SLOW_MS)) +
                        expandHorizontally(tween(Anim.SLOW_MS)),
                exit = fadeOut(tween(Anim.SLOW_MS / 2)) +
                        shrinkHorizontally(tween(Anim.SLOW_MS))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "FurAgent",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary(),
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        NewChatButton(expanded = expanded, onClick = onNewChat)
        Spacer(Modifier.height(10.dp))

        // ============ 主导航 ============
        NavItem(Icons.Default.Memory, AppPage.MEMORY, currentPage, expanded, onNavigate)
        Spacer(Modifier.height(1.dp))
        NavItem(Icons.Default.Schedule, AppPage.SCHEDULES, currentPage, expanded, onNavigate)
        Spacer(Modifier.height(1.dp))
        NavItem(Icons.Default.Extension, AppPage.MCP, currentPage, expanded, onNavigate)
        Spacer(Modifier.height(1.dp))
        NavItem(Icons.Default.Terminal, AppPage.SKILLS, currentPage, expanded, onNavigate)
        Spacer(Modifier.height(1.dp))
        NavItem(Icons.Default.Dashboard, AppPage.STATUS, currentPage, expanded, onNavigate)
        Spacer(Modifier.height(1.dp))
        NavItem(Icons.AutoMirrored.Filled.List, AppPage.LOGS, currentPage, expanded, onNavigate)

        // ============ 中间剩余空间：历史区 ============
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(Anim.SLOW_MS)),
                exit = fadeOut(tween(Anim.SLOW_MS)),
                modifier = Modifier.fillMaxSize()
            ) {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(12.dp))
                    HistorySectionHeader(
                        onNewChat = onNewChat,
                        onImportSession = onImportSession,
                        onClearAllSessions = onClearAllSessions,
                        sortMode = sortMode,
                        onSortModeChange = onSortModeChange
                    )
                    Spacer(Modifier.height(4.dp))

                    if (sessions.isEmpty()) {
                        Text(
                            "还没有会话",
                            fontSize = 12.sp,
                            color = TextMuted(),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            items(
                                sessions,
                                key = { it.id }
                            ) { s ->
                                Box(
                                    Modifier.animateItem(
                                        fadeInSpec = tween(Anim.NORMAL_MS),
                                        placementSpec = tween(Anim.NORMAL_MS + 40),
                                        fadeOutSpec = tween(Anim.QUICK_MS + 20)
                                    )
                                ) {
                                    SessionItem(
                                        session = s,
                                        selected = s.id == currentSessionId && currentPage == AppPage.CHAT,
                                        running = s.id in runningSessions,
                                        onClick = {
                                            if (currentPage != AppPage.CHAT) onNavigate(AppPage.CHAT)
                                            onSelectSession(s.id)
                                        },
                                        onRename = { onRenameSession(s.id) },
                                        onDelete = { onDeleteSession(s.id) },
                                        onExport = { onExportSession(s.id) },
                                        onDuplicate = { onDuplicateSession(s.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ============ 底部：设置按钮（永远固定在这里） ============
        Spacer(Modifier.height(8.dp))
        NavItem(Icons.Default.Settings, AppPage.SETTINGS, currentPage, expanded, onNavigate)
    }
}

// 历史区标题 + 更多操作

@Composable
private fun HistorySectionHeader(
    onNewChat: () -> Unit,
    onImportSession: () -> Unit,
    onClearAllSessions: () -> Unit,
    sortMode: Int = 0,
    onSortModeChange: (Int) -> Unit = {}
) {
    var menuOpen by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "历史对话",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextMuted(),
            letterSpacing = 0.5.sp,
            modifier = Modifier.weight(1f)
        )
        Box {
            HoverTip("更多操作") {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(Radius.TINY))
                        .background(if (hovered) CardHoverColor() else Color.Transparent)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = { menuOpen = true }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多操作",
                        tint = TextSecondary(),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = CardColor()
            ) {
                DropdownMenuItem(
                    text = { Text("新建会话", fontSize = 12.sp, color = TextPrimary()) },
                    onClick = { menuOpen = false; onNewChat() }
                )
                DropdownMenuItem(
                    text = { Text("导入会话 JSON…", fontSize = 12.sp, color = TextPrimary()) },
                    onClick = { menuOpen = false; onImportSession() }
                )
                val sortLabels = listOf("最新更新", "最旧优先", "名称 A-Z", "消息最多")
                DropdownMenuItem(
                    text = { Text("排序：${sortLabels[sortMode]}", fontSize = 12.sp, color = TextPrimary()) },
                    onClick = { menuOpen = false; onSortModeChange((sortMode + 1) % 4) }
                )
                DropdownMenuItem(
                    text = { Text("清空全部会话…", fontSize = 12.sp, color = ErrorColor()) },
                    onClick = { menuOpen = false; onClearAllSessions() }
                )
            }
        }
    }
}

// 新建对话按钮

@Composable
private fun NewChatButton(expanded: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (hovered) AccentHover() else AccentColor(),
        animationSpec = tween(Anim.QUICK_MS),
        label = "newChatBg"
    )

    // 动画修复 原来 horizontal padding 直接用 if 切换、horizontalArrangement 也用
    // if 切换，两者都在布尔变化时"跳变"。同时 AnimatedVisibility 里的文字在展开，
    // 三者不同步 → 图标先跳、文字才跟上，视觉上乱跳。

    // 现在：Arrangement 固定 Start，用 animateDpAsState 平滑动画图标位置。
    // 展开：start = 14.dp（内容左侧留白）
    // 折叠：start = 21.dp（让图标在 60dp 宽 - 6dp*2 侧边留白 = 48dp 里居中：
    //                    (48 - 18) / 2 = 15，加上侧边栏外层的 6，实际给 start 15）
    val startPadding by animateDpAsState(
        targetValue = if (expanded) 14.dp else 15.dp,
        animationSpec = tween(Anim.SLOW_MS, easing = Anim.Easing),
        label = "newChatStart"
    )

    Row(
        Modifier
            .fillMaxWidth()
            .clip(ITEM_SHAPE)
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(start = startPadding, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Icon(
            Icons.Default.AddComment,
            null,
            tint = OnAccent(),
            modifier = Modifier.size(18.dp)
        )
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(Anim.SLOW_MS)) +
                    expandHorizontally(tween(Anim.SLOW_MS)),
            exit = fadeOut(tween(Anim.SLOW_MS / 2)) +
                    shrinkHorizontally(tween(Anim.SLOW_MS))
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "新建对话",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = OnAccent(),
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

// 主导航项

@Composable
private fun NavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    page: AppPage, current: AppPage, expanded: Boolean,
    onNavigate: (AppPage) -> Unit
) {
    val selected = current == page
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val bg by animateColorAsState(
        targetValue = when {
            selected -> SidebarItemActive()
            hovered -> SidebarItemHover()
            else -> Color.Transparent
        },
        animationSpec = tween(Anim.QUICK_MS),
        label = "navBg"
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) AccentColor() else TextSecondary(),
        animationSpec = tween(Anim.QUICK_MS),
        label = "navIconTint"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) AccentColor() else TextPrimary(),
        animationSpec = tween(Anim.QUICK_MS),
        label = "navTextColor"
    )

    // 动画修复 同 NewChatButton：图标位置用动画，Arrangement 固定。
    // 展开：start = 12.dp
    // 折叠：start = 15.dp（48dp 可用宽度里让 18dp 图标居中）
    val startPadding by animateDpAsState(
        targetValue = if (expanded) 12.dp else 15.dp,
        animationSpec = tween(Anim.SLOW_MS, easing = Anim.Easing),
        label = "navItemStart"
    )

    Row(
        Modifier
            .fillMaxWidth()
            .clip(ITEM_SHAPE)
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = null
            ) { onNavigate(page) }
            .padding(start = startPadding, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        if (!expanded) {
            HoverTip(page.label) {
                Icon(
                    icon,
                    contentDescription = page.label,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            Icon(
                icon,
                contentDescription = page.label,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(Anim.SLOW_MS)) +
                    expandHorizontally(tween(Anim.SLOW_MS)),
            exit = fadeOut(tween(Anim.SLOW_MS / 2)) +
                    shrinkHorizontally(tween(Anim.SLOW_MS))
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(12.dp))
                Text(
                    page.label,
                    fontSize = 13.sp,
                    color = textColor,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

// 会话项

// 右键（PointerEventPass.Initial + consume）打开菜单，抢在 clickable 之前。

@Composable
private fun SessionItem(
    session: DesktopSessionMeta,
    selected: Boolean,
    running: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onDuplicate: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    // 动画清理 选中/悬停颜色直接切换，不再做渐变动画
    val bg = when {
        selected -> SidebarItemActive()
        hovered -> SidebarItemHover()
        else -> Color.Transparent
    }
    val fg = if (selected) AccentColor() else TextPrimary()

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(ITEM_SHAPE)
                .background(bg)
                // 右键打开菜单：用 PointerEventPass.Initial 抢在 clickable 之前拦截
                .pointerInput(session.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                menuOpen = true
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick
                )
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 多会话并发 运行中的会话在标题前显示一个脉冲小圆点。
            if (running) {
                val pulse by rememberInfiniteTransition(label = "sessionRunning").animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(700, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "sessionRunningAlpha"
                )
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(AccentColor().copy(alpha = pulse))
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                session.title,
                fontSize = 12.sp,
                color = fg,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(4.dp))
            // hover 移动修复 菜单按钮常驻占位：标题宽度恒定，鼠标悬停不再
            // 插入/移除按钮导致整行文字重排位移。悬停只改变按钮存在感（纯视觉）。
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(Radius.TINY))
                    .clickable { menuOpen = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    null,
                    tint = TextSecondary().copy(
                        alpha = if (hovered || selected || menuOpen) 1f else 0.4f
                    ),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = CardColor()
        ) {
            DropdownMenuItem(
                text = { Text("重命名", fontSize = 12.sp) },
                onClick = { menuOpen = false; onRename() }
            )
            DropdownMenuItem(
                text = { Text("复制会话", fontSize = 12.sp) },
                onClick = { menuOpen = false; onDuplicate() }
            )
            DropdownMenuItem(
                text = { Text("导出 Markdown", fontSize = 12.sp) },
                onClick = { menuOpen = false; onExport() }
            )
            DropdownMenuItem(
                text = { Text("删除", fontSize = 12.sp, color = ErrorColor()) },
                onClick = { menuOpen = false; onDelete() }
            )
        }
    }
}