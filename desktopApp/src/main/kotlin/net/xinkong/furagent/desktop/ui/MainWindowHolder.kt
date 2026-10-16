package net.xinkong.furagent.desktop.ui

import java.awt.Frame
import javax.swing.SwingUtilities

/**
 * 主窗口引用持有者。
 *
 * 用途：Agent 任务完成后，把主窗口拉回前台，让用户看到 AI 的输出。
 *
 * 为什么用 object 而不是参数传递：
 *  - App() 函数在 Window 内部被调用，window 引用在更外层
 *  - App() 参数列表已经很长，不适合再加
 *  - 需要一个跨 UI 树的全局访问点
 *
 * 激活策略（只在"用户可能看不到窗口"时激活）：
 *  - 窗口最小化（ICONIFIED）→ 恢复 + toFront + 短暂置顶
 *  - 窗口可见但非活跃（被其他窗口挡住）→ toFront + 短暂置顶
 *  - 窗口已经活跃 → 不做任何动作（避免打扰正在对话的用户）
 *
 * 置顶采用 500ms 短暂策略：Windows 有时会拒绝 setForegroundWindow，
 * 短暂置顶能可靠地把窗口拉到最前。之后立刻取消置顶，不干扰用户后续操作。
 */
object MainWindowHolder {

    @Volatile
    private var window: java.awt.Window? = null

    @Volatile
    private var activatePending = false

    fun register(w: java.awt.Window?) {
        window = w
    }

    /** 当前主窗口。用于判断“某个按键事件是不是发生在本窗口”。 */
    fun currentWindow(): java.awt.Window? = window

    /**
     * 请求激活主窗口。线程安全。
     */
    fun activate() {
        val w = window ?: return
        SwingUtilities.invokeLater {
            try {
                val frame = w as? Frame

                val minimized = frame != null &&
                        (frame.extendedState and Frame.ICONIFIED) != 0
                val notActive = !w.isActive
                val notVisible = !w.isVisible

                val needActivate = minimized || notActive || notVisible
                if (!needActivate) return@invokeLater

                // 已在激活中（防抖）：500ms 内只执行一次
                if (activatePending) return@invokeLater
                activatePending = true

                try {
                    if (minimized) {
                        // 从最小化恢复
                        frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
                    }
                    if (notVisible) {
                        w.isVisible = true
                    }

                    // 短暂置顶，确保抢到前台焦点
                    val wasAlwaysOnTop = w.isAlwaysOnTop
                    w.isAlwaysOnTop = true
                    w.toFront()
                    w.requestFocus()

                    // 500ms 后取消置顶（如果之前不是 alwaysOnTop）
                    javax.swing.Timer(500) {
                        try {
                            if (!wasAlwaysOnTop) w.isAlwaysOnTop = false
                        } catch (_: Exception) { }
                        activatePending = false
                    }.apply {
                        isRepeats = false
                        start()
                    }
                } catch (_: Exception) {
                    activatePending = false
                }
            } catch (_: Exception) {
                activatePending = false
            }
        }
    }
}