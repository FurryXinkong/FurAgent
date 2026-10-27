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
     * 是否注入 system prompt。
     * 新建的记忆默认 true；用户可在记忆列表手动关闭某条。
     * 反序列化时默认 true，保证旧数据不丢失。
     */
    val enabled: Boolean = true,
    /**
     * 会话摘要专用：来源会话 id。
     * 普通记忆为空串；会话摘要填对应 sessionId，用于幂等覆盖。
     */
    val sessionId: String = "",
    /**
     * 会话摘要专用：摘要生成详情（JSON 字符串）。
     * 包含提取到的工具/路径/命令/高频词、喂给模型的 prompt、模型原始输出。
     * 普通记忆为空串。
     */
    val summaryDetail: String = ""
)

object MemoryStore {
    // 原先硬上限 200 条，超出后 takeLast 会静默丢弃最旧的普通记忆。
    // 记忆是用户资产，改为不设上限（条目本身很小，检索直接读文件）。

    private fun file(): File = File(DesktopPaths.rootDir(), "memory.json")

    // 内存缓存：避免每次操作都读盘解析 JSON
    @Volatile private var cachedMemories: List<Memory>? = null
    @Volatile private var cachedMtime: Long = -1L
    private val cacheLock = Any()

    /**
     * 读-改-写 互斥锁。
     *
     * cacheLock 只保护缓存字段本身；这把锁保护「list() 取快照 → 改 → save() 写回」
     * 整个事务。没有它，并行删两条记忆会互相覆盖：
     *   T1 读到 [A,B,C]，T2 也读到 [A,B,C]
     *   T1 删 A 写回 [B,C]，T2 删 B 写回 [A,C]  ← A 复活了
     * 两次调用都返回成功，但实际只删掉一条。
     */
    private val writeLock = Any()

    private fun atomicWrite(target: File, content: String) {
        net.xinkong.furagent.shared.util.JsonFile.atomicWrite(target, content)
    }

    /**
     * 读取全部记忆。
     *
     * P0 已修 单条容错 + FileRecovery
     * 反序列化 enabled，缺省 true
     *
     * 缓存关键 按 createdAt 升序（旧→新）。
     * 记忆会注入 system prompt，若顺序不稳定，新增一条就会让
     * 整个对话历史的 prompt 前缀缓存全部失效。
     * 升序 + 追加写入 = 已有记忆位置不变，只有尾部新增部分未命中。
     *
     * 内存缓存 通过文件 mtime 判断是否需要重新读盘。
     * 命中缓存时直接返回，避免每次都解析 JSON。
     */
    fun list(): List<Memory> {
        val f = file()
        if (!f.exists()) {
            synchronized(cacheLock) { cachedMemories = null; cachedMtime = -1L }
            return emptyList()
        }
        val currentMtime = f.lastModified()
        synchronized(cacheLock) {
            val cached = cachedMemories
            if (cached != null && cachedMtime == currentMtime) return cached
        }
        // 缓存未命中或文件已变更，重新读盘
        val loaded = loadFromDisk(f)
        synchronized(cacheLock) {
            cachedMemories = loaded
            cachedMtime = currentMtime
        }
        return loaded
    }

    /** 实际的磁盘读取逻辑（从原 list() 拆出）。 */
    private fun loadFromDisk(f: File): List<Memory> {
        return try {
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
            FileRecovery.backupCorrupted(f)
            emptyList()
        }
    }

    fun search(query: String): List<Memory> {
        val q = query.lowercase()
        return list().filter { it.content.lowercase().contains(q) }
    }

    fun add(content: String): Memory = synchronized(writeLock) {
        val current = list().toMutableList()
        // 重复内容不删除旧条目，避免已有记忆位置变化。
        current.firstOrNull { it.content == content }?.let { return it }
        val m = Memory(
            id = UUID.randomUUID().toString(),
            content = content,
            createdAt = System.currentTimeMillis(),
            enabled = true // 新建默认开启
        )
        // 追加到尾部
        current.add(m)
        save(current)
        m
    }

    fun addAll(contents: List<String>): Int = synchronized(writeLock) {
        if (contents.isEmpty()) return 0
        val current = list().toMutableList()
        val existing = current.map { it.content }.toHashSet()
        var added = 0
        for (c in contents) {
            val t = c.trim()
            if (t.isBlank() || t in existing) continue
            // 追加到尾部
            current.add(Memory(
                id = UUID.randomUUID().toString(),
                content = t,
                createdAt = System.currentTimeMillis(),
                enabled = true // 默认开启
            ))
            existing.add(t)
            added++
        }
        if (added > 0) {
            save(current)
        }
        return added
    }

    fun update(id: String, newContent: String) = synchronized(writeLock) {
        val current = list().toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx >= 0) {
            current[idx] = current[idx].copy(content = newContent)
            save(current)
        }
    }

    /**
     * 切换单条记忆的启用状态。
     * 关闭后该条不注入 system prompt，但仍留在列表可随时重开。
     */
    fun setEnabled(id: String, enabled: Boolean) = synchronized(writeLock) {
        val current = list().toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx >= 0) {
            current[idx] = current[idx].copy(enabled = enabled)
            save(current)
        }
    }

    /**
     * 删除一条记忆。
     *
     * 必须持 writeLock：并行删多条时，无锁的「读-改-写」会互相覆盖，
     * 表现为「提示删除成功但列表没变」。
     *
     * @return 是否真的删掉了（id 不存在时返回 false）
     */
    fun remove(id: String): Boolean = synchronized(writeLock) {
        val current = list().toMutableList()
        val removed = current.removeAll { it.id == id }
        if (removed) save(current)
        removed
    }

    /**
     * 新增一条会话摘要（幂等：同 sessionId 只保留一条）。
     * 调用前应先 removeBySessionId。
     * detail：摘要生成详情 JSON（可为空）。
     */
    fun addSessionSummary(
        sessionId: String,
        content: String,
        detail: String = ""
    ): Memory = synchronized(writeLock) {
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
        save(current)
        return m
    }

    /**
     * 删除某会话的旧摘要（幂等覆盖用）。
     */
    fun removeBySessionId(sessionId: String) = synchronized(writeLock) {
        if (sessionId.isBlank()) return@synchronized
        val current = list().toMutableList()
        val before = current.size
        current.removeAll { it.sessionId == sessionId }
        if (current.size != before) save(current)
    }

    fun clear() = synchronized(writeLock) {
        try {
            file().delete()
            synchronized(cacheLock) { cachedMemories = null; cachedMtime = -1L }
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
                    // 序列化 enabled
                    put("enabled", it.enabled)
                    // 序列化 sessionId（普通记忆为空串）
                    if (it.sessionId.isNotBlank()) put("sessionId", it.sessionId)
                    // 序列化 summaryDetail（普通记忆为空串）
                    if (it.summaryDetail.isNotBlank()) put("summaryDetail", it.summaryDetail)
                })
            }
            atomicWrite(file(), arr.toString())
            // 缓存同步 写盘成功后立即更新缓存，避免下次 list() 再读盘。
            // 用写入后的时间戳作为 mtime（atomicWrite 可能跨线程，取当前时间兜底）。
            synchronized(cacheLock) {
                cachedMemories = list
                cachedMtime = file().lastModified().coerceAtLeast(System.currentTimeMillis())
            }
        } catch (e: Exception) {
            DesktopLogger.error(
                "Memory",
                "保存记忆失败（${list.size} 条）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    /**
     * 只注入 enabled=true 的记忆。
     *
     * 缓存关键 取最近 maxEntries 条（list() 已按 createdAt 升序，故取尾部）。
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