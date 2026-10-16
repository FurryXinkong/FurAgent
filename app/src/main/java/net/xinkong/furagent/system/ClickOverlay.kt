package net.xinkong.furagent.system

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator

/**
 * AI 屏幕点击可视化。
 *
 * 当 AI 通过无障碍或手势操作屏幕时，在操作位置画一个短暂的涟漪，
 * 让用户看到"AI 点了这里"。
 *
 * 使用 TYPE_ACCESSIBILITY_OVERLAY，是 AccessibilityService 专属的窗口类型，
 * 不需要 SYSTEM_ALERT_WINDOW 权限。
 *
 * 单例。ScreenAccessibilityService 在 onServiceConnected 时 attach，
 * onDestroy 时 detach。
 */
object ClickOverlay {

    @Volatile private var windowManager: WindowManager? = null
    @Volatile private var view: RippleView? = null
    @Volatile private var params: WindowManager.LayoutParams? = null

    fun attach(ctx: Context) {
        if (view != null) return
        try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = RippleView(ctx)
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            wm.addView(v, p)
            windowManager = wm
            view = v
            params = p
        } catch (_: Exception) {
            // 悬浮层添加失败（可能权限被撤销），静默降级
            windowManager = null
            view = null
            params = null
        }
    }

    fun detach() {
        val v = view ?: return
        try {
            windowManager?.removeView(v)
        } catch (_: Exception) { }
        view = null
        params = null
        windowManager = null
    }

    /**
     * 在屏幕坐标 (x, y) 播一次涟漪。
     * 主线程安全的（内部 post）。
     */
    fun show(x: Int, y: Int) {
        val v = view ?: return
        v.post { v.play(x.toFloat(), y.toFloat()) }
    }

    private class RippleView(ctx: Context) : View(ctx) {

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpLocal(4f)
            color = 0xFF5A9BFF.toInt()
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF5A9BFF.toInt()
        }

        private var cx = 0f
        private var cy = 0f
        private var progress = 0f
        private var animator: ValueAnimator? = null

        private fun dpLocal(v: Float): Float =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
            )

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (progress <= 0f || progress >= 1f) return

            val startRadius = dpLocal(10f)
            val endRadius = dpLocal(52f)
            val radius = startRadius + (endRadius - startRadius) * progress
            val alpha = ((1f - progress).coerceIn(0f, 1f) * 255).toInt()

            ringPaint.alpha = alpha
            dotPaint.alpha = alpha

            canvas.drawCircle(cx, cy, radius, ringPaint)
            canvas.drawCircle(cx, cy, dpLocal(5f), dotPaint)
        }

        fun play(x: Float, y: Float) {
            cx = x
            cy = y
            progress = 0f
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 500
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }
}