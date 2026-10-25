package net.xinkong.furagent.desktop.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.xinkong.furagent.desktop.data.DesktopPaths
import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.desktop.system.DesktopNotifier
import java.io.File
import java.util.Properties

/**
 * 电脑端通知同步管理器。
 *
 * 职责：
 *  1. 保存「接收手机通知」开关状态（config/notification_sync.properties）
 *  2. 收到手机转发的通知后，交给 [DesktopNotifier] 弹系统通知
 *
 * 单向：手机 → 电脑。
 *
 * 弹窗能力复用已有的 DesktopNotifier（SystemTray + TrayIcon），
 * 这里只负责开关状态和去重，不重复造托盘。
 */
object DesktopNotificationManager {

    private const val CONFIG_FILE = "notification_sync.properties"
    private const val KEY_PHONE_TO_PC = "phone_to_pc"

    /** 同一 key 在这么短时间内重复到达就丢弃（防抖） */
    private const val DEDUP_WINDOW_MS = 3_000L

    private val _phoneToPc = MutableStateFlow(false)

    /** 接收手机通知 → 弹到电脑，是否开启 */
    val phoneToPc: StateFlow<Boolean> = _phoneToPc.asStateFlow()

    /** 最近弹过的通知 key → 时间戳，用于去重 */
    private val recentKeys = LinkedHashMap<String, Long>()

    @Volatile
    private var loaded = false

    // ---------- 开关状态 ----------

    private fun configFile(): File = File(DesktopPaths.configDir(), CONFIG_FILE)

    fun load() {
        if (loaded) return
        loaded = true
        try {
            val f = configFile()
            if (!f.exists()) return
            val p = Properties()
            f.inputStream().use { p.load(it) }
            _phoneToPc.value = p.getProperty(KEY_PHONE_TO_PC, "false").toBoolean()
            DesktopLogger.info("Notify", "通知同步开关：${_phoneToPc.value}")
        } catch (e: Exception) {
            DesktopLogger.warn(
                "Notify",
                "读取通知同步配置失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    fun setPhoneToPc(enabled: Boolean) {
        _phoneToPc.value = enabled
        try {
            val f = configFile()
            f.parentFile?.mkdirs()
            val p = Properties()
            p.setProperty(KEY_PHONE_TO_PC, enabled.toString())
            f.outputStream().use { p.store(it, "FurAgent notification sync") }
            DesktopLogger.info("Notify", "通知同步开关已设为：$enabled")
        } catch (e: Exception) {
            DesktopLogger.warn(
                "Notify",
                "保存通知同步配置失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    fun isPhoneToPcEnabled(): Boolean = _phoneToPc.value

    // ---------- 接收通知 ----------

    /**
     * 处理手机转发来的通知。
     *
     * 开关关闭时直接丢弃。去重窗口内的重复通知也丢弃
     * （手机端可能因为通知更新而重复上报同一条）。
     */
    fun onNotificationReceived(
        appName: String,
        packageName: String,
        title: String,
        text: String,
        postTime: Long,
        notifKey: String
    ) {
        if (!_phoneToPc.value) return

        val dedupKey = notifKey.ifBlank { "$packageName|$title|$text" }
        val now = System.currentTimeMillis()
        synchronized(recentKeys) {
            val last = recentKeys[dedupKey]
            if (last != null && now - last < DEDUP_WINDOW_MS) return
            recentKeys[dedupKey] = now
            // 清理过期条目，避免无限增长
            if (recentKeys.size > 200) {
                val it = recentKeys.entries.iterator()
                while (it.hasNext()) {
                    if (now - it.next().value > DEDUP_WINDOW_MS * 10) it.remove()
                }
            }
        }

        val caption = if (appName.isNotBlank()) appName else "手机通知"
        val body = buildString {
            if (title.isNotBlank()) append(title)
            if (title.isNotBlank() && text.isNotBlank()) append('\n')
            if (text.isNotBlank()) append(text)
        }.ifBlank { "（无内容）" }

        DesktopNotifier.notify(caption, body)
    }
}
