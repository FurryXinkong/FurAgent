package net.xinkong.furagent.desktop.data

import net.xinkong.furagent.desktop.core.TaskRuntime
import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.desktop.tools.PythonTools
import net.xinkong.furagent.shared.skill.SkillRuntime
import net.xinkong.furagent.shared.skill.SkillStore as SharedSkillStore
import net.xinkong.furagent.shared.tools.ToolResult
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 自定义脚本（Skill）—— 桌面端适配层。
 *
 * 协议层（脚本格式 / 元数据 / 配置 / 工具描述）已抽到
 * shared.skill.SkillStore，桌面端与安卓端共用。本文件只保留：
 *   1. 类型别名（SkillMeta / SkillConfig / ... 指向 shared，UI 代码零改动）
 *   2. 桌面端 SkillRuntime 实现（ProcessBuilder 调系统 python）
 *   3. 薄委托（把 shared 的 API 暴露成原来的名字）
 *
 * 脚本目录仍是 ~/.furagent/scripts/，与安卓端结构一致，脚本文件两端通用。
 */

// 类型别名：UI / 工具代码继续用 desktop.data.SkillXxx，实际是 shared 的类
typealias SkillMeta = net.xinkong.furagent.shared.skill.SkillMeta
typealias SkillParam = net.xinkong.furagent.shared.skill.SkillParam
typealias SkillConfigParam = net.xinkong.furagent.shared.skill.SkillConfigParam
typealias SkillConfig = net.xinkong.furagent.shared.skill.SkillConfig
typealias BrokenSkill = net.xinkong.furagent.shared.skill.BrokenSkill

/**
 * 桌面端脚本运行时：用 ProcessBuilder 调系统 python。
 *
 * 与安卓端（Chaquopy）遵守同一套 stdin/stdout JSON 协议，
 * 保证同一份 .py 脚本两端行为一致。
 */
object DesktopSkillRuntime : SkillRuntime {

    override fun scriptsDir(): File = DesktopPaths.scriptsDir()

