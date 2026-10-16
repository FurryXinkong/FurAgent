package net.xinkong.furagent.shared.util

import net.xinkong.furagent.shared.tools.ToolLogger
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * HTML → Markdown 转换器（双端共用，纯 Jsoup，无外部依赖）。
 *
 * 设计目标：
 *   1. 保留结构（标题层级 / 列表 / 链接 / 代码块 / 表格 / 引用 / 图片），
 *      信息密度远高于纯文本，同样 token 能装更多内容，因此不必粗暴截断。
 *   2. 剔除噪音（script/style/nav/footer/aside/广告等），只留正文。
 *   3. 输出稳定、可读，便于 LLM 理解。
 *
 * 用法：先 [cleanDocument] 剔除噪音，再 [convert] 得到 Markdown。
 */
object HtmlToMarkdown {

    /** 需要整体删除的噪音标签（含其子树）。 */
    private val NOISE_TAGS = listOf(
        "script", "style", "link", "meta", "noscript", "iframe", "svg", "canvas",
        "nav", "header", "footer", "aside", "form", "button", "input", "select",
        "textarea", "template", "video", "audio", "object", "embed"
    )

    /** 需要删除的噪音选择器（按 class/id 特征匹配）。 */
    private val NOISE_SELECTORS = listOf(
        "[role=navigation]", "[role=banner]", "[role=complementary]", "[role=search]",
        "[class*=sidebar]", "[class*=comment]", "[class*=footer]", "[class*=header]",
        "[class*=nav]", "[class*=menu]", "[class*=cookie]", "[class*=popup]",
        "[class*=modal]", "[class*=advert]", "[class*=banner]", "[class*=share]",
        "[class*=related]", "[class*=recommend]", "[class*=breadcrumb]",
        "[id*=sidebar]", "[id*=comment]", "[id*=footer]", "[id*=nav]",
        "[id*=advert]", "[id*=banner]", "[id*=share]", "[id*=related]"
    )

    /**
     * 剔除噪音节点。就地修改传入的 doc。
     */
    fun cleanDocument(doc: org.jsoup.nodes.Document) {
        for (tag in NOISE_TAGS) {
            try { doc.select(tag).remove() } catch (_: Exception) { }
        }
        for (sel in NOISE_SELECTORS) {
            try { doc.select(sel).remove() } catch (_: Exception) { }
        }
    }

    /**
     * 从整篇文档中挑选「正文根节点」。
     * 优先 article / main / [role=main]，其次常见正文容器，最后回退 body。
     *
     * 关键：用「链接密度」过滤导航区。导航/菜单的特征是大量短链接，
     * 链接文本占比高；正文则相反。因此候选元素若链接密度 > 0.5 则跳过。
     */
    fun pickContentRoot(doc: org.jsoup.nodes.Document): Element {
        val candidates = listOf(
            doc.selectFirst("article"),
            doc.selectFirst("main"),
            doc.selectFirst("[role=main]"),
            doc.selectFirst("#content"),
            doc.selectFirst(".content"),
            doc.selectFirst("#article"),
            doc.selectFirst(".article"),
            doc.selectFirst("#post"),
            doc.selectFirst(".post"),
            doc.selectFirst("#main-content"),
            doc.selectFirst(".main-content")
        )
        // 选文本量最大且链接密度低的候选（>200 字符）
        var best: Element? = null
        var bestLen = 0
        for (c in candidates) {
            if (c == null) continue
            val len = c.text().length
            val ld = linkDensity(c)
            ToolLogger.log("FETCH", "候选 <${c.tagName()} class=${c.className()}> len=$len linkDensity=$ld")
            if (len <= bestLen) continue
            if (ld > 0.5) continue
            best = c
            bestLen = len
        }
        if (best != null && bestLen > 200) {
            ToolLogger.log("FETCH", "pickContentRoot 选中 <${best.tagName()} class=${best.className()}> len=$bestLen")
            return best
        }

        // 候选都不合适时，用「最大文本块」兜底
        val largest = findLargestTextBlock(doc)
        if (largest != null) {
            ToolLogger.log("FETCH", "pickContentRoot 回退最大文本块 <${largest.tagName()} class=${largest.className()}> len=${largest.text().length}")
            return largest
        }

        ToolLogger.log("FETCH", "pickContentRoot 回退 body")
        return doc.body()
    }

