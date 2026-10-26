package net.xinkong.furagent.desktop.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.xinkong.furagent.desktop.system.DesktopLogger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object DownloadManager {

    enum class Status { PENDING, DOWNLOADING, COMPLETED, FAILED, CANCELLED }

    data class Task(
        val id: String,
        val url: String,
        val savePath: String,
        val fileName: String,
        val totalBytes: Long = -1L,
        val downloadedBytes: Long = 0L,
        val status: Status = Status.PENDING,
        val error: String? = null,
        val speedBps: Long = 0L,
        val startedAt: Long = System.currentTimeMillis(),
        val finishedAt: Long = 0L
    ) {
        val progress: Float
            get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            else 0f

        val etaSeconds: Long
            get() {
                if (speedBps <= 0 || totalBytes <= 0) return -1L
                val remain = totalBytes - downloadedBytes
                if (remain <= 0) return 0L
                return remain / speedBps
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    fun download(url: String, savePath: String): String {
        val id = UUID.randomUUID().toString()
        val fileName = File(savePath).name
        val task = Task(id = id, url = url, savePath = savePath, fileName = fileName)
        _tasks.value += task

        val job = scope.launch { runDownload(id) }
        jobs[id] = job
        return id
    }

    /**
     * 外部任务旁路登记 DownloadEngine 走的是 shared 里的跨端下载引擎，
     * 不经过本类的 scope/jobs，但用户希望右下角 DownloadToastBar 也能看到进度。
     * 这里提供三个方法让调用方自主上报：
     *   - beginExternal 返回 id，把任务加进 tasks（状态 DOWNLOADING）
     *   - updateExternal 上报进度
     *   - finishExternal 标记完成/失败
     * 外部任务不在 jobs 里，cancel(id) 不会影响它（由调用方自己的取消机制处理）。
     */
    fun beginExternal(url: String, savePath: String): String {
        val id = UUID.randomUUID().toString()
        val fileName = File(savePath).name
        val task = Task(
            id = id,
            url = url,
            savePath = savePath,
            fileName = fileName,
            status = Status.DOWNLOADING,
            startedAt = System.currentTimeMillis()
        )
        _tasks.value += task
        return id
    }

    fun updateExternal(id: String, downloaded: Long, total: Long, speedBps: Long = 0L) {
        updateTask(id) {
            it.copy(
                downloadedBytes = downloaded,
                totalBytes = if (total > 0) total else it.totalBytes,
                speedBps = speedBps
            )
        }
    }

    fun finishExternal(id: String, success: Boolean, error: String? = null) {
        updateTask(id) {
            it.copy(
                status = if (success) Status.COMPLETED else Status.FAILED,
                error = error,
                finishedAt = System.currentTimeMillis(),
                speedBps = 0
            )
        }
    }

    fun cancel(id: String) {
        jobs[id]?.cancel()
        jobs.remove(id)
        updateTask(id) { it.copy(status = Status.CANCELLED, finishedAt = System.currentTimeMillis()) }
    }

    fun remove(id: String) {
        _tasks.value = _tasks.value.filterNot { it.id == id }
        jobs.remove(id)
    }

    fun clearFinished() {
        _tasks.value = _tasks.value.filter {
            it.status == Status.PENDING || it.status == Status.DOWNLOADING
        }
    }

    private fun updateTask(id: String, block: (Task) -> Task) {
        _tasks.value = _tasks.value.map { if (it.id == id) block(it) else it }
    }

    private suspend fun runDownload(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        updateTask(id) { it.copy(status = Status.DOWNLOADING, startedAt = System.currentTimeMillis()) }

        var conn: HttpURLConnection? = null
        try {
            val target = File(task.savePath)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")

            val u = java.net.URI(task.url).toURL()
            conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 0
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "FurAgent/1.0")
            conn.connect()

            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")

            val total = conn.contentLengthLong
            updateTask(id) { it.copy(totalBytes = total) }

            val input = conn.inputStream
            tmp.outputStream().use { out ->
                val buf = ByteArray(128 * 1024)
                var downloaded = 0L
                var lastEmit = System.currentTimeMillis()
                var lastBytes = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    downloaded += n

                    val now = System.currentTimeMillis()
                    if (now - lastEmit >= 400) {
                        val dt = (now - lastEmit).coerceAtLeast(1)
                        val speed = (downloaded - lastBytes) * 1000 / dt
                        updateTask(id) { it.copy(downloadedBytes = downloaded, speedBps = speed) }
                        lastEmit = now
                        lastBytes = downloaded
                    }
                }
                updateTask(id) { it.copy(downloadedBytes = downloaded) }
            }
            input.close()

            if (!tmp.renameTo(target)) {
                target.writeBytes(tmp.readBytes())
                tmp.delete()
            }
            updateTask(id) {
                it.copy(
                    status = Status.COMPLETED,
                    downloadedBytes = target.length(),
                    totalBytes = if (total > 0) total else target.length(),
                    finishedAt = System.currentTimeMillis(),
                    speedBps = 0
                )
            }
            DesktopLogger.info("Download", "已完成：${target.absolutePath}")
        } catch (e: CancellationException) {
            try { conn?.disconnect() } catch (_: Exception) { }
            updateTask(id) { it.copy(status = Status.CANCELLED, finishedAt = System.currentTimeMillis()) }
            DesktopLogger.info("Download", "已取消：${task.url}")
            throw e
        } catch (e: Exception) {
            try { conn?.disconnect() } catch (_: Exception) { }
            updateTask(id) {
                it.copy(
                    status = Status.FAILED,
                    error = e.message ?: "下载失败",
                    finishedAt = System.currentTimeMillis()
                )
            }
            DesktopLogger.error("Download", "下载失败：${e.message}")
        } finally {
            jobs.remove(id)
        }
    }
}