package net.xinkong.furagent.desktop.data

import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.desktop.tools.PythonTools
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 自定义脚本（Skill）元数据。
 *
 * 【新格式 2026-10-15】脚本直接放在 ~/.furagent/scripts/ 根目录，一个 .py 一个脚本。
 * 元数据写在 .py 文件头部的模块级变量里：
 *
 *   # -*- coding: utf-8 -*-
 *   NAME = "天气查询"
 *   DESCRIPTION = "查询指定城市天气"
 *   PARAMS = {
 *       "city": {"type": "string", "description": "城市名", "required": True},
 *   }
 *   TIMEOUT = 60
 *
 *   import json, sys
 *   def main():
 *       args = json.loads(sys.stdin.readline() or "{}")
 *       print(json.dumps({"ok": True, "result": "..."}, ensure_ascii=False))
 *   if __name__ == "__main__":
 *       main()
 *
 * 解析方式：用 Python 子进程做 AST 静态解析（不 exec 用户代码），返回 JSON。
 * 这样最可靠——不用在 Kotlin 里手写 Python 解析器。
 *
 * 脚本约定：
 *   - 从 stdin 读一行 JSON 参数
 *   - 结果以 JSON 写到 stdout：{"ok": true, "result": "..."}
 *   - 失败：{"ok": false, "error": "..."}
 */
data class SkillMeta(
    /** 脚本标识（= .py 文件名去掉扩展名，也是工具名后缀）。 */
    val name: String,
    /** 给 AI 看的说明。 */
    val description: String,
    /** 超时秒数。 */
    val timeout: Int,
    /** 参数定义。 */
    val params: Map<String, SkillParam>,
    /** 脚本文件（scripts/xxx.py）。 */
    val file: File
) {
    /** 生成给 AI 看的工具描述。 */
    fun toolDescription(): String {
        val sb = StringBuilder()
        sb.append(description.ifBlank { name })
        if (params.isNotEmpty()) {
            sb.append("\n参数：")
            params.forEach { (k, p) ->
                sb.append("\n  - ").append(k)
                sb.append("（").append(p.type)
                if (p.required) sb.append("，必填")
                sb.append("）")
                if (p.description.isNotBlank()) sb.append("：").append(p.description)
            }
        }
        return sb.toString()
    }

    /** 生成 JSON Schema（用于工具注册）。 */
    fun paramsSchema(): String {
        val props = JSONObject()
        val required = mutableListOf<String>()
        params.forEach { (k, p) ->
            props.put(k, JSONObject().apply {
                put("type", p.type)
                if (p.description.isNotBlank()) put("description", p.description)
            })
            if (p.required) required.add(k)
        }
        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) {
                put("required", org.json.JSONArray().apply { required.forEach { put(it) } })
            }
        }
        return schema.toString()
    }
}

data class SkillParam(
    val type: String,
    val description: String,
    val required: Boolean
)

/**
 * 一个无法加载的脚本文件（语法错误 / 解析失败）。
 */
data class BrokenSkill(
    val fileName: String,
    val reason: String,
    val file: java.io.File
)

object SkillStore {

    /** 脚本文件扩展名。 */
    private const val EXT = ".py"

    /** 工具名前缀：每个脚本注册成 `pytool_<name>`。 */
    const val TOOL_PREFIX = "pytool_"

    /** 脚本名合法字符（用于工具名，必须安全）。 */
    private val NAME_RE = Regex("^[A-Za-z0-9_\\-]+$")

    /**
     * 用 Python 做 AST 静态解析的辅助脚本。
     *
     * 只取模块级 Assign 节点（NAME / DESCRIPTION / PARAMS / TIMEOUT），
     * 用 ast.literal_eval 求值，绝不 exec 用户代码。
     * 输出 JSON 到 stdout。
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
            if t.id not in ("NAME", "DESCRIPTION", "PARAMS", "TIMEOUT"):
                continue
            try:
                meta[t.id] = ast.literal_eval(node.value)
            except Exception:
                pass

    print(json.dumps({"ok": True, "meta": meta}, ensure_ascii=False))

main()
""".trimIndent()

    /** 列出所有有效脚本。解析失败的会被跳过并记日志。 */
    fun list(): List<SkillMeta> {
        val root = DesktopPaths.scriptsDir()
        val files = root.listFiles { f -> f.isFile && f.name.endsWith(EXT, ignoreCase = true) }
            ?: return emptyList()
        val out = mutableListOf<SkillMeta>()
        for (f in files) {
            val meta = loadFrom(f)
            if (meta != null) out.add(meta)
        }
        return out.sortedBy { it.name }
    }

