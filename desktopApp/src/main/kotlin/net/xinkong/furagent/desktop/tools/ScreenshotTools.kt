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
            "截取整个屏幕并保存为 PNG，图片会自动附加到下一轮对话（AI 能看到）。可选 save_path（支持 ~ 开头，父目录不存在会自动创建）、screen_index。不传 save_path 时保存到 ~/.furagent/screenshots/。需要当前模型支持图片输入。",
            """{"type":"object","properties":{"save_path":{"type":"string"},"screen_index":{"type":"integer"}}}"""
        )
    )

    val NAMES: Set<String> = TOOLS.map { it.name }.toSet()

    fun displayName(name: String): String =
        TOOLS.firstOrNull { it.name == name }?.displayName ?: name

    /**
     * Java 的 File 不认识 `~`：`~/.furagent/screenshots/x.png` 会被当成
     * 「相对当前工作目录的字面目录名 ~」，在打包运行（工作目录常不可写）时
     * mkdirs 静默失败，ImageIO 再抛 "Can't create an ImageOutputStream!"，
     * 完全看不出是路径问题。这里显式展开到 user.home。
     */
    private fun expandHome(raw: String): String {
        val home = System.getProperty("user.home") ?: return raw
        return when {
            raw == "~" -> home
            raw.startsWith("~/") || raw.startsWith("~\\") -> home + raw.substring(1)
            else -> raw
        }
    }

    suspend fun execute(name: String, args: JSONObject): ToolResult? {
        if (name != "screenshot") return null
        return doScreenshot(args)
    }

    private suspend fun doScreenshot(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val savePathArg = args.optString("save_path", "").trim()
        val screenIndex = args.optInt("screen_index", -1)
        try {
            val savePath = expandHome(savePathArg.ifBlank {
                val dir = net.xinkong.furagent.desktop.data.DesktopPaths.screenshotsDir()
                File(dir, "screenshot_${System.currentTimeMillis()}.png").absolutePath
            })
            val target = File(savePath)

            // 先确保父目录真的可用，再让 ImageIO 写；否则错误信息只会是
            // "Can't create an ImageOutputStream!"，无法定位到目录问题。
            val parent = target.parentFile
            if (parent != null) {
                if (!parent.exists() && !parent.mkdirs()) {
                    return@withContext ToolResult.fail(
                        "截图目录创建失败：${parent.absolutePath}（请检查路径是否合法、是否有写权限；" +
                                "也可以不传 save_path，自动保存到 ~/.furagent/screenshots/）"
                    )
                }
                if (!parent.isDirectory || !parent.canWrite()) {
                    return@withContext ToolResult.fail(
                        "截图目录不可写：${parent.absolutePath}。请换一个有写权限的目录，或不传 save_path。"
                    )
                }
            }
            if (target.exists() && target.isDirectory) {
                return@withContext ToolResult.fail("save_path 指向的是目录，不是文件：$savePath")
            }

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
            // ImageIO.write 在目录不可写时会抛 IIOException("Can't create an ImageOutputStream!")，
            // 补上真实路径，避免用户无从排查。
            try {
                val written = ImageIO.write(image, "png", target)
                if (!written) return@withContext ToolResult.fail("PNG 编码失败：$savePath")
            } catch (e: Exception) {
                return@withContext ToolResult.fail(
                    "截图写入失败（目标：$savePath，父目录：${parent?.absolutePath}）：${e.message}"
                )
            }
            if (!target.exists() || target.length() == 0L) {
                return@withContext ToolResult.fail("截图写入失败：文件未生成或为空（$savePath）")
            }
            net.xinkong.furagent.desktop.core.PendingImages.push(savePath)
            ToolResult.ok("已截图：$savePath（${image.width}x${image.height}，${formatFileSize(target.length())}）。图片已附加到下一轮对话，AI 能看到。")
        } catch (e: Exception) {
            ToolResult.fail("截图失败：${e.message}（若使用了 save_path，请确认目录存在且可写）")
        }
    }
}
