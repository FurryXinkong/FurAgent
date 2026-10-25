package net.xinkong.furagent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import net.xinkong.furagent.core.AgentLogger

/**
 * Android 端安全剪贴板（P1-12）。
 *
 * 与桌面端 GlassUtils.SafeClipboard 语义对齐：
 *  - 3 次重试（每次间隔 60ms）
 *  - 失败写日志，不抛异常
 *
 * 为什么需要：
 * 剪贴板服务在系统繁忙时偶发抛 IllegalStateException
 * （"cannot open system clipboard"），裸调 setPrimaryClip 会崩主线程。
 * 实测在华为 / 小米部分机型上尤为常见。
 *
 * 本文件是独立工具，不接入现有调用。未来批次可以把 MainActivity 里
 * 已有的 SafeClipboard.setText 调用统一替换为 SafeClipboardAndroid.setText。
 */
object SafeClipboardAndroid {

    fun setText(context: Context, text: String): Boolean {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("furagent", text))
                return true
            } catch (t: Throwable) {
                lastError = t
                if (attempt < 2) {
                    try { Thread.sleep(60) } catch (_: InterruptedException) { }
                }
            }
        }
        try {
            AgentLogger.log(
                "Clipboard",
                "复制到剪贴板失败（3 次重试后仍失败）：${lastError?.javaClass?.simpleName}: ${lastError?.message}"
            )
        } catch (_: Exception) { }
        return false
    }
}
