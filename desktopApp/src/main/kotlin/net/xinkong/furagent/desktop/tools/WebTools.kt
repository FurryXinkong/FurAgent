package net.xinkong.furagent.desktop.tools

import net.xinkong.furagent.shared.tools.ToolResult
import net.xinkong.furagent.shared.util.WebSearchEngine
import org.json.JSONObject

/**
 * 联网搜索工具（web）。
 * 双端共用 shared 的 WebSearchEngine，桌面端只需提供工具入口。
 */
object WebTools {

    data class ToolDef(
        val name: String, val displayName: String,
        val description: String, val paramsSchema: String
    )

    val TOOLS: List<ToolDef> = listOf(
        ToolDef(
            "web", "联网",
            "联网工具。op=search 搜索（最多 8 条结果，可 page 翻页；可选 engine=bing/baidu/360 指定搜索源，不填按 360→百度→必应 顺序自动回退）；op=fetch 抓取指定 URL 的网页正文（可 max_length 控制长度，默认 8000）。用户提供 URL 且要求总结或查看内容时，直接用 fetch 抓正文，不要只依赖搜索摘要。",
            """{"type":"object","properties":{"op":{"type":"string","enum":["search","fetch"]},"query":{"type":"string"},"page":{"type":"integer"},"engine":{"type":"string"},"url":{"type":"string"},"max_length":{"type":"integer"}},"required":["op"]}"""
        )
    )

    val NAMES: Set<String> = TOOLS.map { it.name }.toSet()

    fun displayName(name: String): String =
        TOOLS.firstOrNull { it.name == name }?.displayName ?: name

    suspend fun execute(name: String, args: JSONObject): ToolResult? {
        if (name != "web") return null
        val op = args.optString("op", "").lowercase()
        return when (op) {
            "search" -> {
                val query = args.optString("query", "").trim()
                val page = args.optInt("page", 1).coerceAtLeast(1)
                val engine = args.optString("engine", "").trim()
                wrapResult(WebSearchEngine.search(query, page, engine))
            }
            "fetch" -> {
                val url = args.optString("url", "").trim()
                val maxLength = args.optInt("max_length", 2000).coerceIn(500, 20000)
                wrapResult(WebSearchEngine.fetchContent(url, maxLength))
            }
            else -> ToolResult.fail("op 只能是 search / fetch")
        }
    }

    private fun wrapResult(out: String): ToolResult {
        return if (out.startsWith("失败")) {
            ToolResult.fail(out.removePrefix("失败："))
        } else {
            ToolResult.ok(out)
        }
    }
}