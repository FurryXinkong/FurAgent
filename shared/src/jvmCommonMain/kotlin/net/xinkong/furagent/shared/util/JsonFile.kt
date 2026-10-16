package net.xinkong.furagent.shared.util

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * JSON 文件的原子读写工具（双端共用）。
 *
 * 【冗余清理 Batch 12a】原来 Android 5 个 Store + 桌面 6 个 Store
 * 各有一份几乎相同的 atomicWrite。现统一到这里。
 */
object JsonFile {

    /**
     * 原子写：先写 .tmp，再 rename。
     *
     * Windows 上 renameTo 在目标已存在时会失败，所以先删目标。
     * 极端情况（renameTo 也失败）退化为直接写入。
     */
    fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    /**
     * JSONArray → List<String>（null 返回 emptyList）。
     * 用于 MCP 的 args / nameGlob 等字符串列表字段。
     */
    fun jsonArrayToStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val l = mutableListOf<String>()
        for (i in 0 until arr.length()) l.add(arr.optString(i, ""))
        return l
    }

    /**
     * JSONObject → Map<String, String>（null 返回 emptyMap）。
     * 用于 MCP 的 env / headers 等键值对字段。
     */
    fun jsonObjectToStringMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val m = mutableMapOf<String, String>()
        obj.keys().forEach { k -> m[k] = obj.optString(k, "") }
        return m
    }
}