    /**
     * 计算元素的「链接密度」：链接文本长度 / 总文本长度。
     * 导航区通常 > 0.5，正文通常 < 0.2。
     * 注意：只对少量候选元素调用，不要在大循环里对每个元素调用（O(n²)）。
     */
    private fun linkDensity(el: Element): Double {
        val total = el.text().length
        if (total == 0) return 0.0
        var linkLen = 0
        for (a in el.select("a")) {
            linkLen += a.text().length
        }
        return linkLen.toDouble() / total
    }

    /**
     * 找出文档中「文本量最大的块级元素」，作为正文兜底。
     *
     * 性能：单次 select 拿到所有块，然后只对「文本量 top N」的候选算链接密度，
     * 避免对每个元素都做嵌套 select（760KB 页面会 O(n²) 卡死/崩溃）。
     */
    fun findLargestTextBlock(doc: org.jsoup.nodes.Document): Element? {
        val blocks = doc.select("div, section, article, main")
        if (blocks.isEmpty()) return null

        // 先按文本量降序取前 20 个候选（不在这里算 linkDensity）
        val top = blocks
            .map { it to it.text().length }
            .filter { it.second > 100 }
            .sortedByDescending { it.second }
            .take(20)

        // 只对 top 候选算链接密度，选第一个「密度低」的
        for ((el, len) in top) {
            if (linkDensity(el) <= 0.5) return el
        }
        // 全部密度都高时，退而求其次返回文本量最大的
        return top.firstOrNull()?.first
    }

    /**
     * 把 HTML 片段转成 Markdown。
     */
    fun convert(root: Element): String {
        val sb = StringBuilder()
        renderChildren(root, sb, 0)
        return normalize(sb.toString())
    }

    // ============================================================
    // 递归渲染
    // ============================================================
    private fun renderChildren(node: Node, sb: StringBuilder, depth: Int) {
        for (child in node.childNodes()) {
            renderNode(child, sb, depth)
        }
    }

    private fun renderNode(node: Node, sb: StringBuilder, depth: Int) {
        when (node) {
            is TextNode -> {
                val t = node.text()
                if (t.isNotBlank()) sb.append(t.replace(Regex("[ \\t\\r\\n]+"), " "))
            }
            is Element -> renderElement(node, sb, depth)
        }
    }

