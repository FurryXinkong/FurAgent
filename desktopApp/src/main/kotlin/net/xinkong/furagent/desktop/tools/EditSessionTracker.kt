package net.xinkong.furagent.desktop.tools

import java.util.concurrent.atomic.AtomicInteger

/**
 * 本次 Agent 会话的 code_edit 调用统计。
 *
 * 为什么需要
 *   模型被 RLHF 训练成"一步一步来"：改一个文件、看一次结果、再改下一个。
 *   每多一次工具调用轮次，就要把完整历史（system + 全部对话 + tools）
 *   重发一遍，token 成本翻倍。
 *
 *   光靠 system prompt 里写"必须合并"没用 —— 模型对命令式语气不敏感。
 *   所以把"你到底在第几次调用、已经浪费了多少"直接写进 code_edit 的返回值里，
 *   让模型从工具结果里学到这个模式。
 *
 * 生命周期
 *   DesktopAgent.runTurn 开始时 reset()，每次 code_edit 成功时 record()。
 */
object EditSessionTracker {

    private val callCount = AtomicInteger(0)
    private val filesChanged = AtomicInteger(0)
    private val editsApplied = AtomicInteger(0)
    private val tokensWasted = AtomicInteger(0)

    /**
     * 每次额外调用大致相当于重发一遍完整历史。
     * 19K 是典型值：system ~2K + 历史 ~15K + tools ~2K。
     * 保守取值即可，只用于给模型"痛感"，不需要精确。
     */
    private const val TOKENS_PER_EXTRA_CALL = 19_000

    data class Snapshot(
        val callCount: Int,
        val filesChanged: Int,
        val editsApplied: Int,
        val tokensWasted: Int,
    )

    /**
     * 记录一次成功调用。
     *
     * @param files 本次改动的文件数
     * @param edits 本次应用的替换处数
     */
    fun record(files: Int, edits: Int): Snapshot {
        val newCount = callCount.incrementAndGet()
        filesChanged.addAndGet(files)
        editsApplied.addAndGet(edits)
        // 第 1 次不算浪费；从第 2 次开始每次都是额外成本
        if (newCount > 1) {
            tokensWasted.addAndGet(TOKENS_PER_EXTRA_CALL)
        }
        return Snapshot(
            callCount = newCount,
            filesChanged = filesChanged.get(),
            editsApplied = editsApplied.get(),
            tokensWasted = tokensWasted.get(),
        )
    }

    fun reset() {
        callCount.set(0)
        filesChanged.set(0)
        editsApplied.set(0)
        tokensWasted.set(0)
    }
}