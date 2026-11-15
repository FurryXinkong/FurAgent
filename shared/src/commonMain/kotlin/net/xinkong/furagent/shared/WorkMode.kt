package net.xinkong.furagent.shared

/**
 * 工作模式。
 *
 * 默认 AUTO：系统不猜测意图，把「要不要规划、什么时候开工」交给 AI 自己判断，
 * 由 PlanTasks.PROTOCOL 规定 AI 自主制定任务清单并逐条推进。
 * 全工具始终可用，AI 需要规划时自己输出任务清单，不需要用户切模式。
 *
 * PLAN / ASK 仅作为用户手动锁定的强制行为（工具数组会被置空），
 * 不再由系统按关键词自动触发 —— 规则判定命中率低且容易误判。
 */
enum class WorkMode(
    val id: String,
    val displayName: String,
    val instruction: String,
    val toolsEnabled: Boolean
) {
    /** 默认：AI 自主判断，全工具可用，配合任务清单协议自行推进。 */
    AUTO("auto", "自动", "", true),

    /** 直接执行：不强制输出任务清单，其余与 AUTO 一致。 */
    AGENT("agent", "执行", "", true),

    /** 手动锁定：只出计划、不调用任何工具。 */
    PLAN(
        "plan", "规划",
        "【规划模式】当前只做规划，不执行任何操作，禁止调用工具。" +
            "请给出编号的分步计划：每步说明目标、涉及的文件或命令、预期结果与风险；" +
            "最后询问用户是否确认。用户把模式切回「自动」或「执行」后才会真正动手。",
        false
    ),

    /** 手动锁定：只读问答。 */
    ASK(
        "ask", "问答",
        "【问答模式】当前只做只读问答，禁止调用任何工具，不得修改文件、执行命令或发起写操作。" +
            "基于已有上下文与知识直接回答；信息不足时明确说明缺口，不要臆测。",
        false
    );

    companion object {
        fun fromId(id: String?): WorkMode = entries.firstOrNull { it.id == id } ?: AUTO

        /**
         * 求本轮生效模式。
         *
         * 刻意保留 lastUserText 参数以兼容双端调用点与日志，但不再做关键词匹配：
         * AUTO 一律给 AGENT（全工具），意图判断完全交给 AI 与任务清单协议。
         * 只有用户显式锁定 plan/ask 时才改变行为。
         */
        fun resolve(setting: String, @Suppress("UNUSED_PARAMETER") lastUserText: String = ""): WorkMode {
            val forced = fromId(setting)
            return if (forced == AUTO) AGENT else forced
        }
    }
}
