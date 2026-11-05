package net.xinkong.furagent.shared.tools

import java.io.File

/**
 * 统一的路径安全检查。
 *
 * - canonicalPath 展开（自动解软链）
 * - 黑名单（blocked）优先
 * - 白名单（allowed）：
 *     null   = 不限制（桌面端默认）
 *     []     = 拒绝所有
 *     非空   = 必须命中其中之一（Android 端默认）
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

    fun check(
        path: String,
        allowedPaths: List<String>?,
        blockedPaths: List<String>
    ): Result {
        if (path.isBlank()) return Result(null, "路径为空")

        val file = File(path)
        val canonical = try {
            file.canonicalPath
        } catch (_: Exception) {
            file.absolutePath
        }

        // 1. 黑名单优先
        for (blocked in blockedPaths) {
            if (blocked.isBlank()) continue
            val bc = try { File(blocked).canonicalPath } catch (_: Exception) { blocked }
            if (isPrefixOf(canonical, bc)) {
                return Result(null, "禁止访问系统目录：$blocked")
            }
        }

        // 2. 白名单（null = 不限制）
        if (allowedPaths != null) {
            if (allowedPaths.isEmpty()) {
                return Result(null, "未配置任何允许访问的目录")
            }
            val allowed = allowedPaths.any { allow ->
                if (allow.isBlank()) return@any false
                val ac = try { File(allow).canonicalPath } catch (_: Exception) { allow }
                isPrefixOf(canonical, ac)
            }
            if (!allowed) {
                return Result(
                    null,
                    "该路径不在允许范围内（允许：${allowedPaths.joinToString(", ")}）"
                )
            }
        }

        return Result(canonical, null)
    }

    private fun isPrefixOf(path: String, prefix: String): Boolean {
        if (path.equals(prefix, ignoreCase = true)) return true
        if (path.length <= prefix.length) return false
        // Windows 文件系统大小写不敏感，白名单 C:\Users 必须能匹配 c:\Users\file。
        // Unix 大小写敏感，但这里统一用 ignoreCase 不会造成安全问题：
        // 路径已经过 canonicalPath 展开，大小写差异只可能是用户输入习惯。
        if (!path.startsWith(prefix, ignoreCase = true)) return false
        val next = path[prefix.length]
        return next == '/' || next == '\\' || next == File.separatorChar
    }
}