package net.xinkong.furagent.shared.tools

import org.json.JSONObject
import java.io.File

/**
 * 写前备份（Android / Desktop 共用）——「历史修改记录 + 一键回档」的数据层。
 *
 * 与 TrashHelper（删除 → 回收站）互补：
 *   - TrashHelper 管「删除可恢复」
 *   - BackupHelper 管「覆盖写可回档」——doWriteFile 覆盖前把原文件字节
 *     原样拷进备份目录，manifest.jsonl 追加一条记录供 UI 列历史。
 *
 * 目录（与回收站同级）：
 *   - 桌面：~/.furagent/temp/backup
 *   - Android：<filesDir>/temp/backup
 *
 * 接入点在两端的确认层（settings 可达），CommonTools 本身不感知设置。
 */
object BackupHelper {

    private val RE_SAFE_NAME = Regex("""[^A-Za-z0-9._\-]""")

    private const val MANIFEST = "manifest.jsonl"
    private const val MAX_ENTRIES = 200

    /** 从回收站目录推导同级备份目录：.../temp/trash → .../temp/backup */
    fun backupDirFor(trashDir: String?): File? {
        if (trashDir.isNullOrBlank()) return null
        val trash = File(trashDir)
        val parent = trash.parentFile ?: return null
        return File(parent, "backup")
    }

    data class BackupEntry(
        val ts: Long,
        /** 原始文件路径（回档目标） */
        val path: String,
        /** 备份文件在备份目录内的文件名 */
        val backup: String,
        /** 动作：write / append / code_edit */
        val action: String,
        val size: Long,
        /** 【内置 git】本次改动的 unified patch（二进制/无变化为空串） */
        val patch: String = "",
        /** 【内置 git】新增行数 */
        val added: Int = 0,
        /** 【内置 git】删除行数 */
        val removed: Int = 0,
        /** 【内置 git】触发工具名（write_file / code_edit / ...） */
        val tool: String = "",
        /** 【性能】patch 独立文件名（空 = 无 patch 或旧记录内联在 patch 字段） */
        val patchFile: String = "",
        /** 【会话隔离】产生这条记录的会话 id（空 = 旧记录，归属未知） */
        val sessionId: String = ""
    )

    /**
     * 备份一个已存在文件的当前字节。
     *
     * 失败只记日志不抛——备份是尽力而为，绝不允许因备份失败挡住用户本来的写入。
     *
     * @param backupDir 由 [backupDirFor] 得到
     * @param originalPath 原文件路径（manifest 记录用）
     * @param action  触发备份的动作标签（write / append / code_edit）
     * @param read    读原文件字节的回调（由宿主注入，规避 ToolHost 依赖）
     * @return 成功返回条目；原文件不存在/读失败返回 null（无物可备）
     */
    fun backup(
        backupDir: File,
        originalPath: String,
        action: String,
        read: () -> ByteArray?
    ): BackupEntry? {
        return try {
            val bytes = read() ?: return null
            if (!backupDir.exists() && !backupDir.mkdirs()) return null

            val ts = System.currentTimeMillis()
            val base = File(originalPath).name.replace(RE_SAFE_NAME, "_")
            // 时间戳前缀防重名（同 TrashHelper 策略）
            var target = File(backupDir, "${ts}_$base")
            var seq = 1
            while (target.exists()) target = File(backupDir, "${ts}_${seq++}_$base")
            target.writeBytes(bytes)

            val entry = BackupEntry(ts, originalPath, target.name, action, bytes.size.toLong())
            backupDir.resolve(MANIFEST).appendText(entryToJson(entry) + "\n")
            prune(backupDir)
            entry
        } catch (_: Exception) {
            null
        }
    }

    private fun entryToJson(e: BackupEntry): String = JSONObject().apply {
        put("ts", e.ts)
        put("path", e.path)
        put("backup", e.backup)
        put("action", e.action)
        put("size", e.size)
        // 【性能】patch 不再内联进 manifest（否则 manifest 会膨胀到几十 MB，
        // 每次列表都要全量解析）。改为单独文件，列表只读元数据，点开才读 patch。
        if (e.patchFile.isNotEmpty()) put("patchFile", e.patchFile)
        if (e.patch.isNotEmpty()) put("patch", e.patch)
        if (e.added != 0) put("added", e.added)
        if (e.removed != 0) put("removed", e.removed)
        if (e.tool.isNotEmpty()) put("tool", e.tool)
        if (e.sessionId.isNotEmpty()) put("sessionId", e.sessionId)
    }.toString()

