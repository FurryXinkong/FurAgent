package net.xinkong.furagent.desktop.ui

import net.xinkong.furagent.desktop.system.DesktopLogger

/**
 * Glass 相关的非 Composable 工具。
 *
 * 【拆分】从 Glass.kt 抽出（原文件 1480 行，职责过杂）。
 * 这里只放纯 JVM 工具 / 格式化函数，不依赖任何 Composable。
 */

// 下载速度 / 剩余时间格式化

internal fun formatSpeed(bps: Long): String = when {
    bps <= 0 -> "-"
    bps < 1024 -> "${bps}B/s"
    bps < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1fKB/s", bps / 1024.0)
    else -> String.format(java.util.Locale.US, "%.1fMB/s", bps / 1024.0 / 1024.0)
}

internal fun formatEta(sec: Long): String = when {
    sec < 0 -> "-"
    sec < 60 -> "${sec}秒"
    sec < 3600 -> "${sec / 60}分${sec % 60}秒"
    else -> "${sec / 3600}小时${(sec % 3600) / 60}分"
}

// 安全剪贴板

object SafeClipboard {
    fun setText(text: String): Boolean {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                cb.setContents(java.awt.datatransfer.StringSelection(text), null)
                return true
            } catch (t: Throwable) {
                lastError = t
                if (attempt < 2) {
                    try { Thread.sleep(60) } catch (_: InterruptedException) { }
                }
            }
        }
        try {
            DesktopLogger.warn(
                "Clipboard",
                "复制到剪贴板失败（3 次重试后仍失败）：${lastError?.javaClass?.simpleName}: ${lastError?.message}"
            )
        } catch (_: Exception) { }
        return false
    }

    fun getText(): String? {
        repeat(3) { attempt ->
            try {
                val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                if (cb.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                    return cb.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
                }
                return null
            } catch (_: Throwable) {
                if (attempt < 2) {
                    try { Thread.sleep(60) } catch (_: InterruptedException) { }
                }
            }
        }
        return null
    }

    fun getImage(): java.awt.Image? {
        repeat(3) { attempt ->
            try {
                val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                if (cb.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.imageFlavor)) {
                    return cb.getData(java.awt.datatransfer.DataFlavor.imageFlavor) as? java.awt.Image
                }
                return null
            } catch (_: Throwable) {
                if (attempt < 2) {
                    try { Thread.sleep(60) } catch (_: InterruptedException) { }
                }
            }
        }
        return null
    }
}
