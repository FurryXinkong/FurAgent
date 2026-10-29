package net.xinkong.furagent.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.data.AppSettings
import net.xinkong.furagent.data.currentImageProfile
import net.xinkong.furagent.shared.util.ImageGenEngine
import org.json.JSONObject
import java.io.File

/**
 * 生图工具（仅手机端）。
 *
 * 核心生图逻辑在 shared 的 [ImageGenEngine]（与桌面端共用），
 * 本类只负责：路径解析、从设置里取文生图模型配置。
 *
 * 默认保存到 /sdcard/FurAgent/images/。
 */
object ImageGenTools {

    val TOOLS = setOf("generate_image")

    suspend fun execute(
        name: String, args: JSONObject, context: Context, settings: AppSettings
    ): String {
        if (name != "generate_image") return "未实现的生图工具：$name"

        val prompt = args.optString("prompt", "").trim()
        if (prompt.isBlank()) return "失败：generate_image 需要 prompt 参数"

        val savePathRaw = args.optString("save_path", "").trim()
        val savePath = resolveSavePath(savePathRaw, context)
            ?: return "失败：无法解析保存路径：$savePathRaw"

        // 文生图模型（kind == "image"，由「设置 → 文生图模型」选中）
        val imageProfile = settings.currentImageProfile()
            ?: return "失败：未配置文生图模型。请到「设置 → 文生图模型」里添加并选中一个" +
                    "（如 doubao-seedream-5.0-pro / dall-e-3 / gpt-image-1）。"

        val model = args.optString("model", "").trim()
            .ifBlank { imageProfile.model.trim() }
        if (model.isBlank()) {
            return "失败：文生图模型未填「模型名」，请到设置里补上。"
        }

        val apiKey = imageProfile.apiKey.trim()
        if (apiKey.isBlank()) {
            return "失败：文生图模型未填 API Key，请到设置里补上。"
        }

        val size = args.optString("size", "1024x1024").trim().ifBlank { "1024x1024" }

        return withContext(Dispatchers.IO) {
            try {
                val result = ImageGenEngine.generate(
                    baseUrl = imageProfile.baseUrl,
                    model = model,
                    apiKey = apiKey,
                    prompt = prompt,
                    size = size,
                    target = File(savePath)
                )

                if (!result.ok) {
                    "失败：${result.message}"
                } else {
                    val extra = if (result.revisedPrompt.isNotBlank()) {
                        "\n（模型改写后的提示词：${result.revisedPrompt}）"
                    } else ""
                    "已生成图片：$savePath（${result.bytes / 1024}KB）$extra"
                }
            } catch (e: Exception) {
                AgentLogger.log(
                    "ERR",
                    "生图失败：${e.javaClass.simpleName}: ${e.message}"
                )
                "生图失败：${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    /**
     * 解析生图保存路径：
     *   - 空 → /sdcard/FurAgent/images/img_<时间戳>.png
     *   - 纯文件名 → /sdcard/FurAgent/images/<文件名>
     *   - 其他 → normalizeAlias 展开
     */
    private fun resolveSavePath(raw: String, context: Context): String? {
        // 用 outputDir 而非直接 File(SDCARD_PATH, ...)：
        // 分区存储 / 无存储权限时 mkdirs() 静默失败，后续写入抛 FileNotFoundException，
        // 报错完全看不出是权限问题。outputDir 会自动回退到 app 私有目录。
        val defaultDir = outputDir(context, "images")
        val name = raw.trim()
        if (name.isBlank()) {
            defaultDir.mkdirs()
            return File(defaultDir, "img_${System.currentTimeMillis()}.png").absolutePath
        }
        if (!name.contains('/') && !name.contains('\\')) {
            defaultDir.mkdirs()
            return File(defaultDir, name).absolutePath
        }
        return normalizeAlias(name)
    }
}
