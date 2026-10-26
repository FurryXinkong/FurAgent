package net.xinkong.furagent.shared.util

/**
 * 把 UI 树渲染为 LLM 友好的紧凑文本。
 *
 * 单行格式：
 *   [id] ClassName #resourceId "text" @(l,t,r,b) [flags] (disabled) (invisible) {gN}
 *
 * flags 是逗号分隔的能力缩写：
 *   click / long / edit / scroll / check / checked / sel / focus
 *
 * {gN} 只在节点来自"被穿透的父容器"时出现（groupId != 0）。
 * 同一组内的节点通常有并列关系（同一列表行、同一组 Tab）。
 *
 * 不做豁免 全部节点照常渲染，不按"是否有意义"做过滤——
 * 过滤会让 AI 看不到本应看到的元素。层级缩进保持原树结构。
 *
 * 默认不截断
 *  maxChars / maxLines / nodeTextLimit 默认都是 0，表示不限制。
 *  UI 树的文本量本身不大（复杂屏幕也就几万字符），真正的 token 大户是图片。
 *  用户如果确实想限制，可以显式传参。
 */
object UiTreeFormatter {

    fun toText(
        dump: ScreenDump,
        maxChars: Int = 0,
        maxLines: Int = 0,
        nodeTextLimit: Int = 0,
    ): String {
        if (dump.isEmpty) return "（当前屏幕无可读 UI 节点）"

        val sb = StringBuilder()
        sb.append("Package: ${dump.packageName.ifBlank { "(unknown)" }}\n")
        sb.append("Screen: ${dump.screenWidth}x${dump.screenHeight}\n")
        sb.append("Nodes: ${dump.totalNodes}")
        if (dump.truncated) sb.append(" (truncated)")
        sb.append("\n\n")

        val lineCount = intArrayOf(0)
        dump.nodes.forEach { appendNode(sb, it, 0, lineCount, maxLines, nodeTextLimit) }

        val result = sb.toString()
        return if (maxChars > 0 && result.length > maxChars) {
            result.take(maxChars) +
                    "\n\n...(UI 树过长已截断，完整长度 ${result.length} 字符)"
        } else result
    }

    private fun appendNode(
        sb: StringBuilder,
        node: UiNode,
        depth: Int,
        lineCount: IntArray,
        maxLines: Int,
        nodeTextLimit: Int,
    ) {
        if (maxLines > 0 && lineCount[0] >= maxLines) return

        // 不做豁免 原先 isSignificant 为 false 的节点会被跳过（"无意义空容器"），
        // 属于会让 AI 看不到元素的过滤，现全部渲染。
        val indent = "  ".repeat(depth.coerceAtMost(15))
        sb.append(indent).append(buildNodeLine(node, nodeTextLimit)).append('\n')
        lineCount[0]++

        node.children.forEach { appendNode(sb, it, depth + 1, lineCount, maxLines, nodeTextLimit) }
    }

    private fun buildNodeLine(node: UiNode, textLimit: Int): String {
        val sb = StringBuilder()
        sb.append('[').append(node.id).append(']')
        sb.append(' ').append(node.shortClassName.ifBlank { "Node" })
        if (node.resourceId.isNotBlank()) {
            sb.append(" #").append(shortResourceId(node.resourceId))
        }
        val text = node.displayText
        if (text.isNotBlank()) {
            val limited = if (textLimit > 0 && text.length > textLimit) {
                text.take(textLimit) + "…"
            } else text
            sb.append(" \"").append(limited.replace("\n", "\\n")).append('"')
        }
        sb.append(" @(")
            .append(node.bounds.left).append(',')
            .append(node.bounds.top).append(',')
            .append(node.bounds.right).append(',')
            .append(node.bounds.bottom).append(')')

        val flags = buildFlags(node)
        if (flags.isNotEmpty()) {
            sb.append(" [").append(flags.joinToString(",")).append(']')
        }
        if (!node.enabled) sb.append(" (disabled)")
        if (!node.visible) sb.append(" (invisible)")
        // 来源容器组标记
        if (node.groupId != 0) {
            sb.append(" {g").append(node.groupId).append('}')
        }
        return sb.toString()
    }

    private fun buildFlags(node: UiNode): List<String> {
        val flags = mutableListOf<String>()
        if (node.clickable) flags.add("click")
        if (node.longClickable) flags.add("long")
        if (node.editable) flags.add("edit")
        if (node.scrollable) flags.add("scroll")
        if (node.checkable) flags.add("check")
        if (node.checked) flags.add("checked")
        if (node.selected) flags.add("sel")
        if (node.focused) flags.add("focus")
        return flags
    }

    private fun shortResourceId(id: String): String =
        id.substringAfterLast('/').ifBlank { id }

    fun describeQuery(q: UiQuery): String {
        val parts = mutableListOf<String>()
        if (q.text.isNotBlank()) {
            parts.add(if (q.textExact) "text=${q.text}" else "text~${q.text}")
        }
        if (q.textRegex.isNotBlank()) parts.add("regex=${q.textRegex}")
        if (q.className.isNotBlank()) parts.add("class=${q.className}")
        if (q.resourceId.isNotBlank()) parts.add("id=${q.resourceId}")
        q.clickable?.let { parts.add("clickable=$it") }
        q.editable?.let { parts.add("editable=$it") }
        q.scrollable?.let { parts.add("scrollable=$it") }
        q.enabled?.let { parts.add("enabled=$it") }
        return if (parts.isEmpty()) "(空条件)" else parts.joinToString(", ")
    }

    fun describeNode(node: UiNode): String = buildNodeLine(node, 0)
}