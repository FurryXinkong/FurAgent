package net.xinkong.furagent.shared

/**
 * AI 自主任务清单协议。
 *
 * 系统不再按关键词猜用户意图：要不要规划、何时开工、何时推进下一项，
 * 全部由 AI 自己判断。AI 在回复末尾输出一个 tasks 代码块作为清单，
 * 系统只负责把「上一轮未完成的清单进度」回注到下一轮 system prompt，
 * 避免长任务跨轮丢失进度。
 */
object PlanTasks {

    const val FENCE: String = "```"
    const val MARKER: String = "```tasks"

    enum class Status { PENDING, DOING, DONE }

    data class Task(val title: String, val status: Status)

    /** 注入到系统提示词的执行规范。 */
    val PROTOCOL: String = buildString {
        appendLine("【自主任务规划与执行】")
        appendLine("你不需要等用户逐步下指令。当判断一个请求需要两步或以上时，自己制定任务并连续推进：")
        appendLine("1. 在本轮回复末尾输出任务清单代码块，格式固定（每步一句，按执行顺序）：")
        appendLine(MARKER)
        appendLine("- [ ] 第一步做什么")
        appendLine("- [ ] 第二步做什么")
        appendLine(FENCE)
        appendLine("2. 清单建立后立刻开始执行：把当前项标为 [-]，本轮就可以调用工具，不要只给计划就停下。")
        appendLine("3. 每完成一项改成 [x]，随后立即开始下一项，不要停下来问「要继续吗」；遇到失败先换方案重试。")
        appendLine("4. 每一轮回复都带上更新后的完整清单（含已完成项），让进度可见；全部 [x] 后用一两句话总结结果。")
        appendLine("5. 系统会在清单仍有未完成项时自动阻止你结束并续跑，所以只有真正做完了才标 [x]，不要提前标完成。")
        appendLine("6. 只有确实需要用户决策、或缺少必要信息时才停下提问；此时不要输出清单，直接说明卡点。")
        appendLine("纯问答、单步操作、闲聊不需要清单，直接回答。清单必须是回复最后一个代码块，之后不要再写大段正文。")
    }

    private val BLOCK = Regex("```tasks\\s*\\n([\\s\\S]*?)```")

    /** 解析文本里最后一个任务清单块。 */
    fun parse(text: String): List<Task> {
        val last = BLOCK.findAll(text).lastOrNull() ?: return emptyList()
        return last.groupValues[1].lines().mapNotNull { raw ->
            val line = raw.trim()
            val low = line.lowercase()
            when {
                low.startsWith("- [x] ") -> Task(line.substring(6).trim(), Status.DONE)
                low.startsWith("- [-] ") -> Task(line.substring(6).trim(), Status.DOING)
                low.startsWith("- [ ] ") -> Task(line.substring(6).trim(), Status.PENDING)
                else -> null
            }
        }
    }

    /**
     * 从历史里倒序找最近一份「尚未全部完成」的清单。
     * 全部完成则返回空，避免下一轮又被旧清单牵着走。
     */
    fun latestFrom(historyTexts: List<String>): List<Task> {
        for (i in historyTexts.indices.reversed()) {
            val tasks = parse(historyTexts[i])
            if (tasks.isNotEmpty() && tasks.any { it.status != Status.DONE }) return tasks
        }
        return emptyList()
    }

    /**
     * 供聊天页常驻进度条使用：取会话里**最近一份**任务清单。
     *
     * 与 [latestFrom] 的区别：最近这份即使已全部完成也照样返回，让进度条显示
     * 「✓ 全部完成」而不是突然消失；是否收起由用户点关闭决定。早期实现遇到
     * 全完成就返回空，加上只扫最后 40 条消息，导致任务执行到一半 UI 凭空消失。
     *
     * 从后往前扫全量历史（带上限保护），因为一轮并行工具会产生大量
     * tool_call / tool_result，固定窗口很容易把带清单的 assistant 消息挤出去。
     */
    fun latestForUi(historyTexts: List<String>): List<Task> {
        var scanned = 0
        for (i in historyTexts.indices.reversed()) {
            if (scanned++ >= 400) break
            val tasks = parse(historyTexts[i])
            if (tasks.isNotEmpty()) return tasks
        }
        return emptyList()
    }

    /** 把未完成进度回注到下一轮 system prompt。 */
    fun toPrompt(tasks: List<Task>): String = buildString {
        appendLine("【当前任务进度 · 系统自动回注】")
        tasks.forEachIndexed { i, t ->
            val mark = when (t.status) {
                Status.DONE -> "已完成"
                Status.DOING -> "进行中"
                Status.PENDING -> "待办"
            }
            appendLine("${i + 1}. [$mark] ${t.title}")
        }
        appendLine("请从第一个未完成项继续执行，不要重复已完成项；完成后按规范更新清单状态并继续下一项。")
    }

    /** 从展示文本里移除清单块（供 UI 单独渲染清单卡片）。 */
    fun strip(text: String): String = BLOCK.replace(text, "").trim()

    fun hasBlocks(text: String): Boolean = BLOCK.containsMatchIn(text)

    /** 系统级自动续跑的最大次数，防止模型反复输出同一份未完成清单导致死循环。 */
    const val MAX_AUTO_CONTINUE: Int = 8

    fun hasPending(tasks: List<Task>): Boolean = tasks.any { it.status != Status.DONE }

    /**
     * 清单未完成时由系统注入的续跑指令。
     * 这是硬约束：不依赖模型自律，Agent 循环检测到未完成项就不允许 return。
     */
    fun continuePrompt(tasks: List<Task>): String = buildString {
        appendLine("【系统自动推进 · 任务清单尚未完成】")
        appendLine("你上一条回复里的任务清单还有未完成项，本轮不允许结束。请立即继续执行第一个未完成项所需的工具调用。")
        tasks.forEachIndexed { i, t ->
            val mark = when (t.status) {
                Status.DONE -> "已完成"
                Status.DOING -> "进行中"
                Status.PENDING -> "待办"
            }
            appendLine("${i + 1}. [$mark] ${t.title}")
        }
        appendLine("不要重复已完成项，不要询问是否继续；完成后按规范输出更新后的完整清单。")
    }
}
