package net.xinkong.furagent.shared.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具目录与搜索。双端共用。
 *
 * 职责：
 *   - buildCatalogText：把所有非核心工具的"名字 + 一句话描述"拼成目录文本，
 *     塞进 tool_search 工具的 description 里（每个工具约 8~15 token）
 *   - search：关键词匹配，返回相关度排序的工具列表
 *   - renderToolDetail / renderSearchResult：把结果渲染成 AI 易读的文本
 *
 * 搜索策略：纯关键词匹配（不依赖嵌入模型，零外部依赖）。
 *   打分优先级：name 精确 > name 包含 > displayName > 分词命中 name
 *              > description 包含 > 分词命中 display > 分词命中 desc
 *
 * 为什么不用语义搜索：
 *   - 嵌入模型会引入额外的网络请求或本地模型依赖
 *   - 工具数量级（几十到几百）下关键词匹配足够
 *   - AI 本身可以做语义改写（"搜索网页" vs "web 工具"）
 */
object ToolCatalog {

    private val RE_WHITESPACE = Regex("""\s+""")

    /**
     * 构建"目录"文本，会拼进 tool_search 工具的 description。
     *
     * 【token 优化】只输出工具名，不再附 brief 描述。
     * 原因：目录每轮请求都要重发，是固定开销。而 tool_search 本身
     * 就能按关键词搜到工具，目录只需让模型"知道存在哪些工具"即可，
     * 不需要每个工具都带一句话描述。实测可省 60%+ 目录 token。
     *
     * 格式：
     *   [file] list_files read_file write_file ...
     *   [system] execute_shell ...
     */
    fun buildCatalogText(
        tools: List<CatalogTool>,
        disabled: Set<String>,
        visionEnabled: Boolean,
        visionTools: Set<String>
    ): String {
        val sb = StringBuilder()
        var lastCat = ""
        var count = 0
        tools.asSequence()
            .filter { it.name !in ToolCategory.CORE_TOOL_NAMES }
            .filter { it.name !in disabled }
            .filter { visionEnabled || it.name !in visionTools }
            .forEach { t ->
                val cat = ToolCategory.categorize(t.name)
                if (cat != lastCat) {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append("[").append(cat).append("] ")
                    lastCat = cat
                } else {
                    sb.append(" ")
                }
                sb.append(t.name)
                count++
            }
        if (count == 0) return "(暂无其他工具)"
        return sb.toString()
    }

    /**
     * 搜索工具。返回匹配的 CatalogTool 列表，按相关度降序。
     *
     * @param query    自然语言或关键词
     * @param category 可选，按分类过滤（file / system / media / network / screen / remote / mcp / skill / other）
     * @param limit    返回上限（1~20，默认 5）
     */
    fun search(
        query: String,
        category: String?,
        tools: List<CatalogTool>,
        disabled: Set<String>,
        visionEnabled: Boolean,
        visionTools: Set<String>,
        limit: Int = 5
    ): List<CatalogTool> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        val terms = q.split(RE_WHITESPACE).filter { it.isNotBlank() }
        // 【新增】category 支持中文（“文件”“屏幕控制”等）
        val catFilter = category?.trim()?.takeIf { it.isNotBlank() }
            ?.let { ToolCategory.normalizeCategory(it) }
            ?.takeIf { it.isNotBlank() }

        return tools.asSequence()
            .filter { it.name !in ToolCategory.CORE_TOOL_NAMES }
            .filter { it.name !in disabled }
            .filter { visionEnabled || it.name !in visionTools }
            .filter { catFilter == null || ToolCategory.categorize(it.name) == catFilter }
            .map { it to score(it, q, terms) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit.coerceIn(1, 20))
            .map { it.first }
            .toList()
    }

    /**
     * 打分。分数越高越相关。0 表示不匹配。
     *
     * 【中文支持】除了工具自带的英文 name / displayName / description，
     * 额外把 ToolCategory 里挂的中文关键词也拿来撞一遍，
     * 否则中文 query 会全部得 0 分。
     */
    private fun score(t: CatalogTool, q: String, terms: List<String>): Int {
        val name = t.name.lowercase()
        val disp = t.displayName.lowercase()
        val desc = t.description.lowercase()
        val zh = ToolCategory.zhKeywords(t.name)

        val zhWhole = zh.any { it.contains(q) || q.contains(it) }
        val zhTerm = zh.any { k -> terms.any { tm -> tm.isNotEmpty() && k.contains(tm) } }

        return when {
            name == q -> 100
            name.contains(q) -> 60
            zh.any { it.equals(q, ignoreCase = true) } -> 58
            disp.contains(q) -> 50
            zhWhole -> 48
            terms.any { it.length >= 2 && name.contains(it) } -> 40
            desc.contains(q) -> 30
            terms.any { it.length >= 2 && disp.contains(it) } -> 25
            zhTerm -> 22
            terms.any { it.length >= 2 && desc.contains(it) } -> 15
            else -> 0
        }
    }

    /**
     * 把单个工具渲染成"完整定义"文本。
     * 用作 tool_search / tool_load 的返回内容（tool 消息）。
     */
    fun renderToolDetail(t: CatalogTool): String {
        val sb = StringBuilder()
        sb.append("工具名：").append(t.name).append("\n")
        sb.append("分类：").append(ToolCategory.categorize(t.name)).append("\n")
        if (t.displayName.isNotBlank() && t.displayName != t.name) {
            sb.append("显示名：").append(t.displayName).append("\n")
        }
        sb.append("说明：").append(t.description.ifBlank { "(无)" }).append("\n")
        sb.append("参数 Schema：\n").append(t.paramsSchema.ifBlank { "{}" })
        return sb.toString()
    }

    /**
     * 把搜索结果渲染成 JSON（AI 易读）。
     *
     * 结果里带 parameters schema，AI 拿到后可以直接在下一轮调用。
     * 不需要额外的 tool_load 往返。
     */
    fun renderSearchResult(results: List<CatalogTool>): String {
        if (results.isEmpty()) {
            return "未找到匹配工具。\n" +
                    "建议：换更宽泛的关键词，或用 category 过滤（file/system/media/network/screen/remote/mcp/skill）。"
        }
        val arr = JSONArray()
        results.forEach { t ->
            arr.put(JSONObject().apply {
                put("name", t.name)
                put("category", ToolCategory.categorize(t.name))
                // 【token 优化】description 从 400 降到 200 字符，
                // 且不再重复输出 displayName（模型用 name 调用，displayName 无用）。
                put("description", t.description.take(200))
                put("parameters", try {
                    JSONObject(t.paramsSchema)
                } catch (_: Exception) {
                    JSONObject()
                })
            })
        }
        return "找到 ${results.size} 个工具：\n$arr"
    }
}

/**
 * 目录条目的最简结构。
 *
 * ToolRegistry / DesktopTools 把各自的 ToolSpec / DesktopTool 转成这个，
 * 然后交给 ToolCatalog 统一处理。
 */
data class CatalogTool(
    val name: String,
    val displayName: String,
    val description: String,
    val paramsSchema: String,
    /** 目录里显示用的短描述（≤80 字符，无换行）。 */
    val brief: String = description.take(80).replace("\n", " ").trim()
)