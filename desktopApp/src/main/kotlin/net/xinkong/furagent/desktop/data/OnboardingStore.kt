package net.xinkong.furagent.desktop.data

import net.xinkong.furagent.desktop.system.DesktopLogger
import org.json.JSONObject
import java.io.File

/**
 * 桌面端新手引导状态。
 *
 * 桌面端没有 Android 那种逐个弹权限的需求（系统权限由 Windows 处理，
 * HTTP/WS 端口监听本身就是隐式权限），所以只有"功能导览"一个标记。
 *
 * 存储：~/.furagent/config/onboarding.json
 *   { "guideDone": true }
 */
object OnboardingStore {

    private fun file(): File = File(DesktopPaths.configDir(), "onboarding.json")

    fun isGuideDone(): Boolean {
        return try {
            val f = file()
            if (!f.exists()) {
                false
            } else {
                JSONObject(f.readText(Charsets.UTF_8)).optBoolean("guideDone", false)
            }
        } catch (e: Exception) {
            DesktopLogger.warn(
                "SYS",
                "读取 onboarding 状态失败：${e.javaClass.simpleName}: ${e.message}"
            )
            false
        }
    }

    fun markGuideDone() {
        try {
            val f = file()
            val o = if (f.exists()) {
                try {
                    JSONObject(f.readText(Charsets.UTF_8))
                } catch (_: Exception) {
                    JSONObject()
                }
            } else {
                JSONObject()
            }
            o.put("guideDone", true)

            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString(2), Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(o.toString(2), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            DesktopLogger.warn(
                "SYS",
                "写入 onboarding 状态失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }
}