package net.xinkong.furagent.data

import net.xinkong.furagent.shared.util.DateFormats
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.shared.SecretCodec
import net.xinkong.furagent.tools.SDCARD_PATH
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean


object SessionStore {

    private val RE_ILLEGAL_FN = Regex("""[\\/:*?"<>|]""")
    private const val DIR_NAME = "sessions"
    private const val INDEX_FILE = "index.json"

    private const val MAX_EXPORT_IMAGE_BYTES = 5L * 1024 * 1024

    data class ImportSingleResult(
        val success: Boolean,
        val sessionId: String? = null,
        val sessionTitle: String? = null,
        val messageCount: Int = 0,
        val error: String? = null
    )

    /**
     * importAll 的返回结果。
     *
     * 旧返回类型是 Triple<Int, Int, Boolean>，第三个布尔表示
     * "settings 是否恢复"。调用方拿到 true 之后还得自己再
     * SettingsStore.load(prefs) 一次才能更新内存态。契约隐晦，换调用点容易漏。
     *
     * 现在直接返回恢复后的 AppSettings（null 表示未恢复），
     * 调用方 `importResult.restoredSettings?.let { settings = it }` 即可，
     * 契约明确，不依赖调用方记着要刷内存。
     */
    data class ImportAllResult(
        val sessionCount: Int,
        val memoryCount: Int,
        val restoredSettings: AppSettings? = null
    ) {
        val success: Boolean get() = sessionCount >= 0
        val settingsRestored: Boolean get() = restoredSettings != null
    }

    // 索引内存缓存
    private val indexLock = Any()

    @Volatile private var cachedIndex: LinkedHashMap<String, JSONObject>? = null

    @Volatile private var cachedIndexMtime: Long = -1L

    // 索引写盘节流（500ms）
    @Volatile private var lastIndexWriteAt: Long = 0L
    @Volatile private var indexDirty: Boolean = false
    private val indexWritePending = AtomicBoolean(false)
    private val indexWriteHandler = Handler(Looper.getMainLooper())
    private const val INDEX_WRITE_THROTTLE_MS = 500L

    // 会话文件写盘节流（500ms）

    // 每一轮 onHistoryChanged 都会调 SessionStore.save()，长会话每次写
    // 整个 session.json（几百条消息 = 上百 KB），浪费 IO 且可能卡顿。
    // 加节流：500ms 内只写一次，把最新内容缓存起来最后写。

    // 与 index 不同的是：会话内容不能丢——必须在进程退出前 flush。
    // 所以额外提供 flushPending() 供 MainActivity.onPause 或 critical section 调用。
    @Volatile private var lastSessionWriteAt: Long = 0L
    private val pendingSessionSaves = java.util.concurrent.ConcurrentHashMap<String, Session>()
    private val sessionWritePending = AtomicBoolean(false)
    // 写盘从主线程挪到独立 HandlerThread。
    // 长会话（几百条消息）每次序列化 + 原子写几百 KB JSON，主线程会明显卡顿，
    // 尤其每一轮 onHistoryChanged 都调 save 时。
    // HandlerThread 保证串行执行（不并发写同一个文件），且天然支持 postDelayed 节流。
    private val sessionWriteThread = android.os.HandlerThread("FurAgent-SessionWriter").apply {
        start()
    }
    private val sessionWriteHandler = Handler(sessionWriteThread.looper)
    private const val SESSION_WRITE_THROTTLE_MS = 500L

    private fun sessionsDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun sessionFile(context: Context, id: String) = File(sessionsDir(context), "$id.json")
    private fun indexFile(context: Context) = File(sessionsDir(context), INDEX_FILE)

    private fun atomicWrite(target: File, content: String) {
        net.xinkong.furagent.shared.util.JsonFile.atomicWrite(target, content)
    }

    private fun backupCorrupted(f: File) {
        try {
            val bak = File(f.parentFile, f.name + ".corrupt." + System.currentTimeMillis())
            f.renameTo(bak)
        } catch (_: Exception) { }
    }

