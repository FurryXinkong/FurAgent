package net.xinkong.furagent.shared.skill

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 自定义脚本（Skill）元数据。
 *
 * 【双端共用 2026-10-18】从桌面端 desktop.data.SkillStore 抽到 shared，
 * 桌面端（ProcessBuilder 跑 python）与安卓端（Chaquopy 内嵌 python）共用同一套
 * 脚本格式、元数据解析、配置管理。脚本文件本身两端完全通用。
 *
 * 【脚本格式】脚本直接放在 scripts/ 根目录，一个 .py 一个脚本。
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
 * 解析方式：用 Python 子进程 / 内嵌解释器做 AST 静态解析（不 exec 用户代码），
 * 返回 JSON。这样最可靠——不用在 Kotlin 里手写 Python 解析器。
 *
 * 脚本约定：
 *   - 从 stdin 读一行 JSON 参数
 *   - 结果以 JSON 写到 stdout：{"ok": true, "result": "..."}
 *   - 失败：{"ok": false, "error": "..."}
 *
 * 【配置】每个脚本可以有自己的配置文件：scripts/<name>.config.json
 *
 *   {
 *     "enabled": true,
 *     "timeout": 120,
 *     "values": { "api_key": "sk-xxx", "default_count": 8 }
 *   }
 *
 * 配置项在脚本头部用 CONFIG 声明（与 PARAMS 同格式，额外支持 secret / default）。
 * 运行时把配置注入脚本：
 *   - stdin 的 JSON 里多一个 "_config" 字段（= values 对象）
 *   - 环境变量 FURAGENT_SKILL_CONFIG = values 的 JSON 字符串
 */
data class SkillMeta(
    /** 脚本标识（= .py 文件名去掉扩展名，也是工具名后缀）。 */
    val name: String,
    /** 给 AI 看的说明。 */
    val description: String,
    /** 超时秒数（脚本头部 TIMEOUT 声明，可被配置文件覆盖）。 */
    val timeout: Int,
    /** 参数定义。 */
    val params: Map<String, SkillParam>,
    /** 配置项定义（脚本头部 CONFIG 声明，可为空）。 */
    val configSpec: Map<String, SkillConfigParam>,
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
                put("required", JSONArray().apply { required.forEach { put(it) } })
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
 * 脚本配置项声明（脚本头部 CONFIG 里的一项）。
 *
 * @param secret 敏感项（API Key 之类）：UI 里打码显示，AI 读配置时也打码。
 * @param default 默认值（JSON 值，可为 null）。
 */
data class SkillConfigParam(
    val type: String,
    val description: String,
    val secret: Boolean,
    val default: Any?
)

/**
 * 一个脚本的运行时配置（scripts/<name>.config.json 的内容）。
 *
 * @param enabled 是否启用（AI 能否看到并调用这个脚本）。
 * @param timeout 超时秒数覆盖值；null 表示用脚本头部的 TIMEOUT。
 * @param values 配置项的实际值。
 */
data class SkillConfig(
    val enabled: Boolean = true,
    val timeout: Int? = null,
    val values: Map<String, Any?> = emptyMap()
) {
    /** 取某个配置项的值（没有就用声明里的默认值）。 */
    fun valueOf(key: String, spec: Map<String, SkillConfigParam>): Any? =
        if (values.containsKey(key)) values[key] else spec[key]?.default
}

/**
 * 一个无法加载的脚本文件（语法错误 / 解析失败）。
 */
data class BrokenSkill(
    val fileName: String,
    val reason: String,
    val file: File
)
