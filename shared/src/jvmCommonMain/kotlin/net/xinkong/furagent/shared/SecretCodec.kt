package net.xinkong.furagent.shared

import java.util.*

/**
 * API Key 混淆工具。
 *
 * 伪装：混淆后格式是 "sk-<base64>"，看起来就像一把正常的 API Key。
 * 强度：XOR + Base64，密钥内置。仅防肉眼，不防有心人。
 *
 * 反混淆的可靠性：
 *   混淆前的原文前面加了 2 字节魔术标记 [0x01, 0x02]，
 *   反混淆时只有魔术标记匹配才认为是混淆数据，否则原样返回（兼容明文 sk-xxx）。
 */
object SecretCodec {

    private const val KEY = "heywhyulookme"
    private const val PREFIX = "sk-"
    private val MAGIC = byteArrayOf(0x01, 0x02)

    fun obfuscate(plain: String): String {
        if (plain.isBlank()) return ""
        return try {
            val key = KEY.toByteArray(Charsets.UTF_8)
            val body = plain.toByteArray(Charsets.UTF_8)

            // 前置魔术标记，用于反混淆时判定
            val data = ByteArray(MAGIC.size + body.size)
            System.arraycopy(MAGIC, 0, data, 0, MAGIC.size)
            System.arraycopy(body, 0, data, MAGIC.size, body.size)

            val out = ByteArray(data.size)
            for (i in data.indices) {
                out[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
            }

            PREFIX + Base64.getEncoder().encodeToString(out)
        } catch (_: Exception) {
            ""
        }
    }

    fun deobfuscate(encoded: String): String {
        if (encoded.isBlank()) return ""
        if (!encoded.startsWith(PREFIX)) return encoded

        return try {
            val payload = encoded.removePrefix(PREFIX)
            val data = Base64.getDecoder().decode(payload)
            if (data.size < MAGIC.size) return encoded

            val key = KEY.toByteArray(Charsets.UTF_8)
            val out = ByteArray(data.size)
            for (i in data.indices) {
                out[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
            }

            // 校验魔术标记
            if (out[0] != MAGIC[0] || out[1] != MAGIC[1]) return encoded

            String(out, MAGIC.size, out.size - MAGIC.size, Charsets.UTF_8)
        } catch (_: Exception) {
            encoded
        }
    }
}