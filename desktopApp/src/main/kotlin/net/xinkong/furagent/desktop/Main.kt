package net.xinkong.furagent.desktop

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import net.xinkong.furagent.desktop.bridge.BridgeServer
import net.xinkong.furagent.desktop.bridge.DesktopNotificationManager
import net.xinkong.furagent.desktop.bridge.HttpFileServer
import net.xinkong.furagent.desktop.core.*
import net.xinkong.furagent.desktop.core.mcp.McpManager
import net.xinkong.furagent.desktop.data.*
import net.xinkong.furagent.desktop.system.*
import net.xinkong.furagent.desktop.tools.DesktopTools
import net.xinkong.furagent.desktop.ui.*
import net.xinkong.furagent.shared.update.UpdateChecker
import net.xinkong.furagent.shared.update.UpdateInfo
import net.xinkong.furagent.shared.util.DateFormats
import org.json.JSONObject
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

private fun shutdownAndExit() {
    try { TaskScheduler.stop() } catch (_: Throwable) { }
    try { BridgeServer.stop() } catch (_: Throwable) { }
    try { HttpFileServer.stop() } catch (_: Throwable) { }
    try { McpManager.shutdown() } catch (_: Throwable) { }
    try { DesktopSpeechHelper.stop() } catch (_: Throwable) { }
    try { UiaScreenController.shutdown() } catch (_: Throwable) { }
    try { DesktopNotifier.shutdown() } catch (_: Throwable) { }
    try { DesktopLogger.info("SYS", "正在退出") } catch (_: Throwable) { }
    exitProcess(0)
}

/**
 * 全局崩溃处理
 *
 * 背景：桌面端（Compose Desktop / Skiko）在未捕获异常时，JVM 默认会弹一个
 * AWT 原生错误对话框（只有一个路径/堆栈，点“确定”后进程就退出）。
 * 用户看到的就是“莫名其妙弹个框，确认后软件就崩了”。
 *
 * 方案：
 *   1. 装全局 UncaughtExceptionHandler，把异常写进日志文件，不再弹原生框；
 *   2. 关掉 AWT 的异常弹窗（sun.awt.exception.handler / 系统属性）；
 *   3. 崩溃后不直接 exitProcess，让用户能继续看到界面（除非是致命错误）。
 *
 * 日志位置：~/.furagent/logs/crash-*.log（与 DesktopLogger 同目录）。
 */
private fun installGlobalCrashHandler() {
    // AWT 的 EDT 不走 setDefaultUncaughtExceptionHandler，
    // 必须把处理器类名设给 sun.awt.exception.handler，否则剪贴板被占用时
    // 会弹原生框 “cannot open system clipboard”。
    // 注意：这里必须设**类名**，设空字符串是无效值（AWT 会回退到默认弹框）。
    try {
        System.setProperty("sun.awt.exception.handler", AwtExceptionHandler::class.java.name)
    } catch (_: Throwable) { }

    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        try {
            val sw = java.io.StringWriter()
            throwable.printStackTrace(java.io.PrintWriter(sw))
            val stack = sw.toString()
            val header = "[${DateFormats.formatFullMs()}] " +
                    "线程 ${thread.name} 未捕获异常：${throwable.javaClass.name}: ${throwable.message}\n"
            // 优先走 DesktopLogger（已初始化时）；否则直接写文件
            try {
                DesktopLogger.error("CRASH", header + stack)
            } catch (_: Throwable) {
                try {
                    val dir = File(System.getProperty("user.home"), ".furagent/logs")
                    dir.mkdirs()
                    val f = File(dir, "crash-${System.currentTimeMillis()}.log")
                    f.writeText(header + stack)
                } catch (_: Throwable) { }
            }
        } catch (_: Throwable) { }
        // 不再调用 defaultHandler（它会弹 AWT 原生框并退出）
    }
}

