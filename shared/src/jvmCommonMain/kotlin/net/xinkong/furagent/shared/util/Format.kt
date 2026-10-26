package net.xinkong.furagent.shared.util

import java.util.Locale

/**
 * 统一的格式化工具。
 *
 * 全项目通用，桌面端 / Android 端都从这里 import。
 * 冗余统一 #1 / #2
 */

/**
 * 字节数格式化。
 *   1023      → "1023B"
 *   12345     → "12.1KB"
 *   1572864   → "1.5MB"
 *   3221225472 → "3.00GB"
 */
fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1fKB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1fMB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.2fGB", bytes / 1024.0 / 1024.0 / 1024.0)
}

/**
 * 时长格式化。
 *   999    → "999ms"
 *   3200   → "3.2s"
 *   83000  → "1:23"
 */
fun formatDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> String.format(Locale.US, "%.1fs", ms / 1000.0)
    else -> {
        val s = ms / 1000
        String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}