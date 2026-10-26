package net.xinkong.furagent.desktop.core

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 协程级的会话归属标记。
 *
 * 背景：原实现用全局 @Volatile var CurrentTaskSession.id 承载「当前是哪个会话在执行工具」，
 * 多会话并发时互相覆盖：
 *   - A 会话正在跑 execute_command，期间用户切到 B 发消息
 *   - runAgent(B) 把 CurrentTaskSession.id 改成 B
 *   - A 之后的某个工具读到的 sessionId 就是 "B"，被归到 B 名下
 *   - 用户在 A 点停止，cancelBySession("A") 找不到 A 的工具
 *
 * 正确做法：sessionId 是协程的上下文属性，跟协程走，不跨协程串。
 * DesktopAgent.runTurn 一进来就 withContext(SessionIdElement(sessionId))，
 * 内部所有工具（含子会话、并行分支）都通过 currentSessionId() 读到正确值。
 */
class SessionIdElement(val id: String) : AbstractCoroutineContextElement(SessionIdElement) {
    companion object Key : CoroutineContext.Key<SessionIdElement>
}

/**
 * 读当前协程的会话 id。没有标记则返回 ""。
 *
 * 调用点：
 *   - DesktopTools.execute 默认参数
 *   - HistoryTools.execute 里的历史归属
 *   - confirmThenWrite / confirmThenEdit 里的备份归属
 */
suspend fun currentSessionId(): String =
    currentCoroutineContext()[SessionIdElement]?.id ?: ""
