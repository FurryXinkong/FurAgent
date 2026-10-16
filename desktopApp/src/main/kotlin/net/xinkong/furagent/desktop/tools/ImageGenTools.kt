package net.xinkong.furagent.desktop.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.desktop.data.ApiKeyResolver
import net.xinkong.furagent.desktop.data.DesktopSettingsHolder
import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.shared.tools.ToolHost
import net.xinkong.furagent.shared.tools.ToolResult
import net.xinkong.furagent.shared.util.ImageGenEngine
import org.json.JSONObject
import java.io.File

/**
 * 生图工具（仅桌面端）。
 *
 * 核心生图逻辑在 shared 的 [ImageGenEngine]（与手机端共用），
 * 本类只负责：路径解析、从设置里取文生图模型配置。
 */
object ImageGenTools {

    data class ToolDef(
        val name: String,
        val displayName: String,
        val description: String,
        val paramsSchema: String
    )

    val TOOLS: List<ToolDef> = listOf(
        ToolDef(
            name = "generate_image",
            displayName = "AI 生图",
            description = "用 AI 生成图片并保存到本地。需要先在「设置 → 智能体 → 文生图模型」里选中一个生图模型。" +
                    "生成后返回图片路径，可用 view_image 查看。" +
                    "【保存位置】建议存到专属图片文件夹 ~/.furagent/images/（如 ~/.furagent/images/xxx.png）。" +
                    "save_path 可省略，省略时自动存到 ~/.furagent/images/ 下（时间戳命名）。",
            paramsSchema = """{"type":"object","properties":{""" +
                    """"prompt":{"type":"string","description":"图片描述（越具体越好）"},""" +
                    """"save_path":{"type":"string","description":"保存路径。推荐存到专属图片文件夹 ~/.furagent/images/xxx.png。可省略，省略时自动存到 ~/.furagent/images/。支持 ~ 和中文别名（桌面/图片/下载）"},""" +
                    """"size":{"type":"string","description":"尺寸，如 1024x1024 / 1024x1536 / 1792x1024，默认 1024x1024"},""" +
                    """"model":{"type":"string","description":"可选，覆盖配置里的生图模型名"}""" +
                    """},"required":["prompt"]}"""
        )
    )

    val NAMES: Set<String> = TOOLS.map { it.name }.toSet()

    fun displayName(name: String): String =
        TOOLS.firstOrNull { it.name == name }?.displayName ?: name

    suspend fun execute(name: String, args: JSONObject, host: ToolHost): ToolResult? {
        if (name !in NAMES) return null

        val prompt = args.optString("prompt", "").trim()
        if (prompt.isBlank()) return ToolResult.fail("generate_image 需要 prompt 参数")

        val savePathRaw = args.optString("save_path", "").trim()
        val savePath = resolveSavePath(savePathRaw, host)
            ?: return ToolResult.fail("无法解析保存路径：$savePathRaw")

        val settings = DesktopSettingsHolder.current

        // 文生图模型（kind == "image"，由「设置 → 智能体 → 文生图模型」选中）
        val imageProfile = settings.currentImageProfile()
            ?: return ToolResult.fail(
                "未配置文生图模型。请到「设置 → 智能体 → 文生图模型」里添加并选中一个" +
                        "（如 doubao-seedream-5.0-pro / dall-e-3 / gpt-image-1）。"
            )

        val model = args.optString("model", "").trim()
            .ifBlank { imageProfile.model.trim() }
        if (model.isBlank()) {
            return ToolResult.fail("文生图模型未填「模型名」，请到设置里补上。")
        }

        val apiKeyResult = ApiKeyResolver.resolve(settings, imageProfile)
        if (apiKeyResult !is ApiKeyResolver.Result.Found) {
            return ToolResult.fail("无法获取 API Key：${(apiKeyResult as ApiKeyResolver.Result.NotFound).message}")
        }

        val size = args.optString("size", "1024x1024").trim().ifBlank { "1024x1024" }

        return withContext(Dispatchers.IO) {
            try {
                val result = ImageGenEngine.generate(
                    baseUrl = imageProfile.baseUrl,
                    model = model,
                    apiKey = apiKeyResult.key,
                    prompt = prompt,
                    size = size,
                    target = File(savePath)
                )

                if (!result.ok) {
                    return@withContext ToolResult.fail(result.message)
                }

                val extra = if (result.revisedPrompt.isNotBlank()) {
                    "\n（模型改写后的提示词：${result.revisedPrompt}）"
                } else ""
                ToolResult.ok("已生成图片：$savePath（${result.bytes / 1024}KB）$extra")
            } catch (e: Exception) {
                DesktopLogger.error(
                    "ImageGenTools",
                    "生图失败：${e.javaClass.simpleName}: ${e.message}"
                )
                ToolResult.fail("生图失败：${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * 解析生图保存路径：
     *   - 空 → 自动落到 ~/.furagent/images/（时间戳命名）
     *   - 纯文件名（无目录分隔）→ 落到 ~/.furagent/images/ 下
     *   - 其他 → 交给 host.resolvePath（支持 ~ / 中文别名）
     */
    private fun resolveSavePath(raw: String, host: ToolHost): String? {
        val defaultDir = host.resolvePath("~/.furagent/images")
        val name = raw.trim()
        if (name.isBlank()) {
            val dir = defaultDir ?: return null
            File(dir).mkdirs()
            return File(dir, "img_${System.currentTimeMillis()}.png").absolutePath
        }
        // 纯文件名（不含路径分隔符）→ 放进专属目录
        if (!name.contains('/') && !name.contains('\\')) {
            val dir = defaultDir ?: return host.resolvePath(name)
            File(dir).mkdirs()
            return File(dir, name).absolutePath
        }
        return host.resolvePath(name)
    }
}
