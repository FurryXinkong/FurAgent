package net.xinkong.furagent.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.xinkong.furagent.FloatingChatActivity
import net.xinkong.furagent.MainActivity
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.core.AgentRunner
import net.xinkong.furagent.system.notifyToast

/**
 * 悬浮球前台服务。
 *
 * 两个 View：
 *  1. 球（ballView）—— 可拖动，点击打开悬浮对话
 *  2. 状态气泡（bubbleView）—— 只在 AI 运行中显示，贴在球旁边
 *     显示 AI 当前状态（thinking / 工具名 / 流式预览），带"停止"按钮
 *
 * AI 任务完成时若用户不在主界面，自动拉起 MainActivity。
 *
 * 拖动时限制球心（左上角）在屏幕可视范围内，避免被拖出屏幕后不可见。
 */
class FloatingService : Service() {

    companion object {
        private const val CHANNEL_ID = "furagent_floating"
        private const val CHANNEL_NAME = "悬浮助手"
        private const val NOTIFICATION_ID = 3001
        private const val BALL_SIZE_DP = 56
        private const val BUBBLE_WIDTH_DP = 220
        private const val BUBBLE_GAP_DP = 8

        @Volatile
        var isRunning: Boolean = false
            private set

        /** 悬浮球是否应显示（App 主界面在前台时置 false） */
        private val _ballVisible = MutableStateFlow(true)
        val ballVisible: StateFlow<Boolean> = _ballVisible.asStateFlow()

        fun setBallVisible(visible: Boolean) {
            _ballVisible.value = visible
        }

        fun start(context: Context) {
            val ctx = context.applicationContext
            if (!Settings.canDrawOverlays(ctx)) {
                AgentLogger.log("ERR", "悬浮窗权限未授予")
                return
            }
            try {
                val intent = Intent(ctx, FloatingService::class.java)
                ctx.startForegroundService(intent)
            } catch (e: Exception) {
                AgentLogger.log("ERR", "启动悬浮服务失败：${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.applicationContext.stopService(
                    Intent(context, FloatingService::class.java)
                )
            } catch (e: Exception) {
                AgentLogger.log("ERR", "停止悬浮服务失败：${e.message}")
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null

    // 状态气泡
    private var bubbleView: LinearLayout? = null
    private var bubbleStateText: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    /** 上一次 isRunning 状态，用于检测"刚刚结束" */
    private var lastRunning: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        createChannel()
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            AgentLogger.log("ERR", "悬浮服务 startForeground 失败：${e.message}")
        }

        try {
            showBall()
            isRunning = true
        } catch (e: Exception) {
            AgentLogger.log("ERR", "显示悬浮球失败：${e.message}")
            try {
                notifyToast(
                    applicationContext,
                    "悬浮球显示失败：${e.message ?: "未知错误"}（可能是悬浮窗权限被撤销）"
                )
            } catch (_: Exception) { }
            stopSelf()
            return
        }

        // 观察球可见性
        serviceScope.launch {
            ballVisible.collect { visible ->
                val view = ballView ?: return@collect
                try {
                    view.visibility = if (visible) View.VISIBLE else View.GONE
                } catch (e: Exception) {
                    AgentLogger.log(
                        "SYS",
                        "设置悬浮球可见性失败：${e.javaClass.simpleName}: ${e.message}"
                    )
                }
                // 球隐藏时气泡也隐藏
                if (!visible) {
                    bubbleView?.visibility = View.GONE
                }
            }
        }

        // 观察 AI 状态
        serviceScope.launch {
            AgentRunner.snapshot.collect { snap ->
                updateBubbleState(snap)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        try {
            ballView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            AgentLogger.log(
                "SYS",
                "移除悬浮球失败（可忽略）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
        try {
            bubbleView?.let { windowManager.removeView(it) }
        } catch (_: Exception) { }
        ballView = null
        ballParams = null
        bubbleView = null
        bubbleStateText = null
        bubbleParams = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun showBall() {
        if (ballView != null) return

        if (!Settings.canDrawOverlays(this)) {
            throw SecurityException("悬浮窗权限已被撤销")
        }

        val size = dp(BALL_SIZE_DP)
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val maxX = (screenW - size).coerceAtLeast(0)
        val maxY = (screenH - size).coerceAtLeast(0)

        val ball = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF3B82F6.toInt())
                setStroke(dp(2), 0x66FFFFFF)
            }
            elevation = dp(8).toFloat()
        }

        val label = TextView(this).apply {
            text = "AI"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        ball.addView(label)

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val params = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12).coerceIn(0, maxX)
            y = dp(200).coerceIn(0, maxY)
        }

        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var moved = false

        ball.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) moved = true
                    params.x = (startX + dx.toInt()).coerceIn(0, maxX)
                    params.y = (startY + dy.toInt()).coerceIn(0, maxY)
                    try {
                        windowManager.updateViewLayout(ball, params)
                    } catch (e: Exception) {
                        AgentLogger.log(
                            "SYS",
                            "拖动悬浮球更新布局失败：${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                    updateBubblePosition()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        try {
                            FloatingChatActivity.start(this@FloatingService)
                        } catch (e: Exception) {
                            AgentLogger.log("ERR", "打开悬浮对话失败：${e.message}")
                        }
                    }
                    updateBubblePosition()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(ball, params)
        ballView = ball
        ballParams = params

        ball.visibility = if (ballVisible.value) View.VISIBLE else View.GONE
    }

    // ============================================================
    // 状态气泡
    // ============================================================

    private fun showBubble() {
        if (bubbleView != null) return
        try {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(6), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    // 半透明深色 + 蓝色描边
                    setColor(0xE0202225.toInt())
                    setStroke(dp(1), 0x665A9BFF)
                }
                elevation = dp(6).toFloat()
            }

            val stateText = TextView(this).apply {
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                setOnClickListener { openMainActivity() }
            }

            val stopButton = TextView(this).apply {
                text = "停止"
                setTextColor(0xFFFF5A5A.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { stopAgent() }
            }

            container.addView(stateText)
            container.addView(stopButton)

            val params = WindowManager.LayoutParams(
                dp(BUBBLE_WIDTH_DP),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(12)
                y = dp(200)
            }

            windowManager.addView(container, params)
            bubbleView = container
            bubbleStateText = stateText
            bubbleParams = params
        } catch (e: Exception) {
            AgentLogger.log("ERR", "创建状态气泡失败：${e.message}")
            bubbleView = null
            bubbleStateText = null
            bubbleParams = null
        }
    }

