package net.xinkong.furagent.desktop.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.desktop.core.DesktopConfirmation
import net.xinkong.furagent.desktop.core.TaskRuntime
import net.xinkong.furagent.desktop.data.DesktopPaths
import net.xinkong.furagent.desktop.data.DesktopSettingsHolder
import net.xinkong.furagent.desktop.data.SkillMeta
import net.xinkong.furagent.desktop.data.SkillStore
import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.shared.tools.ToolResult
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 自定义脚本工具。
 *
 * 【设计 2026-10-15】
 *  - `pytool` 常驻：op=list / info / run / create / delete，负责管理脚本。
 *  - 每个脚本额外动态注册成一个独立工具 `pytool_<脚本名>`，AI 一眼看到有哪些脚本、
 *    参数是什么，可直接调用，不用先 list 再 run。
 *
 * 脚本格式见 SkillStore 的注释。
 */
object SkillTools {

    data class ToolDef(
        val name: String,
        val displayName: String,
        val description: String,
        val paramsSchema: String
    )

    /** 常驻管理工具。 */
    val TOOLS: List<ToolDef> = listOf(
        ToolDef(
            name = "pytool",
            displayName = "自定义脚本",
            description = "管理用户的自定义 Python 脚本（放在 ~/.furagent/scripts/ 下，一个 .py 一个脚本）。" +
                    "op=list 列出所有脚本；op=info 查看详情（name）；op=run 执行（name + args）；" +
                    "op=create 创建新脚本（name + description + params + code）；op=delete 删除（name）。" +
                    "\n\n【重要】每个脚本也会单独注册成一个工具（名字形如 pytool_<脚本名>），" +
                    "你可以直接调用那个工具，不用先 list 再 run。" +
                    "\n\n【创建脚本】用户要求「做个脚本 / 加个功能 / 以后都用这个流程」时用 op=create。" +
                    "你只需提供：name（英文标识，字母数字下划线连字符）、description（给 AI 看的说明）、" +
                    "params（参数定义对象，可为空）、code（脚本主体代码）。" +
                    "工具会自动在文件头部生成 NAME/DESCRIPTION/PARAMS/TIMEOUT 元数据变量，你不用自己写。" +
                    "\n脚本约定：从 stdin 读一行 JSON 参数；结果以 JSON 写到 stdout：" +
                    "{\"ok\":true,\"result\":\"...\"}，失败 {\"ok\":false,\"error\":\"...\"}；" +
                    "用 print(json.dumps(..., ensure_ascii=False)) 保证中文不乱码；不要用 input() 交互。" +
                    "\nparams 格式：{\"参数名\":{\"type\":\"string\",\"description\":\"说明\",\"required\":true}}，" +
                    "type 只能是 string/integer/number/boolean。" +
                    "\n创建后建议用 pytool(op=run) 实测一次，再告知用户用法。",
            paramsSchema = """{"type":"object","properties":{"op":{"type":"string","enum":["list","info","run","create","delete"]},"name":{"type":"string"},"args":{"type":"object"},"description":{"type":"string"},"params":{"type":"object"},"code":{"type":"string"},"timeout":{"type":"integer"}},"required":["op"]}"""
        )
    )

    val NAMES: Set<String> = TOOLS.map { it.name }.toSet()

    fun displayName(name: String): String =
        TOOLS.firstOrNull { it.name == name }?.displayName ?: name

    /**
     * 动态生成每个脚本对应的独立工具。
     *
     * 工具名 = `pytool_<脚本名>`，参数 schema 直接来自脚本的 PARAMS。
     */
    fun dynamicTools(): List<ToolDef> {
        return SkillStore.list().map { meta ->
            ToolDef(
                name = SkillStore.TOOL_PREFIX + meta.name,
                displayName = "脚本：${meta.name}",
                description = "[自定义脚本] " + meta.toolDescription(),
                paramsSchema = meta.paramsSchema()
            )
        }
    }

    /** 判断某个工具名是否是动态脚本工具。 */
    fun isDynamicTool(name: String): Boolean = name.startsWith(SkillStore.TOOL_PREFIX)

    suspend fun execute(name: String, args: JSONObject): ToolResult? {
        // 动态脚本工具：pytool_<脚本名>
        if (isDynamicTool(name)) {
            val scriptName = name.removePrefix(SkillStore.TOOL_PREFIX)
            return doRunByName(scriptName, args)
        }
        if (name != "pytool") return null
        return when (args.optString("op", "").lowercase()) {
            "list" -> doList()
            "info" -> doInfo(args)
            "run" -> doRun(args)
            "create" -> doCreate(args)
            "delete" -> doDelete(args)
            else -> ToolResult.fail("op 只能是 list / info / run / create / delete")
        }
    }

    private fun doList(): ToolResult {
        val skills = SkillStore.list()
        if (skills.isEmpty()) {
            return ToolResult.ok(
                "暂无自定义脚本。\n\n" +
                        "脚本目录：${DesktopPaths.scriptsDir().absolutePath}/\n" +
                        "一个 .py 一个脚本，元数据写在文件头部（NAME / DESCRIPTION / PARAMS / TIMEOUT）。\n" +
                        "可用 pytool(op=create) 创建，或让用户手动放进去。"
            )
        }
        val sb = StringBuilder("共 ${skills.size} 个自定义脚本：\n")
        skills.forEach { s ->
            sb.append("\n- ").append(s.name)
            sb.append("（工具名 ").append(SkillStore.TOOL_PREFIX).append(s.name).append("）")
            if (s.description.isNotBlank()) sb.append("：").append(s.description)
            if (s.params.isNotEmpty()) {
                sb.append("\n  参数：").append(s.params.keys.joinToString(", "))
            }
        }
        return ToolResult.ok(sb.toString())
    }

