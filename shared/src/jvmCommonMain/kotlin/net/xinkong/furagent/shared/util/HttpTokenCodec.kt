package net.xinkong.furagent.shared.util

import java.security.MessageDigest

/**
 * HTTP token 短码派生（双端共用）。
 *
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
            // hash[i] 是 Byte，装 Int 时符号扩展：>= 0x80 的字节会输出 FFFFFF85 这种 8 位串。
            // SHA-256 的三字节里 >= 0x80 概率 7/8，绝大多数用户短码变成 8~24 位，
            // 被 DirectDeviceCodec.isShortCodeString 的 length in 4..8 拒绝。
            for (i in 0 until 3) sb.append("%02X".format(hash[i].toInt() and 0xFF))
            sb.toString()
        } catch (_: Exception) {
            ""
        }
    }
}
