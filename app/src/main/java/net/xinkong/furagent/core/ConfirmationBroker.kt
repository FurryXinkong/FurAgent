package net.xinkong.furagent.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.xinkong.furagent.data.PendingConfirm
import kotlin.time.Duration.Companion.seconds

/**
 * 权限确认协调器（队列化）。
 *
 * 【为什么需要队列】
 *  工具并行执行时，多个需要确认的工具（write_file / execute_shell / ffmpeg /
 *  file_op）会同时调用 request()。单槽位实现（只有一个 pending + 一个 deferred）会：
 *    - UI 只弹一次（pending 被后到者覆盖）
 *    - 先到的请求等不到 resolve，卡到 300s 超时
 *  现在改成队列：每个请求各自持有 CompletableDeferred，UI 依次弹窗，
 *  resolve 只完成队首，完成后自动把下一个设为 pending。
 *
 * 【Compose State 线程约束】
 *  _pending 是 mutableStateOf，只在主线程写（withContext(Dispatchers.Main)）。
 *  队列本身用 synchronized(lock) 保护，多线程并发安全。
 *
 * 【超时清理】
 *  300 秒无响应视为拒绝。超时后 entry 仍在队列里时移除并推进队首；
 *  正常被 resolve 的路径下 entry 已被移除，此处不会重复处理。
 */
object ConfirmationBroker {

    /**
     * 【P0-5 与桌面端对齐】子会话（后台子代理）协程名。
     *
     * 该上下文里的确认请求没人能点，会一直排队到 300s 超时，
     * 表现为「子会话卡住 5 分钟」。这里做一件事：
     * 给标题加"子代理 ·"前缀，用户知道是谁在请求，
     * 不会误以为是主会话在要权限。
     */
    const val SUBSESSION_COROUTINE_NAME = "FurAgentSubSession"

    private class Entry(
        val confirm: PendingConfirm,
        val deferred: CompletableDeferred<Boolean>
    )

    private var _pending by mutableStateOf<PendingConfirm?>(null)
    val pending: PendingConfirm? get() = _pending

    private val lock = Any()
    private val queue = ArrayDeque<Entry>()

    suspend fun request(title: String, message: String): Boolean {
        // 【P0-5】子会话来源标注（与桌面端 DesktopConfirmation 保持一致）
        val fromSub = kotlinx.coroutines.currentCoroutineContext()[
            kotlinx.coroutines.CoroutineName
        ]?.name == SUBSESSION_COROUTINE_NAME
        val displayTitle = if (fromSub) "子代理 · $title" else title
        AgentLogger.log("SYS", "[确认] 请求：$displayTitle")
        val entry = Entry(PendingConfirm(displayTitle, message), CompletableDeferred())

        val toPublish: PendingConfirm?
        synchronized(lock) {
            val wasEmpty = queue.isEmpty()
            queue.addLast(entry)
            toPublish = if (wasEmpty) entry.confirm else null
        }

        if (toPublish != null) {
            withContext(Dispatchers.Main) { _pending = toPublish }
        }

        AgentLogger.log("SYS", "[确认] 等待用户响应：$displayTitle")
        val approved = withTimeoutOrNull(300.seconds) { entry.deferred.await() } ?: false

        val removed: Boolean
        val newHead: PendingConfirm?
        synchronized(lock) {
            removed = queue.remove(entry)
            newHead = queue.firstOrNull()?.confirm
        }
        if (removed) {
            withContext(Dispatchers.Main) { _pending = newHead }
        }

        if (!approved) {
            AgentLogger.log("ERR", "[确认] 超时或拒绝：$displayTitle")
        } else {
            AgentLogger.log("SYS", "[确认] 用户允许：$displayTitle")
        }
        return approved
    }

    /** UI 层调用，回传用户选择。只完成队首，并推进到下一个。 */
    fun resolve(approved: Boolean) {
        val head: Entry?
        val newHead: PendingConfirm?
        synchronized(lock) {
            head = queue.removeFirstOrNull()
            newHead = queue.firstOrNull()?.confirm
        }
        if (head != null) {
            // UI 层调用，已经在主线程
            _pending = newHead
            head.deferred.complete(approved)
        }
    }
}