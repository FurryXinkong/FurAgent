package net.xinkong.furagent.desktop.system

import java.awt.Desktop
import java.net.URI

/**
 * 桌面端版本号读取。
 *
 * 版本号来源优先级：
 *   1. jpackage 打包后的 exe 里，Manifest 的 Implementation-Version
 *   2. 开发期直接跑 gradle run 时没有 Manifest，退回读系统属性
 *   3. 都拿不到就返回空串，调用方跳过检查更新
 *
 * 版本号格式是 yy.mm.build（如 26.10.24119），纯数字分段，
 * 可以直接和 GitHub Release 的 tag 按段比较。
 */
object DesktopVersion {

    fun current(): String {
        // 1. Manifest（jpackage / jar 打包）
        try {
            val v = DesktopVersion::class.java.`package`?.implementationVersion
            if (!v.isNullOrBlank()) return v
        } catch (_: Throwable) { }

        // 2. 系统属性（开发期由 gradle 传入，见 desktopApp/build.gradle.kts）
        try {
            val v = System.getProperty("furagent.version")
            if (!v.isNullOrBlank()) return v
        } catch (_: Throwable) { }

        return ""
    }
}

/**
 * 用系统默认浏览器打开外部链接。
 *
 * 失败时静默返回 false，由调用方决定要不要提示。
 * 桌面端没有 Activity 那套 Intent，只能走 AWT Desktop。
 */
fun openExternalUrl(url: String): Boolean {
    return try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
            true
        } else {
            false
        }
    } catch (_: Throwable) {
        false
    }
}
