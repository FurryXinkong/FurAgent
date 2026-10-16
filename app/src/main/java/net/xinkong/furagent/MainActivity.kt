package net.xinkong.furagent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.xinkong.furagent.bridge.AndroidHttpFileServer
import net.xinkong.furagent.bridge.PcBridgeClient
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.core.AgentRunner
import net.xinkong.furagent.core.ConfirmationBroker
import net.xinkong.furagent.core.mcp.McpManager
import net.xinkong.furagent.data.*
import net.xinkong.furagent.service.FloatingService
import net.xinkong.furagent.system.LocalNetworkPermission
import net.xinkong.furagent.system.PermissionRequestBroker
import net.xinkong.furagent.system.SpeechHelper
import net.xinkong.furagent.system.notifyToast
import net.xinkong.furagent.tools.FfmpegHelper
import net.xinkong.furagent.tools.SDCARD_PATH
import net.xinkong.furagent.tools.ToolRegistry
import net.xinkong.furagent.ui.*
import net.xinkong.furagent.ui.pages.*
import net.xinkong.furagent.ui.theme.FurAgentTheme
import net.xinkong.furagent.shared.util.ChatListItem
import net.xinkong.furagent.shared.util.buildChatItems
import org.json.JSONObject
import java.io.File
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "MainActivity"

/**
 * 整屏 Page 列表。点击后盖在聊天界面之上，系统返回键或左上角返回按钮可关闭。
 */
enum class AppPage {
    MEMORY,
    SETTINGS,
    ADVANCED_SETTINGS,
    LOG,
    PERMISSIONS,
    SESSIONS,
    SCHEDULES,
    PC_HUB,
    MCP,
    ENV_STORE
}

class MainActivity : ComponentActivity() {
    private val themeState = mutableStateOf("system")

