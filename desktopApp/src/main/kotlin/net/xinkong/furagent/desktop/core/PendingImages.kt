package net.xinkong.furagent.desktop.core

/**
 * 工具执行期间产生的待附加图片。
 * 例如 view_image / screenshot 会把路径推到这里，
 * Agent 循环下一轮把它们作为多模态内容追加到上下文。
 */
object PendingImages {
    private val list = mutableListOf<String>()

    @Synchronized
    fun push(path: String) {
        if (path.isNotBlank()) list.add(path)
    }

    @Synchronized
    fun drain(): List<String> {
        val r = list.toList()
        list.clear()
        return r
    }

    @Synchronized
    fun clear() {
        list.clear()
    }
}