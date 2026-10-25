package net.xinkong.furagent.system

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.shared.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * 屏幕控制无障碍服务。
 *
 * 职责：
 *  1. 读取当前屏幕的 UI 树（AccessibilityNodeInfo 递归）
 *  2. 对指定元素执行动作（performAction）
 *  3. 派发手势（dispatchGesture）
 *  4. 执行系统级动作（performGlobalAction）
 *  5. 在操作位置画涟漪，让用户看到"AI 点了这里"（ClickOverlay）
 *
 * 【节点 id 设计】
 *  id 是自增短序号（"1"、"2"、"15"…），不是路径串。
 *  原始路径（root→node 的 child 索引数组）保存在 NodeRef 里，
 *  用于 performAction 时重新定位节点。
 *
 * 【节点过滤（v3）】
 *
 *  三条判据：
 *    - mustKeep      = 有 text/contentDesc/hintText，或可交互（含 checked/selected）
 *    - hasId         = 有 resourceId
 *    - hasChildren   = 有通过了过滤的有意义子孙
 *
 *  处理逻辑：
 *    A. mustKeep → 保留
 *    B. 非 mustKeep + 有子孙 → 穿透（丢弃自己，子孙上提）
 *    C. 非 mustKeep + 无子孙 + 有 id → 保底保留
 *    D. 非 mustKeep + 无子孙 + 无 id → 丢
 *
 *  【v3 修正的三处误判】
 *
 *  1. 不可见元素一刀切丢子树 → 改成"先看子孙有没有可见的，有就上提"
 *     原逻辑 !visible && !mustKeep → 丢整棵子树。isVisibleToUser 会误报
 *     （转场动画 / 多窗口 / alpha=0 待淡入），一个恰好 !mustKeep 的容器
 *     （无 text 的 FrameLayout）被误判后整棵子树消失 → 偶发"屏幕无元素"。
 *
 *  2. 不可见时的 mustKeep 判据太宽 → 引入 mustKeepIfInvisible
 *     不可见时不算 contentDesc。大量不可见元素的图标按钮带 contentDesc
 *     （Overflow 菜单、未选中 tab、ViewPager 预加载页），会被误保下来。
 *
 *  3. RatingBar / StepView 状态丢失 → 把 checked/selected 也纳入交互性判断
 *     这类控件本身是容器，状态在容器上，但容器会被穿透。加 checked/selected
 *     到 isInteractive 里，容器就不会被穿透，状态得以保留。
 *
 *  【穿透 group 标记】
 *  穿透容器的子孙带同一个 groupId，AI 通过 {gN} 标记能识别
 *  "这几个节点来自同一个被压平的容器"（如 RecyclerView 里的一行）。
 *
 *  【为什么不缓存 AccessibilityNodeInfo】
 *   它是系统池化对象，长期持有会影响系统回收。且 recycle() 在 API 33 起是 no-op，
 *   在更早版本上手动 recycle 又容易引入 use-after-recycle 崩溃。
 *   这里采取"只缓存路径 + 特征，操作时重新遍历"的方案。
 *
 *  【关于 CHECK / UNCHECK】
 *   Android 无障碍 API 没有 ACTION_CHECK / ACTION_UNCHECK 常量。
 *   checkbox 的勾选切换用 ACTION_CLICK，但 ACTION_CLICK 是 toggle 语义，
 *   不能保证"勾上/取消"。所以 CHECK / UNCHECK 需要比较 isChecked 状态后再点。
 */
@Suppress("DEPRECATION")
class ScreenAccessibilityService : AccessibilityService() {

