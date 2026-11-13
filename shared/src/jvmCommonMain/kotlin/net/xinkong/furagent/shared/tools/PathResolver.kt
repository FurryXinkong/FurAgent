package net.xinkong.furagent.shared.tools

import java.io.File

/**
 * 统一的路径规范化工具。
 *
 * 历史实现带有黑名单（/data /system /vendor /proc /sys …）与白名单（仅 /sdcard）两层
 * 目录访问限制，导致用户明确要求读取 /data 等目录时仍被拒绝。按需求已彻底移除该限制：
 * 这里只做 canonicalPath 展开（解软链、规范化 `..`），不再拦截任何目录；
 * 路径能否读写交由操作系统权限决定。
 */
object PathResolver {

    data class Result(
        /** 通过时的规范化绝对路径 */
        val resolved: String?,
        /** 失败原因（不含"失败："前缀） */
        val error: String?
    ) {
        val ok: Boolean get() = resolved != null
    }

    fun check(path: String): Result {
        if (path.isBlank()) return Result(null, "路径为空")

        val file = File(path)
        val canonical = try {
            file.canonicalPath
        } catch (_: Exception) {
            file.absolutePath
        }

        return Result(canonical, null)
    }
}
