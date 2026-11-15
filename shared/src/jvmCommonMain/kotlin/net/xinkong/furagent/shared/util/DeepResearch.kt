package net.xinkong.furagent.shared.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 多源深度研究（对齐 WorkBuddy 的 domain_search 思路）。
 *
 * 把一个主题拆成若干视角分别检索，去重后汇总成结构化摘要，
 * 避免单次搜索只看到一种表述。结果来自搜索摘要，重要结论仍需 fetch 原文核实。
 */
object DeepResearch {

    private val ASPECTS = listOf("概述", "最新进展", "优缺点", "典型应用")

    suspend fun run(topic: String): String = withContext(Dispatchers.IO) {
        if (topic.isBlank()) return@withContext "失败：未提供研究主题"
        val out = StringBuilder()
        out.appendLine("# 深度研究：$topic")
        val seen = HashSet<String>()
        for (aspect in ASPECTS) {
            val raw = try {
                WebSearchEngine.search("$topic $aspect", 1, "")
            } catch (e: Exception) {
                "失败：${e.javaClass.simpleName}: ${e.message}"
            }
            out.appendLine()
            out.appendLine("## $aspect")
            if (raw.startsWith("失败")) {
                out.appendLine(raw)
                continue
            }
            val lines = raw.lines()
                .map { it.trim() }
                .filter { it.length > 12 }
                .distinct()
                .filter { seen.add(it) }
                .take(5)
            if (lines.isEmpty()) out.appendLine("（无新增结果）")
            else lines.forEach { out.appendLine("- $it") }
        }
        out.appendLine()
        out.appendLine("> 结果来自公开搜索摘要，重要结论请用 web(op=fetch) 打开原文核实。")
        out.toString()
    }
}
