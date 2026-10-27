package net.xinkong.furagent.data

import android.content.Context
import net.xinkong.furagent.core.AgentLogger
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
    val enabled: Boolean = true
)

object MemoryStore {
    private const val FILE_NAME = "memory.json"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    // 内存缓存。
    // 每轮对话组装 prompt 都会调 toPromptString -> list()，如果每次都读盘解析 JSON，
    // 记忆条目多了会严重拖慢 Agent 响应速度。
    @Volatile private var cachedMemories: List<Memory>? = null
    @Volatile private var cachedFileMtime: Long = -1L

    /**
     * 读-改-写 互斥锁。
     *
     * 所有「list() 取快照 → 改 → save() 写回」的操作都必须持有这把锁，
     * 否则并行调用会互相覆盖：
     *   T1 读到 [A,B,C]，T2 也读到 [A,B,C]
     *   T1 删 A 写回 [B,C]，T2 删 B 写回 [A,C]  ← A 复活了
     * 两次调用都返回成功，但实际只删掉一条。
     *
     * 用 MemoryStore 单例本身当锁，保证全进程唯一。
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
     * 缓存关键 按 createdAt 升序（旧 → 新）。
     * 记忆会拼进 system prompt，顺序稳定才能保住前缀缓存。
     */
    fun list(context: Context): List<Memory> {
        return try {
            val f = file(context)
            if (!f.exists()) {
                cachedMemories = emptyList()
                cachedFileMtime = 0L
                return emptyList()
            }
            
            // 检查缓存
            val currentMtime = f.lastModified()
            if (cachedMemories != null && cachedFileMtime == currentMtime) {
                return cachedMemories!!
            }

            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val list = mutableListOf<Memory>()
            var skipped = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o == null) {
                    skipped++
                    continue
                }
                try {
                    val id = o.optString("id", "")
                    val content = o.optString("content", "")
                    if (id.isBlank() && content.isBlank()) {
                        skipped++
                        continue
                    }
                    list.add(
                        Memory(
                            id = id.ifBlank { UUID.randomUUID().toString() },
                            content = content,
                            createdAt = o.optLong("createdAt", 0L),
                            enabled = o.optBoolean("enabled", true)
                        )
                    )
                } catch (e: Exception) {
                    skipped++
                    AgentLogger.log(
                        "Memory",
                        "memory[$i] 解析失败，已跳过：${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }
            if (skipped > 0) {
                AgentLogger.log("Memory", "已跳过 $skipped 条异常记忆，保留 ${list.size} 条")
            }
            val result = list.sortedBy { it.createdAt }
            cachedMemories = result
            cachedFileMtime = currentMtime
            result
        } catch (e: Exception) {
            AgentLogger.log(
                "Memory",
                "读取记忆失败，已备份损坏文件：${e.javaClass.simpleName}: ${e.message}"
            )
            FileRecovery.backupCorrupted(file(context))
            emptyList()
        }
    }

    fun search(context: Context, query: String): List<Memory> {
        val q = query.lowercase()
        return list(context).filter { it.content.lowercase().contains(q) }
    }

    fun add(context: Context, content: String): Memory = synchronized(writeLock) {
        val current = list(context).toMutableList()
        // 重复内容不删除旧条目，避免已有记忆位置变化。
        current.firstOrNull { it.content == content }?.let { return it }
        val m = Memory(
            id = UUID.randomUUID().toString(),
            content = content,
            createdAt = System.currentTimeMillis(),
            enabled = true // 新建默认开启
        )
        // 追加到尾部（list() 已按 createdAt 升序）
        current.add(m)
        // 记忆是用户资产，不设上限
        save(context, current)
        m
    }

    fun addAll(context: Context, contents: List<String>): Int = synchronized(writeLock) {
        if (contents.isEmpty()) return@synchronized 0
        val current = list(context).toMutableList()
        val existing = current.map { it.content }.toHashSet()
        var added = 0
        for (c in contents) {
            val trimmed = c.trim()
            if (trimmed.isBlank() || trimmed in existing) continue
            // 追加到尾部
            current.add(Memory(
                id = UUID.randomUUID().toString(),
                content = trimmed,
                createdAt = System.currentTimeMillis(),
                enabled = true // 默认开启
            ))
            existing.add(trimmed)
            added++
        }
        if (added > 0) {
            // 记忆是用户资产，不设上限
            save(context, current)
        }
        added
    }

    fun update(context: Context, id: String, newContent: String) = synchronized(writeLock) {
        val current = list(context).toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx >= 0) {
            current[idx] = current[idx].copy(content = newContent)
            save(context, current)
        }
    }

    /**
     * 切换单条记忆的启用状态。
     * 关闭后该条不注入 system prompt，但仍留在列表可随时重开。
     */
    fun setEnabled(context: Context, id: String, enabled: Boolean) = synchronized(writeLock) {
        val current = list(context).toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx >= 0) {
            current[idx] = current[idx].copy(enabled = enabled)
            save(context, current)
        }
    }

    /**
     * 删除一条记忆。
     *
     * 必须持锁：并行删多条时，无锁的「读-改-写」会互相覆盖，
     * 表现为「提示删除成功但列表没变」。
     *
     * @return 是否真的删掉了（id 不存在时返回 false）
     */
    fun remove(context: Context, id: String): Boolean = synchronized(writeLock) {
        val current = list(context).toMutableList()
        val removed = current.removeAll { it.id == id }
        if (removed) save(context, current)
        removed
    }

    fun clear(context: Context) = synchronized(writeLock) {
        try {
            file(context).delete()
            cachedMemories = emptyList()
            cachedFileMtime = 0L
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "清空记忆失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun save(context: Context, list: List<Memory>) {
        try {
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("content", it.content)
                    put("createdAt", it.createdAt)
                    // 序列化 enabled
                    put("enabled", it.enabled)
                })
            }
            atomicWrite(file(context), arr.toString())
            // 写入后刷新缓存
            cachedMemories = list
            cachedFileMtime = file(context).lastModified()
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "保存记忆失败（${list.size} 条）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    /**
     * 只注入 enabled=true 的记忆。
     *
     * 缓存关键 取最近 maxEntries 条（list() 已按 createdAt 升序，故取尾部）。
     * 尾部追加语义：新增记忆只影响末尾，前面已缓存的前缀不受影响。
     */
    fun toPromptString(context: Context, maxEntries: Int = 20): String {
        val enabledList = list(context).filter { it.enabled }
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