package net.xinkong.furagent.desktop.data

import net.xinkong.furagent.shared.util.DateFormats
import kotlinx.coroutines.*
import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.shared.SecretCodec
import net.xinkong.furagent.shared.ThinkingLevel
import net.xinkong.furagent.shared.util.FileRecovery
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

object DesktopSessionStore {

    private val RE_ILLEGAL_FN = Regex("""[\\/:*?"<>|]""")

    private const val MAX_EXPORT_IMAGE_BYTES = 5L * 1024 * 1024

    data class ImportResult(
        val sessionCount: Int,
        val settingsRestored: Boolean,
        val restoredSettings: DesktopSettings?,
        val error: String? = null
    )

    data class ImportSingleResult(
        val success: Boolean,
        val sessionId: String? = null,
        val sessionTitle: String? = null,
        val messageCount: Int = 0,
        val error: String? = null
    )

    // 索引内存缓存
    private val indexLock = Any()

    @Volatile private var cachedIndex: LinkedHashMap<String, JSONObject>? = null
    @Volatile private var cachedIndexMtime: Long = -1L

    // 索引写盘节流（500ms）
    @Volatile private var lastIndexWriteAt: Long = 0L
    @Volatile private var indexDirty: Boolean = false
    private val indexWritePending = AtomicBoolean(false)
    private val indexWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val INDEX_WRITE_THROTTLE_MS = 500L

    private fun sessionFile(id: String): File = File(DesktopPaths.sessionDir(), "$id.json")
    private fun indexFile(): File = File(DesktopPaths.sessionDir(), "index.json")
    private fun exportsDir(): File = DesktopPaths.exportsDir()

    private fun atomicWrite(target: File, content: String) {
        net.xinkong.furagent.shared.util.JsonFile.atomicWrite(target, content)
    }

