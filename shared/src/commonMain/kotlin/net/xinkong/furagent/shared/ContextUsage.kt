package net.xinkong.furagent.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 上下文用量快照。
 *
 * 与消息列表底部的「累计开销」不同，这里统计的是**当前这一轮请求会占用多少上下文**
 * （系统提示词 + 工具定义 + 历史消息），并按 WorkBuddy 的五类口径拆分，
 * 供输入区右下角的「上下文用量」面板展示。
 *
 * 纯 Kotlin，commonMain 可用，不依赖平台 API。
 */
data class ContextUsage(
    val systemTokens: Int = 0,
    val toolsTokens: Int = 0,
    val messagesTokens: Int = 0,
    val connectorTokens: Int = 0,
    val skillTokens: Int = 0,
    /** 预算上限，单位千 token；<=0 表示不限，展示时退回默认窗口。 */
    val budgetK: Int = 100,
    /** 本轮是否发生了上下文压缩。 */
    val compacted: Boolean = false,
    /** 本轮压缩节省的字符数（压缩前 - 压缩后）。 */
    val compactedChars: Int = 0,
    val sessionId: String = ""
) {
    val used: Int get() = systemTokens + toolsTokens + messagesTokens + connectorTokens + skillTokens
    val budget: Int get() = if (budgetK > 0) budgetK * 1000 else DEFAULT_WINDOW
    val ratio: Float get() = (used.toFloat() / budget.coerceAtLeast(1)).coerceIn(0f, 1f)

    /** 某一类占「已用总量」的比例，用于分段进度条。 */
    fun partRatio(part: Int): Float =
        if (used <= 0) 0f else (part.toFloat() / used).coerceIn(0f, 1f)

    /** 某一类占「预算上限」的比例，用于明细右侧的百分比。 */
    fun windowPercent(part: Int): Float =
        (part.toFloat() / budget.coerceAtLeast(1)).coerceIn(0f, 1f)

    val level: String
        get() = when {
            ratio < 0.35f -> "充裕"
            ratio < 0.65f -> "适中"
            ratio < 0.85f -> "偏高"
            else -> "即将压缩"
        }

    companion object {
        /** 未设置预算时用于展示的默认上下文窗口（128K）。 */
        const val DEFAULT_WINDOW: Int = 128_000
    }
}

/** 全局用量总线：Agent 每轮上报，UI 订阅；同时承载「立即压缩」请求。 */
object ContextUsageStore {
    private val _state = MutableStateFlow(ContextUsage())
    val state: StateFlow<ContextUsage> = _state.asStateFlow()

    /** 单调递增的压缩请求计数；Agent 记录已处理的计数来判定新请求。 */
    private val _compactRequests = MutableStateFlow(0L)
    val compactRequests: StateFlow<Long> = _compactRequests.asStateFlow()

    fun report(usage: ContextUsage) {
        _state.value = usage
    }

    /** 面板上点「立即压缩」：下一轮请求强制压缩一次。 */
    fun requestCompact() {
        _compactRequests.value += 1
    }

    /**
     * 已处理的压缩请求序号。
     *
     * commonMain 不能用 @Volatile（JVM 专属注解），这里用普通 var：
     * 最坏情况是多会话并发时重复压缩一次，不影响正确性。
     */
    private var handledCompactSeq = 0L

    /**
     * 由 Agent 在构造历史前调用：只在新请求出现时返回一次 true，
     * 避免「立即压缩」被后续每一轮反复触发。
     */
    fun consumeCompactRequest(): Boolean {
        val seq = _compactRequests.value
        return if (seq > handledCompactSeq) {
            handledCompactSeq = seq
            true
        } else false
    }

    private val _lastCompaction = MutableStateFlow(CompactionInfo())
    val lastCompaction: StateFlow<CompactionInfo> = _lastCompaction.asStateFlow()

    /** 由共享裁剪函数写入，Agent 上报用量时读取。 */
    fun noteCompaction(compacted: Boolean, savedChars: Int) {
        _lastCompaction.value = CompactionInfo(compacted, savedChars.coerceAtLeast(0))
    }

    fun current(): ContextUsage = _state.value
}

/** 一次裁剪/压缩的结果摘要。 */
data class CompactionInfo(
    val compacted: Boolean = false,
    val savedChars: Int = 0
)

/** 把 token 数格式化成 320.1K / 1.2M。 */
fun formatTokenCount(n: Int): String {
    if (n < 1000) return n.toString()
    val k = n / 1000.0
    if (k < 1000.0) return "${(kotlin.math.round(k * 10) / 10.0)}K"
    return "${(kotlin.math.round(k / 100.0) / 10.0)}M"
}

/** 百分比字符串，保留一位小数。 */
fun formatPercent(ratio: Float): String {
    val p = (ratio * 1000).toInt() / 10.0
    return "$p%"
}
