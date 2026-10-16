package net.xinkong.furagent.desktop.data

import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.shared.util.FileRecovery
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*

data class Memory(
    val id: String,
    val content: String,
    val createdAt: Long,
    /**
     * 【F2 新增】是否注入 system prompt。
     * 新建的记忆默认 true；用户可在记忆列表手动关闭某条。
     * 反序列化时默认 true，保证旧数据不丢失。
     */
    val enabled: Boolean = true,
    /**
     * 【P1-2 新增】会话摘要专用：来源会话 id。
     * 普通记忆为空串；会话摘要填对应 sessionId，用于幂等覆盖。
     */
    val sessionId: String = "",
    /**
     * 【P1-3 新增】会话摘要专用：摘要生成详情（JSON 字符串）。
     * 包含提取到的工具/路径/命令/高频词、喂给模型的 prompt、模型原始输出。
     * 普通记忆为空串。
     */
    val summaryDetail: String = ""
)

object MemoryStore {
    private const val MAX_ENTRIES = 200

    private fun file(): File = File(DesktopPaths.rootDir(), "memory.json")

    private fun atomicWrite(target: File, content: String) {
        // 【冗余清理 Batch 12a】实现已抽到 shared/util/JsonFile
        net.xinkong.furagent.shared.util.JsonFile.atomicWrite(target, content)
    }

    /**
     * 读取全部记忆。
     *
     * 【P0 已修】单条容错 + FileRecovery
     * 【F2】反序列化 enabled，缺省 true
     *
     * 【缓存关键】按 createdAt 升序（旧→新）。
     * 记忆会注入 system prompt，若顺序不稳定，新增一条就会让
     * 整个对话历史的 prompt 前缀缓存全部失效。
     * 升序 + 追加写入 = 已有记忆位置不变，只有尾部新增部分未命中。
     */
    fun list(): List<Memory> {
        return try {
            val f = file()
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val out = mutableListOf<Memory>()
            var skipped = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o == null) {
                    skipped++
                    continue
                }
                try {
                    out.add(Memory(
                        id = o.optString("id", ""),
                        content = o.optString("content", ""),
                        createdAt = o.optLong("createdAt"),
                        enabled = o.optBoolean("enabled", true),
                        sessionId = o.optString("sessionId", ""),
                        summaryDetail = o.optString("summaryDetail", "")
                    ))
                } catch (e: Exception) {
                    skipped++
                    DesktopLogger.warn("Memory", "memory[$i] 解析失败，已跳过：${e.message}")
                }
            }
            if (skipped > 0) {
                DesktopLogger.warn("Memory", "已跳过 $skipped 条异常记忆，保留 ${out.size} 条")
            }
            out.sortedBy { it.createdAt }
        } catch (e: Exception) {
            DesktopLogger.error(
                "Memory",
                "读取记忆失败，已备份损坏文件：${e.javaClass.simpleName}: ${e.message}"
            )
            FileRecovery.backupCorrupted(file())
            emptyList()
        }
    }

    fun search(query: String): List<Memory> {
        val q = query.lowercase()
        return list().filter { it.content.lowercase().contains(q) }
    }

    fun add(content: String): Memory {
        val current = list().toMutableList()
        // 【缓存关键】重复内容不删除旧条目，避免已有记忆位置变化。
        current.firstOrNull { it.content == content }?.let { return it }
        val m = Memory(
            id = UUID.randomUUID().toString(),
            content = content,
            createdAt = System.currentTimeMillis(),
            enabled = true   // 【F2】新建默认开启
        )
        // 【缓存关键】追加到尾部
        current.add(m)
        val trimmed = if (current.size > MAX_ENTRIES) current.takeLast(MAX_ENTRIES) else current
        save(trimmed)
        return m
    }

    fun addAll(contents: List<String>): Int {
        if (contents.isEmpty()) return 0
        val current = list().toMutableList()
        val existing = current.map { it.content }.toHashSet()
        var added = 0
        for (c in contents) {
            val t = c.trim()
            if (t.isBlank() || t in existing) continue
            // 【缓存关键】追加到尾部
            current.add(Memory(
                id = UUID.randomUUID().toString(),
                content = t,
                createdAt = System.currentTimeMillis(),
                enabled = true   // 【F2】默认开启
            ))
            existing.add(t)
            added++
        }
        if (added > 0) {
            val trimmed = if (current.size > MAX_ENTRIES) current.takeLast(MAX_ENTRIES) else current
            save(trimmed)
        }
        return added
    }

    fun update(id: String, newContent: String) {
        val current = list().toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx >= 0) {
            current[idx] = current[idx].copy(content = newContent)
            save(current)
        }
    }

    /**
     * 【F2 新增】切换单条记忆的启用状态。
     * 关闭后该条不注入 system prompt，但仍留在列表可随时重开。
     */
    fun setEnabled(id: String, enabled: Boolean) {
        val current = list().toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx >= 0) {
            current[idx] = current[idx].copy(enabled = enabled)
            save(current)
        }
    }

    fun remove(id: String) {
        val current = list().toMutableList()
        current.removeAll { it.id == id }
        save(current)
    }

    /**
     * 【P1-2】新增一条会话摘要（幂等：同 sessionId 只保留一条）。
     * 调用前应先 removeBySessionId。
     * 【P1-3】detail：摘要生成详情 JSON（可为空）。
     */
    fun addSessionSummary(sessionId: String, content: String, detail: String = ""): Memory {
        val current = list().toMutableList()
        val m = Memory(
            id = UUID.randomUUID().toString(),
            content = content,
            createdAt = System.currentTimeMillis(),
            enabled = true,
            sessionId = sessionId,
            summaryDetail = detail
        )
        current.add(m)
        val trimmed = if (current.size > MAX_ENTRIES) current.takeLast(MAX_ENTRIES) else current
        save(trimmed)
        return m
    }

    /**
     * 【P1-2】删除某会话的旧摘要（幂等覆盖用）。
     */
    fun removeBySessionId(sessionId: String) {
        if (sessionId.isBlank()) return
        val current = list().toMutableList()
        val before = current.size
        current.removeAll { it.sessionId == sessionId }
        if (current.size != before) save(current)
    }

    fun clear() {
        try {
            file().delete()
        } catch (e: Exception) {
            DesktopLogger.error(
                "Memory",
                "清空记忆失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun save(list: List<Memory>) {
        try {
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("content", it.content)
                    put("createdAt", it.createdAt)
                    // 【F2】序列化 enabled
                    put("enabled", it.enabled)
                    // 【P1-2】序列化 sessionId（普通记忆为空串）
                    if (it.sessionId.isNotBlank()) put("sessionId", it.sessionId)
                    // 【P1-3】序列化 summaryDetail（普通记忆为空串）
                    if (it.summaryDetail.isNotBlank()) put("summaryDetail", it.summaryDetail)
                })
            }
            atomicWrite(file(), arr.toString())
        } catch (e: Exception) {
            DesktopLogger.error(
                "Memory",
                "保存记忆失败（${list.size} 条）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    /**
     * 【F2】只注入 enabled=true 的记忆。
     *
     * 【缓存关键】取最近 maxEntries 条（list() 已按 createdAt 升序，故取尾部）。
     */
    fun toPromptString(maxEntries: Int = 20): String {
        val enabledList = list().filter { it.enabled }
        if (enabledList.isEmpty()) return ""
        val list = if (enabledList.size > maxEntries) {
            enabledList.takeLast(maxEntries)
        } else {
            enabledList
        }
        val sb = StringBuilder("已知关于用户的信息：\n")
        list.forEach { sb.append("- ${it.content}\n") }
        return sb.toString()
    }
}