    /** 按名称查找。 */
    fun find(name: String): SkillMeta? = list().firstOrNull { it.name == name }

    /**
     * 列出所有加载失败的脚本文件。
     */
    fun listBroken(): List<BrokenSkill> {
        val root = DesktopPaths.scriptsDir()
        val files = root.listFiles { f -> f.isFile && f.name.endsWith(EXT, ignoreCase = true) }
            ?: return emptyList()
        val out = mutableListOf<BrokenSkill>()
        for (f in files) {
            val reason = diagnose(f) ?: continue
            out.add(BrokenSkill(f.name, reason, f))
        }
        return out.sortedBy { it.fileName }
    }

    /** 检查一个脚本文件为何无法加载。能正常加载时返回 null。 */
    private fun diagnose(file: File): String? {
        val parsed = parseMeta(file) ?: return "解析失败（Python 语法错误或无法读取）"
        if (parsed.optBoolean("ok", false).not()) {
            return parsed.optString("error", "解析失败")
        }
        return null
    }

    /** 从某个脚本文件读取元数据。 */
    fun loadFrom(file: File): SkillMeta? {
        val parsed = parseMeta(file)
        if (parsed == null || !parsed.optBoolean("ok", false)) {
            DesktopLogger.warn(
                "Skill",
                "跳过 ${file.name}：${parsed?.optString("error") ?: "解析失败"}"
            )
            return null
        }
        val meta = parsed.optJSONObject("meta") ?: JSONObject()
        val name = file.nameWithoutExtension
        val description = meta.optString("DESCRIPTION", "").trim()
        val timeout = meta.optInt("TIMEOUT", 60).coerceIn(1, 3600)

        val params = mutableMapOf<String, SkillParam>()
        meta.optJSONObject("PARAMS")?.let { pObj ->
            pObj.keys().forEach { k ->
                val p = pObj.optJSONObject(k) ?: return@forEach
                val type = p.optString("type", "string")
                    .takeIf { it in listOf("string", "integer", "number", "boolean") }
                    ?: "string"
                params[k] = SkillParam(
                    type = type,
                    description = p.optString("description", ""),
                    required = p.optBoolean("required", false)
                )
            }
        }

        return SkillMeta(
            name = name,
            description = description,
            timeout = timeout,
            params = params,
            file = file
        )
    }

