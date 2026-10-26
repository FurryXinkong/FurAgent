@file:OptIn(kotlinx.coroutines.FlowPreview::class)
package net.xinkong.furagent.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import net.xinkong.furagent.desktop.core.AgentControlBus
import net.xinkong.furagent.desktop.core.AgentRuntimeState
import net.xinkong.furagent.desktop.core.TaskRuntime
import net.xinkong.furagent.desktop.data.DesktopPaths
import net.xinkong.furagent.desktop.ui.theme.Radius
import org.json.JSONObject
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

// 常量

private val CARD_SIZE = DpSize(340.dp, 116.dp)

/** 多会话时每多一个运行中的会话，卡片增高多少（每个会话两行：任务 + 输出）。 */
private val EXTRA_ROW_HEIGHT = 34.dp

/** 最多额外显示几行（超出显示 +N）。 */
private const val MAX_EXTRA_ROWS = 3

/**
 * 收起态尺寸。
 *
 * 关键点：收起时**只在一个方向变窄**，另一个方向与 CARD_SIZE 一致。
 * 动画是位置 + 尺寸同时插值的。如果收起时另一个方向也变（比如
 * LEFT 吸附的高度从 96 → 80），computeDockPosition 里的 clamp 上界
 * (screen.height - h) 会随之变化，freeY 在动画中途被 clamp 到不同值，
 * 表现成"窗口一边缩一边抖"。
 */
/**
 * 收起态在屏幕上露出的宽度/高度。
 *
 * 恒定为卡片尺寸、只把窗口移到屏幕外。
 * 渲染表面 → 拉出/拉回时一闪一闪、还掉帧。
 */
private val COLLAPSED_PEEK = 8.dp

private const val DOCK_THRESHOLD_PX = 30
private const val HOT_ZONE_PX = 30
private const val ANIM_MS = 260
private const val PERSIST_DEBOUNCE_MS = 600L

private const val AUTO_COLLAPSE_DELAY_MS = 1200L
private const val HOT_ZONE_POLL_MS = 100L

private val CORNER_SHAPE = RoundedCornerShape(Radius.LG)

// 状态

private enum class DockSide {
    NONE, LEFT, RIGHT, TOP, BOTTOM;

    companion object {
        fun parse(s: String): DockSide =
            entries.firstOrNull { it.name == s } ?: NONE
    }
}

private data class PersistedState(
    val x: Int,
    val y: Int,
    val dockSide: DockSide,
)

// 持久化

private object FloatingWindowStore {

    private fun file(): File = File(DesktopPaths.configDir(), "floating_window.json")

    fun load(): PersistedState {
        return try {
            val f = file()
            if (!f.exists()) return defaultState()
            val o = JSONObject(f.readText(Charsets.UTF_8))
            val dp = defaultState()
            PersistedState(
                x = o.optInt("x", dp.x),
                y = o.optInt("y", dp.y),
                dockSide = DockSide.parse(o.optString("dockSide", "NONE")),
            )
        } catch (_: Exception) {
            defaultState()
        }
    }

