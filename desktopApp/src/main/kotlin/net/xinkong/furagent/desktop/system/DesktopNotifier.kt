package net.xinkong.furagent.desktop.system

import java.awt.*
import java.awt.image.BufferedImage

/**
 * 桌面通知。
 *
 * 使用 JDK 内置 `SystemTray` + `TrayIcon.displayMessage`：
 *  - Windows / macOS / Linux 都支持
 *  - 不需要额外依赖（JNA / PowerShell）
 *  - 会走系统通知中心，不会一闪而过
 *
 * 只创建 TrayIcon 用来发通知，**不挂菜单**（之前 AWT PopupMenu 中文是方块，
 * 那个功能已删除；本次只保留弹通知能力，菜单相关 API 保留为空实现）。
 *
 * 初始化失败（无系统托盘 / 权限不足 / headless 环境）时降级为日志。
 */
object DesktopNotifier {

    data class TrayActions(
        val onShowWindow: () -> Unit = {},
        val onHideWindow: () -> Unit = {},
        val onOpenSettings: () -> Unit = {},
        val onQuitApp: () -> Unit = {}
    )

    /** 保留兼容，空实现。 */
    fun setActions(a: TrayActions) {
        // 无操作
    }

    @Volatile private var trayIcon: TrayIcon? = null
    @Volatile private var initialized = false
    @Volatile private var available = false
    private val initLock = Any()

    private fun ensureInit() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            initialized = true
            try {
                if (!SystemTray.isSupported()) {
                    DesktopLogger.warn("Notify", "系统不支持 SystemTray，通知将只写日志")
                    available = false
                    return
                }
                val tray = SystemTray.getSystemTray()
                val image = buildPlaceholderIcon()
                val icon = TrayIcon(image, "FurAgent").apply {
                    isImageAutoSize = true
                }
                tray.add(icon)
                trayIcon = icon
                available = true
                DesktopLogger.info("Notify", "系统通知已就绪")
            } catch (e: Exception) {
                DesktopLogger.warn(
                    "Notify",
                    "初始化系统通知失败（降级为日志）：${e.javaClass.simpleName}: ${e.message}"
                )
                available = false
            }
        }
    }

    /**
     * 生成一个 16x16 的蓝色方块作为托盘图标。
     * 避免依赖图片资源文件。
     */
    private fun buildPlaceholderIcon(): BufferedImage {
        val size = 16
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(0xFF5A9BFF.toInt())
            g.fillRoundRect(1, 1, size - 2, size - 2, 6, 6)
            g.color = Color.WHITE
            g.font = Font("SansSerif", Font.BOLD, 10)
            val fm = g.fontMetrics
            val text = "F"
            val tw = fm.stringWidth(text)
            g.drawString(text, (size - tw) / 2, size - (size - fm.ascent) / 2)
        } finally {
            g.dispose()
        }
        return img
    }

    /**
     * 发送系统通知。
     *
     * @param title 标题
     * @param content 内容
     */
    fun notify(title: String, content: String) {
        try {
            DesktopLogger.info("Notify", "$title - $content")
        } catch (_: Exception) {
            // 日志本身失败也不能影响后续
        }

        ensureInit()
        if (!available) return

        try {
            val icon = trayIcon ?: return
            icon.displayMessage(
                title.ifBlank { "FurAgent" },
                content.take(500),
                TrayIcon.MessageType.NONE
            )
        } catch (e: Exception) {
            DesktopLogger.warn(
                "Notify",
                "发送系统通知失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    /** 关闭时清理托盘图标。 */
    fun shutdown() {
        try {
            trayIcon?.let { SystemTray.getSystemTray().remove(it) }
        } catch (_: Exception) { }
        trayIcon = null
        available = false
        initialized = false
    }
}