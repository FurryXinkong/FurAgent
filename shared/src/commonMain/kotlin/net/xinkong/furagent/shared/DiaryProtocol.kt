package net.xinkong.furagent.shared

/**
 * 工作日记协议。
 *
 * 与「记忆」的分工：
 *  - 记忆（memory）= 跨会话长期事实：用户是谁、偏好、常用路径、项目约定；
 *  - 日记（diary） = 按天的工作流水：今天做了什么、改了哪些文件、结果如何、还剩什么。
 *
 * 为什么要单独做日记工具：
 *  - 对话上下文会被压缩、会话会被新建，跨天回溯只能靠落盘的日记；
 *  - 记忆是无序条目，塞进工作流水会污染长期事实，也会无限膨胀；
 *  - 按天分文件后，AI 只要读「昨天 / 今天」两篇就能接上进度。
 */
object DiaryProtocol {

    /** 注入 system prompt 的写日记规范。 */
    val RULES: String = buildString {
        appendLine("【工作日记 · 必须执行】")
        appendLine("你有一个 diary 工具，用来写按天归档的工作日记（一天一篇，多次写入自动追加，不需要你建文件）。")
        appendLine("何时写：一个任务真正做完后（任务清单全部 [x]、或用户的一次需求已交付）**立即调用 diary(op=write)** 记一条，然后再给用户最终答复。")
        appendLine("不必写：纯闲聊、纯问答、只查了一下信息、以及任务还在中途。同一个任务不要写多条，做完一次写一条。")
        appendLine("写什么（200-600 字，只写事实）：")
        appendLine("- title：一句话说清这件事（如「修复手机端 8GB 文件导入不识别」）")
        appendLine("- content：用户要什么 → 你怎么做的 → 改了哪些文件/路径 → 结果（成功/失败/部分完成）→ 踩到的坑与结论 → 下次要接着做什么")
        appendLine("- tags：2-4 个主题词，便于以后按主题检索")
        appendLine("写法要求：具体到文件路径、命令、参数、错误信息；不要写「已完成任务」这种没有信息量的话；不要复述对话原文。")
        appendLine("何时读：用户问「上次做到哪 / 之前那个问题」、或接着一个跨天任务继续做时，先 diary(op=read, days=2) 看最近两天的记录，不要凭空猜。")
    }

    /** 系统级提醒：本轮干了活但没写日记时注入，不依赖模型自律。 */
    const val NUDGE: String = "【系统提醒 · 还没写工作日记】\n" +
            "本轮你已经执行了实际工作，但还没有调用 diary(op=write)。\n" +
            "请先调用 diary 记录这次任务：用户要什么 / 怎么做的 / 改了哪些文件或命令 / 结果 / 踩到的坑与结论 / 下次接着做什么，然后再给用户最终答复。\n" +
            "只写一条，不要把同一件事拆成多条。"

    /** 把最近的日记注入提示词，让 AI 一上来就知道昨天做到哪。 */
    fun toPrompt(recent: String): String {
        if (recent.isBlank()) return ""
        return buildString {
            appendLine("【最近的工作日记 · 系统自动注入】")
            appendLine("以下是最近几天你写过的工作日记，用于接续跨天任务。它是事实记录，不要重复其中已完成的工作；需要细节时用 diary(op=read) 读原文。")
            appendLine()
            appendLine(recent)
        }.trim()
    }
}
