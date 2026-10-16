package net.xinkong.furagent.desktop.system

/**
 * 命令输出压缩。
 *
 * 【为什么需要】
 *   gradle / npm / maven 失败时会吐几百行日志：
 *     - "> Task :shared:compileKotlin UP-TO-DATE" 这类噪音占 80%
 *     - 真正的错误只有几行
 *   如果不裁，这 500 行会进入历史，之后每轮对话都要重发一遍。
 *
 * 【策略】
 *   - 少于阈值（80 行）不裁，避免小输出反被破坏
 *   - 命中"关键模式"的行 + 前后 2 行上下文保留
 *   - 尾部永远保留最后 15 行（退出码、BUILD FAILED、总结通常在末尾）
 *   - 保留量 >= 总行数 - 10 时放弃裁剪（本来就不冗余）
 *   - 输出末尾加一行说明，让模型知道可以 read_file 拿完整日志
 *
 * 【不裁的】
 *   - read_file / code_edit 的返回值（已经是精简的）
 *   - ffmpeg / python 的输出（用户需要看完整过程）
 */
object OutputCompressor {

    private const val MIN_LINES_TO_COMPRESS = 80
    private const val TAIL_KEEP_LINES = 15
    private const val CONTEXT_LINES = 2

    private val IMPORTANT_PATTERNS = listOf(
        // 通用错误
        Regex("""\berror\b""", RegexOption.IGNORE_CASE),
        Regex("""\bfailed\b""", RegexOption.IGNORE_CASE),
        Regex("""\bfailure\b""", RegexOption.IGNORE_CASE),
        Regex("""\bfatal\b""", RegexOption.IGNORE_CASE),
        Regex("""\bexception\b""", RegexOption.IGNORE_CASE),
        Regex("""Caused by""", RegexOption.IGNORE_CASE),

        // Kotlin / Java 编译器
        Regex("""\be:\s"""),
        Regex("""^\s+at\s+[\w.$]+\([\w.]+:\d+\)"""),
        Regex("""BUILD FAILED"""),

        // TypeScript
        Regex("""\berror\s+TS\d+"""),
        Regex("""\bwarning\s+TS\d+"""),

        // gcc / clang
        Regex("""^\s*\d+\s+error[s]?"""),

        // npm / node
        Regex("""\bnpm ERR!"""),

        // 文件路径（常指示出错位置）
        Regex("""([A-Za-z]:\\|/)[^\s:]+\.(kt|java|py|js|ts|json|xml|gradle|kts|mjs|cjs)\b"""),

        // 进程退出 / 中断
        Regex("""Process finished with exit code"""),
        Regex("""Terminated"""),
    )

    /**
     * 压缩命令输出。少于阈值直接返回原值。
     *
     * 线程安全：无状态，可并发调用。
     */
    fun compress(raw: String): String {
        if (raw.isBlank()) return raw
        val lines = raw.split('\n')
        if (lines.size <= MIN_LINES_TO_COMPRESS) return raw

        val keep = mutableSetOf<Int>()

        // 1. 关键行 + 前后上下文
        lines.forEachIndexed { i, line ->
            if (IMPORTANT_PATTERNS.any { it.containsMatchIn(line) }) {
                for (j in (i - CONTEXT_LINES)..(i + CONTEXT_LINES)) {
                    if (j in lines.indices) keep.add(j)
                }
            }
        }

        // 2. 尾部永远保留
        val tailStart = (lines.size - TAIL_KEEP_LINES).coerceAtLeast(0)
        for (i in tailStart until lines.size) keep.add(i)

        // 3. 保留量不够少就放弃（裁剪收益太低不值得，还破坏可读性）
        if (keep.size >= lines.size - 10) return raw

        val sb = StringBuilder()
        var lastKept = -1
        for (i in keep.sorted()) {
            if (i > lastKept + 1) {
                val omitted = i - lastKept - 1
                sb.append("... (省略 ").append(omitted).append(" 行)\n")
            }
            sb.append(lines[i]).append('\n')
            lastKept = i
        }

        val saved = lines.size - keep.size
        sb.append("\n[输出已裁剪：")
            .append(lines.size).append(" 行 → ").append(keep.size).append(" 行，")
            .append("省 ").append(saved).append(" 行。")
            .append("如需完整输出请用 read_file 读取对应日志文件]")

        return sb.toString()
    }
}