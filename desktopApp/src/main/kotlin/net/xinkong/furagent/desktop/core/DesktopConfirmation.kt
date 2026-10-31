package net.xinkong.furagent.desktop.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * 桌面端确认协调器。
 *
 * 每个请求各自持有 CompletableDeferred，UI 依次弹窗，
 * resolve 只完成队首，完成后自动把下一个设为 pending。
 *
 * 线程模型：request 在协程（可能多线程）调用；resolve 在 UI 线程调用。
 * 队列用 synchronized 保护，保证两者互斥。
 */
object DesktopConfirmation {

    /**
     * 子会话（后台子代理）协程名。该上下文里的确认请求没人能点，
     * 会一直排队到 300s 超时，表现为「子会话卡住 5 分钟」。
     */
    const val SUBSESSION_COROUTINE_NAME = "FurAgentSubSession"

    data class PendingConfirm(
        val title: String,
        val message: String
    )

    private class Entry(
        val confirm: PendingConfirm,
        val deferred: CompletableDeferred<Boolean>
    )

    private val _pending = MutableStateFlow<PendingConfirm?>(null)
    val pending: StateFlow<PendingConfirm?> = _pending.asStateFlow()

    private val lock = Any()
    private val queue = ArrayDeque<Entry>()

    /** 把队首推给 UI（调用方需持有 lock）。 */
    private fun publishHeadLocked() {
        _pending.value = queue.firstOrNull()?.confirm
    }

    /** 挂起等待用户响应。超时或拒绝返回 false。 */
    suspend fun request(title: String, message: String): Boolean {
        // 子代理在后台独立协程里跑，但确认弹窗本来就是全局队列 + 主窗口订阅，
        // 直接转到主会话弹窗即可（用户能点）。这里只给标题标注来源，
        // 避免用户不知道是谁在请求。
        val fromSub = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.CoroutineName]?.name ==
                SUBSESSION_COROUTINE_NAME
        val entry = Entry(
            PendingConfirm(if (fromSub) "子代理 · $title" else title, message),
            CompletableDeferred()
        )
        synchronized(lock) {
            queue.addLast(entry)
            // 只有当前没有正在展示的确认时，才把队首推给 UI
            if (_pending.value == null) publishHeadLocked()
        }

        val approved = withTimeoutOrNull(300_000L.milliseconds) { entry.deferred.await() } ?: false

        // 超时（或异常）后清理：若该 entry 还在队列里，移除并推进到下一个。
        // 正常被 resolve 的路径下，entry 已被移除，这里不会重复处理。
        synchronized(lock) {
            if (queue.remove(entry)) publishHeadLocked()
        }
        return approved
    }

    /**
     * UI 层调用，回传用户选择。只完成队首，并推进到下一个。
     *
     * 超时边界：request 的 withTimeoutOrNull 超时后会自己把 entry 从队列移除，
     * 但若 resolve 恰好也在同一时刻执行，removeFirstOrNull 可能拿到的是
     * 下一个 entry（或 null）。这里在锁内完成「移除 + complete」，
     * 并检查 deferred 是否还 active，避免给已超时的请求补一个迟到的结果。
     */
    fun resolve(approved: Boolean) {
        val head = synchronized(lock) {
            val h = queue.removeFirstOrNull()
            if (h != null) publishHeadLocked()
            h
        }
        // 已超时/已取消的 deferred 不再 complete，避免调用方拿到两次结果
        if (head != null && head.deferred.isActive) {
            head.deferred.complete(approved)
        }
    }
}