    fun save(state: PersistedState) {
        try {
            val f = file()
            val o = JSONObject().apply {
                put("x", state.x)
                put("y", state.y)
                put("dockSide", state.dockSide.name)
            }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString(2), Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(o.toString(2), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (_: Exception) { }
    }

    private fun defaultState(): PersistedState {
        return try {
            val gd = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice
            val bounds = gd.defaultConfiguration.bounds
            PersistedState(
                x = bounds.width - 380,
                y = bounds.height - 160,
                dockSide = DockSide.NONE,
            )
        } catch (_: Exception) {
            PersistedState(100, 100, DockSide.NONE)
        }
    }
}

// 屏幕几何

private fun screenBoundsContaining(x: Int, y: Int): Rectangle {
    return try {
        val devices = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        for (device in devices) {
            val b = device.defaultConfiguration.bounds
            if (x in b.x..(b.x + b.width) && y in b.y..(b.y + b.height)) return b
        }
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.bounds
    } catch (_: Exception) {
        Rectangle(0, 0, 1920, 1080)
    }
}

private fun totalScreenBounds(): Rectangle {
    return try {
        val devices = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        if (devices.isEmpty()) return Rectangle(0, 0, 1920, 1080)
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (device in devices) {
            val b = device.defaultConfiguration.bounds
            if (b.x < minX) minX = b.x
            if (b.y < minY) minY = b.y
            if (b.x + b.width > maxX) maxX = b.x + b.width
            if (b.y + b.height > maxY) maxY = b.y + b.height
        }
        Rectangle(minX, minY, maxX - minX, maxY - minY)
    } catch (_: Exception) {
        Rectangle(0, 0, 1920, 1080)
    }
}

private fun clampToScreen(x: Int, y: Int, w: Int, h: Int): Point {
    val bounds = totalScreenBounds()
    val cx = x.coerceIn(bounds.x, (bounds.x + bounds.width - w).coerceAtLeast(bounds.x))
    val cy = y.coerceIn(bounds.y, (bounds.y + bounds.height - h).coerceAtLeast(bounds.y))
    return Point(cx, cy)
}

private fun detectDockSide(winX: Int, winY: Int, winW: Int, winH: Int): DockSide {
    val screen = screenBoundsContaining(winX + winW / 2, winY + winH / 2)
    val distLeft = winX - screen.x
    val distRight = (screen.x + screen.width) - (winX + winW)
    val distTop = winY - screen.y
    val distBottom = (screen.y + screen.height) - (winY + winH)
    val minDist = minOf(distLeft, distRight, distTop, distBottom)
    if (minDist > DOCK_THRESHOLD_PX) return DockSide.NONE
    return when (minDist) {
        distLeft -> DockSide.LEFT
        distRight -> DockSide.RIGHT
        distTop -> DockSide.TOP
        else -> DockSide.BOTTOM
    }
}

/**
 * 计算窗口应在的位置。
 *
 * 窗口尺寸恒定为卡片尺寸；收起时把窗口移到屏幖外，
 * 只留 [COLLAPSED_PEEK] 那么宽的一条在屏幕内。
 * 好处：动画过程中窗口尺寸不变，只有位置在动，
 * 不会触发 Windows 分层窗口的每帧重建，也就不闪了。
 */
private fun computeDockPosition(
    side: DockSide,
    freeX: Int,
    freeY: Int,
    size: DpSize,
    collapsed: Boolean,
): Point {
    val w = size.width.value.toInt()
    val h = size.height.value.toInt()
    val peek = COLLAPSED_PEEK.value.toInt()
    val screen = screenBoundsContaining(freeX + w / 2, freeY + h / 2)
    val cy = freeY.coerceIn(screen.y, (screen.y + screen.height - h).coerceAtLeast(screen.y))
    val cx = freeX.coerceIn(screen.x, (screen.x + screen.width - w).coerceAtLeast(screen.x))
    return when (side) {
        DockSide.NONE -> Point(freeX, freeY)
        DockSide.LEFT -> Point(
            if (collapsed) screen.x - (w - peek) else screen.x, cy
        )
        DockSide.RIGHT -> Point(
            if (collapsed) screen.x + screen.width - peek else screen.x + screen.width - w, cy
        )
        DockSide.TOP -> Point(
            cx, if (collapsed) screen.y - (h - peek) else screen.y
        )
        DockSide.BOTTOM -> Point(
            cx, if (collapsed) screen.y + screen.height - peek else screen.y + screen.height - h
        )
    }
}

/**
 * 鼠标是否在吸附边的热区里。
 *
 * [margin] 是迟滞余量：展开态传 60，收起态传 0。
 * 这样「展开用 30px 的窄条触发、收起要离开 90px」——鼠标沿着屏幕边缘
 * 抖动时不会在 30px 边界上反复进出，也就不会反复触发展开/收起动画。
 */
private fun isMouseInHotZone(
    mouseX: Int, mouseY: Int,
    side: DockSide,
    winX: Int, winY: Int, winW: Int, winH: Int,
    margin: Int = 0,
): Boolean {
    if (side == DockSide.NONE) return false
    val screen = screenBoundsContaining(winX + winW / 2, winY + winH / 2)
    val extra = 40
    val zone = HOT_ZONE_PX + margin
    return when (side) {
        DockSide.LEFT ->
            mouseX in screen.x..(screen.x + zone) &&
                    mouseY in (winY - extra)..(winY + winH + extra)
        DockSide.RIGHT ->
            mouseX in (screen.x + screen.width - zone)..(screen.x + screen.width) &&
                    mouseY in (winY - extra)..(winY + winH + extra)
        DockSide.TOP ->
            mouseY in screen.y..(screen.y + zone) &&
                    mouseX in (winX - extra)..(winX + winW + extra)
        DockSide.BOTTOM ->
            mouseY in (screen.y + screen.height - zone)..(screen.y + screen.height) &&
                    mouseX in (winX - extra)..(winX + winW + extra)
        DockSide.NONE -> false
    }
}

/** 工具输出的最后一行非空内容（悬浮窗只展示这一行）。 */
private fun lastOutputLine(s: String): String =
    s.lineSequence().map { it.trimEnd() }.lastOrNull { it.isNotBlank() } ?: ""

/** 耗时格式化：12.3s / 1:05 */
private fun formatElapsed(ms: Long): String {
    val sec = ms / 1000.0
    return if (sec < 60) "%.1fs".format(sec)
    else "%d:%02d".format((ms / 60000).toInt(), ((ms / 1000) % 60).toInt())
}

/**
 * 卡片内容相对窗口的对齐方式。
 *
 * 关键：收起态窗口很窄（8dp），内容固定 CARD_SIZE（340x96），
 * 超出窗口的部分被自然裁剪。对齐方向决定"卡片露出哪一边"：
 *   - LEFT 吸附：贴窗口左 → 露出卡片最左 8dp（左边缘）
 *   - RIGHT 吸附：贴窗口右 → 露出卡片最右 8dp（右边缘）
 *   - TOP 吸附：贴窗口顶 → 露出卡片最上 8dp
 *   - BOTTOM 吸附：贴窗口底 → 露出卡片最下 8dp
 *
 * 展开动画时窗口逐渐变大，露出的卡片内容也越来越多，
 * 视觉上就是"卡片从边缘滑出来"，内容全程不变形。
 */
private fun contentAlignmentFor(side: DockSide): Alignment = when (side) {
    DockSide.NONE -> Alignment.Center
    DockSide.LEFT -> Alignment.CenterStart
    DockSide.RIGHT -> Alignment.CenterEnd
    DockSide.TOP -> Alignment.TopCenter
    DockSide.BOTTOM -> Alignment.BottomCenter
}

// 主入口

@Composable
fun FloatingStatusWindow(
    visible: Boolean,
    themeMode: String,
    onOpenMainWindow: () -> Unit,
) {
    // 不要在这里 `if (!visible) return`。
    // 那会让整个 DialogWindow 在「正在显示」的过程中离开组合：AWT 队列里
    // setVisible(true) 还没跑完，ComposeContainer 就已经被 dispose，
    // ComposeWindowPanel.setBounds -> getComposeContainer 会抛
    // IllegalArgumentException: ComposeContainer is disposed。
    // 改为把 visible 透传给 DialogWindow 自己处理：窗口常驻组合，只切换可见性。
    FurAgentTheme(themeMode = themeMode) {
        FloatingStatusWindowInner(visible = visible, onOpenMainWindow = onOpenMainWindow)
    }
}

@Composable
private fun FloatingStatusWindowInner(visible: Boolean, onOpenMainWindow: () -> Unit) {
    val runtimeState by AgentRuntimeState.state.debounce(50).collectAsState(initial = AgentRuntimeState.state.value)
    val taskList by TaskRuntime.tasks.collectAsState()

    // 多会话并发 运行中的会话数决定卡片高度（最多额外 3 行）。
    val extraRows = (runtimeState.runningCount - 1).coerceIn(0, MAX_EXTRA_ROWS)
    val cardSize = CARD_SIZE.copy(height = CARD_SIZE.height + EXTRA_ROW_HEIGHT * extraRows)

    val saved = remember { FloatingWindowStore.load() }
    var dockSide by remember { mutableStateOf(saved.dockSide) }
    var isDragging by remember { mutableStateOf(false) }
    var freeX by remember { mutableIntStateOf(saved.x) }
    var freeY by remember { mutableIntStateOf(saved.y) }
    var collapsed by remember { mutableStateOf(false) }

    val windowState = rememberDialogState(
        position = WindowPosition(saved.x.dp, saved.y.dp),
        size = cardSize,
    )

    // 窗口尺寸恒定为卡片尺寸，收起靠移动到屏幖外实现。
    // 只有会话数变化（多会话 +1 行）才改一次尺寸。
    val targetSize: DpSize = cardSize

    // 位置 + 尺寸动画。

    // 帧同步用 withFrameNanos（Compose 官方 API），不用 delay(16)：
    //   - 每帧只跑一次，不会堆积
    //   - 和 Compose 的渲染循环天然同步

    // 拖动时（isDragging=true）完全不动画，跟随鼠标走。
    LaunchedEffect(dockSide, collapsed, isDragging, cardSize, visible) {
        if (!visible) return@LaunchedEffect
        if (isDragging) return@LaunchedEffect

        val target = computeDockPosition(dockSide, freeX, freeY, targetSize, collapsed)

        // 尺寸先一步到位（最多一次 resize），不在动画循环里反复改
        if (windowState.size != targetSize) windowState.size = targetSize

        val sx = windowState.position.x.value
        val sy = windowState.position.y.value
        val ex = target.x.toFloat()
        val ey = target.y.toFloat()

        if (abs(sx - ex) < 0.5f && abs(sy - ey) < 0.5f) {
            windowState.position = WindowPosition(ex.dp, ey.dp)
            return@LaunchedEffect
        }

        val startNanos = System.nanoTime()
        while (true) {
            val done = withFrameNanos { now ->
                val elapsedMs = (now - startNanos) / 1_000_000f
                val t = (elapsedMs / ANIM_MS).coerceIn(0f, 1f)
                val eased = FastOutSlowInEasing.transform(t)

                windowState.position = WindowPosition(
                    (sx + (ex - sx) * eased).dp,
                    (sy + (ey - sy) * eased).dp,
                )
                t >= 1f
            }
            if (done) break
        }

        windowState.position = WindowPosition(ex.dp, ey.dp)
    }

    // 热区轮询 + 自动收起
    LaunchedEffect(dockSide, isDragging, runtimeState.isRunning, visible) {
        if (!visible) return@LaunchedEffect
        if (dockSide == DockSide.NONE || isDragging) {
            if (collapsed) collapsed = false
            return@LaunchedEffect
        }
        var lastInHotZoneAt = System.currentTimeMillis()
        while (isActive) {
            delay(HOT_ZONE_POLL_MS.milliseconds)

            if (isDragging) {
                lastInHotZoneAt = System.currentTimeMillis()
                continue
            }

            val mouse = try { MouseInfo.getPointerInfo()?.location } catch (_: Exception) { null }
                ?: continue

            val winX = windowState.position.x.value.roundToInt()
            val winY = windowState.position.y.value.roundToInt()
            val winW = windowState.size.width.value.roundToInt()
            val winH = windowState.size.height.value.roundToInt()
            // 已展开时判定范围放大到 90px，避免擦边就收起
            val inHot = isMouseInHotZone(
                mouse.x, mouse.y, dockSide, winX, winY, winW, winH,
                margin = if (collapsed) 0 else 60,
            )

            if (runtimeState.isRunning) {
                lastInHotZoneAt = System.currentTimeMillis()
                if (collapsed) collapsed = false
                continue
            }

            if (inHot) {
                lastInHotZoneAt = System.currentTimeMillis()
                if (collapsed) collapsed = false
            } else {
                val idle = System.currentTimeMillis() - lastInHotZoneAt
                if (!collapsed && idle >= AUTO_COLLAPSE_DELAY_MS) {
                    collapsed = true
                }
            }
        }
    }

    // 位置持久化（debounce 600ms）
    LaunchedEffect(freeX, freeY, dockSide) {
        delay(PERSIST_DEBOUNCE_MS.milliseconds)
        FloatingWindowStore.save(
            PersistedState(x = freeX, y = freeY, dockSide = dockSide)
        )
    }

    DialogWindow(
        visible = visible,
        onCloseRequest = { /* 禁用关闭，只能从设置里关 */ },
        state = windowState,
        title = "FurAgent",
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        resizable = false,
    ) {
        val window = this.window

        // 窗口级加固

        // 只保留焦点监听 + 定时兜底置顶，删掉：
        //   - setType(UTILITY)：DialogWindow 本身就不在任务栏
        //   - WindowStateListener ICONIFIED 处理：JDialog 无此状态
        DisposableEffect(window) {
            val focusListener = object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent?) { }
                override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                    try {
                        if (!window.isAlwaysOnTop) window.isAlwaysOnTop = true
                    } catch (_: Exception) { }
                }
            }
            try { window.addWindowFocusListener(focusListener) } catch (_: Exception) { }

            val keepTopTimer = javax.swing.Timer(2000) {
                try {
                    if (!window.isAlwaysOnTop) window.isAlwaysOnTop = true
                } catch (_: Exception) { }
            }
            keepTopTimer.isCoalesce = true
            keepTopTimer.start()

            onDispose {
                try { window.removeWindowFocusListener(focusListener) } catch (_: Exception) { }
                try { keepTopTimer.stop() } catch (_: Exception) { }
            }
        }