fun main() {
    // 最先装全局崩溃处理器，拦住 JVM 原生错误弹窗
    installGlobalCrashHandler()

    val renderApi = System.getenv("FURAGENT_RENDER")?.takeIf { it.isNotBlank() } ?: "OPENGL"
    System.setProperty("skiko.renderApi", renderApi)

    // 抑制 Skiko 原生崩溃弹窗
    // Skiko 在渲染线程崩溃时会弹一个 Windows 原生 MessageBox（只显示一个日志路径），
    // 用户点“确定”后进程就退出。这里关掉它的弹窗行为，让异常走我们的日志。
    // 不同 Skiko 版本属性名不同，全部设一遍，不存在的属性会被忽略。
    try { System.setProperty("skiko.win.exception.handler", "") } catch (_: Throwable) { }
    try { System.setProperty("skiko.exception.handler", "") } catch (_: Throwable) { }
    try { System.setProperty("skiko.crash.report", "false") } catch (_: Throwable) { }
    try { System.setProperty("skiko.show.crash.dialog", "false") } catch (_: Throwable) { }

    application {
        LaunchedEffect(Unit) {
            // 首次运行 自动创建所有目录和初始配置文件
            // 实现已移到 data/ConfigBootstrap.kt
            ConfigBootstrap.ensureAllConfigFiles()

            val s = DesktopSettings.load()
            DesktopLogger.init()
            net.xinkong.furagent.shared.tools.ToolLogger.setHook { tag, msg ->
                DesktopLogger.info(tag, msg)
            }
            DesktopSettingsHolder.current = s
            HttpFileServer.start(s.port + 1)
            BridgeServer.start(s.port)
            // 通知同步开关（手机 → 电脑）
            DesktopNotificationManager.load()
            TaskScheduler.start()
            McpManager.startAll()
            // 启动时按设置清理 temp 目录（只删程序生成的、超过 N 天的文件）
            TempCleaner.cleanupOnStartup(s.tempCleanupDays)
            // 双端共用 安装桌面端脚本运行时（ProcessBuilder 调系统 python）
            SkillStore.install()
            // 首次运行时创建示例脚本，让用户知道脚本目录怎么用
            SkillStore.ensureExample()
            // 屏幕控制（UIA）。内部异步探活，不阻塞启动。
            UiaScreenController.init()

            // 会话摘要：每天首次打开时异步跑一次（不阻塞 UI）
            launch {
                try {
                    SessionSummarizer.runOnStartup(s)
                } catch (e: Exception) {
                    DesktopLogger.warn("SYS", "会话摘要启动失败：${e.message}")
                }
            }

            DesktopLogger.info("SYS", "渲染后端：$renderApi")
        }

        // settings 提升到 application 作用域，悬浮窗也要读它
        var settings by remember { mutableStateOf(DesktopSettings.load()) }

        // 常驻后台 窗口是否已收进托盘；托盘图标常驻，菜单可恢复/退出
        var hiddenToTray by remember { mutableStateOf(false) }
        val trayState = rememberTrayState()

        // 关闭统一入口 窗口右上角的 × 是自建标题栏按钮，它不会触发
        // onCloseRequest（那是系统声明的关闭事件）。之前它直接调 quitApp()，
        // 所以点×永远是真退出，closeToTray 开关形同虚设。现在两边走同一个入口。
        fun requestClose() {
            if (settings.closeToTray && java.awt.SystemTray.isSupported()) {
                // 只隐藏窗口，不发任何通知：托盘图标就在那儿，
                // 再弹一个“仍在后台运行”只会嫌烦。
                hiddenToTray = true
            } else {
                shutdownAndExit()
            }
        }

        Window(
            visible = !hiddenToTray,
            onCloseRequest = { requestClose() },
            title = "FurAgent Desktop",
            icon = AppIcon.painter(),
            undecorated = true,
            state = rememberWindowState(width = 1280.dp, height = 820.dp),
            onPreviewKeyEvent = { event ->
                // 排除 Shift/Alt，避免 Ctrl+Shift+N 也触发新建会话
                event.type == KeyEventType.KeyDown &&
                    event.isCtrlPressed &&
                    !event.isShiftPressed &&
                    !event.isAltPressed &&
                    when (event.key) {
                        Key.N -> { HotkeyBus.send(HotkeyBus.Action.NEW_CHAT); true }
                        Key.K -> { HotkeyBus.send(HotkeyBus.Action.SEARCH_SESSIONS); true }
                        Key.L -> { HotkeyBus.send(HotkeyBus.Action.CLEAR_INPUT); true }
                        Key.Comma -> { HotkeyBus.send(HotkeyBus.Action.OPEN_SETTINGS); true }
                        Key.B -> { HotkeyBus.send(HotkeyBus.Action.TOGGLE_SIDEBAR); true }
                        else -> false
                    }
            }
        ) {
            val window = this.window
            var isMaximized by remember { mutableStateOf(false) }
            // 最大化前的窗口位置和大小，还原时用（无边框窗口手动模拟最大化，必须自己记）
            var restoreBounds by remember { mutableStateOf<java.awt.Rectangle?>(null) }

            DisposableEffect(window) {
                MainWindowHolder.register(window)
                onDispose { MainWindowHolder.register(null) }
            }

            fun quitApp() {
                shutdownAndExit()
            }

            fun applySettings(newSettings: DesktopSettings) {
                settings = newSettings
                DesktopSettingsHolder.current = newSettings
                newSettings.save()
            }

            fun toggleMaximize() {
                if (!isMaximized) {
                    // 记一下原来的位置，个别 JVM 上系统还原会跑位，兜底用
                    restoreBounds = window.bounds
                    // MAXIMIZED_BOTH 对无边框窗口不管用：Windows 看到「无边框 + 铺满整屏」
                    // 就按全屏独占路径处理（DirectFlip），所以最大化后跟全屏一样。
                    // 现在只铺「工作区」（已扣掉任务栏），窗口不覆盖整屏，
                    // 系统就不会当它是全屏，任务栏也正常可见。
                    runCatching {
                        val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                        val wa = ge.maximumWindowBounds      // 工作区，已排除任务栏
                        val screen = ge.screenDevices.firstOrNull()?.defaultConfiguration?.bounds ?: wa
                        var w = wa.width
                        var h = wa.height
                        // 任务栏自动隐藏时工作区等于整屏，再缩 1px 避开全屏判定
                        if (w >= screen.width && h >= screen.height) {
                            w -= 1
                            h -= 1
                        }
                        window.extendedState = window.extendedState and java.awt.Frame.MAXIMIZED_BOTH.inv()
                        window.bounds = java.awt.Rectangle(wa.x, wa.y, w, h)
                    }
                    isMaximized = true
                } else {
                    window.extendedState = window.extendedState and java.awt.Frame.MAXIMIZED_BOTH.inv()
                    isMaximized = false
                    // 等系统把 NORMAL 状态处理完再校正一次位置，避免无边框窗口还原后跑位
                    val b = restoreBounds
                    if (b != null) {
                        javax.swing.SwingUtilities.invokeLater {
                            try {
                                if (window.extendedState and java.awt.Frame.MAXIMIZED_BOTH == 0) {
                                    window.bounds = b
                                }
                            } catch (_: Throwable) { }
                        }
                    }
                }
            }

            // 用 ComponentListener 监听 AWT 窗口状态变化，
            // 覆盖 Win+↑ / 双击标题栏等用户直接操作，保证 isMaximized 与实际状态一致
            DisposableEffect(window) {
                val listener = object : java.awt.event.ComponentAdapter() {
                    override fun componentResized(e: java.awt.event.ComponentEvent) {
                        try {
                            val wa = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                            val b = window.bounds
                            val nowMax = kotlin.math.abs(b.width - wa.width) <= 2 &&
                                    kotlin.math.abs(b.height - wa.height) <= 2 &&
                                    kotlin.math.abs(b.x - wa.x) <= 2 &&
                                    kotlin.math.abs(b.y - wa.y) <= 2
                            if (nowMax != isMaximized) isMaximized = nowMax
                        } catch (_: Throwable) { }
                    }
                }
                window.addComponentListener(listener)
                onDispose { window.removeComponentListener(listener) }
            }

            DisposableEffect(Unit) {
                DesktopNotifier.setActions(
                    DesktopNotifier.TrayActions(
                        // 托盘/通知恢复窗口时必须同步 hiddenToTray，
                        // 否则 Window(visible = !hiddenToTray) 会在下一次重组时把窗口藏回去
                        onShowWindow = {
                            hiddenToTray = false
                            javax.swing.SwingUtilities.invokeLater {
                                window.isVisible = true
                                window.toFront()
                                window.requestFocus()
                            }
                        },
                        onHideWindow = {
                            javax.swing.SwingUtilities.invokeLater { window.isVisible = false }
                        },
                        onOpenSettings = {
                            hiddenToTray = false
                            javax.swing.SwingUtilities.invokeLater {
                                window.isVisible = true
                                window.toFront()
                                window.requestFocus()
                            }
                            HotkeyBus.send(HotkeyBus.Action.OPEN_SETTINGS)
                        },
                        onQuitApp = {
                            javax.swing.SwingUtilities.invokeLater { quitApp() }
                        }
                    )
                )
                onDispose { }
            }

            DisposableEffect(Unit) {
                HttpFileServer.setWindowActivator {
                    javax.swing.SwingUtilities.invokeLater {
                        window.isVisible = true
                        window.toFront()
                        window.requestFocus()
                    }
                }
                onDispose { HttpFileServer.setWindowActivator { } }
            }

            FurAgentTheme(themeMode = settings.themeMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            WindowTitleBar(
                                title = "FurAgent Desktop",
                                window = window,
                                onMinimize = {
                                    try { window.setState(java.awt.Frame.ICONIFIED) } catch (_: Exception) { }
                                },
                                onMaximize = { toggleMaximize() },
                                onClose = { requestClose() },
                                // 最大化状态下拖拽先还原，避免无边框窗口乱跳
                                isMaximized = isMaximized,
                                onRestoreIfMaximized = {
                                    val b = restoreBounds
                                    if (b != null) {
                                        window.extendedState = window.extendedState and
                                                java.awt.Frame.MAXIMIZED_BOTH.inv()
                                        window.bounds = b
                                        isMaximized = false
                                    }
                                }
                            )
                            App(settings = settings, onSettingsChange = { applySettings(it) })
                        }

                        // 右上角浮层区：工具运行状态 + 下载进度 + 轻提示
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(end = 24.dp, top = 60.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            TaskFloatingBar()
                            DownloadToastBar()
                            ToastHost()
                        }
                    }
                }
            }
        }

        // 常驻后台 系统托盘：关闭窗口后靠它恢复/退出
        if (settings.closeToTray && java.awt.SystemTray.isSupported()) {
            Tray(
                state = trayState,
                icon = AppIcon.painter() ?: androidx.compose.ui.graphics.painter.ColorPainter(
                    androidx.compose.ui.graphics.Color(0xFF7C5CFF)
                ),
                tooltip = "FurAgent 正在后台运行",
                onAction = {
                    hiddenToTray = false
                    MainWindowHolder.activate()
                },
                menu = {
                    Item("显示主窗口") {
                        hiddenToTray = false
                        MainWindowHolder.activate()
                    }
                    Separator()
                    Item("退出 FurAgent") { shutdownAndExit() }
                }
            )
        }

        // 独立的置顶悬浮窗，显示 AI 运行状态
        FloatingStatusWindow(
            visible = settings.floatingWindowEnabled,
            themeMode = settings.themeMode,
            onOpenMainWindow = {
                hiddenToTray = false
                MainWindowHolder.activate()
            },
        )
    }
}

