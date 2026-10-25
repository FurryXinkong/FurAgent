package net.xinkong.furagent.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import net.xinkong.furagent.shared.tools.BackupHelper
import net.xinkong.furagent.shared.tools.LineDiff
import java.io.File

/**
 * 修改历史 SQLite 存储（Android 端）——与桌面端 HistoryStore 同表结构、同 API 签名。
 *
 * Android 原生 SQLiteDatabase（零外部依赖），替代 manifest.jsonl + 散落备份文件。
 * 一张 history_entry 表存全部：元数据 + unified patch 文本 + 改前备份字节。
 * 旧 manifest.jsonl 数据已完成一次性迁移（不再保留迁移代码）。
 */
class HistoryStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "history.db"
        private const val DB_VERSION = 1
        private const val TABLE = "history_entry"
        private const val META_TABLE = "meta"

        @Volatile
        private var instance: HistoryStore? = null

        fun getInstance(context: Context): HistoryStore {
            return instance ?: synchronized(this) {
                instance ?: HistoryStore(context).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS $TABLE(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                path TEXT NOT NULL,
                action TEXT NOT NULL,
                size INTEGER DEFAULT 0,
                added INTEGER DEFAULT 0,
                removed INTEGER DEFAULT 0,
                tool TEXT DEFAULT '',
                session_id TEXT DEFAULT '',
                backup BLOB,
                patch TEXT DEFAULT ''
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_he_ts ON $TABLE(ts)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_he_path ON $TABLE(path)")
        db.execSQL("CREATE TABLE IF NOT EXISTS $META_TABLE(key TEXT PRIMARY KEY, value TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ── 查询 ────────────────────────────────────────────────

    /** 历史记录（新 → 旧）。不取 BLOB 列，列表轻量。 */
    fun listHistory(backupDir: File?, limit: Int = 50): List<BackupHelper.BackupEntry> {
        return try {
            val entries = mutableListOf<BackupHelper.BackupEntry>()
            readableDatabase.rawQuery(
                "SELECT ts,path,action,size,added,removed,tool,session_id FROM $TABLE ORDER BY ts DESC LIMIT ?",
                arrayOf(limit.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    entries.add(
                        BackupHelper.BackupEntry(
                            ts = c.getLong(0),
                            path = c.getString(1),
                            backup = "",
                            action = c.getString(2),
                            size = c.getLong(3),
                            patch = "",
                            added = c.getInt(4),
                            removed = c.getInt(5),
                            tool = c.getString(6),
                            patchFile = "",
                            sessionId = c.getString(7)
                        )
                    )
                }
            }
            entries
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 读一条记录的改前备份字节；无备份返回 null。 */
    fun readBackup(entry: BackupHelper.BackupEntry): ByteArray? {
        return try {
            readableDatabase.rawQuery(
                "SELECT backup FROM $TABLE WHERE ts=? AND path=? AND action=? AND backup IS NOT NULL LIMIT 1",
                arrayOf(entry.ts.toString(), entry.path, entry.action)
            ).use { c ->
                if (c.moveToFirst()) c.getBlob(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 读一条记录的 unified patch 文本。 */
    fun readPatch(entry: BackupHelper.BackupEntry): String {
        return try {
            readableDatabase.rawQuery(
                "SELECT patch FROM $TABLE WHERE ts=? AND path=? AND action=? LIMIT 1",
                arrayOf(entry.ts.toString(), entry.path, entry.action)
            ).use { c ->
                if (c.moveToFirst()) c.getString(0) ?: "" else ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    /** 历史总条数。 */
    fun historyCount(backupDir: File?): Int {
        return try {
            readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        } catch (_: Exception) {
            0
        }
    }

    // ── 写入 ────────────────────────────────────────────────

    /**
     * 记录一次编辑：算行级 patch + 元数据/改前字节入表。
     * 与桌面端 HistoryStore.recordEdit 同签名语义。
     */
    fun recordEdit(
        backupDir: File?,
        originalPath: String,
        action: String,
        oldBytes: ByteArray?,
        newBytes: ByteArray?,
        tool: String = action,
        sessionId: String = ""
    ): BackupHelper.BackupEntry? {
        return try {
            val ts = System.currentTimeMillis()
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
            val cv = ContentValues().apply {
                put("ts", ts)
                put("path", originalPath)
                put("action", action)
                put("size", (newBytes?.size ?: 0).toLong())
                put("added", d.added)
                put("removed", d.removed)
                put("tool", tool)
                put("session_id", sessionId)
                put("backup", oldBytes)
                put("patch", d.patch)
            }
            writableDatabase.insert(TABLE, null, cv)
            BackupHelper.BackupEntry(
                ts = ts, path = originalPath, backup = "", action = action,
                size = (newBytes?.size ?: 0).toLong(), patch = "",
                added = d.added, removed = d.removed, tool = tool,
                patchFile = "", sessionId = sessionId
            )
        } catch (_: Exception) {
            null
        }
    }
}