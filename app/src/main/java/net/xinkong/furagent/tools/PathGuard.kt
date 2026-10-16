package net.xinkong.furagent.tools

import net.xinkong.furagent.data.AppSettings
import net.xinkong.furagent.shared.tools.PathResolver

/**
 * Android 私有工具的路径守卫。
 *
 * 内部委托给 shared 的 PathResolver（唯一实现），
 * 只是把错误信息加上"失败："前缀，让 README 4.9 的 isToolFailure 能正确判定。
 *
 * 调用约定：返回 null 表示通过，非 null 表示失败原因（含"失败："前缀）。
 */
object PathGuard {
    fun check(path: String, settings: AppSettings): String? {
        val result = PathResolver.check(path, settings.allowedPaths, settings.blockedPaths)
        return if (result.ok) null else "失败：${result.error}"
    }
}