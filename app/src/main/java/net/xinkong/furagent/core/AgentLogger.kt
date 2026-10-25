package net.xinkong.furagent.core

import net.xinkong.furagent.shared.util.DateFormats
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import net.xinkong.furagent.data.LogEntry
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 日志系统。
 *
 * 双写：
 *  - 内存列表（Compose 直接订阅，给 UI 显示"实时"）
 *  - 文件（filesDir/logs/yyyy-MM-dd.log，按天滚动，保留 7 天，单文件超 10MB 切分）
 *
 * 【性能】
 *  - 100ms 节流：主线程处理次数从"N 次 add"降为"1 次 addAll"
 *  - `_logs` 仍是 SnapshotStateList，Compose diff 机制不变
 *
 * 【完整性】
 *  - 单条 content 上限 1_000_000 字符（约 1MB），足够覆盖任何工具返回
 *  - 内存条数：panelOpen=true 保留 500 条，否则 200 条
 */
object AgentLogger {

    private const val MAX_LOG_ENTRIES = 500
    private const val IDLE_KEEP_ENTRIES = 200
    private const val LOG_DIR_NAME = "logs"
    private const val KEEP_DAYS = 7
    private const val MAX_FILE_SIZE = 10L * 1024 * 1024

    /**
     * 单条 content 上限。
     *
     * 1MB。与桌面端 DesktopLogger 对齐。
     * UI 树的文本量本身不大（复杂屏幕也就几万字符），
     * 但用户可能主动读大文件 / 大网页，日志要完整记录。
     */
    private const val MAX_CONTENT_LEN = 1_000_000

    private const val FLUSH_THROTTLE_MS = 100L

    private val _logs = mutableStateListOf<LogEntry>()
    val logs: List<LogEntry> get() = _logs
    private val h = Handler(Looper.getMainLooper())

    @Volatile
    var panelOpen: Boolean = false

    private val pendingQueue = ArrayDeque<LogEntry>()
    private val pendingLock = Any()
    private val flushPending = AtomicBoolean(false)

    @Volatile private var appContext: Context? = null
    @Volatile private var workerStarted = false

    private val writeQueue = LinkedBlockingQueue<String>()
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AgentLogger-writer").apply { isDaemon = true }
    }

    private val fileSdf = DateFormats.fullMsFormatter()
    private val dateSdf = DateFormats.dateFormatter()

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        startWriter()
        cleanupOldFiles()
    }

    private fun startWriter() {
        if (workerStarted) return
        workerStarted = true
        writer.execute {
            while (true) {
                try {
                    val first = writeQueue.take()
                    val batch = mutableListOf(first)
                    writeQueue.drainTo(batch, 255)
                    writeBatch(batch)
                } catch (_: InterruptedException) {
                    return@execute
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun writeBatch(batch: List<String>) {
        val ctx = appContext ?: return
        try {
            val dir = File(ctx.filesDir, LOG_DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            val now = System.currentTimeMillis()
            val baseName = dateSdf.format(Date(now))
            var target = File(dir, "$baseName.log")
            if (target.exists() && target.length() > MAX_FILE_SIZE) {
                val ts = DateFormats.formatHourTs(now)
                target.renameTo(File(dir, "${baseName}_$ts.log"))
                target = File(dir, "$baseName.log")
            }
            FileOutputStream(target, true).bufferedWriter(Charsets.UTF_8).use { w ->
                batch.forEach {
                    w.write(it)
                    w.newLine()
                }
            }
        } catch (_: Exception) { }
    }

    private fun enqueueFile(line: String) {
        if (appContext == null) return
        writeQueue.offer(line)
    }

    fun log(type: String, content: String) {
        val now = System.currentTimeMillis()
        val full = if (content.length > MAX_CONTENT_LEN) {
            content.take(MAX_CONTENT_LEN) + "\n...(日志单条超过 $MAX_CONTENT_LEN 字符已截断，完整长度 ${content.length})"
        } else content
        val entry = LogEntry(now, type, full)

        enqueueFile("${fileSdf.format(Date(now))} [$type] $full")

        if (!panelOpen && _logs.size >= IDLE_KEEP_ENTRIES) return

        synchronized(pendingLock) {
            pendingQueue.addLast(entry)
            while (pendingQueue.size > MAX_LOG_ENTRIES * 2) {
                pendingQueue.removeFirst()
            }
        }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (!flushPending.compareAndSet(false, true)) return
        h.postDelayed({
            flushPending.set(false)
            val batch: List<LogEntry>
            synchronized(pendingLock) {
                if (pendingQueue.isEmpty()) return@postDelayed
                batch = pendingQueue.toList()
                pendingQueue.clear()
            }
            _logs.addAll(batch)
            while (_logs.size > MAX_LOG_ENTRIES) _logs.removeAt(0)
        }, FLUSH_THROTTLE_MS)
    }

    fun clear() {
        synchronized(pendingLock) { pendingQueue.clear() }
        h.post { _logs.clear() }
    }

    fun logDir(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, LOG_DIR_NAME)
    }

    fun listLogDates(): List<String> {
        val dir = logDir() ?: return emptyList()
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "log" }
            ?.map { it.name.removeSuffix(".log") }
            ?.sortedDescending()
            ?: emptyList()
    }

    fun logFileFor(date: String): File? {
        val dir = logDir() ?: return null
        val f = File(dir, "$date.log")
        return if (f.exists()) f else null
    }

    fun loadFromFile(date: String): List<LogEntry> {
        val f = logFileFor(date) ?: return emptyList()
        return try {
            f.readLines(Charsets.UTF_8).mapNotNull { parseLine(it) }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseLine(line: String): LogEntry? {
        val open = line.indexOf('[')
        if (open <= 0) return null
        val close = line.indexOf("] ", open)
        if (close < 0) return null
        val tsStr = line.substring(0, open).trim()
        val type = line.substring(open + 1, close)
        val content = line.substring(close + 2)
        val ts = DateFormats.parseFullMs(tsStr) ?: return null
        return LogEntry(ts, type, content)
    }

    fun clearFiles() {
        try {
            val dir = logDir() ?: return
            dir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) { }
    }

    private fun cleanupOldFiles() {
        try {
            val dir = logDir() ?: return
            if (!dir.exists()) return
            val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600L * 1000L
            dir.listFiles()?.forEach { f ->
                if (f.lastModified() < cutoff) f.delete()
            }
        } catch (_: Exception) { }
    }
}

val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }