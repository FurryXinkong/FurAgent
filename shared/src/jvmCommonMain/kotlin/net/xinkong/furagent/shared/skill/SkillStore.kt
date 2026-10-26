package net.xinkong.furagent.shared.skill

import net.xinkong.furagent.shared.tools.ToolLogger
import org.json.JSONObject
import java.io.File

/**
 * 自定义脚本（Skill）存储与管理（双端共用 2026-10-18）。
 *
 * 从桌面端 desktop.data.SkillStore 抽到 shared。所有"跑 Python"的动作
 * 委托给注入的 [SkillRuntime]，本类只负责脚本格式、元数据、配置、文件管理。
 *
 * 使用前必须先 [install] 注册运行时（两端 App 启动时各注册一次）。
 */
object SkillStore {

    /** 脚本文件扩展名。 */
    private const val EXT = ".py"

    /** 工具名前缀：每个脚本注册成 `pytool_<name>`。 */
    const val TOOL_PREFIX = "pytool_"

    /** 脚本名合法字符（用于工具名，必须安全）。 */
    private val NAME_RE = Regex("^[A-Za-z0-9_\\-]+$")

    @Volatile
    private var runtime: SkillRuntime? = null

    /** 注册脚本运行时（两端 App 启动时各调一次）。 */
    fun install(rt: SkillRuntime) {
        runtime = rt
    }

    /** 取当前运行时（同包内 SkillTools 也用）。未安装时抛错。 */
    internal fun rt(): SkillRuntime =
        runtime ?: error("SkillStore 未安装 SkillRuntime，请先在 App 启动时调用 SkillStore.install(...)")

    /** 脚本目录。 */
    fun scriptsDir(): File = rt().scriptsDir()

    /**
     * 临时执行一段 Python 代码（不落盘）。
     *
     * 转发给当前运行时；平台不支持时返回失败。
     */
    suspend fun execCode(
        code: String,
        args: org.json.JSONObject,
        workDir: String,
        timeout: Int
    ): net.xinkong.furagent.shared.tools.ToolResult = rt().execCode(code, args, workDir, timeout)

    // 列表 / 查找 / 解析

