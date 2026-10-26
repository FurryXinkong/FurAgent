package net.xinkong.furagent.desktop.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.desktop.core.DesktopConfirmation
import net.xinkong.furagent.desktop.core.TaskRuntime
import net.xinkong.furagent.desktop.data.DesktopSettingsHolder
import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.shared.tools.ToolResult
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object PythonTools {

    data class ToolDef(
        val name: String,
        val displayName: String,
        val description: String,
        val paramsSchema: String
    )

    val TOOLS: List<ToolDef> = listOf(
        ToolDef(
            name = "python",
            displayName = "Python",
            description = "执行 Python 3，可运行任意 Python 3 代码。op=run_code 执行一段 Python 源码（code，可选 args/timeout/work_dir/stdin）；op=run_file 执行已有 .py 文件（path，可选 args/timeout/work_dir/stdin）；op=info 查询 Python 版本与已安装包；op=install 通过 pip 安装包（package，可选 upgrade）。执行代码/文件/pip 安装前会弹确认。" +
                    "\n\n【注意】如果用户要求「做个脚本 / 加个功能 / 以后都用这个流程」，不要用 python 工具直接跑一段代码了事。" +
                    "持久化自定义脚本请用 skill 工具（tool_search(\"自定义脚本\") / tool_load(\"skill\")）创建：一个脚本就是一个 ~/.furagent/scripts/xxx.py，" +
                    "元数据写在该文件头部的模块级变量里（NAME / DESCRIPTION / PARAMS / TIMEOUT），由 skill 工具自动生成；不要手写缺元数据的裸 .py。",
            paramsSchema = """{"type":"object","properties":{"op":{"type":"string","enum":["run_code","run_file","info","install"]},"code":{"type":"string"},"timeout":{"type":"integer"},"work_dir":{"type":"string"},"stdin":{"type":"string"},"path":{"type":"string"},"args":{"type":"string"},"package":{"type":"string"},"upgrade":{"type":"boolean"}},"required":["op"]}"""
        )
    )

    val NAMES: Set<String> = TOOLS.map { it.name }.toSet()

    fun displayName(name: String): String =
        TOOLS.firstOrNull { it.name == name }?.displayName ?: name

    // Python 探测

    @Volatile private var cachedPython: String? = null
    @Volatile private var pythonMissExpireAt: Long = 0L
    private const val MISS_TTL_MS = 5_000L

    /** 临时脚本名唯一化，避免同一毫秒并发撞名。 */
    private val tempSeq = AtomicLong(0)

    fun clearCache() {
        cachedPython = null
        pythonMissExpireAt = 0L
    }

    private fun currentPython(): String? {
        val configured = DesktopSettingsHolder.current.toolPath("python").trim()
        if (configured.isNotBlank()) {
            val f = File(configured)
            if (f.exists() && f.isFile) return configured
        }
        cachedPython?.let { return it }
        val now = System.currentTimeMillis()
        if (now < pythonMissExpireAt) return null
        val detected = detectPython()
        if (detected != null) cachedPython = detected
        else pythonMissExpireAt = now + MISS_TTL_MS
        return detected
    }

    private fun detectPython(): String? {
        val isWin = System.getProperty("os.name").lowercase().contains("win")
        val candidates = if (isWin) {
            listOf("python", "python3", "py")
        } else {
            listOf("python3", "python")
        }

        for (cmd in candidates) {
            try {
                val p = ProcessBuilder(cmd, "--version").redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
                val finished = p.waitFor(5, TimeUnit.SECONDS)
                if (finished && p.exitValue() == 0 && out.startsWith("Python 3")) {
                    DesktopLogger.info("Python", "找到：$cmd → $out")
                    return cmd
                }
                p.destroyForcibly()
            } catch (_: Exception) { }
        }

        if (isWin) {
            val localAppData = System.getenv("LOCALAPPDATA") ?: ""
            val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
            val userProfile = System.getProperty("user.home") ?: ""
            val candidates2 = listOf(
                "$localAppData\\Programs\\Python\\Python313\\python.exe",
                "$localAppData\\Programs\\Python\\Python312\\python.exe",
                "$localAppData\\Programs\\Python\\Python311\\python.exe",
                "$localAppData\\Programs\\Python\\Python310\\python.exe",
                "$userProfile\\scoop\\shims\\python.exe",
                "C:\\Python313\\python.exe",
                "C:\\Python312\\python.exe",
                "$programFiles\\Python313\\python.exe",
                "$programFiles\\Python312\\python.exe"
            )
            for (c in candidates2) {
                val f = File(c)
                if (f.exists() && f.isFile) {
                    try {
                        val p = ProcessBuilder(f.absolutePath, "--version").redirectErrorStream(true).start()
                        val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
                        val finished = p.waitFor(5, TimeUnit.SECONDS)
                        if (finished && p.exitValue() == 0 && out.startsWith("Python 3")) {
                            DesktopLogger.info("Python", "找到：${f.absolutePath}")
                            return f.absolutePath
                        }
                        p.destroyForcibly()
                    } catch (_: Exception) { }
                }
            }
        }

        val unixPaths = listOf(
            "/usr/local/bin/python3",
            "/opt/homebrew/bin/python3",
            "/usr/bin/python3",
            "/bin/python3",
            "/snap/bin/python3"
        )
        for (c in unixPaths) {
            val f = File(c)
            if (f.exists() && f.canExecute()) {
                try {
                    val p = ProcessBuilder(c, "--version").redirectErrorStream(true).start()
                    val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
                    val finished = p.waitFor(5, TimeUnit.SECONDS)
                    if (finished && p.exitValue() == 0 && out.startsWith("Python 3")) return c
                    p.destroyForcibly()
                } catch (_: Exception) { }
            }
        }

        return null
    }

    fun isAvailable(): Boolean = currentPython() != null

    fun getPythonPath(): String? = currentPython()

    private fun notInstalledMsg(): String = "Python 3 未安装或不在 PATH 中"

    // 分发

    suspend fun execute(name: String, args: JSONObject): ToolResult? {
        if (name != "python") return null
        val op = args.optString("op", "").lowercase()

        // 拦截 Python 代码里的删除操作。
        // AI 不得以任何方式直接删除文件，包括通过 python 绕过 delete_file。
        if (op == "run_code" || op == "run_file") {
            val src = if (op == "run_code") args.optString("code", "")
            else runCatching { File(args.optString("path", "")).readText(Charsets.UTF_8) }.getOrDefault("")
            detectDestructivePython(src)?.let { return ToolResult.fail(it) }
        }

        return when (op) {
            "run_code" -> doRunCode(args)
            "run_file" -> doRunFile(args)
            "info" -> doInfo()
            "install" -> doInstallPackage(args)
            else -> ToolResult.fail("op 只能是 run_code / run_file / info / install")
        }
    }

    /**
     * 检测 Python 源码里是否包含删除/破坏性操作。
     *
     * 为什么 AI 可以通过 python 跑 os.remove / shutil.rmtree 绕过
     * delete_file 的回收站机制。这里做一道静态拦截。
     *
     * 返回 null 表示安全；非 null 是拒绝文案。
     */

    private val DESTRUCTIVE_PYTHON_PATTERNS = listOf(
        Regex("""shutil\.rmtree"""),
        Regex("""os\.remove"""),
        Regex("""os\.unlink"""),
        Regex("""os\.rmdir"""),
        Regex("""os\.removedirs"""),
        Regex("""pathlib.*\.unlink"""),
        Regex("""pathlib.*\.rmdir"""),
        Regex("""\.unlink\("""),
        Regex("""subprocess.*(rm|del|rmdir|rd)"""),
        Regex("""os\.system\s*\(\s*['"]\s*(rm|del|rmdir|rd)""")
    )

    private fun detectDestructivePython(code: String): String? {
        if (code.isBlank()) return null
        val hit = DESTRUCTIVE_PYTHON_PATTERNS.firstOrNull { it.containsMatchIn(code) } ?: return null
        return "已拦截：Python 代码包含删除操作（${hit.pattern}）。\n" +
                "AI 不得直接删除文件。如需删除，请改用 delete_file 工具，" +
                "文件会被移入回收站，由用户决定何时清空。"
    }

    private suspend fun doRunCode(args: JSONObject): ToolResult {
        val python = currentPython() ?: return ToolResult.fail(notInstalledMsg())
        val code = args.optString("code", "")
        if (code.isBlank()) return ToolResult.fail("未提供 code")
        val timeoutSec = args.optInt("timeout", 120).coerceIn(1, 1800)
        val workDirRaw = args.optString("work_dir", "").trim()
        val stdin = args.optString("stdin", "")
        // run_code 之前不支持 args，sys.argv 永远是空的。
        // 现在与 run_file 一致，支持传参（带引号解析）。
        val extraArgs = args.optString("args", "").trim()

        val settings = DesktopSettingsHolder.current
        if (settings.requireConfirmPython) {
            val preview = if (code.length > 800) code.take(800) + "\n...(已截断)" else code
            val argsLine = if (extraArgs.isNotBlank()) "\n参数：$extraArgs" else ""
            val approved = DesktopConfirmation.request(
                "执行 Python 代码确认",
                "AI 请求执行以下 Python 代码：\n\n$preview$argsLine\n\n超时：${timeoutSec} 秒" +
                        if (workDirRaw.isNotBlank()) "\n工作目录：$workDirRaw" else ""
            )
            if (!approved) return ToolResult.fail("用户已拒绝执行 Python 代码")
        }

        return withContext(Dispatchers.IO) {
            try {
                val tempDir = net.xinkong.furagent.desktop.data.DesktopPaths.tempDir()
                // 脚本名加自增序号，避免同一毫秒并发撞名。
                val scriptFile = File(
                    tempDir,
                    "run_${System.currentTimeMillis()}_${tempSeq.incrementAndGet()}.py"
                )
                scriptFile.writeText(code, Charsets.UTF_8)

                // 临时脚本必须 try-finally 删除。
                // 旧实现 runProcess 抛异常时 delete 不执行，脚本残留在 temp 目录。
                val result = try {
                    val workDir = resolveWorkDir(workDirRaw)
                    val cmd = mutableListOf(python, "-u", scriptFile.absolutePath)
                    if (extraArgs.isNotBlank()) {
                        cmd.addAll(parseArgsLine(extraArgs))
                    }
                    runProcess(cmd, workDir, timeoutSec, stdin)
                } finally {
                    try { scriptFile.delete() } catch (_: Exception) { }
                }

                if (result.exitCode == 0) {
                    ToolResult.ok(
                        buildString {
                            append("执行成功（耗时 ${result.durationMs / 1000}s）\n")
                            if (result.stdout.isNotBlank()) {
                                append("\n--- stdout ---\n")
                                append(result.stdout)
                            }
                            if (result.stderr.isNotBlank()) {
                                append("\n--- stderr ---\n")
                                append(result.stderr)
                            }
                            if (result.stdout.isBlank() && result.stderr.isBlank()) {
                                append("(无输出)")
                            }
                        }
                    )
                } else {
                    ToolResult.fail(
                        buildString {
                            append("Python 退出码 ${result.exitCode}\n")
                            if (result.stdout.isNotBlank()) {
                                append("\n--- stdout ---\n")
                                append(result.stdout)
                            }
                            if (result.stderr.isNotBlank()) {
                                append("\n--- stderr ---\n")
                                append(result.stderr)
                            }
                            if (result.timedOut) {
                                append("\n\n(执行超时，已强制终止)")
                            }
                        }
                    )
                }
            } catch (e: Exception) {
                DesktopLogger.error("Python", "run_code 异常：${e.javaClass.simpleName}: ${e.message}")
                ToolResult.fail("执行失败：${e.message}")
            }
        }
    }

    private suspend fun doRunFile(args: JSONObject): ToolResult {
        val python = currentPython() ?: return ToolResult.fail(notInstalledMsg())
        val pathStr = args.optString("path", "").trim()
        if (pathStr.isBlank()) return ToolResult.fail("未提供 path")
        val file = File(pathStr)
        if (!file.exists()) return ToolResult.fail("文件不存在：$pathStr")
        if (!file.isFile) return ToolResult.fail("不是文件：$pathStr")
        if (!file.extension.equals("py", true)) return ToolResult.fail("不是 .py 文件")

        val timeoutSec = args.optInt("timeout", 300).coerceIn(1, 3600)
        val extraArgs = args.optString("args", "").trim()
        val workDirRaw = args.optString("work_dir", "").trim()
        val stdin = args.optString("stdin", "")

        val settings = DesktopSettingsHolder.current
        if (settings.requireConfirmPython) {
            val extraArgsLine = if (extraArgs.isNotBlank()) "\n参数：$extraArgs" else ""
            val approved = DesktopConfirmation.request(
                "执行 Python 文件确认",
                "AI 请求执行：\n${file.absolutePath}" +
                        extraArgsLine +
                        "\n\n超时：${timeoutSec} 秒"
            )
            if (!approved) return ToolResult.fail("用户已拒绝执行 Python 文件")
        }

        return withContext(Dispatchers.IO) {
            try {
                val workDir = resolveWorkDir(
                    workDirRaw.ifBlank { file.parentFile?.absolutePath ?: "" }
                )
                val cmd = mutableListOf(python, "-u", file.absolutePath)
                if (extraArgs.isNotBlank()) {
                    // 用带引号处理的解析器，而不是简单 split(" ")。
                    // 旧实现会把 --name "my file.txt" 拆成 ["--name", "\"my", "file.txt\""]。
                    cmd.addAll(parseArgsLine(extraArgs))
                }

                val result = runProcess(cmd, workDir, timeoutSec, stdin)

                if (result.exitCode == 0) {
                    ToolResult.ok(
                        buildString {
                            append("执行成功（耗时 ${result.durationMs / 1000}s）\n")
                            if (result.stdout.isNotBlank()) {
                                append("\n--- stdout ---\n")
                                append(result.stdout)
                            }
                            if (result.stderr.isNotBlank()) {
                                append("\n--- stderr ---\n")
                                append(result.stderr)
                            }
                            if (result.stdout.isBlank() && result.stderr.isBlank()) append("(无输出)")
                        }
                    )
                } else {
                    ToolResult.fail(
                        buildString {
                            append("Python 退出码 ${result.exitCode}\n")
                            if (result.stdout.isNotBlank()) {
                                append("\n--- stdout ---\n")
                                append(result.stdout)
                            }
                            if (result.stderr.isNotBlank()) {
                                append("\n--- stderr ---\n")
                                append(result.stderr)
                            }
                            if (result.timedOut) append("\n\n(执行超时，已强制终止)")
                        }
                    )
                }
            } catch (e: Exception) {
                DesktopLogger.error("Python", "run_file 异常：${e.javaClass.simpleName}: ${e.message}")
                ToolResult.fail("执行失败：${e.message}")
            }
        }
    }

    private suspend fun doInfo(): ToolResult = withContext(Dispatchers.IO) {
        val python = currentPython() ?: return@withContext ToolResult.fail(notInstalledMsg())
        try {
            val version = runProcess(listOf(python, "--version"), null, 5, "")
            val path = runProcess(
                listOf(python, "-c", "import sys; print(sys.executable)"), null, 5, ""
            )
            val pipList = runProcess(
                listOf(python, "-m", "pip", "list", "--format=freeze"), null, 15, ""
            )

            val sb = StringBuilder()
            sb.append("Python：").append(version.stdout.trim().ifBlank { "未知" }).append("\n")
            sb.append("可执行文件：").append(path.stdout.trim().ifBlank { "未知" }).append("\n")
            sb.append("\n=== 已安装包 ===\n")
            val lines = pipList.stdout.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) sb.append("(无法列出，或 pip 未安装)\n")
            else lines.forEach { sb.append(it).append("\n") }

            ToolResult.ok(sb.toString().trim())
        } catch (e: Exception) {
            DesktopLogger.warn("Python", "info 查询失败：${e.message}")
            ToolResult.fail("查询失败：${e.message}")
        }
    }

    private suspend fun doInstallPackage(args: JSONObject): ToolResult {
        val python = currentPython() ?: return ToolResult.fail(notInstalledMsg())
        val pkg = args.optString("package", "").trim()
        if (pkg.isBlank()) return ToolResult.fail("未提供 package")
        val upgrade = args.optBoolean("upgrade", false)

        val settings = DesktopSettingsHolder.current
        if (settings.requireConfirmPython) {
            val approved = DesktopConfirmation.request(
                "安装 Python 包确认",
                "AI 请求通过 pip 安装：\n\n$pkg" +
                        if (upgrade) "\n\n（含 --upgrade）" else ""
            )
            if (!approved) return ToolResult.fail("用户已拒绝安装")
        }

        return withContext(Dispatchers.IO) {
            try {
                val cmd = mutableListOf(python, "-m", "pip", "install")
                if (upgrade) cmd.add("--upgrade")
                cmd.addAll(pkg.split(" ").filter { it.isNotBlank() })
                val result = runProcess(cmd, null, 300, "")
                if (result.exitCode == 0) {
                    ToolResult.ok(
                        "安装成功（耗时 ${result.durationMs / 1000}s）\n\n" +
                                result.stdout.ifBlank { "(无输出)" }
                    )
                } else {
                    ToolResult.fail(
                        "安装失败，退出码 ${result.exitCode}\n\n" +
                                (result.stderr.ifBlank { result.stdout })
                    )
                }
            } catch (e: Exception) {
                DesktopLogger.error("Python", "pip 安装异常：${e.javaClass.simpleName}: ${e.message}")
                ToolResult.fail("安装失败：${e.message}")
            }
        }
    }

    private data class ProcResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val durationMs: Long,
        val timedOut: Boolean
    )

    private suspend fun runProcess(
        cmd: List<String>,
        workDir: File?,
        timeoutSec: Int,
        stdin: String
    ): ProcResult {
        val taskId = TaskRuntime.currentId()
        val start = System.currentTimeMillis()
        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(false)
        if (workDir != null && workDir.isDirectory) pb.directory(workDir)

        val env = pb.environment()
        if (env["PYTHONIOENCODING"].isNullOrBlank()) env["PYTHONIOENCODING"] = "utf-8"
        if (env["PYTHONUTF8"].isNullOrBlank()) env["PYTHONUTF8"] = "1"

        val p = pb.start()

        if (stdin.isNotBlank()) {
            try {
                p.outputStream.bufferedWriter(Charsets.UTF_8).use { w ->
                    w.write(stdin)
                    if (!stdin.endsWith("\n")) w.write("\n")
                }
            } catch (e: Exception) {
                DesktopLogger.warn("Python", "写 stdin 失败：${e.message}")
            }
        } else {
            try { p.outputStream.close() } catch (_: Exception) { }
        }

        val stdoutSb = StringBuilder()
        val stderrSb = StringBuilder()

        val stdoutThread = Thread {
            try {
                p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    if (stdoutSb.length < 100_000_000) stdoutSb.append(line).append("\n")
                    if (taskId != null) {
                        TaskRuntime.appendOutput(taskId, line)
                    }
                }
            } catch (e: Exception) {
                DesktopLogger.warn("Python", "读 stdout 失败：${e.message}")
            }
        }
        val stderrThread = Thread {
            try {
                p.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    if (stderrSb.length < 100_000_000) stderrSb.append(line).append("\n")
                    if (taskId != null) {
                        TaskRuntime.appendOutput(taskId, "[err] $line")
                    }
                }
            } catch (e: Exception) {
                DesktopLogger.warn("Python", "读 stderr 失败：${e.message}")
            }
        }
        stdoutThread.start()
        stderrThread.start()

        val finished = p.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
        val elapsed = System.currentTimeMillis() - start

        if (!finished) {
            p.destroyForcibly()
            // 杀进程后等读线程收尾，避免读到不完整内容 / 竞态。
            try { stdoutThread.join(500) } catch (_: Exception) { }
            try { stderrThread.join(500) } catch (_: Exception) { }
            return ProcResult(-1, stdoutSb.toString().trim(), stderrSb.toString().trim(), elapsed, true)
        }

        try { stdoutThread.join(1000) } catch (_: Exception) { }
        try { stderrThread.join(1000) } catch (_: Exception) { }

        return ProcResult(
            exitCode = p.exitValue(),
            stdout = stdoutSb.toString().trim(),
            stderr = stderrSb.toString().trim(),
            durationMs = elapsed,
            timedOut = false
        )
    }

    /**
     * 解析命令行参数字符串，支持单/双引号包裹。
     * 与 FfmpegTools.parseCommandLine 同语义。
     */
    private fun parseArgsLine(cmd: String): List<String> =
        net.xinkong.furagent.shared.util.CommandLine.parse(cmd)

    private fun resolveWorkDir(raw: String): File? {
        if (raw.isBlank()) return null
        val t = raw.trim().trim('"', '\'', '`')
        if (t.startsWith("~")) {
            val home = System.getProperty("user.home") ?: return null
            return File(home + t.substring(1))
        }
        val f = File(t)
        return if (f.isAbsolute) f else File(System.getProperty("user.home") ?: ".", t)
    }
}
