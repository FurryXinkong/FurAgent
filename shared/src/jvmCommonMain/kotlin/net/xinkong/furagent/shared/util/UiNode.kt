package net.xinkong.furagent.shared.util

/**
 * UI 元素边界矩形（屏幕像素坐标）。
 */
data class UiRect(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val isValid: Boolean get() = width > 0 && height > 0

    override fun toString(): String = "($left,$top,$right,$bottom)"
}

/**
 * 统一的 UI 节点抽象。
 *
 * Android 无障碍节点（AccessibilityNodeInfo）和 Windows UIA 元素都归一到这个结构。
 *
 * AI 通过 [id] 引用节点，工具层根据 [id] 找到真实节点并执行动作。
 * [id] 只在一次 dump 会话内有效，跨 dump 必须重新获取。
 */
data class UiNode(
    val id: String,
    val className: String = "",
    val text: String = "",
    val contentDesc: String = "",
    val resourceId: String = "",
    val hintText: String = "",
    val bounds: UiRect = UiRect(),
    val packageName: String = "",
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val visible: Boolean = true,
    /**
     * 【新增】来源容器组。
     *
     * 当一个"无 text、无交互、但有子孙"的容器被穿透过滤时，它的子孙会被上提。
     * 这些上提的子孙带同一个 groupId，表示它们来自同一个被压平的容器。
     *
     * 典型场景：RecyclerView 里每行的 container 被压平后，行内的
     * [icon] [text] [delete_btn] 带同一个 groupId，AI 才能区分
     * "这 10 个删除按钮分别属于哪一行"。
     *
     * 0 表示不属于任何被穿透的容器（默认）。
     */
    val groupId: Int = 0,
    /** 可执行动作名（如 "click"、"set_text"、"scroll_forward"），供 AI 参考。 */
    val actions: List<String> = emptyList(),
    val children: List<UiNode> = emptyList(),
) {
    /** 展示用文本：优先 text，其次 contentDesc，再次 hintText。 */
    val displayText: String
        get() = text.ifBlank { contentDesc.ifBlank { hintText } }

    /** 短类名：android.widget.Button → Button */
    val shortClassName: String
        get() = className.substringAfterLast('.').ifBlank { className }

    /** 是否是"有意义的"节点——有文本、有 resourceId、或有可交互属性。 */
    val isSignificant: Boolean
        get() = displayText.isNotBlank() || resourceId.isNotBlank() ||
                clickable || longClickable || scrollable || editable ||
                checkable || selected || focused
}