@file:OptIn(kotlinx.coroutines.FlowPreview::class)
package net.xinkong.furagent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.xinkong.furagent.core.AgentLogger
import kotlinx.coroutines.flow.debounce
import net.xinkong.furagent.core.AgentRunner
import net.xinkong.furagent.data.*
import net.xinkong.furagent.system.notifyToast
import net.xinkong.furagent.ui.WorkProcessItemList
import net.xinkong.furagent.ui.ChipSurface
import net.xinkong.furagent.ui.ImageViewerDialog
import net.xinkong.furagent.ui.theme.FurAgentTheme
import net.xinkong.furagent.ui.theme.AppRadius
import java.util.*

/**
 * 悬浮球点击后弹出的轻量对话窗口。
 *
 * 特点：
 *  - 使用 MainActivity 当前打开会话（通过 SharedPreferences 传递）
 *  - 输入草稿常驻（保存到 SharedPreferences）
 *  - 显示当前会话历史（最近 50 条）
 *  - 点击遮罩区域关闭
 *  - 有入场/退场动画
 *  - 支持语音输入
 */
class FloatingChatActivity : ComponentActivity() {

    companion object {
        fun start(context: Context) {
            try {
                val intent = Intent(context, FloatingChatActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                AgentLogger.log("ERR", "打开悬浮对话失败：${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("FurAgentPrefs", MODE_PRIVATE)
        val theme = prefs.getString("theme_mode", "system") ?: "system"

        setContent {
            FurAgentTheme(themeMode = theme) {
                FloatingChatScreen(onDismiss = { finish() })
            }
        }
    }
}

@Composable
private fun FloatingChatScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("FurAgentPrefs", Context.MODE_PRIVATE) }
    val keyboardController = LocalSoftwareKeyboardController.current

    var userInput by remember {
        mutableStateOf(prefs.getString("floating_chat_draft", "") ?: "")
    }
    var settings by remember { mutableStateOf(SettingsStore.load(prefs)) }
    var visible by remember { mutableStateOf(false) }
    var viewingImage by remember { mutableStateOf<String?>(null) }
    var inputFocused by remember { mutableStateOf(false) }

    // MainActivity 修改设置（切模型、填 API Key 等）后，
    // 悬浮窗每次回到前台都要读到最新值。
    // 原来只在 remember 首次执行时读一次，一直是旧 settings。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                settings = SettingsStore.load(prefs)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val profile = settings.currentProfile()
    val listState = rememberLazyListState()

    // 读取 MainActivity 当前会话 ID（SharedPreferences 桥接）
    // 健壮性 读到的 ID 必须先验证还在 SessionStore 里。
    // 用户可能在主界面删除了会话，或者 MainActivity 崩溃后残留旧值。
    // 不验证的话，发消息会写入一个已删除的会话，SessionStore.save 会创建一个
    // 孤立会话（sessionList 里看不到），用户完全不知道消息去哪了。
    val currentSessionId = remember {
        val saved = prefs.getString("current_session_id", null)
        val savedExists = saved != null && SessionStore.load(context, saved) != null
        if (savedExists) saved
        else SessionStore.list(context).firstOrNull()?.id
    }

    var history by remember {
        mutableStateOf(
            if (currentSessionId != null) {
                SessionStore.load(context, currentSessionId)?.messages ?: emptyList()
            } else emptyList()
        )
    }

    // 实时监听 AgentRunner 状态，更新历史
    val snapshot by AgentRunner.snapshot.debounce(32).collectAsState(initial = AgentRunner.snapshot.value)
    LaunchedEffect(snapshot.history, snapshot.sessionId) {
        if (snapshot.history.isNotEmpty() && snapshot.sessionId == currentSessionId) {
            history = snapshot.history
        }
    }

    // 打开时滚动到底部
    LaunchedEffect(history.size, visible) {
        if (visible && history.isNotEmpty()) {
            try {
                listState.scrollToItem(history.size - 1)
            } catch (e: Exception) {
                AgentLogger.log(
                    "SYS",
                    "悬浮对话滚动失败：${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
    }

    LaunchedEffect(Unit) { visible = true }

    LaunchedEffect(userInput) {
        prefs.edit { putString("floating_chat_draft", userInput) }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data?.getStringArrayListExtra(
            android.speech.RecognizerIntent.EXTRA_RESULTS
        )?.firstOrNull()
        if (!text.isNullOrBlank()) {
            userInput = if (userInput.isBlank()) text else "$userInput $text"
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
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "悬浮对话启动语音识别失败：${e.javaClass.simpleName}: ${e.message}"
            )
            notifyToast(context, "语音识别不可用：${e.message}")
        }
    }

    fun send() {
        val text = userInput.trim()
        if (text.isBlank()) return
        if (profile == null || profile.apiKey.isBlank()) {
            notifyToast(context, "请先在主界面配置 API Key")
            return
        }
        if (AgentRunner.isRunning()) {
            notifyToast(context, "AI 正在忙，请稍等")
            return
        }

        // 优先使用 MainActivity 当前会话
        val sessionId = currentSessionId ?: run {
            val newId = UUID.randomUUID().toString()
            SessionStore.save(
                context,
                Session(
                    id = newId, title = "新会话",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    messages = emptyList()
                )
            )
            newId
        }
        val existing = SessionStore.load(context, sessionId)?.messages ?: emptyList()
        val createdAt = SessionStore.load(context, sessionId)?.createdAt
            ?: System.currentTimeMillis()

        val newHistory = existing + ChatMessage(role = "user", content = text)
        val title = SessionStore.generateTitle(newHistory)
        SessionStore.save(
            context,
            Session(
                id = sessionId,
                title = title,
                createdAt = createdAt,
                updatedAt = System.currentTimeMillis(),
                messages = newHistory
            )
        )

        AgentRunner.start(
            context = context,
            sessionId = sessionId,
            profile = profile,
            initialHistory = newHistory,
            maxTurns = prefs.getInt("max_turns", 20),
            prefs = prefs,
            settings = settings,
            onFinish = { finalHistory ->
                // 保存 AI 回复到会话
                val cur = SessionStore.load(context, sessionId)
                SessionStore.save(
                    context,
                    Session(
                        id = sessionId,
                        title = SessionStore.generateTitle(finalHistory),
                        createdAt = cur?.createdAt ?: System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        messages = finalHistory
                    )
                )
            }
        )

        userInput = ""
        prefs.edit { putString("floating_chat_draft", "") }
        keyboardController?.hide()
        notifyToast(context, "已发送，后台运行中")
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(180)) + slideInVertically(tween(260)) { it / 4 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 4 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .clip(RoundedCornerShape(AppRadius.XL))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.98f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* 吃掉点击 */ }
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 标题栏
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "悬浮助手",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    "当前模型：${profile?.name ?: "未配置"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 聊天历史区（最近 50 条）
                if (history.isNotEmpty()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .clip(RoundedCornerShape(AppRadius.MD_MINUS))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 体验优化 从 50 增加到 200，与主界面更接近。
                        // 改用 WorkProcessItemList，与主界面一致：
                        // 工具调用渲染为树形紧凑行，不再是大卡片。
                        val shown = history.takeLast(200)
                        item {
                            WorkProcessItemList(
                                items = shown.filter { it.type != "system_note" },
                                onMessageLongPress = { },
                                onImageClick = { path -> viewingImage = path }
                            )
                        }
                    }
                }

                // 输入框
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp, max = 140.dp)
                        .clip(RoundedCornerShape(AppRadius.MD))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .border(
                            width = 1.dp,
                            color = if (inputFocused) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(AppRadius.MD)
                        ),
                    contentAlignment = Alignment.TopStart
                ) {
                    BasicTextField(
                        value = userInput,
                        onValueChange = { userInput = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 20.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 5,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 13.dp)
                            .onFocusChanged { inputFocused = it.isFocused },
                        decorationBox = { inner ->
                            Box {
                                if (userInput.isEmpty()) {
                                    Text(
                                        "说点什么…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                                inner()
                            }
                        }
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ChipSurface("截图", selected = false) {
                        userInput = "截图看看当前屏幕"
                    }
                    ChipSurface("读通知", selected = false) {
                        userInput = "读一下最近的通知"
                    }
                    ChipSurface("主界面", selected = false) {
                        try {
                            val intent = Intent(context, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            AgentLogger.log(
                                "ERR",
                                "打开主界面失败：${e.javaClass.simpleName}: ${e.message}"
                            )
                        }
                        onDismiss()
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { startVoiceInput() },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "语音输入",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledIconButton(
                        onClick = { send() },
                        enabled = userInput.isNotBlank(),
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, "发送")
                    }
                }
            }
        }
    }

    viewingImage?.let { path ->
        ImageViewerDialog(path = path, onDismiss = { viewingImage = null })
    }
}