    /**
     * 用 Python 子进程做 AST 解析，返回 {"ok":true,"meta":{...}} 或 {"ok":false,"error":"..."}。
     * 失败返回 null。
     *
     * 【为什么写临时文件而不是 python -c】Windows 下 ProcessBuilder 传多行 -c 代码会被破坏，
     * 导致代码不执行、无输出。写成临时文件最稳。
     */
    private fun parseMeta(file: File): JSONObject? {
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

    /**
     * 把解析脚本写到临时文件（内容变化时重写），返回文件。
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

    /**
     * 创建一个脚本文件。
     *
     * @param name 脚本名（= 文件名，不含 .py）。必须匹配 [A-Za-z0-9_-]+
     * @param description 给 AI 看的说明
     * @param params 参数定义（JSON 对象，可为空）
     * @param timeout 超时秒数
     * @param code 脚本主体代码（不含头部元数据变量，由本方法拼装）
     * @return 成功返回 null，失败返回原因
     */
    fun create(
        name: String,
        description: String,
        params: JSONObject?,
        timeout: Int,
        code: String
    ): String? {
        val n = name.trim()
        if (n.isBlank()) return "脚本名不能为空"
        if (!NAME_RE.matches(n)) return "脚本名只能包含字母、数字、下划线、连字符：$n"

        val root = DesktopPaths.scriptsDir()
        val file = File(root, "$n$EXT")
        if (file.exists()) return "脚本已存在：$n（如需修改请先删除，或换个名字）"

        val header = buildString {
            append("# -*- coding: utf-8 -*-\n")
            append("# FurAgent 自定义脚本。元数据由工具自动生成，可手动修改。\n")
            append("NAME = ").append(pyStr(n)).append("\n")
            append("DESCRIPTION = ").append(pyStr(description)).append("\n")
            append("PARAMS = ").append(pyLiteral(params ?: JSONObject())).append("\n")
            append("TIMEOUT = ").append(timeout.coerceIn(1, 3600)).append("\n")
            append("\n")
        }

        return try {
            file.writeText(header + code.trimStart('\n', '\r') + "\n", Charsets.UTF_8)
            DesktopLogger.info("Skill", "已创建脚本 $n：${file.absolutePath}")
            null
        } catch (e: Exception) {
            val msg = "创建脚本失败：${e.javaClass.simpleName}: ${e.message}"
            DesktopLogger.error("Skill", msg)
            msg
        }
    }

    /**
     * 删除一个脚本文件。
     *
     * @return 成功返回 null，失败返回原因
     */
    fun delete(name: String): String? {
        val meta = find(name) ?: return "脚本不存在：$name"
        return deleteFile(meta.file, name)
    }

    /** 删除一个加载失败的脚本文件。 */
    fun deleteBroken(fileName: String): String? {
        val b = listBroken().firstOrNull { it.fileName == fileName }
            ?: return "文件不存在或已可正常加载：$fileName"
        return deleteFile(b.file, fileName)
    }

    private fun deleteFile(target: File, label: String): String? {
        val root = DesktopPaths.scriptsDir().canonicalFile
        val t = target.canonicalFile
        if (t == root || !t.path.startsWith(root.path + File.separator)) {
            DesktopLogger.warn("Skill", "拒绝删除 ${t.absolutePath}：不在脚本目录内")
            return "拒绝删除：文件不在脚本目录内"
        }
        return try {
            if (!t.delete()) {
                val msg = "删除失败（可能被占用或无权限）：${t.absolutePath}"
                DesktopLogger.error("Skill", msg)
                msg
            } else {
                DesktopLogger.info("Skill", "已删除脚本 $label：${t.absolutePath}")
                null
            }
        } catch (e: Exception) {
            val msg = "删除 $label 失败：${e.javaClass.simpleName}: ${e.message}"
            DesktopLogger.error("Skill", msg)
            msg
        }
    }

    /**
     * 手动创建一个示例脚本（用户主动触发，便于学习脚本格式）。
     */
    fun createExample(): String? {
        val root = DesktopPaths.scriptsDir()
        val file = File(root, "example_hello$EXT")
        if (file.exists()) return "示例脚本已存在：${file.absolutePath}"
        return create(
            name = "example_hello",
            description = "示例脚本：向指定对象打招呼。演示参数传递与结果返回约定。",
            params = JSONObject().apply {
                put("who", JSONObject().apply {
                    put("type", "string")
                    put("description", "打招呼的对象，例如「世界」")
                    put("required", true)
                })
            },
            timeout = 30,
            code = """
import sys
import json


def main():
    raw = sys.stdin.read().strip()
    args = json.loads(raw) if raw else {}
    who = args.get("who", "世界")
    print(json.dumps({"ok": True, "result": "你好，%s！" % who}, ensure_ascii=False))


if __name__ == "__main__":
    main()
""".trimIndent()
        )
    }

    /** 确保脚本目录存在，并在为空时写入示例脚本。 */
    fun ensureExample() {
        try {
            val root = DesktopPaths.scriptsDir()
            val existing = root.listFiles { f -> f.isFile && f.name.endsWith(EXT, ignoreCase = true) }
            if (!existing.isNullOrEmpty()) return
            createExample()
        } catch (e: Exception) {
            DesktopLogger.warn("Skill", "创建示例脚本失败：${e.message}")
        }
    }

    // ============================================================
    // Python 字面量序列化
    // ============================================================

    /** 把字符串转成 Python 字符串字面量（用 JSON 转义，兼容）。 */
    private fun pyStr(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    /** 把 JSON 值转成 Python 字面量（True/False/None 与 Python 一致）。 */
    private fun pyLiteral(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "None"
        is Boolean -> if (v) "True" else "False"
        is Int, is Long, is Double, is Float -> v.toString()
        is String -> pyStr(v)
        is JSONObject -> {
            val parts = mutableListOf<String>()
            val it = v.keys()
            while (it.hasNext()) {
                val k = it.next()
                parts.add("${pyStr(k)}: ${pyLiteral(v.opt(k))}")
            }
            "{${parts.joinToString(", ")}}"
        }
        is org.json.JSONArray -> {
            val parts = (0 until v.length()).map { i -> pyLiteral(v.opt(i)) }
            "[${parts.joinToString(", ")}]"
        }
        else -> pyStr(v.toString())
    }
}
