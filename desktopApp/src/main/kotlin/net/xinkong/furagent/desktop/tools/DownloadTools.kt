package net.xinkong.furagent.desktop.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.desktop.core.DownloadManager
import net.xinkong.furagent.desktop.core.TaskRuntime
import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.shared.tools.ToolHost
import net.xinkong.furagent.shared.tools.ToolResult
import net.xinkong.furagent.shared.util.DownloadEngine
import net.xinkong.furagent.shared.util.formatFileSize
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 下载文件工具（仅桌面端）。
 *
 * 核心下载逻辑在 shared 的 [DownloadEngine]（与手机端共用），
 * 本类只负责：路径解析、TaskRuntime 进度上报、取消注册。
 */
object DownloadTools {

    data class ToolDef(
        val name: String,
        val displayName: String,
        val description: String,
        val paramsSchema: String
    )

    val TOOLS: List<ToolDef> = listOf(
        ToolDef(
            name = "download_file",
            displayName = "下载文件",
            description = "从 URL 下载文件到电脑。支持多线程分片加速、断点续传、代理（走梯子）。" +
                    "【线程数】threads 参数由你（AI）按情况判断：小文件（<1MB）用 1；" +
                    "大文件（>10MB）且服务器支持 Range 时可用 4~8；不确定就用 1（最稳）。" +
                    "服务器不支持 Range 时会自动降级为单线程。" +
                    "【代理】需要走梯子时传 proxy，如 http://127.0.0.1:7890；" +
                    "不传则自动读环境变量 HTTP_PROXY/HTTPS_PROXY。" +
                    "【保存位置】save_path 可省略，省略时存到 ~/.furagent/downloads/（用 URL 末段命名）。",
            paramsSchema = """{"type":"object","properties":{""" +
                    """"url":{"type":"string","description":"下载地址"},""" +
                    """"save_path":{"type":"string","description":"保存路径。可省略，省略时存到 ~/.furagent/downloads/。支持 ~ 和中文别名（桌面/下载/图片）"},""" +
                    """"threads":{"type":"integer","description":"下载线程数，由 AI 判断。默认 1。大文件可 4~8，服务器不支持 Range 时自动降级为 1"},""" +
                    """"proxy":{"type":"string","description":"可选代理，如 http://127.0.0.1:7890（走梯子）。不传则读环境变量 HTTP_PROXY/HTTPS_PROXY"},""" +
                    """"resume":{"type":"boolean","description":"断点续传，默认 false。true 时复用已下载的分片"}""" +
                    """},"required":["url"]}"""
        )
    )

    val NAMES: Set<String> = TOOLS.map { it.name }.toSet()

    fun displayName(name: String): String =
        TOOLS.firstOrNull { it.name == name }?.displayName ?: name

    suspend fun execute(name: String, args: JSONObject, host: ToolHost): ToolResult? {
        if (name !in NAMES) return null

        val url = args.optString("url", "").trim()
        if (url.isBlank()) return ToolResult.fail("download_file 需要 url 参数")

        val savePathRaw = args.optString("save_path", "").trim()
        val savePath = resolveSavePath(savePathRaw, url, host)
            ?: return ToolResult.fail("无法解析保存路径：$savePathRaw")

        val threads = args.optInt("threads", 1).coerceIn(1, 32)
        val proxy = args.optString("proxy", "").trim().ifBlank { null }
        val resume = args.optBoolean("resume", false)

        val target = File(savePath)
        if (target.exists() && !resume) {
            return ToolResult.fail("目标文件已存在：$savePath（如需覆盖请先删除，或传 resume=true 续传）")
        }

        return withContext(Dispatchers.IO) {
            val taskId = TaskRuntime.currentId()
            val cancelled = AtomicBoolean(false)
            if (taskId != null) {
                TaskRuntime.registerCancel(taskId) { cancelled.set(true) }
            }
            // 与 phone_receive_file / 状态面板对齐 把下载进度旁路登记到 DownloadManager，
            // 让右下角 DownloadToastBar 也能看到进度（旧实现只有 TaskFloatingBar 可见）。
            val displayId = try {
                DownloadManager.beginExternal(url, savePath)
            } catch (_: Exception) { null }

            try {
                val result = DownloadEngine.download(
                    url = url,
                    target = target,
                    threads = threads,
                    proxy = proxy,
                    resume = resume,
                    onProgress = { done, total ->
                        if (taskId != null) {
                            val p = if (total > 0) done.toFloat() / total else null
                            val detail = formatFileSize(done) +
                                    if (total > 0) " / ${formatFileSize(total)}" else ""
                            TaskRuntime.updateProgress(taskId, p, detail)
                        }
                        if (displayId != null) {
                            try { DownloadManager.updateExternal(displayId, done, total) } catch (_: Exception) { }
                        }
                    },
                    isCancelled = { cancelled.get() }
                )
                // 无论成功失败都标记，让 Toast 自动收起
                if (displayId != null) {
                    try {
                        DownloadManager.finishExternal(
                            displayId, result.ok,
                            if (result.ok) null else result.message
                        )
                    } catch (_: Exception) { }
                }

                if (!result.ok) {
                    return@withContext ToolResult.fail(result.message)
                }

                val note = buildString {
                    append("已下载：$savePath（${formatFileSize(result.bytes)}）")
                    if (result.threadsUsed > 1) append("，${result.threadsUsed} 线程")
                    else if (threads > 1) append("，单线程（服务器不支持 Range 或文件过小）")
                    if (proxy != null) append("，经代理 $proxy")
                }
                ToolResult.ok(note)
            } catch (e: Exception) {
                DesktopLogger.error(
                    "DownloadTools",
                    "下载失败（$url -> $savePath）：${e.javaClass.simpleName}: ${e.message}"
                )
                ToolResult.fail("下载失败：${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * 解析保存路径：
     *   - 空 → ~/.furagent/downloads/<URL 末段文件名>
     *   - 纯文件名 → ~/.furagent/downloads/<文件名>
     *   - 其他 → host.resolvePath
     */
    private fun resolveSavePath(raw: String, url: String, host: ToolHost): String? {
        val defaultDir = host.resolvePath("~/.furagent/downloads")
        val name = raw.trim()
        if (name.isBlank()) {
            val dir = defaultDir ?: return null
            File(dir).mkdirs()
            return File(dir, DownloadEngine.guessFileName(url)).absolutePath
        }
        if (!name.contains('/') && !name.contains('\\')) {
            val dir = defaultDir ?: return host.resolvePath(name)
            File(dir).mkdirs()
            return File(dir, name).absolutePath
        }
        return host.resolvePath(name)
    }
}