    private val shizukuReceivedListener = rikka.shizuku.Shizuku.OnBinderReceivedListener {
        AgentLogger.log("SYS", "Shizuku 已连接")
    }
    private val shizukuDeadListener = rikka.shizuku.Shizuku.OnBinderDeadListener {
        AgentLogger.log("SYS", "Shizuku 已断开")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentLogger.init(applicationContext)
        McpManager.init(applicationContext)
        // 注入 Context 给 shared 模块的 WebView 渲染器（网页抓取用）
        net.xinkong.furagent.shared.util.AndroidContextHolder.init(applicationContext)

        net.xinkong.furagent.shared.tools.ToolLogger.setHook { tag, msg ->
            AgentLogger.log(tag, msg)
        }

        setupGlobalCrashHandler()
        enableEdgeToEdge()
        val prefs = getSharedPreferences("FurAgentPrefs", MODE_PRIVATE)
        themeState.value = prefs.getString("theme_mode", "system") ?: "system"

        AppContextHolder.set(applicationContext)
        TokenStats.init(applicationContext)
        SpeechHelper.init(applicationContext)

        try {
            val settings = SettingsStore.load(prefs)
            AndroidHttpFileServer.setReceiveDir(settings.pcReceiveDir)
            AndroidHttpFileServer.setPathPolicy(settings.allowedPaths, settings.blockedPaths)
            AndroidHttpFileServer.setFileReceivedCallback { path ->
                try {
                    val file = File(path)
                    // 【B15 改动】带 PendingIntent 打开文件，点击通知直接打开
                    net.xinkong.furagent.system.NotificationHelper.showFileReceivedNotification(
                        applicationContext, file
                    )
                } catch (e: Exception) {
                    AgentLogger.log(
                        "BRIDGE",
                        "接收文件通知失败（$path）：${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }
            val (hp, _) = AndroidHttpFileServer.start(applicationContext, settings.pcBridgePort + 1)
            AgentLogger.log("BRIDGE", "本地 HTTP 文件服务端口：$hp")
        } catch (e: Exception) {
            AgentLogger.log("ERR", "启动本地 HTTP 服务失败：${e.message}")
        }

        try {
            rikka.shizuku.Shizuku.addBinderReceivedListenerSticky(shizukuReceivedListener)
            rikka.shizuku.Shizuku.addBinderDeadListener(shizukuDeadListener)
        } catch (e: Exception) {
            AgentLogger.log(
                "SYS",
                "注册 Shizuku 监听器失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2001)
                } catch (e: Exception) {
                    AgentLogger.log(
                        "SYS",
                        "请求通知权限失败：${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }
        }

        // Android 17（API 37）本地网络保护：在建立桥接连接之前申请。
        // 低版本（< 37）恒视为已授权，不会弹窗。
        if (LocalNetworkPermission.isRequired() && !LocalNetworkPermission.isGranted(this)) {
            LocalNetworkPermission.ensure(this)
        }

        setContent {
            FurAgentTheme(themeMode = themeState.value) {
                GlassBackground {
                    FurAgentMainScreen(
                        onThemeModeChanged = { newMode ->
                            themeState.value = newMode
                            prefs.edit { putString("theme_mode", newMode) }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            FloatingService.setBallVisible(false)
        } catch (e: Exception) {
            AgentLogger.log(
                "SYS",
                "设置悬浮球可见性失败（onResume）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            if (FloatingService.isRunning) FloatingService.setBallVisible(true)
        } catch (e: Exception) {
            AgentLogger.log(
                "SYS",
                "设置悬浮球可见性失败（onPause）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            SpeechHelper.shutdown()
        } catch (e: Exception) {
            AgentLogger.log(
                "SYS",
                "关闭 TTS 失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
        try {
            AndroidHttpFileServer.stop()
        } catch (e: Exception) {
            AgentLogger.log(
                "SYS",
                "停止 HTTP 文件服务失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
        try {
            McpManager.shutdown()
        } catch (e: Exception) {
            AgentLogger.log(
                "SYS",
                "停止 MCP 服务失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
        try {
            rikka.shizuku.Shizuku.removeBinderReceivedListener(shizukuReceivedListener)
            rikka.shizuku.Shizuku.removeBinderDeadListener(shizukuDeadListener)
        } catch (e: Exception) {
            AgentLogger.log(
                "SYS",
                "移除 Shizuku 监听器失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // 【修复 F2】POST_NOTIFICATIONS 的请求码是 2001（见 onCreate），
        // 旧实现只处理了本地网络（2002），漏掉通知权限的授权结果。
        if (requestCode == 2001) {
            val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            AgentLogger.log("SYS", "POST_NOTIFICATIONS 授权结果：$granted")
            if (!granted) {
                notifyToast(this, "未授予通知权限，定时任务提醒和实时通知将不可用")
            }
            return
        }

        if (requestCode == LocalNetworkPermission.REQUEST_CODE) {
            val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            AgentLogger.log("SYS", "ACCESS_LOCAL_NETWORK 授权结果：$granted")
            if (granted) {
                // 授权后立即重连桥接，避免等下一次退避重试
                try {
                    val prefs = getSharedPreferences("FurAgentPrefs", MODE_PRIVATE)
                    val s = SettingsStore.load(prefs)
                    if (s.pcBridgeEnabled && s.pcBridgeHost.isNotBlank()) {
                        PcBridgeClient.connect(this, s.pcBridgeHost, s.pcBridgePort, s)
                    }
                } catch (e: Exception) {
                    AgentLogger.log(
                        "SYS",
                        "授权后重连桥接失败：${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            } else {
                notifyToast(this, "未授予本地网络权限，将无法连接电脑或局域网设备")
            }
        }
    }

    private fun setupGlobalCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stackTrace = Log.getStackTraceString(throwable)
                CrashStore.save(this, stackTrace)
                AgentLogger.log("CRASH", stackTrace)
            } catch (e: Exception) {
                try {
                    Log.e(TAG, "崩溃处理器记录失败：${e.javaClass.simpleName}: ${e.message}")
                } catch (_: Throwable) { }
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1fKB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1fMB", bytes / 1024.0 / 1024.0)
        else -> String.format(Locale.US, "%.2fGB", bytes / 1024.0 / 1024.0 / 1024.0)
    }
}

// ============================================================
// 图片压缩
// ============================================================
private object ImageCompressor {
    suspend fun compress(src: File, quality: Int, maxDim: Int = 1280): File? =
        withContext(Dispatchers.IO) {
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(src.absolutePath, opts)
                if (opts.outWidth <= 0 || opts.outHeight <= 0) return@withContext null

                var sample = 1
                while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) {
                    sample *= 2
                }

                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = BitmapFactory.decodeFile(src.absolutePath, decodeOpts)
                    ?: return@withContext null

                // 【临时文件落点】统一落 /sdcard/FurAgent/temp/，与 TOAI §2.2 一致。
                // 旧实现落 src.parentFile（用户图片目录），会污染用户可见目录。
                val tmpDir = File(SDCARD_PATH, "FurAgent/temp")
                if (!tmpDir.exists()) tmpDir.mkdirs()
                val baseName = src.nameWithoutExtension.ifBlank { "img" }
                val dst = File(tmpDir, "furagent_cmp_${System.currentTimeMillis()}_$baseName.jpg")
                dst.outputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 100), out)
                }
                bmp.recycle()
                if (dst.exists() && dst.length() > 0) dst else null
            } catch (e: Exception) {
                AgentLogger.log(
                    "ERR",
                    "压缩图片失败（${src.absolutePath}）：${e.javaClass.simpleName}: ${e.message}"
                )
                null
            }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FurAgentMainScreen(onThemeModeChanged: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("FurAgentPrefs", Context.MODE_PRIVATE) }
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val sendMutex = remember { Mutex() }

    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeBottomDp = with(density) { imeBottomPx.toDp() }
    val dynamicBottomPadding = 120.dp + imeBottomDp

    val agentSnapshot by AgentRunner.snapshot.collectAsState()

    var currentSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var chatHistory by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var sessionList by remember { mutableStateOf<List<SessionMeta>>(emptyList()) }
    var userInput by rememberSaveable { mutableStateOf("") }

    val isLoading = agentSnapshot.isRunning && agentSnapshot.sessionId == currentSessionId
    val streamingText = if (agentSnapshot.sessionId == currentSessionId) agentSnapshot.streaming else ""
    val currentThinking = if (agentSnapshot.sessionId == currentSessionId) agentSnapshot.thinking else ""
    val currentReasoning = if (agentSnapshot.sessionId == currentSessionId) agentSnapshot.reasoning else ""

    // ============================================================
    // 整屏 Page 导航
    // 短交互（更多功能面板、关于）保留 boolean。
    // ============================================================
    var currentPage by rememberSaveable { mutableStateOf<AppPage?>(null) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showMorePanel by rememberSaveable { mutableStateOf(false) }

    // 系统返回键：有 Page 就关 Page，没有就交给系统
    BackHandler(enabled = currentPage != null) {
        currentPage = null
    }

    var maxTurns by remember { mutableIntStateOf(prefs.getInt("max_turns", 5)) }
    var crashInfo by remember { mutableStateOf(CrashStore.load(context)) }
    var selectedMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var renamingSession by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(SettingsStore.load(prefs)) }
    var themeMode by remember { mutableStateOf(prefs.getString("theme_mode", "system") ?: "system") }
    var exportSessionId by remember { mutableStateOf<String?>(null) }
    var pendingImportJson by remember { mutableStateOf<String?>(null) }
    var pendingOverwriteJson by remember { mutableStateOf<String?>(null) }
    var pendingDeleteSessionId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteMessageId by remember { mutableStateOf<String?>(null) }
    var currentSessionCreatedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var pendingPickedImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var viewingImage by remember { mutableStateOf<String?>(null) }

    var autoChatMode by rememberSaveable { mutableStateOf(false) }
    var voiceTriggerCounter by remember { mutableIntStateOf(0) }
    var voiceFailStreak by remember { mutableIntStateOf(0) }
    var lastIsSpeaking by remember { mutableStateOf(false) }
    var ttsErrorShown by remember { mutableStateOf(false) }

    var floatingOn by remember { mutableStateOf(FloatingService.isRunning) }

    val currentProfileValue = remember(settings.currentProfileId, settings.profiles) {
        settings.currentProfile()
    }
    val isSpeaking by SpeechHelper.isSpeaking.collectAsState()

    val chatItems = remember(chatHistory) { buildChatItems(chatHistory) }

    // ============================================================
    // 【新增】首次运行引导
    // 两个独立标记，可分别跳过：权限引导 / 功能导览。
    // ============================================================
    var onboardingStage by remember {
        val permDone = prefs.getBoolean("onboarding_permissions_done", false)
        val guideDone = prefs.getBoolean("onboarding_guide_done", false)
        mutableStateOf(
            when {
                !permDone -> "permissions"
                !guideDone -> "guide"
                else -> "done"
            }
        )
    }

    LaunchedEffect(currentSessionId) {
        if (currentSessionId != null) {
            prefs.edit { putString("current_session_id", currentSessionId) }
        }
    }

    LaunchedEffect(agentSnapshot.sessionId, agentSnapshot.history) {
        if (agentSnapshot.sessionId.isNotBlank() &&
            agentSnapshot.sessionId == currentSessionId &&
            agentSnapshot.history.isNotEmpty()
        ) {
            chatHistory = agentSnapshot.history
        }
    }

    fun persist() {
        val id = currentSessionId ?: return
        val title = SessionStore.generateTitle(chatHistory)
        SessionStore.save(
            context,
            Session(
                id = id, title = title,
                createdAt = currentSessionCreatedAt,
                updatedAt = System.currentTimeMillis(),
                messages = chatHistory
            )
        )
        sessionList = SessionStore.list(context)
    }

    fun interruptAgent() {
        if (streamingText.isNotBlank()) {
            chatHistory = chatHistory + ChatMessage(
                role = "assistant", content = streamingText, interrupted = true
            )
            persist()
        }
        AgentRunner.cancel(context)
        FfmpegHelper.cancelAll()
    }

    fun stopAgent() {
        val wasStreaming = streamingText.isNotBlank()
        interruptAgent()
        if (wasStreaming) {
            chatHistory = chatHistory + ChatMessage(
                role = "system", content = "已停止生成", type = "system_note"
            )
            persist()
        }
    }

    fun startNewSession() {
        if (isLoading) interruptAgent()
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        SessionStore.save(context, Session(id, "新会话", now, now, emptyList()))
        currentSessionId = id
        currentSessionCreatedAt = now
        chatHistory = emptyList()
        sessionList = SessionStore.list(context)
    }

    fun switchToSession(id: String) {
        if (isLoading) interruptAgent()
        currentSessionId = id
        val loaded = SessionStore.load(context, id)
        chatHistory = loaded?.messages ?: emptyList()
        currentSessionCreatedAt = loaded?.createdAt ?: System.currentTimeMillis()
    }

    fun duplicateSession(id: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                SessionStore.duplicate(context, id)
            }
            if (result != null) {
                sessionList = SessionStore.list(context)
                notifyToast(context, "已复制会话「${result.title}」")
            } else {
                notifyToast(context, "复制失败：会话不存在")
            }
        }
    }

    val sessionImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val text = inputStream?.bufferedReader()?.use { it.readText() }
                if (text.isNullOrBlank()) {
                    notifyToast(context, "读取文件失败")
                    return@launch
                }
                // 【P1-4】保留原文件后缀，供 importSingleSession 分流（.md / .json）
                val ext = context.contentResolver.getType(uri)
                    ?.let { if (it.contains("markdown")) "md" else null }
                    ?: uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
                    ?: "json"
                val tmp = File(context.cacheDir, "import_session_${System.currentTimeMillis()}.$ext")
                tmp.writeText(text, Charsets.UTF_8)
                val result = withContext(Dispatchers.IO) {
                    SessionStore.importSingleSession(context, tmp)
                }
                tmp.delete()
                if (result.success) {
                    sessionList = SessionStore.list(context)
                    result.sessionId?.let { switchToSession(it) }
                    notifyToast(context, "已导入：${result.sessionTitle}（${result.messageCount} 条消息）")
                } else {
                    notifyToast(context, "导入失败：${result.error ?: "未知错误"}")
                }
            } catch (e: Exception) {
                AgentLogger.log("ERR", "导入会话失败：${e.javaClass.simpleName}: ${e.message}")
                notifyToast(context, "导入失败：${e.message}")
            }
        }
    }

    fun importSingleSessionFromFile() {
        try {
            // 【P1-4】放开 MIME，允许选 .md / .json
            sessionImportLauncher.launch("*/*")
        } catch (e: Exception) {
            notifyToast(context, "打开文件选择器失败：${e.message}")
        }
    }

    fun doImport(json: String, replaceExisting: Boolean) {
        try {
            val tmp = File(context.cacheDir, "import_temp.json")
            tmp.writeText(json)
            val importResult = SessionStore.importAll(context, prefs, tmp, replaceExisting)
            tmp.delete()

            if (!importResult.success) {
                notifyToast(context, "文件格式不正确，缺少必要字段")
                return
            }
            notifyToast(
                context,
                "导入成功：${importResult.sessionCount} 个会话，${importResult.memoryCount} 条记忆"
            )

            sessionList = SessionStore.list(context)
            // 【修复】设置恢复后立即同步内存。
            // 旧实现里 SessionStore.importAll 只返回一个 Boolean，调用方需要自己
            // 再 SettingsStore.load(prefs) 一次才能更新内存态。契约隐晦，
            // 换调用点极易漏。现在 importAll 直接返回恢复后的 AppSettings，
            // 契约明确：拿到就用，不依赖调用方记着要刷内存。
            importResult.restoredSettings?.let { restored ->
                settings = restored
                maxTurns = prefs.getInt("max_turns", 5)
                themeMode = prefs.getString("theme_mode", "system") ?: "system"
                onThemeModeChanged(themeMode)
                try {
                    AndroidHttpFileServer.setReceiveDir(restored.pcReceiveDir)
                } catch (e: Exception) {
                    AgentLogger.log(
                        "BRIDGE",
                        "导入后更新接收目录失败：${e.javaClass.simpleName}: ${e.message}"
                    )
                }
                try {
                    AndroidHttpFileServer.setPathPolicy(restored.allowedPaths, restored.blockedPaths)
                } catch (e: Exception) {
                    AgentLogger.log(
                        "BRIDGE",
                        "导入后更新路径策略失败：${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }
            if (sessionList.none { it.id == currentSessionId }) {
                val first = sessionList.firstOrNull()
                if (first != null) {
                    currentSessionId = first.id
                    val loaded = SessionStore.load(context, first.id)
                    chatHistory = loaded?.messages ?: emptyList()
                    currentSessionCreatedAt = loaded?.createdAt ?: System.currentTimeMillis()
                } else startNewSession()
            }
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "导入备份失败：${e.javaClass.simpleName}: ${e.message}"
            )
            notifyToast(context, "导入失败：${e.message}")
        } finally {
            pendingImportJson = null
        }
    }

    fun autoSpeakForce(text: String) {
        val clean = text
            .replace(Regex("""https?://\S+"""), "")
            .replace(Regex("""/\S+"""), "")
            .replace(Regex("""[*_`#>]+"""), "")
            .trim()
        if (clean.isBlank()) {
            scope.launch {
                delay(500.milliseconds)
                if (autoChatMode && !AgentRunner.isRunning()) voiceTriggerCounter++
            }
            return
        }
        val ok = SpeechHelper.speak(clean.take(500))
        if (!ok) {
            if (!ttsErrorShown) {
                ttsErrorShown = true
                notifyToast(context, SpeechHelper.lastError ?: "TTS 不可用，已退出免提模式")
            }
            autoChatMode = false
        }
    }

    fun startAgent(initialHistory: List<ChatMessage>) {
        val profile = currentProfileValue ?: return
        val sid = currentSessionId ?: return
        val started = AgentRunner.start(
            context = context,
            sessionId = sid,
            profile = profile,
            initialHistory = initialHistory,
            maxTurns = maxTurns,
            prefs = prefs,
            settings = settings,
            onFinish = { finalHistory ->
                chatHistory = finalHistory
                persist()
                if (autoChatMode) {
                    val last = finalHistory.lastOrNull {
                        it.role == "assistant" && it.type == "text"
                    }
                    if (last != null && last.content.isNotBlank()) {
                        autoSpeakForce(last.content)
                    } else {
                        scope.launch {
                            delay(500.milliseconds)
                            if (autoChatMode && !AgentRunner.isRunning()) voiceTriggerCounter++
                        }
                    }
                }
            }
        )
        if (!started) notifyToast(context, "已有 AI 任务在运行中，请先停止")
    }

    fun sendMessageInternal(text: String, images: List<String> = emptyList()) {
        if (text.isBlank() && images.isEmpty()) return
        if (currentProfileValue == null || currentProfileValue.apiKey.isBlank()) {
            currentPage = AppPage.SETTINGS
            notifyToast(context, "请先为模型配置 API Key")
            return
        }

        scope.launch {
            sendMutex.withLock {
                if (AgentRunner.isRunning()) {
                    notifyToast(context, "已有 AI 任务在运行中")
                    return@withLock
                }

                val finalImages = if (settings.imageCompress) {
                    val compressed = mutableListOf<String>()
                    for (path in images) {
                        val src = File(path)
                        if (!src.exists()) continue
                        val dst = ImageCompressor.compress(
                            src,
                            quality = settings.imageCompressQuality,
                            maxDim = settings.imageCompressMaxDim
                        )
                        if (dst != null) compressed.add(dst.absolutePath)
                        else compressed.add(path)
                    }
                    compressed
                } else images

                val userMsg = ChatMessage(role = "user", content = text, imagePaths = finalImages)
                val withUser = chatHistory + userMsg
                chatHistory = withUser
                // 【滚动修复】用户主动发消息时无需在此重置上滑锁定：
                // userScrolledUp 是聊天页 Composable 内的局部 remember 状态，本函数作用域访问不到；
                // 且自动滚动逻辑里 shouldFollow = !userScrolledUp || last?.role == "user"，
                // 最后一条是 user 消息时本就会强制跟随滚动，功能上无需额外重置。
                persist()
                userInput = ""
                pendingPickedImages = emptyList()
                keyboardController?.hide()
                focusManager.clearFocus()

                startAgent(withUser)
            }
        }
    }

    fun reExecuteToolCall(msg: ChatMessage) {
        scope.launch {
            val firstLine = msg.content.substringBefore('\n').trim()
            val argsPart = msg.content.substringAfter('\n', "").trim()
            if (firstLine.isBlank()) {
                notifyToast(context, "无法解析工具调用")
                return@launch
            }
            // 【修复】元工具无法"再次执行"：
            //   - tool_search / tool_load 是分层工具系统的入口，只在本轮 tools 数组里动态下发
            //   - 它们不在 ToolRegistry.allToolNames() 里，反查失败会被 fallback 当原文
            //   - 重新执行还会污染当前 loadedToolNames，影响后续对话
            // 直接拒绝并给出清晰提示。
            if (firstLine == "工具搜索" || firstLine == "tool_search") {
                notifyToast(context, "工具搜索不支持再次执行")
                return@launch
            }
            if (firstLine == "加载工具" || firstLine == "tool_load") {
                notifyToast(context, "加载工具不支持再次执行")
                return@launch
            }
            val toolName = ToolRegistry.findToolNameByDisplay(firstLine)
            if (toolName == null) {
                notifyToast(context, "找不到工具：$firstLine")
                return@launch
            }
            val args = try {
                if (argsPart.isBlank()) JSONObject() else JSONObject(argsPart)
            } catch (e: Exception) {
                notifyToast(context, "参数解析失败：${e.message}")
                return@launch
            }
            chatHistory = chatHistory + ChatMessage(
                role = "system", content = "再次执行：$firstLine", type = "system_note"
            )
            persist()
            try {
                val result = withContext(Dispatchers.IO) {
                    ToolRegistry.execute(toolName, args, context, settings)
                }
                chatHistory = chatHistory + ChatMessage(
                    role = "tool", content = result.take(2000), type = "tool_result"
                )
                persist()
            } catch (e: Exception) {
                AgentLogger.log(
                    "ERR",
                    "再次执行工具 $toolName 失败：${e.javaClass.simpleName}: ${e.message}"
                )
                notifyToast(context, "执行失败：${e.message}")
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val paths = mutableListOf<String>()
        uris.forEach { uri ->
            try {
                val ext = context.contentResolver.getType(uri)?.substringAfter("/") ?: "jpg"
                val dst = File(
                    context.cacheDir,
                    "picked_${System.currentTimeMillis()}_${paths.size}.$ext"
                )
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                if (dst.exists()) paths.add(dst.absolutePath)
            } catch (e: Exception) {
                AgentLogger.log(
                    "ERR",
                    "复制图片到缓存失败（$uri）：${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
        pendingPickedImages = pendingPickedImages + paths
    }

    val systemSpeechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cancelled = result.resultCode != android.app.Activity.RESULT_OK
        val data = result.data
        val text = data?.getStringArrayListExtra(
            android.speech.RecognizerIntent.EXTRA_RESULTS
        )?.firstOrNull()

        if (cancelled || text.isNullOrBlank()) {
            if (autoChatMode) {
                voiceFailStreak++
                if (voiceFailStreak >= 3) {
                    autoChatMode = false
                    voiceFailStreak = 0
                    notifyToast(context, "连续 3 次未识别到语音，已退出免提模式")
                } else {
                    scope.launch {
                        delay(1200.milliseconds)
                        if (autoChatMode && !isLoading) voiceTriggerCounter++
                    }
                }
            }
            return@rememberLauncherForActivityResult
        }
        voiceFailStreak = 0
        if (autoChatMode) {
            sendMessageInternal(text)
        } else {
            userInput = text
        }
    }

    fun startVoiceInput() {
        try {
            val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "请说话")
            }
            systemSpeechLauncher.launch(intent)
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "启动语音识别失败：${e.javaClass.simpleName}: ${e.message}"
            )
            notifyToast(context, "语音识别不可用：${e.message}")
        }
    }

    LaunchedEffect(voiceTriggerCounter) {
        if (voiceTriggerCounter > 0 && autoChatMode && !isLoading) {
            startVoiceInput()
        }
    }

    LaunchedEffect(isSpeaking) {
        if (lastIsSpeaking && !isSpeaking) {
            if (autoChatMode && !isLoading) {
                delay(400.milliseconds)
                if (autoChatMode) voiceTriggerCounter++
            }
        }
        lastIsSpeaking = isSpeaking
    }

    LaunchedEffect(autoChatMode) {
        if (!autoChatMode) {
            voiceTriggerCounter = 0
            voiceFailStreak = 0
        } else {
            voiceFailStreak = 0
            if (!isLoading) {
                delay(300.milliseconds)
                voiceTriggerCounter++
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            AndroidHttpFileServer.setPathPolicy(settings.allowedPaths, settings.blockedPaths)
        } catch (e: Exception) {
            AgentLogger.log(
                "BRIDGE",
                "初始化路径策略失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }

        try {
            FloatingService.setBallVisible(false)
        } catch (e: Exception) {
            AgentLogger.log(
                "SYS",
                "初始化悬浮球可见性失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }

        // 首次运行不再跳 PERMISSIONS 页，改由 onboarding overlay 处理。

        val runningSessionId = if (AgentRunner.isRunning()) {
            agentSnapshot.sessionId.takeIf { it.isNotBlank() }
        } else null

        val list = SessionStore.list(context)
        sessionList = list

        val target = when {
            runningSessionId != null && list.any { it.id == runningSessionId } -> runningSessionId
            currentSessionId != null && list.any { it.id == currentSessionId } -> currentSessionId!!
            list.isNotEmpty() -> list.first().id
            else -> null
        }

        if (target != null) {
            currentSessionId = target
            val loaded = SessionStore.load(context, target)
            chatHistory = if (AgentRunner.isRunning() && agentSnapshot.sessionId == target) {
                agentSnapshot.history
            } else {
                loaded?.messages ?: emptyList()
            }
            currentSessionCreatedAt = loaded?.createdAt ?: System.currentTimeMillis()
        } else {
            val id = UUID.randomUUID().toString()
            currentSessionId = id
            chatHistory = emptyList()
            currentSessionCreatedAt = System.currentTimeMillis()
            SessionStore.save(
                context,
                Session(id, "新会话", currentSessionCreatedAt, currentSessionCreatedAt, emptyList())
            )
            sessionList = SessionStore.list(context)
        }

        if (settings.pcBridgeEnabled && settings.pcBridgeHost.isNotBlank()) {
            PcBridgeClient.connect(
                context,
                settings.pcBridgeHost,
                settings.pcBridgePort,
                settings
            )
        }

        try {
            McpManager.startAll()
        } catch (e: Exception) {
            AgentLogger.log(
                "MCP",
                "启动 MCP 服务失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }

        delay(1500.milliseconds)
        if (settings.autoSpeakReplies && !SpeechHelper.isReady()) {
            notifyToast(context, SpeechHelper.lastError ?: "TTS 初始化失败，自动朗读不可用")
        } else if (settings.autoSpeakReplies && SpeechHelper.languageMissing) {
            notifyToast(context, "系统缺少中文语音包，请到系统设置安装")
        }
    }

    var hasAutoShownSettings by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(currentProfileValue?.id, currentProfileValue?.apiKey, currentPage) {
        if (currentPage == AppPage.SETTINGS) return@LaunchedEffect
        // onboarding 未走完时不抢跳到设置页
        if (onboardingStage != "done") return@LaunchedEffect
        if (currentProfileValue == null || currentProfileValue.apiKey.isBlank()) {
            if (!hasAutoShownSettings) {
                currentPage = AppPage.SETTINGS
                hasAutoShownSettings = true
            }
        } else {
            hasAutoShownSettings = false
        }
    }

    // 【修复：用户上滑后不再被强制拉到底】
    // 背景：原来只要 chatItems.size 变化（每次工具调用都会变）就无条件
    // scrollToItem 到底部，用户上滑看历史时会被反复拽回底部，体验极差。
    //
    // 【方案 v2】不再用 isScrollInProgress 猜“用户是否在拖”，因为程序滚动
    // （scrollToItem）也会把它置 true，容易误判。改用两个更可靠的信号：
    //   - listState.canScrollForward == false  → 真的到底了
    //   - listState.isScrollInProgress         → 有滚动在进行
    // 只要“正在滚动”且“没到底”，就认定用户主动上滑，锁定不自动滚。
    // 程序滚动到底后 canScrollForward 变 false，会自动解锁。
    //
    // 【注意】isAtBottom / userScrolledUp 必须声明在下面的自动滚动 LaunchedEffect 之前，
    // 否则 Compose 里会报 “Unresolved reference”。
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.totalItemsCount == 0) return@derivedStateOf true
            // canScrollForward=false 表示已经滚到最底，比“最后可见项 index”更可靠
            !listState.canScrollForward
        }
    }
    var userScrolledUp by remember { mutableStateOf(false) }
    // 【关键】程序滚动标志：scrollToItem 期间置 true，避免把“自动滚到底”
    // 误判成“用户上滑”。
    var programmaticScroll by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canForward) ->
                if (programmaticScroll) return@collect
                if (scrolling && canForward) {
                    // 正在滚动且还能往下滚 → 用户主动上滑，锁定不自动滚
                    userScrolledUp = true
                } else if (!canForward) {
                    // 已经到底 → 解锁
                    userScrolledUp = false
                }
            }
    }

    LaunchedEffect(chatItems.size, streamingText.length, currentThinking.length) {
        val totalItems = chatItems.size +
                (if (streamingText.isNotEmpty()) 1 else 0) +
                (if (currentThinking.isNotEmpty() && streamingText.isEmpty()) 1 else 0)
        if (totalItems == 0) return@LaunchedEffect
        val last = chatHistory.lastOrNull()

        // 【修复】用户主动上滑后，不再强制拉到底部。
        // 只有两种情况才自动滚：
        //   1. 用户本来就在底部（没上滑）
        //   2. 用户刚发了一条消息（last.role == "user"）——这时应该跟到最新
        // 其余情况（工具调用、流式输出、AI 回复）若用户已上滑，一律不抢滚动。
        val shouldFollow = !userScrolledUp || last?.role == "user"
        if (shouldFollow) {
            try {
                programmaticScroll = true
                listState.scrollToItem(totalItems - 1)
            } catch (e: Exception) {
                AgentLogger.log(
                    "SYS",
                    "滚动到底部失败：${e.javaClass.simpleName}: ${e.message}"
                )
            } finally {
                programmaticScroll = false
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                PermissionRequestBroker.onAppResumed(context)
                floatingOn = FloatingService.isRunning
                PcBridgeClient.updateSettings(settings)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerContentProgress by animateFloatAsState(
        targetValue = if (drawerState.targetValue == DrawerValue.Open) 1f else 0f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "drawerContentProgress"
    )

    fun openPageFromDrawer(page: AppPage) {
        scope.launch {
            drawerState.close()
            delay(180.milliseconds)
            currentPage = page
        }
    }

    // ============================================================
    // 弹窗（保留 AlertDialog 系列的）
    // ============================================================
    crashInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { CrashStore.clear(context); crashInfo = null },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("软件崩溃") },
            text = {
                Column {
                    Text("上次运行发生错误。")
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            info.take(800),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("crash", info))
                    notifyToast(context, "已复制")
                }) { Text("复制") }
            },
            dismissButton = {
                TextButton(onClick = {
                    CrashStore.clear(context); crashInfo = null
                }) { Text("我知道了") }
            }
        )
    }

    PermissionRequestBroker.pending?.let { pending ->
        AlertDialog(
            onDismissRequest = { PermissionRequestBroker.onUserRejected() },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(pending.title) },
            text = { Text(pending.message) },
            confirmButton = {
                TextButton(onClick = { PermissionRequestBroker.onUserApproved(context) }) {
                    Text("前往设置", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { PermissionRequestBroker.onUserRejected() }) {
                    Text("取消", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }

    ConfirmationBroker.pending?.let { pending ->
        AlertDialog(
            onDismissRequest = { ConfirmationBroker.resolve(false) },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(pending.title) },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(pending.message, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = { ConfirmationBroker.resolve(true) }) {
                    Text("允许", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { ConfirmationBroker.resolve(false) }) {
                    Text("拒绝", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }

    pendingImportJson?.let { json ->
        AlertDialog(
            onDismissRequest = { pendingImportJson = null },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("导入方式") },
            text = {
                Column {
                    Text("追加导入：保留现有会话与记忆。")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "覆盖导入：清空现有会话与记忆后再导入。",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { doImport(json, replaceExisting = false) }) { Text("追加") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        pendingOverwriteJson = json
                        pendingImportJson = null
                    }) { Text("覆盖", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { pendingImportJson = null }) { Text("取消") }
                }
            }
        )
    }

    pendingOverwriteJson?.let { json ->
        AlertDialog(
            onDismissRequest = { pendingOverwriteJson = null },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("确认覆盖？") },
            text = { Text("这会清空当前所有会话与记忆，然后导入备份。此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingOverwriteJson = null
                    doImport(json, replaceExisting = true)
                }) { Text("确认覆盖", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingOverwriteJson = null }) { Text("取消") }
            }
        )
    }

    pendingDeleteSessionId?.let { id ->
        val title = sessionList.firstOrNull { it.id == id }?.title ?: "该会话"
        AlertDialog(
            onDismissRequest = { pendingDeleteSessionId = null },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("删除会话") },
            text = { Text("确定删除「$title」？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    if (id == currentSessionId && isLoading) interruptAgent()
                    SessionStore.delete(context, id)
                    sessionList = SessionStore.list(context)
                    if (id == currentSessionId) {
                        val next = sessionList.firstOrNull()
                        if (next != null) {
                            currentSessionId = next.id
                            val loaded = SessionStore.load(context, next.id)
                            chatHistory = loaded?.messages ?: emptyList()
                            currentSessionCreatedAt = loaded?.createdAt ?: System.currentTimeMillis()
                        } else startNewSession()
                    }
                    pendingDeleteSessionId = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSessionId = null }) { Text("取消") }
            }
        )
    }

    pendingDeleteMessageId?.let { mid ->
        AlertDialog(
            onDismissRequest = { pendingDeleteMessageId = null },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("删除消息") },
            text = { Text("确定删除这条消息？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    val idx = chatHistory.indexOfFirst { it.id == mid }
                    if (idx >= 0) {
                        chatHistory = deleteMessagePair(chatHistory, idx)
                        persist()
                    }
                    pendingDeleteMessageId = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteMessageId = null }) { Text("取消") }
            }
        )
    }

    exportSessionId?.let { sid ->
        AlertDialog(
            onDismissRequest = { exportSessionId = null },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("导出会话") },
            text = { Text("导出内容是否包含工具调用与工具返回？") },
            confirmButton = {
                TextButton(onClick = {
                    val s = SessionStore.load(context, sid)
                    if (s == null) {
                        notifyToast(context, "会话已不存在")
                    } else {
                        val f = SessionStore.exportMarkdown(context, s, hideToolMessages = false)
                        notifyToast(context, if (f != null) "已导出到：${f.absolutePath}" else "导出失败")
                    }
                    exportSessionId = null
                }) { Text("包含") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val s = SessionStore.load(context, sid)
                        if (s == null) {
                            notifyToast(context, "会话已不存在")
                        } else {
                            val f = SessionStore.exportMarkdown(context, s, hideToolMessages = true)
                            notifyToast(context, if (f != null) "已导出到：${f.absolutePath}" else "导出失败")
                        }
                        exportSessionId = null
                    }) { Text("不包含") }
                    TextButton(onClick = { exportSessionId = null }) { Text("取消") }
                }
            }
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("关于我们") },
            text = {
                Column {
                    Text("FurAgent By Xinkong", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("QQ: 226355502", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "内部测试版 请勿泄露",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, "https://space.bilibili.com/454325752".toUri())
                        )
                    } catch (e: Exception) {
                        AgentLogger.log(
                            "ERR",
                            "打开 Bilibili 失败：${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                }) { Text("访问 Bilibili") }
            },
            dismissButton = {
                TextButton(onClick = { showAbout = false }) { Text("关闭") }
            }
        )
    }

    if (showMorePanel) {
        val morePanelSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showMorePanel = false },
            sheetState = morePanelSheetState,
            containerColor = glassSheetBg(),
            scrimColor = Color.Black.copy(alpha = 0.15f),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            dragHandle = { GlassDragHandle() }
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                GlassSheen(
                    modifier = Modifier.align(Alignment.TopCenter),
                    height = 100.dp,
                    alpha = 0.06f
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        "更多功能",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        MoreActionItem(
                            icon = Icons.Default.AddPhotoAlternate,
                            label = "图片",
                            onClick = {
                                showMorePanel = false
                                keyboardController?.hide()
                                imagePickerLauncher.launch("image/*")
                            }
                        )
                        MoreActionItem(
                            icon = Icons.Default.RecordVoiceOver,
                            label = if (autoChatMode) "退出免提" else "免提模式",
                            highlighted = autoChatMode,
                            onClick = {
                                showMorePanel = false
                                if (autoChatMode) {
                                    autoChatMode = false
                                    SpeechHelper.stop()
                                    if (isLoading) stopAgent()
                                } else {
                                    autoChatMode = true
                                }
                            }
                        )
                        MoreActionItem(
                            icon = Icons.Default.Add,
                            label = "新对话",
                            onClick = {
                                showMorePanel = false
                                startNewSession()
                            }
                        )
                        MoreActionItem(
                            icon = Icons.Default.DeleteSweep,
                            label = "清空输入",
                            enabled = userInput.isNotEmpty() || pendingPickedImages.isNotEmpty(),
                            onClick = {
                                showMorePanel = false
                                userInput = ""
                                pendingPickedImages = emptyList()
                            }
                        )
                    }
                }
            }
        }
    }

    renamingSession?.let { id ->
        val cur = sessionList.firstOrNull { it.id == id }?.title ?: ""
        InputDialog("重命名会话", cur, { renamingSession = null }) { newTitle ->
            SessionStore.rename(context, id, newTitle)
            sessionList = SessionStore.list(context)
            renamingSession = null
        }
    }

    selectedMessage?.let { msg ->
        val idx = chatHistory.indexOfFirst { it.id == msg.id }
        MessageOptionsSheet(
            message = msg,
            onDismiss = { selectedMessage = null },
            onCopy = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("message", msg.content))
                selectedMessage = null
            },
            onEdit = {
                // 【体验优化 B7/B20】任意 text 类型都可编辑
                if (msg.type == "text") editingMessage = msg
                selectedMessage = null
            },
            onRegenerate = {
                selectedMessage = null
                if (idx >= 0 && !isLoading && currentProfileValue != null) {
                    val truncated = chatHistory.take(idx)
                    chatHistory = truncated
                    persist()
                    startAgent(truncated)
                }
            },
            onDelete = {
                if (idx >= 0) pendingDeleteMessageId = msg.id
                selectedMessage = null
            },
            onShare = {
                try {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, msg.content)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "分享到"))
                } catch (e: Exception) {
                    AgentLogger.log(
                        "ERR",
                        "分享消息失败：${e.javaClass.simpleName}: ${e.message}"
                    )
                }
                selectedMessage = null
            },
            onReExecute = if (msg.type == "tool_call") {
                {
                    selectedMessage = null
                    reExecuteToolCall(msg)
                }
            } else null
        )
    }

    editingMessage?.let { msg ->
        EditMessageDialog(msg.content, { editingMessage = null }) { newContent ->
            val idx = chatHistory.indexOfFirst { it.id == msg.id }
            editingMessage = null
            if (idx >= 0 && !isLoading && currentProfileValue != null) {
                val truncated = chatHistory.take(idx) +
                        ChatMessage(role = "user", content = newContent)
                chatHistory = truncated
                persist()
                startAgent(truncated)
            }
        }
    }

    val currentProfileName = currentProfileValue?.name ?: "未配置"

    viewingImage?.let { path ->
        ImageViewerDialog(path = path, onDismiss = { viewingImage = null })
    }

    // ============================================================
    // 主结构：抽屉 + 聊天 + Page 浮层 + Onboarding overlay
    // ============================================================
    Box(Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = glassSheetBg(),
                    drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                    modifier = Modifier.width(300.dp)
                ) {
                    SideDrawerContent(
                        progress = drawerContentProgress,
                        currentProfileName = currentProfileName,
                        floatingOn = floatingOn,
                        onNewChat = {
                            startNewSession()
                            scope.launch { drawerState.close() }
                        },
                        onOpenPcHub = { openPageFromDrawer(AppPage.PC_HUB) },
                        onOpenSchedules = { openPageFromDrawer(AppPage.SCHEDULES) },
                        onOpenMcp = { openPageFromDrawer(AppPage.MCP) },
                        onOpenMemory = { openPageFromDrawer(AppPage.MEMORY) },
                        onOpenAdvancedSettings = { openPageFromDrawer(AppPage.ADVANCED_SETTINGS) },
                        onToggleFloating = {
                            scope.launch { drawerState.close() }
                            if (FloatingService.isRunning) {
                                FloatingService.stop(context)
                                floatingOn = false
                                notifyToast(context, "悬浮助手已关闭")
                            } else {
                                if (!android.provider.Settings.canDrawOverlays(context)) {
                                    try {
                                        val intent = Intent(
                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            "package:${context.packageName}".toUri()
                                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                        context.startActivity(intent)
                                        notifyToast(context, "请授予悬浮窗权限，然后再次开启")
                                    } catch (e: Exception) {
                                        AgentLogger.log(
                                            "ERR",
                                            "打开悬浮窗权限设置失败：${e.javaClass.simpleName}: ${e.message}"
                                        )
                                    }
                                } else {
                                    FloatingService.start(context)
                                    FloatingService.setBallVisible(false)
                                    floatingOn = true
                                    notifyToast(context, "悬浮助手已开启")
                                }
                            }
                        },
                        onOpenAbout = {
                            scope.launch {
                                drawerState.close()
                                delay(180.milliseconds)
                                showAbout = true
                            }
                        }
                    )
                }
            }
        ) {
            Scaffold(
                topBar = {
                    Column {
                        TopAppBar(
                            title = {
                                Column(
                                    modifier = Modifier.clickable {
                                        scope.launch { drawerState.open() }
                                    }
                                ) {
                                    Text(
                                        "FurAgent",
                                        fontWeight = FontWeight.Black,
                                        fontSize = 20.sp,
                                        letterSpacing = (-0.5).sp,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Crossfade(
                                        targetState = when {
                                            autoChatMode -> "免提模式 · Token ≈ ${TokenStats.total}"
                                            isLoading -> "AI 正在工作（后台继续运行）"
                                            else -> "Token ≈ ${TokenStats.total}  ·  $currentProfileName"
                                        },
                                        animationSpec = tween(300),
                                        label = "subtitle"
                                    ) { subtitle ->
                                        Text(
                                            subtitle,
                                            fontSize = 11.sp,
                                            color = if (autoChatMode || isLoading)
                                                MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            actions = {
                                IconButton(onClick = { currentPage = AppPage.LOG }) {
                                    Icon(
                                        Icons.Default.Terminal,
                                        contentDescription = "日志",
                                        tint = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                                IconButton(onClick = { currentPage = AppPage.SESSIONS }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.List,
                                        contentDescription = "会话列表",
                                        tint = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                                IconButton(onClick = { currentPage = AppPage.SETTINGS }) {
                                    Icon(
                                        Icons.Default.Settings,
                                        contentDescription = "设置",
                                        tint = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
                                titleContentColor = MaterialTheme.colorScheme.onBackground
                            )
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(glassDivider())
                        )
                    }
                },
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0)
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = padding.calculateTopPadding())
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 12.dp, end = 12.dp, top = 8.dp,
                            bottom = dynamicBottomPadding
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (chatItems.isEmpty() &&
                            currentThinking.isEmpty() &&
                            streamingText.isEmpty()
                        ) {
                            item(key = "empty", contentType = "empty") { EmptyStatePlaceholder() }
                        }
                        items(
                            items = chatItems,
                            key = { it.key },
                            contentType = { item ->
                                when (item) {
                                    is ChatListItem.ToolsGroup -> "tools_group"
                                    is ChatListItem.Single -> item.msg.type
                                }
                            }
                        ) { item ->
                            when (item) {
                                is ChatListItem.Single -> {
                                    val msg = item.msg
                                    ChatBubble(
                                        message = msg,
                                        onLongPress = {
                                            if (msg.type != "system_note") selectedMessage = msg
                                        },
                                        onImageClick = { path -> viewingImage = path }
                                    )
                                }
                                is ChatListItem.ToolsGroup -> {
                                    ToolsGroupBubble(
                                        group = item,
                                        onMessageLongPress = { msg ->
                                            if (msg.type != "system_note") selectedMessage = msg
                                        },
                                        onImageClick = { path -> viewingImage = path }
                                    )
                                }
                            }
                        }
                        if (currentReasoning.isNotEmpty()) {
                            item(key = "reasoning_panel", contentType = "reasoning") {
                                ReasoningPanel(text = currentReasoning)
                            }
                        }
                        if (currentThinking.isNotEmpty() && streamingText.isEmpty()) {
                            item(key = "thinking_indicator", contentType = "thinking") {
                                ThinkingBubble(text = currentThinking)
                            }
                        }
                        if (streamingText.isNotEmpty()) {
                            item(key = "streaming_bubble", contentType = "text") {
                                TextBubble(
                                    message = ChatMessage(
                                        role = "assistant",
                                        content = streamingText,
                                        isStreaming = true
                                    ),
                                    onLongPress = { }
                                )
                            }
                        }
                    }

                    if (!isAtBottom && chatItems.isNotEmpty()) {
                        ExtendedFloatingActionButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        userScrolledUp = false
                                        listState.animateScrollToItem(chatItems.size - 1)
                                    } catch (_: Exception) { }
                                }
                            },
                            icon = { Icon(Icons.Default.VerticalAlignBottom, "回到底部") },
                            text = { Text("最新") },
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 24.dp, bottom = 132.dp)
                        )
                    }

                    // ============================================================
                    // 底部输入卡片（28dp 圆角 + 半透明 + 阴影）
                    // 这是输入框专用形状，不属于"卡片"，不套 PocoCard。
                    // ============================================================
                    val shadowColor = glassShadowColor()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .align(Alignment.BottomCenter)
                            .imePadding()
                            .navigationBarsPadding()
                            .shadow(
                                elevation = 16.dp,
                                shape = RoundedCornerShape(28.dp),
                                clip = false,
                                ambientColor = shadowColor,
                                spotColor = shadowColor
                            )
                    ) {
                        Surface(
                            color = glassFloatingBg(),
                            shape = RoundedCornerShape(28.dp),
                            border = BorderStroke(
                                0.5.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                AnimatedVisibility(
                                    visible = autoChatMode,
                                    enter = expandVertically(tween(180)) + fadeIn(tween(150)),
                                    exit = shrinkVertically(tween(150)) + fadeOut(tween(120))
                                ) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                                        shape = RoundedCornerShape(20.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.RecordVoiceOver,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                when {
                                                    isLoading -> "AI 正在回复…"
                                                    isSpeaking -> "AI 正在朗读…"
                                                    else -> "免提模式 · 正在聆听"
                                                },
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(
                                                onClick = {
                                                    autoChatMode = false
                                                    SpeechHelper.stop()
                                                    if (isLoading) stopAgent()
                                                },
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                            ) {
                                                Icon(Icons.Default.Stop, null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("退出", fontWeight = FontWeight.Medium)
                                            }
                                        }
                                    }
                                }

                                AnimatedVisibility(
                                    visible = isSpeaking && !autoChatMode,
                                    enter = expandVertically(tween(180)) + fadeIn(tween(150)),
                                    exit = shrinkVertically(tween(150)) + fadeOut(tween(120))
                                ) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                        shape = RoundedCornerShape(20.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.VolumeUp,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                "AI 正在朗读…",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(
                                                onClick = { SpeechHelper.stop() },
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                            ) {
                                                Icon(Icons.Default.Stop, null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("停止", fontWeight = FontWeight.Medium)
                                            }
                                        }
                                    }
                                }

                                if (pendingPickedImages.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        pendingPickedImages.forEach { path ->
                                            val bmp by produceState<Bitmap?>(initialValue = null, path) {
                                                value = withContext(Dispatchers.IO) {
                                                    try {
                                                        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                                                        BitmapFactory.decodeFile(path, opts)
                                                    } catch (_: Exception) { null }
                                                }
                                            }
                                            val b = bmp
                                            if (b != null) {
                                                Box {
                                                    Image(
                                                        bitmap = b.asImageBitmap(),
                                                        contentDescription = null,
                                                        modifier = Modifier
                                                            .size(52.dp)
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .clickable { viewingImage = path },
                                                        contentScale = ContentScale.Crop
                                                    )
                                                    Icon(
                                                        Icons.Default.Close,
                                                        contentDescription = "移除",
                                                        tint = Color.White,
                                                        modifier = Modifier
                                                            .size(16.dp)
                                                            .align(Alignment.TopEnd)
                                                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                                            .clickable {
                                                                pendingPickedImages = pendingPickedImages - path
                                                            }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // 输入框顶部工具行：深度思考开关 + 模型快捷选择（左上角）
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp, end = 12.dp, top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (currentProfileValue?.supportsThinking != false) {
                                        val thinkingOn = settings.enableThinking
                                        Surface(
                                            color = if (thinkingOn)
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                            else glassInputBg(),
                                            shape = RoundedCornerShape(16.dp),
                                            border = BorderStroke(
                                                1.dp,
                                                if (thinkingOn)
                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                                else glassInputBorder()
                                            ),
                                            modifier = Modifier.clickable {
                                                val newSettings = settings.copy(enableThinking = !thinkingOn)
                                                settings = newSettings
                                                SettingsStore.save(prefs, newSettings)
                                            }
                                        ) {
                                            Text(
                                                "深度思考",
                                                fontSize = 12.sp,
                                                fontWeight = if (thinkingOn) FontWeight.Medium else FontWeight.Normal,
                                                color = if (thinkingOn)
                                                    MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    var modelMenuOpen by remember { mutableStateOf(false) }
                                    Box {
                                        Surface(
                                            color = glassInputBg(),
                                            shape = RoundedCornerShape(16.dp),
                                            border = BorderStroke(1.dp, glassInputBorder()),
                                            modifier = Modifier.clickable { modelMenuOpen = true }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    currentProfileValue?.name ?: "未配置",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                    modifier = Modifier.widthIn(max = 160.dp)
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                Icon(
                                                    Icons.Default.ArrowDropDown,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        DropdownMenu(
                                            expanded = modelMenuOpen,
                                            onDismissRequest = { modelMenuOpen = false }
                                        ) {
                                            settings.chatProfiles().forEach { p ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            p.name,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = if (p.id == settings.currentProfileId)
                                                                MaterialTheme.colorScheme.primary
                                                            else MaterialTheme.colorScheme.onSurface,
                                                            fontWeight = if (p.id == settings.currentProfileId)
                                                                FontWeight.SemiBold else FontWeight.Normal
                                                        )
                                                    },
                                                    onClick = {
                                                        modelMenuOpen = false
                                                        val newSettings = settings.copy(currentProfileId = p.id)
                                                        settings = newSettings
                                                        SettingsStore.save(prefs, newSettings)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 8.dp, end = 6.dp, top = 2.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    var inputFocused by remember { mutableStateOf(false) }
                                    val inputBorderColor by animateColorAsState(
                                        targetValue = if (inputFocused) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                        } else glassInputBorder(),
                                        animationSpec = tween(durationMillis = 180),
                                        label = "inputBorder"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 46.dp, max = 140.dp)
                                            .clip(RoundedCornerShape(23.dp))
                                            .background(glassInputBg())
                                            .border(
                                                width = 1.dp,
                                                color = inputBorderColor,
                                                shape = RoundedCornerShape(23.dp)
                                            ),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        BasicTextField(
                                            value = userInput,
                                            onValueChange = { userInput = it },
                                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                color = MaterialTheme.colorScheme.onSurface,
                                                lineHeight = 20.sp
                                            ),
                                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                            // 软键盘 Enter 必须"按一次换一行"。
                                            //
                                            // 旧实现没设 keyboardOptions，BasicTextField 默认
                                            // imeAction = ImeAction.Default。部分国产输入法会把
                                            // 它当成"完成/发送"，第一次按只收起键盘，要按两次才换行。
                                            //
                                            // 显式声明 ImeAction.Default + singleLine = false，
                                            // 让软键盘显示"换行"键并直接插入 \n。
                                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                                imeAction = androidx.compose.ui.text.input.ImeAction.Default
                                            ),
                                            singleLine = false,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 13.dp)
                                                .onFocusChanged { inputFocused = it.isFocused },
                                            maxLines = 5,
                                            decorationBox = { innerTextField ->
                                                Box {
                                                    if (userInput.isEmpty()) {
                                                        Text(
                                                            if (autoChatMode) "免提模式 · 直接说话即可"
                                                            else "发消息…",
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                .copy(alpha = 0.6f)
                                                        )
                                                    }
                                                    innerTextField()
                                                }
                                            }
                                        )
                                    }

                                    Spacer(Modifier.width(2.dp))

                                    if (!autoChatMode) {
                                        IconButton(
                                            onClick = { startVoiceInput() },
                                            enabled = !isLoading,
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Mic,
                                                contentDescription = "语音输入",
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = {
                                            keyboardController?.hide()
                                            showMorePanel = true
                                        },
                                        enabled = !isLoading,
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Add,
                                            contentDescription = "更多功能",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Spacer(Modifier.width(2.dp))

                                    if (isLoading) {
                                        FilledIconButton(
                                            onClick = { stopAgent() },
                                            modifier = Modifier.size(40.dp),
                                            shape = CircleShape,
                                            colors = IconButtonDefaults.filledIconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.error,
                                                contentColor = MaterialTheme.colorScheme.onError
                                            )
                                        ) {
                                            Icon(
                                                Icons.Default.Stop,
                                                contentDescription = "停止生成",
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    } else {
                                        FilledIconButton(
                                            onClick = {
                                                if (userInput.isBlank() && pendingPickedImages.isEmpty()) {
                                                    return@FilledIconButton
                                                }
                                                sendMessageInternal(userInput, pendingPickedImages)
                                            },
                                            enabled = userInput.isNotBlank() || pendingPickedImages.isNotEmpty(),
                                            modifier = Modifier.size(40.dp),
                                            shape = CircleShape,
                                            colors = IconButtonDefaults.filledIconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Send,
                                                contentDescription = "发送",
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ============================================================
        // 整屏 Page 浮层
        // ============================================================
        AnimatedContent(
            targetState = currentPage,
            transitionSpec = {
                if (targetState != null) {
                    // 进入 Page：从右滑入
                    (slideInHorizontally(
                        animationSpec = tween(300, easing = FastOutSlowInEasing),
                        initialOffsetX = { it }
                    ) + fadeIn(tween(260))) togetherWith
                            fadeOut(tween(200))
                } else {
                    // 退出 Page：向右滑出
                    fadeIn(tween(260)) togetherWith
                            (slideOutHorizontally(
                                animationSpec = tween(280, easing = FastOutSlowInEasing),
                                targetOffsetX = { it }
                            ) + fadeOut(tween(200)))
                }
            },
            label = "pageTransition",
            modifier = Modifier.fillMaxSize()
        ) { page ->
            if (page != null) {
                when (page) {
                    AppPage.MEMORY -> MemoryPage(onBack = { currentPage = null })

                    AppPage.PERMISSIONS -> PermissionsPage(onBack = { currentPage = null })

                    AppPage.SESSIONS -> SessionsPage(
                        sessions = sessionList,
                        currentId = currentSessionId,
                        onSelect = { id ->
                            switchToSession(id)
                            currentPage = null
                        },
                        onDelete = { id -> pendingDeleteSessionId = id },
                        onRename = { id -> renamingSession = id },
                        onExport = { id -> exportSessionId = id },
                        onDuplicate = { id -> duplicateSession(id) },
                        onImport = { importSingleSessionFromFile() },
                        onCreate = { startNewSession(); currentPage = null },
                        onBack = { currentPage = null }
                    )

                    AppPage.SCHEDULES -> SchedulesPage(onBack = { currentPage = null })

                    AppPage.LOG -> LogConsolePage(
                        logs = AgentLogger.logs,
                        onClear = { AgentLogger.clear() },
                        onClearCrash = { CrashStore.clear(context); crashInfo = null },
                        onBack = { currentPage = null }
                    )

                    AppPage.SETTINGS -> SettingsPage(
                        settings = settings,
                        maxTurns = maxTurns,
                        themeMode = themeMode,
                        onThemeModeChanged = { newMode ->
                            themeMode = newMode
                            onThemeModeChanged(newMode)
                        },
                        onBack = {
                            currentPage = null
                            settings = SettingsStore.load(prefs)
                            maxTurns = prefs.getInt("max_turns", 5)
                        },
                        onSettingsChanged = { newTurns, newSettings ->
                            maxTurns = newTurns
                            settings = newSettings
                            prefs.edit { putInt("max_turns", newTurns) }
                            SettingsStore.save(prefs, newSettings)
                            try {
                                AndroidHttpFileServer.setReceiveDir(newSettings.pcReceiveDir)
                            } catch (e: Exception) {
                                AgentLogger.log(
                                    "BRIDGE",
                                    "更新接收目录失败：${e.javaClass.simpleName}: ${e.message}"
                                )
                            }
                            try {
                                AndroidHttpFileServer.setPathPolicy(newSettings.allowedPaths, newSettings.blockedPaths)
                            } catch (e: Exception) {
                                AgentLogger.log(
                                    "BRIDGE",
                                    "更新路径策略失败：${e.javaClass.simpleName}: ${e.message}"
                                )
                            }
                        }
                    )

                    AppPage.ADVANCED_SETTINGS -> AdvancedSettingsPage(
                        settings = settings,
                        onBack = {
                            currentPage = null
                            settings = SettingsStore.load(prefs)
                        },
                        onSettingsChanged = { newSettings ->
                            settings = newSettings
                            SettingsStore.save(prefs, newSettings)
                            try {
                                AndroidHttpFileServer.setPathPolicy(newSettings.allowedPaths, newSettings.blockedPaths)
                            } catch (e: Exception) {
                                AgentLogger.log(
                                    "BRIDGE",
                                    "更新路径策略失败：${e.javaClass.simpleName}: ${e.message}"
                                )
                            }
                        },
                        onImportRequested = { json ->
                            currentPage = null
                            pendingImportJson = json
                        },
                        onOpenPermissions = {
                            currentPage = AppPage.PERMISSIONS
                        },
                        onOpenEnvStore = {
                            currentPage = AppPage.ENV_STORE
                        }
                    )

                    AppPage.MCP -> McpPage(
                        disabledTools = settings.disabledTools,
                        onDisabledToolsChange = { newSet ->
                            val newSettings = settings.copy(disabledTools = newSet)
                            settings = newSettings
                            SettingsStore.save(prefs, newSettings)
                        },
                        onBack = { currentPage = null }
                    )

                    AppPage.ENV_STORE -> EnvStorePage(onBack = { currentPage = null })

                    AppPage.PC_HUB -> PcHubPage(
                        settings = settings,
                        onSettingsChanged = { newSettings ->
                            settings = newSettings
                            SettingsStore.save(prefs, newSettings)
                            try {
                                AndroidHttpFileServer.setReceiveDir(newSettings.pcReceiveDir)
                            } catch (_: Exception) { }
                            try {
                                AndroidHttpFileServer.setPathPolicy(newSettings.allowedPaths, newSettings.blockedPaths)
                            } catch (_: Exception) { }
                        },
                        onBack = { currentPage = null }
                    )
                }
            }
        }

        // ============================================================
        // 【新增】Onboarding overlay（最外层，盖住抽屉 + Page + 输入框）
        // ============================================================
        when (onboardingStage) {
            "permissions" -> OnboardingPermissionsFlow(
                onFinished = {
                    prefs.edit { putBoolean("onboarding_permissions_done", true) }
                    onboardingStage = if (
                        prefs.getBoolean("onboarding_guide_done", false)
                    ) "done" else "guide"
                }
            )
            "guide" -> OnboardingGuideDialog(
                onFinished = {
                    prefs.edit { putBoolean("onboarding_guide_done", true) }
                    onboardingStage = "done"
                }
            )
            else -> Unit
        }
    }
}

// ============================================================
// 更多功能面板项
// ============================================================
//
// 【UI 统一】图标容器圆角保留 18dp（面板内专用尺寸）。
// 高亮态走 primary，常态走 surfaceVariant。

@Composable
private fun MoreActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
            .clip(RoundedCornerShape(16.dp))
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
                highlighted -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            },
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (highlighted) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (highlighted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

// ============================================================
// 侧边抽屉内容
// ============================================================
//
// 【UI 统一】抽屉背景走 glassSheetBg（ModalDrawerSheet 传入）。
// 项颜色走 colorScheme。

@Composable
private fun SideDrawerContent(
    progress: Float,
    currentProfileName: String,
    floatingOn: Boolean,
    onNewChat: () -> Unit,
    onOpenPcHub: () -> Unit,
    onOpenSchedules: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onToggleFloating: () -> Unit,
    onOpenAbout: () -> Unit
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
            fontWeight = FontWeight.Black,
            fontSize = 22.sp,
            letterSpacing = (-0.5).sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
        Spacer(Modifier.height(20.dp))
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(14.dp),
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

        DrawerItem(Icons.Default.Devices, "电脑互联", onOpenPcHub)
        DrawerItem(Icons.Default.Schedule, "定时任务", onOpenSchedules)
        DrawerItem(Icons.Default.Extension, "MCP 服务器", onOpenMcp)
        DrawerItem(Icons.Default.Psychology, "长期记忆", onOpenMemory)
        DrawerItem(Icons.Default.Tune, "高级设置", onOpenAdvancedSettings)
        DrawerItem(
            Icons.Default.BubbleChart,
            if (floatingOn) "关闭悬浮助手" else "开启悬浮助手",
            onToggleFloating
        )

        Spacer(Modifier.weight(1f))

        DrawerItem(Icons.Default.Favorite, "关于我们", onOpenAbout)
        Spacer(Modifier.height(20.dp))
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 20.dp)
        ) {
            Text(
                "当前模型",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                currentProfileName,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Token 累计 ≈ ${TokenStats.total}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "FurAgent v1.0",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun DrawerItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 500f),
        label = "drawerItemScale"
    )
    val bg = if (pressed) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    else Color.Transparent
    Surface(
        color = bg,
        shape = RoundedCornerShape(12.dp),
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
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(14.dp))
            Text(label, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

// ============================================================
// 消息删除辅助
// ============================================================

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