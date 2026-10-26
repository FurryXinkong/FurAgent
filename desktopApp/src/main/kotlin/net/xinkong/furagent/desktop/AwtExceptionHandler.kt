package net.xinkong.furagent.desktop

import net.xinkong.furagent.desktop.system.DesktopLogger

/**
 * AWT 事件分发线程（EDT）的未捕获异常处理器。
 *
 * 为什么需要
 * `Thread.setDefaultUncaughtExceptionHandler` 对 EDT 不生效 —— AWT 的
 * `EventDispatchThread.processException` 有自己的处理链，优先于全局处理器。
 * 当 EDT 上抛出未捕获异常（典型：剪贴板被占用时 `WClipboard.openClipboard`
 * 抛 `IllegalStateException: cannot open system clipboard`），AWT 会回退到
 * 默认行为：弹一个原生错误对话框（“cannot open system clipboard”）。
 *
 * 修法
 * 把本类名设给系统属性 `sun.awt.exception.handler`，AWT 会实例化它作为
 * EDT 的异常处理器，从而拦住弹框。要求：无参构造函数 + 实现
 * `Thread.UncaughtExceptionHandler`。
 *
 * 注意：剪贴板被占用是**瞬时**状态（其他进程正在写剪贴板），重试即可成功，
 * 所以这里只记日志、不弹框、不退出。
 */
class AwtExceptionHandler : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val msg = "${e.javaClass.name}: ${e.message}"
            // 剪贴板占用是预期内的瞬时错误，降级为 warn，避免刷屏
            if (e is IllegalStateException && e.message?.contains("clipboard", ignoreCase = true) == true) {
                DesktopLogger.warn("AWT", "EDT 剪贴板异常（已忽略）：$msg")
            } else {
                DesktopLogger.error("AWT", "EDT 未捕获异常：$msg\n${e.stackTraceToString()}")
            }
        } catch (_: Throwable) {
            // 日志失败也不能再抛，否则又回到弹框
        }
    }
}
