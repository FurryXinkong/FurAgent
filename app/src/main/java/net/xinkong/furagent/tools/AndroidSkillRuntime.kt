package net.xinkong.furagent.tools

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.shared.skill.SkillConfig
import net.xinkong.furagent.shared.skill.SkillMeta
import net.xinkong.furagent.shared.skill.SkillRuntime
import net.xinkong.furagent.shared.skill.SkillStore
import net.xinkong.furagent.shared.tools.ToolResult
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 安卓端脚本运行时：Chaquopy 内嵌 CPython（2026-10-18）。
 *
 * 【为什么用 Chaquopy 而不是 Termux】
 *   - 用户零门槛：Python 打进 APK，装完就能用，不用教用户装 Termux。
 *   - 进程内执行：不像 Termux 要跨 App 通信，链路稳定。
 *   - 协议一致：与桌面端（ProcessBuilder 调系统 python）遵守同一套
 *     stdin/stdout JSON 协议，同一份 .py 脚本两端行为一致。
 *
 * 【怎么模拟 stdin/stdout】
 *   Chaquopy 没有真正的子进程，脚本里 sys.stdin.readline() / print() 需要
 *   一个"假"的 stdin/stdout。做法：在 Python 侧把 sys.stdin 换成 io.StringIO
 *   （预置一行 JSON 参数），sys.stdout 换成 io.StringIO（收集输出），
 *   再用 runpy.run_path(script, run_name="__main__") 执行脚本。
 *   脚本代码零改动，两端通用。
 *
 * 【超时】
 *   Chaquopy 是进程内执行，无法像子进程那样 destroyForcibly。
 *   用独立线程 + join(timeout) 做软超时：超时后返回失败，线程继续跑但结果丢弃。
 *   脚本里若有死循环会一直占一个线程，但不会卡住主流程。
 */
class AndroidSkillRuntime(private val context: Context) : SkillRuntime {

    private val appContext = context.applicationContext

    /**
     * 确保 Python 已启动。
     *
     * 【关键】Chaquopy 在 Android 上必须显式调用
     * Python.start(AndroidPlatform(context))，否则 getInstance() 会抛
     * "Cannot use GenericPlatform on Android"。
     * start() 只能调一次，重复调用会抛异常，所以用 @Volatile 标记 + 双重检查。
     */
    @Volatile
    private var pythonStarted = false