@Composable
fun App(settings: DesktopSettings, onSettingsChange: (DesktopSettings) -> Unit) {
    var messages by remember { mutableStateOf<List<DesktopMessage>>(emptyList()) }
    var currentSessionId by remember { mutableStateOf<String?>(null) }
    var sessionCreatedAt by remember { mutableStateOf(0L) }
    var sessionList by remember { mutableStateOf<List<DesktopSessionMeta>>(emptyList()) }
    // 多会话并发 运行状态按会话隔离，切会话不互相干扰。
    // runningSessions：哪些会话正在跑；sessionThinking/sessionStreaming：各会话的中间态。
    var runningSessions by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sessionThinking by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // 错误状态 API 错误（如「HTTP 429 · 用量配额已用尽」），渲染成状态小气泡。
    // 与 sessionThinking 分开：thinking 在任务结束时清空，error 保留到下次发消息。
    var sessionError by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var sessionReasoning by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var sessionStreaming by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // 多会话并发 运行中会话的最新消息列表（供悬浮窗显示非当前会话的工具名/进度）。
    var sessionMessages by remember { mutableStateOf<Map<String, List<DesktopMessage>>>(emptyMap()) }
    var inputText by remember { mutableStateOf("") }

    // 检查更新结果。 = 还没查完 / 查失败，此时不显示红点也不改按钮文案。
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

    // 启动时静默检查更新：UpdateChecker 内部切 Dispatchers.IO，
    // 这里直接 launch 不会阻塞 UI 线程。失败静默忽略。
    LaunchedEffect(Unit) {
        val ver = DesktopVersion.current()
        if (ver.isNotBlank()) {
            UpdateChecker.check(ver, UpdateChecker.Platform.DESKTOP).onSuccess { updateInfo = it }
        }
    }

    // 会话隔离 输入框草稿按会话保存：切走存盘、切回恢复、输入后防抖落盘。
    // 监听 currentSessionId 而非逐个改切换入口，新增入口也不会漏。
    // 从 Preferences 改为文件存储：Preferences 单值有 8KB 上限，
    // 超长草稿 put 会抛异常或静默失败，且 Windows 走注册表不稳。
    val draftDir = remember {
        java.io.File(System.getProperty("user.home"), ".furagent/drafts").apply { mkdirs() }
    }
    fun draftFile(id: String) = java.io.File(draftDir, "$id.txt")
    fun readDraft(id: String): String = try {
        val f = draftFile(id)
        if (f.exists()) f.readText(Charsets.UTF_8) else ""
    } catch (_: Throwable) { "" }
    fun writeDraft(id: String, text: String) {
        try {
            val f = draftFile(id)
            if (text.isEmpty()) {
                if (f.exists()) f.delete()
            } else {
                // 原子写：先写临时文件再 rename，防止崩溃留下半截草稿
                val tmp = java.io.File(draftDir, "$id.tmp")
                tmp.writeText(text, Charsets.UTF_8)
                if (f.exists()) f.delete()
                if (!tmp.renameTo(f)) {
                    f.writeText(text, Charsets.UTF_8)
                    tmp.delete()
                }
            }
        } catch (_: Throwable) { }
    }
    // 草稿归属者：知道当前 inputText 属于哪个会话
    var draftOwner by remember { mutableStateOf(currentSessionId) }
    LaunchedEffect(currentSessionId) {
        val prev = draftOwner
        if (prev == currentSessionId) return@LaunchedEffect
        prev?.let { writeDraft(it, inputText) }
        draftOwner = currentSessionId
        inputText = currentSessionId?.let { readDraft(it) } ?: ""
    }
    LaunchedEffect(inputText, currentSessionId) {
        delay(400.milliseconds)
        // 只写回「自己的」会话：切换瞬间 inputText 可能还是旧的，
        // 不加这个判断会把 A 的草稿写进 B
        if (draftOwner == currentSessionId) {
            currentSessionId?.let { writeDraft(it, inputText) }
        }
    }

    // 把"当前显示会话"同步给 UI 层 shim（HistoryPage 等页面使用）。
    // 主 Agent 的工具归属已改用协程上下文（SessionIdElement），不再依赖此字段。
    LaunchedEffect(currentSessionId) {
        net.xinkong.furagent.desktop.tools.DesktopTools.CurrentTaskSession.id =
            currentSessionId ?: ""
    }

    var currentPage by remember { mutableStateOf(AppPage.CHAT) }
    var sidebarExpanded by remember { mutableStateOf(true) }

    // 首次运行引导：只弹一次。看过一次后 ~/.furagent/config/onboarding.json 里
    // guideDone=true，之后永远不再弹（TOAI §22.4）。
    var showOnboardingGuide by remember {
        mutableStateOf(!OnboardingStore.isGuideDone())
    }

    var pendingImportFile by remember { mutableStateOf<File?>(null) }
    var pendingOverwriteFile by remember { mutableStateOf<File?>(null) }
    var exportSessionId by remember { mutableStateOf<String?>(null) }
    var renamingSessionId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteSessionId by remember { mutableStateOf<String?>(null) }
    // 体验优化 清空全部会话的二次确认
    var pendingClearAllSessions by remember { mutableStateOf(false) }
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    var editingContent by remember { mutableStateOf("") }

    var showSearch by remember { mutableStateOf(false) }
    var scrollToMessageId by remember { mutableStateOf<String?>(null) }

    // 多会话并发 每个会话一个 Job / token，互不干扰。
    var sessionJobs by remember { mutableStateOf<Map<String, Job>>(emptyMap()) }
    var sessionTokens by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }

    val scope = rememberCoroutineScope()

    // 当前会话的派生状态（UI 用）
    val isLoading = currentSessionId != null && currentSessionId in runningSessions
    val thinking = currentSessionId?.let { sessionThinking[it] } ?: ""
    val reasoning = currentSessionId?.let { sessionReasoning[it] } ?: ""
    val streamingText = currentSessionId?.let { sessionStreaming[it] } ?: ""
    // 错误状态 API 错误（如「HTTP 429 · 用量配额已用尽」）
    val errorText = currentSessionId?.let { sessionError[it] } ?: ""

    // 局部函数：先定义，后使用

    /** 停掉指定会话的 Agent 任务。 */
    fun stopSession(sessionId: String) {
        // cancel 之前先把已生成的流式内容落盘。
        // 之前直接 cancel，finally 里 token 已变 isCurrent() 为 false，不持久化，
        // 用户点了「停止」，屏幕上那段回复一切会话就没了。
        val streaming = sessionStreaming[sessionId] ?: ""
        if (streaming.isNotBlank()) {
            val base = sessionMessages[sessionId] ?: messages
            val withStreaming = base + DesktopMessage(
                role = "assistant",
                content = streaming,
                type = "text",
                interrupted = true
            )
            sessionMessages = sessionMessages + (sessionId to withStreaming)
            if (currentSessionId == sessionId) messages = withStreaming
            val createdAt = if (sessionId == currentSessionId) sessionCreatedAt
                else DesktopSessionStore.load(sessionId)?.createdAt ?: System.currentTimeMillis()
            // persist 定义在 stopSession 之后，局部函数不能前向引用，
            // 这里直接调 DesktopSessionStore.save（标题/父会话保护逻辑与 persist 保持一致）。
            try {
                val existing = if (sessionId.startsWith("sub_")) DesktopSessionStore.load(sessionId) else null
                val title = when {
                    existing?.title?.startsWith("↳") == true -> existing.title
                    sessionId.startsWith("sub_") -> "↳ " + DesktopSessionStore.generateTitle(withStreaming)
                    else -> DesktopSessionStore.generateTitle(withStreaming)
                }
                DesktopSessionStore.save(
                    DesktopSession(
                        id = sessionId,
                        title = title,
                        createdAt = createdAt,
                        updatedAt = System.currentTimeMillis(),
                        messages = withStreaming,
                        parentId = existing?.parentId
                    )
                )
            } catch (_: Throwable) { }
        }

        // 递增该会话的 token，让旧任务的 isCurrent() 变 false
        sessionTokens = sessionTokens + (sessionId to ((sessionTokens[sessionId] ?: 0L) + 1))
        sessionJobs[sessionId]?.cancel()
        sessionJobs = sessionJobs - sessionId
        // 杀掉正在跑的工具进程（execute_command / ffmpeg / python 等）。
        // 光 cancel 协程只能停住“下一轮”，正在阻塞执行的子进程不会自己退出，
        // 必须主动触发 TaskRuntime 的取消回调（内部会 destroyForcibly + taskkill）。
        //
        // 按会话精确取消：只杀属于本会话的正在运行工具，
        // 不再误杀其他并发会话正在跑的工具。
        try {
            TaskRuntime.cancelBySession(sessionId)
        } catch (_: Throwable) { }
        runningSessions = runningSessions - sessionId
        sessionThinking = sessionThinking - sessionId
        sessionReasoning = sessionReasoning - sessionId
        sessionStreaming = sessionStreaming - sessionId
        sessionMessages = sessionMessages - sessionId
        if (currentSessionId == sessionId) {
            try { DesktopSpeechHelper.stop() } catch (_: Throwable) { }
        }
    }

    /** 停掉当前会话的 Agent 任务（UI 的“停止”按钮）。 */
    fun stopAgent() {
        currentSessionId?.let { stopSession(it) }
    }

    fun persist(
        sessionId: String? = currentSessionId,
        history: List<DesktopMessage> = messages,
        createdAt: Long = sessionCreatedAt,
        refreshList: Boolean = true
    ) {
        val id = sessionId ?: return
        // 子会话标题保护 子会话标题带「↳ 」前缀，是子代理列表的识别依据。
        // 以前这里无条件 generateTitle，一 persist 就把前缀抹掉，
        // 子会话于是出现在主会话侧栏、又不在子代理列表里（看起来就像
        // “子代理把原会话覆盖了”，而且再也找不到回去的入口）。
        // 现在：子会话（id 以 sub_ 开头）保留原标题。
        // load 不限于子会话：主会话也可能被用户手动重命名过。
        // titleLocked=true 表示用户手动设置过标题，persist 时保留，不覆盖。
        val existing = DesktopSessionStore.load(id)
        val title = when {
            existing?.titleLocked == true -> existing.title
            existing?.title?.startsWith("↳") == true -> existing.title
            id.startsWith("sub_") -> "↳ " + DesktopSessionStore.generateTitle(history)
            else -> DesktopSessionStore.generateTitle(history)
        }
        DesktopSessionStore.save(
            DesktopSession(
                id = id,
                title = title,
                createdAt = createdAt,
                updatedAt = System.currentTimeMillis(),
                messages = history,
                parentId = existing?.parentId,
                titleLocked = existing?.titleLocked
            )
        )
        if (refreshList) {
            sessionList = DesktopSessionStore.list().filter { it.parentId == null }
        }
    }

    fun startNewSession() {
        val s = DesktopSessionStore.newSession()
        currentSessionId = s.id
        sessionCreatedAt = s.createdAt
        messages = emptyList()
        sessionList = DesktopSessionStore.list().filter { it.parentId == null }
    }

    fun switchSession(id: String) {
        val s = DesktopSessionStore.load(id) ?: return
        currentSessionId = s.id
        sessionCreatedAt = s.createdAt
        messages = s.messages
    }

    fun duplicateSession(id: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                DesktopSessionStore.duplicate(id)
            }
            if (result != null) {
                sessionList = DesktopSessionStore.list().filter { it.parentId == null }
                ToastBus.info("已复制会话「${result.title}」")
            } else {
                ToastBus.error("复制失败：会话不存在")
            }
        }
    }

    fun pickAndImportSingleSession() {
        // 改用原生文件对话框
        val f = NativeFilePicker.pickFile(
            title = "选择要导入的会话（JSON / Markdown）",
            extensions = listOf("json", "md")
        ) ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                DesktopSessionStore.importSingleSession(f)
            }
            if (result.success) {
                sessionList = DesktopSessionStore.list().filter { it.parentId == null }
                result.sessionId?.let { switchSession(it) }
                currentPage = AppPage.CHAT
                ToastBus.info("已导入「${result.sessionTitle}」（${result.messageCount} 条消息）")
            } else {
                ToastBus.error("导入失败：${result.error ?: "未知错误"}")
            }
        }
    }

    fun runAgent(history: List<DesktopMessage>) {
        val profile = settings.currentProfile()
        if (profile == null || profile.apiKey.isBlank()) {
            currentPage = AppPage.SETTINGS
            return
        }

        try { DesktopSpeechHelper.stop() } catch (_: Throwable) { }

        val taskSessionId = currentSessionId ?: return
        val taskCreatedAt = sessionCreatedAt

        val myToken = (sessionTokens[taskSessionId] ?: 0L) + 1
        sessionTokens = sessionTokens + (taskSessionId to myToken)

        runningSessions = runningSessions + taskSessionId
        sessionThinking = sessionThinking + (taskSessionId to "")
        sessionReasoning = sessionReasoning + (taskSessionId to "")
        sessionStreaming = sessionStreaming + (taskSessionId to "")
        // 错误消失 新一轮任务一开始，把上一次的 API 错误清掉。
        // 之前只在重试按钮里清，所以切了模型、发了新消息，
        // 那个 HTTP 429 气泡还挂在底下不动。
        sessionError = sessionError + (taskSessionId to "")
        sessionMessages = sessionMessages + (taskSessionId to history)

        val job = scope.launch {
            fun isCurrent() = sessionTokens[taskSessionId] == myToken
            // 流式输出缓冲：每 chunk 直接写 state 会让主窗口每 chunk 全量重组，
            // 合并到 32ms 间隔再由 flusher 协程刷出
            val streamBuf = java.util.concurrent.ConcurrentLinkedQueue<String>()
            // reasoning 也走队列节流，否则每 chunk 都触发全局重组
            val reasoningBuf = java.util.concurrent.ConcurrentLinkedQueue<String>()
            var thinkingLastFlush = 0L
            fun drainStream() {
                if (streamBuf.isEmpty()) return
                val sb = StringBuilder()
                while (true) {
                    val c = streamBuf.poll() ?: break
                    sb.append(c)
                }
                sessionStreaming = sessionStreaming +
                    (taskSessionId to ((sessionStreaming[taskSessionId] ?: "") + sb))
            }
            fun drainReasoning() {
                if (reasoningBuf.isEmpty()) return
                val sb = StringBuilder()
                while (true) {
                    val c = reasoningBuf.poll() ?: break
                    sb.append(c)
                }
                sessionReasoning = sessionReasoning +
                    (taskSessionId to ((sessionReasoning[taskSessionId] ?: "") + sb))
            }
            val flusher = launch {
                while (isActive) { delay(32.milliseconds); drainStream(); drainReasoning() }
            }
            try {
                // 不再写全局：sessionId 通过协程上下文传递，
                // runTurn 内部 withContext(SessionIdElement(taskSessionId))
                val result = DesktopAgent.runTurn(
                    settings = settings,
                    history = history,
                    sessionId = taskSessionId,
                    onUpdate = { newList ->
                        if (!isCurrent()) return@runTurn
                        // 只更新内存状态，不在每次消息变化时写盘（persist 移到 onStreamCommit/finally）
                        sessionMessages = sessionMessages + (taskSessionId to newList)
                        if (currentSessionId == taskSessionId) {
                            messages = newList
                        }
                    },
                    onThinking = { t ->
                        if (!isCurrent()) return@runTurn
                        // 全量替换语义，32ms 窗口内只保留最后一帧，减少主窗口重组
                        val now = System.currentTimeMillis()
                        if (now - thinkingLastFlush >= 32) {
                            thinkingLastFlush = now
                            sessionThinking = sessionThinking + (taskSessionId to t)
                        }
                    },
                    onError = { err ->
                        if (!isCurrent()) return@runTurn
                        // 错误走独立状态：不进消息列表（不占 AI 聊天气泡）
                        sessionError = sessionError + (taskSessionId to err)
                    },
                    onReasoning = { r ->
                        if (!isCurrent()) return@runTurn
                        reasoningBuf.add(r)
                    },
                    onStream = { chunk ->
                        if (!isCurrent()) return@runTurn
                        streamBuf.add(chunk)
                    },
                    onStreamCommit = {
                        if (!isCurrent()) return@runTurn
                        drainStream()
                        drainReasoning()
                        // 每次助手消息落盘时持久化一次（比 onUpdate 每次工具返回都写盘省 I/O）
                        // refreshList=false：agent 运行期间会话列表不变，省掉磁盘读取
                        // 多会话并发时，用本会话的历史，不用当前显示的 messages；
                        // 否则用户切到 B 会话时，A 的流式内容会写进 B 的会话文件
                        persist(
                            taskSessionId,
                            sessionMessages[taskSessionId] ?: history,
                            taskCreatedAt,
                            refreshList = false
                        )
                        sessionStreaming = sessionStreaming + (taskSessionId to "")
                        sessionReasoning = sessionReasoning + (taskSessionId to "")
                    },
                    // 会话专用提示词：每轮发送前实时读盘，编辑后下一轮即生效
                    sessionPrompt = DesktopSessionStore.load(taskSessionId)?.systemPrompt ?: ""
                )
                if (isCurrent()) {
                    persist(taskSessionId, result, taskCreatedAt)
                    sessionMessages = sessionMessages + (taskSessionId to result)
                    if (currentSessionId == taskSessionId) {
                        messages = result
                    }
                    if (settings.autoSpeak && currentSessionId == taskSessionId) {
                        val last = result.lastOrNull {
                            it.role == "assistant" && it.type == "text"
                        }
                        if (last != null && last.content.isNotBlank()) {
                            val clean = cleanForSpeech(last.content)
                            if (clean.isNotBlank()) {
                                try {
                                    DesktopSpeechHelper.speak(clean)
                                } catch (e: Exception) {
                                    DesktopLogger.warn("Agent", "TTS 启动失败：${e.message}")
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                // Throwable 而非 Exception：NoClassDefFoundError 等 Error 也要兜住，
                // 否则会穿出协程直达 EDT 弹原生 Error 框并打崩进程
                DesktopLogger.error(
                    "Agent",
                    "runAgent 任务异常：${e.javaClass.simpleName}: ${e.message}\n" +
                            e.stackTrace.take(5).joinToString("\n") { "  at $it" }
                )
                // 异常退出时也要保存已产生的消息；
                // 非用户主动停止的异常写一条系统提示进会话，避免对话"无声死亡"只留日志
                if (isCurrent()) {
                    // 用本会话的历史，不动当前显示的 messages
                    val base = sessionMessages[taskSessionId] ?: history
                    val newList = if (e !is CancellationException) {
                        base + DesktopMessage(
                            role = "system",
                            type = "system_note",
                            content = "本轮执行异常已中断：${e.javaClass.simpleName}: ${e.message}",
                            timestamp = System.currentTimeMillis()
                        )
                    } else base
                    sessionMessages = sessionMessages + (taskSessionId to newList)
                    if (currentSessionId == taskSessionId) messages = newList
                    persist(taskSessionId, newList, taskCreatedAt, refreshList = false)
                }
            } finally {
                flusher.cancel()
                drainStream()
                drainReasoning()
                // 用本会话的历史落盘
                if (isCurrent()) {
                    val hist = sessionMessages[taskSessionId] ?: history
                    persist(taskSessionId, hist, taskCreatedAt, refreshList = false)
                    runningSessions = runningSessions - taskSessionId
                    sessionStreaming = sessionStreaming - taskSessionId
                    sessionThinking = sessionThinking - taskSessionId
                    sessionReasoning = sessionReasoning - taskSessionId
                    sessionMessages = sessionMessages - taskSessionId
                    // 注意 sessionError 不在这里清：错误要保留到用户下次发消息，
                    // 否则一闪而过看不到。下次发消息时在启动处清空。
                }
                sessionJobs = sessionJobs - taskSessionId
            }
        }
        sessionJobs = sessionJobs + (taskSessionId to job)
    }

    fun send(attachments: List<PendingAttachment>) {
        val text = inputText.trim()
        if (text.isBlank() && attachments.isEmpty()) return
        // 子代理只读 子会话是 AI 派发的任务上下文，用户不能直接发消息，
        // 否则会污染子代理上下文。AI 追问走 run_sub_session 的 session_id 参数。
        if (currentSessionId?.startsWith("sub_") == true) {
            ToastBus.info("子代理会话只读，不能直接发消息")
            return
        }
        // 多会话并发 只拦当前会话正在跑的情况；其他会话在跑不影响本会话发消息。
        if (currentSessionId != null && currentSessionId in runningSessions) return
        val profile = settings.currentProfile()
        if (profile == null || profile.apiKey.isBlank()) {
            currentPage = AppPage.SETTINGS
            return
        }

        inputText = ""

        scope.launch {
            val finalImages = withContext(Dispatchers.IO) {
                if (!settings.imageCompress) attachments.filter { it.isImage }.map { it.path }
                else attachments.filter { it.isImage }.mapNotNull { att ->
                    val src = File(att.path)
                    if (!src.exists()) return@mapNotNull null
                    compressImage(src, settings.imageCompressQuality, settings.imageCompressMaxDim)?.absolutePath
                        ?: att.path
                }
            }
            val nonImages = attachments.filter { !it.isImage }
            val content = buildString {
                append(text)
                if (nonImages.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append("【附件】\n")
                    nonImages.forEach { append("- ${it.path}\n") }
                }
            }.trim()

            val userMsg = DesktopMessage(role = "user", content = content, imagePaths = finalImages)
            val withUser = messages + userMsg
            messages = withUser
            persist()

            runAgent(withUser)
        }
    }

    fun doExportAll() {
        val f = DesktopSessionStore.exportAll(settings)
        if (f != null) {
            ToastBus.info("已导出：${f.absolutePath}")
        } else {
            ToastBus.error("导出失败")
        }
    }

    fun doImport(file: File, replaceExisting: Boolean) {
        val result = DesktopSessionStore.importAll(file, replaceExisting)
        if (result.sessionCount < 0) {
            ToastBus.error("导入失败：${result.error ?: "未知错误"}")
            return
        }
        sessionList = DesktopSessionStore.list().filter { it.parentId == null }
        if (currentSessionId == null || sessionList.none { it.id == currentSessionId }) {
            val first = sessionList.firstOrNull()
            if (first != null) switchSession(first.id) else startNewSession()
        }
        if (result.settingsRestored && result.restoredSettings != null) onSettingsChange(result.restoredSettings)
        ToastBus.info("导入成功：${result.sessionCount} 个会话")
    }

    fun pickAndImport() {
        // 改用原生文件对话框
        pendingImportFile = NativeFilePicker.pickFile(
            title = "选择 FurAgent 备份文件",
            extensions = listOf("json")
        ) ?: return
    }

    // 副作用

    // 多会话并发 同一会话从“跑”变“不跑”时激活窗口（任务完成提醒）。
    // 记录上次的 (会话, 是否在跑)，切会话时不误触发。
    var lastRunningSession by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentSessionId, isLoading) {
        if (lastRunningSession == currentSessionId && !isLoading) {
            MainWindowHolder.activate()
        }
        lastRunningSession = if (isLoading) currentSessionId else null
    }

    // 多会话并发 把运行中的会话汇总上报给 AgentRuntimeState（供悬浮窗读取）。
    // 每个会话一份快照，工具名/工具数从 sessionMessages 取（运行中的会话都有缓存）。
    LaunchedEffect(runningSessions, sessionThinking, sessionStreaming, sessionMessages, currentSessionId) {
        val map = runningSessions.associateWith { sid ->
            val meta = sessionList.firstOrNull { it.id == sid }
            val msgs = sessionMessages[sid].orEmpty()
            val tool = msgs.lastOrNull { it.type == "tool_call" }
                ?.content?.substringBefore('\n')?.trim().orEmpty()
            AgentRuntimeState.SessionRuntime(
                sessionId = sid,
                title = meta?.title ?: "会话",
                thinking = sessionThinking[sid].orEmpty(),
                streamingText = sessionStreaming[sid].orEmpty(),
                currentToolName = tool,
                toolCallCount = msgs.count { it.type == "tool_call" },
            )
        }
        AgentRuntimeState.setSessions(map, currentSessionId)
    }

    LaunchedEffect(Unit) {
        AgentControlBus.commands.collect { cmd ->
            when (cmd) {
                AgentControlBus.Command.StopAgent -> {
                    stopAgent()
                }
                AgentControlBus.Command.OpenMainWindow -> {
                    MainWindowHolder.activate()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        HotkeyBus.events.collect { action ->
            when (action) {
                HotkeyBus.Action.NEW_CHAT -> {
                    startNewSession()
                    currentPage = AppPage.CHAT
                }
                HotkeyBus.Action.SEARCH_SESSIONS -> {
                    showSearch = true
                }
                HotkeyBus.Action.CLEAR_INPUT -> {
                    inputText = ""
                }
                HotkeyBus.Action.OPEN_SETTINGS -> {
                    currentPage = AppPage.SETTINGS
                }
                HotkeyBus.Action.TOGGLE_SIDEBAR -> {
                    sidebarExpanded = !sidebarExpanded
                }
            }
        }
    }

    // 子会话完成 → 主对话注入「子代理完成」通知（结果摘要回流）
    LaunchedEffect(Unit) {
        DesktopTools.SubSessionProgress.events.collect { e ->
            // originId 为空（桥接/悬浮窗发起、母会话已关）时此前直接丢弃通知，
            // 现在回落到最近一个主会话，至少不会让子代理结果人间蒸发。
            val target = e.originId.ifBlank {
                DesktopSessionStore.list().firstOrNull { it.parentId == null }?.id ?: ""
            }
            if (target.isBlank()) return@collect
            val notice = DesktopMessage(
                role = "system",
                content = "【子代理完成】「${e.title}」（${e.subId}）已完成，共 ${e.msgCount} 条消息。\n" +
                        "结果摘要：\n${e.summary}",
                timestamp = System.currentTimeMillis()
            )
            if (currentSessionId == target) {
                messages = messages + notice
                val title = sessionList.find { it.id == target }?.title ?: "对话"
                withContext(Dispatchers.IO) {
                    DesktopSessionStore.save(
                        DesktopSession(
                            id = target,
                            title = title,
                            createdAt = sessionCreatedAt,
                            updatedAt = System.currentTimeMillis(),
                            messages = messages
                        )
                    )
                }
            } else {
                withContext(Dispatchers.IO) {
                    val s = DesktopSessionStore.load(target) ?: return@withContext
                    DesktopSessionStore.save(
                        s.copy(
                            messages = s.messages + notice,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    // 定时任务 run_agent 写回会话 → 当前会话立即显示，侧栏列表同步刷新
    LaunchedEffect(Unit) {
        TaskScheduler.agentResults.collect { e ->
            if (currentSessionId == e.sessionId) {
                messages = messages + e.message
                persist()
            }
            sessionList = DesktopSessionStore.list().filter { it.parentId == null }
        }
    }

    // 子会话卡片/列表点击 → 打开子会话；进入前记住来路，顶部「返回」可切回主会话
    SubSessionNavigator.open = { id ->
        val enteringSub = id.startsWith("sub_")
        if (enteringSub && currentSessionId?.startsWith("sub_") != true) {
            SubSessionNavigator.originId = currentSessionId
        } else if (!enteringSub && currentSessionId?.startsWith("sub_") == true) {
            SubSessionNavigator.originId = null
        }
        switchSession(id)
    }

    LaunchedEffect(Unit) {
        val list = DesktopSessionStore.list()
        sessionList = list
        if (list.isNotEmpty()) {
            val s = DesktopSessionStore.load(list.first().id)
            if (s != null) {
                currentSessionId = s.id
                sessionCreatedAt = s.createdAt
                messages = s.messages
            }
        } else startNewSession()
    }

    // UI

    var sidebarSortMode by remember { mutableIntStateOf(0) }
    val sortedSessions = remember(sessionList, sidebarSortMode) {
        when (sidebarSortMode) {
            1 -> sessionList.sortedBy { it.updatedAt }
            2 -> sessionList.sortedBy { it.title.lowercase() }
            3 -> sessionList.sortedByDescending { it.messageCount }
            else -> sessionList.sortedByDescending { it.updatedAt }
        }
    }

    Row(Modifier.fillMaxSize()) {
        Sidebar(
            expanded = sidebarExpanded,
            currentPage = currentPage,
            sessions = sortedSessions,
            currentSessionId = currentSessionId,
            runningSessions = runningSessions,
            onToggleExpand = { sidebarExpanded = !sidebarExpanded },
            onNavigate = { currentPage = it },
            onNewChat = { startNewSession(); currentPage = AppPage.CHAT },
            onSelectSession = { switchSession(it) },
            onDeleteSession = { pendingDeleteSessionId = it },
            onRenameSession = { renamingSessionId = it },
            onExportSession = { exportSessionId = it },
            onDuplicateSession = { id -> duplicateSession(id) },
            onImportSession = { pickAndImportSingleSession() },
            onClearAllSessions = { pendingClearAllSessions = true },
            sortMode = sidebarSortMode,
            onSortModeChange = { sidebarSortMode = it }
        )

        Box(Modifier.width(1.dp).fillMaxHeight().background(BorderColor()))

        GlassBackground {
            AnimatedContent(
                targetState = currentPage,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    val enter = slideInHorizontally(
                        animationSpec = tween(280, easing = FastOutSlowInEasing),
                        initialOffsetX = { w -> if (forward) w / 8 else -w / 8 }
                    ) + fadeIn(tween(240, easing = FastOutSlowInEasing))
                    val exit = slideOutHorizontally(
                        animationSpec = tween(220, easing = FastOutSlowInEasing),
                        targetOffsetX = { w -> if (forward) -w / 8 else w / 8 }
                    ) + fadeOut(tween(180, easing = FastOutSlowInEasing))
                    enter togetherWith exit
                },
                label = "pageTransition"
            ) { page ->
                when (page) {
                    AppPage.CHAT -> ChatPage(
                        sessionId = currentSessionId,
                        messages = messages,
                        streamingText = streamingText,
                        thinking = thinking,
                        reasoning = reasoning,
                        errorText = errorText,
                        isLoading = isLoading,
                        inputText = inputText,
                        hasApiKey = remember(settings.currentProfileId) { settings.currentProfile()?.apiKey?.isNotBlank() == true },
                        modelName = settings.currentProfile()?.name ?: "未配置模型",
                        thinkingLevel = settings.thinkingLevel,
                        supportsThinking = settings.currentProfile()?.supportsThinking ?: true,
                        chatProfiles = settings.chatProfiles().map { it.id to it.name },
                        currentProfileId = settings.currentProfileId,
                        onThinkingLevelChange = { level ->
                            onSettingsChange(settings.copy(thinkingLevel = level))
                        },
                        onSelectProfile = { id ->
                            onSettingsChange(settings.copy(currentProfileId = id))
                            // 错误消失 切了模型，上一个模型报的 HTTP 429 已经不成立，
                            // 不该继续挂在底下吓人。当前会话的错误直接清掉。
                            val sid = currentSessionId
                            if (sid != null) sessionError = sessionError + (sid to "")
                        },
                        scrollToMessageId = scrollToMessageId,
                        onScrollToMessageHandled = { scrollToMessageId = null },
                        onInputChange = { inputText = it },
                        onSend = { atts -> send(atts) },
                        onStop = { stopAgent() },
                        onOpenSettings = { currentPage = AppPage.SETTINGS },
                        onOpenHistory = { currentPage = AppPage.HISTORY },
                        onCopyMessage = { msg ->
                            val ok = SafeClipboard.setText(msg.content)
                            if (ok) ToastBus.info("已复制")
                            else ToastBus.error("复制失败（系统剪贴板被占用）")
                        },
                        onRegenerateMessage = { msg ->
                            if (!isLoading) {
                                val idx = messages.indexOfFirst { it.id == msg.id }
                                if (idx >= 0) {
                                    val truncated = messages.take(idx)
                                    messages = truncated
                                    persist()
                                    runAgent(truncated)
                                }
                            }
                        },
                        onEditAndRegenerate = { msg ->
                            if (!isLoading) {
                                editingMessageId = msg.id
                                editingContent = msg.content
                            }
                        },
                        onReExecuteTool = { msg ->
                            if (!isLoading) {
                                scope.launch {
                                    reExecuteToolCall(
                                        msg = msg,
                                        messages = messages,
                                        onMessagesChanged = { messages = it; persist() },
                                        infoCallback = { text -> ToastBus.info(text) }
                                    )
                                }
                            }
                        },
                        onDeleteMessage = { msg ->
                            val idx = messages.indexOfFirst { it.id == msg.id }
                            if (idx >= 0) {
                                messages = deleteMessagePair(messages, idx)
                                persist()
                            }
                        },
                        onRetryError = {
                            val lastUserMsg = messages.lastOrNull { it.role == "user" }?.content
                            if (!lastUserMsg.isNullOrBlank() && !isLoading) {
                                sessionError = sessionError + ((currentSessionId ?: "") to "")
                                val userMsg = DesktopMessage(role = "user", content = lastUserMsg)
                                messages = messages + userMsg
                                persist()
                                runAgent(messages)
                            }
                        }
                    )
                    AppPage.MEMORY -> MemoryPage(
                        onOpenSession = { sid ->
                            if (sessionList.any { it.id == sid }) {
                                switchSession(sid)
                                currentPage = AppPage.CHAT
                            } else {
                                ToastBus.error("该会话已不存在")
                            }
                        }
                    )
                    AppPage.HISTORY -> HistoryPage()
                    AppPage.SCHEDULES -> SchedulesPage()
                    AppPage.MCP -> McpPage(
                        disabledTools = settings.disabledTools,
                        onDisabledToolsChange = { newSet ->
                            onSettingsChange(settings.copy(disabledTools = newSet))
                        }
                    )
                    AppPage.SKILLS -> SkillPage(
                        disabledTools = settings.disabledTools,
                        onDisabledToolsChange = { newSet ->
                            onSettingsChange(settings.copy(disabledTools = newSet))
                        }
                    )
                    AppPage.STATUS -> StatusPage()
                    AppPage.LOGS -> LogsPage()
                    AppPage.SETTINGS -> SettingsPage(
                        settings = settings,
                        onSettingsChange = onSettingsChange,
                        onExportAll = { doExportAll() },
                        onImport = { pickAndImport() },
                        hasUpdate = updateInfo?.hasUpdate == true,
                        onCheckUpdate = {
                            scope.launch {
                                val ver = DesktopVersion.current()
                                if (ver.isBlank()) {
                                    ToastBus.error("读取版本号失败")
                                } else {
                                    UpdateChecker.check(
                                        ver,
                                        UpdateChecker.Platform.DESKTOP,
                                        force = true
                                    )
                                        .onSuccess { info ->
                                            updateInfo = info
                                            if (info.hasUpdate) {
                                                ToastBus.info("发现新版本 ${info.latestVersion}")
                                            } else {
                                                ToastBus.info("已是最新版本 ${info.currentVersion}")
                                            }
                                        }
                                        .onFailure {
                                            ToastBus.error("检查失败，请检查网络")
                                        }
                                }
                            }
                        },
                        onDownloadUpdate = {
                            val info = updateInfo
                            val url = info?.downloadUrl
                            if (url.isNullOrBlank()) {
                                // 没有对应平台的安装包附件，退回 Release 页面让用户自己选
                                ToastBus.warn("没有找到安装包，已打开更新页面")
                                openExternalUrl(UpdateChecker.RELEASES_URL)
                            } else {
                                // 存到 ~/.furagent/downloads，文件名沿用 Release 附件名
                                val dir = DesktopPaths.receivedDir()
                                val name = url.substringAfterLast('/').ifBlank {
                                    "FurAgent-${info.latestVersion}.exe"
                                }
                                val target = File(dir, name)
                                DownloadManager.download(url, target.absolutePath)
                                ToastBus.info("开始下载 $name")
                            }
                        }
                    )
                }
            }
        }
    }

    if (showSearch) {
        SearchPage(
            onDismiss = { showSearch = false },
            onJumpTo = { sessionId, messageId ->
                if (sessionId != currentSessionId) {
                    switchSession(sessionId)
                }
                currentPage = AppPage.CHAT
                scrollToMessageId = messageId
            }
        )
    }

    renamingSessionId?.let { id ->
        val current = sessionList.firstOrNull { it.id == id }?.title ?: ""
        InputDialog(
            title = "重命名会话", initial = current,
            onDismiss = { renamingSessionId = null },
            onConfirm = { newTitle ->
                DesktopSessionStore.load(id)?.let { s ->
                    // 手动重命名置 titleLocked = true，persist 不再用 generateTitle 覆盖
                    DesktopSessionStore.save(
                        s.copy(title = newTitle, titleLocked = true, updatedAt = System.currentTimeMillis())
                    )
                }
                sessionList = DesktopSessionStore.list().filter { it.parentId == null }
                renamingSessionId = null
            }
        )
    }

    editingMessageId?.let { id ->
        var draft by remember(id) { mutableStateOf(editingContent) }
        GlassDialog(
            title = "编辑消息",
            onDismiss = { editingMessageId = null },
            width = 600.dp,
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = false, minLines = 6, maxLines = 14
                    )
                }
            },
            actions = {
                TextButton(onClick = { editingMessageId = null }) {
                    Text("取消", color = TextSecondary())
                }
                TextButton(onClick = {
                    val idx = messages.indexOfFirst { it.id == id }
                    if (idx >= 0 && draft.isNotBlank()) {
                        val truncated = messages.take(idx) +
                                DesktopMessage(role = "user", content = draft)
                        messages = truncated
                        persist()
                        editingMessageId = null
                        runAgent(truncated)
                    } else {
                        editingMessageId = null
                    }
                }) {
                    Text("保存并重新生成", color = AccentColor(), fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    // 体验优化 清空全部会话二次确认
    if (pendingClearAllSessions) {
        ConfirmDialog(
            title = "清空全部会话",
            message = "将删除全部 ${sessionList.size} 个会话（不可恢复），当前会话将重置为新会话。是否继续？",
            confirmText = "清空全部", danger = true,
            onConfirm = {
                pendingClearAllSessions = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        DesktopSessionStore.list().forEach { meta ->
                            DesktopSessionStore.delete(meta.id)
                        }
                        // 会话没了，摘要状态里的孤儿脏标记一起清
                        SessionSummarizer.pruneState()
                    }
                    sessionList = DesktopSessionStore.list().filter { it.parentId == null }
                    startNewSession()
                    ToastBus.info("已清空全部会话")
                }
            },
            onDismiss = { pendingClearAllSessions = false }
        )
    }

    pendingDeleteSessionId?.let { id ->
        val title = sessionList.firstOrNull { it.id == id }?.title ?: "该会话"
        ConfirmDialog(
            title = "删除会话",
            message = "确定删除「$title」？此操作不可恢复。",
            confirmText = "删除", danger = true,
            onConfirm = {
                // 删会话前先停掉它的 Agent 任务。
                // 之前直接 delete，正在跑的 sessionJobs[id] 不管，
                // 导致“删了会话 AI 还在继续执行工具”。
                stopSession(id)
                DesktopSessionStore.delete(id)
                // 会话删了，它的记忆摘要和摘要状态也要一起清
                SessionSummarizer.pruneState()
                sessionList = DesktopSessionStore.list().filter { it.parentId == null }
                if (id == currentSessionId) {
                    val next = sessionList.firstOrNull()
                    if (next != null) switchSession(next.id) else startNewSession()
                }
                pendingDeleteSessionId = null
            },
            onDismiss = { pendingDeleteSessionId = null }
        )
    }

    exportSessionId?.let { sid ->
        GlassDialog(
            title = "导出会话",
            onDismiss = { exportSessionId = null },
            width = 400.dp,
            content = { Text("是否包含工具调用与工具返回？", fontSize = 13.sp, color = TextSecondary()) },
            actions = {
                TextButton(onClick = {
                    val s = DesktopSessionStore.load(sid)
                    if (s == null) {
                        ToastBus.error("会话已不存在")
                    } else {
                        val f = DesktopSessionStore.exportMarkdown(s, hideToolMessages = true)
                        if (f != null) ToastBus.info("已导出：${f.absolutePath}")
                        else ToastBus.error("导出失败")
                    }
                    exportSessionId = null
                }) { Text("不包含", color = TextSecondary()) }
                TextButton(onClick = {
                    val s = DesktopSessionStore.load(sid)
                    if (s == null) {
                        ToastBus.error("会话已不存在")
                    } else {
                        val f = DesktopSessionStore.exportMarkdown(s, hideToolMessages = false)
                        if (f != null) ToastBus.info("已导出：${f.absolutePath}")
                        else ToastBus.error("导出失败")
                    }
                    exportSessionId = null
                }) { Text("包含", color = AccentColor(), fontWeight = FontWeight.SemiBold) }
            }
        )
    }

    pendingImportFile?.let { file ->
        GlassDialog(
            title = "导入方式",
            onDismiss = { pendingImportFile = null },
            width = 400.dp,
            content = {
                Column {
                    Text("文件：${file.name}", fontSize = 12.sp, color = TextMuted())
                    Spacer(Modifier.height(8.dp))
                    Text("追加导入：保留现有会话。", fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("覆盖导入：清空现有会话后再导入。", fontSize = 13.sp, color = ErrorColor())
                }
            },
            actions = {
                TextButton(onClick = { pendingImportFile = null }) { Text("取消", color = TextSecondary()) }
                TextButton(onClick = { pendingOverwriteFile = file; pendingImportFile = null }) { Text("覆盖", color = ErrorColor()) }
                TextButton(onClick = {
                    pendingImportFile = null
                    doImport(file, replaceExisting = false)
                }) { Text("追加", color = AccentColor(), fontWeight = FontWeight.SemiBold) }
            }
        )
    }

    pendingOverwriteFile?.let { file ->
        ConfirmDialog(
            title = "确认覆盖？",
            message = "这会清空当前所有会话，然后从 ${file.name} 导入。此操作不可恢复。",
            confirmText = "确认覆盖", danger = true,
            onConfirm = {
                pendingOverwriteFile = null
                doImport(file, replaceExisting = true)
            },
            onDismiss = { pendingOverwriteFile = null }
        )
    }

    val pendingConfirm by DesktopConfirmation.pending.collectAsState()
    pendingConfirm?.let { p ->
        ConfirmDialog(
            title = p.title, message = p.message,
            confirmText = "允许", dismissText = "拒绝",
            onConfirm = { DesktopConfirmation.resolve(true) },
            onDismiss = { DesktopConfirmation.resolve(false) }
        )
    }

    // 首次运行功能导览。放在最外层，盖住所有其他 UI。
    if (showOnboardingGuide) {
        OnboardingGuideDialog(
            onFinished = {
                OnboardingStore.markGuideDone()
                showOnboardingGuide = false
            }
        )
    }
}

private suspend fun reExecuteToolCall(
    msg: DesktopMessage,
    messages: List<DesktopMessage>,
    onMessagesChanged: (List<DesktopMessage>) -> Unit,
    infoCallback: (String) -> Unit
) {
    val firstLine = msg.content.substringBefore('\n').trim()
    val argsPart = msg.content.substringAfter('\n', "").trim()
    if (firstLine.isBlank()) {
        infoCallback("无法解析工具调用")
        return
    }

    // 与安卓端对齐 元工具不支持再次执行：
    //   - tool_search / tool_load 是分层工具系统入口，只在本轮 tools 数组里动态下发
    //   - 反查失败后 fallback 会把显示名当工具名，导致污染 loadedToolNames
    if (firstLine == "工具搜索" || firstLine == "tool_search") {
        infoCallback("工具搜索不支持再次执行")
        return
    }
    if (firstLine == "加载工具" || firstLine == "tool_load") {
        infoCallback("加载工具不支持再次执行")
        return
    }

    val toolName = DesktopTools.getToolList().firstOrNull { it.displayName == firstLine }?.name
        ?: DesktopTools.getToolList().firstOrNull { it.name == firstLine }?.name
        ?: firstLine

    val args = try {
        if (argsPart.isBlank()) JSONObject() else JSONObject(argsPart)
    } catch (e: Exception) {
        infoCallback("参数解析失败：${e.message}")
        return
    }

    val result = try {
        withContext(Dispatchers.IO) {
            DesktopTools.execute(toolName, args.toString())
        }
    } catch (e: Exception) {
        DesktopLogger.error("Agent", "重执行工具 $toolName 异常：${e.message}")
        infoCallback("执行失败：${e.message}")
        return
    }

    val resultMsg = DesktopMessage(
        role = "tool",
        content = result.output.take(3000),
        type = "tool_result",
        toolCallId = msg.toolCallId.ifBlank { "reexec_${System.currentTimeMillis()}" }
    )
    onMessagesChanged(messages + resultMsg)
    infoCallback(if (result.success) "已执行：${DesktopTools.displayName(toolName)}"
    else "失败：${result.output.take(200)}")
}

fun deleteMessagePair(history: List<DesktopMessage>, index: Int): List<DesktopMessage> {
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

private fun compressImage(src: File, quality: Int, maxDim: Int): File? {
    return try {
        val img = ImageIO.read(src) ?: return null
        val scale = minOf(maxDim.toFloat() / img.width, maxDim.toFloat() / img.height, 1f)
        val newW = (img.width * scale).toInt().coerceAtLeast(1)
        val newH = (img.height * scale).toInt().coerceAtLeast(1)
        val buffered = java.awt.image.BufferedImage(newW, newH, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = buffered.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(img, 0, 0, newW, newH, null)
        g.dispose()

        // 临时文件落点 统一落 ~/.furagent/temp/，与 TOAI §21.1 一致。
        // 旧实现落 src.parentFile（用户图片目录），会在用户目录里堆 cmp_*.jpg。
        // 前缀 furagent_ 在 TempCleaner 白名单里，7 天后自动清理。
        val tmpDir = DesktopPaths.tempDir()
        val dst = File(
            tmpDir,
            "furagent_cmp_${System.currentTimeMillis()}_${src.nameWithoutExtension}.jpg"
        )
        val writers = ImageIO.getImageWritersByFormatName("jpg")
        if (writers.hasNext()) {
            val writer = writers.next()
            val ios = ImageIO.createImageOutputStream(dst)
            writer.output = ios
            val param = writer.defaultWriteParam
            param.compressionMode = ImageWriteParam.MODE_EXPLICIT
            param.compressionQuality = (quality / 100f).coerceIn(0.1f, 1f)
            writer.write(null, IIOImage(buffered, null, null), param)
            ios.close(); writer.dispose()
        } else ImageIO.write(buffered, "jpg", dst)
        if (dst.exists() && dst.length() > 0) dst else null
    } catch (_: Exception) { null }
}