    /**
     * 【内置 git】记录一次编辑：备份旧内容 + 算行级 patch + 写 manifest。
     *
     * 与 [backup] 的区别：这里同时拿到「改前」和「改后」内容，
     * 所以能给出 +N/-M 和 unified patch，供时间线 UI 与 AI 查看。
     *
     * @param oldBytes 改前内容（新建文件传 null）
     * @param newBytes 改后内容（删除文件传 null）
     */
    fun recordEdit(
        backupDir: File,
        originalPath: String,
        action: String,
        oldBytes: ByteArray?,
        newBytes: ByteArray?,
        tool: String = action,
        sessionId: String = ""
    ): BackupEntry? {
        return try {
            if (!backupDir.exists() && !backupDir.mkdirs()) return null
            val ts = System.currentTimeMillis()
            val base = File(originalPath).name.replace(RE_SAFE_NAME, "_")
            var target = File(backupDir, "${ts}_$base")
            var seq = 1
            while (target.exists()) target = File(backupDir, "${ts}_${seq++}_$base")
            if (oldBytes != null) target.writeBytes(oldBytes)

            // 二进制文件（含 NUL 字节）不做文本 diff
            val isBinary = (oldBytes?.any { it == 0.toByte() } == true) ||
                    (newBytes?.any { it == 0.toByte() } == true)
            val d = if (isBinary) {
                LineDiff.Result("", 0, 0)
            } else {
                LineDiff.diff(
                    oldBytes?.toString(Charsets.UTF_8) ?: "",
                    newBytes?.toString(Charsets.UTF_8) ?: "",
                    originalPath
                )
            }

            // patch 单独落盘（与备份文件同名前缀，便于 prune 一起清理）
            var patchFileName = ""
            if (d.patch.isNotEmpty()) {
                val pf = File(backupDir, "${target.name}.patch")
                pf.writeText(d.patch, Charsets.UTF_8)
                patchFileName = pf.name
            }

            val entry = BackupEntry(
                ts = ts,
                path = originalPath,
                backup = if (oldBytes != null) target.name else "",
                action = action,
                size = (newBytes?.size ?: 0).toLong(),
                patch = "",
                added = d.added,
                removed = d.removed,
                tool = tool,
                patchFile = patchFileName,
                sessionId = sessionId
            )
            backupDir.resolve(MANIFEST).appendText(entryToJson(entry) + "\n")
            invalidateCache()
            prune(backupDir)
            entry
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 读一条记录的 patch 文本。
     * 新记录从独立 .patch 文件读；旧记录（patch 内联在 manifest）直接返回。
     */
    fun readPatch(backupDir: File, entry: BackupEntry): String {
        if (entry.patchFile.isNotEmpty()) {
            return try {
                val f = File(backupDir, entry.patchFile)
                if (f.exists()) f.readText(Charsets.UTF_8) else ""
            } catch (_: Exception) {
                ""
            }
        }
        return entry.patch
    }

    // 列表缓存（避免每次重组都全量读盘 + 解析 manifest）

    private val cacheLock = Any()
    private var cacheDir: String? = null
    private var cacheStamp: Long = -1L
    private var cacheSize: Long = -1L
    private var cacheEntries: List<BackupEntry> = emptyList()

    private fun invalidateCache() {
        synchronized(cacheLock) {
            cacheStamp = -1L
            cacheSize = -1L
        }
    }

    /**
     * 列出历史记录（新 → 旧）。
     * manifest 损坏/缺失时返回空列表，绝不抛异常。
     *
     * 【性能】按 manifest 的 lastModified + size 做缓存：
     * UI 重组、滚动、切页都不会重复读盘；只有真的写入了新记录才重新解析。
     */
    fun listHistory(backupDir: File, limit: Int = 50): List<BackupEntry> {
        return try {
            val manifest = backupDir.resolve(MANIFEST)
            if (!manifest.exists()) return emptyList()
            val stamp = manifest.lastModified()
            val size = manifest.length()
            synchronized(cacheLock) {
                if (cacheDir == backupDir.absolutePath && cacheStamp == stamp && cacheSize == size) {
                    return if (limit >= cacheEntries.size) cacheEntries else cacheEntries.take(limit)
                }
            }
            val parsed = manifest.readLines()
                .mapNotNull { line ->
                    if (line.isBlank()) return@mapNotNull null
                    try {
                        val o = JSONObject(line)
                        BackupEntry(
                            ts = o.optLong("ts", 0),
                            path = o.optString("path", ""),
                            backup = o.optString("backup", ""),
                            action = o.optString("action", ""),
                            size = o.optLong("size", 0),
                            patch = o.optString("patch", ""),
                            added = o.optInt("added", 0),
                            removed = o.optInt("removed", 0),
                            tool = o.optString("tool", ""),
                            patchFile = o.optString("patchFile", ""),
                            sessionId = o.optString("sessionId", "")
                        )
                    } catch (_: Exception) {
                        // 单行损坏跳过，不影响其余记录
                        null
                    }
                }
                .asReversed()          // manifest 按时间追加，倒序 = 新在前
            synchronized(cacheLock) {
                cacheDir = backupDir.absolutePath
                cacheStamp = stamp
                cacheSize = size
                cacheEntries = parsed
            }
            if (limit >= parsed.size) parsed else parsed.take(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 读一条备份的内容字节；文件缺失/失败返回 null。 */
    fun readBackup(backupDir: File, entry: BackupEntry): ByteArray? = try {
        val f = File(backupDir, entry.backup)
        if (f.exists()) f.readBytes() else null
    } catch (_: Exception) {
        null
    }

    /**
     * 修剪历史：只保留最近 [keep] 条 manifest，同时删除被挤掉的备份文件。
     * 从头部删（最老），manifest 尾部追加所以旧记录在头部。
     */
    fun prune(backupDir: File, keep: Int = MAX_ENTRIES) {
        try {
            val manifest = backupDir.resolve(MANIFEST)
            if (!manifest.exists()) return
            val lines = manifest.readLines().filter { it.isNotBlank() }
            if (lines.size <= keep) return
            val drop = lines.size - keep
            lines.take(drop).forEach { line ->
                try {
                    val o = JSONObject(line)
                    val name = o.optString("backup", "")
                    if (name.isNotEmpty()) File(backupDir, name).delete()
                    // 【性能】patch 是独立文件，一并清理，否则会越积越多
                    val pf = o.optString("patchFile", "")
                    if (pf.isNotEmpty()) File(backupDir, pf).delete()
                } catch (_: Exception) {
                }
            }
            manifest.writeText(lines.drop(drop).joinToString("\n") + "\n")
            invalidateCache()
        } catch (_: Exception) {
        }
    }

    /** 历史条数（设置页入口显示用）。 */
    fun historyCount(backupDir: File): Int = try {
        backupDir.resolve(MANIFEST).takeIf { it.exists() }
            ?.readLines()?.count { it.isNotBlank() } ?: 0
    } catch (_: Exception) {
        0
    }
}
