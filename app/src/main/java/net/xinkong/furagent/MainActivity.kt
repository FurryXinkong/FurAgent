@file:OptIn(kotlinx.coroutines.FlowPreview::class)
package net.xinkong.furagent

import net.xinkong.furagent.shared.util.DateFormats
import net.xinkong.furagent.shared.ThinkingLevel
import net.xinkong.furagent.ui.AndroidBackupHistoryDialog
import net.xinkong.furagent.ui.AndroidSubSessionListDialog
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
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import kotlinx.coroutines.flow.debounce
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
import net.xinkong.furagent.tools.ToolRegistry.SubSessionProgress
import net.xinkong.furagent.ui.*
import net.xinkong.furagent.shared.update.UpdateChecker
import net.xinkong.furagent.shared.update.UpdateInfo
import net.xinkong.furagent.ui.pages.*
import net.xinkong.furagent.ui.theme.FurAgentTheme
import net.xinkong.furagent.ui.theme.AppRadius
import net.xinkong.furagent.shared.util.ChatListItem
import net.xinkong.furagent.shared.util.buildChatItems
import net.xinkong.furagent.shared.util.SessionAnalytics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import net.xinkong.furagent.ui.PocoDivider
import net.xinkong.furagent.ui.ModelSettingsButton
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
    ENV_STORE,
    SKILL,
    WEBVIEW,
    HISTORY
}

/** 修改文件按扩展名分发：这些走内置文本阅读器（其余未知扩展交给系统打开）。 */
private val TEXT_FILE_EXTS = setOf(
    "txt", "md", "kt", "java", "js", "jsx", "ts", "tsx", "json", "xml", "yaml", "yml",
    "toml", "ini", "conf", "cfg", "log", "csv", "tsv", "sql", "sh", "bash", "zsh",
    "css", "scss", "gradle", "properties", "pro", "env", "gitignore", "lua", "c", "h",
    "cpp", "hpp", "go", "rs", "rb", "php", "vue", "bat", "ps1", "dockerfile"
)

