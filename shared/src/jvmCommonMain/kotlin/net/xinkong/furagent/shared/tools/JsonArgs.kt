package net.xinkong.furagent.shared.tools

import org.json.JSONObject

/**
 * JSONObject 的 Kotlin 语法糖封装。
 * 消灭大量的 args.optString("xxx", "") 样板代码，且带有更好的默认值和自动类型转换。
 */
class JsonArgs(private val raw: JSONObject) {
    fun str(key: String, default: String = ""): String =
        raw.optString(key, default).trim()

    fun int(key: String, default: Int = 0): Int =
        raw.optInt(key, default)

    fun long(key: String, default: Long = 0L): Long =
        raw.optLong(key, default)

    fun bool(key: String, default: Boolean = false): Boolean =
        raw.optBoolean(key, default)

    fun has(key: String): Boolean = raw.has(key)

    fun json(key: String): JSONObject? = raw.optJSONObject(key)

    fun jsonArray(key: String): org.json.JSONArray? = raw.optJSONArray(key)

    fun raw(): JSONObject = raw

    companion object {
        fun from(jsonStr: String): JsonArgs {
            return try {
                if (jsonStr.isBlank()) JsonArgs(JSONObject()) else JsonArgs(JSONObject(jsonStr))
            } catch (_: Exception) {
                JsonArgs(JSONObject())
            }
        }
    }
}