    /**
     * 索引单条脏数据不再导致整个会话列表丢失：
     *   - 数组元素不是 object → 跳过
     *   - 单条缺 id → 跳过
     * 顶层反序列化失败 → 备份损坏文件（TOAI §14.2）
     */
    private fun loadIndexIntoMemory(context: Context): LinkedHashMap<String, JSONObject> {
        val f = indexFile(context)
        val fileExists = f.exists()
        val currentMtime = if (fileExists) f.lastModified() else 0L

        val cached = cachedIndex
        if (cached != null && cachedIndexMtime == currentMtime && cachedIndexMtime >= 0) {
            return cached
        }

        val map = LinkedHashMap<String, JSONObject>()
        if (fileExists) {
            try {
                val arr = JSONArray(f.readText(Charsets.UTF_8))
                var skipped = 0
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i)
                    if (o == null) {
                        skipped++
                        continue
                    }
                    val id = o.optString("id", "")
                    if (id.isNotBlank()) {
                        map[id] = o
                    } else {
                        skipped++
                    }
                }
                if (skipped > 0) {
                    AgentLogger.log(
                        "Session",
                        "索引已跳过 $skipped 条异常条目，保留 ${map.size} 条"
                    )
                }
                cachedIndexMtime = currentMtime
            } catch (e: Exception) {
                AgentLogger.log(
                    "ERR",
                    "读取会话索引失败，已备份损坏文件：${e.javaClass.simpleName}: ${e.message}"
                )
                backupCorrupted(f)
                cachedIndexMtime = -1L
            }
        } else {
            cachedIndexMtime = 0L
        }

        cachedIndex = map
        return map
    }

    private fun flushIndex(context: Context, map: LinkedHashMap<String, JSONObject>) {
        cachedIndex = map
        indexDirty = true

        val now = System.currentTimeMillis()
        val sinceLast = now - lastIndexWriteAt

        if (sinceLast >= INDEX_WRITE_THROTTLE_MS) {
            doWriteIndexNow(context)
        } else {
            scheduleDelayedIndexWrite(context, INDEX_WRITE_THROTTLE_MS - sinceLast)
        }
    }

    private fun scheduleDelayedIndexWrite(context: Context, delayMs: Long) {
        if (!indexWritePending.compareAndSet(false, true)) return
        indexWriteHandler.postDelayed({
            indexWritePending.set(false)
            if (indexDirty) {
                synchronized(indexLock) {
                    if (indexDirty) {
                        doWriteIndexNow(context)
                    }
                }
            }
        }, delayMs.coerceAtLeast(50L))
    }

    private fun doWriteIndexNow(context: Context) {
        try {
            val map = cachedIndex
            if (map == null) {
                indexDirty = false
                return
            }
            val out = JSONArray()
            map.values.sortedByDescending { it.optLong("updatedAt") }.forEach { out.put(it) }
            val f = indexFile(context)
            atomicWrite(f, out.toString())
            cachedIndexMtime = f.lastModified()
            lastIndexWriteAt = System.currentTimeMillis()
            indexDirty = false
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "写入会话索引失败：${e.javaClass.simpleName}: ${e.message}"
            )
            cachedIndexMtime = -1L
        }
    }

    fun list(context: Context): List<SessionMeta> {
        synchronized(indexLock) {
            val map = loadIndexIntoMemory(context)
            if (map.isEmpty()) return emptyList()
            val out = ArrayList<SessionMeta>(map.size)
            for (o in map.values) {
                try {
                    out.add(
                        SessionMeta(
                            id = o.optString("id", ""),
                            title = o.optString("title", "新会话"),
                            createdAt = o.optLong("createdAt"),
                            updatedAt = o.optLong("updatedAt"),
                            messageCount = o.optInt("messageCount", 0),
                            parentId = o.optString("parentId", "").takeIf { it.isNotBlank() }
                        )
                    )
                } catch (e: Exception) {
                    AgentLogger.log(
                        "ERR",
                        "解析索引条目失败：${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }
            return out.sortedByDescending { it.updatedAt }
        }
    }

    /**
     * 单条消息脏数据不再让整个会话读不出来：
     *   - 消息数组元素不是 object → 跳过
     *   - 单条 role/content 类型异常 → 单条 try-catch 跳过
     */
    fun load(context: Context, id: String): Session? {
        return try {
            val f = sessionFile(context, id)
            if (!f.exists()) return null
            val o = JSONObject(f.readText(Charsets.UTF_8))
            val arr = o.optJSONArray("messages") ?: JSONArray()
            val msgs = mutableListOf<ChatMessage>()
            var skipped = 0
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i)
                if (m == null) {
                    skipped++
                    continue
                }
                // 反序列化逻辑抽到 parseChatMessageFromJson
                val msg = parseChatMessageFromJson(m, null)
                if (msg != null) {
                    msgs.add(msg)
                } else {
                    skipped++
                }
            }
            if (skipped > 0) {
                AgentLogger.log(
                    "Session",
                    "会话 $id 已跳过 $skipped 条异常消息，保留 ${msgs.size} 条"
                )
            }
            Session(
                id = o.optString("id", id),
                title = o.optString("title", "新会话"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                parentId = o.optString("parentId", "").takeIf { it.isNotBlank() },
                // 会话专用提示词：此前从未被反序列化，导致用户设置后重启丢失
                systemPrompt = if (o.has("systemPrompt")) o.optString("systemPrompt", "") else null,
                // 三态：null 表示磁盘上还没有此字段（旧数据）
                titleLocked = if (o.has("titleLocked")) o.optBoolean("titleLocked", false) else null,
                messages = msgs
            )
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "读取会话 $id 失败，已备份损坏文件：${e.javaClass.simpleName}: ${e.message}"
            )
            backupCorrupted(sessionFile(context, id))
            null
        }
    }

    /**
     * 保存会话。
     *
     * 新增节流 500ms 内多次 save 只写一次。
     * 上下文：每一轮 onHistoryChanged 都调这里，长会话（几百条消息）
     * 每次都要序列化 + 写整个 JSON，既慢又浪费。
     *
     * 被节流掉的 save 会保存在 pendingSessionSaves 里，稍后批量写。
     * 进程退出前可调 flushPending(context) 强制落盘（不调也不影响正确性，
     * 因为 Handler 的 postDelayed 在进程活着时一定会执行；只有进程被强杀
     * 才会丢最多 500ms 的数据，比不节流时每次可能丢全部要好得多）。
     */
    fun save(context: Context, session: Session) {
        // 更新索引是轻量的，且索引本身已有节流，这里直接调
        upsertIndexEntry(context, session)

        // 会话内容按 500ms 节流
        pendingSessionSaves[session.id] = session
        val now = System.currentTimeMillis()
        val sinceLast = now - lastSessionWriteAt

        if (sinceLast >= SESSION_WRITE_THROTTLE_MS) {
            doWriteSessionsNow(context)
        } else {
            scheduleDelayedSessionWrite(context, SESSION_WRITE_THROTTLE_MS - sinceLast)
        }
    }

    /**
     * 立即把待写会话落盘。用于进程退出前 / 关键切换点。
     *
     * 写盘已移到后台 HandlerThread，这里投递任务后用 CountDownLatch 同步等完成，
     * 最多等 3 秒（超时放弃，避免 ANR；onPause 场景下下次启动能读回旧数据，最多丢最后 500ms）。
     */
    fun flushPending(context: Context) {
        if (pendingSessionSaves.isEmpty()) return
        val latch = java.util.concurrent.CountDownLatch(1)
        sessionWriteHandler.post {
            try {
                doWriteSessionsNow(context)
            } catch (_: Throwable) {
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
    }

    /**
     * 关闭后台写盘线程。MainActivity.onDestroy 调用。
     *
     * quitSafely 会等队列里已入队的消息执行完再退出，pending 的写盘不会丢。
     */
    fun shutdownWriter() {
        try {
            sessionWriteThread.quitSafely()
        } catch (_: Throwable) {
        }
    }

    private fun scheduleDelayedSessionWrite(context: Context, delayMs: Long) {
        if (!sessionWritePending.compareAndSet(false, true)) return
        sessionWriteHandler.postDelayed({
            sessionWritePending.set(false)
            if (pendingSessionSaves.isNotEmpty()) {
                doWriteSessionsNow(context)
            }
        }, delayMs.coerceAtLeast(50L))
    }

    private fun doWriteSessionsNow(context: Context) {
        // 取出并清空（先清空再写，避免写完又被新 save 覆盖）
        val toWrite = HashMap<String, Session>()
        synchronized(pendingSessionSaves) {
            toWrite.putAll(pendingSessionSaves)
            pendingSessionSaves.clear()
        }
        if (toWrite.isEmpty()) return

        val appContext = context.applicationContext
        // parentId 兜底：索引为准（upsertIndexEntry 已在 save 时合并），persist 重造对象不带 parentId
        val indexParents = synchronized(indexLock) {
            runCatching {
                loadIndexIntoMemory(appContext)
                    .mapValues { (_, v) -> v.optString("parentId", "") }
            }.getOrNull().orEmpty()
        }
        for ((_, session) in toWrite) {
            val parentId = session.parentId ?: indexParents[session.id]?.takeIf { it.isNotBlank() }
            try {
                // systemPrompt=null 表示未携带（persist 重造对象不带它）：
                // 保留磁盘原值，避免被抹掉。""= 用户显式清空，正常写入。
                val systemPrompt = session.systemPrompt ?: runCatching {
                    val f = sessionFile(appContext, session.id)
                    if (f.exists()) JSONObject(f.readText(Charsets.UTF_8)).optString("systemPrompt", "")
                    else ""
                }.getOrDefault("")
                // titleLocked 三态兜底：null 保留磁盘原值
                val titleLocked = session.titleLocked ?: runCatching {
                    val f = sessionFile(appContext, session.id)
                    if (f.exists()) JSONObject(f.readText(Charsets.UTF_8)).optBoolean("titleLocked", false)
                    else false
                }.getOrDefault(false)
                val o = JSONObject().apply {
                    put("id", session.id)
                    put("title", session.title)
                    put("createdAt", session.createdAt)
                    put("updatedAt", session.updatedAt)
                    if (parentId != null) put("parentId", parentId)
                    put("titleLocked", titleLocked)
                    put("systemPrompt", systemPrompt)
                    put("messages", JSONArray().apply {
                        session.messages.forEach { m ->
                            put(net.xinkong.furagent.shared.data.SessionCodec.messageToJson(m))
                        }
                    })
                }
                atomicWrite(sessionFile(appContext, session.id), o.toString())
            } catch (e: Exception) {
                AgentLogger.log(
                    "ERR",
                    "保存会话 ${session.id}（${session.messages.size} 条）失败：${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
        lastSessionWriteAt = System.currentTimeMillis()
    }

    private fun upsertIndexEntry(context: Context, session: Session) {
        synchronized(indexLock) {
            try {
                val map = loadIndexIntoMemory(context)
                // parentId 兜底：调用方未带时保留旧索引值（persist/重命名会重造 Session 对象）
                val parentId = session.parentId
                    ?: map[session.id]?.optString("parentId", "")?.takeIf { it.isNotBlank() }
                map[session.id] = JSONObject().apply {
                    put("id", session.id)
                    put("title", session.title)
                    put("createdAt", session.createdAt)
                    put("updatedAt", session.updatedAt)
                    put("messageCount", session.messages.size)
                    if (parentId != null) put("parentId", parentId)
                }
                flushIndex(context, map)
            } catch (e: Exception) {
                AgentLogger.log(
                    "ERR",
                    "更新会话索引 ${session.id} 失败：${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
    }

    fun delete(context: Context, id: String) {
        // 级联：母会话删除时，名下子会话（parentId == id）一并删除
        try {
            for (c in list(context)) {
                if (c.parentId == id) delete(context, c.id)
            }
        } catch (_: Exception) { }
        try {
            // 从待写队列里也移除
            pendingSessionSaves.remove(id)
            sessionFile(context, id).delete()
            removeIndexEntry(context, id)
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "删除会话 $id 失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun removeIndexEntry(context: Context, id: String) {
        synchronized(indexLock) {
            try {
                val map = loadIndexIntoMemory(context)
                if (map.remove(id) == null) return
                flushIndex(context, map)
            } catch (e: Exception) {
                AgentLogger.log(
                    "ERR",
                    "从索引移除会话 $id 失败：${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
    }

    fun rename(context: Context, id: String, newTitle: String) {
        val s = load(context, id) ?: return
        // 手动重命名置 titleLocked = true，persist 不再用 generateTitle 覆盖
        save(context, s.copy(title = newTitle, titleLocked = true, updatedAt = System.currentTimeMillis()))
    }

    /**
     * generateTitle 抽到 shared/data/SessionCodec。
     */
    fun generateTitle(messages: List<ChatMessage>): String =
        net.xinkong.furagent.shared.data.SessionCodec.generateTitle(messages)

    /**
     * 从 JSON 反序列化一条 ChatMessage。
     *
     * 各有一份几乎逐字的反序列化逻辑（各约 40 行）。现统一到这里。
     *
     * @param importedImagesDir 非 null 时会尝试解码 imageData 里的 base64 图片，
     *                          写入该目录；null 时跳过 imageData 处理（load 场景不需要）。
     * @return 解析成功返回 ChatMessage；单条解析失败返回 null（调用方自行计 skipped）
     */
    /**
     * parseChatMessageFromJson 抽到 shared/data/SessionCodec。
     * Android 传 DEFAULT 宽松版 Base64 解码器。
     */
    private fun parseChatMessageFromJson(
        m: JSONObject,
        importedImagesDir: File?
    ): ChatMessage? = net.xinkong.furagent.shared.data.SessionCodec.parseMessageFromJson(
        m = m,
        importedImagesDir = importedImagesDir,
        decodeBase64 = { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
    )

    /**
     * duplicate 的 Session 构造抽到 shared/data/SessionCodec。
     * 本端只负责 load + save。
     */
    fun duplicate(context: Context, id: String): Session? {
        val src = load(context, id) ?: return null
        val copy = net.xinkong.furagent.shared.data.SessionCodec.duplicateSession(src)
        save(context, copy)
        return copy
    }

    /**
     * 单条消息脏数据不再让整个单会话导入失败。
     */
    fun importSingleSession(context: Context, file: File): ImportSingleResult {
        // 按扩展名分流：.md 走 Markdown 解析，其余走 JSON
        if (file.extension.equals("md", ignoreCase = true)) {
            return importMarkdown(context, file)
        }
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))

            val sessionJson: JSONObject = when {
                root.has("sessions") -> {
                    val arr = root.optJSONArray("sessions")
                        ?: return ImportSingleResult(false, error = "sessions 字段不是数组")
                    if (arr.length() == 0) {
                        return ImportSingleResult(false, error = "备份里没有会话")
                    }
                    arr.optJSONObject(0)
                        ?: return ImportSingleResult(false, error = "备份里第一个会话格式错误")
                }
                root.has("messages") -> root
                else -> return ImportSingleResult(
                    false,
                    error = "文件格式不正确：既不是完整备份也不是单会话"
                )
            }

            val importedImagesDir = File(context.filesDir, "imported_images")
            if (!importedImagesDir.exists()) importedImagesDir.mkdirs()

            val msgs = mutableListOf<ChatMessage>()
            var skipped = 0
            sessionJson.optJSONArray("messages")?.let { mArr ->
                for (i in 0 until mArr.length()) {
                    val m = mArr.optJSONObject(i)
                    if (m == null) {
                        skipped++
                        continue
                    }
                    // 反序列化逻辑抽到 parseChatMessageFromJson
                    val msg = parseChatMessageFromJson(m, importedImagesDir)
                    if (msg != null) {
                        msgs.add(msg)
                    } else {
                        skipped++
                    }
                }
            }
            if (skipped > 0) {
                AgentLogger.log("Session", "导入已跳过 $skipped 条异常消息")
            }

            if (msgs.isEmpty()) return ImportSingleResult(false, error = "会话内容为空")

            val newId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val title = sessionJson.optString("title", "导入会话")

            save(
                context,
                Session(
                    id = newId,
                    title = title,
                    createdAt = now,
                    updatedAt = now,
                    messages = msgs
                )
            )

            ImportSingleResult(
                success = true,
                sessionId = newId,
                sessionTitle = title,
                messageCount = msgs.size
            )
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "导入单会话失败：${e.javaClass.simpleName}: ${e.message}"
            )
            ImportSingleResult(false, error = "解析失败：${e.message}")
        }
    }

    /**
     * 导入 Markdown 会话（与 exportMarkdown 格式对齐）。
     *
     * 格式：
     *   # 标题
     *   导出时间：...
     *   ---
     *   **用户：** 内容
     *   **AI：** 内容
     *   > 工具调用：...
     *   > 工具返回：...
     *
     * 注意：md 是有损格式，id/timestamp/图片会丢失，导入时重新生成。
     */
    fun importMarkdown(context: Context, file: File): ImportSingleResult {
        return try {
            val text = file.readText(Charsets.UTF_8)
            val parsed = net.xinkong.furagent.shared.data.SessionCodec.parseMarkdownSession(
                text = text,
                fallbackTitle = file.nameWithoutExtension
            ) ?: return ImportSingleResult(false, error = "Markdown 里没有解析到消息")

            val (title, msgs) = parsed
            val newId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            save(
                context,
                Session(
                    id = newId,
                    title = title,
                    createdAt = now,
                    updatedAt = now,
                    messages = msgs
                )
            )

            ImportSingleResult(
                success = true,
                sessionId = newId,
                sessionTitle = title,
                messageCount = msgs.size
            )
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "导入 Markdown 失败：${e.javaClass.simpleName}: ${e.message}"
            )
            ImportSingleResult(false, error = "Markdown 解析失败：${e.message}")
        }
    }

    fun rebuildIndex(context: Context) {
        synchronized(indexLock) {
            try {
                val dir = sessionsDir(context)
                val files = dir.listFiles { f ->
                    f.extension == "json" && f.name != INDEX_FILE
                } ?: return
                val map = LinkedHashMap<String, JSONObject>()
                for (f in files) {
                    try {
                        val o = JSONObject(f.readText(Charsets.UTF_8))
                        val id = o.optString("id", "")
                        if (id.isBlank()) continue
                        map[id] = JSONObject().apply {
                            put("id", id)
                            put("title", o.optString("title", "新会话"))
                            put("createdAt", o.optLong("createdAt"))
                            put("updatedAt", o.optLong("updatedAt"))
                            put("messageCount", o.optJSONArray("messages")?.length() ?: 0)
                            put("parentId", o.optString("parentId", ""))
                        }
                    } catch (e: Exception) {
                        AgentLogger.log(
                            "ERR",
                            "重建索引跳过损坏文件 ${f.name}：${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                }
                flushIndex(context, map)
            } catch (e: Exception) {
                AgentLogger.log(
                    "ERR",
                    "重建索引失败：${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
    }

    fun exportMarkdown(
        context: Context,
        session: Session,
        hideToolMessages: Boolean = false
    ): File? {
        return try {
            val exportDir = File(SDCARD_PATH, "FurAgent/exports")
            if (!exportDir.exists()) exportDir.mkdirs()
            val safeTitle = session.title.replace(RE_ILLEGAL_FN, "_")
            val ts = DateFormats.formatFileTs()
            val file = File(exportDir, "${safeTitle}_$ts.md")
            val content = net.xinkong.furagent.shared.data.SessionCodec.sessionToMarkdown(
                session = session,
                hideToolMessages = hideToolMessages,
                maxImageBytes = MAX_EXPORT_IMAGE_BYTES,
                encodeBase64 = { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) },
                showDuration = false
            )
            file.writeText(content, Charsets.UTF_8)
            file
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "导出 Markdown 失败（${session.title}）：${e.javaClass.simpleName}: ${e.message}"
            )
            null
        }
    }


    fun exportAll(context: Context, prefs: android.content.SharedPreferences): File? {
        return try {
            val exportDir = File(SDCARD_PATH, "FurAgent/backup")
            if (!exportDir.exists()) exportDir.mkdirs()
            val ts = DateFormats.formatFileTsNoMs()
            val file = File(exportDir, "backup_$ts.json")

            val root = JSONObject()

            val sessionsArr = JSONArray()
            list(context).forEach { meta ->
                val s = load(context, meta.id) ?: return@forEach
                sessionsArr.put(JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("createdAt", s.createdAt)
                    put("updatedAt", s.updatedAt)
                    put("messages", JSONArray().apply {
                        s.messages.forEach { m ->
                            put(net.xinkong.furagent.shared.data.SessionCodec.messageToJsonWithImages(
                                m = m,
                                maxImageBytes = MAX_EXPORT_IMAGE_BYTES,
                                encodeBase64 = { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
                            ))
                        }
                    })
                })
            }
            root.put("sessions", sessionsArr)

            root.put("settings", JSONObject().apply {
                put("profiles", buildProfilesArray(prefs))
                put("currentProfileId", prefs.getString("current_profile_id", "") ?: "")
                put("maxTurns", prefs.getInt("max_turns", 5))
                put("maxToolCallLoops", prefs.getInt("max_tool_call_loops", 20))
                put("systemPromptAddon", prefs.getString("custom_system", "") ?: "")
                put("autoSpeak", prefs.getBoolean("auto_speak_replies", false))
                put("requireConfirmWrite", prefs.getBoolean("confirm_write", true))
                put("requireConfirmDelete", prefs.getBoolean("confirm_delete", true))
                put("requireConfirmShell", prefs.getBoolean("confirm_shell", true))
                put("requireConfirmPython", prefs.getBoolean("confirm_python", true))
                put("showTokenBar", prefs.getBoolean("show_token_bar", false))
                put("showModifiedFiles", prefs.getBoolean("show_modified_files", false))
                put("enableNewSessionSuggest", prefs.getBoolean("enable_new_session_suggest", false))
                put("fileBackupEnabled", prefs.getBoolean("file_backup_enabled", true))
                put("receiveDir", prefs.getString("pc_receive_dir", "/sdcard/FurAgent/downloads")
                    ?: "/sdcard/FurAgent/downloads")
                put("themeMode", prefs.getString("theme_mode", "system") ?: "system")
                put("imageCompress", prefs.getBoolean("image_compress", true))
                put("imageCompressQuality", prefs.getInt("image_compress_quality", 70))
                put("imageCompressMaxDim", prefs.getInt("image_compress_max_dim", 1280))
                put("disabledTools", JSONArray().apply {
                    val s = prefs.getString("disabled_tools", "") ?: ""
                    s.split("|").map { it.trim() }.filter { it.isNotBlank() }.forEach { put(it) }
                })
                put("allowedPaths", JSONArray().apply {
                    val s = prefs.getString("allowed_paths", "/sdcard") ?: "/sdcard"
                    s.split("|").map { it.trim() }.filter { it.isNotBlank() }.forEach { put(it) }
                })
                put("blockedPaths", JSONArray().apply {
                    val s = prefs.getString("blocked_paths", "/data|/system|/vendor|/proc|/sys") ?: ""
                    s.split("|").map { it.trim() }.filter { it.isNotBlank() }.forEach { put(it) }
                })
                put("enableMemory", prefs.getBoolean("enable_memory", true))
                put("notificationMode", prefs.getString("notification_mode", "off") ?: "off")
                put("notifyTools", JSONArray().apply {
                    val s = prefs.getString("notify_tools", "") ?: ""
                    s.split("|").map { it.trim() }.filter { it.isNotBlank() }.forEach { put(it) }
                })
                put("pcBridgeEnabled", prefs.getBoolean("pc_bridge_enabled", false))
                put("pcBridgeHost", prefs.getString("pc_bridge_host", "") ?: "")
                put("pcBridgePort", prefs.getInt("pc_bridge_port", 8425))
                put("floatingWindowEnabled", prefs.getBoolean("floating_window_enabled", true))
                put("imageProfileId", prefs.getString("image_profile_id", "") ?: "")
                put("enableThinking", prefs.getBoolean("enable_thinking", false))
            })

            val memArr = JSONArray()
            MemoryStore.list(context).forEach {
                memArr.put(JSONObject().apply {
                    put("id", it.id)
                    put("content", it.content)
                    put("createdAt", it.createdAt)
                })
            }
            root.put("memory", memArr)

            file.writeText(root.toString(2), Charsets.UTF_8)
            file
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "导出备份失败：${e.javaClass.simpleName}: ${e.message}"
            )
            null
        }
    }

    private fun buildProfilesArray(prefs: android.content.SharedPreferences): JSONArray {
        val out = JSONArray()
        val profilesStr = prefs.getString("model_profiles", "") ?: ""
        if (profilesStr.isBlank()) return out
        try {
            val arr = JSONArray(profilesStr)
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val key = p.optString("apiKey", "")
                out.put(JSONObject().apply {
                    put("id", p.optString("id", ""))
                    put("name", p.optString("name", ""))
                    put("baseUrl", p.optString("baseUrl", ""))
                    put("model", p.optString("model", ""))
                    put("apiKey", if (key.isBlank()) "" else SecretCodec.obfuscate(key))
                    put("isBuiltIn", p.optBoolean("isBuiltIn", false))
                    put("supportsDisableThinking", p.optBoolean("supportsDisableThinking", true))
                    put("supportsThinking", p.optBoolean("supportsThinking", true))
                    put("supportsVision", p.optBoolean("supportsVision", true))
                    put("temperature", p.optDouble("temperature", 0.1))
                })
            }
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "构建 profiles 数组失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
        return out
    }

    /**
     * 单条会话/消息脏数据不再让整个导入失败。
     *
     * 返回值从 Triple<Int, Int, Boolean> 改成 ImportAllResult，
     * 直接带上恢复后的 AppSettings，避免调用方漏刷内存。
     */
    fun importAll(
        context: Context,
        prefs: android.content.SharedPreferences,
        file: File,
        replaceExisting: Boolean = false
    ): ImportAllResult {
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            if (!root.has("sessions") && !root.has("memory") && !root.has("settings")) {
                return ImportAllResult(-1, -1, null)
            }

            if (replaceExisting) {
                sessionsDir(context).listFiles()?.forEach { it.delete() }
                MemoryStore.clear(context)
                pendingSessionSaves.clear()
                synchronized(indexLock) {
                    cachedIndex = null
                    cachedIndexMtime = -1L
                }
            }

            var sessionCount = 0
            var memoryCount = 0
            var restoredSettings: AppSettings? = null

            val importedImagesDir = File(context.filesDir, "imported_images")
            if (!importedImagesDir.exists()) importedImagesDir.mkdirs()

            root.optJSONArray("sessions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val msgs = mutableListOf<ChatMessage>()
                    o.optJSONArray("messages")?.let { mArr ->
                        for (j in 0 until mArr.length()) {
                            val m = mArr.optJSONObject(j) ?: continue
                            // 反序列化逻辑抽到 parseChatMessageFromJson
                            val msg = parseChatMessageFromJson(m, importedImagesDir)
                            if (msg != null) msgs.add(msg)
                        }
                    }
                    val newId = UUID.randomUUID().toString()
                    save(context, Session(
                        id = newId,
                        title = o.optString("title", "导入会话"),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = System.currentTimeMillis(),
                        messages = msgs
                    ))
                    sessionCount++
                }
            }

            root.optJSONArray("memory")?.let { arr ->
                val contents = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    contents.add(o.optString("content", ""))
                }
                MemoryStore.addAll(context, contents)
                memoryCount = arr.length()
            }

            root.optJSONObject("settings")?.let { s ->
                prefs.edit {
                    s.optJSONArray("profiles")?.let { arr ->
                        if (arr.length() > 0) {
                            val out = JSONArray()
                            for (i in 0 until arr.length()) {
                                val p = arr.optJSONObject(i) ?: continue
                                val key = p.optString("apiKey", "")
                                p.put("apiKey", if (key.isBlank()) "" else SecretCodec.deobfuscate(key))
                                out.put(p)
                            }
                            putString("model_profiles", out.toString())
                        }
                    }
                    s.optString("currentProfileId", "").takeIf { it.isNotBlank() }?.let {
                        putString("current_profile_id", it)
                    }

                    putString("custom_system", s.optString("systemPromptAddon", ""))
                    putInt("max_turns", s.optInt("maxTurns", 5).coerceIn(1, 100))
                    putInt("max_tool_call_loops", s.optInt("maxToolCallLoops", 20).coerceIn(5, 100))
                    putBoolean("auto_speak_replies", s.optBoolean("autoSpeak", false))
                    putBoolean("confirm_write", s.optBoolean("requireConfirmWrite", true))
                    putBoolean("confirm_delete", s.optBoolean("requireConfirmDelete", true))
                    putBoolean("confirm_shell", s.optBoolean("requireConfirmShell", true))
                    putBoolean("confirm_python", s.optBoolean("requireConfirmPython", true))
                    putBoolean("show_token_bar", s.optBoolean("showTokenBar", false))
                    putBoolean("show_modified_files", s.optBoolean("showModifiedFiles", false))
                    putBoolean("enable_new_session_suggest", s.optBoolean("enableNewSessionSuggest", false))
                    putBoolean("file_backup_enabled", s.optBoolean("fileBackupEnabled", true))
                    putString("pc_receive_dir", s.optString("receiveDir", "/sdcard/FurAgent/downloads"))
                    putString("theme_mode", s.optString("themeMode", "system"))
                    putBoolean("image_compress", s.optBoolean("imageCompress", true))
                    putInt("image_compress_quality", s.optInt("imageCompressQuality", 70).coerceIn(30, 100))
                    putInt("image_compress_max_dim", s.optInt("imageCompressMaxDim", 1280).coerceIn(320, 4096))
                    s.optJSONArray("disabledTools")?.let { arr ->
                        val l = mutableListOf<String>()
                        for (i in 0 until arr.length()) {
                            val n = arr.optString(i, "").trim()
                            if (n.isNotBlank()) l.add(n)
                        }
                        putString("disabled_tools", l.joinToString("|"))
                    }
                    s.optJSONArray("allowedPaths")?.let { arr ->
                        val l = mutableListOf<String>()
                        for (i in 0 until arr.length()) {
                            val p = arr.optString(i, "").trim()
                            if (p.isNotBlank()) l.add(p)
                        }
                        if (l.isNotEmpty()) putString("allowed_paths", l.joinToString("|"))
                    }
                    s.optJSONArray("blockedPaths")?.let { arr ->
                        val l = mutableListOf<String>()
                        for (i in 0 until arr.length()) {
                            val p = arr.optString(i, "").trim()
                            if (p.isNotBlank()) l.add(p)
                        }
                        putString("blocked_paths", l.joinToString("|"))
                    }
                    putBoolean("enable_memory", s.optBoolean("enableMemory", true))
                    putString("notification_mode", s.optString("notificationMode", "off"))
                    s.optJSONArray("notifyTools")?.let { arr ->
                        val l = mutableListOf<String>()
                        for (i in 0 until arr.length()) {
                            val n = arr.optString(i, "").trim()
                            if (n.isNotBlank()) l.add(n)
                        }
                        putString("notify_tools", l.joinToString("|"))
                    }
                    putBoolean("pc_bridge_enabled", s.optBoolean("pcBridgeEnabled", false))
                    putString("pc_bridge_host", s.optString("pcBridgeHost", ""))
                    putInt("pc_bridge_port", s.optInt("pcBridgePort", 8425).coerceIn(1, 65535))
                    putBoolean("floating_window_enabled", s.optBoolean("floatingWindowEnabled", true))
                    putString("image_profile_id", s.optString("imageProfileId", ""))
                    putBoolean("enable_thinking", s.optBoolean("enableThinking", false))
                }
                // 反读一次，得到 deobfuscate 后的 AppSettings，
                // 保证 API Key、路径等字段就是内存里可用的最新值，
                // 调用方拿到后直接用即可，不需要自己再 SettingsStore.load。
                restoredSettings = SettingsStore.load(prefs)
            }

            rebuildIndex(context)
            ImportAllResult(sessionCount, memoryCount, restoredSettings)
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "导入备份失败（${file.name}）：${e.javaClass.simpleName}: ${e.message}"
            )
            ImportAllResult(0, 0, null)
        }
    }
}