    private fun renderElement(el: Element, sb: StringBuilder, depth: Int) {
        val tag = el.tagName().lowercase()
        when (tag) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val level = tag[1].digitToInt()
                val text = inlineText(el)
                if (text.isNotBlank()) {
                    ensureBlankLine(sb)
                    sb.append("#".repeat(level)).append(' ').append(text).append("\n\n")
                }
            }
            "p" -> {
                val text = inlineText(el)
                if (text.isNotBlank()) {
                    ensureBlankLine(sb)
                    sb.append(text).append("\n\n")
                }
            }
            "br" -> sb.append("\n")
            "hr" -> { ensureBlankLine(sb); sb.append("---\n\n") }
            "strong", "b" -> {
                val text = inlineText(el)
                if (text.isNotBlank()) sb.append("**").append(text).append("**")
            }
            "em", "i" -> {
                val text = inlineText(el)
                if (text.isNotBlank()) sb.append('*').append(text).append('*')
            }
            "code" -> {
                // 行内代码（父级不是 pre）
                if (el.parent()?.tagName()?.lowercase() != "pre") {
                    val text = el.text()
                    if (text.isNotBlank()) sb.append('`').append(text).append('`')
                }
            }
            "pre" -> {
                val code = el.text()
                if (code.isNotBlank()) {
                    ensureBlankLine(sb)
                    sb.append("```\n").append(code.trimEnd()).append("\n```\n\n")
                }
            }
            "blockquote" -> {
                val inner = StringBuilder()
                renderChildren(el, inner, depth)
                val quoted = inner.toString().trim().lines().joinToString("\n") { "> $it" }
                if (quoted.isNotBlank()) {
                    ensureBlankLine(sb)
                    sb.append(quoted).append("\n\n")
                }
            }
            "ul", "ol" -> {
                ensureBlankLine(sb)
                renderList(el, sb, depth, ordered = tag == "ol")
                sb.append("\n")
            }
            "a" -> {
                val href = el.attr("href").trim()
                val text = inlineText(el)
                if (text.isNotBlank()) {
                    if (href.isNotBlank() && !href.startsWith("javascript:")) {
                        sb.append('[').append(text).append("](").append(href).append(')')
                    } else {
                        sb.append(text)
                    }
                }
            }
            "img" -> {
                val src = el.attr("src").trim()
                val alt = el.attr("alt").trim()
                if (src.isNotBlank() && !src.startsWith("data:")) {
                    sb.append("![").append(alt).append("](").append(src).append(')')
                }
            }
            "table" -> {
                ensureBlankLine(sb)
                renderTable(el, sb)
                sb.append("\n")
            }
            "div", "section", "span", "article", "main", "body", "figure", "figcaption", "dl", "dt", "dd" -> {
                renderChildren(el, sb, depth)
            }
            else -> renderChildren(el, sb, depth)
        }
    }

    /** 取元素的行内文本（保留 strong/em/code/a 的 Markdown 标记）。 */
    private fun inlineText(el: Element): String {
        val sb = StringBuilder()
        renderChildren(el, sb, 0)
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun renderList(list: Element, sb: StringBuilder, depth: Int, ordered: Boolean) {
        var index = 1
        for (li in list.children()) {
            if (li.tagName().lowercase() != "li") continue
            val indent = "  ".repeat(depth)
            val marker = if (ordered) "${index}. " else "- "
            // li 的直接文本（不含嵌套列表）
            val direct = StringBuilder()
            for (child in li.childNodes()) {
                if (child is Element && (child.tagName() == "ul" || child.tagName() == "ol")) continue
                renderNode(child, direct, depth)
            }
            val text = direct.toString().replace(Regex("\\s+"), " ").trim()
            if (text.isNotBlank()) {
                sb.append(indent).append(marker).append(text).append("\n")
            }
            // 嵌套列表
            for (child in li.children()) {
                val ct = child.tagName().lowercase()
                if (ct == "ul" || ct == "ol") {
                    renderList(child, sb, depth + 1, ordered = ct == "ol")
                }
            }
            index++
        }
    }

    private fun renderTable(table: Element, sb: StringBuilder) {
        val rows = table.select("tr")
        if (rows.isEmpty()) return
        var headerDone = false
        for (row in rows) {
            val cells = row.select("th, td")
            if (cells.isEmpty()) continue
            val line = cells.joinToString(" | ") { it.text().replace(Regex("\\s+"), " ").trim() }
            sb.append("| ").append(line).append(" |\n")
            if (!headerDone) {
                sb.append("| ").append(cells.joinToString(" | ") { "---" }).append(" |\n")
                headerDone = true
            }
        }
    }

    private fun ensureBlankLine(sb: StringBuilder) {
        if (sb.isEmpty()) return
        val s = sb.toString()
        if (!s.endsWith("\n\n")) {
            if (s.endsWith("\n")) sb.append('\n') else sb.append("\n\n")
        }
    }

    /** 归一化：压缩 3+ 连续空行为 2，去掉行尾空格。 */
    private fun normalize(md: String): String {
        return md.lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}
