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
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.xinkong.furagent.desktop.bridge.BridgeServer
import net.xinkong.furagent.desktop.bridge.HttpFileServer
import net.xinkong.furagent.desktop.core.*
import net.xinkong.furagent.desktop.core.mcp.McpManager
import net.xinkong.furagent.desktop.data.*
import net.xinkong.furagent.desktop.system.*
import net.xinkong.furagent.desktop.tools.DesktopTools
import net.xinkong.furagent.desktop.ui.*
import org.json.JSONObject
import java.awt.Toolkit
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.system.exitProcess

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
 * 【全局崩溃处理】
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
    // 【EDT 弹框修复】AWT 的 EDT 不走 setDefaultUncaughtExceptionHandler，
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
            val header = "[${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date())}] " +
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
    // 【关键】最先装全局崩溃处理器，拦住 JVM 原生错误弹窗
    installGlobalCrashHandler()

    // 【关键】必须在 application { } 之前设置渲染后端。
    //
    // 【为什么默认 OPENGL 而不是 DIRECT3D】
    // 曾经默认 DIRECT3D（D3D11），因为 Intel 核显（UHD 620 等）的 OpenGL 驱动
    // 在窗口 resize 时有经典 bug：front/back buffer 不同步 → 画面错位/花屏/残影。
    //
    // 但 D3D 在多显卡环境下会崩：当机器上装了虚拟显示适配器
    // （MuMu 模拟器 / GameViewer 等），Skiko 的 Direct3DRedrawer 可能选错适配器，
    // swap 时抛 EXCEPTION_ACCESS_VIOLATION（code 3221225477）→ 画面崩溃。
    // 实测：装了 MuMu + GameViewer 虚拟适配器的机器上，强制 D3D 必崩。
    //
    // OpenGL 后端对多显卡/虚拟适配器更宽容（走系统默认适配器，通常是独显），
    // 且花屏 bug 只影响 Intel 核显，独显机器不受影响。
    //
    // 用户遇到问题可设环境变量 FURAGENT_RENDER 切换：
    //   OPENGL   / DIRECT3D / SOFTWARE
    // SOFTWARE 是 100% 稳定的兜底（CPU 占用高）。
    val renderApi = System.getenv("FURAGENT_RENDER")?.takeIf { it.isNotBlank() } ?: "OPENGL"
    System.setProperty("skiko.renderApi", renderApi)

    // 【抑制 Skiko 原生崩溃弹窗】
    // Skiko 在渲染线程崩溃时会弹一个 Windows 原生 MessageBox（只显示一个日志路径），
    // 用户点“确定”后进程就退出。这里关掉它的弹窗行为，让异常走我们的日志。
    // 不同 Skiko 版本属性名不同，全部设一遍，不存在的属性会被忽略。
    try { System.setProperty("skiko.win.exception.handler", "") } catch (_: Throwable) { }
    try { System.setProperty("skiko.exception.handler", "") } catch (_: Throwable) { }
    try { System.setProperty("skiko.crash.report", "false") } catch (_: Throwable) { }
    try { System.setProperty("skiko.show.crash.dialog", "false") } catch (_: Throwable) { }

    application {
        LaunchedEffect(Unit) {
            // 【首次运行】自动创建所有目录和初始配置文件
            // 【P2-9 修复】实现已移到 data/ConfigBootstrap.kt
            ConfigBootstrap.ensureAllConfigFiles()

            val s = DesktopSettings.load()
            DesktopLogger.init()
            net.xinkong.furagent.shared.tools.ToolLogger.setHook { tag, msg ->
                DesktopLogger.info(tag, msg)
            }
            DesktopSettingsHolder.current = s
            HttpFileServer.start(s.port + 1)
            BridgeServer.start(s.port)
            TaskScheduler.start()
            McpManager.startAll()
            // 启动时按设置清理 temp 目录（只删程序生成的、超过 N 天的文件）
            TempCleaner.cleanupOnStartup(s.tempCleanupDays)
            // 首次运行时创建示例脚本，让用户知道脚本目录怎么用
            SkillStore.ensureExample()
            // 屏幕控制（UIA）。内部异步探活，不阻塞启动。
            UiaScreenController.init()

            // 【P1-2】会话摘要：每天首次打开时异步跑一次（不阻塞 UI）
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

        Window(
            onCloseRequest = { shutdownAndExit() },
            title = "FurAgent Desktop",
            undecorated = true,
            state = rememberWindowState(width = 1280.dp, height = 820.dp),
            onPreviewKeyEvent = { event ->
                event.type == KeyEventType.KeyDown && event.isCtrlPressed && when (event.key) {
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
                val screenSize = Toolkit.getDefaultToolkit().screenSize
                if (!isMaximized) {
                    window.setSize(screenSize.width, screenSize.height)
                    window.setLocation(0, 0)
                    isMaximized = true
                } else {
                    window.setSize(1280, 820)
                    window.setLocation((screenSize.width - 1280) / 2, (screenSize.height - 820) / 2)
                    isMaximized = false
                }
            }

            DisposableEffect(Unit) {
                DesktopNotifier.setActions(
                    DesktopNotifier.TrayActions(
                        onShowWindow = {
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
                                onClose = { quitApp() }
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

        // 独立的置顶悬浮窗，显示 AI 运行状态
        FloatingStatusWindow(
            visible = settings.floatingWindowEnabled,
            themeMode = settings.themeMode,
            onOpenMainWindow = { MainWindowHolder.activate() },
        )
    }
}

@Composable
fun App(settings: DesktopSettings, onSettingsChange: (DesktopSettings) -> Unit) {
    var messages by remember { mutableStateOf<List<DesktopMessage>>(emptyList()) }
    var currentSessionId by remember { mutableStateOf<String?>(null) }
    var sessionCreatedAt by remember { mutableStateOf(0L) }
    var sessionList by remember { mutableStateOf<List<DesktopSessionMeta>>(emptyList()) }
    // 【多会话并发】运行状态按会话隔离，切会话不互相干扰。
    // runningSessions：哪些会话正在跑；sessionThinking/sessionStreaming：各会话的中间态。
    var runningSessions by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sessionThinking by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var sessionReasoning by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var sessionStreaming by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // 【多会话并发】运行中会话的最新消息列表（供悬浮窗显示非当前会话的工具名/进度）。
    var sessionMessages by remember { mutableStateOf<Map<String, List<DesktopMessage>>>(emptyMap()) }
    var inputText by remember { mutableStateOf("") }

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
    // 【体验优化 B17】清空全部会话的二次确认
    var pendingClearAllSessions by remember { mutableStateOf(false) }
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    var editingContent by remember { mutableStateOf("") }

    var showSearch by remember { mutableStateOf(false) }
    var scrollToMessageId by remember { mutableStateOf<String?>(null) }

    // 【多会话并发】每个会话一个 Job / token，互不干扰。
    var sessionJobs by remember { mutableStateOf<Map<String, Job>>(emptyMap()) }
    var sessionTokens by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }

    val scope = rememberCoroutineScope()

    // 当前会话的派生状态（UI 用）
    val isLoading = currentSessionId != null && currentSessionId in runningSessions
    val thinking = currentSessionId?.let { sessionThinking[it] } ?: ""
    val reasoning = currentSessionId?.let { sessionReasoning[it] } ?: ""
    val streamingText = currentSessionId?.let { sessionStreaming[it] } ?: ""

    // ============================================================
    // 局部函数：先定义，后使用
    // ============================================================

    /** 停掉指定会话的 Agent 任务。 */
    fun stopSession(sessionId: String) {
        // 递增该会话的 token，让旧任务的 isCurrent() 变 false
        sessionTokens = sessionTokens + (sessionId to ((sessionTokens[sessionId] ?: 0L) + 1))
        sessionJobs[sessionId]?.cancel()
        sessionJobs = sessionJobs - sessionId
        // 【中断修复】杀掉正在跑的工具进程（execute_command / ffmpeg / python 等）。
        // 光 cancel 协程只能停住“下一轮”，正在阻塞执行的子进程不会自己退出，
        // 必须主动触发 TaskRuntime 的取消回调（内部会 destroyForcibly + taskkill）。
        try {
            TaskRuntime.tasks.value.filter { it.running }.forEach { TaskRuntime.cancel(it.id) }
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
        createdAt: Long = sessionCreatedAt
    ) {
        val id = sessionId ?: return
        val title = DesktopSessionStore.generateTitle(history)
        DesktopSessionStore.save(
            DesktopSession(
                id = id,
                title = title,
                createdAt = createdAt,
                updatedAt = System.currentTimeMillis(),
                messages = history
            )
        )
        sessionList = DesktopSessionStore.list()
    }

    fun startNewSession() {
        val s = DesktopSessionStore.newSession()
        currentSessionId = s.id
        sessionCreatedAt = s.createdAt
        messages = emptyList()
        sessionList = DesktopSessionStore.list()
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
                sessionList = DesktopSessionStore.list()
                ToastBus.info("已复制会话「${result.title}」")
            } else {
                ToastBus.error("复制失败：会话不存在")
            }
        }
    }

    fun pickAndImportSingleSession() {
        val chooser = JFileChooser().apply {
            dialogTitle = "选择要导入的会话（JSON / Markdown）"
            fileFilter = FileNameExtensionFilter("会话文件 (*.json, *.md)", "json", "md")
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return
        val f = chooser.selectedFile ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                DesktopSessionStore.importSingleSession(f)
            }
            if (result.success) {
                sessionList = DesktopSessionStore.list()
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
        sessionMessages = sessionMessages + (taskSessionId to history)

        val job = scope.launch {
            fun isCurrent() = sessionTokens[taskSessionId] == myToken
            try {
                val result = DesktopAgent.runTurn(
                    settings = settings,
                    history = history,
                    onUpdate = { newList ->
                        if (!isCurrent()) return@runTurn
                        persist(taskSessionId, newList, taskCreatedAt)
                        sessionMessages = sessionMessages + (taskSessionId to newList)
                        if (currentSessionId == taskSessionId) {
                            messages = newList
                        }
                    },
                    onThinking = { t ->
                        if (!isCurrent()) return@runTurn
                        sessionThinking = sessionThinking + (taskSessionId to t)
                    },
                    onReasoning = { r ->
                        if (!isCurrent()) return@runTurn
                        sessionReasoning = sessionReasoning + (taskSessionId to ((sessionReasoning[taskSessionId] ?: "") + r))
                    },
                    onStream = { chunk ->
                        if (!isCurrent()) return@runTurn
                        sessionStreaming = sessionStreaming + (taskSessionId to ((sessionStreaming[taskSessionId] ?: "") + chunk))
                    },
                    onStreamCommit = {
                        if (!isCurrent()) return@runTurn
                        // reasoning 已随 assistant 消息落库，清空运行时状态避免与消息内面板重复
                        sessionStreaming = sessionStreaming + (taskSessionId to "")
                        sessionReasoning = sessionReasoning + (taskSessionId to "")
                    }
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
            } catch (e: Exception) {
                DesktopLogger.error(
                    "Agent",
                    "runAgent 任务异常：${e.javaClass.simpleName}: ${e.message}\n" +
                            e.stackTrace.take(5).joinToString("\n") { "  at $it" }
                )
            } finally {
                if (isCurrent()) {
                    runningSessions = runningSessions - taskSessionId
                    sessionStreaming = sessionStreaming - taskSessionId
                    sessionThinking = sessionThinking - taskSessionId
                    sessionReasoning = sessionReasoning - taskSessionId
                    sessionMessages = sessionMessages - taskSessionId
                }
                sessionJobs = sessionJobs - taskSessionId
            }
        }
        sessionJobs = sessionJobs + (taskSessionId to job)
    }

    fun send(attachments: List<PendingAttachment>) {
        val text = inputText.trim()
        if (text.isBlank() && attachments.isEmpty()) return
        // 【多会话并发】只拦当前会话正在跑的情况；其他会话在跑不影响本会话发消息。
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
        sessionList = DesktopSessionStore.list()
        if (currentSessionId == null || sessionList.none { it.id == currentSessionId }) {
            val first = sessionList.firstOrNull()
            if (first != null) switchSession(first.id) else startNewSession()
        }
        if (result.settingsRestored && result.restoredSettings != null) onSettingsChange(result.restoredSettings)
        ToastBus.info("导入成功：${result.sessionCount} 个会话")
    }

    fun pickAndImport() {
        val chooser = JFileChooser().apply {
            dialogTitle = "选择 FurAgent 备份文件"
            fileFilter = FileNameExtensionFilter("JSON 备份 (*.json)", "json")
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return
        pendingImportFile = chooser.selectedFile ?: return
    }

    // ============================================================
    // 副作用
    // ============================================================

    // 【多会话并发】同一会话从“跑”变“不跑”时激活窗口（任务完成提醒）。
    // 记录上次的 (会话, 是否在跑)，切会话时不误触发。
    var lastRunningSession by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentSessionId, isLoading) {
        if (lastRunningSession == currentSessionId && !isLoading) {
            MainWindowHolder.activate()
        }
        lastRunningSession = if (isLoading) currentSessionId else null
    }

    // 【多会话并发】把运行中的会话汇总上报给 AgentRuntimeState（供悬浮窗读取）。
    // 每个会话一份快照，工具名/工具数从 sessionMessages 取（运行中的会话都有缓存）。
    LaunchedEffect(runningSessions, sessionThinking, sessionStreaming, sessionMessages, currentSessionId, sessionList) {
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

    // ============================================================
    // UI
    // ============================================================

    Row(Modifier.fillMaxSize()) {
        Sidebar(
            expanded = sidebarExpanded,
            currentPage = currentPage,
            sessions = sessionList,
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
            onClearAllSessions = { pendingClearAllSessions = true }
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
                        isLoading = isLoading,
                        inputText = inputText,
                        hasApiKey = settings.currentProfile()?.apiKey?.isNotBlank() == true,
                        modelName = settings.currentProfile()?.name ?: "未配置模型",
                        enableThinking = settings.enableThinking,
                        supportsThinking = settings.currentProfile()?.supportsThinking ?: true,
                        chatProfiles = settings.chatProfiles().map { it.id to it.name },
                        currentProfileId = settings.currentProfileId,
                        onToggleThinking = { on ->
                            onSettingsChange(settings.copy(enableThinking = on))
                        },
                        onSelectProfile = { id ->
                            onSettingsChange(settings.copy(currentProfileId = id))
                        },
                        scrollToMessageId = scrollToMessageId,
                        onScrollToMessageHandled = { scrollToMessageId = null },
                        onInputChange = { inputText = it },
                        onSend = { atts -> send(atts) },
                        onStop = { stopAgent() },
                        onOpenSettings = { currentPage = AppPage.SETTINGS },
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
                        onImport = { pickAndImport() }
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
                    DesktopSessionStore.save(s.copy(title = newTitle, updatedAt = System.currentTimeMillis()))
                }
                sessionList = DesktopSessionStore.list()
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

    // 【体验优化 B17】清空全部会话二次确认
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
                    }
                    sessionList = DesktopSessionStore.list()
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
                DesktopSessionStore.delete(id)
                sessionList = DesktopSessionStore.list()
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

        // 【临时文件落点】统一落 ~/.furagent/temp/，与 TOAI §21.1 一致。
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