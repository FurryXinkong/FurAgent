package net.xinkong.furagent.desktop.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import net.xinkong.furagent.desktop.system.DesktopLogger
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 工具运行状态（统一浮层承载）。
 *
 * 单一职责：所有工具（通用 / ffmpeg / python / execute_command / 未来新增）都通过它上报状态。
 */
object TaskRuntime {

    data class RunningTask(
        val id: String,
        val toolName: String,
        val displayName: String,
        val startedAt: Long,
        val subtitle: String = "",
        val progress: Float? = null,
        val detail: String = "",
        val output: String = "",
        val running: Boolean = true,
        val exitCode: Int? = null,
        val finishedAt: Long = 0L,
        val timedOut: Boolean = false,
        val cancelled: Boolean = false,
        /**
         * 【P0-2】归属会话 id。用于 stopSession 精确取消，不再误杀其他并发会话。
         * 空串 = 不属于任何会话（如定时任务），不会被任何 cancelBySession 命中。
         */
        val sessionId: String = ""
    ) {
        val elapsedMs: Long
            get() = if (running) System.currentTimeMillis() - startedAt
            else (finishedAt - startedAt).coerceAtLeast(0L)
    }

    class TaskIdElement(val id: String) : AbstractCoroutineContextElement(TaskIdElement) {
        companion object Key : CoroutineContext.Key<TaskIdElement>
    }

    private const val KEEP_FINISHED_MS = 6_000L
    private const val MAX_OUTPUT_LEN = 80_000

    private val _tasks = MutableStateFlow<List<RunningTask>>(emptyList())
    val tasks: StateFlow<List<RunningTask>> = _tasks.asStateFlow()

    private val map = ConcurrentHashMap<String, RunningTask>()
    private val cancelCallbacks = ConcurrentHashMap<String, () -> Unit>()

    suspend fun currentId(): String? =
        currentCoroutineContext()[TaskIdElement]?.id

    fun start(
        toolName: String,
        displayName: String,
        subtitle: String = "",
        sessionId: String = ""
    ): String {
        val id = UUID.randomUUID().toString().take(8)
        map[id] = RunningTask(
            id = id,
            toolName = toolName,
            displayName = displayName,
            startedAt = System.currentTimeMillis(),
            subtitle = subtitle.take(500),
            sessionId = sessionId
        )
        refresh()
        return id
    }

    /**
     * 便捷方法：启动任务 → 在 TaskIdElement 上下文里执行 block → 结束任务。
     *
     * 相当于：
     *   val id = start(...)
     *   try { withContext(TaskIdElement(id)) { block(id) } }
     *   finally { finish(id) }
     *
     * 让 DesktopTools.execute 里的样板代码变成一行。
     */
    suspend fun <T> withTask(
        toolName: String,
        displayName: String,
        subtitle: String = "",
        sessionId: String = "",
        block: suspend (taskId: String) -> T
    ): T {
        val id = start(toolName, displayName, subtitle, sessionId)
        return try {
            withContext(TaskIdElement(id)) {
                block(id)
            }
        } finally {
            finish(id)
        }
    }

    fun registerCancel(id: String, cancel: () -> Unit) {
        cancelCallbacks[id] = cancel
    }

    fun cancel(id: String) {
        val old = map[id] ?: return
        if (!old.running) return
        try {
            cancelCallbacks[id]?.invoke()
        } catch (e: Throwable) {
            DesktopLogger.error(
                "Task",
                "取消回调 $id 抛出异常：${e.javaClass.simpleName}: ${e.message}"
            )
        }
        map[id] = old.copy(
            running = false,
            cancelled = true,
            exitCode = -1,
            finishedAt = System.currentTimeMillis()
        )
        cancelCallbacks.remove(id)
        refresh()
    }

    /**
     * 【P0-2】按会话精确取消：只杀属于该会话的正在运行工具，不影响其他并发会话。
     *
     * sessionId 为空的降级：
     *   - 桌面端 stopSession 传具体 sessionId
     *   - 定时任务的工具显式传 ""（不属于任何会话，不会被这里命中）
     *   - 空串调用只匹配 sessionId == "" 的 task，不跨会话误杀
     */
    fun cancelBySession(sessionId: String) {
        map.values
            .filter { it.running && it.sessionId == sessionId }
            .forEach { cancel(it.id) }
    }

    fun updateProgress(id: String, progress: Float?, detail: String) {
        val old = map[id] ?: return
        if (!old.running) return
        map[id] = old.copy(
            progress = progress?.coerceIn(0f, 1f),
            detail = detail.take(300)
        )
        refresh()
    }

    fun appendDetail(id: String, line: String) {
        val old = map[id] ?: return
        if (!old.running) return
        map[id] = old.copy(detail = line.take(300))
        refresh()
    }

    fun appendOutput(id: String, line: String) {
        val old = map[id] ?: return
        if (!old.running) return
        val merged = (old.output + line + "\n").takeLast(MAX_OUTPUT_LEN)
        map[id] = old.copy(
            output = merged,
            detail = line.take(300)
        )
        refresh()
    }

    fun finish(id: String) {
        val old = map[id] ?: return
        if (!old.running) return
        finishWithCode(id, 0, false)
    }

    fun finishWithCode(id: String, exitCode: Int, timedOut: Boolean) {
        val old = map[id] ?: return
        if (!old.running) return
        map[id] = old.copy(
            running = false,
            exitCode = exitCode,
            timedOut = timedOut,
            finishedAt = System.currentTimeMillis()
        )
        cancelCallbacks.remove(id)
        refresh()
    }

    fun remove(id: String) {
        map.remove(id)
        cancelCallbacks.remove(id)
        refresh()
    }

    fun clear() {
        map.clear()
        cancelCallbacks.clear()
        refresh()
    }

    fun tickCleanup() {
        refresh()
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        val stale = map.values.filter {
            !it.running && now - it.finishedAt > KEEP_FINISHED_MS
        }.map { it.id }
        stale.forEach { map.remove(it) }
        _tasks.value = map.values.sortedBy { it.startedAt }
    }
}