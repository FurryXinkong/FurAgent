package net.xinkong.furagent.desktop.tools

import net.xinkong.furagent.desktop.data.DesktopPaths
import net.xinkong.furagent.shared.tools.ToolResult
import net.xinkong.furagent.shared.util.DiaryStore
import org.json.JSONObject

/**
 * 工作日记工具（桌面端）。
 *
 * 与手机端共用 [DiaryStore]，只是根目录不同：桌面放在 ~/.furagent/diary/。
 * 一天一篇，多次写入追加，不覆盖。
 */
object DiaryTools {

    data class ToolDef(
        val name: String,
        val displayName: String,
        val description: String,
        val paramsSchema: String
    )

    val TOOLS: List<ToolDef> = listOf(
        ToolDef(
            name = "diary",
            displayName = "工作日记",
            description = "工作日记（按天归档，一天一篇，多次写入自动追加）。" +
                    "op=write 写一条日记（需要 title 和 content，可选 tags 数组）：一个任务真正做完后调用一次，" +
                    "内容写清「用户要什么 → 怎么做的 → 改了哪些文件/命令 → 结果 → 坑与结论 → 下次接着做什么」，不要写空话。" +
                    "op=read 读日记（date=yyyy-MM-dd 指定某天，省略则读最近 days 天，默认 1 天；可选 keyword 只取含该词的条目）。" +
                    "op=list 列出有日记的日期。" +
                    "用户问「上次做到哪」或接续跨天任务时先 op=read。",
            paramsSchema = """{"type":"object","properties":{"op":{"type":"string","enum":["write","read","list"]},"title":{"type":"string"},"content":{"type":"string"},"tags":{"type":"array","items":{"type":"string"}},"date":{"type":"string"},"keyword":{"type":"string"},"days":{"type":"integer"},"limit":{"type":"integer"}},"required":["op"]}"""
        )
    )

    val NAMES: Set<String> = TOOLS.map { it.name }.toSet()

    fun displayName(name: String): String =
        TOOLS.firstOrNull { it.name == name }?.displayName ?: name

    fun execute(name: String, args: JSONObject): ToolResult? {
        if (name != "diary") return null
        val op = args.optString("op", "").trim().lowercase()
        val root = DesktopPaths.rootDir()
        return when (op) {
            "write" -> {
                val content = args.optString("content", "").trim()
                if (content.isBlank()) return ToolResult.fail("未提供 content（日记正文不能为空）")
                val title = args.optString("title", "").trim().ifBlank { "工作记录" }
                val tags = mutableListOf<String>()
                args.optJSONArray("tags")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val t = arr.optString(i, "").trim()
                        if (t.isNotBlank()) tags.add(t)
                    }
                }
                try {
                    val r = DiaryStore.write(root, title, content, tags)
                    ToolResult.ok("已写入 ${r.date} 工作日记（当天第 ${r.indexInDay} 条）：${r.path}")
                } catch (e: Exception) {
                    ToolResult.fail("写入日记失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
            "read" -> {
                val date = args.optString("date", "").trim()
                val keyword = args.optString("keyword", "").trim()
                val days = args.optInt("days", 1)
                val text = DiaryStore.read(
                    root = root,
                    date = date.ifBlank { null },
                    keyword = keyword.ifBlank { null },
                    days = days
                )
                if (text.isBlank()) {
                    if (date.isNotBlank()) ToolResult.fail("$date 没有日记记录")
                    else ToolResult.ok("暂无日记记录")
                } else ToolResult.ok(text)
            }
            "list" -> {
                val days = DiaryStore.listDays(root, args.optInt("limit", 30))
                if (days.isEmpty()) ToolResult.ok("暂无日记记录")
                else ToolResult.ok(buildString {
                    appendLine("共 ${days.size} 天有日记（新 → 旧）：")
                    days.forEach { d ->
                        appendLine("- ${d.date}：${d.entries} 条${if (d.preview.isNotBlank()) " · ${d.preview}" else ""}")
                    }
                }.trim())
            }
            else -> ToolResult.fail("op 只能是 write / read / list")
        }
    }
}
