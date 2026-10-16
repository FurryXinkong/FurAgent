package net.xinkong.furagent.desktop.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Agent 控制指令总线。
 *
 * 悬浮窗是独立 Window，无法直接调用 App() 里的 stopAgent() 函数。
 * 用一个全局 SharedFlow 把"停止请求"从悬浮窗传出去，App() 里 collect 后处理。
 */
object AgentControlBus {

    sealed class Command {
        /** 停止当前 Agent 任务（取消协程 + 清状态 + 停 TTS） */
        data object StopAgent : Command()

        /** 打开主窗口 */
        data object OpenMainWindow : Command()
    }

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 4)
    val commands: SharedFlow<Command> = _commands.asSharedFlow()

    fun stopAgent() {
        _commands.tryEmit(Command.StopAgent)
    }

    fun openMainWindow() {
        _commands.tryEmit(Command.OpenMainWindow)
    }
}