    private fun doInfo(args: JSONObject): ToolResult {
        val name = args.optString("name", "").trim()
        if (name.isBlank()) return ToolResult.fail("未提供 name")
        val meta = SkillStore.find(name)
            ?: return ToolResult.fail("脚本不存在：$name（可用 pytool(op=list) 查看）")

        val sb = StringBuilder()
        sb.append("名称：").append(meta.name).append("\n")
        sb.append("说明：").append(meta.description.ifBlank { "(无)" }).append("\n")
        sb.append("文件：").append(meta.file.absolutePath).append("\n")
        sb.append("工具名：").append(SkillStore.TOOL_PREFIX).append(meta.name).append("\n")
        sb.append("超时：").append(meta.timeout).append(" 秒\n")
        if (meta.params.isEmpty()) {
            sb.append("参数：无\n")
        } else {
            sb.append("参数：\n")
            meta.params.forEach { (k, p) ->
                sb.append("  - ").append(k)
                sb.append("（").append(p.type)
                if (p.required) sb.append("，必填")
                sb.append("）")
                if (p.description.isNotBlank()) sb.append("：").append(p.description)
                sb.append("\n")
            }
        }
        return ToolResult.ok(sb.toString().trim())
    }

    private fun doCreate(args: JSONObject): ToolResult {
        val name = args.optString("name", "").trim()
        if (name.isBlank()) return ToolResult.fail("未提供 name")
        val description = args.optString("description", "").trim()
        val code = args.optString("code", "")
        if (code.isBlank()) return ToolResult.fail("未提供 code（脚本主体代码）")
        val params = args.optJSONObject("params")
        val timeout = args.optInt("timeout", 60)

        val err = SkillStore.create(name, description, params, timeout, code)
        return if (err == null) {
            ToolResult.ok(
                "已创建脚本 $name。\n" +
                        "文件：${DesktopPaths.scriptsDir().absolutePath}/$name.py\n" +
                        "工具名：${SkillStore.TOOL_PREFIX}$name\n\n" +
                        "建议用 pytool(op=run, name=\"$name\", args={...}) 实测一次。"
            )
        } else {
            ToolResult.fail(err)
        }
    }

    private fun doDelete(args: JSONObject): ToolResult {
        val name = args.optString("name", "").trim()
        if (name.isBlank()) return ToolResult.fail("未提供 name")
        val err = SkillStore.delete(name)
        return if (err == null) ToolResult.ok("已删除脚本 $name")
        else ToolResult.fail(err)
    }

    private suspend fun doRun(args: JSONObject): ToolResult {
        val name = args.optString("name", "").trim()
        if (name.isBlank()) return ToolResult.fail("未提供 name")
        val scriptArgs = args.optJSONObject("args") ?: JSONObject()
        return doRunByName(name, scriptArgs)
    }

    /** 执行脚本。args 直接就是脚本参数对象（动态工具调用时）。 */
    private suspend fun doRunByName(name: String, scriptArgs: JSONObject): ToolResult {
        val meta = SkillStore.find(name)
            ?: return ToolResult.fail("脚本不存在：$name（可用 pytool(op=list) 查看）")

        // 必填参数校验
        val missing = meta.params.filter { it.value.required && !scriptArgs.has(it.key) }.keys
        if (missing.isNotEmpty()) {
            return ToolResult.fail("缺少必填参数：${missing.joinToString(", ")}")
        }

        val python = PythonTools.getPythonPath()
            ?: return ToolResult.fail("Python 3 未安装或不在 PATH 中，无法运行脚本")

        // 确认弹窗受 requireConfirmSkill 控制
        val settings = DesktopSettingsHolder.current
        if (settings.requireConfirmSkill) {
            val argsPreview = if (scriptArgs.length() == 0) "(无)" else scriptArgs.toString(2)
            val approved = DesktopConfirmation.request(
                "运行自定义脚本确认",
                "AI 请求运行脚本：\n${meta.name}\n\n" +
                        "文件：${meta.file.absolutePath}\n" +
                        "参数：\n$argsPreview\n\n" +
                        "超时：${meta.timeout} 秒\n\n是否允许？"
            )
            if (!approved) return ToolResult.fail("用户已拒绝运行脚本")
        }

        return withContext(Dispatchers.IO) {
            runScript(python, meta, scriptArgs)
        }
    }

    private suspend fun runScript(python: String, meta: SkillMeta, scriptArgs: JSONObject): ToolResult {
        val taskId = TaskRuntime.currentId()
        val start = System.currentTimeMillis()

        return try {
            val pb = ProcessBuilder(python, "-u", meta.file.absolutePath)
            pb.directory(meta.file.parentFile)
            pb.redirectErrorStream(false)

            val env = pb.environment()
            if (env["PYTHONIOENCODING"].isNullOrBlank()) env["PYTHONIOENCODING"] = "utf-8"
            if (env["PYTHONUTF8"].isNullOrBlank()) env["PYTHONUTF8"] = "1"

            val p = pb.start()

            // 写参数到 stdin
            try {
                p.outputStream.bufferedWriter(Charsets.UTF_8).use { w ->
                    w.write(scriptArgs.toString())
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

            val finished = p.waitFor(meta.timeout.toLong(), TimeUnit.SECONDS)
            val elapsed = System.currentTimeMillis() - start

            if (!finished) {
                p.destroyForcibly()
                try { stdoutThread.join(500) } catch (_: Exception) { }
                try { stderrThread.join(500) } catch (_: Exception) { }
                return ToolResult.fail(
                    "脚本执行超时（${meta.timeout} 秒），已强制终止\n\n" +
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
}
