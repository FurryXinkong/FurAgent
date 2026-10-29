package net.xinkong.furagent.desktop.tools

import net.xinkong.furagent.desktop.data.MemoryStore
import net.xinkong.furagent.shared.tools.ToolResult
import org.json.JSONObject

object MemoryTools {

    data class ToolDef(
        val name: String,
        val displayName: String,
        val description: String,
        val paramsSchema: String
    )

    val TOOLS: List<ToolDef> = listOf(
        ToolDef(
            name = "memory",
            displayName = "记忆",
            description = "长期记忆管理。op=save 保存一条记忆（content）；op=recall 按 query 检索记忆（query 为空则列出全部）；op=list 列出所有记忆（带 ID）；op=delete 按 memory_id 或 keyword 删除。当用户透露姓名、职业、偏好、习惯、忌讳或明确纠正你的事实时主动保存，不要等用户说\"记住\"。",
            paramsSchema = """{"type":"object","properties":{"op":{"type":"string","enum":["save","recall","list","delete"]},"content":{"type":"string"},"query":{"type":"string"},"memory_id":{"type":"string"},"keyword":{"type":"string"}},"required":["op"]}"""
        )
    )

    val NAMES: Set<String> = TOOLS.map { it.name }.toSet()

    fun displayName(name: String): String =
        TOOLS.firstOrNull { it.name == name }?.displayName ?: name

    fun execute(name: String, args: JSONObject): ToolResult? {
        if (name != "memory") return null
        val op = args.optString("op", "").lowercase()
        return when (op) {
            "save" -> doSave(args)
            "recall" -> doRecall(args)
            "list" -> doList()
            "delete" -> doDelete(args)
            else -> ToolResult.fail("op 只能是 save / recall / list / delete")
        }
    }

    private fun doSave(args: JSONObject): ToolResult {
        val content = args.optString("content", "").trim()
        if (content.isBlank()) return ToolResult.fail("未提供 content")
        val m = MemoryStore.add(content)
        return ToolResult.ok("已记住：${m.content}")
    }

    private fun doRecall(args: JSONObject): ToolResult {
        val query = args.optString("query", "").trim()
        // 只返回已启用的记忆：用户在记忆页关掉的条目，AI 不该看到。
        // 之前直接调 MemoryStore.list()/search() 没过滤 enabled，
        // 导致「关掉」的记忆照样被 AI 读出来，等于开关失效。
        val list = (if (query.isBlank()) MemoryStore.list() else MemoryStore.search(query))
            .filter { it.enabled }
        if (list.isEmpty()) return ToolResult.ok("暂无相关记忆")
        val sb = StringBuilder("找到 ${list.size} 条记忆：\n")
        list.take(30).forEach { sb.append("- ${it.content}\n") }
        return ToolResult.ok(sb.toString())
    }

    private fun doList(): ToolResult {
        val list = MemoryStore.list().filter { it.enabled }
        if (list.isEmpty()) return ToolResult.ok("暂无记忆")
        val sb = StringBuilder("共 ${list.size} 条记忆：\n\n")
        list.forEachIndexed { i, m ->
            sb.append("${i + 1}. ${m.content}\n")
            sb.append("   ID：${m.id}\n\n")
        }
        return ToolResult.ok(sb.toString())
    }

    private fun doDelete(args: JSONObject): ToolResult {
        val id = args.optString("memory_id", "").trim()
        val keyword = args.optString("keyword", "").trim()
        val list = MemoryStore.list()
        val target = when {
            id.isNotBlank() -> list.firstOrNull { it.id == id }
            keyword.isNotBlank() -> list.firstOrNull { it.content.contains(keyword, ignoreCase = true) }
            else -> null
        } ?: return ToolResult.fail("未找到匹配的记忆")
        MemoryStore.remove(target.id)
        return ToolResult.ok("已删除：${target.content}")
    }
}