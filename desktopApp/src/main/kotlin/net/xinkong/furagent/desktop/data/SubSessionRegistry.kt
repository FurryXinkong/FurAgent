package net.xinkong.furagent.desktop.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 子会话运行登记表（重启恢复用）。
 *
 * 解决的问题：子代理跑在内存协程里，软件一重启全部丢失，
 * 主对话连「刚才在跑什么」都不知道，只能重新派一遍。
 *
 * 做法：子会话启动时把 {id, title, task, parentId, startedAt} 落盘，
 * 正常完成时移除。启动时读残留记录 = 上次被中断的子会话，
 * 报告给主对话，由它决定要不要重派。
 *
 * 注意：这里只记录「元信息」，不记录执行进度。
 * 中断的子会话历史已经由 DesktopSessionStore 增量存盘，
 * 用户点进去能看到它跑到哪一步。
 */
object SubSessionRegistry {

    private fun file(): File = File(DesktopPaths.rootDir(), "sub_sessions.json")

    /** 一条运行中的子会话记录。 */
    data class Entry(
        val id: String,
        val title: String,
        val task: String,
        val parentId: String?,
        val startedAt: Long
    )

    @Synchronized
    private fun readAll(): MutableList<Entry> {
        val f = file()
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val out = mutableListOf<Entry>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isBlank()) continue
                out.add(
                    Entry(
                        id = id,
                        title = o.optString("title", ""),
                        task = o.optString("task", ""),
                        parentId = o.optString("parentId", "").takeIf { it.isNotBlank() },
                        startedAt = o.optLong("startedAt", 0L)
                    )
                )
            }
            out
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    private fun writeAll(list: List<Entry>) {
        try {
            val arr = JSONArray()
            list.forEach { e ->
                arr.put(
                    JSONObject().apply {
                        put("id", e.id)
                        put("title", e.title)
                        put("task", e.task)
                        put("parentId", e.parentId ?: "")
                        put("startedAt", e.startedAt)
                    }
                )
            }
            file().writeText(arr.toString(), Charsets.UTF_8)
        } catch (_: Exception) { }
    }

    /** 子会话启动时登记。 */
    @Synchronized
    fun register(id: String, title: String, task: String, parentId: String?) {
        val list = readAll()
        list.removeAll { it.id == id }
        list.add(Entry(id, title, task, parentId, System.currentTimeMillis()))
        writeAll(list)
    }

    /** 子会话正常结束时注销。 */
    @Synchronized
    fun unregister(id: String) {
        val list = readAll()
        if (list.removeAll { it.id == id }) writeAll(list)
    }

    /**
     * 启动时调用：取出上次残留的记录（= 被中断的子会话），并清空登记表。
     * 返回的列表由调用方决定怎么展示。
     */
    @Synchronized
    fun takeInterrupted(): List<Entry> {
        val list = readAll()
        if (list.isEmpty()) return emptyList()
        writeAll(emptyList())
        return list
    }
}