private val RE_STRIP_URL = Regex("""https?://\S+""")
private val RE_STRIP_PATH = Regex("""/\S+""")
private val RE_STRIP_MARKDOWN = Regex("""[*_`#>]+""")

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

        // 自定义脚本 安装安卓端脚本运行时（Chaquopy 内嵌 CPython）
        try {
            net.xinkong.furagent.shared.skill.SkillStore.install(
                net.xinkong.furagent.tools.AndroidSkillRuntime(applicationContext)
            )
            // ensureExample 会调 Python 解析脚本，首次启动 Chaquopy 要解压运行时，
            // 放主线程会卡顿甚至 ANR。改到后台线程异步执行。
            Thread {
                try {
                    net.xinkong.furagent.shared.skill.SkillStore.ensureExample()
                } catch (e: Throwable) {
                    AgentLogger.log("Skill", "创建示例脚本失败：${e.javaClass.simpleName}: ${e.message}")
                }
            }.start()
        } catch (e: Throwable) {
            AgentLogger.log("Skill", "初始化脚本运行时失败：${e.javaClass.simpleName}: ${e.message}")
        }

        setupGlobalCrashHandler()
        enableEdgeToEdge()
        val prefs = getSharedPreferences("FurAgentPrefs", MODE_PRIVATE)
        themeState.value = prefs.getString("theme_mode", "system") ?: "system"

        TokenStats.init(applicationContext)
        SpeechHelper.init(applicationContext)
        NotificationSyncSettings.init(applicationContext)

        try {
            val settings = SettingsStore.load(prefs)
            AndroidHttpFileServer.setReceiveDir(settings.pcReceiveDir)
            AndroidHttpFileServer.setPathPolicy(settings.allowedPaths, settings.blockedPaths)
            AndroidHttpFileServer.setFileReceivedCallback { path ->
                try {
                    val file = File(path)
                    // 带 PendingIntent 打开文件，点击通知直接打开
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
            // 会话文件写盘节流 500ms，退到后台时强制落盘，避免进程被杀时丢最后一条消息
            SessionStore.flushPending(applicationContext)
        } catch (e: Exception) {
            AgentLogger.log(
                "SYS",
                "flushPending 失败（onPause）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
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
            // 关闭后台写盘线程（quitSafely 会先处理完队列里 pending 的写盘）
            SessionStore.shutdownWriter()
        } catch (e: Exception) {
            AgentLogger.log(
                "SYS",
                "关闭会话写盘线程失败：${e.javaClass.simpleName}: ${e.message}"
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

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // POST_NOTIFICATIONS 的请求码是 2001（见 onCreate），
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
}

// 图片压缩
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

                // 临时文件落点 统一落 /sdcard/FurAgent/temp/，与 TOAI §2.2 一致。
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
    // 底部留白 = 输入框实测高度 + 8dp 余量（键盘由外层 imePadding 统一处理）
    var inputBarHeightPx by remember { mutableIntStateOf(0) }
    val inputBarHeightDp = with(density) { inputBarHeightPx.toDp() }
    val dynamicBottomPadding = inputBarHeightDp + 8.dp

    val agentSnapshot by AgentRunner.snapshot.debounce(32.milliseconds).collectAsState(initial = AgentRunner.snapshot.value)

    var currentSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var chatHistory by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var sessionList by remember { mutableStateOf<List<SessionMeta>>(emptyList()) }
    var userInput by rememberSaveable { mutableStateOf("") }
    // 会话隔离 输入框草稿按会话保存：切走存盘、切回恢复
    val draftPrefs = remember {
        context.getSharedPreferences("session_drafts", Context.MODE_PRIVATE)
    }
    fun readDraft(id: String): String = draftPrefs.getString("d_$id", "") ?: ""
    fun writeDraft(id: String, text: String) {
        draftPrefs.edit().putString("d_$id", text).apply()
    }

    // 草稿归属者：知道当前 userInput 属于哪个会话
    var draftOwner by remember { mutableStateOf(currentSessionId) }
    // 会话切换：旧会话的输入存回它自己，再取新会话的草稿。
    // 监听 currentSessionId 而非改每个调用点，新增切换入口也不会漏。
    LaunchedEffect(currentSessionId) {
        val prev = draftOwner
        if (prev == currentSessionId) return@LaunchedEffect
        prev?.let { writeDraft(it, userInput) }
        draftOwner = currentSessionId
        userInput = currentSessionId?.let { readDraft(it) } ?: ""
    }
    // 输入后防抖落盘：直接杀进程也不丢草稿
    LaunchedEffect(userInput, currentSessionId) {
        delay(400.milliseconds)
        // 只写回「自己的」会话，避免切换瞬间串味
        if (draftOwner == currentSessionId) {
            currentSessionId?.let { writeDraft(it, userInput) }
        }
    }
    var isSwitchingSession by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val isLoading = agentSnapshot.isRunning && agentSnapshot.sessionId == currentSessionId
    val streamingText = if (agentSnapshot.sessionId == currentSessionId) agentSnapshot.streaming else ""
    val currentReasoning = if (agentSnapshot.sessionId == currentSessionId) agentSnapshot.reasoning else ""
    val currentThinking = if (agentSnapshot.sessionId == currentSessionId) agentSnapshot.thinking else ""
    // 错误状态 API 错误（如「HTTP 429 · 用量配额已用尽」），渲染成状态小气泡
    val currentError = if (agentSnapshot.sessionId == currentSessionId) agentSnapshot.error else ""

    // 整屏 Page 导航
    // 短交互（更多功能面板、关于）保留 boolean。
    var currentPage by rememberSaveable { mutableStateOf<AppPage?>(null) }
    // 检查更新结果。 = 还没查完 / 查失败，此时不显示红点也不改按钮文案。
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var showMorePanel by rememberSaveable { mutableStateOf(false) }
    // 会话专用提示词弹窗
    var showSessionPrompt by remember { mutableStateOf(false) }


    var maxTurns by remember { mutableIntStateOf(prefs.getInt("max_turns", 5)) }

    // 启动时静默检查更新：后台 IO 线程跑，失败不打扰用户。
    // 结果只用来点亮设置页红点、改「关于我们」里的 GitHub 按钮文案。
    LaunchedEffect(Unit) {
        val ver = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        if (ver.isNotBlank()) {
            UpdateChecker.check(ver, UpdateChecker.Platform.ANDROID).onSuccess { updateInfo = it }
        }
    }
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

    // 子会话出口 系统返回键：有 Page 就关 Page，在子会话里优先回母会话。
    // 以前只在有 Page 时接管返回键，子会话里按返回会直接退出应用。
    // 放在 currentSessionCreatedAt / sessionList / currentPage 声明之后，避免前向引用。
    val inSubSessionForBack = currentSessionId?.startsWith("sub_") == true
    BackHandler(enabled = currentPage != null || inSubSessionForBack) {
        if (currentPage != null) {
            currentPage = null
        } else {
            val back = SubSessionNavigator.originId
            if (back != null) {
                SubSessionNavigator.open?.invoke(back)
            } else {
                // 来路不明（进程重建 / 从子代理列表直进）：回最近一个主会话
                val main = sessionList.firstOrNull { !it.id.startsWith("sub_") }
                if (main != null) {
                    currentSessionId = main.id
                    chatHistory = SessionStore.load(context, main.id)?.messages ?: emptyList()
                    currentSessionCreatedAt = main.createdAt
                }
            }
        }
    }

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
    // 任务完成结果卡的数据源（remember 必须在 Composable 上下文，不能放 LazyListScope）
    val modifiedFiles = remember(chatHistory) { SessionAnalytics.collectModifiedFiles(chatHistory) }
    // 文件预览状态：修改文件卡片点击 → 按类型分发（html→内置浏览器 / 文本→阅读器 / py→执行器）
    // webViewerUrl 用 saveable：currentPage 是 rememberSaveable，旋转/进程恢复后
    // 若只有 URL 丢失，WEBVIEW 页会以空地址恢复成一张白屏
    var webViewerUrl by rememberSaveable { mutableStateOf("") }
    var fileTextPath by remember { mutableStateOf<String?>(null) }
    var filePyPath by remember { mutableStateOf<String?>(null) }
    fun openModifiedFile(path: String) {
        val ext = path.substringAfterLast('.', "").lowercase()
        when (ext) {
            "html", "htm" -> {
                webViewerUrl = path
                currentPage = AppPage.WEBVIEW
            }
            "py" -> filePyPath = path
            in TEXT_FILE_EXTS -> fileTextPath = path
            else -> openLocalFileOnAndroid(context, path)
        }
    }
    // 定时任务 AI 结果回流
    // TaskExecutionService 执行完 run_agent 后发广播，这里收到就把消息注入当前会话。
    // 旧代码只 sendBroadcast 没人接，导致「AI 在后台回了话，但打开 App 看不到」。
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val sid = intent?.getStringExtra("session_id").orEmpty()
                if (sid.isBlank()) return
                // 刷新侧栏会话列表（标题/时间/条数可能变了）
                sessionList = SessionStore.list(context)
                // 当前正开着这个会话：重新读盘注入，避免与后台写入竞争
                if (sid == currentSessionId) {
                    val loaded = SessionStore.load(context, sid)
                    if (loaded != null) {
                        chatHistory = loaded.messages
                        currentSessionCreatedAt = loaded.createdAt
                    }
                }
            }
        }
        val filter = android.content.IntentFilter(
            net.xinkong.furagent.service.TaskExecutionService.ACTION_SCHEDULED_AGENT_RESULT
        )
        androidx.core.content.ContextCompat.registerReceiver(
            context, receiver, filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { try { context.unregisterReceiver(receiver) } catch (_: Exception) { } }
    }

    // 子会话完成 → 主对话注入「子代理完成」通知（结果摘要回流）
    LaunchedEffect(Unit) {
        SubSessionProgress.events.collect { e ->
            // 对齐桌面端 originId 为空（桥接/悬浮窗发起、母会话已关）时
            // 以前直接丢弃，子代理结果彻底蒸发，主会话什么也收不到。
            // 现在回落到最近一个主会话，至少能看到结果。
            val target = e.originId.ifBlank {
                SessionStore.list(context).firstOrNull { it.parentId == null }?.id ?: ""
            }
            if (target.isBlank()) return@collect
            val notice = ChatMessage(
                role = "system",
                content = "【子代理完成】「${e.title}」（${e.subId}）已完成，共 ${e.msgCount} 条消息。\n" +
                        "结果摘要：\n${e.summary}",
                type = "text",
                timestamp = System.currentTimeMillis()
            )
            if (currentSessionId == target) {
                // 子会话完成通知合并。
                // 如果主任务此刻正在跑（流式输出），不要直接改 chatHistory，
                // 避免和 AgentRunner 的 onHistoryChanged 发生覆盖冲突。
                if (isLoading) {
                    withContext(Dispatchers.IO) {
                        val s = SessionStore.load(context, target) ?: return@withContext
                        SessionStore.save(
                            context,
                            s.copy(
                                messages = s.messages + notice,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                } else {
                    chatHistory = chatHistory + notice
                    val title = sessionList.find { it.id == target }?.title ?: "对话"
                    val targetSession = withContext(Dispatchers.IO) { SessionStore.load(context, target) }
                    withContext(Dispatchers.IO) {
                        SessionStore.save(
                            context,
                            Session(
                                id = target,
                                title = title,
                                createdAt = currentSessionCreatedAt,
                                updatedAt = System.currentTimeMillis(),
                                messages = chatHistory,
                                parentId = targetSession?.parentId
                            )
                        )
                    }
                }
            } else {
                withContext(Dispatchers.IO) {
                    val s = SessionStore.load(context, e.originId) ?: return@withContext
                    SessionStore.save(
                        context,
                        s.copy(
                            messages = s.messages + notice,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    // 子会话卡片/列表点击 → 无打断切入；进入子会话前记住来路，顶部「返回」可切回
    SubSessionNavigator.open = { id ->
        val enteringSub = id.startsWith("sub_")
        if (enteringSub && currentSessionId?.startsWith("sub_") != true) {
            SubSessionNavigator.originId = currentSessionId
        } else if (!enteringSub && currentSessionId?.startsWith("sub_") == true) {
            SubSessionNavigator.originId = null
        }
        isSwitchingSession = true
        currentSessionId = id
        val loaded = SessionStore.load(context, id)
        chatHistory = loaded?.messages ?: emptyList()
        currentSessionCreatedAt = loaded?.createdAt ?: System.currentTimeMillis()
        isSwitchingSession = false
    }

    // 首次运行引导
    // 两个独立标记，可分别跳过：权限引导 / 功能导览。
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
            // 同步给 UI 层，供 History 页面过滤
            net.xinkong.furagent.tools.ToolRegistry.CurrentTaskSession.id = currentSessionId!!
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
        // 子会话标题保护 子会话标题带「↳ 」前缀，是子代理列表的识别依据。
        // 以前这里无条件 generateTitle，一 persist 就把前缀抹掉，
        // 子会话于是混进主会话列表、又不在子代理列表里
        //（看起来就像“子代理把原会话覆盖了”，而且找不到回去的入口）。
        // load 不限于子会话：主会话也可能被用户手动重命名过
        val existing = SessionStore.load(context, id)
        val title = when {
            existing?.titleLocked == true -> existing.title       // 用户手动重命名，保留
            existing?.title?.startsWith("↳") == true -> existing.title
            id.startsWith("sub_") -> "↳ " + SessionStore.generateTitle(chatHistory)
            else -> SessionStore.generateTitle(chatHistory)
        }
        val now = System.currentTimeMillis()
        SessionStore.save(
            context,
            Session(
                id = id, title = title,
                createdAt = currentSessionCreatedAt,
                updatedAt = now,
                messages = chatHistory,
                parentId = existing?.parentId,
                titleLocked = existing?.titleLocked
            )
        )
        // 增量更新：避免每次 persist 都读磁盘重建列表
        val idx = sessionList.indexOfFirst { it.id == id }
        sessionList = if (idx >= 0) {
            sessionList.toMutableList().apply {
                set(idx, SessionMeta(id, title, currentSessionCreatedAt, now, chatHistory.size))
            }
        } else {
            // 新会话首次保存，fallback 到完整读取
            // 过滤子会话，避免子代理混入主列表
            SessionStore.list(context).filter { it.parentId == null }
        }
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
        sessionList = SessionStore.list(context).filter { it.parentId == null }
    }

    fun switchToSession(id: String) {
        if (isLoading) interruptAgent()
        isSwitchingSession = true
        currentSessionId = id
        val loaded = SessionStore.load(context, id)
        chatHistory = loaded?.messages ?: emptyList()
        currentSessionCreatedAt = loaded?.createdAt ?: System.currentTimeMillis()
        isSwitchingSession = false
    }

    fun duplicateSession(id: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                SessionStore.duplicate(context, id)
            }
            if (result != null) {
                sessionList = SessionStore.list(context).filter { it.parentId == null }
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
                // 保留原文件后缀，供 importSingleSession 分流（.md / .json）
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
                    sessionList = SessionStore.list(context).filter { it.parentId == null }
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
            // 放开 MIME，允许选 .md / .json
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

            sessionList = SessionStore.list(context).filter { it.parentId == null }
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
        // 统一走 shared/util/SpeechCleaner，与桌面端完全同源
        val clean = net.xinkong.furagent.shared.util.SpeechCleaner.cleanForSpeech(text)
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
        // 会话专用提示词：每轮发送前实时读盘，编辑后下一轮即生效
        val sessionPrompt = SessionStore.load(context, sid)?.systemPrompt ?: ""
        val started = AgentRunner.start(
            context = context,
            sessionId = sid,
            profile = profile,
            initialHistory = initialHistory,
            maxTurns = maxTurns,
            prefs = prefs,
            settings = settings,
            sessionPrompt = sessionPrompt,
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
                // 用户主动发消息时无需在此重置上滑锁定：
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
        // 子会话识别 改按 parentId 判定，不再靠标题前缀：
        // 以前标题被 persist 重算后子会话就混进主列表。
        val mainList = list.filter { it.parentId == null }
        sessionList = mainList

        val target = when {
            // 运行中的会话/用户上次停留的会话若还在（含子会话），保持原状
            runningSessionId != null && list.any { it.id == runningSessionId } -> runningSessionId
            currentSessionId != null && list.any { it.id == currentSessionId } -> currentSessionId!!
            // 兜底只从主会话列表选，避免启动时直接进子会话
            mainList.isNotEmpty() -> mainList.first().id
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
            sessionList = SessionStore.list(context).filter { it.parentId == null }
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

    // 用户上滑后不再被强制拉到底
    // 背景：原来只要 chatItems.size 变化（每次工具调用都会变）就无条件
    // scrollToItem 到底部，用户上滑看历史时会被反复拽回底部，体验极差。

    // 不再用 isScrollInProgress 猜“用户是否在拖”，因为程序滚动
    // （scrollToItem）也会把它置 true，容易误判。改用两个更可靠的信号：
    //   - listState.canScrollForward == false  → 真的到底了
    //   - listState.isScrollInProgress         → 有滚动在进行
    // 只要“正在滚动”且“没到底”，就认定用户主动上滑，锁定不自动滚。
    // 程序滚动到底后 canScrollForward 变 false，会自动解锁。

    // 注意 isAtBottom / userScrolledUp 必须声明在下面的自动滚动 LaunchedEffect 之前，
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
    // 删掉无效的 programmaticScroll：scrollToItem 是 suspend 且同步完成，
    // 而 snapshotFlow 的 collect 是异步的，等到 collect 执行时标志早已复位，拦不住任何东西。
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canForward) ->
                if (scrolling && canForward) {
                    // 正在滚动且还能往下滚 → 用户主动上滑，锁定不自动滚
                    userScrolledUp = true
                } else if (!canForward) {
                    // 已经到底 → 解锁
                    userScrolledUp = false
                }
            }
    }

    // 性能 用 snapshotFlow 替代 LaunchedEffect 频繁变化的 key
    // 原来 streamingText.length 每 32ms 变一次 → LaunchedEffect 重启 → 取消+重建协程
    // 改为 snapshotFlow：协程常驻，只在值变化时执行 collect 块
    LaunchedEffect(Unit) {
        snapshotFlow {
            chatItems.size +
                (if (streamingText.isNotEmpty()) 1 else 0) +
                (if (currentThinking.isNotEmpty() && streamingText.isEmpty()) 1 else 0)
        }.collect { totalItems ->
            if (totalItems == 0) return@collect
            val last = chatHistory.lastOrNull()
            // 用户主动上滑后不强制拉到底部
            val shouldFollow = !userScrolledUp || last?.role == "user"
            if (shouldFollow) {
                try {
                    // LazyColumn 里除 chatItems 还有 status bar / modified files 等
                    // 额外 item，用 totalItems-1 滚不到真正的底部，差 1~3 个 item。
                    // scrollToItem(Int.MAX_VALUE) 由 LazyList 自己 clamp 到最后一个 item。
                    listState.scrollToItem(Int.MAX_VALUE)
                } catch (e: Exception) {
                    AgentLogger.log(
                        "SYS",
                        "滚动到底部失败：${e.javaClass.simpleName}: ${e.message}"
                    )
                }
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

    // 弹窗（保留 AlertDialog 系列的）
    crashInfo?.let { info ->
        PocoAlertDialog(
            onDismiss = { CrashStore.clear(context); crashInfo = null },
            title = "软件崩溃",
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
            confirmText = "复制",
            onConfirm = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", info))
                notifyToast(context, "已复制")
            },
            dismissText = "我知道了",
            onDismissClick = { CrashStore.clear(context); crashInfo = null }
        )
    }

    PermissionRequestBroker.pending?.let { pending ->
        PocoAlertDialog(
            onDismiss = { PermissionRequestBroker.onUserRejected() },
            title = pending.title,
            text = { Text(pending.message) },
            confirmText = "前往设置",
            onConfirm = { PermissionRequestBroker.onUserApproved(context) },
            dismissText = "取消",
            dismissColor = MaterialTheme.colorScheme.error,
            onDismissClick = { PermissionRequestBroker.onUserRejected() }
        )
    }

    ConfirmationBroker.pending?.let { pending ->
        PocoAlertDialog(
            onDismiss = { ConfirmationBroker.resolve(false) },
            title = pending.title,
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(pending.message, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmText = "允许",
            onConfirm = { ConfirmationBroker.resolve(true) },
            dismissText = "拒绝",
            dismissColor = MaterialTheme.colorScheme.error,
            onDismissClick = { ConfirmationBroker.resolve(false) }
        )
    }

    pendingImportJson?.let { json ->
        PocoAlertDialog(
            onDismiss = { pendingImportJson = null },
            title = "导入方式",
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
            confirmText = "追加",
            onConfirm = { doImport(json, replaceExisting = false) },
            extraDismissText = "覆盖",
            extraDismissColor = MaterialTheme.colorScheme.error,
            onExtraDismissClick = {
                pendingOverwriteJson = json
                pendingImportJson = null
            },
            dismissText = "取消",
            onDismissClick = { pendingImportJson = null }
        )
    }

    pendingOverwriteJson?.let { json ->
        PocoAlertDialog(
            onDismiss = { pendingOverwriteJson = null },
            title = "确认覆盖？",
            text = { Text("这会清空当前所有会话与记忆，然后导入备份。此操作不可恢复。") },
            confirmText = "确认覆盖",
            confirmColor = MaterialTheme.colorScheme.error,
            onConfirm = {
                pendingOverwriteJson = null
                doImport(json, replaceExisting = true)
            },
            dismissText = "取消",
            onDismissClick = { pendingOverwriteJson = null }
        )
    }

    pendingDeleteSessionId?.let { id ->
        val title = sessionList.firstOrNull { it.id == id }?.title ?: "该会话"
        PocoAlertDialog(
            onDismiss = { pendingDeleteSessionId = null },
            title = "删除会话",
            text = { Text("确定删除「$title」？此操作不可恢复。") },
            confirmText = "删除",
            confirmColor = MaterialTheme.colorScheme.error,
            onConfirm = {
                if (id == currentSessionId) {
                    interruptAgent()
                } else {
                    // 非当前会话：也要取消它的任务（AgentRunner 是单任务模型，
                    // 直接 cancel 即可；若将来支持多会话并行，这里要改成按 sessionId 取消）
                    AgentRunner.cancel(context)
                }
                SessionStore.delete(context, id)
                sessionList = SessionStore.list(context).filter { it.parentId == null }
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
            },
            dismissText = "取消",
            onDismissClick = { pendingDeleteSessionId = null }
        )
    }

    pendingDeleteMessageId?.let { mid ->
        PocoAlertDialog(
            onDismiss = { pendingDeleteMessageId = null },
            title = "删除消息",
            text = { Text("确定删除这条消息？此操作不可恢复。") },
            confirmText = "删除",
            confirmColor = MaterialTheme.colorScheme.error,
            onConfirm = {
                val idx = chatHistory.indexOfFirst { it.id == mid }
                if (idx >= 0) {
                    chatHistory = deleteMessagePair(chatHistory, idx)
                    persist()
                }
                pendingDeleteMessageId = null
            },
            dismissText = "取消",
            onDismissClick = { pendingDeleteMessageId = null }
        )
    }

    exportSessionId?.let { sid ->
        PocoAlertDialog(
            onDismiss = { exportSessionId = null },
            title = "导出会话",
            text = { Text("导出内容是否包含工具调用与工具返回？") },
            confirmText = "包含",
            onConfirm = {
                val s = SessionStore.load(context, sid)
                if (s == null) {
                    notifyToast(context, "会话已不存在")
                } else {
                    val f = SessionStore.exportMarkdown(context, s, hideToolMessages = false)
                    notifyToast(context, if (f != null) "已导出到：${f.absolutePath}" else "导出失败")
                }
                exportSessionId = null
            },
            extraDismissText = "不包含",
            onExtraDismissClick = {
                val s = SessionStore.load(context, sid)
                if (s == null) {
                    notifyToast(context, "会话已不存在")
                } else {
                    val f = SessionStore.exportMarkdown(context, s, hideToolMessages = true)
                    notifyToast(context, if (f != null) "已导出到：${f.absolutePath}" else "导出失败")
                }
                exportSessionId = null
            },
            dismissText = "取消",
            onDismissClick = { exportSessionId = null }
        )
    }

    // 内置文件预览：文本阅读器 / Python 执行器（由修改文件卡片点击分发打开）
    fileTextPath?.let { p ->
        AndroidTextFileDialog(path = p, onDismiss = { fileTextPath = null })
    }
    filePyPath?.let { p ->
        AndroidPyRunDialog(path = p, onDismiss = { filePyPath = null })
    }

    if (showMorePanel) {
        val morePanelSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showMorePanel = false },
            sheetState = morePanelSheetState,
            containerColor = glassSheetBg(),
            scrimColor = Color.Black.copy(alpha = 0.15f),
            shape = RoundedCornerShape(topStart = AppRadius.XL, topEnd = AppRadius.XL),
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
            sessionList = SessionStore.list(context).filter { it.parentId == null }
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
                // 体验优化 任意 text 类型都可编辑
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

    // 手机端优化 子代理 / 修改历史弹窗开关（入口在顶栏图标）
    var subListOpen by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }

    viewingImage?.let { path ->
        ImageViewerDialog(path = path, onDismiss = { viewingImage = null })
    }

    // 手机端优化 子代理 / 修改历史弹窗（入口在顶栏图标）
    if (historyOpen) AndroidBackupHistoryDialog(onDismiss = { historyOpen = false })
    if (subListOpen) AndroidSubSessionListDialog(onDismiss = { subListOpen = false })

    // 会话专用提示词编辑弹窗
    val sessionPromptTarget = currentSessionId
    if (showSessionPrompt && sessionPromptTarget != null) {
        AndroidSessionPromptDialog(
            sessionId = sessionPromptTarget,
            onDismiss = { showSessionPrompt = false },
            onSaved = { /* 保存后无需额外处理，AgentRunner 每轮实时读盘 */ }
        )
    }

    // 主结构：抽屉 + 聊天 + Page 浮层 + Onboarding overlay
    Box(Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = glassSheetBg(),
                    drawerShape = RoundedCornerShape(topEnd = AppRadius.XL, bottomEnd = AppRadius.XL),
                    modifier = Modifier.width(300.dp)
                ) {
                    SideDrawerContent(
                        progress = drawerContentProgress,
                        currentProfileName = currentProfileName,
                        currentPage = currentPage,
                        floatingOn = floatingOn,
                        onNewChat = {
                            startNewSession()
                            scope.launch { drawerState.close() }
                        },
                        onOpenPcHub = { openPageFromDrawer(AppPage.PC_HUB) },
                        onOpenSchedules = { openPageFromDrawer(AppPage.SCHEDULES) },
                        onOpenMcp = { openPageFromDrawer(AppPage.MCP) },
                        onOpenSkill = { openPageFromDrawer(AppPage.SKILL) },
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
// 手机端排版 从顶栏挪进来的入口
                        onOpenSubList = {
                            scope.launch { drawerState.close() }
                            subListOpen = true
                        },
                        onOpenHistory = {
                            scope.launch { drawerState.close() }
                            currentPage = AppPage.HISTORY
                        },
                        onOpenSessions = { openPageFromDrawer(AppPage.SESSIONS) },
                        onOpenLog = { openPageFromDrawer(AppPage.LOG) },
                        // 会话专用提示词入口
                        onOpenSessionPrompt = {
                            scope.launch {
                                drawerState.close()
                                delay(180.milliseconds)
                                showSessionPrompt = true
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
                                    // 副标题只在「非日常状态」出现：免提 / 工作中。
                                    // 平时只显示模型名，顶栏保持安静（主流客户端做法）
                                    Crossfade(
                                        targetState = when {
                                            autoChatMode -> "免提模式"
                                            isLoading -> "正在工作"
                                            else -> currentProfileName
                                        },
                                        animationSpec = tween(200),
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
                                // 子会话出口 在子会话里直接给一个返回按钮，
                                // 不用再拉开抽屉找「子代理」列表，也不用按返回键退应用。
                                if (inSubSessionForBack) {
                                    IconButton(onClick = {
                                        val back = SubSessionNavigator.originId
                                        if (back != null) {
                                            SubSessionNavigator.open?.invoke(back)
                                        } else {
                                            val main = sessionList.firstOrNull { !it.id.startsWith("sub_") }
                                            if (main != null) {
                                                currentSessionId = main.id
                                                chatHistory = SessionStore.load(context, main.id)?.messages
                                                    ?: emptyList()
                                                currentSessionCreatedAt = main.createdAt
                                            }
                                        }
                                    }) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "返回母会话",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                // 手机端排版 顶栏只留搜索 / 会话列表 / 设置，避免窄屏把标题挤成竖排。
                                // 子代理 / 修改历史 / 日志 收进侧边抽屉。
                                IconButton(onClick = { showSearch = !showSearch; if (!showSearch) searchQuery = "" }) {
                                    Icon(
                                        if (showSearch) Icons.Default.Close else Icons.Default.Search,
                                        contentDescription = "搜索",
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
                        if (showSearch) {
                            // 复用项目表单标准组件 OutlinedTextField（Dialogs / 编辑对话框同款），
                            // 加 remember，避免每次重组都全量遍历长会话
                            val matchCount = remember(searchQuery, chatItems) {
                                if (searchQuery.isNotBlank()) chatItems.count { item ->
                                    when (item) {
                                        is ChatListItem.Single -> item.msg.content.contains(searchQuery, ignoreCase = true)
                                        is ChatListItem.ToolsGroup -> item.items.any { it.content.contains(searchQuery, ignoreCase = true) }
                                        is ChatListItem.WorkProcess -> item.items.any { it.content.contains(searchQuery, ignoreCase = true) }
                                        is ChatListItem.DateSeparator -> item.displayText.contains(searchQuery, ignoreCase = true)
                                    }
                                } else 0
                            }
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                placeholder = { Text("搜索对话内容...") },
                                trailingIcon = if (searchQuery.isNotBlank()) {
                                    {
                                        Text(
                                            "$matchCount 条",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                } else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
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
                        // 输入框被键盘盖住
                        // imePadding 必须加在外层 Box 上：Column（列表）和输入框 Box
                        // 都是它的子元素，这样两者一起随键盘上移。
                        // 之前加在 Column 上，输入框不在 Column 内，所以没被顶上去。
                        .imePadding()
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(
                                start = 16.dp, end = 16.dp, top = 12.dp,
                                bottom = dynamicBottomPadding
                            ),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                        if (isSwitchingSession) {
                            item(key = "switching") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(200.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(32.dp),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        } else if (chatItems.isEmpty() &&
                            currentThinking.isEmpty() &&
                            streamingText.isEmpty()
                        ) {
                            item(key = "empty", contentType = "empty") {
                                EmptyStatePlaceholder(
                                    hasApiKey = currentProfileValue != null && currentProfileValue.apiKey.isNotBlank(),
                                    onOpenSettings = { currentPage = AppPage.SETTINGS },
                                    onPromptClick = { userInput = it }
                                )
                            }
                        }
                        itemsIndexed(
                            items = chatItems,
                            key = { _, it -> it.key },
                            contentType = { _, item ->
                                when (item) {
                                    is ChatListItem.ToolsGroup -> "tools_group"
                                    is ChatListItem.WorkProcess -> "work_process"
                                    is ChatListItem.Single -> item.msg.type
                                    is ChatListItem.DateSeparator -> "date_sep"
                                }
                            }
                        ) { index, item ->
                            // 新消息淡入 + 位移动画（参考主流 AI 客户端）
                            Box(Modifier.animateItem()) {
                            when (item) {
                                is ChatListItem.Single -> {
                                    val msg = item.msg
                                    // 同一分钟内只显示最后一条气泡的时间（与桌面端一致）
                                    val prevBubble = chatItems.getOrNull(index - 1)
                                    val showTime = prevBubble !is ChatListItem.Single ||
                                        prevBubble.msg.timestamp / 60_000L != msg.timestamp / 60_000L
                                    ChatBubble(
                                        message = msg,
                                        showTime = showTime,
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
                                is ChatListItem.WorkProcess -> {
                                    WorkProcessBubble(
                                        group = item,
                                        onMessageLongPress = { msg ->
                                            if (msg.type != "system_note") selectedMessage = msg
                                        },
                                        onImageClick = { path -> viewingImage = path },
                                        // 实时思考归位 运行中把流式思考挂到最后一条工作过程里（
                                        // 即最后一个工具调用之后），在它内部展开显示。
                                        // 之前是丢在气泡外面单独一行、只显示最后一行，
                                        // 既看不出上下文也看不到完整思考。
                                        liveReasoning = if (isLoading &&
                                            item === chatItems.lastOrNull() &&
                                            streamingText.isEmpty()
                                        ) currentReasoning else ""
                                    )
                                }
                                is ChatListItem.DateSeparator -> {
                                    DateSeparatorChip(item.displayText)
                                }
                            }
                            }
                        }
                        // 任务完成结果卡：本次会话编辑过的文件（功能强制常开）
                        if (!isLoading && modifiedFiles.isNotEmpty()) {
                            item(key = "modified_files_result", contentType = "modified_files") {
                                AndroidModifiedFilesCard(
                                    modifiedFiles,
                                    onOpenPath = { openModifiedFile(it) }
                                )
                            }
                        }
                        // 底部状态栏（强制常开）：轮次/步数/token/上下文 + 修改历史入口
                        if (!isLoading && chatHistory.isNotEmpty()) {
                            item(key = "token_status_bar", contentType = "token_bar") {
                                AndroidTokenStatusBar(chatHistory)
                            }
                        }
                        // 深度思考直显 还没出现工作过程气泡时（第一次工具调用之前），
                        // 流式思考没有宿主可挂；而下面的 thinking 气泡又只在 reasoning 为空时显示，
                        // 结果开了深度思考后，从发消息到第一次工具调用之间思考过程完全看不到。
                        val liveReasoningHost = chatItems.lastOrNull() is ChatListItem.WorkProcess
                        if (isLoading && currentReasoning.isNotBlank() && streamingText.isEmpty() && !liveReasoningHost) {
                            item(key = "live_reasoning_standalone", contentType = "thinking") {
                                LiveReasoningStep(currentReasoning)
                            }
                        }
                        // 实时思考 已归位到工作过程末尾（见 WorkProcessBubble 的 liveReasoning），
                        // 不再在气泡外面单独画一行「深度思考」。
                        if (currentThinking.isNotEmpty() && streamingText.isEmpty() &&
                            currentReasoning.isBlank()
                        ) {
                            item(key = "thinking_indicator", contentType = "thinking") {
                                ThinkingBubble(text = currentThinking)
                            }
                        }
                        // 错误状态气泡 与「重试中」同款样式，不是 AI 聊天气泡。
                        // 任务结束后仍保留（onFinish 只清 thinking，不清 error），
                        // 直到用户下次发消息、点重试、手动关闭或切换模型才清。
                        if (currentError.isNotEmpty() && streamingText.isEmpty()) {
                            item(key = "error_indicator", contentType = "error") {
                                ErrorBubble(text = currentError, onRetry = {
                                    // 重新发送最后一条用户消息
                                    val lastUserMsg = chatHistory.lastOrNull { it.role == "user" }?.content
                                    if (!lastUserMsg.isNullOrBlank()) {
                                        startAgent(chatHistory + ChatMessage(role = "user", content = lastUserMsg))
                                    }
                                }, onDismiss = {
                                    AgentRunner.clearError()
                                })
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
                    }

                    // 删除手机端 MiniMap：手机上空间宝贵，不需要这种全局导航条

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
                                // 固定位置修正 之前写死 132dp，但输入卡片高度是变的
                                // （多行输入 / 图片预览 / 彩盘切换），卡片一高 FAB 就被压上去。
                                // 直接用实测的 inputBar 高度 + 余量。
                                .padding(end = 24.dp, bottom = dynamicBottomPadding + 12.dp)
                        )
                    }

                    // 底部输入卡片（28dp 圆角 + 半透明 + 阴影）
                    // 这是输入框专用形状，不属于"卡片"，不套 PocoCard。
                    val shadowColor = glassShadowColor()
                    // 子代理页面不渲染输入框 不是禁用，是整块不画。
                    // 子会话是 AI 派发的任务上下文，用户没有发消息的场景。
                    if (currentSessionId?.startsWith("sub_") != true) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 关键修正：尺寸量在 padding 外层
                            // modifier 链从左到右「由外到内」，
                            // onSizeChanged 之前挂在 .padding(vertical = 12.dp) 后面，
                            // 量到的是扣掉上下 padding 和导航栏后的内层高度，
                            // 比真实高度小了一截，所以 LazyColumn 底部留白不够，
                            // 最后一条（token 状态栏）被输入框压住。
                            // 现在提到 padding 之前，量到的就是占地面积的完整高度。
                            .onSizeChanged { inputBarHeightPx = it.height }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .shadow(
                                elevation = 12.dp,
                                shape = RoundedCornerShape(AppRadius.XL),
                                clip = false,
                                ambientColor = shadowColor,
                                spotColor = shadowColor
                            )
                    ) {
                        Surface(
                            color = glassFloatingBg(),
                            shape = RoundedCornerShape(AppRadius.XL),
                            border = BorderStroke(
                                0.5.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
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
                                        shape = RoundedCornerShape(AppRadius.LG),
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
                                        shape = RoundedCornerShape(AppRadius.LG),
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
                                                            .clip(RoundedCornerShape(AppRadius.XS))
                                                            .clickable { viewingImage = path },
                                                        contentScale = ContentScale.Crop
                                                    )
                                                    Icon(
                                                        Icons.Default.Close,
                                                        contentDescription = "移除",
                                                        tint = Color.White,
                                                        modifier = Modifier
                                                            .size(24.dp)
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

                                    // DeepSeek 式布局 一个圆角容器：
                                    //   上：输入区（占满宽度，多行）
                                    //   下：左 = 深度思考 / 模型选择 chip，右 = 语音/附件/发送
                                    // chip 在容器内部，不再飘在外面占额外一行。
                                    // 融合式 输入区不自带底色/边框，直接坐在外层玻璃容器上；
                                    // 聚焦时仅轻微提亮底色，避免"卡中卡"的割裂感。
                                    // 子代理只读 子会话是 AI 派发的任务上下文，用户不能直接发消息，
                                    // 否则会污染子代理的上下文（AI 追问走 run_sub_session 的 session_id）。
                                    // 输入框直接禁用，而不是等你敲完一大段再告诉你“只读”。
                                    val inSubSession = currentSessionId?.startsWith("sub_") == true
                                    OutlinedTextField(
                                        value = userInput,
                                        onValueChange = { if (!inSubSession) userInput = it },
                                        enabled = !inSubSession,
                                        placeholder = {
                                            Text(
                                                if (inSubSession) "子代理会话（只读）—— 由 AI 派发与追问"
                                                else if (autoChatMode) "免提模式 · 直接说话即可"
                                                else "发消息…",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    .copy(alpha = 0.6f)
                                            )
                                        },
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurface,
                                            lineHeight = 21.sp
                                        ),
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                            imeAction = androidx.compose.ui.text.input.ImeAction.Default
                                        ),
                                        singleLine = false,
                                        maxLines = 8,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 46.dp, max = 100.dp),
                                        shape = RoundedCornerShape(AppRadius.XL),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            // 永不变色：聚焦/未聚焦都是透明，直接坐在外层玻璃容器上
                                            focusedContainerColor = Color.Transparent,
                                            unfocusedContainerColor = Color.Transparent,
                                            focusedBorderColor = Color.Transparent,
                                            unfocusedBorderColor = Color.Transparent,
                                            cursorColor = MaterialTheme.colorScheme.primary
                                        )
                                    )

                                        // 底部工具行：左 chip，右按钮
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // 模型胶囊：点开弹面板（模型选择 + 推理强度滑块）
                                            ModelSettingsButton(
                                                modelName = currentProfileValue?.name ?: "未配置",
                                                profiles = settings.chatProfiles().map { it.id to it.name },
                                                currentProfileId = settings.currentProfileId,
                                                onSelectProfile = { id ->
                                                    val newSettings = settings.copy(currentProfileId = id)
                                                    settings = newSettings
                                                    SettingsStore.save(prefs, newSettings)
                                                    // 换模型 = 开始新尝试：上一次针对旧配置的错误不再挂着
                                                    AgentRunner.clearError()
                                                },
                                                thinkingLevel = settings.thinkingLevel,
                                                supportsThinking = currentProfileValue?.supportsThinking != false,
                                                onThinkingLevelChange = { level ->
                                                    val newSettings = settings.copy(thinkingLevel = level)
                                                    settings = newSettings
                                                    SettingsStore.save(prefs, newSettings)
                                                }
                                            )

                                            Spacer(Modifier.weight(1f))

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
                                                    modifier = Modifier.size(38.dp),
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
                                                val canSend = userInput.isNotBlank() || pendingPickedImages.isNotEmpty()
                                                // 渐变淡入：从「不可发」到「可发」时不会硬切
                                                val sendGradAlpha by animateFloatAsState(
                                                    targetValue = if (canSend) 1f else 0f,
                                                    animationSpec = tween(200),
                                                    label = "sendButtonGradient"
                                                )
                                                Box(
                                                    Modifier
                                                        .size(38.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            SolidColor(
                                                                MaterialTheme.colorScheme.surfaceVariant
                                                            )
                                                        )
                                                        .background(FluidGradient.diagonal(alpha = sendGradAlpha))
                                                        .clickable(enabled = canSend) {
                                                            sendMessageInternal(userInput, pendingPickedImages)
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        Icons.AutoMirrored.Filled.Send,
                                                        contentDescription = "发送",
                                                        tint = if (canSend) Color.White
                                                               else MaterialTheme.colorScheme.onSurfaceVariant,
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
                }
            }

        // 全局轻提示浮层（右上角），在 Page 浮层之下、Onboarding 之上
        ToastHost(
            alignment = Alignment.TopEnd,
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 8.dp, end = 16.dp)
        )

        // 整屏 Page 浮层
        AnimatedContent(
            targetState = currentPage,
            transitionSpec = {
                // 纯水平位移切屏：进入从右滑入，退出向右滑出。
                // 必须显式关掉 SizeTransform 的 clip，否则新旧内容尺寸不同时
                // AnimatedContent 会裁剪并缩放内容，观感就是"画面缩小"。
                if (targetState != null) {
                    slideInHorizontally(
                        animationSpec = tween(260, easing = FastOutSlowInEasing),
                        initialOffsetX = { it }
                    ) togetherWith
                            slideOutHorizontally(
                                animationSpec = tween(260, easing = FastOutSlowInEasing),
                                targetOffsetX = { it }
                            )
                } else {
                    slideInHorizontally(
                        animationSpec = tween(260, easing = FastOutSlowInEasing),
                        initialOffsetX = { -it }
                    ) togetherWith
                            slideOutHorizontally(
                                animationSpec = tween(260, easing = FastOutSlowInEasing),
                                targetOffsetX = { it }
                            )
                }.using(SizeTransform(clip = false))
            },
            label = "pageTransition",
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            if (page != null) {
                // 滑动返回手势：从左边缘向右滑动关闭页面
                val swipeOffset = remember { androidx.compose.animation.core.Animatable(0f) }
                val density = LocalDensity.current
                val edgeThresholdPx = with(density) { 24.dp.toPx() }
                val dismissThresholdPx = with(density) { 120.dp.toPx() }
                Box(
                    Modifier
                        .fillMaxSize()
                        .offset { IntOffset(swipeOffset.value.toInt(), 0) }
                        .pointerInput(page) {
                            // 网页预览页：横滑手势全部让给网页（页面内拖拽/横滑不再被
                            // 外层"右滑返回"抢走），关闭页面交给页内返回键与 BackHandler
                            if (page == AppPage.WEBVIEW) return@pointerInput
                            detectHorizontalDragGestures(
                                onDragStart = { /* 不限制起点，整个页面都可以右滑返回 */ },
                                onDragEnd = {
                                    scope.launch {
                                        if (swipeOffset.value > dismissThresholdPx) {
                                            // 过阈值直接交棒退出动画：AnimatedContent 的 slideOut+fadeOut
                                            // 从手势当前位置无缝续接。不能先 animateTo 屏外再置 null——
                                            // 那会让退出动画从原点重播（内容弹回再飞），出现"拽着跑"的鬼畜
                                            currentPage = null
                                        } else {
                                            swipeOffset.animateTo(
                                                targetValue = 0f,
                                                animationSpec = tween(200)
                                            )
                                        }
                                    }
                                },
                                onDragCancel = {
                                    scope.launch {
                                        swipeOffset.animateTo(
                                            targetValue = 0f,
                                            animationSpec = tween(200)
                                        )
                                    }
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    scope.launch {
                                        val newOffset =
                                            (swipeOffset.value + dragAmount).coerceIn(0f, size.width.toFloat())
                                        swipeOffset.snapTo(newOffset)
                                    }
                                }
                            )
                        }
                ) {
                when (page) {
                    AppPage.WEBVIEW -> AndroidWebViewerPage(
                        url = webViewerUrl,
                        onBack = { currentPage = null }
                    )

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
                        hasUpdate = updateInfo?.hasUpdate == true,
                        onCheckUpdate = {
                            scope.launch {
                                val ver = try {
                                    context.packageManager
                                        .getPackageInfo(context.packageName, 0).versionName ?: ""
                                } catch (e: Exception) {
                                    ""
                                }
                                if (ver.isBlank()) {
                                    notifyToast(context, "读取版本号失败")
                                } else {
                                    UpdateChecker.check(
                                        ver,
                                        UpdateChecker.Platform.ANDROID,
                                        force = true
                                    )
                                        .onSuccess { info ->
                                            updateInfo = info
                                            notifyToast(
                                                context,
                                                if (info.hasUpdate) {
                                                    "发现新版本 ${info.latestVersion}"
                                                } else {
                                                    "已是最新版本 ${info.currentVersion}"
                                                }
                                            )
                                        }
                                        .onFailure {
                                            notifyToast(context, "检查失败，请检查网络")
                                        }
                                }
                            }
                        },
                        onDownloadUpdate = {
                            downloadUpdateApk(context, scope, updateInfo)
                        },
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

                    AppPage.SKILL -> SkillPage(onBack = { currentPage = null })

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

                    AppPage.HISTORY -> HistoryPage(onBack = { currentPage = null })
                }
                } // Box (swipe gesture)
            }
        }

        // Onboarding overlay（最外层，盖住抽屉 + Page + 输入框）
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

/**
 * 安全打开外部链接。
 *
 * 设备上没有能处理该 scheme 的应用时 startActivity 会抛
 * ActivityNotFoundException，这里统一兜住并记日志，避免点按钮直接崩。
 */
private fun openUrlSafely(context: android.content.Context, url: String, label: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (e: Exception) {
        AgentLogger.log("ERR", "打开 $label 失败：${e.javaClass.simpleName}: ${e.message}")
    }
}

/**
 * 下载更新包并提示安装。
 *
 * 从「关于我们」弹窗的「下载更新」按钮调用。
 * 存到 /sdcard/FurAgent/downloads/，下完发通知，点通知进系统安装器。
 * 没有 apk 附件时退回 Release 页面。
 */
private fun downloadUpdateApk(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    info: net.xinkong.furagent.shared.update.UpdateInfo?
) {
    val url = info?.downloadUrl
    if (url.isNullOrBlank()) {
        notifyToast(context, "没有找到安装包，已打开更新页面")
        openUrlSafely(context, net.xinkong.furagent.shared.update.UpdateChecker.RELEASES_URL, "更新页面")
        return
    }
    scope.launch {
        val name = url.substringAfterLast('/').ifBlank {
            "FurAgent-${info.latestVersion}.apk"
        }
        val dir = java.io.File("/sdcard/FurAgent/downloads")
        if (!dir.exists()) dir.mkdirs()
        val target = java.io.File(dir, name)
        notifyToast(context, "开始下载 $name")
        val result = net.xinkong.furagent.shared.util.DownloadEngine.download(
            url = url,
            target = target,
            threads = 4
        )
        if (result.ok) {
            notifyToast(context, "下载完成，点通知安装")
            net.xinkong.furagent.system.NotificationHelper
                .showFileReceivedNotification(context, target)
        } else {
            notifyToast(context, "下载失败：${result.message}")
        }
    }
}

/** 子代理入口 + 修改历史对话框 → 已拆到 ui/AndroidDialogs.kt */