    override fun parseMeta(file: File): JSONObject? {
        val python = PythonTools.getPythonPath() ?: run {
            DesktopLogger.warn("Skill", "Python 不可用，无法解析 ${file.name}")
            return null
        }
        return try {
            val parser = ensureParserScript() ?: return null
            val pb = ProcessBuilder(python, parser.absolutePath, file.absolutePath)
            pb.redirectErrorStream(false)
            val env = pb.environment()
            if (env["PYTHONIOENCODING"].isNullOrBlank()) env["PYTHONIOENCODING"] = "utf-8"
            if (env["PYTHONUTF8"].isNullOrBlank()) env["PYTHONUTF8"] = "1"
            val p = pb.start()
            val stdout = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val finished = p.waitFor(15, TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                DesktopLogger.warn("Skill", "解析 ${file.name} 超时")
                return null
            }
            val line = stdout.lines().lastOrNull { it.isNotBlank() } ?: return null
            JSONObject(line)
        } catch (e: Exception) {
            DesktopLogger.warn("Skill", "解析 ${file.name} 异常：${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    override suspend fun runScript(
        meta: SkillMeta,
        args: JSONObject,
        cfg: SkillConfig,
        timeout: Int
    ): ToolResult {
        val python = PythonTools.getPythonPath()
            ?: return ToolResult.fail(pythonUnavailableHint())

        val taskId = TaskRuntime.currentId()
        val start = System.currentTimeMillis()

        // 【配置注入】把脚本配置塞进 stdin 的 _config 字段 + 环境变量
        val cfgJson = SharedSkillStore.configValuesJson(meta, cfg)
        val stdinArgs = JSONObject(args.toString())
        if (cfgJson.length() > 0) stdinArgs.put("_config", cfgJson)

        return try {
            val pb = ProcessBuilder(python, "-u", meta.file.absolutePath)
            pb.directory(meta.file.parentFile)
            pb.redirectErrorStream(false)

            val env = pb.environment()
            if (env["PYTHONIOENCODING"].isNullOrBlank()) env["PYTHONIOENCODING"] = "utf-8"
            if (env["PYTHONUTF8"].isNullOrBlank()) env["PYTHONUTF8"] = "1"
            if (cfgJson.length() > 0) env["FURAGENT_SKILL_CONFIG"] = cfgJson.toString()

            val p = pb.start()

            // 写参数到 stdin
            try {
                p.outputStream.bufferedWriter(Charsets.UTF_8).use { w ->
                    w.write(stdinArgs.toString())
                    w.write("\n")
                }
            } catch (e: Exception) {
                DesktopLogger.warn("Skill", "写脚本 stdin 失败：${e.message}")
            }

            val stdoutSb = StringBuilder()
            val stderrSb = StringBuilder()

            val stdoutThread = Thread {
                try {
                    p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        if (stdoutSb.length < 10_000_000) stdoutSb.append(line).append("\n")
                        if (taskId != null) TaskRuntime.appendOutput(taskId, line)
                    }
                } catch (e: Exception) {
                    DesktopLogger.warn("Skill", "读脚本 stdout 失败：${e.message}")
                }
            }
            val stderrThread = Thread {
                try {
                    p.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        if (stderrSb.length < 10_000_000) stderrSb.append(line).append("\n")
                        if (taskId != null) TaskRuntime.appendOutput(taskId, "[err] $line")
                    }
                } catch (e: Exception) {
                    DesktopLogger.warn("Skill", "读脚本 stderr 失败：${e.message}")
                }
            }
            stdoutThread.start()
            stderrThread.start()

            val finished = p.waitFor(timeout.toLong(), TimeUnit.SECONDS)
            val elapsed = System.currentTimeMillis() - start

            if (!finished) {
                p.destroyForcibly()
                try { stdoutThread.join(500) } catch (_: Exception) { }
                try { stderrThread.join(500) } catch (_: Exception) { }
                return ToolResult.fail(
                    "脚本执行超时（$timeout 秒），已强制终止\n\n" +
                            "已收到的输出：\n" + stdoutSb.toString().take(2000)
                )
            }

            try { stdoutThread.join(1000) } catch (_: Exception) { }
            try { stderrThread.join(1000) } catch (_: Exception) { }

            val exitCode = p.exitValue()
            val stdout = stdoutSb.toString().trim()
            val stderr = stderrSb.toString().trim()

            // 解析脚本返回的 JSON
            val parsed = try {
                if (stdout.isBlank()) null else JSONObject(stdout.lines().last { it.isNotBlank() })
            } catch (_: Exception) { null }

            if (parsed != null) {
                val ok = parsed.optBoolean("ok", exitCode == 0)
                if (ok) {
                    val result = parsed.opt("result")
                    val text = when (result) {
                        null -> "(脚本无返回内容)"
                        is String -> result
                        else -> result.toString()
                    }
                    return ToolResult.ok("脚本 ${meta.name} 执行成功（耗时 ${elapsed / 1000}s）\n\n$text")
                } else {
                    val err = parsed.optString("error", "脚本返回 ok=false")
                    return ToolResult.fail("脚本 ${meta.name} 执行失败：$err")
                }
            }

            // 没有 JSON 输出，按退出码判断
            if (exitCode == 0) {
                ToolResult.ok(
                    "脚本 ${meta.name} 执行成功（耗时 ${elapsed / 1000}s）\n\n" +
                            stdout.ifBlank { "(无输出)" }
                )
            } else {
                ToolResult.fail(
                    "脚本 ${meta.name} 退出码 $exitCode\n\n" +
                            (stderr.ifBlank { stdout }).take(2000)
                )
            }
        } catch (e: Exception) {
            DesktopLogger.error("Skill", "运行脚本异常：${e.javaClass.simpleName}: ${e.message}")
            ToolResult.fail("运行脚本失败：${e.message}")
        }
    }

    override fun isPythonAvailable(): Boolean = PythonTools.getPythonPath() != null

    override fun pythonUnavailableHint(): String = "Python 3 未安装或不在 PATH 中，无法运行脚本"