    @Synchronized
    private fun ensurePythonStarted() {
        if (pythonStarted) return
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(appContext))
        }
        pythonStarted = true
    }

    override fun scriptsDir(): File {
        // 【缓存】list/find/create 都会频繁调本方法，
        // 不能每次都写探针文件 + 遍历迁移目录。
        cachedScriptsDir?.let { return it }
        synchronized(this) {
            cachedScriptsDir?.let { return it }
            // 【优先用开放目录】/sdcard/FurAgent/scripts，用户能用文件管理器直接看到、
            // 手动放脚本、用编辑器改。之前用 filesDir（App 私有目录）导致：
            //   1. 用户看不到也放不进去
            //   2. 卸载 App 脚本全丢
            //   3. “AI 说创建成功但找不到文件”

            // 【回退】没给 MANAGE_EXTERNAL_STORAGE 权限时，外部目录不可写，
            // 回退到私有目录，保证功能不崩（只是用户看不到）。
            val external = File(
                SDCARD_PATH,
                "FurAgent/scripts"
            )
            val target =
                if (canUseExternal(external)) external else File(appContext.filesDir, "scripts")
            if (!target.exists()) target.mkdirs()
            cachedScriptsDir = target
            return target
        }
    }

    @Volatile
    private var cachedScriptsDir: File? = null

    companion object {
        /**
         * 脚本执行全局互斥锁。
         *
         * Chaquopy 只有一个 Python 解释器，sys.stdin/stdout 是全局状态，
         * 并发执行必然串结果。这里强制串行。
         */
        private val scriptMutex = Mutex()
    }

    /** 外部目录是否可用：能创建且能写入。 */
    private fun canUseExternal(dir: File): Boolean {
        return try {
            if (!dir.exists() && !dir.mkdirs()) return false
            val probe = File(dir, ".write_probe")
            probe.writeText("ok")
            probe.delete()
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun isPythonAvailable(): Boolean = try {
        ensurePythonStarted()
        true
    } catch (e: Throwable) {
        AgentLogger.log("Skill", "Python 启动失败：${e.javaClass.simpleName}: ${e.message}")
        false
    }

    override fun pythonUnavailableHint(): String =
        "安卓端 Python 运行时未就绪（Chaquopy 初始化失败），请重启 App 或查看日志"

    // AST 解析

    override fun parseMeta(file: File): JSONObject? {
        return try {
            ensurePythonStarted()
            val py = Python.getInstance()
            val parser = py.getModule("skill_parser")
            val out = parser.callAttr("parse_meta", file.absolutePath).toString()
            if (out.isBlank()) {
                AgentLogger.log("Skill", "解析 ${file.name}：parse_meta 返回空")
                return null
            }
            JSONObject(out)
        } catch (e: Throwable) {
            // 【诊断】把完整堆栈记下来，否则“create 成功但 list 为空”无法排查
            AgentLogger.log(
                "Skill",
                "解析 ${file.name} 异常：${e.javaClass.simpleName}: ${e.message}\n" +
                        e.stackTraceToString().take(1500)
            )
            null
        }
    }

    // 执行脚本

    override suspend fun runScript(
        meta: SkillMeta,
        args: JSONObject,
        cfg: SkillConfig,
        timeout: Int
    ): ToolResult = withContext(Dispatchers.IO) {
        // 【并发串结果修复】Chaquopy 是单进程单解释器，sys.stdin/stdout 是全局状态。
        // 两个脚本同时跑时，A 把 sys.stdout 换成自己的 buffer，B 又换一次，
        // A 的输出就写进了 B 的 buffer——表现为“拿到另一个脚本的结果”、
        // “参数丢失”、“报别的脚本的参数校验错误”。

        // 修法：全局 Mutex 强制串行。脚本执行通常很快，串行代价可接受。
        // （真正并发需要每个脚本独立解释器，Chaquopy 做不到）
        scriptMutex.withLock {
            runScriptLocked(meta, args, cfg, timeout)
        }
    }

    // 临时执行 Python 代码（不落盘）

    /**
     * 临时执行一段 Python 代码，不写文件。
     *
     * 【与 runScript 的区别】
     *   runScript 执行 scripts/ 下的 .py 文件（持久化脚本）；
     *   execCode 执行传入的代码字符串（一次性，用完即弃）。
     *
     * 【为什么需要】用户说“帮我算一下”“验证这段逻辑”时，
     * 不应该为了跑一次就往 scripts/ 里塞个文件。
     *
     * 【并发】同样受 scriptMutex 保护：Chaquopy 单解释器，
     * sys.stdout 是全局状态，并发会串结果。
     */
    override suspend fun execCode(
        code: String,
        args: JSONObject,
        workDir: String,
        timeout: Int
    ): ToolResult = withContext(Dispatchers.IO) {
        scriptMutex.withLock {
            val start = System.currentTimeMillis()
            val holder = AtomicReference<ToolResult?>()
            val worker = Thread {
                try {
                    holder.set(execCodeLocked(code, args, workDir, start))
                } catch (e: Throwable) {
                    holder.set(
                        ToolResult.fail(
                            "执行 Python 代码失败：${e.javaClass.simpleName}: ${e.message}"
                        )
                    )
                }
            }
            worker.isDaemon = true
            worker.start()
            worker.join(timeout.toLong() * 1000)

            if (worker.isAlive) {
                return@withLock ToolResult.fail(
                    "Python 代码执行超时（$timeout 秒）。安卓端为进程内执行，无法强制终止，" +
                            "该线程会在后台继续运行直到自行结束。请检查代码是否有死循环或阻塞操作。"
                )
            }
            holder.get() ?: ToolResult.fail("Python 代码未产生结果")
        }
    }

    private fun execCodeLocked(
        code: String,
        stdinArgs: JSONObject,
        workDir: String,
        start: Long
    ): ToolResult {
        ensurePythonStarted()
        val py = Python.getInstance()
        val parser = py.getModule("skill_parser")
        val out = parser.callAttr(
            "exec_code",
            code,
            stdinArgs.toString(),
            workDir
        ).toString()

        val result = JSONObject(out)
        val ok = result.optBoolean("ok", false)
        val stdout = result.optString("stdout", "")
        val stderr = result.optString("stderr", "")
        val error = result.optString("error", "")
        val elapsed = System.currentTimeMillis() - start

        if (!ok) {
            return ToolResult.fail(
                "Python 代码执行失败：${error.ifBlank { stderr.ifBlank { "未知错误" } }}"
            )
        }

        // 尝试把最后一行当 JSON 解析（与脚本协议一致）
        val parsed = try {
            val lastLine = stdout.lines().lastOrNull { it.isNotBlank() }
            if (lastLine == null) null else JSONObject(lastLine)
        } catch (_: Exception) { null }

        if (parsed != null) {
            val scriptOk = parsed.optBoolean("ok", true)
            if (scriptOk) {
                val r = parsed.opt("result")
                val text = when (r) {
                    null -> "(代码无返回内容)"
                    is String -> r
                    else -> r.toString()
                }
                return ToolResult.ok("执行成功（耗时 ${elapsed / 1000}s）\n\n$text")
            } else {
                val err = parsed.optString("error", "代码返回 ok=false")
                return ToolResult.fail("Python 代码执行失败：$err")
            }
        }

        return ToolResult.ok(
            "执行成功（耗时 ${elapsed / 1000}s）\n\n" +
                    stdout.ifBlank { "(无输出)" }
        )
    }

    private suspend fun runScriptLocked(
        meta: SkillMeta,
        args: JSONObject,
        cfg: SkillConfig,
        timeout: Int
    ): ToolResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()

        // 【配置注入】与桌面端一致：stdin 的 _config 字段 + 环境变量
        val cfgJson = SkillStore.configValuesJson(meta, cfg)
        val stdinArgs = JSONObject(args.toString())
        if (cfgJson.length() > 0) stdinArgs.put("_config", cfgJson)

        val holder = AtomicReference<ToolResult?>(null)
        val worker = Thread {
            try {
                holder.set(execScript(meta, stdinArgs, cfgJson, start))
            } catch (e: Throwable) {
                holder.set(
                    ToolResult.fail(
                        "运行脚本失败：${e.javaClass.simpleName}: ${e.message}"
                    )
                )
            }
        }
        worker.isDaemon = true
        worker.start()
        worker.join(timeout.toLong() * 1000)

        if (worker.isAlive) {
            // 软超时：线程还在跑，结果丢弃
            return@withContext ToolResult.fail(
                "脚本执行超时（$timeout 秒）。安卓端为进程内执行，无法强制终止，" +
                        "该脚本线程会在后台继续运行直到自行结束。请检查脚本是否有死循环或阻塞操作。"
            )
        }
        holder.get() ?: ToolResult.fail("脚本 ${meta.name} 未产生结果")
    }

    private fun execScript(
        meta: SkillMeta,
        stdinArgs: JSONObject,
        cfgJson: JSONObject,
        start: Long
    ): ToolResult {
        ensurePythonStarted()
        val py = Python.getInstance()

        // 同样改用 Python 模块（skill_parser.run_script），
        // 不用 exec + dict 取 __ok__/__stdout__。
        val parser = py.getModule("skill_parser")
        val out = parser.callAttr(
            "run_script",
            meta.file.absolutePath,
            stdinArgs.toString(),
            if (cfgJson.length() > 0) cfgJson.toString() else ""
        ).toString()

        val result = JSONObject(out)
        val ok = result.optBoolean("ok", false)
        val stdout = result.optString("stdout", "")
        val stderr = result.optString("stderr", "")
        val error = result.optString("error", "")

        val elapsed = System.currentTimeMillis() - start

        if (!ok) {
            return ToolResult.fail(
                "脚本 ${meta.name} 执行失败：${error.ifBlank { stderr.ifBlank { "未知错误" } }}"
            )
        }

        // 解析脚本返回的 JSON（最后一行非空）
        val parsed = try {
            val lastLine = stdout.lines().lastOrNull { it.isNotBlank() }
            if (lastLine == null) null else JSONObject(lastLine)
        } catch (_: Exception) { null }

        if (parsed != null) {
            val scriptOk = parsed.optBoolean("ok", true)
            if (scriptOk) {
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

        // 没有 JSON 输出，按纯文本返回
        return ToolResult.ok(
            "脚本 ${meta.name} 执行成功（耗时 ${elapsed / 1000}s）\n\n" +
                    stdout.ifBlank { "(无输出)" }
        )
    }
}