    /**
     * 一次 dump 的节点快照，用于后续操作时校验目标是否仍然有效。
     */
    private class NodeRef(
        val path: List<Int>,
        val className: String,
        val resourceId: String,
        val text: String,
        val contentDesc: String,
        val bounds: UiRect,
    ) {
        /**
         * 判断 node 是否仍是当初那个节点。
         *
         * 校验顺序（任一通过即认为一致）：
         *  1. resourceId 相同（最稳）
         *  2. text / contentDesc 相同
         *  3. bounds 相差 <= 30px（UI 微调场景）
         */
        fun matches(node: AccessibilityNodeInfo): Boolean {
            // 【修复 C3】resourceId 相同不等于同一个节点。
            // RecyclerView 里所有行的标题都叫 "com.xxx:id/item_title"，
            // 只靠 resourceId 会误判为同一节点，操作错的元素。
            // 现在：resourceId 相同 + 至少还有一个特征匹配才认定同一节点。
            val curId = node.viewIdResourceName ?: ""
            val idMatch = resourceId.isNotBlank() && curId == resourceId

            val curText = node.text?.toString() ?: ""
            val curDesc = node.contentDescription?.toString() ?: ""
            val textMatch = (text.isNotBlank() && curText == text) ||
                    (contentDesc.isNotBlank() && curDesc == contentDesc)

            val rect = Rect()
            node.getBoundsInScreen(rect)
            val boundsMatch = kotlin.math.abs(rect.left - bounds.left) <= 30 &&
                    kotlin.math.abs(rect.top - bounds.top) <= 30 &&
                    kotlin.math.abs(rect.right - bounds.right) <= 30 &&
                    kotlin.math.abs(rect.bottom - bounds.bottom) <= 30

            // 判据：
            //  - 有 resourceId 时：id 匹配 + (text 匹配 或 bounds 匹配)
            //  - 无 resourceId 时：text 匹配 或 bounds 匹配
            return if (resourceId.isNotBlank()) {
                idMatch && (textMatch || boundsMatch)
            } else {
                textMatch || boundsMatch
            }
        }
    }