    /**
     * 用 Python 做 AST 静态解析的辅助脚本。
     *
     * 只取模块级 Assign 节点（NAME / DESCRIPTION / PARAMS / TIMEOUT / CONFIG），
     * 用 ast.literal_eval 求值，绝不 exec 用户代码。输出 JSON 到 stdout。
     */
    private val PARSE_SCRIPT = """
import ast, json, sys

def main():
    path = sys.argv[1]
    try:
        with open(path, "r", encoding="utf-8") as f:
            src = f.read()
        tree = ast.parse(src)
    except Exception as e:
        print(json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)}, ensure_ascii=False))
        return

    meta = {}
    for node in tree.body:
        if not isinstance(node, ast.Assign):
            continue
        for t in node.targets:
            if not isinstance(t, ast.Name):
                continue
            if t.id not in ("NAME", "DESCRIPTION", "PARAMS", "TIMEOUT", "CONFIG"):
                continue
            try:
                meta[t.id] = ast.literal_eval(node.value)
            except Exception:
                pass

    print(json.dumps({"ok": True, "meta": meta}, ensure_ascii=False))

main()
""".trimIndent()

    /**
     * 把解析脚本写到临时文件（内容变化时重写），返回文件。
     *
     * 【为什么写临时文件而不是 python -c】Windows 下 ProcessBuilder 传多行 -c 代码会被破坏，
     * 导致代码不执行、无输出。写成临时文件最稳。
     */
    private fun ensureParserScript(): File? {
        return try {
            val dir = DesktopPaths.tempDir()
            val f = File(dir, "_skill_parser.py")
            if (!f.exists() || f.readText(Charsets.UTF_8) != PARSE_SCRIPT) {
                f.writeText(PARSE_SCRIPT, Charsets.UTF_8)
            }
            f
        } catch (e: Exception) {
            DesktopLogger.warn("Skill", "写解析脚本失败：${e.message}")
            null
        }
    }
}

/**
 * 桌面端脚本存储门面：委托 shared.skill.SkillStore。
 *
 * 保留原有 API 名字，UI / 工具代码零改动。
 */
object SkillStore {

    /** 工具名前缀：每个脚本注册成 `pytool_<name>`。 */
    const val TOOL_PREFIX = SharedSkillStore.TOOL_PREFIX

    /** 安装桌面端运行时（App 启动时调一次）。 */
    fun install() {
        SharedSkillStore.install(DesktopSkillRuntime)
    }

    fun scriptsDir(): File = SharedSkillStore.scriptsDir()
    fun list(): List<SkillMeta> = SharedSkillStore.list()
    fun find(name: String): SkillMeta? = SharedSkillStore.find(name)
    fun listBroken(): List<BrokenSkill> = SharedSkillStore.listBroken()
    fun loadFrom(file: File): SkillMeta? = SharedSkillStore.loadFrom(file)
    fun configFile(name: String): File = SharedSkillStore.configFile(name)
    fun loadConfig(name: String): SkillConfig = SharedSkillStore.loadConfig(name)
    fun saveConfig(name: String, config: SkillConfig): String? = SharedSkillStore.saveConfig(name, config)
    fun deleteConfig(name: String) = SharedSkillStore.deleteConfig(name)
    fun isEnabled(name: String): Boolean = SharedSkillStore.isEnabled(name)
    fun effectiveTimeout(meta: SkillMeta, config: SkillConfig): Int =
        SharedSkillStore.effectiveTimeout(meta, config)
    fun configValuesJson(meta: SkillMeta, config: SkillConfig): JSONObject =
        SharedSkillStore.configValuesJson(meta, config)
    fun create(
        name: String,
        description: String,
        params: JSONObject?,
        timeout: Int,
        code: String,
        config: JSONObject? = null
    ): String? = SharedSkillStore.create(name, description, params, timeout, code, config)
    fun delete(name: String): String? = SharedSkillStore.delete(name)
    fun deleteBroken(fileName: String): String? = SharedSkillStore.deleteBroken(fileName)
    fun createExample(): String? = SharedSkillStore.createExample()
    fun ensureExample() = SharedSkillStore.ensureExample()
}
