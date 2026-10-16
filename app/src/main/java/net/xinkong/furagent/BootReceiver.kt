package net.xinkong.furagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.data.ScheduleStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            val appContext = context.applicationContext
            val pendingResult = goAsync()
            Thread {
                try {
                    ScheduleStore.pruneExpired(appContext)
                    ScheduleStore.reregisterAll(appContext)
                } catch (e: Exception) {
                    AgentLogger.log(
                        "ERR",
                        "开机恢复定时任务失败：${e.javaClass.simpleName}: ${e.message}"
                    )
                } finally {
                    try { pendingResult.finish() } catch (_: Exception) { }
                }
            }.start()
        }
    }
}