        // 拖动：用鼠标绝对屏幕坐标算
        val dragStartMouse = remember { arrayOfNulls<Point>(1) }
        val dragStartWindow = remember { arrayOfNulls<Point>(1) }

        val dragModifier = Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = {
                    isDragging = true
                    if (dockSide != DockSide.NONE) {
                        dockSide = DockSide.NONE
                    }
                    if (collapsed) collapsed = false
                    try {
                        dragStartMouse[0] = MouseInfo.getPointerInfo()?.location
                        dragStartWindow[0] = Point(
                            windowState.position.x.value.roundToInt(),
                            windowState.position.y.value.roundToInt(),
                        )
                    } catch (_: Exception) {
                        dragStartMouse[0] = null
                        dragStartWindow[0] = null
                    }
                },
                onDrag = { change, _ ->
                    change.consume()
                    val m0 = dragStartMouse[0] ?: return@detectDragGestures
                    val w0 = dragStartWindow[0] ?: return@detectDragGestures
                    val cur = try { MouseInfo.getPointerInfo()?.location } catch (_: Exception) { null }
                        ?: return@detectDragGestures
                    val w = cardSize.width.value.roundToInt()
                    val h = cardSize.height.value.roundToInt()
                    val newX = w0.x + (cur.x - m0.x)
                    val newY = w0.y + (cur.y - m0.y)
                    val clamped = clampToScreen(newX, newY, w, h)
                    windowState.position = WindowPosition(clamped.x.dp, clamped.y.dp)
                    freeX = clamped.x
                    freeY = clamped.y
                },
                onDragEnd = {
                    isDragging = false
                    val w = cardSize.width.value.roundToInt()
                    val h = cardSize.height.value.roundToInt()
                    val side = detectDockSide(freeX, freeY, w, h)
                    if (side != DockSide.NONE) {
                        dockSide = side
                    }
                },
                onDragCancel = {
                    isDragging = false
                },
            )
        }

        // 内容区

        // 窗口尺寸已经等于卡片尺寸，内容始终填满，不需要对齐偏移
        val contentAlign = Alignment.Center
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .align(contentAlign)
                    .size(cardSize)
            ) {
                ExpandedCard(
                    runtimeState = runtimeState,
                    runningTasks = taskList.filter { it.running },
                    onStop = { AgentControlBus.stopAgent() },
                    onOpenMain = onOpenMainWindow,
                    modifier = dragModifier,
                )
            }
        }
    }
}

