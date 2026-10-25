package net.xinkong.furagent.shared.util

import java.net.URLDecoder

/**
 * HTTP query string 解析（双端共用）。
 *
 * 几乎逐字相同的 parseQuery 实现（共 2 处）。现统一到这里。
 *
 * 规则：
 *  - 空串 → 空 map
 *  - 按 & 切分，每段按第一个 = 分 key / value
 *  - key / value 都做 URL 解码
 *  - 解码失败静默跳过该 pair
 */
object HttpQuery {

    fun parse(q: String): Map<String, String> {
        if (q.isBlank()) return emptyMap()
        val m = mutableMapOf<String, String>()
        q.split('&').forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                try {
                    val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                    val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                    m[k] = v
                } catch (_: Exception) {
                    // 单个 pair 解析失败，跳过
                }
            }
        }
        return m
    }
}
