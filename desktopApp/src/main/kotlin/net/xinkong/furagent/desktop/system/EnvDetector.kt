package net.xinkong.furagent.desktop.system

import kotlinx.coroutines.*
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 环境检测。
 *
 * 检测本机是否安装了常用的开发工具，用于：
 *  - 设置页展示"已安装 / 未安装"
 *  - 未安装时给出一键安装命令（winget / brew / apt）
 *  - Agent 系统提示词注入"已安装工具"清单，让 AI 知道能用什么
 *
 * 只保留与 FurAgent 日常使用相关、且 AI 真的会用的工具：
 *   java / node / git / python / uv / ffmpeg
 * 删掉工程化的 C/CPP/Rust/Go/Docker/CMake（用户反馈用不到，且占版面）。
 */
object EnvDetector {

    data class ToolDef(
        val key: String,
        val displayName: String,
        val description: String,
        val probeCommand: List<String>,
        val windowsInstall: String,
        val macInstall: String,
        val linuxInstall: String
    )

    data class ToolStatus(
        val key: String,
        val displayName: String,
        val description: String,
        val detected: Boolean,
        val version: String,
        val path: String,
        val installCommand: String
    )

    val TOOLS: List<ToolDef> = listOf(
        ToolDef("java", "Java (JDK)", "运行 .jar、编译 Java 代码", listOf("java", "-version"),
            "winget install Microsoft.OpenJDK.21", "brew install openjdk",
            "sudo apt install openjdk-21-jdk"),
        ToolDef("node", "Node.js", "运行 JS 脚本、npm 包", listOf("node", "--version"),
            "winget install OpenJS.NodeJS.LTS", "brew install node",
            "sudo apt install nodejs npm"),
        ToolDef("git", "Git", "版本控制、克隆仓库", listOf("git", "--version"),
            "winget install Git.Git", "brew install git",
            "sudo apt install git"),
        ToolDef("python", "Python 3", "运行 Python 脚本", listOf("python", "--version"),
            "winget install Python.Python.3.12", "brew install python3",
            "sudo apt install python3"),
        ToolDef("uv", "uv", "极速 Python 包与环境管理器", listOf("uv", "--version"),
            "powershell -c \"irm https://astral.sh/uv/install.ps1 | iex\"", "brew install uv",
            "curl -LsSf https://astral.sh/uv/install.sh | sh"),
        ToolDef("ffmpeg", "FFmpeg", "音视频处理", listOf("ffmpeg", "-version"),
            "winget install ffmpeg", "brew install ffmpeg",
            "sudo apt install ffmpeg")
    )

    private val cache = ConcurrentHashMap<String, ToolStatus>()

    fun currentInstallCommand(def: ToolDef): String {
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        return when {
            os.contains("win") -> def.windowsInstall
            os.contains("mac") -> def.macInstall
            else -> def.linuxInstall
        }
    }

    suspend fun detect(key: String): ToolStatus = withContext(Dispatchers.IO) {
        cache[key]?.let { return@withContext it }
        val def = TOOLS.firstOrNull { it.key == key }
            ?: return@withContext ToolStatus(
                key = key, displayName = key, description = "",
                detected = false, version = "", path = "", installCommand = ""
            )

        val install = currentInstallCommand(def)
        try {
            val pb = ProcessBuilder(def.probeCommand)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val finished = p.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                val s = ToolStatus(key, def.displayName, def.description, false, "", "", install)
                cache[key] = s
                return@withContext s
            }
            val exit = p.exitValue()
            if (exit != 0) {
                val s = ToolStatus(key, def.displayName, def.description, false, "", "", install)
                cache[key] = s
                return@withContext s
            }
            val version = out.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120) ?: ""
            val path = findExecutablePath(def.probeCommand[0]) ?: def.probeCommand[0]
            val s = ToolStatus(key, def.displayName, def.description, true, version, path, install)
            cache[key] = s
            s
        } catch (_: Exception) {
            val s = ToolStatus(key, def.displayName, def.description, false, "", "", install)
            cache[key] = s
            s
        }
    }

    /**
     * 并发探测。原来串行最坏 10×5=50 秒，现在最多 5 秒。
     */
    suspend fun detectAll(): List<ToolStatus> = coroutineScope {
        TOOLS.map { def -> async { detect(def.key) } }.awaitAll()
    }

    fun clearCache() {
        cache.clear()
    }

    private fun findExecutablePath(cmd: String): String? {
        val isWin = System.getProperty("os.name").lowercase().contains("win")
        val f = File(cmd)
        if (f.isAbsolute && f.exists()) return f.absolutePath
        val pathEnv = System.getenv("PATH") ?: return null
        val sep = if (isWin) ";" else ":"
        val exts = if (isWin) listOf(".exe", ".cmd", ".bat", "") else listOf("")
        pathEnv.split(sep).forEach { dir ->
            exts.forEach { ext ->
                val candidate = File(dir, "$cmd$ext")
                if (candidate.exists() && candidate.isFile) return candidate.absolutePath
            }
        }
        return null
    }

    /**
     * 用于系统提示词的"已安装工具"清单。
     */
    suspend fun buildPromptSection(): String = withContext(Dispatchers.IO) {
        val list = coroutineScope {
            TOOLS.map { def -> async { detect(def.key) } }.awaitAll()
        }.filter { it.detected }
        if (list.isEmpty()) return@withContext ""
        val sb = StringBuilder("【用户电脑上已安装的开发工具】\n")
        list.forEach { sb.append("- ${it.displayName}：${it.version.ifBlank { "(已安装)" }}\n") }
        sb.append("这些工具可以直接在命令行中使用，需要时可用 execute_command 调。\n")
        sb.toString()
    }

    /**
     * 执行安装命令。返回 (成功, 输出)。
     */
    suspend fun install(
        key: String,
        onOutput: (String) -> Unit
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val def = TOOLS.firstOrNull { it.key == key }
            ?: return@withContext false to "未知工具：$key"
        val cmd = currentInstallCommand(def)
        if (cmd.isBlank()) return@withContext false to "当前系统无安装命令"

        val isWin = System.getProperty("os.name").lowercase().contains("win")
        val shell = if (isWin) arrayOf("cmd.exe", "/c", "chcp 65001 >nul && $cmd")
        else arrayOf("sh", "-c", cmd)

        try {
            val pb = ProcessBuilder(*shell)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val output = StringBuilder()
            p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                output.append(line).append("\n")
                try { onOutput(line) } catch (_: Exception) { }
            }
            val exit = p.waitFor(30, TimeUnit.MINUTES)
            clearCache()
            if (exit) {
                val code = p.exitValue()
                (code == 0) to output.toString().takeLast(3000)
            } else {
                p.destroyForcibly()
                false to "安装超时，已中止"
            }
        } catch (e: Exception) {
            false to "安装失败：${e.message}"
        }
    }
}