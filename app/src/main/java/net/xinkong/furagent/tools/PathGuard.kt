package net.xinkong.furagent.tools

import net.xinkong.furagent.data.AppSettings
import net.xinkong.furagent.shared.tools.PathResolver

/**
 * Android 私有工具的路径规范化入口。
 *
 * 历史上这里执行目录黑名单/白名单策略（禁止读取 /data 等）；按需求该限制已彻底移除，
 * 现在只委托 shared 的 PathResolver 做 canonicalPath 规范化，仅拒绝空路径。
 * 保留此对象是为了不改动大量工具的调用点。
 *
 * 调用约定：返回 null 表示通过，非 null 表示失败原因（含"失败："前缀）。
 */
object PathGuard {
    fun check(path: String, settings: AppSettings): String? {
        val result = PathResolver.check(path)
        return if (result.ok) null else "失败：${result.error}"
    }
}