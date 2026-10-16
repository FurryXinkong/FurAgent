package net.xinkong.furagent.shared.util

/**
 * 等宽字符显示宽度计算。
 *
 * 用于两端 Markdown 渲染器的表格对齐。
 * CJK / 全角字符算 2 宽，其他算 1。
 */
object DisplayWidth {

    fun width(s: String): Int {
        var w = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            w += when (cp) {
                in 0x1100..0x115F -> 2
                in 0x2E80..0x303E -> 2
                in 0x3041..0x33FF -> 2
                in 0x3400..0x4DBF -> 2
                in 0x4E00..0x9FFF -> 2
                in 0xA000..0xA4CF -> 2
                in 0xAC00..0xD7A3 -> 2
                in 0xF900..0xFAFF -> 2
                in 0xFE30..0xFE4F -> 2
                in 0xFF00..0xFF60 -> 2
                in 0xFFE0..0xFFE6 -> 2
                in 0x20000..0x2FFFD -> 2
                else -> 1
            }
            i += Character.charCount(cp)
        }
        return w
    }

    fun padTo(s: String, targetWidth: Int): String {
        val w = width(s)
        if (w >= targetWidth) return s
        return s + " ".repeat(targetWidth - w)
    }
}