    companion object {
        @Volatile
        private var instance: ScreenAccessibilityService? = null

        fun get(): ScreenAccessibilityService? = instance
        fun isRunning(): Boolean = instance != null

        /**
         * 检查系统设置里 FurAgent 无障碍是否已启用。
         * 比"实例是否存在"更准确：服务可能已启用但当前未 bind。
         */
        fun isEnabledInSettings(context: android.content.Context): Boolean {
            return try {
                val flat = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_accessibility_services"
                ) ?: return false
                val target = android.content.ComponentName(
                    context, ScreenAccessibilityService::class.java
                ).flattenToString()
                val targetShort = android.content.ComponentName(
                    context, ScreenAccessibilityService::class.java
                ).flattenToShortString()
                flat.split(":")
                    .any { it.equals(target, true) || it.equals(targetShort, true) }
            } catch (_: Exception) {
                false
            }
        }
    }

    private val controller = object : ScreenController {
        override fun isAvailable(): Boolean = true

        override suspend fun dumpScreen(maxDepth: Int, maxNodes: Int): ScreenDump =
            dumpScreenInternal(maxDepth, maxNodes)

        override suspend fun findNodes(query: UiQuery): List<UiNode> =
            findNodesInternal(query)

        override suspend fun performAction(
            nodeId: String, action: UiAction, text: String,
        ): ActionResult = performActionInternal(nodeId, action, text)

        override suspend fun dispatchGesture(gesture: UiGesture): ActionResult =
            dispatchGestureInternal(gesture)

        override suspend fun globalAction(action: GlobalAction): ActionResult =
            globalActionInternal(action)
    }

    /** 序号字符串 → 特征快照。每次 dump 清空重建。 */
    private val nodeRefs = ConcurrentHashMap<String, NodeRef>()
    private val sessionCounter = AtomicInteger(0)

    /** 主线程 Handler，用于延迟播放下一个涟漪 */
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ScreenControlRegistry.register(controller)
        ClickOverlay.attach(applicationContext)
        AgentLogger.log("UI", "无障碍服务已连接（屏幕控制就绪）")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        if (ScreenControlRegistry.get() === controller) {
            ScreenControlRegistry.register(null)
        }
        ClickOverlay.detach()
        nodeRefs.clear()
        AgentLogger.log("UI", "无障碍服务已销毁")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 纯被动服务：不做事件驱动逻辑
    }

    override fun onInterrupt() {
        // nothing
    }

    // dumpScreen

    private fun dumpScreenInternal(maxDepth: Int, maxNodes: Int): ScreenDump {
        val root = rootInActiveWindow
            ?: return ScreenDump("", 0, 0, emptyList(), 0, false)

        // 新 session：清空旧 id 映射
        nodeRefs.clear()
        sessionCounter.incrementAndGet()

        val pkg = root.packageName?.toString() ?: ""
        val counter = intArrayOf(0)
        val truncated = booleanArrayOf(false)
        // 序号从 1 开始，人类习惯
        val seq = intArrayOf(1)
        // 【新增】穿透 group 计数器：从 1 开始
        val groupCounter = intArrayOf(1)

        val rootList = collectNodes(
            root,
            path = emptyList(),
            depth = 0,
            maxDepth = maxDepth.coerceIn(1, 200),
            maxNodes = maxNodes.coerceIn(10, 100_000),
            counter = counter,
            truncated = truncated,
            seq = seq,
            groupCounter = groupCounter,
            inheritedGroup = 0,
        )

        val dm = resources.displayMetrics
        return ScreenDump(
            packageName = pkg,
            screenWidth = dm.widthPixels,
            screenHeight = dm.heightPixels,
            nodes = rootList,
            totalNodes = counter[0],
            truncated = truncated[0],
        )
    }

    /**
     * 递归收集节点。
     *
     * 【v4：不做豁免】
     *
     * 除 maxDepth / maxNodes 两个总量上限外，不丢弃任何节点：
     *  - 不可见节点照常保留（UiTreeFormatter 标记 (invisible)，AI 自行判断）
     *  - 零尺寸节点照常保留
     *  - 无 text / 无 id 的叶子照常保留
     *
     * 原先的判据里「不可见丢子树 / 零尺寸丢 / 无 id 丢」这三类豁免
     * 会因 isVisibleToUser 误报（转场动画、多窗口、alpha 待淡入）或
     * 元素尚未布局完成而误杀，让 AI 变瞎子，已全部去除。
     *
     * 仅保留穿透：无 text 无交互的容器外壳丢自己、子孙上提并带同一 groupId，
     * 信息零丢失（这是层级压平，不是豁免）。
     *
     * @param path 从 root 到当前节点的 child 索引数组；root 的 path 为空。
     * @param seq 全局自增序号数组（Kotlin 没有闭包可变变量捕获，用数组绕）
     * @param groupCounter 穿透 group 自增计数器
     * @param inheritedGroup 从父级继承的 group 编号（0=无）
     */
    private fun collectNodes(
        node: AccessibilityNodeInfo,
        path: List<Int>,
        depth: Int,
        maxDepth: Int,
        maxNodes: Int,
        counter: IntArray,
        truncated: BooleanArray,
        seq: IntArray,
        groupCounter: IntArray,
        inheritedGroup: Int,
    ): List<UiNode> {
        if (counter[0] >= maxNodes) {
            truncated[0] = true
            return emptyList()
        }
        if (depth > maxDepth) {
            truncated[0] = true
            return emptyList()
        }
        counter[0]++

        val rect = Rect()
        node.getBoundsInScreen(rect)

        val resourceId = node.viewIdResourceName ?: ""
        val className = node.className?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val hintText = node.hintText?.toString() ?: ""
        val visible = node.isVisibleToUser

        // 递归子节点（先递归，拿到 childNodes 后再判自己）
        val childNodes = mutableListOf<UiNode>()
        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (counter[0] >= maxNodes) {
                truncated[0] = true
                break
            }
            val child = node.getChild(i) ?: continue
            val childResult = collectNodes(
                child, path + i, depth + 1, maxDepth, maxNodes,
                counter, truncated, seq, groupCounter,
                inheritedGroup = inheritedGroup,
            )
            childNodes.addAll(childResult)
        }

        // 三判据
        val hasText = text.isNotBlank() || contentDesc.isNotBlank() || hintText.isNotBlank()

        // 【改】checked / selected 也算交互性。
        // 原因：RatingBar / StepView 这类控件是容器，状态在容器上，
        // 但容器会被穿透。加这两个到 isInteractive 里，
        // 有状态的容器不会被穿透，状态得以保留。
        val isInteractive = node.isClickable || node.isLongClickable ||
                node.isEditable || node.isScrollable || node.isCheckable ||
                node.isChecked || node.isSelected

        val mustKeepNormal = hasText || isInteractive

        // 【不做豁免】原先对「不可见 / 零尺寸 / 无文本无 id」的节点会整棵丢弃。
        // 这类过滤会误杀（isVisibleToUser 在转场动画、多窗口、alpha 待淡入时误报；
        // 未布局完成的元素零尺寸），让 AI 变瞎子。
        // 现在除 maxDepth / maxNodes 上限外不做任何丢弃：
        // 不可见节点照常保留，由 UiTreeFormatter 标记 (invisible)，AI 自行判断。

        // 穿透（仅丢无信息的容器外壳，子孙全部保留上提）
        if (!mustKeepNormal) {
            if (childNodes.isNotEmpty()) {
                // B 分支：有子孙 → 穿透（丢自己，子孙上提），同时分配 group
                return assignGroup(childNodes, inheritedGroup, groupCounter)
            }
            // 叶子节点也保留（原先无 id 会丢，属豁免，现去除）
        }

        // 正常保留
        val mySeq = seq[0]++
        val nodeId = mySeq.toString()

        nodeRefs[nodeId] = NodeRef(
            path = path,
            className = className,
            resourceId = resourceId,
            text = text,
            contentDesc = contentDesc,
            bounds = UiRect(rect.left, rect.top, rect.right, rect.bottom),
        )

        // 【actions 精简】
        // 只保留对 AI 有价值的动作名。
        // long_click / focus / select 之类的信息在 flags 里已经有（[long]/[focus]/[sel]）。
        val importantActions = setOf(
            "click",
            "set_text",
            "scroll_forward", "scroll_backward",
            "scroll_up", "scroll_down", "scroll_left", "scroll_right",
            "expand", "collapse",
        )
        val actions = node.actionList
            .mapNotNull { actionToName(it.id) }
            .filter { it in importantActions }

        return listOf(
            UiNode(
                id = nodeId,
                className = className,
                text = text,
                contentDesc = contentDesc,
                resourceId = resourceId,
                hintText = hintText,
                bounds = UiRect(rect.left, rect.top, rect.right, rect.bottom),
                packageName = node.packageName?.toString() ?: "",
                clickable = node.isClickable,
                longClickable = node.isLongClickable,
                scrollable = node.isScrollable,
                editable = node.isEditable,
                checkable = node.isCheckable,
                checked = node.isChecked,
                selected = node.isSelected,
                enabled = node.isEnabled,
                focused = node.isFocused,
                visible = visible,
                groupId = inheritedGroup,
                actions = actions,
                children = childNodes,
            )
        )
    }

    /**
     * 给一组"上提"的节点分配 group 编号。
     *
     * 语义：这些节点来自同一个被穿透的容器，应共享同一个 groupId。
     *
     * 分配规则：
     *   - 父级已经在一个 group 里（inheritedGroup > 0），就沿用父级的编号
     *     （层层穿透时，所有子孙都属于最外层那个被压平的容器）
     *   - 否则新开一个编号
     *   - 已经有 groupId 的节点（自己已经穿透过的）保持原样
     */
    private fun assignGroup(
        nodes: List<UiNode>,
        inheritedGroup: Int,
        groupCounter: IntArray,
    ): List<UiNode> {
        val myGroup = if (inheritedGroup > 0) inheritedGroup else groupCounter[0]++
        return nodes.map { n ->
            if (n.groupId == 0) n.copy(groupId = myGroup) else n
        }
    }

    /**
     * 动作 id → 名字。
     *
     * 注意：Android 无障碍 API 没有 ACTION_CHECK / ACTION_UNCHECK，
     * 所以这里也不会有 "check" / "uncheck" 输出。
     */
    private fun actionToName(actionId: Int): String? = when (actionId) {
        AccessibilityNodeInfo.ACTION_CLICK -> "click"
        AccessibilityNodeInfo.ACTION_LONG_CLICK -> "long_click"
        AccessibilityNodeInfo.ACTION_FOCUS -> "focus"
        AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> "clear_focus"
        AccessibilityNodeInfo.ACTION_SELECT -> "select"
        AccessibilityNodeInfo.ACTION_CLEAR_SELECTION -> "clear_select"
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "scroll_forward"
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "scroll_backward"
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id -> "scroll_up"
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id -> "scroll_down"
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id -> "scroll_left"
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id -> "scroll_right"
        AccessibilityNodeInfo.ACTION_SET_TEXT -> "set_text"
        AccessibilityNodeInfo.ACTION_EXPAND -> "expand"
        AccessibilityNodeInfo.ACTION_COLLAPSE -> "collapse"
        else -> null
    }

    // findNodes

    private suspend fun findNodesInternal(query: UiQuery): List<UiNode> =
        withContext(Dispatchers.Main.immediate) {
            if (query.isEmpty) {
                return@withContext emptyList()
            }

            // find 内部会重新 dump 一次，保证返回的 id 是可操作的
            val dump = dumpScreenInternal(maxDepth = 60, maxNodes = 20_000)
            val results = mutableListOf<UiNode>()
            val regex = try {
                if (query.textRegex.isNotBlank()) Regex(query.textRegex) else null
            } catch (e: Exception) {
                AgentLogger.log("UI", "正则编译失败：${e.message}")
                null
            }
            dump.nodes.forEach { walkAndMatch(it, query, regex, results) }
            results.take(query.maxResults.coerceIn(1, 500))
        }

    private fun walkAndMatch(
        node: UiNode,
        query: UiQuery,
        regex: Regex?,
        out: MutableList<UiNode>,
    ) {
        if (out.size >= query.maxResults) return
        if (matches(node, query, regex)) out.add(node)
        node.children.forEach { walkAndMatch(it, query, regex, out) }
    }

    private fun matches(node: UiNode, query: UiQuery, regex: Regex?): Boolean {
        if (query.text.isNotBlank()) {
            val target = node.displayText
            if (target.isBlank()) return false
            if (query.textExact) {
                if (!target.equals(query.text, ignoreCase = true)) return false
            } else {
                if (!target.contains(query.text, ignoreCase = true)) return false
            }
        }
        if (regex != null) {
            val target = node.displayText
            if (target.isBlank() || !regex.containsMatchIn(target)) return false
        }
        if (query.className.isNotBlank()) {
            if (!node.className.contains(query.className, ignoreCase = true)) return false
        }
        if (query.resourceId.isNotBlank()) {
            if (!node.resourceId.contains(query.resourceId, ignoreCase = true)) return false
        }
        query.clickable?.let { if (node.clickable != it) return false }
        query.editable?.let { if (node.editable != it) return false }
        query.scrollable?.let { if (node.scrollable != it) return false }
        query.enabled?.let { if (node.enabled != it) return false }
        return true
    }

    // performAction

    private suspend fun performActionInternal(
        nodeId: String,
        action: UiAction,
        text: String,
    ): ActionResult = withContext(Dispatchers.Main.immediate) {
        val ref = nodeRefs[nodeId]
            ?: return@withContext ActionResult.fail(
                "节点 id \"$nodeId\" 已失效（可能来自旧的 dump）。请重新调用 ui(op=dump)。"
            )

        val node = findNodeByPath(ref.path)
            ?: return@withContext ActionResult.fail(
                "找不到节点，UI 可能已变化。请重新调用 ui(op=dump)。"
            )

        if (!ref.matches(node)) {
            return@withContext ActionResult.fail(
                "节点已变化（界面可能已刷新），拒绝操作。请重新调用 ui(op=dump) 获取新 id。"
            )
        }

        // 【可视化】CLICK / LONG_CLICK 时先播个涟漪，让用户看到"AI 点了这里"
        if (action == UiAction.CLICK || action == UiAction.LONG_CLICK) {
            try {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    ClickOverlay.show(r.centerX(), r.centerY())
                }
            } catch (_: Exception) { }
        }

        // 【特殊处理】CHECK / UNCHECK。
        // Android 无障碍 API 没有 ACTION_CHECK / ACTION_UNCHECK，
        // checkbox 的切换只能通过 ACTION_CLICK，但 CLICK 是 toggle 语义。
        // 所以先比较 isChecked 状态，只有目标状态和当前不同才点击。
        if (action == UiAction.CHECK || action == UiAction.UNCHECK) {
            val wantChecked = action == UiAction.CHECK
            val isCheckedNow = node.isChecked
            if (isCheckedNow == wantChecked) {
                return@withContext ActionResult.ok(
                    "已经${if (wantChecked) "勾选" else "取消勾选"}，无需操作"
                )
            }
            val ok = try {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (e: Exception) {
                AgentLogger.log(
                    "UI",
                    "CHECK/UNCHECK 点击异常：${e.javaClass.simpleName}: ${e.message}"
                )
                false
            }
            return@withContext if (ok) {
                ActionResult.ok("已${if (wantChecked) "勾选" else "取消勾选"}")
            } else {
                ActionResult.fail("节点不支持切换（可能需要点父节点或兄弟节点）")
            }
        }

        val actionId = when (action) {
            UiAction.CLICK -> AccessibilityNodeInfo.ACTION_CLICK
            UiAction.LONG_CLICK -> AccessibilityNodeInfo.ACTION_LONG_CLICK
            UiAction.FOCUS -> AccessibilityNodeInfo.ACTION_FOCUS
            UiAction.SELECT -> AccessibilityNodeInfo.ACTION_SELECT
            UiAction.EXPAND -> AccessibilityNodeInfo.ACTION_EXPAND
            UiAction.COLLAPSE -> AccessibilityNodeInfo.ACTION_COLLAPSE
            UiAction.SCROLL_FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            UiAction.SCROLL_BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            UiAction.SCROLL_UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            UiAction.SCROLL_DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            UiAction.SCROLL_LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
            UiAction.SCROLL_RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
            UiAction.SET_TEXT -> AccessibilityNodeInfo.ACTION_SET_TEXT
            UiAction.CLEAR_TEXT -> AccessibilityNodeInfo.ACTION_SET_TEXT
            // CHECK / UNCHECK 已在上面 return，不会走到这里
            UiAction.CHECK, UiAction.UNCHECK -> AccessibilityNodeInfo.ACTION_CLICK
        }

        val args = if (action == UiAction.SET_TEXT || action == UiAction.CLEAR_TEXT) {
            android.os.Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    if (action == UiAction.CLEAR_TEXT) "" else text,
                )
            }
        } else null

        val ok = try {
            node.performAction(actionId, args)
        } catch (e: Exception) {
            AgentLogger.log(
                "UI",
                "performAction 异常：${e.javaClass.simpleName}: ${e.message}"
            )
            false
        }

        if (ok) {
            ActionResult.ok("已执行 $action")
        } else {
            val avail = try {
                node.actionList.joinToString(",") { it.id.toString() }
            } catch (_: Exception) { "" }
            ActionResult.fail(
                "performAction 返回失败（节点可能不支持该动作）。" +
                        if (avail.isNotBlank()) "节点可用动作 id：[$avail]" else ""
            )
        }
    }

    private fun findNodeByPath(path: List<Int>): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo = rootInActiveWindow ?: return null
        for (idx in path) {
            if (idx < 0 || idx >= cur.childCount) return null
            cur = cur.getChild(idx) ?: return null
        }
        return cur
    }

    // dispatchGesture

    private suspend fun dispatchGestureInternal(gesture: UiGesture): ActionResult =
        withContext(Dispatchers.Main.immediate) {
            val type = gesture.type.lowercase()
            if (type !in listOf("tap", "long_press", "swipe")) {
                return@withContext ActionResult.fail(
                    "type 只能是 tap / long_press / swipe（收到：${gesture.type}）"
                )
            }

            // 【可视化】手势前先播涟漪
            when (type) {
                "tap", "long_press" -> ClickOverlay.show(gesture.startX, gesture.startY)
                "swipe" -> {
                    ClickOverlay.show(gesture.startX, gesture.startY)
                    // 终点延迟再画一次
                    mainHandler.postDelayed({
                        ClickOverlay.show(gesture.endX, gesture.endY)
                    }, 250L)
                }
            }

            val path = Path().apply {
                moveTo(gesture.startX.toFloat(), gesture.startY.toFloat())
                if (type == "swipe") {
                    lineTo(gesture.endX.toFloat(), gesture.endY.toFloat())
                }
            }

            val duration = when (type) {
                "long_press" -> gesture.durationMs.coerceAtLeast(600L)
                "swipe" -> gesture.durationMs.coerceIn(100L, 5000L)
                else -> gesture.durationMs.coerceIn(50L, 500L)
            }

            val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
            val gestureDesc = GestureDescription.Builder().addStroke(stroke).build()

            val ok = suspendCancellableCoroutine { cont ->
                val callback = object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onCancelled(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
                val dispatched = try {
                    dispatchGesture(gestureDesc, callback, null)
                } catch (e: Exception) {
                    AgentLogger.log(
                        "UI",
                        "dispatchGesture 异常：${e.javaClass.simpleName}: ${e.message}"
                    )
                    false
                }
                if (!dispatched && cont.isActive) cont.resume(false)
            }

            if (ok) ActionResult.ok("手势已执行")
            else ActionResult.fail("手势被系统取消或未派发")
        }

    // globalAction

    private suspend fun globalActionInternal(action: GlobalAction): ActionResult =
        withContext(Dispatchers.Main.immediate) {
            val code = when (action) {
                GlobalAction.BACK -> GLOBAL_ACTION_BACK
                GlobalAction.HOME -> GLOBAL_ACTION_HOME
                GlobalAction.RECENTS -> GLOBAL_ACTION_RECENTS
                GlobalAction.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
                GlobalAction.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
                GlobalAction.LOCK_SCREEN -> GLOBAL_ACTION_LOCK_SCREEN
            }
            val ok = try { performGlobalAction(code) } catch (_: Exception) { false }
            if (ok) ActionResult.ok("$action 已执行")
            else ActionResult.fail("$action 执行失败")
        }
}