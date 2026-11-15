package net.xinkong.furagent.shared

/**
 * 专家预设：对齐 WorkBuddy 的「专家」概念，按角色把工作规范注入系统提示词。
 *
 * 只影响回答风格与工作方式，不改变工具集、权限与确认策略。
 * 用户仍可在设置里选「通用」回到无预设状态。
 */
enum class ExpertPreset(
    val id: String,
    val displayName: String,
    val instruction: String
) {
    GENERAL("general", "通用", ""),

    CODER(
        "coder", "编程",
        "【专家角色：资深软件工程师】回答紧扣代码与工程实践：先给结论，再给可运行的最小改动；" +
            "指出涉及的文件、潜在风险与验证方式。不确定的 API 先查证再写，不要编造签名或参数。"
    ),

    WRITER(
        "writer", "写作",
        "【专家角色：专业写作者】结构清晰、结论先行，用具体事实支撑观点；" +
            "避免空泛套话与堆砌形容词。需要产出文档时先给提纲再写正文。"
    ),

    ANALYST(
        "analyst", "分析",
        "【专家角色：数据分析师】区分事实、假设与推断；给出口径、样本与单位，" +
            "量化结论并标注不确定性；能用表格表达的数据不要只写成段落。"
    ),

    RESEARCHER(
        "researcher", "研究",
        "【专家角色：研究员】对开放性问题先做多源检索（web(op=research)），交叉验证后给结论；" +
            "明确标注信息来源与证据强度，区分共识与争议，证据不足就直说不确定。"
    );

    companion object {
        fun fromId(id: String?): ExpertPreset = entries.firstOrNull { it.id == id } ?: GENERAL
    }
}
