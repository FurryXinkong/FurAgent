package net.xinkong.furagent.desktop.data

import net.xinkong.furagent.shared.tools.BackupHelper
import net.xinkong.furagent.shared.tools.LineDiff
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types

/**
 * 修改历史 SQLite 存储（桌面端）——替代 manifest.jsonl + 散落备份文件的文件系统方案。
 *
 * 一张 history_entry 表存全部：元数据 + unified patch 文本 + 改前备份字节。
 * 历史不再受 BackupHelper 200 条 prune 的静默丢弃；时间段/按文件查询走索引。
 *
 * 并发模型（针对「SQLite 写锁」的顾虑）：
 *   - 单连接 + 全操作 synchronized 串行 = 本进程内零锁争用；
 *   - WAL + busy_timeout=5000 兜底外部进程，拿不到锁是等待重试而非死锁。
 *
 * 旧 manifest.jsonl 数据已完成一次性迁移（不再保留迁移代码）。
 */
object HistoryStore {

    @Volatile
    private var conn: Connection? = null
    private val lock = Any()

    private fun dbFile() = File(File(System.getProperty("user.home"), ".furagent"), "history.db")

    private fun connection(): Connection {
        conn?.takeIf { !it.isClosed }?.let { return it }
        synchronized(lock) {
            conn?.takeIf { !it.isClosed }?.let { return it }
            dbFile().parentFile?.mkdirs()
            val c = DriverManager.getConnection("jdbc:sqlite:${dbFile().absolutePath}")
            c.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("PRAGMA busy_timeout=5000")
                st.execute("PRAGMA synchronous=NORMAL")
                st.execute(
                    """CREATE TABLE IF NOT EXISTS history_entry(
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
                st.execute("CREATE INDEX IF NOT EXISTS idx_he_ts ON history_entry(ts)")
                st.execute("CREATE INDEX IF NOT EXISTS idx_he_path ON history_entry(path)")
                st.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)")
            }
            conn = c
            return c
        }
    }

    // ── 查询 ────────────────────────────────────────────────

    /** 历史记录（新 → 旧）。不取 BLOB 列，列表轻量。 */
    fun listHistory(backupDir: File?, limit: Int = 50): List<BackupHelper.BackupEntry> {
        return try {
            val c = connection()
            synchronized(lock) {
                c.prepareStatement(
                    "SELECT ts,path,action,size,added,removed,tool,session_id FROM history_entry ORDER BY ts DESC LIMIT ?"
                ).use { st ->
                    st.setInt(1, limit)
                    st.executeQuery().use { rs ->
                        val out = ArrayList<BackupHelper.BackupEntry>(minOf(limit, 256))
                        while (rs.next()) {
                            out.add(
                                BackupHelper.BackupEntry(
                                    ts = rs.getLong(1),
                                    path = rs.getString(2),
                                    backup = "",
                                    action = rs.getString(3),
                                    size = rs.getLong(4),
                                    patch = "",
                                    added = rs.getInt(5),
                                    removed = rs.getInt(6),
                                    tool = rs.getString(7),
                                    patchFile = "",
                                    sessionId = rs.getString(8)
                                )
                            )
                        }
                        out
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 读一条记录的改前备份字节；无备份（新建文件/二进制以外空记录）返回 null。 */
    fun readBackup(entry: BackupHelper.BackupEntry): ByteArray? {
        return try {
            val c = connection()
            synchronized(lock) {
                c.prepareStatement(
                    "SELECT backup FROM history_entry WHERE ts=? AND path=? AND action=? AND backup IS NOT NULL LIMIT 1"
                ).use { st ->
                    st.setLong(1, entry.ts)
                    st.setString(2, entry.path)
                    st.setString(3, entry.action)
                    st.executeQuery().use { rs -> if (rs.next()) rs.getBytes(1) else null }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 读一条记录的 unified patch 文本。 */
    fun readPatch(entry: BackupHelper.BackupEntry): String {
        return try {
            val c = connection()
            synchronized(lock) {
                c.prepareStatement(
                    "SELECT patch FROM history_entry WHERE ts=? AND path=? AND action=? LIMIT 1"
                ).use { st ->
                    st.setLong(1, entry.ts)
                    st.setString(2, entry.path)
                    st.setString(3, entry.action)
                    st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) ?: "" else "" }
                }
            }
        } catch (_: Exception) {
            ""
        }
    }

    /** 历史总条数（设置页入口显示用）。 */
    fun historyCount(backupDir: File?): Int {
        return try {
            val c = connection()
            synchronized(lock) {
                c.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM history_entry").use { rs ->
                        if (rs.next()) rs.getInt(1) else 0
                    }
                }
            }
        } catch (_: Exception) {
            0
        }
    }

    // ── 写入 ────────────────────────────────────────────────

    /**
     * 记录一次编辑：算行级 patch + 元数据/改前字节入表。
     * 与 BackupHelper.recordEdit 同签名语义（oldBytes 改前、newBytes 改后、二进制不算 diff）。
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
            val c = connection()
            synchronized(lock) {
                c.prepareStatement(
                    "INSERT INTO history_entry(ts,path,action,size,added,removed,tool,session_id,backup,patch) VALUES(?,?,?,?,?,?,?,?,?,?)"
                ).use { st ->
                    st.setLong(1, ts)
                    st.setString(2, originalPath)
                    st.setString(3, action)
                    st.setLong(4, (newBytes?.size ?: 0).toLong())
                    st.setInt(5, d.added)
                    st.setInt(6, d.removed)
                    st.setString(7, tool)
                    st.setString(8, sessionId)
                    if (oldBytes != null) st.setBytes(9, oldBytes) else st.setNull(9, Types.BLOB)
                    st.setString(10, d.patch)
                    st.executeUpdate()
                }
            }
            BackupHelper.BackupEntry(
                ts = ts,
                path = originalPath,
                backup = "",
                action = action,
                size = (newBytes?.size ?: 0).toLong(),
                patch = "",
                added = d.added,
                removed = d.removed,
                tool = tool,
                patchFile = "",
                sessionId = sessionId
            )
        } catch (_: Exception) {
            null
        }
    }
}
