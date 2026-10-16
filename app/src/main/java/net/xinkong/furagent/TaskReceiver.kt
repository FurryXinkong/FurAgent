package net.xinkong.furagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.service.TaskExecutionService

/**
 * 定时任务广播接收器。
 *
 * 【职责简化】
 *  只做"转发"：把 BroadcastReceiver 收到的 Intent 交给 TaskExecutionService。
 *  为什么：
 *    - BroadcastReceiver 的 goAsync() 只有 10 秒（前台）/ 30 秒（后台）窗口
 *    - run_workflow / run_cron 可能跑几分钟到几小时，必然被系统 KILL
 *    - 前台服务没有这个限制，且能持 WakeLock
 *
 * 实际执行逻辑全部在 TaskExecutionService 里。
 */
class TaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        try {
            TaskExecutionService.start(appContext, intent)
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "[定时任务] 转发到执行服务失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }
}