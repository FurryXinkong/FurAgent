package net.xinkong.furagent.shared.util

/**
 * 一次 UI dump 的完整结果。
 */
data class ScreenDump(
    val packageName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val nodes: List<UiNode>,
    val totalNodes: Int,
    val truncated: Boolean,
) {
    val isEmpty: Boolean get() = nodes.isEmpty()
}

/**
 * 元素查询条件。所有条件都必须满足（AND 语义）。
 */
data class UiQuery(
    val text: String = "",
    val textExact: Boolean = false,
    val textRegex: String = "",
    val className: String = "",
    val resourceId: String = "",
    val clickable: Boolean? = null,
    val editable: Boolean? = null,
    val scrollable: Boolean? = null,
    val enabled: Boolean? = null,
    val maxResults: Int = 20,
) {
    val isEmpty: Boolean
        get() = text.isBlank() && textRegex.isBlank() && className.isBlank() &&
                resourceId.isBlank() && clickable == null && editable == null &&
                scrollable == null && enabled == null
}

/**
 * 对单个元素执行的动作。
 */
enum class UiAction {
    CLICK,
    LONG_CLICK,
    SET_TEXT,
    CLEAR_TEXT,
    FOCUS,
    SELECT,
    CHECK,
    UNCHECK,
    EXPAND,
    COLLAPSE,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
    SCROLL_UP,
    SCROLL_DOWN,
    SCROLL_LEFT,
    SCROLL_RIGHT,
    ;

    companion object {
        fun parse(s: String): UiAction? =
            entries.firstOrNull { it.name.equals(s.trim(), ignoreCase = true) }
    }
}

/**
 * 系统级动作。
 */
enum class GlobalAction {
    BACK,
    HOME,
    RECENTS,
    NOTIFICATIONS,
    QUICK_SETTINGS,
    LOCK_SCREEN,
    ;

    companion object {
        fun parse(s: String): GlobalAction? =
            entries.firstOrNull { it.name.equals(s.trim(), ignoreCase = true) }
    }
}

/**
 * 手势。坐标是屏幕像素。
 *
 * type:
 *   "tap"         单击
 *   "long_press"  长按（durationMs 建议 >= 600）
 *   "swipe"       滑动（从 start 到 end）
 */
data class UiGesture(
    val type: String,
    val startX: Int,
    val startY: Int,
    val endX: Int = startX,
    val endY: Int = startY,
    val durationMs: Long = 300L,
)

data class ActionResult(
    val success: Boolean,
    val message: String = "",
) {
    companion object {
        fun ok(msg: String = ""): ActionResult = ActionResult(true, msg)
        fun fail(msg: String): ActionResult = ActionResult(false, msg)
    }
}

/**
 * 屏幕控制抽象。Android 无障碍服务 / Windows UIA 都实现这个接口。
 *
 * 生命周期约定：
 *  - [dumpScreen] / [findNodes] 每次调用返回的 [UiNode.id] 与当前时刻的 UI 树绑定。
 *  - 若 UI 发生显著变化（节点被销毁），后续 [performAction] 会因"节点已失效"而失败，
 *    调用方需要重新 dump。
 *  - 实现应使用路径 + 特征校验来检测过期 id，而不是无限期缓存节点引用。
 */
interface ScreenController {
    fun isAvailable(): Boolean

    /** 读取当前屏幕的 UI 树。 */
    suspend fun dumpScreen(maxDepth: Int = 20, maxNodes: Int = 300): ScreenDump

    /** 按条件查找匹配的节点（只在当前 UI 树里查找）。 */
    suspend fun findNodes(query: UiQuery): List<UiNode>

    /** 对指定节点执行动作。[text] 只在 SET_TEXT 时使用。 */
    suspend fun performAction(nodeId: String, action: UiAction, text: String = ""): ActionResult

    /** 执行手势。 */
    suspend fun dispatchGesture(gesture: UiGesture): ActionResult

    /** 系统级动作。 */
    suspend fun globalAction(action: GlobalAction): ActionResult
}

/**
 * 全局注册点。两端在服务就绪时 register，销毁时 unregister。
 *
 * 工具层通过 [get] 拿控制器，不用关心具体是 Android 还是桌面。
 */
object ScreenControlRegistry {
    @Volatile
    private var controller: ScreenController? = null

    fun register(c: ScreenController?) {
        controller = c
    }

    fun get(): ScreenController? = controller

    fun isAvailable(): Boolean = controller?.isAvailable() == true
}