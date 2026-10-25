package net.xinkong.furagent.shared.util

import java.io.File

/**
 * 从消息正文里提取图片路径（双端共用）。
 *
 * 几乎逐字相同的实现（共 2 处）。现统一到这里。
 *
 * 提取三类：
 *  1. Markdown 图片：`![alt](path)`
 *  2. 独立成行的绝对路径（Unix `/` 开头或 Windows 盘符）
 *  3. 【新增】行内嵌的绝对路径（如工具返回“已生成图片：D:\a.png（12KB）”）
 *
 * 只返回本地已存在的文件。
 */
object ImagePathExtractor {

    private val IMAGE_EXT_REGEX = Regex(
        """\.(png|jpe?g|gif|webp|bmp)$""",
        RegexOption.IGNORE_CASE
    )

    private val WINDOWS_ABS = Regex("""^[A-Za-z]:[\\/]""")

    /**
     * 行内绝对路径：Windows 盘符（C:\...）或 Unix（/...）。
     * 允许路径里含中文（如 D:\图片\a.png），只排除空白与明显分隔符
     * （中文/英文括号、引号、逗号、分号、尖括号、竖线），
     * 避免把“已生成图片：D:\a.png（12KB）”里的括号也吃进去。
     */
    private val INLINE_ABS = Regex(
        """(?:[A-Za-z]:[\\/]|/)[^\s（）()\[\]{}<>"'`,;|]+?""" +
                """\.(?:png|jpe?g|gif|webp|bmp)""",
        RegexOption.IGNORE_CASE
    )

    fun extract(content: String): List<String> {
        if (content.isBlank()) return emptyList()
        val out = linkedSetOf<String>()

        // Markdown ![alt](path)
        val mdRegex = Regex("""!\[[^\]]*]\(([^)\s]+)\)""")
        mdRegex.findAll(content).forEach { m ->
            val p = m.groupValues[1].trim()
            if (p.isNotBlank() && IMAGE_EXT_REGEX.containsMatchIn(p)) {
                out.add(p.removePrefix("file://"))
            }
        }

        // 独立成行的绝对路径
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().trim('`', '*', '"', '\'')
            if (line.isBlank()) return@forEach
            val cleaned = line.removePrefix("file://")
            val isAbs = cleaned.startsWith("/") || WINDOWS_ABS.containsMatchIn(cleaned)
            if (isAbs && IMAGE_EXT_REGEX.containsMatchIn(cleaned)) {
                try {
                    val f = File(cleaned)
                    if (f.exists() && f.isFile) out.add(cleaned)
                } catch (_: Exception) {
                    // 路径检查失败，跳过
                }
            }
        }

        // 【新增】行内嵌的绝对路径（如“已生成图片：D:\a.png（12KB）”）。
        // 只对尚未命中的行做，避免重复。
        content.lineSequence().forEach { rawLine ->
            if (rawLine.isBlank()) return@forEach
            INLINE_ABS.findAll(rawLine).forEach { m ->
                val p = m.value.removePrefix("file://")
                try {
                    val f = File(p)
                    if (f.exists() && f.isFile) out.add(p)
                } catch (_: Exception) {
                    // 路径检查失败，跳过
                }
            }
        }

        return out.toList()
    }
}
