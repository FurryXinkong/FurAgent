package net.xinkong.furagent.desktop.tools

import net.xinkong.furagent.desktop.core.DesktopConfirmation
import net.xinkong.furagent.desktop.data.DesktopSettingsHolder
import net.xinkong.furagent.desktop.data.SkillStore
import net.xinkong.furagent.shared.skill.SkillTools as SharedSkillTools
import net.xinkong.furagent.shared.tools.ToolResult
import org.json.JSONObject

/**
 * 自定义脚本工具 —— 桌面端适配层。
 *
 * 协议层（pytool 工具定义 / list / info / run / create /
 * delete / config / 动态工具注册）已抽到 shared.skill.SkillTools。
 * 本文件只保留桌面端特有的「运行前确认弹窗」（requireConfirmSkill）。
 *
 * 脚本格式、调用协议、配置注入与安卓端完全一致，脚本文件两端通用。
 */
object SkillTools {

    data class ToolDef(
        val name: String,
        val displayName: String,
        val description: String,
        val paramsSchema: String
    )

    /** 常驻管理工具。 */
    val TOOLS: List<ToolDef> = SharedSkillTools.TOOLS.map {
        ToolDef(it.name, it.displayName, it.description, it.paramsSchema)
    }

    val NAMES: Set<String> = TOOLS.map { it.name }.toSet()

    fun displayName(name: String): String = SharedSkillTools.displayName(name)

    /** 动态生成每个脚本对应的独立工具。 */
    fun dynamicTools(): List<ToolDef> = SharedSkillTools.dynamicTools().map {
        ToolDef(it.name, it.displayName, it.description, it.paramsSchema)
    }

    /** 判断某个工具名是否是动态脚本工具。 */
    fun isDynamicTool(name: String): Boolean = SharedSkillTools.isDynamicTool(name)

    /**
     * 执行脚本工具。
     *
     * 与 shared 版的区别：桌面端在真正运行脚本前会弹确认框（受 requireConfirmSkill 控制）。
     * 管理类 op（list / info / create / delete / config）不弹窗，直接委托。
     */
    suspend fun execute(name: String, args: JSONObject): ToolResult? {
        // 动态脚本工具 / op=run：需要确认
        val isRun = isDynamicTool(name) ||
                (name == "pytool" && args.optString("op", "").lowercase() == "run")
        if (isRun) {
            val scriptName = if (isDynamicTool(name)) {
                name.removePrefix(SkillStore.TOOL_PREFIX)
            } else {
                args.optString("name", "").trim()
            }
            val scriptArgs = if (isDynamicTool(name)) args else (args.optJSONObject("args") ?: JSONObject())

            val meta = SkillStore.find(scriptName)
                ?: return ToolResult.fail("脚本不存在：$scriptName（可用 pytool(op=list) 查看）")

            val settings = DesktopSettingsHolder.current
            if (settings.requireConfirmSkill) {
                val cfg = SkillStore.loadConfig(scriptName)
                val timeout = SkillStore.effectiveTimeout(meta, cfg)
                val argsPreview = if (scriptArgs.length() == 0) "(无)" else scriptArgs.toString(2)
                val approved = DesktopConfirmation.request(
                    "运行自定义脚本确认",
                    "AI 请求运行脚本：\n${meta.name}\n\n" +
                            "文件：${meta.file.absolutePath}\n" +
                            "参数：\n$argsPreview\n\n" +
                            "超时：$timeout 秒\n\n是否允许？"
                )
                if (!approved) return ToolResult.fail("用户已拒绝运行脚本")
            }
            return SharedSkillTools.doRunByName(scriptName, scriptArgs)
        }

        // 其余（list / info / create / delete / config）直接委托
        return SharedSkillTools.execute(name, args)
    }
}
