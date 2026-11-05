package net.xinkong.furagent.shared.util

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * JSON 文件的原子读写工具（双端共用）。
 *
 * 原来 Android 5 个 Store + 桌面 6 个 Store
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
        if (tmp.renameTo(target)) return
        // Windows 上 renameTo 在目标已存在时会失败；直接 delete 目标再 rename
        // 一旦 rename 又失败就丢数据。改为：先改名到 .bak → rename tmp → 清理 .bak，
        // rename 失败时从 .bak 恢复，保证目标文件永远存在。
        if (!target.exists()) {
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
            return
        }
        val bak = File(target.parentFile, target.name + ".bak")
        try { bak.delete() } catch (_: Exception) { }
        if (!target.renameTo(bak)) {
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
            return
        }
        if (tmp.renameTo(target)) {
            try { bak.delete() } catch (_: Exception) { }
        } else {
            bak.renameTo(target)
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
