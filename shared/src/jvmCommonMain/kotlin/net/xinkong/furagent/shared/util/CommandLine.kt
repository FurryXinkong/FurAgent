package net.xinkong.furagent.shared.util

/**
 * 命令行参数解析（双端共用）。
 *
 * 语义等价于 shell 的最小解析：
 *   `-c "hello world" -o out.txt`
 *   → ["-c", "hello world", "-o", "out.txt"]
 *
 * 逐字符扫描，不走正则，避免转义兼容问题。
 *
 * 各有 1 份相同实现（共 4 处）。现统一到这里。
 */
object CommandLine {

    /**
     * 解析命令行字符串为参数列表。
     *
     * 规则：
     *  - 引号内的空白不切分
     *  - 支持 " 和 ' 两种引号
     *  - 引号字符本身不出现在结果里
     *  - 反斜杠不转义（保持简单，ffmpeg 参数很少含转义需求）
     */
    fun parse(cmd: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote = false
        var quoteChar = ' '
        for (c in cmd) {
            when {
                inQuote -> {
                    if (c == quoteChar) inQuote = false
                    else sb.append(c)
                }
                c == '"' || c == '\'' -> {
                    inQuote = true
                    quoteChar = c
                }
                c.isWhitespace() -> {
                    if (sb.isNotEmpty()) {
                        result.add(sb.toString())
                        sb.clear()
                    }
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) result.add(sb.toString())
        return result
    }
}
