package net.xinkong.furagent.desktop.ui

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import net.xinkong.furagent.desktop.system.DesktopLogger
import java.awt.Image
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * 应用图标。
 *
 * 图标文件在 `desktopApp/src/main/resources/icon.png`（512×512 RGBA）。
 *
 * 两套接口：
 *  - [painter]：给 Compose 的 Window(icon = ...) 用
 *  - [awtImage]：给 AWT 的 TrayIcon 用
 *
 * 统一走 AWT 的 ImageIO 加载，再转成 Compose 的 ImageBitmap。
 * 不用 painterResource 是因为它是 @Composable，不能包 try-catch，
 * 而图标缺失不该让应用起不来。
 *
 * 加载失败时返回 null / 占位图，不抛异常。
 */
object AppIcon {

    private const val RES_PATH = "icon.ico"

    @Volatile
    private var cachedBuffered: BufferedImage? = null

    @Volatile
    private var cachedPainter: Painter? = null

    @Volatile
    private var painterResolved = false

    /** 从 classpath 读 icon.ico，失败返回 null。 */
    private fun loadBuffered(): BufferedImage? {
        cachedBuffered?.let { return it }
        synchronized(this) {
            cachedBuffered?.let { return it }
            val img = try {
                val stream = AppIcon::class.java.classLoader?.getResourceAsStream(RES_PATH)
                if (stream == null) {
                    DesktopLogger.warn("Icon", "classpath 里找不到 $RES_PATH")
                    null
                } else {
                    stream.use { ImageIO.read(it) }
                }
            } catch (e: Exception) {
                DesktopLogger.warn(
                    "Icon",
                    "读取图标失败：${e.javaClass.simpleName}: ${e.message}"
                )
                null
            }
            cachedBuffered = img
            return img
        }
    }

    /**
     * Compose 窗口图标。加载失败返回 null（窗口用系统默认图标）。
     *
     * 注意：这是普通函数，不是 @Composable，可以在任意位置调用。
     */
    fun painter(): Painter? {
        if (painterResolved) return cachedPainter
        synchronized(this) {
            if (painterResolved) return cachedPainter
            painterResolved = true
            cachedPainter = try {
                loadBuffered()?.toComposeImageBitmap()?.let { BitmapPainter(it) }
            } catch (e: Exception) {
                DesktopLogger.warn(
                    "Icon",
                    "转换窗口图标失败：${e.javaClass.simpleName}: ${e.message}"
                )
                null
            }
            return cachedPainter
        }
    }

    /**
     * AWT 图标（托盘用）。
     *
     * 优先从 classpath 读 icon.ico；读不到就退回代码画的占位图，
     * 保证托盘永远有图标可显示。
     */
    fun awtImage(): Image = loadBuffered() ?: buildPlaceholder()

    /** 兜底：16×16 蓝色圆角方块 + 白色 F。 */
    private fun buildPlaceholder(): BufferedImage {
        val size = 16
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON
            )
            g.color = java.awt.Color(0xFF8B93FF.toInt())
            g.fillRoundRect(1, 1, size - 2, size - 2, 6, 6)
            g.color = java.awt.Color.WHITE
            g.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 10)
            val fm = g.fontMetrics
            val text = "F"
            val tw = fm.stringWidth(text)
            g.drawString(text, (size - tw) / 2, size - (size - fm.ascent) / 2)
        } finally {
            g.dispose()
        }
        return img
    }
}
