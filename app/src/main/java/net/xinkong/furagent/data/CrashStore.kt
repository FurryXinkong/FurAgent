package net.xinkong.furagent.data

import android.content.Context
import androidx.core.content.edit
import net.xinkong.furagent.core.AgentLogger

object CrashStore {
    private const val PREFS = "FurAgentPrefs"
    private const val KEY_CRASH = "last_crash"
    private const val KEY_CRASH_TIME = "last_crash_time"

    fun save(context: Context, stackTrace: String) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit {
                    putString(KEY_CRASH, stackTrace)
                        .putLong(KEY_CRASH_TIME, System.currentTimeMillis())
                }
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "保存崩溃信息失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    fun load(context: Context): String? = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CRASH, null)
    } catch (_: Exception) { null }

    fun loadTime(context: Context): Long = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_CRASH_TIME, 0L)
    } catch (_: Exception) { 0L }

    fun clear(context: Context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit {
                    remove(KEY_CRASH)
                        .remove(KEY_CRASH_TIME)
                }
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "清除崩溃信息失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }
}