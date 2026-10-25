package net.xinkong.furagent.shared.util

/**
 * 送给 TTS 前的文本清洗（双端共用，P1 抽 shared）。
 *
 * 合并原因：桌面端 ChatPage、Android 端 AgentRunner / TaskExecutionService /
 * MainActivity 各有一份清洗逻辑，且不完全一致：
 *  - 桌面版最完整（15 个正则：代码块、图片、链接、加粗、斜体、标题、列表、URL、路径、emoji）
 *  - Android 版有的只有 3 个（URL、路径、Markdown 符号）
 * 统一到最完整版本。简单场景只是多删几个符号，不会误伤。
 */
object SpeechCleaner {

    private val RE_CODE_BLOCK = Regex("```[\\s\\S]*?```")
    private val RE_INLINE_CODE = Regex("(`[^`\\n]+`)")
    private val RE_IMAGE = Regex("!\\[[^\\]]*]\\([^)]+\\)")
    private val RE_LINK = Regex("\\[([^\\]]+)]\\([^)]+\\)")
    private val RE_BOLD = Regex("\\*\\*([^*]+)\\*\\*")
    private val RE_ITALIC = Regex("(?!\\*)\\*([^*]+)\\*(?!\\*)")
    private val RE_BOLD_UL = Regex("__([^_]+)__")
    private val RE_ITALIC_UL = Regex("_([^_]+)_")
    private val RE_HEADING = Regex("(?m)^\\s*#{1,6}\\s*")
    private val RE_UL_LIST = Regex("(?m)^\\s*[-*+]\\s+")
    private val RE_OL_LIST = Regex("(?m)^\\s*\\d+\\.\\s+")
    private val RE_URL = Regex("https?://\\S+")
    private val RE_PATH = Regex("(?:[A-Za-z]:[\\\\/]|/)(?:[^\\s/]+/)*[^\\s/]*")
    private val RE_EMOJI = Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+")
    private val RE_WHITESPACE = Regex("[ \\t]+")
    private val RE_MULTI_NL = Regex("\\n{2,}")

    fun cleanForSpeech(text: String): String {
        var t = text
        t = RE_CODE_BLOCK.replace(t, "")
        t = RE_INLINE_CODE.replace(t) { it.groupValues[1] }
        t = RE_IMAGE.replace(t, "")
        t = RE_LINK.replace(t) { it.groupValues[1] }
        t = RE_BOLD.replace(t) { it.groupValues[1] }
        t = RE_ITALIC.replace(t) { it.groupValues[1] }
        t = RE_BOLD_UL.replace(t) { it.groupValues[1] }
        t = RE_ITALIC_UL.replace(t) { it.groupValues[1] }
        t = RE_HEADING.replace(t, "")
        t = RE_UL_LIST.replace(t, "")
        t = RE_OL_LIST.replace(t, "")
        t = RE_URL.replace(t, "")
        t = RE_PATH.replace(t, "")
        t = RE_EMOJI.replace(t, "")
        t = RE_WHITESPACE.replace(t, " ")
        t = RE_MULTI_NL.replace(t, "\n")
        t = t.trim()
        return if (t.length > 800) t.take(800) else t
    }
}
