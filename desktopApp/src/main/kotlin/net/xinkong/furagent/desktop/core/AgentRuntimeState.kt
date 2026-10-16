package net.xinkong.furagent.desktop.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Agent 运行时状态快照，供独立悬浮窗读取。
 *
 * 为什么需要这个？
 *  - Main.kt 里 App() 内部的运行状态是 composable 局部状态
 *  - 悬浮窗是独立 Window，无法访问 App() 的局部状态
 *  - 用一个全局 StateFlow 让 App() 单向上报、悬浮窗单向读取
 *
 * 【多会话并发】支持同时运行多个会话：sessions 是「运行中的会话」集合，
 * 每个会话一份进度快照。currentSessionId 标记当前选中的会话，
 * 兼容属性（isRunning / thinking / ...）取当前会话的值，供旧调用点无痛使用。
 */
object AgentRuntimeState {

    /** 单个会话的运行时快照。 */
    data class SessionRuntime(
        val sessionId: String,
        val title: String,
        val thinking: String = "",
        val streamingText: String = "",
        val currentToolName: String = "",
        val toolCallCount: Int = 0,
    )

    data class State(
        /** 运行中的会话：sessionId → 快照。 */
        val sessions: Map<String, SessionRuntime> = emptyMap(),
        /** 当前选中的会话 id。 */
        val currentSessionId: String? = null,
    ) {
        /** 当前会话的快照（没在跑则为 null）。 */
        val current: SessionRuntime? get() = currentSessionId?.let { sessions[it] }

        // ---- 兼容旧 API：都取「当前会话」的值 ----
        val isRunning: Boolean get() = current != null
        val thinking: String get() = current?.thinking ?: ""
        val streamingText: String get() = current?.streamingText ?: ""
        val currentToolName: String get() = current?.currentToolName ?: ""
        val toolCallCount: Int get() = current?.toolCallCount ?: 0

        /** 正在运行的会话数。 */
        val runningCount: Int get() = sessions.size
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun snapshot(): State = _state.value

    fun update(block: (State) -> State) {
        _state.value = block(_state.value)
    }

    /** 整体上报：运行中的会话集合 + 当前选中会话。 */
    fun setSessions(sessions: Map<String, SessionRuntime>, currentSessionId: String?) {
        _state.value = State(sessions = sessions, currentSessionId = currentSessionId)
    }

    fun reset() {
        _state.value = State()
    }
}
