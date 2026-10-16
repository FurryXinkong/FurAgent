package net.xinkong.furagent.shared.util

/**
 * Token 粗估工具。
 *
 * 因为不再向 API 请求 stream_options（部分网关不认这个字段），
 * 流式响应的 usage 统计取不到了。这里用经验公式估算：
 *
 *   CJK 字符（中日韩 + 全角标点）：约 1.5 字符 / token
 *   其他字符（ASCII / 拉丁 / 数字 / 半角标点）：约 4 字符 / token
 *
 * 该公式基于 GPT-4 系列 tokenizer 统计，中文为主的文本误差约 ±15%。
 * 仅用于 UI 显示，不做计费依据。
 */
object TokenEstimator {

    /** 单张图片的估算 token 数（OpenAI 低清标准约 85，高清约 1105，这里取中间值） */
    const val PER_IMAGE_TOKENS: Int = 200

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        var i = 0
        val n = text.length
        while (i < n) {
            val cp = text.codePointAt(i)
            if (isCjk(cp)) cjk++ else other++
            i += Character.charCount(cp)
        }
        val result = cjk / 1.5 + other / 4.0
        return result.toInt().coerceAtLeast(1)
    }

    private fun isCjk(cp: Int): Boolean = when (cp) {
        in 0x3000..0x303F -> true    // CJK 符号和标点（、。「」等）
        in 0x3040..0x309F -> true    // 平假名
        in 0x30A0..0x30FF -> true    // 片假名
        in 0x3400..0x4DBF -> true    // CJK 扩展 A
        in 0x4E00..0x9FFF -> true    // CJK 统一表意（常用汉字）
        in 0xAC00..0xD7AF -> true    // 韩文音节
        in 0xF900..0xFAFF -> true    // CJK 兼容表意
        in 0xFF00..0xFFEF -> true    // 全角字符（全角字母、标点）
        in 0x20000..0x2A6DF -> true  // CJK 扩展 B
        in 0x2A700..0x2B73F -> true  // CJK 扩展 C
        in 0x2B740..0x2B81F -> true  // CJK 扩展 D
        in 0x2B820..0x2CEAF -> true  // CJK 扩展 E
        else -> false
    }
}