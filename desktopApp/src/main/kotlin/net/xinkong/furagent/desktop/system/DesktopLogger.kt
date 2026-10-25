package net.xinkong.furagent.desktop.system

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.xinkong.furagent.desktop.data.DesktopPaths
import net.xinkong.furagent.shared.util.DateFormats
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * 桌面端日志。
 *
 * 双写：
 *  - 内存（StateFlow 供 UI 订阅"实时"）
 *  - 文件（~/.furagent/logs/yyyy-MM-dd.log，按天滚动，保留 7 天，单文件超 10MB 切分）
 *
 * 【性能】环形 ArrayDeque + 100ms 节流。
 *
 * 【完整性】单条 content 上限 1_000_000 字符（约 1MB），与 Android 端对齐。
 */
object DesktopLogger {

    private val RE_ILLEGAL_FN = Regex("""[\\/:*?"<>|]""")

    data class Entry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String
    )

    private const val MAX_ENTRIES = 1000
    private const val KEEP_DAYS = 7
    private const val MAX_FILE_SIZE = 10L * 1024 * 1024

    /**
     * 单条 message 上限。
     *
     * 1MB。与 Android 端 AgentLogger 对齐。
     * UI 树的文本量本身不大（复杂屏幕也就几万字符），
     * 但用户可能主动读大文件 / 大网页，日志要完整记录。
     */
    private const val MAX_CONTENT_LEN = 1_000_000

    private const val EMIT_THROTTLE_MS = 100L

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val buffer = ArrayDeque<Entry>(MAX_ENTRIES + 1)
    private val bufferLock = Any()

    private val emitScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val emitPending = AtomicBoolean(false)

    private val writeQueue = LinkedBlockingQueue<String>()
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DesktopLogger-writer").apply { isDaemon = true }
    }

    private val fileSdf = DateFormats.fullMsFormatter()
    private val dateSdf = DateFormats.dateFormatter()

    @Volatile private var started = false

    fun init() {
        if (started) return
        started = true
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
        cleanupOldFiles()
    }

    private fun logDir(): File = DesktopPaths.logsDir()

    private fun writeBatch(batch: List<String>) {
        try {
            val dir = logDir()
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
                batch.forEach { w.write(it); w.newLine() }
            }
        } catch (_: Exception) { }
    }

    fun log(level: String, tag: String, message: String) {
        val ts = System.currentTimeMillis()
        val full = if (message.length > MAX_CONTENT_LEN) {
            message.take(MAX_CONTENT_LEN) +
                    "\n...(日志单条超过 $MAX_CONTENT_LEN 字符已截断，完整长度 ${message.length})"
        } else message
        val entry = Entry(ts, level, tag, full)

        writeQueue.offer("${fileSdf.format(Date(ts))} [$level] [$tag] $full")

        synchronized(bufferLock) {
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        }

        scheduleEmit()

        // 控制台也打一份
        try {
            println("[${level.uppercase()}][$tag] $message")
        } catch (_: Throwable) { }
    }

    private fun scheduleEmit() {
        if (!emitPending.compareAndSet(false, true)) return
        emitScope.launch {
            delay(EMIT_THROTTLE_MS.milliseconds)
            emitPending.set(false)
            val snapshot = synchronized(bufferLock) { buffer.toList() }
            _entries.value = snapshot
        }
    }

    fun debug(tag: String, msg: String) = log("DEBUG", tag, msg)
    fun info(tag: String, msg: String) = log("INFO", tag, msg)
    fun warn(tag: String, msg: String) = log("WARN", tag, msg)
    fun error(tag: String, msg: String) = log("ERROR", tag, msg)

    fun clear() {
        synchronized(bufferLock) { buffer.clear() }
        _entries.value = emptyList()
    }

    /** 当前内存里的日志快照。 */
    fun snapshot(): List<Entry> = synchronized(bufferLock) { buffer.toList() }

    /** 单条日志的纯文本行（不带换行）。时间格式：yyyy-MM-dd HH:mm:ss.SSS。 */
    fun formatLine(e: Entry): String {
        val fmt = DateFormats.fullMsFormatter()
        return "${fmt.format(Date(e.timestamp))} [${e.level}] [${e.tag}] ${e.message}"
    }

    fun exportText(): String {
        return snapshot().joinToString("\n") { formatLine(it) }
    }

    /**
     * 把一组日志导出成 Markdown 文件，写到 ~/.furagent/exports/。
     *
     * 与「导出会话」保持一致的落盘方式（返回 File，失败返回 null）。
     *
     * @param label  文件名标签与文档标题，如「全部日志」「2026-10-14」。
     * @param entries 要导出的日志（调用方决定是实时缓冲还是某个日志文件）。
     */
    fun exportMarkdown(label: String, entries: List<Entry>): File? {
        return try {
            if (entries.isEmpty()) return null
            val dir = DesktopPaths.exportsDir()
            if (!dir.exists()) dir.mkdirs()
            val safeLabel = label.replace(RE_ILLEGAL_FN, "_")
            val ts = DateFormats.formatFileTs()
            val file = File(dir, "${safeLabel}_$ts.md")
            file.writeText(buildMarkdown(label, entries), Charsets.UTF_8)
            file
        } catch (e: Exception) {
            error("Logger", "导出日志 Markdown 失败：${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** 拼 Markdown 正文。单条 message 内部换行会缩进成代码块，保证仍可读。 */
    private fun buildMarkdown(label: String, entries: List<Entry>): String {
        val sb = StringBuilder()
        sb.append("# 日志 · $label\n\n")
        sb.append("导出时间：")
            .append(DateFormats.formatDateTime())
            .append("\n\n")
        sb.append("共 ${entries.size} 条\n\n---\n\n")
        sb.append("```log\n")
        entries.forEach { e ->
            sb.append(formatLine(e)).append('\n')
        }
        sb.append("```\n")
        return sb.toString()
    }

    fun listLogDates(): List<String> {
        val dir = logDir()
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "log" }
            ?.map { it.name.removeSuffix(".log") }
            ?.sortedDescending()
            ?: emptyList()
    }

    fun logFileFor(date: String): File? {
        val f = File(logDir(), "$date.log")
        return if (f.exists()) f else null
    }

    fun loadFromFile(date: String): List<Entry> {
        val f = logFileFor(date) ?: return emptyList()
        return try {
            f.readLines(Charsets.UTF_8).mapNotNull { parseLine(it) }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseLine(line: String): Entry? {
        val open1 = line.indexOf('[')
        if (open1 <= 0) return null
        val close1 = line.indexOf("] ", open1)
        if (close1 < 0) return null
        val open2 = close1 + 2
        if (open2 >= line.length || line[open2] != '[') return null
        val close2 = line.indexOf("] ", open2)
        if (close2 < 0) return null

        val tsStr = line.substring(0, open1).trim()
        val level = line.substring(open1 + 1, close1)
        val tag = line.substring(open2 + 1, close2)
        val msg = line.substring(close2 + 2)
        val ts = DateFormats.parseFullMs(tsStr) ?: return null
        return Entry(ts, level, tag, msg)
    }

    fun clearFiles() {
        try {
            logDir().listFiles()?.forEach { it.delete() }
        } catch (_: Exception) { }
    }

    private fun cleanupOldFiles() {
        try {
            val dir = logDir()
            if (!dir.exists()) return
            val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600L * 1000L
            dir.listFiles()?.forEach { f -> if (f.lastModified() < cutoff) f.delete() }
        } catch (_: Exception) { }
    }
}