// 展开卡片

@Composable
private fun ExpandedCard(
    runtimeState: AgentRuntimeState.State,
    runningTasks: List<TaskRuntime.RunningTask>,
    onStop: () -> Unit,
    onOpenMain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 性能 不做 hover 检测（展开动画期间会与窗口动画争帧）
    val bg = CardColor()
    val borderColor by animateColorAsState(
        targetValue = if (runtimeState.isRunning) {
            AccentColor().copy(alpha = 0.55f)
        } else BorderColor(),
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "cardBorder",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .shadow(
                elevation = 12.dp,
                shape = CORNER_SHAPE,
                clip = false,
                ambientColor = glassShadowColor(),
                spotColor = glassShadowColor(),
            )
            .background(bg, CORNER_SHAPE)
            .border(1.dp, borderColor, CORNER_SHAPE)
            .clip(CORNER_SHAPE),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(runtimeState)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = headerText(runtimeState),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (runtimeState.isRunning) {
                    SmallButton(
                        icon = Icons.Default.Stop,
                        tint = ErrorColor(),
                        contentDesc = "停止",
                        onClick = onStop,
                    )
                    Spacer(Modifier.width(2.dp))
                }
                SmallButton(
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    tint = TextSecondary(),
                    contentDesc = "打开主窗口",
                    onClick = onOpenMain,
                )
            }

            // 信息增强 多会话：每个会话两行（会话名 + 当前工具 / 最新输出）
            //           单会话：「正在执行的任务 + 耗时」+ 「工具最新一行输出」
            val running = runtimeState.sessions.values.toList()
            if (running.size > 1) {
                val shown = running.take(1 + MAX_EXTRA_ROWS)
                shown.forEach { s ->
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .background(AccentColor(), CircleShape),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = s.title,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (s.currentToolName.isNotBlank()) s.currentToolName else "生成中…",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (s.currentToolName.isNotBlank()) WarningColor() else TextMuted(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = sessionProgressText(s),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                        )
                    }
                }
                val hidden = running.size - shown.size
                if (hidden > 0) {
                    Text(
                        text = "还有 $hidden 个会话在运行",
                        fontSize = 10.sp,
                        color = TextMuted(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                val task = runningTasks.lastOrNull()
                if (task != null) {
                    // 任务行：正在执行什么 + 已经跑了多久
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = task.displayName,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = WarningColor(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = formatElapsed(task.elapsedMs),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted(),
                        )
                    }
                    // 输出行：工具的最新一行输出（没有就退回 subtitle）
                    val line = lastOutputLine(task.output).ifBlank {
                        task.subtitle.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
                    }
                    if (line.isNotBlank()) {
                        Text(
                            text = line,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextSecondary(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Text(
                        text = detailText(runtimeState),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (runningTasks.isNotEmpty()) {
                val task = runningTasks.last()
                val progress = task.progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = AccentColor(),
                        trackColor = BorderSubtle(),
                    )
                } else {
                    IndeterminateBar()
                }
            } else if (runtimeState.streamingText.isNotEmpty()) {
                IndeterminateBar()
            }
        }
    }
}

// 小组件

@Composable
private fun StatusDot(
    runtimeState: AgentRuntimeState.State,
    size: Int = 10,
) {
    val color = when {
        !runtimeState.isRunning -> TextMuted()
        runtimeState.currentToolName.isNotBlank() -> WarningColor()
        runtimeState.streamingText.isNotBlank() -> AccentColor()
        else -> ResultColor()
    }

    val infinite = rememberInfiniteTransition(label = "dot")
    val alpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (runtimeState.isRunning) 0.45f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotAlpha",
    )

    Box(
        Modifier
            .size(size.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(color, CircleShape),
    )
}

@Composable
private fun SmallButton(
    icon: ImageVector,
    tint: Color,
    contentDesc: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (hovered) CardStrongColor() else Color.Transparent,
        animationSpec = tween(120),
        label = "btnBg",
    )

    Box(
        Modifier
            .size(24.dp)
            .background(bg, RoundedCornerShape(Radius.TINY))
            .clip(RoundedCornerShape(Radius.TINY))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDesc,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun IndeterminateBar() {
    val infinite = rememberInfiniteTransition(label = "indet")
    val shift by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "indetShift",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(BorderSubtle(), RoundedCornerShape(Radius.HAIRLINE))
            .clip(RoundedCornerShape(Radius.HAIRLINE)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.4f)
                .graphicsLayer { translationX = shift * 500f }
                .background(AccentColor(), RoundedCornerShape(Radius.HAIRLINE)),
        )
    }
}

// 文案

private fun headerText(state: AgentRuntimeState.State): String {
    return when {
        !state.isRunning -> "FurAgent"
        state.runningCount > 1 -> "FurAgent · ${state.runningCount} 个会话运行中"
        state.toolCallCount > 0 -> "FurAgent · 已调用 ${state.toolCallCount} 个工具"
        else -> "FurAgent · 思考中"
    }
}

/** 多会话列表里单个会话的进度文案。 */
private fun sessionProgressText(s: AgentRuntimeState.SessionRuntime): String {
    return when {
        s.currentToolName.isNotBlank() -> "调用：${s.currentToolName}"
        s.streamingText.isNotBlank() -> {
            // 从 40 字符扩到 120
            val preview = s.streamingText.takeLast(120).replace("\n", " ").trim()
            preview.ifBlank { "生成中…" }
        }
        s.thinking.isNotBlank() -> s.thinking.take(80)
        else -> "处理中…"
    }
}

private fun detailText(state: AgentRuntimeState.State): String {
    return when {
        !state.isRunning -> "空闲"
        state.currentToolName.isNotBlank() -> "正在调用：${state.currentToolName}"
        state.streamingText.isNotBlank() -> {
            // 从 60 字符扩到 200，让悬浮窗能看到更多 AI 输出
            val preview = state.streamingText.takeLast(200).replace("\n", " ").trim()
            preview.ifBlank { "生成中…" }
        }
        state.thinking.isNotBlank() -> state.thinking.take(120)
        else -> "处理中…"
    }
}