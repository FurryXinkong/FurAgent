package net.xinkong.furagent.shared.util

import org.json.JSONObject

/**
 * tool_call 消息的 content 格式固定为 "显示名\n{参数 JSON}"（双端 Agent 一致）。
 * 解析出显示名与主参数摘要，供工作过程折叠组的紧凑行展示。
 *
 * @return Pair(显示名, 主参数摘要)。摘要优先取路径类 key（path/file/url 等），
 * 否则取第一个非空字符串值；JSON 解析失败时摘要为空串。
 */
fun toolCallLabel(content: String): Pair<String, String> {
    val nl = content.indexOf('\n')
    val name = if (nl >= 0) content.substring(0, nl) else content.trim()
    if (nl < 0 || nl >= content.length - 1) return name to ""
    val summary = runCatching { JSONObject(content.substring(nl + 1)) }
        .getOrNull()
        ?.let { pickArgSummary(it) }
        ?: ""
    return name to summary
}

private val PATH_KEYS = setOf("path", "file", "file_path", "url", "input", "save_path", "target")

private fun pickArgSummary(obj: JSONObject): String {
    val keys = obj.keys()
    val fallback = mutableListOf<String>()
    while (keys.hasNext()) {
        val k = keys.next()
        val v = obj.opt(k) as? String ?: continue
        if (v.isBlank()) continue
        if (k.lowercase() in PATH_KEYS) return clipSummary(v)
        fallback.add(v)
    }
    return fallback.firstOrNull()?.let { clipSummary(it) } ?: ""
}

private fun clipSummary(s: String) = if (s.length > 80) s.take(79) + "…" else s

/** tool_result 输出行数，用于「输出 N 行」摘要。 */
fun toolResultLineCount(content: String): Int =
    content.lineSequence().count { it.isNotBlank() }.coerceAtLeast(
        if (content.isBlank()) 0 else 1
    )

/**
 * 中间内容是否需要折叠显示（双端共用阈值）。
 *
 * 超过 [maxLines] 行或超过 [maxChars] 字符即折叠，点击气泡展开。
 */
fun shouldCollapseContent(
    content: String,
    maxLines: Int = 2,
    maxChars: Int = 160
): Boolean =
    content.length > maxChars || content.count { it == '\n' } >= maxLines
