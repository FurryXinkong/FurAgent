package net.xinkong.furagent.desktop.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.shared.tools.ToolResult
import net.xinkong.furagent.shared.util.formatFileSize
import org.json.JSONObject
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 屏幕截图工具（screenshot）。
 * 从原 SystemIntegrationTools 拆出，独立成文件便于 vision 能力过滤。
 */
object ScreenshotTools {

    data class ToolDef(
        val name: String, val displayName: String,
        val description: String, val paramsSchema: String
    )

    val TOOLS: List<ToolDef> = listOf(
        ToolDef(
            "screenshot", "屏幕截图",
            "截取整个屏幕并保存为 PNG，图片会自动附加到下一轮对话（AI 能看到）。可选 save_path、screen_index。需要当前模型支持图片输入。",
            """{"type":"object","properties":{"save_path":{"type":"string"},"screen_index":{"type":"integer"}}}"""
        )
    )

    val NAMES: Set<String> = TOOLS.map { it.name }.toSet()

    fun displayName(name: String): String =
        TOOLS.firstOrNull { it.name == name }?.displayName ?: name

    suspend fun execute(name: String, args: JSONObject): ToolResult? {
        if (name != "screenshot") return null
        return doScreenshot(args)
    }

    private suspend fun doScreenshot(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val savePathArg = args.optString("save_path", "").trim()
        val screenIndex = args.optInt("screen_index", -1)
        try {
            val savePath = savePathArg.ifBlank {
                val dir = net.xinkong.furagent.desktop.data.DesktopPaths.screenshotsDir()
                File(dir, "screenshot_${System.currentTimeMillis()}.png").absolutePath
            }
            val target = File(savePath)
            target.parentFile?.mkdirs()
            val robot = Robot()
            val image: BufferedImage = if (screenIndex >= 0) {
                val gc = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                val screens = gc.screenDevices
                if (screenIndex >= screens.size) return@withContext ToolResult.fail("screen_index 越界")
                robot.createScreenCapture(screens[screenIndex].defaultConfiguration.bounds)
            } else {
                val gc = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                robot.createScreenCapture(gc.defaultScreenDevice.defaultConfiguration.bounds)
            }
            ImageIO.write(image, "png", target)
            if (!target.exists() || target.length() == 0L) return@withContext ToolResult.fail("写入失败")
            net.xinkong.furagent.desktop.core.PendingImages.push(savePath)
            ToolResult.ok("已截图：$savePath（${image.width}x${image.height}，${formatFileSize(target.length())}）。图片已附加到下一轮对话，AI 能看到。")
        } catch (e: Exception) { ToolResult.fail("截图失败：${e.message}") }
    }
}