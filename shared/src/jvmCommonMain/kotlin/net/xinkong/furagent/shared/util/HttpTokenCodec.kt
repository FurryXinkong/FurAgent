package net.xinkong.furagent.shared.util

import java.security.MessageDigest

/**
 * HTTP token 短码派生（双端共用）。
 *
 * 【2026-10-15 抽公共】原来 AndroidHttpFileServer / HttpFileServer 各有一份
 * 完全相同的 deriveShortCode 实现（共 2 处）。现统一到这里。
 *
 * 算法：SHA-256(token) 的前 3 字节转 16 进制大写（6 位）。
 *  - 单向：知道短码无法反推 token
 *  - 稳定：同一 token 永远得同一短码
 */
object HttpTokenCodec {

    fun deriveShortCode(token: String): String {
        if (token.isBlank()) return ""
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(token.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder()
            for (i in 0 until 3) sb.append("%02X".format(hash[i]))
            sb.toString()
        } catch (_: Exception) {
            ""
        }
    }
}
