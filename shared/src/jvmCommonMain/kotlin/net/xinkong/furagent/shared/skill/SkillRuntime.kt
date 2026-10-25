package net.xinkong.furagent.shared.skill

import net.xinkong.furagent.shared.tools.ToolResult
import org.json.JSONObject
import java.io.File

/**
 * 脚本执行引擎抽象（双端共用 2026-10-18）。
 *
 * SkillStore / SkillTools 是纯协议层（脚本格式、元数据、配置、工具描述），
 * 与"怎么跑 Python"无关。真正跑 Python 的方式两端不同：
 *   - 桌面端：ProcessBuilder 调系统 python（DesktopSkillRuntime）
 *   - 安卓端：Chaquopy 内嵌 CPython（AndroidSkillRuntime）
 *
 * 因此把"跑 Python"的能力抽成这个接口，由两端各自实现并注册。
 * 只要实现遵守同一套 stdin/stdout JSON 协议，同一份 .py 脚本两端行为一致。
 */
interface SkillRuntime {

    /** 脚本目录（scripts/）。不存在时应创建。 */
    fun scriptsDir(): File

    /**
     * 用 Python 做 AST 静态解析，返回 {"ok":true,"meta":{...}} 或
     * {"ok":false,"error":"..."}。失败返回 null。
     *
     * 只取模块级 Assign 节点（NAME / DESCRIPTION / PARAMS / TIMEOUT / CONFIG），
     * 用 ast.literal_eval 求值，绝不 exec 用户代码。
     */
    fun parseMeta(file: File): JSONObject?

    /**
     * 执行脚本。
     *
     * 实现方负责：把 args（含 _config）以一行 JSON 写进脚本 stdin，
     * 读 stdout 的 JSON 结果，按超时终止，返回 ToolResult。
     *
     * @param meta    脚本元数据
     * @param args    脚本参数（不含 _config，由实现方注入）
     * @param cfg     脚本配置
     * @param timeout 超时秒数
     */
    suspend fun runScript(
        meta: SkillMeta,
        args: JSONObject,
        cfg: SkillConfig,
        timeout: Int
    ): ToolResult

    /**
     * 临时执行一段 Python 代码（不落盘）。
     *
     * 与 [runScript] 的区别：代码来自字符串而不是 scripts/ 下的文件，
     * 用于「帮我算一下」「验证这段逻辑」这类一次性需求。
     *
     * 默认实现返回失败（不支持），需要的平台自行覆写。
     *
     * @param code     要执行的 Python 源码
     * @param args     会以一行 JSON 写入 stdin（可为空）
     * @param workDir  工作目录（open() 相对路径的基准），空串表示不改
     * @param timeout  超时秒数
     */
    suspend fun execCode(
        code: String,
        args: JSONObject,
        workDir: String,
        timeout: Int
    ): ToolResult = ToolResult.fail("当前平台不支持临时执行 Python 代码")

    /**
     * Python 运行时是否可用。不可用时返回 false，
     * 调用方据此给出友好提示（桌面端提示装 Python，安卓端理论上恒 true）。
     */
    fun isPythonAvailable(): Boolean

    /** Python 不可用时的提示文案。 */
    fun pythonUnavailableHint(): String
}
