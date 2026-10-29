package net.xinkong.furagent.desktop.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.shared.tools.ToolResult
import org.json.JSONObject
import java.io.File
import java.util.*

/**
 * 应用管理工具（apps）。
 * 从原 SystemIntegrationTools 拆出。
 */
object AppTools {

    data class ToolDef(
        val name: String, val displayName: String,
        val description: String, val paramsSchema: String
    )

    val TOOLS: List<ToolDef> = listOf(
        ToolDef(
            "apps", "应用",
            "应用管理。op=list 列出电脑上已安装的图形应用（可 keyword 过滤，limit 控制数量）；op=open 打开应用/文件/文件夹/URL（path，可选 args）。",
            """{"type":"object","properties":{"op":{"type":"string","enum":["list","open"]},"keyword":{"type":"string"},"limit":{"type":"integer"},"path":{"type":"string"},"args":{"type":"string"}},"required":["op"]}"""
        )
    )

    val NAMES: Set<String> = TOOLS.map { it.name }.toSet()

    fun displayName(name: String): String =
        TOOLS.firstOrNull { it.name == name }?.displayName ?: name

    private val isWin: Boolean get() = System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")
    private val isMac: Boolean get() = System.getProperty("os.name").lowercase(Locale.ROOT).contains("mac")
    private val isLinux: Boolean get() = !isWin && !isMac

    suspend fun execute(name: String, args: JSONObject): ToolResult? {
        if (name != "apps") return null
        val op = args.optString("op", "").lowercase()
        return when (op) {
            "list" -> doListApps(args)
            "open" -> doOpenApp(args)
            else -> ToolResult.fail("op 只能是 list / open")
        }
    }

    private suspend fun doListApps(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val keyword = args.optString("keyword", "").trim()
        val limit = args.optInt("limit", 100).coerceIn(1, 500)
        data class AppEntry(val name: String, val path: String)
        val found = mutableListOf<AppEntry>()

        try {
            when {
                isWin -> {
                    val dirs = listOf(
                        "C:\\ProgramData\\Microsoft\\Windows\\Start Menu\\Programs",
                        System.getenv("APPDATA") + "\\Microsoft\\Windows\\Start Menu\\Programs"
                    )
                    for (d in dirs) {
                        val dir = File(d)
                        if (!dir.exists()) continue
                        dir.walkTopDown()
                            .filter { it.isFile && it.name.endsWith(".lnk", true) }
                            .take(2000)
                            .forEach { f ->
                                val n = f.nameWithoutExtension
                                if (keyword.isBlank() || n.contains(keyword, true))
                                    found.add(AppEntry(n, f.absolutePath))
                            }
                    }
                }
                isMac -> {
                    val dir = File("/Applications")
                    if (dir.isDirectory) {
                        dir.listFiles()?.forEach { f ->
                            val n = f.nameWithoutExtension
                            if (keyword.isBlank() || n.contains(keyword, true))
                                found.add(AppEntry(n, f.absolutePath))
                        }
                    }
                }
                else -> {
                    val dirs = listOf(
                        "/usr/share/applications",
                        "/usr/local/share/applications",
                        System.getProperty("user.home") + "/.local/share/applications"
                    )
                    for (d in dirs) {
                        val dir = File(d)
                        if (!dir.isDirectory) continue
                        dir.listFiles()?.filter { it.name.endsWith(".desktop") }?.forEach { f ->
                            val lines = f.readLines()
                            val nameLine = lines.firstOrNull { it.startsWith("Name=") }
                            val execLine = lines.firstOrNull { it.startsWith("Exec=") }
                            val n = nameLine?.removePrefix("Name=")?.trim() ?: f.nameWithoutExtension
                            val exec = execLine?.removePrefix("Exec=")?.trim()?.substringBefore(" ") ?: ""
                            if (keyword.isBlank() || n.contains(keyword, true))
                                found.add(AppEntry(n, exec))
                        }
                    }
                }
            }
        } catch (_: Exception) { }

        val uniq = found.distinctBy { it.name.lowercase() }.sortedBy { it.name.lowercase() }
        if (uniq.isEmpty()) return@withContext ToolResult.ok(
            if (keyword.isBlank()) "未找到已安装的图形应用"
            else "未找到匹配 \"$keyword\" 的应用"
        )
        val shown = uniq.take(limit)
        val sb = StringBuilder("共 ${uniq.size} 个应用，显示前 ${shown.size} 个：\n\n")
        shown.forEach { sb.append("${it.name}  ->  ${it.path}\n") }
        if (uniq.size > shown.size) sb.append("\n...(还有 ${uniq.size - shown.size} 个未显示)")
        ToolResult.ok(sb.toString())
    }

    private suspend fun doOpenApp(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val path = args.optString("path", "").trim()
        val extraArgs = args.optString("args", "").trim()
        if (path.isBlank()) return@withContext ToolResult.fail("未提供 path")
        try {
            when {
                isWin -> if (extraArgs.isNotBlank())
                    // cmd.exe /c start 的语义：第一个带引号的参数是窗口标题（空标题传 ""），
                    // 第二个是路径。含空格的路径必须加引号，否则被 cmd 当作两个参数。
                    ProcessBuilder("cmd.exe", "/c", "start", "\"\"", "\"$path\"", extraArgs).start()
                else ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", path).start()
                isMac -> if (extraArgs.isNotBlank())
                    ProcessBuilder("open", "-a", path, "--args", extraArgs).start()
                else ProcessBuilder("open", path).start()
                else -> ProcessBuilder("xdg-open", path).start()
            }
            ToolResult.ok("已打开：$path")
        } catch (e: Exception) { ToolResult.fail("打开失败：${e.message}") }
    }
}