    private fun loadIndexIntoMemory(): LinkedHashMap<String, JSONObject> {
        val f = indexFile()
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
                for (i in 0 until arr.length()) {
                    // 单个索引项容错，避免一条脏数据使整个会话列表消失
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "")
                    if (id.isNotBlank()) map[id] = o
                }
                cachedIndexMtime = currentMtime
            } catch (e: Exception) {
                DesktopLogger.error(
                    "Session",
                    "读取 index.json 失败，已备份损坏文件：${e.javaClass.simpleName}: ${e.message}"
                )
                FileRecovery.backupCorrupted(f)
                cachedIndexMtime = -1L
            }
        } else {
            cachedIndexMtime = 0L
        }

        cachedIndex = map
        return map
    }

    private fun flushIndex(map: LinkedHashMap<String, JSONObject>) {
        cachedIndex = map
        indexDirty = true

        val now = System.currentTimeMillis()
        val sinceLast = now - lastIndexWriteAt

        if (sinceLast >= INDEX_WRITE_THROTTLE_MS) {
            doWriteIndexNow()
        } else {
            scheduleDelayedIndexWrite(INDEX_WRITE_THROTTLE_MS - sinceLast)
        }
    }

    private fun scheduleDelayedIndexWrite(delayMs: Long) {
        if (!indexWritePending.compareAndSet(false, true)) return
        indexWriteScope.launch {
            delay(delayMs.coerceAtLeast(50L).milliseconds)
            indexWritePending.set(false)
            if (indexDirty) {
                synchronized(indexLock) {
                    if (indexDirty) {
                        doWriteIndexNow()
                    }
                }
            }
        }
    }

    private fun doWriteIndexNow() {
        try {
            val map = cachedIndex ?: run {
                indexDirty = false
                return
            }
            val out = JSONArray()
            map.values.sortedByDescending { it.optLong("updatedAt") }.forEach { out.put(it) }
            val f = indexFile()
            atomicWrite(f, out.toString())
            cachedIndexMtime = f.lastModified()
            lastIndexWriteAt = System.currentTimeMillis()
            indexDirty = false
        } catch (e: Exception) {
            DesktopLogger.error(
                "Session",
                "写入 index.json 失败：${e.javaClass.simpleName}: ${e.message}"
            )
            cachedIndexMtime = -1L
        }
    }

    fun list(): List<DesktopSessionMeta> {
        synchronized(indexLock) {
            val map = loadIndexIntoMemory()
            if (map.isEmpty()) return emptyList()
            val out = ArrayList<DesktopSessionMeta>(map.size)
            for (o in map.values) {
                try {
                    out.add(DesktopSessionMeta(
                        id = o.getString("id"),
                        title = o.optString("title", "新会话"),
                        createdAt = o.optLong("createdAt"),
                        updatedAt = o.optLong("updatedAt"),
                        messageCount = o.optInt("messageCount", 0),
                        parentId = o.optString("parentId", "").takeIf { it.isNotBlank() }
                    ))
                } catch (e: Exception) {
                    DesktopLogger.error(
                        "Session",
                        "解析索引条目失败：${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }
            return out.sortedByDescending { it.updatedAt }
        }
    }

    fun load(id: String): DesktopSession? {
        return try {
            val f = sessionFile(id)
            if (!f.exists()) return null
            val o = JSONObject(f.readText(Charsets.UTF_8))
            val msgs = parseMessages(o.optJSONArray("messages"))
            DesktopSession(
                id = o.getString("id"),
                title = o.optString("title", "新会话"),
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
                parentId = o.optString("parentId", "").takeIf { it.isNotBlank() },
                systemPrompt = if (o.has("systemPrompt")) o.getString("systemPrompt") else null,
                // 三态：null 表示磁盘上还没有此字段（旧数据）
                titleLocked = if (o.has("titleLocked")) o.optBoolean("titleLocked", false) else null,
                messages = msgs
            )
        } catch (e: Exception) {
            DesktopLogger.error(
                "Session",
                "读取会话 $id 失败，已备份损坏文件：${e.javaClass.simpleName}: ${e.message}"
            )
            FileRecovery.backupCorrupted(sessionFile(id))
            null
        }
    }

    fun save(session: DesktopSession) {
        try {
            // parentId 兜底：persist/重命名会重造 Session 对象（不带 parentId），保留磁盘原值
            val parentId = session.parentId ?: synchronized(indexLock) {
                runCatching { loadIndexIntoMemory()[session.id]?.optString("parentId", "") }
                    .getOrNull()?.takeIf { it.isNotBlank() }
            }
            // systemPrompt=null 表示未携带（persist 重造对象不带它）：保留磁盘原值，避免被抹掉
            val systemPrompt = session.systemPrompt ?: runCatching {
                val f = sessionFile(session.id)
                if (f.exists()) JSONObject(f.readText(Charsets.UTF_8)).optString("systemPrompt", "") else ""
            }.getOrDefault("")
            // titleLocked 三态兜底：null 保留磁盘原值（同 systemPrompt 语义）
            val titleLocked = session.titleLocked ?: runCatching {
                val f = sessionFile(session.id)
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
            atomicWrite(sessionFile(session.id), o.toString())
            upsertIndex(session)
        } catch (e: Exception) {
            DesktopLogger.error(
                "Session",
                "保存会话 ${session.id} 失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun upsertIndex(session: DesktopSession) {
        synchronized(indexLock) {
            try {
                val map = loadIndexIntoMemory()
                // parentId 兜底：调用方未带时保留旧索引值
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
                flushIndex(map)
            } catch (e: Exception) {
                DesktopLogger.error(
                    "Session",
                    "更新索引 ${session.id} 失败：${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
    }

    fun delete(id: String) {
        // 级联：母会话删除时，名下子会话（parentId == id）一并删除
        try {
            for (c in list()) {
                if (c.parentId == id) delete(c.id)
            }
        } catch (_: Exception) { }
        try {
            sessionFile(id).delete()
            removeIndexEntry(id)
            // 会话删了，它的长期记忆摘要也必须一起删，否则记忆里留着已删除会话的摘要
            MemoryStore.removeBySessionId(id)
        } catch (e: Exception) {
            DesktopLogger.error(
                "Session",
                "删除会话 $id 失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun removeIndexEntry(id: String) {
        synchronized(indexLock) {
            try {
                val map = loadIndexIntoMemory()
                if (map.remove(id) == null) return
                flushIndex(map)
            } catch (e: Exception) {
                DesktopLogger.error(
                    "Session",
                    "从索引移除会话 $id 失败：${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
    }

    fun newSession(): DesktopSession {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val s = DesktopSession(id, "新会话", now, now, emptyList())
        save(s)
        return s
    }

    /**
     * duplicate 的 Session 构造抽到 shared/data/SessionCodec。
     * 本端只负责 load + save。
     */
    fun duplicate(id: String): DesktopSession? {
        val src = load(id) ?: return null
        val copy = net.xinkong.furagent.shared.data.SessionCodec.duplicateSession(src)
        save(copy)
        return copy
    }

    /**
     * generateTitle 抽到 shared/data/SessionCodec。
     */
    fun generateTitle(messages: List<DesktopMessage>): String =
        net.xinkong.furagent.shared.data.SessionCodec.generateTitle(messages)

    /**
     * 从 JSON 反序列化一条 DesktopMessage。
     *
     * 三处各有一份几乎逐字的反序列化逻辑。现统一到这里。
     *
     * @param importedImagesDir 非 null 时会尝试解码 imageData 里的 base64 图片，
     *                          写入该目录；null 时跳过（load 场景不需要）。
     * @return 解析成功返回 DesktopMessage；单条解析失败返回 null
     */
    /**
     * parseDesktopMessageFromJson 抽到 shared/data/SessionCodec。
     * 桌面传严格版 java.util.Base64 解码器。
     */
    private fun parseDesktopMessageFromJson(
        m: JSONObject,
        importedImagesDir: File?
    ): DesktopMessage? = net.xinkong.furagent.shared.data.SessionCodec.parseMessageFromJson(
        m = m,
        importedImagesDir = importedImagesDir,
        decodeBase64 = { Base64.getDecoder().decode(it) }
    )

    private fun parseMessages(arr: JSONArray?): List<DesktopMessage> {
        if (arr == null) return emptyList()
        val msgs = mutableListOf<DesktopMessage>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            // 反序列化逻辑抽到 parseDesktopMessageFromJson
            parseDesktopMessageFromJson(m, null)?.let { msgs.add(it) }
        }
        return msgs
    }

    fun exportMarkdown(session: DesktopSession, hideToolMessages: Boolean = false): File? {
        return try {
            val safeTitle = session.title.replace(RE_ILLEGAL_FN, "_")
            val ts = DateFormats.formatFileTs()
            val file = File(exportsDir(), "${safeTitle}_$ts.md")
            val content = net.xinkong.furagent.shared.data.SessionCodec.sessionToMarkdown(
                session = session,
                hideToolMessages = hideToolMessages,
                maxImageBytes = MAX_EXPORT_IMAGE_BYTES,
                encodeBase64 = { Base64.getEncoder().encodeToString(it) },
                showDuration = true
            )
            file.writeText(content, Charsets.UTF_8)
            file
        } catch (e: Exception) {
            DesktopLogger.error("Session", "导出 Markdown 失败：${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }


    fun exportAll(settings: DesktopSettings): File? {
        return try {
            val ts = DateFormats.formatFileTsNoMs()
            val file = File(exportsDir(), "backup_$ts.json")
            val root = JSONObject()
            root.put("version", 2)

            val sessionsArr = JSONArray()
            list().forEach { meta ->
                val s = load(meta.id) ?: return@forEach
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
                                encodeBase64 = { Base64.getEncoder().encodeToString(it) }
                            ))
                        }
                    })
                })
            }
            root.put("sessions", sessionsArr)

            root.put("settings", JSONObject().apply {
                put("profiles", JSONArray().apply {
                    settings.profiles.forEach { p ->
                        put(JSONObject().apply {
                            put("id", p.id)
                            put("name", p.name)
                            put("baseUrl", p.baseUrl)
                            put("model", p.model)
                            put("apiKey", if (p.apiKey.isBlank()) "" else SecretCodec.obfuscate(p.apiKey))
                            put("isBuiltIn", p.isBuiltIn)
                            put("supportsDisableThinking", p.supportsDisableThinking)
                            put("supportsThinking", p.supportsThinking)
                            put("supportsVision", p.supportsVision)
                        })
                    }
                })
                put("currentProfileId", settings.currentProfileId)
                put("summaryProfileId", settings.summaryProfileId)
                put("imageProfileId", settings.imageProfileId)
                put("apiKeySource", settings.apiKeySource)
                put("maxToolCallLoops", settings.maxToolCallLoops)
                put("maxTurns", settings.maxTurns)
                put("systemPromptMode", settings.systemPromptMode)
                put("systemPromptAddon", settings.systemPromptAddon)
                put("systemPromptFull", settings.systemPromptFull)
                put("autoSpeak", settings.autoSpeak)
                put("thinkingLevel", settings.thinkingLevel.id)
                put("port", settings.port)
                put("requireConfirmWrite", settings.requireConfirmWrite)
                put("requireConfirmDelete", settings.requireConfirmDelete)
                put("requireConfirmShell", settings.requireConfirmShell)
                put("requireConfirmPython", settings.requireConfirmPython)
                put("requireConfirmSkill", settings.requireConfirmSkill)
                put("showTokenBar", settings.showTokenBar)
                put("showModifiedFiles", settings.showModifiedFiles)
                put("enableNewSessionSuggest", settings.enableNewSessionSuggest)
                put("fileBackupEnabled", settings.fileBackupEnabled)
                put("customToolPaths", JSONObject().apply {
                    settings.customToolPaths.forEach { (k, v) -> put(k, v) }
                })
                put("receiveDir", settings.receiveDir)
                put("themeMode", settings.themeMode)
                put("imageCompress", settings.imageCompress)
                put("imageCompressQuality", settings.imageCompressQuality)
                put("imageCompressMaxDim", settings.imageCompressMaxDim)
                put("notificationsEnabled", settings.notificationsEnabled)
                put("disabledTools", JSONArray().apply {
                    settings.disabledTools.forEach { put(it) }
                })
                put("hiddenSettings", JSONArray().apply {
                    settings.hiddenSettings.forEach { put(it) }
                })
            })

            val memArr = JSONArray()
            MemoryStore.list().forEach {
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
            DesktopLogger.error("Session", "导出全部失败：${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    fun importAll(file: File, replaceExisting: Boolean): ImportResult {
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            if (!root.has("sessions") && !root.has("settings")) {
                return ImportResult(-1, false, null, "文件格式不正确")
            }

            if (replaceExisting) {
                // 先删会话并检查结果，成功后再清记忆。
                // 旧实现先删会话（listFiles() 返回 null 时静默跳过）再清记忆，
                // 若删除失败会出现「记忆没了但会话还在」的不一致状态。
                val sessionDir = DesktopPaths.sessionDir()
                val files = sessionDir.listFiles()
                if (files == null) {
                    DesktopLogger.error("Session", "覆盖导入失败：无法列出会话目录 ${sessionDir.absolutePath}")
                    return ImportResult(-1, false, null, "无法读取会话目录，已中止导入（未改动任何数据）")
                }
                var deleteFailed = 0
                files.forEach { f ->
                    if (f.exists() && !f.delete()) {
                        deleteFailed++
                        DesktopLogger.warn("Session", "删除旧会话文件失败：${f.absolutePath}")
                    }
                }
                if (deleteFailed > 0) {
                    DesktopLogger.error("Session", "覆盖导入中止：$deleteFailed 个旧会话文件删除失败")
                    return ImportResult(
                        -1, false, null,
                        "有 $deleteFailed 个旧会话文件删除失败，已中止导入（记忆未清空，数据保持原样）"
                    )
                }
                // 会话已全部删除，再清记忆
                MemoryStore.clear()
                synchronized(indexLock) {
                    cachedIndex = null
                    cachedIndexMtime = -1L
                }
            }

            var sessionCount = 0

            val importedImagesDir = File(DesktopPaths.rootDir(), "imported_images")
            if (!importedImagesDir.exists()) importedImagesDir.mkdirs()

            root.optJSONArray("sessions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val msgs = mutableListOf<DesktopMessage>()
                    o.optJSONArray("messages")?.let { mArr ->
                        for (j in 0 until mArr.length()) {
                            val m = mArr.optJSONObject(j) ?: continue
                            // 反序列化逻辑抽到 parseDesktopMessageFromJson
                            parseDesktopMessageFromJson(m, importedImagesDir)?.let { msgs.add(it) }
                        }
                    }
                    val newId = UUID.randomUUID().toString()
                    save(DesktopSession(
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
                MemoryStore.addAll(contents)
            }

            var restored: DesktopSettings? = null
            root.optJSONObject("settings")?.let { s ->
                val current = DesktopSettingsHolder.current

                val importedProfiles = mutableListOf<DesktopModelProfile>()
                s.optJSONArray("profiles")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i) ?: continue
                        val importedKey = p.optString("apiKey", "")
                        importedProfiles.add(DesktopModelProfile(
                            id = p.optString("id", "imported_$i"),
                            name = p.optString("name", "导入模型 $i"),
                            baseUrl = p.optString("baseUrl", ""),
                            model = p.optString("model", ""),
                            apiKey = if (importedKey.isBlank()) "" else SecretCodec.deobfuscate(importedKey),
                            isBuiltIn = p.optBoolean("isBuiltIn", false),
                            supportsDisableThinking = p.optBoolean("supportsDisableThinking", true),
                            supportsThinking = p.optBoolean("supportsThinking", true),
                            supportsVision = p.optBoolean("supportsVision", true)
                        ))
                    }
                }

                val localById = current.profiles.associateBy { it.id }
                val mergedProfiles = if (importedProfiles.isEmpty()) {
                    current.profiles
                } else {
                    importedProfiles.map { imp ->
                        val local = localById[imp.id]
                        if (local != null && local.apiKey.isNotBlank()) imp.copy(apiKey = local.apiKey) else imp
                    }.let { importedList ->
                        val importedIds = importedList.map { it.id }.toSet()
                        importedList + current.profiles.filter { it.id !in importedIds }
                    }
                }

                val currentId = s.optString("currentProfileId", current.currentProfileId)
                    .takeIf { it.isNotBlank() && mergedProfiles.any { p -> p.id == it } }
                    ?: mergedProfiles.firstOrNull()?.id
                    ?: current.currentProfileId

                val customPaths = mutableMapOf<String, String>()
                s.optJSONObject("customToolPaths")?.let { obj ->
                    obj.keys().forEach { k ->
                        val v = obj.optString(k, "").trim()
                        if (v.isNotBlank()) customPaths[k] = v
                    }
                }

                val disabled = mutableSetOf<String>()
                s.optJSONArray("disabledTools")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val n = arr.optString(i, "").trim()
                        if (n.isNotBlank()) disabled.add(n)
                    }
                }
                val hidden = mutableSetOf<String>()
                s.optJSONArray("hiddenSettings")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val n = arr.optString(i, "").trim()
                        if (n.isNotBlank()) hidden.add(n)
                    }
                }

                val new = current.copy(
                    profiles = mergedProfiles,
                    currentProfileId = currentId,
                    summaryProfileId = s.optString("summaryProfileId", current.summaryProfileId),
                    imageProfileId = s.optString("imageProfileId", current.imageProfileId),
                    apiKeySource = s.optString("apiKeySource", current.apiKeySource)
                        .takeIf { it in listOf("profile", "env") } ?: current.apiKeySource,
                    maxToolCallLoops = s.optInt("maxToolCallLoops", current.maxToolCallLoops).coerceAtLeast(1),
                    maxTurns = s.optInt("maxTurns", current.maxTurns).coerceAtLeast(1),
                    systemPromptMode = s.optString("systemPromptMode", current.systemPromptMode)
                        .takeIf { it in listOf("append", "replace") } ?: current.systemPromptMode,
                    systemPromptAddon = s.optString("systemPromptAddon", current.systemPromptAddon),
                    systemPromptFull = s.optString("systemPromptFull", current.systemPromptFull),
                    autoSpeak = s.optBoolean("autoSpeak", current.autoSpeak),
                    thinkingLevel = if (s.has("thinkingLevel"))
                        ThinkingLevel.fromId(s.optString("thinkingLevel", null))
                    else ThinkingLevel.fromLegacyBoolean(s.optBoolean("enableThinking", current.thinkingLevel.enabled)),
                    port = s.optInt("port", current.port).coerceIn(1, 65535),
                    requireConfirmWrite = s.optBoolean("requireConfirmWrite", current.requireConfirmWrite),
                    requireConfirmDelete = s.optBoolean("requireConfirmDelete", current.requireConfirmDelete),
                    requireConfirmShell = s.optBoolean("requireConfirmShell", current.requireConfirmShell),
                    requireConfirmPython = s.optBoolean("requireConfirmPython", current.requireConfirmPython),
                    showTokenBar = s.optBoolean("showTokenBar", current.showTokenBar),
                    showModifiedFiles = s.optBoolean("showModifiedFiles", current.showModifiedFiles),
                    enableNewSessionSuggest = s.optBoolean("enableNewSessionSuggest", current.enableNewSessionSuggest),
                    fileBackupEnabled = s.optBoolean("fileBackupEnabled", current.fileBackupEnabled),
                    customToolPaths = customPaths.ifEmpty { current.customToolPaths },
                    receiveDir = s.optString("receiveDir", current.receiveDir),
                    themeMode = s.optString("themeMode", current.themeMode),
                    imageCompress = s.optBoolean("imageCompress", current.imageCompress),
                    imageCompressQuality = s.optInt("imageCompressQuality", current.imageCompressQuality).coerceIn(30, 100),
                    imageCompressMaxDim = s.optInt("imageCompressMaxDim", current.imageCompressMaxDim).coerceIn(320, 4096),
                    notificationsEnabled = s.optBoolean("notificationsEnabled", current.notificationsEnabled),
                    disabledTools = if (disabled.isEmpty()) current.disabledTools else disabled,
                    hiddenSettings = hidden
                )
                new.save()
                DesktopSettingsHolder.current = new
                restored = new
            }

            ImportResult(sessionCount, restored != null, restored)
        } catch (e: Exception) {
            DesktopLogger.error("Session", "导入失败：${e.javaClass.simpleName}: ${e.message}")
            ImportResult(-1, false, null, "解析失败：${e.message}")
        }
    }

    fun importSingleSession(file: File): ImportSingleResult {
        // 按扩展名分流：.md 走 Markdown 解析，其余走 JSON
        if (file.extension.equals("md", ignoreCase = true)) {
            return importMarkdown(file)
        }
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))

            val sessionJson: JSONObject = when {
                root.has("sessions") -> {
                    val arr = root.optJSONArray("sessions")
                        ?: return ImportSingleResult(false, error = "sessions 字段不是数组")
                    if (arr.length() == 0) return ImportSingleResult(false, error = "备份里没有会话")
                    arr.optJSONObject(0)
                }
                root.has("messages") -> root
                else -> return ImportSingleResult(
                    false,
                    error = "文件格式不正确：既不是完整备份也不是单会话"
                )
            }

            val importedImagesDir = File(DesktopPaths.rootDir(), "imported_images")
            if (!importedImagesDir.exists()) importedImagesDir.mkdirs()

            val mArr = sessionJson.optJSONArray("messages")
            val msgs = mutableListOf<DesktopMessage>()
            if (mArr != null) {
                for (i in 0 until mArr.length()) {
                    val m = mArr.optJSONObject(i) ?: continue
                    // 反序列化逻辑抽到 parseDesktopMessageFromJson
                    parseDesktopMessageFromJson(m, importedImagesDir)?.let { msgs.add(it) }
                }
            }
            if (msgs.isEmpty()) return ImportSingleResult(false, error = "会话内容为空")

            val newId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val title = sessionJson.optString("title", "导入会话")

            save(DesktopSession(
                id = newId,
                title = title,
                createdAt = now,
                updatedAt = now,
                messages = msgs
            ))

            ImportSingleResult(
                success = true,
                sessionId = newId,
                sessionTitle = title,
                messageCount = msgs.size
            )
        } catch (e: Exception) {
            DesktopLogger.error(
                "Session",
                "导入单会话失败：${e.javaClass.simpleName}: ${e.message}"
            )
            ImportSingleResult(false, error = "解析失败：${e.message}")
        }
    }

    /**
     * 导入 Markdown 会话（与手机端 exportMarkdown 格式对齐）。
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
    fun importMarkdown(file: File): ImportSingleResult {
        return try {
            val text = file.readText(Charsets.UTF_8)
            val parsed = net.xinkong.furagent.shared.data.SessionCodec.parseMarkdownSession(
                text = text,
                fallbackTitle = file.nameWithoutExtension
            ) ?: return ImportSingleResult(false, error = "Markdown 里没有解析到消息")

            val (title, msgs) = parsed
            val newId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            save(DesktopSession(
                id = newId,
                title = title,
                createdAt = now,
                updatedAt = now,
                messages = msgs
            ))

            ImportSingleResult(
                success = true,
                sessionId = newId,
                sessionTitle = title,
                messageCount = msgs.size
            )
        } catch (e: Exception) {
            DesktopLogger.error(
                "Session",
                "导入 Markdown 失败：${e.javaClass.simpleName}: ${e.message}"
            )
            ImportSingleResult(false, error = "Markdown 解析失败：${e.message}")
        }
    }
}