    private fun updateBubblePosition() {
        val bubble = bubbleView ?: return
        val bParams = bubbleParams ?: return
        val ballP = ballParams ?: return

        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val bubbleW = dp(BUBBLE_WIDTH_DP)
        val ballSize = dp(BALL_SIZE_DP)
        val gap = dp(BUBBLE_GAP_DP)

        val ballRight = ballP.x + ballSize
        val ballLeft = ballP.x

        // 优先放球的右侧，右侧空间不够放左侧，都不够则贴边
        val x = when {
            ballRight + gap + bubbleW <= screenW -> ballRight + gap
            ballLeft - gap - bubbleW >= 0 -> ballLeft - gap - bubbleW
            else -> (screenW - bubbleW - dp(4)).coerceAtLeast(0)
        }
        val y = ballP.y.coerceIn(0, (screenH - dp(60)).coerceAtLeast(0))

        bParams.x = x
        bParams.y = y
        try {
            windowManager.updateViewLayout(bubble, bParams)
        } catch (_: Exception) { }
    }

    private fun updateBubbleState(snap: AgentRunner.Snapshot) {
        // 检测"刚结束"
        val justFinished = lastRunning && !snap.isRunning
        lastRunning = snap.isRunning

        if (!snap.isRunning) {
            bubbleView?.visibility = View.GONE
            if (justFinished) {
                // 只在用户不在主界面（球是"应显示"状态）时自动返回
                if (ballVisible.value) {
                    openMainActivity()
                }
            }
            return
        }

        // 未在跑 → 保证气泡存在
        if (bubbleView == null) {
            showBubble()
        } else {
            if (!ballVisible.value) {
                bubbleView?.visibility = View.GONE
                return
            }
            bubbleView?.visibility = View.VISIBLE
            updateBubblePosition()
        }

        // 更新文字
        val text = when {
            snap.currentToolName.isNotBlank() -> "→ ${snap.currentToolName}"
            snap.streaming.isNotBlank() ->
                snap.streaming.takeLast(60).replace("\n", " ")
            snap.thinking.isNotBlank() -> snap.thinking.take(60)
            else -> "处理中…"
        }
        bubbleStateText?.text = text
    }

    // ============================================================
    // 操作
    // ============================================================

    private fun openMainActivity() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            startActivity(intent)
        } catch (e: Exception) {
            AgentLogger.log("ERR", "拉回主界面失败：${e.message}")
        }
    }

    private fun stopAgent() {
        try {
            AgentRunner.cancel(applicationContext)
            notifyToast(applicationContext, "已停止 AI")
        } catch (e: Exception) {
            AgentLogger.log("ERR", "停止 AI 失败：${e.message}")
        }
    }

    // ============================================================
    // 通知
    // ============================================================

    private fun createChannel() {
        try {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "悬浮助手运行中"
                    setShowBadge(false)
                }
                mgr.createNotificationChannel(ch)
            }
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "创建悬浮服务通知渠道失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("悬浮助手运行中")
            .setContentText("点击悬浮球可与 AI 对话")
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }
}