package net.xinkong.furagent.shared.util

import java.util.Locale

/**
 * 路径别名展开（双端共用）。
 *
 *   - AndroidToolHost.expandAlias（支持 "别名/子路径"）
 *   - ToolRegistry.normalizeAlias（只支持全等匹配，不支持子路径）
 *   - DesktopToolHost.expandAlias
 * 行为不一致。现统一到这里。
 *
 * 关键行为：取路径首段做别名展开，剩余部分拼回。
 *   "下载/a.zip" -> "$sdcard/Download/a.zip"
 *   "桌面"       -> "$home/Desktop"
 */
object PathAlias {

    /**
     * 展开 Android 端路径别名。
     *
     * @param sdcardPath 外部存储根路径
     */
    fun expandAndroid(path: String, sdcardPath: String): String {
        val t = path.trim().trim('"', '\'', '`')
        if (t.isBlank()) return t

        val normalized = t.replace('\\', '/')
        val slash = normalized.indexOf('/')
        val head = if (slash >= 0) normalized.substring(0, slash) else normalized
        val tail = if (slash >= 0) normalized.substring(slash) else ""

        val base = when (head.lowercase(Locale.ROOT)) {
            "sdcard", "内部存储", "手机存储", "根目录", "~" -> sdcardPath
            "下载", "下载目录", "download", "downloads" -> "$sdcardPath/Download"
            "桌面", "desktop" -> "$sdcardPath/Desktop"
            "图片", "相册", "照片", "dcim" -> "$sdcardPath/DCIM"
            "相机", "camera" -> "$sdcardPath/DCIM/Camera"
            "文档", "文档目录", "documents" -> "$sdcardPath/Documents"
            "音乐", "music" -> "$sdcardPath/Music"
            else -> return t
        }
        return base + tail
    }

    /**
     * 展开桌面端路径别名。
     *
     * @param home 用户主目录
     */
    fun expandDesktop(path: String, home: String): String {
        val t = path.trim().trim('"', '\'', '`')
        if (t.isBlank()) return t

        val expanded = if (t.startsWith("~")) home + t.substring(1) else t

        val normalized = expanded.replace('\\', '/')
        val slash = normalized.indexOf('/')
        val head = if (slash >= 0) normalized.substring(0, slash) else normalized
        val tail = if (slash >= 0) normalized.substring(slash) else ""

        val base = when (head.lowercase(Locale.ROOT)) {
            "桌面", "desktop" -> "$home/Desktop"
            "下载", "downloads" -> "$home/Downloads"
            "文档", "documents" -> "$home/Documents"
            "图片", "pictures" -> "$home/Pictures"
            "音乐", "music" -> "$home/Music"
            "主目录", "home" -> home
            else -> return expanded
        }
        return base + tail
    }
}