    /** 列出所有有效脚本。解析失败的会被跳过并记日志。
     *
     * 异常保护 运行时未安装 / Python 未就绪时返回空列表，不抛异常。
     * 工具列表构建、UI 列表都依赖这个方法，绝不能让它把上层拖崩。
     */
    fun list(): List<SkillMeta> {
        return try {
            val root = scriptsDir()
            val files = root.listFiles { f -> f.isFile && f.name.endsWith(EXT, ignoreCase = true) }
                ?: return emptyList()
            val out = mutableListOf<SkillMeta>()
            for (f in files) {
                val meta = loadFrom(f)
                if (meta != null) out.add(meta)
            }
            out.sortedBy { it.name }
        } catch (e: Throwable) {
            ToolLogger.log("Skill", "list 失败：${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }

    /** 按名称查找。 */
    fun find(name: String): SkillMeta? = list().firstOrNull { it.name == name }

    /** 列出所有加载失败的脚本文件。 */
    fun listBroken(): List<BrokenSkill> {
        return try {
            val root = scriptsDir()
            val files = root.listFiles { f -> f.isFile && f.name.endsWith(EXT, ignoreCase = true) }
                ?: return emptyList()
            val out = mutableListOf<BrokenSkill>()
            for (f in files) {
                val reason = diagnose(f) ?: continue
                out.add(BrokenSkill(f.name, reason, f))
            }
            out.sortedBy { it.fileName }
        } catch (e: Throwable) {
            ToolLogger.log("Skill", "listBroken 失败：${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }

    /** 检查一个脚本文件为何无法加载。能正常加载时返回 null。 */
    private fun diagnose(file: File): String? {
        val parsed = rt().parseMeta(file) ?: return "解析失败（Python 语法错误或无法读取）"
        if (parsed.optBoolean("ok", false).not()) {
            return parsed.optString("error", "解析失败")
        }
        return null
    }

    /** 从某个脚本文件读取元数据。 */
    fun loadFrom(file: File): SkillMeta? {
        val parsed = rt().parseMeta(file)
        if (parsed == null || !parsed.optBoolean("ok", false)) {
            ToolLogger.log(
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

        val configSpec = mutableMapOf<String, SkillConfigParam>()
        meta.optJSONObject("CONFIG")?.let { cObj ->
            cObj.keys().forEach { k ->
                val c = cObj.optJSONObject(k) ?: return@forEach
                val type = c.optString("type", "string")
                    .takeIf { it in listOf("string", "integer", "number", "boolean") }
                    ?: "string"
                configSpec[k] = SkillConfigParam(
                    type = type,
                    description = c.optString("description", ""),
                    secret = c.optBoolean("secret", false),
                    default = if (c.has("default")) c.opt("default") else null
                )
            }
        }

        return SkillMeta(
            name = name,
            description = description,
            timeout = timeout,
            params = params,
            configSpec = configSpec,
            file = file
        )
    }

    // 脚本配置（scripts/<name>.config.json）

    /** 某个脚本的配置文件。 */
    fun configFile(name: String): File = File(scriptsDir(), "$name.config.json")

    /** 读脚本配置。文件不存在 / 损坏时返回默认配置（enabled=true）。 */
    fun loadConfig(name: String): SkillConfig {
        val f = configFile(name)
        if (!f.exists()) return SkillConfig()
        return try {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            val values = mutableMapOf<String, Any?>()
            o.optJSONObject("values")?.let { v ->
                v.keys().forEach { k -> values[k] = v.opt(k) }
            }
            SkillConfig(
                enabled = o.optBoolean("enabled", true),
                timeout = if (o.has("timeout") && !o.isNull("timeout")) {
                    o.optInt("timeout", 0).takeIf { it > 0 }?.coerceIn(1, 3600)
                } else null,
                values = values
            )
        } catch (e: Exception) {
            ToolLogger.log("Skill", "读取配置失败 ${f.name}：${e.javaClass.simpleName}: ${e.message}")
            SkillConfig()
        }
    }

    /** 写脚本配置。返回 null 表示成功，否则是错误原因。 */
    fun saveConfig(name: String, config: SkillConfig): String? {
        return try {
            val o = JSONObject().apply {
                put("enabled", config.enabled)
                if (config.timeout != null) put("timeout", config.timeout.coerceIn(1, 3600))
                put("values", JSONObject().apply {
                    config.values.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
                })
            }
            val f = configFile(name)
            f.parentFile?.mkdirs()
            f.writeText(o.toString(2), Charsets.UTF_8)
            ToolLogger.log("Skill", "已保存脚本配置 $name：${f.absolutePath}")
            null
        } catch (e: Exception) {
            val msg = "保存配置失败：${e.javaClass.simpleName}: ${e.message}"
            ToolLogger.log("Skill", msg)
            msg
        }
    }

    /** 删除脚本配置（脚本被删时一起清掉）。 */
    fun deleteConfig(name: String) {
        try {
            val f = configFile(name)
            if (f.exists()) f.delete()
        } catch (e: Exception) {
            ToolLogger.log("Skill", "删除配置失败 $name：${e.message}")
        }
    }

    /** 脚本是否启用（读配置文件，缺省启用）。 */
    fun isEnabled(name: String): Boolean = loadConfig(name).enabled

    /** 实际生效的超时秒数（配置覆盖 > 脚本头部 TIMEOUT）。 */
    fun effectiveTimeout(meta: SkillMeta, config: SkillConfig): Int =
        config.timeout ?: meta.timeout

    /**
     * 把配置值转成注入脚本的 JSON 对象。
     * 未填的项用声明里的默认值补齐。
     */
    fun configValuesJson(meta: SkillMeta, config: SkillConfig): JSONObject {
        val o = JSONObject()
        meta.configSpec.forEach { (k, spec) ->
            val v = config.valueOf(k, meta.configSpec)
            if (v != null) o.put(k, v) else if (spec.default != null) o.put(k, spec.default)
        }
        // 配置里声明之外的多余项也带上（用户/AI 手动加的）
        config.values.forEach { (k, v) -> if (!o.has(k) && v != null) o.put(k, v) }
        return o
    }

    // 创建 / 删除

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
        code: String,
        config: JSONObject? = null
    ): String? {
        val n = name.trim()
        if (n.isBlank()) return "脚本名不能为空"
        if (!NAME_RE.matches(n)) return "脚本名只能包含字母、数字、下划线、连字符：$n"

        val root = scriptsDir()
        val file = File(root, "$n$EXT")
        if (file.exists()) return "脚本已存在：$n（如需修改请先删除，或换个名字）"

        val header = buildString {
            append("# -*- coding: utf-8 -*-\n")
            append("# FurAgent 自定义脚本。元数据由工具自动生成，可手动修改。\n")
            append("NAME = ").append(pyStr(n)).append("\n")
            append("DESCRIPTION = ").append(pyStr(description)).append("\n")
            append("PARAMS = ").append(pyLiteral(params ?: JSONObject())).append("\n")
            if (config != null && config.length() > 0) {
                append("CONFIG = ").append(pyLiteral(config)).append("\n")
            }
            append("TIMEOUT = ").append(timeout.coerceIn(1, 3600)).append("\n")
            append("\n")
        }

        return try {
            file.writeText(header + code.trimStart('\n', '\r') + "\n", Charsets.UTF_8)
            ToolLogger.log("Skill", "已创建脚本 $n：${file.absolutePath}")
            null
        } catch (e: Exception) {
            val msg = "创建脚本失败：${e.javaClass.simpleName}: ${e.message}"
            ToolLogger.log("Skill", msg)
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
        val err = deleteFile(meta.file, name)
        if (err == null) deleteConfig(name)
        return err
    }

    /** 删除一个加载失败的脚本文件。 */
    fun deleteBroken(fileName: String): String? {
        val b = listBroken().firstOrNull { it.fileName == fileName }
            ?: return "文件不存在或已可正常加载：$fileName"
        return deleteFile(b.file, fileName)
    }

    private fun deleteFile(target: File, label: String): String? {
        val root = scriptsDir().canonicalFile
        val t = target.canonicalFile
        if (t == root || !t.path.startsWith(root.path + File.separator)) {
            ToolLogger.log("Skill", "拒绝删除 ${t.absolutePath}：不在脚本目录内")
            return "拒绝删除：文件不在脚本目录内"
        }
        return try {
            if (!t.delete()) {
                val msg = "删除失败（可能被占用或无权限）：${t.absolutePath}"
                ToolLogger.log("Skill", msg)
                msg
            } else {
                ToolLogger.log("Skill", "已删除脚本 $label：${t.absolutePath}")
                null
            }
        } catch (e: Exception) {
            val msg = "删除 $label 失败：${e.javaClass.simpleName}: ${e.message}"
            ToolLogger.log("Skill", msg)
            msg
        }
    }

    /**
     * 手动创建一个示例脚本（用户主动触发，便于学习脚本格式）。
     *
     * 示例脚本同时是「编写规范说明书」：头部注释分八节讲清楚文件位置、元数据、
     * PARAMS/CONFIG 格式、运行时怎么拿参数和配置、怎么返回结果、必须遵守的约定、调试方法。
     */
    fun createExample(): String? {
        val root = scriptsDir()
        val file = File(root, "example_hello$EXT")
        if (file.exists()) return "示例脚本已存在：${file.absolutePath}"
        return try {
            file.writeText(EXAMPLE_SCRIPT, Charsets.UTF_8)
            ToolLogger.log("Skill", "已创建示例脚本：${file.absolutePath}")
            null
        } catch (e: Exception) {
            val msg = "创建示例脚本失败：${e.javaClass.simpleName}: ${e.message}"
            ToolLogger.log("Skill", msg)
            msg
        }
    }

    /** 确保脚本目录存在，并在为空时写入示例脚本。 */
    fun ensureExample() {
        try {
            val root = scriptsDir()
            val existing = root.listFiles { f -> f.isFile && f.name.endsWith(EXT, ignoreCase = true) }
            if (!existing.isNullOrEmpty()) return
            createExample()
        } catch (e: Exception) {
            ToolLogger.log("Skill", "创建示例脚本失败：${e.message}")
        }
    }

    // Python 字面量序列化

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

    /** 示例脚本全文（= 编写规范说明书）。 */
    private val EXAMPLE_SCRIPT = """
# -*- coding: utf-8 -*-
# ============================================================================
# FurAgent 自定义脚本 —— 完整示例 / 编写规范
#
# 这个文件同时是「能跑的脚本」和「怎么写脚本的说明书」。
# 写新脚本前先读一遍，照着改就行。
# ============================================================================
#
# 【一、文件放哪】
#   scripts/<脚本名>.py（桌面端 ~/.furagent/scripts/，安卓端 App 私有目录 scripts/）
#   一个 .py 一个脚本，文件名（去掉 .py）就是脚本名，也是工具名后缀。
#   脚本名只能用字母、数字、下划线、连字符。
#   例：example_hello.py → 工具名 pytool_example_hello
#
# 【二、头部元数据（模块级变量，必须写在文件最外层）】
#   NAME        脚本名（一般与文件名一致）
#   DESCRIPTION 给 AI 看的说明：这个脚本干什么、什么时候该用、有什么坑
#   PARAMS      调用参数定义（AI 调用时传的）
#   CONFIG      配置项定义（长期保存的值，如 API Key、默认路径）
#   TIMEOUT     超时秒数（1-3600，默认 60）
#
#   注意：这些变量必须用「字面量」写（字符串/数字/字典/列表），
#   不能用变量拼接或函数调用，否则解析器读不到。
#
# 【三、PARAMS 格式】
#   PARAMS = {
#       "参数名": {
#           "type": "string",        # string / integer / number / boolean
#           "description": "说明",    # 给 AI 看，写清楚格式和例子
#           "required": True,        # 必填
#       },
#   }
#
# 【四、CONFIG 格式（可选）】
#   CONFIG = {
#       "配置名": {
#           "type": "string",
#           "description": "说明",
#           "secret": True,          # 敏感值：UI 打码、AI 读配置时也打码
#           "default": "默认值",      # 可选，没填时用这个
#       },
#   }
#   配置存在 scripts/<脚本名>.config.json，
#   用户可以在「自定义脚本」页点「配置」按钮改，
#   AI 也可以用 pytool(op=config) 读写。
#
# 【五、运行时怎么拿到参数和配置】
#   FurAgent 启动脚本时：
#     - 往 stdin 写一行 JSON（参数 + 配置）
#     - 参数在顶层，配置在 "_config" 字段里
#     - 同时设置环境变量 FURAGENT_SKILL_CONFIG（配置的 JSON 字符串）
#
#   args = json.loads(sys.stdin.readline() or "{}")
#   who  = args.get("who", "世界")          # 普通参数
#   cfg  = args.get("_config", {})          # 配置项
#   key  = cfg.get("api_key", "")
#
# 【六、怎么返回结果】
#   往 stdout 打一行 JSON（最后一行会被解析）：
#     成功：{"ok": true, "result": "给 AI 看的文本"}
#     失败：{"ok": false, "error": "错误原因"}
#
#   result 可以是字符串，也可以是对象/数组（会被转成 JSON 文本）。
#   一定要用 ensure_ascii=False，否则中文会变成 \uXXXX。
#
# 【七、必须遵守的约定】
#   1. 不要用 input() 交互 —— 没有人在键盘前，会卡死到超时。
#   2. 不要往 stdout 打调试信息 —— 会污染 JSON 解析。
#      要调试就打到 stderr（sys.stderr.write），FurAgent 会收进日志。
#   3. 所有异常都要捕获，返回 {"ok": false, "error": ...}，
#      否则 AI 只能看到「退出码 1」这种没用的信息。
#   4. 长任务要自己控制时间，别超过 TIMEOUT。
#   5. 路径参数直接用用户给的字符串即可，FurAgent 不限制路径。
#   6. 需要第三方库时，优先用标准库；非标准库要在 DESCRIPTION 里写明依赖。
#      注意：安卓端（Chaquopy）只带标准库 + 少量预编译包，桌面专属的
#      系统命令 / 原生二进制在安卓上不可用，需要时用 sys.platform 分支。
#
# 【八、调试方法】
#   在「自定义脚本」页点「试运行」，填参数直接跑，看返回。
#   或者命令行：
#     echo {"who":"世界"} | python example_hello.py
# ============================================================================

NAME = "example_hello"
DESCRIPTION = (
    "示例脚本 / 编写规范。向指定对象打招呼，演示参数传递、配置读取、"
    "错误处理和结果返回的完整写法。写新脚本时可以直接复制这个文件改。"
)
PARAMS = {
    "who": {
        "type": "string",
        "description": "打招呼的对象，例如「世界」「小明」",
        "required": True,
    },
    "times": {
        "type": "integer",
        "description": "重复几次，默认 1，最大 10",
        "required": False,
    },
    "shout": {
        "type": "boolean",
        "description": "是否用感叹号加强语气，默认 false",
        "required": False,
    },
}
CONFIG = {
    "greeting": {
        "type": "string",
        "description": "问候语前缀，默认「你好」",
        "default": "你好",
    },
    "signature": {
        "type": "string",
        "description": "结尾署名，留空则不加",
        "default": "",
    },
}
TIMEOUT = 30

import json
import sys

def out(obj):
    # 统一的输出函数：一行 JSON 到 stdout。
    print(json.dumps(obj, ensure_ascii=False))

def main():
    # ---- 1. 读参数 ----
    raw = sys.stdin.readline()
    try:
        args = json.loads(raw) if raw.strip() else {}
    except Exception as e:
        out({"ok": False, "error": "参数解析失败：%s" % e})
        return

    # ---- 2. 读配置（FurAgent 注入的 _config）----
    cfg = args.get("_config") or {}

    # ---- 3. 取参数并做校验 ----
    who = (args.get("who") or "").strip()
    if not who:
        out({"ok": False, "error": "缺少必填参数 who"})
        return

    try:
        times = int(args.get("times") or 1)
    except (TypeError, ValueError):
        out({"ok": False, "error": "times 必须是整数"})
        return
    times = max(1, min(10, times))

    shout = bool(args.get("shout"))

    greeting = (cfg.get("greeting") or "你好").strip()
    signature = (cfg.get("signature") or "").strip()

    # ---- 4. 干活 ----
    lines = []
    for _ in range(times):
        line = "%s，%s" % (greeting, who)
        if shout:
            line += "！！！"
        else:
            line += "！"
        lines.append(line)

    if signature:
        lines.append("")
        lines.append("—— %s" % signature)

    # ---- 5. 返回结果 ----
    out({"ok": True, "result": "\n".join(lines)})

if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        # 兜底：任何未捕获异常都转成结构化错误，别让 AI 只看到退出码
        out({"ok": False, "error": "%s: %s" % (type(e).__name__, e)})
""".trimStart('\n')
}
