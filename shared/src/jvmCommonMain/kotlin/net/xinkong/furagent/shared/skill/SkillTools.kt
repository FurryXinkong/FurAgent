package net.xinkong.furagent.shared.skill

import net.xinkong.furagent.shared.tools.ToolResult
import org.json.JSONObject

/**
 * 自定义脚本工具（双端共用 2026-10-18）。
 *
 * 从桌面端 desktop.tools.SkillTools 抽到 shared。
 *
 * 设计
 *  - `pytool` 常驻：op=list / info / run / create / delete / config，负责管理脚本。
 *  - 每个脚本额外动态注册成一个独立工具 `pytool_<脚本名>`，AI 一眼看到有哪些脚本、
 *    参数是什么，可直接调用，不用先 list 再 run。
 *
 * 配置 每个脚本有自己的配置文件 scripts/<name>.config.json，
 * 存 enabled / timeout / values。op=config 读写它，运行时自动注入脚本。
 *
 * 确认弹窗 由端侧负责：本类只做协议层，不弹窗。端侧在调用 [execute] 前
 * 自行决定是否弹确认（桌面端 requireConfirmSkill，安卓端 ConfirmationBroker）。
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
            description = "管理用户的自定义 Python 脚本（放在 scripts/ 下，一个 .py 一个脚本）。" +
                    "op=list 列出所有脚本；op=info 查看详情（name）；op=run 执行（name + args）；" +
                    "op=create 创建新脚本（name + description + params + code）；op=delete 删除（name）；" +
                    "op=config 读写脚本配置（name + config / values / enabled / timeout）。" +
                    "\n\n【环境限制】手机端内置了 numpy, pillow, beautifulsoup4, pyyaml（不支持 requests，网络请用标准库 urllib.request 或内置工具），不支持在运行时 pip install 其他库。桌面端环境取决于系统 Python，可随意安装。" +
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
                    "\n\n【脚本配置】脚本需要 API Key / 默认值这类长期参数时，用 CONFIG 声明配置项" +
                    "（格式同 params，额外支持 secret:true 打码、default 默认值），" +
                    "再用 op=config 写入实际值。运行时配置会以 args[\"_config\"] 和环境变量 " +
                    "FURAGENT_SKILL_CONFIG 注入脚本，脚本里读 args.get(\"_config\", {}) 即可。" +
                    "\n创建后建议用 pytool(op=run) 实测一次，再告知用户用法。",
            paramsSchema = """{"type":"object","properties":{"op":{"type":"string","enum":["list","info","run","create","delete","config"]},"name":{"type":"string"},"args":{"type":"object"},"description":{"type":"string"},"params":{"type":"object"},"config":{"type":"object"},"values":{"type":"object"},"enabled":{"type":"boolean"},"code":{"type":"string"},"timeout":{"type":"integer"}},"required":["op"]}"""
        )
    )

    val NAMES: Set<String> = TOOLS.map { it.name }.toSet()

    fun displayName(name: String): String =
        TOOLS.firstOrNull { it.name == name }?.displayName ?: name

    /**
     * 动态生成每个脚本对应的独立工具。
     *
     * 工具名 = `pytool_<脚本名>`，参数 schema 直接来自脚本的 PARAMS。
     *
     * 异常保护 list() 会调 Python 解析脚本元数据。若 Python 运行时未就绪
     * （首次启动 Chaquopy 还在解压、或初始化失败），这里必须返回空列表，
     * 绝不能让异常冒到工具列表构建处——否则整个 tools 数组构建失败，
     * Agent 拿不到任何工具，表现为“工具全没了 / 回复异常”。
     */
    fun dynamicTools(): List<ToolDef> {
        return try {
            SkillStore.list()
                .filter { SkillStore.isEnabled(it.name) }
                .map { meta ->
                    ToolDef(
                        name = SkillStore.TOOL_PREFIX + meta.name,
                        displayName = "脚本：${meta.name}",
                        description = "[自定义脚本] " + meta.toolDescription(),
                        paramsSchema = meta.paramsSchema()
                    )
                }
        } catch (e: Exception) {
            net.xinkong.furagent.shared.tools.ToolLogger.log(
                "Skill",
                "dynamicTools 失败（Python 未就绪？）：${e.javaClass.simpleName}: ${e.message}"
            )
            emptyList()
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
            "config" -> doConfig(args)
            else -> ToolResult.fail("op 只能是 list / info / run / create / delete / config")
        }
    }

    /**
     * op=config：读 / 写脚本配置。
     *
     * 只传 name → 读配置（敏感项打码）。
     * 传 values / enabled / timeout → 合并写入。
     * 传 config（整个对象）→ 覆盖写入。
     */
    private fun doConfig(args: JSONObject): ToolResult {
        val name = args.optString("name", "").trim()
        if (name.isBlank()) return ToolResult.fail("未提供 name")
        val meta = SkillStore.find(name)
            ?: return ToolResult.fail("脚本不存在：$name（可用 pytool(op=list) 查看）")

        val hasWrite = args.has("config") || args.has("values") ||
                args.has("enabled") || args.has("timeout")
        if (!hasWrite) {
            return ToolResult.ok(renderConfig(meta, SkillStore.loadConfig(name)))
        }

        val cur = SkillStore.loadConfig(name)
        val newCfg = if (args.has("config")) {
            val c = args.optJSONObject("config") ?: JSONObject()
            val values = mutableMapOf<String, Any?>()
            c.optJSONObject("values")?.let { v ->
                v.keys().forEach { k -> values[k] = v.opt(k) }
            }
            SkillConfig(
                enabled = c.optBoolean("enabled", cur.enabled),
                timeout = if (c.has("timeout") && !c.isNull("timeout")) {
                    c.optInt("timeout", 0).takeIf { it > 0 }?.coerceIn(1, 3600)
                } else cur.timeout,
                values = if (values.isEmpty()) cur.values else values
            )
        } else {
            val values = cur.values.toMutableMap()
            args.optJSONObject("values")?.let { v ->
                v.keys().forEach { k -> values[k] = v.opt(k) }
            }
            SkillConfig(
                enabled = if (args.has("enabled")) args.optBoolean("enabled", cur.enabled) else cur.enabled,
                timeout = if (args.has("timeout")) {
                    args.optInt("timeout", 0).takeIf { it > 0 }?.coerceIn(1, 3600)
                } else cur.timeout,
                values = values
            )
        }

        val err = SkillStore.saveConfig(name, newCfg)
        return if (err == null) {
            ToolResult.ok("已更新脚本 $name 的配置。\n\n" + renderConfig(meta, newCfg))
        } else {
            ToolResult.fail(err)
        }
    }

    /** 渲染配置给 AI 看（敏感项打码）。 */
    private fun renderConfig(meta: SkillMeta, cfg: SkillConfig): String {
        val sb = StringBuilder()
        sb.append("脚本：").append(meta.name).append("\n")
        sb.append("启用：").append(if (cfg.enabled) "是" else "否").append("\n")
        sb.append("超时：")
            .append(cfg.timeout?.let { "$it 秒（配置覆盖）" } ?: "${meta.timeout} 秒（脚本默认）")
            .append("\n")
        sb.append("配置文件：").append(SkillStore.configFile(meta.name).absolutePath).append("\n")
        if (meta.configSpec.isEmpty() && cfg.values.isEmpty()) {
            sb.append("配置项：无（脚本头部没有 CONFIG 声明）")
            return sb.toString()
        }
        sb.append("配置项：\n")
        val keys = LinkedHashSet<String>().apply {
            addAll(meta.configSpec.keys)
            addAll(cfg.values.keys)
        }
        keys.forEach { k ->
            val spec = meta.configSpec[k]
            val v = cfg.valueOf(k, meta.configSpec)
            sb.append("  - ").append(k)
            if (spec != null) {
                sb.append("（").append(spec.type)
                if (spec.secret) sb.append("，敏感")
                sb.append("）")
                if (spec.description.isNotBlank()) sb.append("：").append(spec.description)
            }
            sb.append("\n    当前值：")
            sb.append(
                when {
                    v == null -> "(未设置)"
                    spec?.secret == true -> maskSecret(v.toString())
                    else -> v.toString()
                }
            )
            sb.append("\n")
        }
        return sb.toString().trim()
    }

    /** 敏感值打码：只留前 4 后 4。 */
    private fun maskSecret(s: String): String {
        if (s.length <= 8) return "****"
        return s.take(4) + "****" + s.takeLast(4)
    }

    private fun doList(): ToolResult {
        val skills = SkillStore.list()
        val broken = SkillStore.listBroken()

        if (skills.isEmpty()) {
            val sb = StringBuilder("暂无可用自定义脚本。\n\n")
            sb.append("脚本目录：${SkillStore.scriptsDir().absolutePath}/\n")
            // 如果目录里有 .py 但解析失败，必须告诉 AI，
            // 否则表现为“create 成功但 list 为空”，极难排查。
            if (broken.isNotEmpty()) {
                sb.append("\n【注意】目录里有 ${broken.size} 个 .py 文件但解析失败：\n")
                broken.forEach { b ->
                    sb.append("  - ${b.fileName}：${b.reason}\n")
                }
                sb.append("\n这通常是 Python 运行时未就绪或脚本语法错误。请把上面的错误告知用户。")
            } else {
                sb.append("一个 .py 一个脚本，元数据写在文件头部（NAME / DESCRIPTION / PARAMS / TIMEOUT）。\n")
                sb.append("可用 pytool(op=create) 创建，或让用户手动放进去。")
            }
            return ToolResult.ok(sb.toString().trim())
        }
        val sb = StringBuilder("共 ${skills.size} 个自定义脚本：\n")
        skills.forEach { s ->
            val cfg = SkillStore.loadConfig(s.name)
            sb.append("\n- ").append(s.name)
            sb.append("（工具名 ").append(SkillStore.TOOL_PREFIX).append(s.name).append("）")
            if (!cfg.enabled) sb.append(" [已禁用]")
            if (s.description.isNotBlank()) sb.append("：").append(s.description)
            if (s.params.isNotEmpty()) {
                sb.append("\n  参数：").append(s.params.keys.joinToString(", "))
            }
            if (s.configSpec.isNotEmpty()) {
                sb.append("\n  配置项：").append(s.configSpec.keys.joinToString(", "))
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
        val cfg = SkillStore.loadConfig(name)
        sb.append("启用：").append(if (cfg.enabled) "是" else "否（AI 看不到这个工具）").append("\n")
        sb.append("超时：").append(SkillStore.effectiveTimeout(meta, cfg)).append(" 秒\n")
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
        if (meta.configSpec.isNotEmpty()) {
            sb.append("配置项（用 op=config 读写）：\n")
            meta.configSpec.forEach { (k, c) ->
                sb.append("  - ").append(k).append("（").append(c.type)
                if (c.secret) sb.append("，敏感")
                sb.append("）")
                if (c.description.isNotBlank()) sb.append("：").append(c.description)
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
        val config = args.optJSONObject("config")
        val timeout = args.optInt("timeout", 60)

        val err = SkillStore.create(name, description, params, timeout, code, config)
        return if (err == null) {
            ToolResult.ok(
                "已创建脚本 $name。\n" +
                        "文件：${SkillStore.scriptsDir().absolutePath}/$name.py\n" +
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
    suspend fun doRunByName(name: String, scriptArgs: JSONObject): ToolResult {
        val meta = SkillStore.find(name)
            ?: return ToolResult.fail("脚本不存在：$name（可用 pytool(op=list) 查看）")

        // 必填参数校验
        val missing = meta.params.filter { it.value.required && !scriptArgs.has(it.key) }.keys
        if (missing.isNotEmpty()) {
            return ToolResult.fail("缺少必填参数：${missing.joinToString(", ")}")
        }

        val cfg = SkillStore.loadConfig(name)
        val timeout = SkillStore.effectiveTimeout(meta, cfg)

        return SkillStore.rt().runScript(meta, scriptArgs, cfg, timeout)
    }
}
