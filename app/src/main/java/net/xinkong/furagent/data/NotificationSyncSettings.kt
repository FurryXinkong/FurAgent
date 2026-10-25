package net.xinkong.furagent.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.xinkong.furagent.core.AgentLogger

/**
 * 通知同步开关（手机端）。
 *
 * 单向：手机通知 → 电脑。
 *
 * 开关打开后，[net.xinkong.furagent.system.MediaSessionListenerService]
 * 每收到一条通知就通过 [net.xinkong.furagent.bridge.PcBridgeClient]
 * 转发给电脑，电脑端弹系统通知。
 *
 * 状态存 SharedPreferences，进程重启后保持。
 */
object NotificationSyncSettings {

    private const val PREFS = "notification_sync"
    private const val KEY_PHONE_TO_PC = "phone_to_pc"

    private val _phoneToPc = MutableStateFlow(false)

    /** 手机通知 → 电脑，是否开启 */
    val phoneToPc: StateFlow<Boolean> = _phoneToPc.asStateFlow()

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        try {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _phoneToPc.value = p.getBoolean(KEY_PHONE_TO_PC, false)
        } catch (e: Exception) {
            AgentLogger.log(
                "NOTIFY",
                "读取通知同步开关失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    fun setPhoneToPc(context: Context, enabled: Boolean) {
        _phoneToPc.value = enabled
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PHONE_TO_PC, enabled)
                .apply()
        } catch (e: Exception) {
            AgentLogger.log(
                "NOTIFY",
                "保存通知同步开关失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    fun isPhoneToPcEnabled(): Boolean = _phoneToPc.value
}
