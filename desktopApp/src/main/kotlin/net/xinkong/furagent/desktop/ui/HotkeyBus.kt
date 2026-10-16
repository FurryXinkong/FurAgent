package net.xinkong.furagent.desktop.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 全局快捷键事件总线。
 *
 * Window 层的 onPreviewKeyEvent 往这里推，App 内部订阅消费。
 */
object HotkeyBus {

    enum class Action {
        NEW_CHAT,
        SEARCH_SESSIONS,
        CLEAR_INPUT,
        OPEN_SETTINGS,
        TOGGLE_SIDEBAR
    }

    private val _events = MutableSharedFlow<Action>(extraBufferCapacity = 16)
    val events: SharedFlow<Action> = _events.asSharedFlow()

    fun send(action: Action) {
        _events.tryEmit(action)
    }
}