package net.xinkong.furagent.desktop.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.xinkong.furagent.desktop.data.SkillMeta
import net.xinkong.furagent.desktop.data.SkillStore
import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.desktop.tools.PythonTools
import org.json.JSONObject
import java.awt.Desktop
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 自定义脚本的手动操作通道（UI 用）。
 *
 * 为什么不直接复用 SkillTools.doRun：
 *   - doRun 会弹 DesktopConfirmation，那是给 AI 调用用的安全闸；
 *     手动试运行时确认已经在 UI 上做过一次，再弹一次是重复确认。
 *   - doRun 返回的 ToolResult 需要渲染，而这里只要一行 Toast + 日志。
 *   - doRun 会检查 meta.enabled；手动试运行不应该被用户的禁用拦。
 *
 * 因此这里独立实现执行逻辑，但**参数约定与超时行为和 SkillTools 完全一致**，
 * 保证「试运行通过」能代表「AI 调用也能跑通」。
 *
 * 【F3】setEnabled / setEnabledAll 也走这里，因为写盘是 IO 操作，
 * 不能在 Compose 主线程直接调。
 */
object SkillRunBus {

    // 页面可能被销毁，执行不能挂在 Composable 的作用域上
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 正在执行的脚本名（null 表示空闲）。UI 据此禁用按钮并显示「执行中」。 */
    private val _running = MutableStateFlow<String?>(null)
    val running: StateFlow<String?> = _running.asStateFlow()

    fun run(meta: SkillMeta, values: Map<String, String>) {
        if (_running.value != null) {
            ToastBus.warn("已有脚本正在执行：${_running.value}")
            return
        }
        // 注意：不检查 meta.enabled。用户手动试运行是调试用途，不应被自己的禁用拦。
        _running.value = meta.name
        scope.launch {
            try {
                val args = buildArgs(meta, values)
                val missing = meta.params.filter { it.value.required && !args.has(it.key) }.keys
                if (missing.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        ToastBus.error("缺少必填参数：${missing.joinToString(", ")}")
                    }
                    return@launch
                }

                val python = PythonTools.getPythonPath()
                if (python == null) {
                    withContext(Dispatchers.Main) {
                        ToastBus.error("Python 3 未安装或不在 PATH 中，无法运行脚本")
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) { ToastBus.info("开始执行 ${meta.name}…") }
                val (ok, output) = exec(python, meta, args)

                DesktopLogger.info(
                    "Skill",
                    "手动执行 ${meta.name}：${if (ok) "成功" else "失败"}\n$output"
                )
                withContext(Dispatchers.Main) {
                    if (ok) {
                        ToastBus.info("${meta.name} 执行完成：${output.lineSequence().firstOrNull { it.isNotBlank() } ?: "无输出"}")
                    } else {
                        ToastBus.error("${meta.name} 执行失败：${output.lineSequence().firstOrNull { it.isNotBlank() } ?: "无输出"}")
                    }
                }
            } catch (e: Exception) {
                DesktopLogger.error("Skill", "手动执行 ${meta.name} 异常：${e.javaClass.simpleName}: ${e.message}")
                withContext(Dispatchers.Main) { ToastBus.error("执行异常：${e.message}") }
            } finally {
                _running.value = null
            }
        }
    }

    /**
     * 把界面上的字符串按声明的类型转成 JSON。
     *
     * 数值类型转换失败时保留原字符串并提示，避免静默把 "abc" 变成 0 之类的脏数据。
     */
    private fun buildArgs(meta: SkillMeta, values: Map<String, String>): JSONObject {
        val o = JSONObject()
        meta.params.forEach { (k, p) ->
            val raw = values[k]?.trim().orEmpty()
            when (p.type) {
                "integer" -> {
                    val n = raw.toLongOrNull()
                    if (n != null) o.put(k, n) else if (raw.isNotEmpty()) o.put(k, raw)
                }
                "number" -> {
                    val n = raw.toDoubleOrNull()
                    if (n != null) o.put(k, n) else if (raw.isNotEmpty()) o.put(k, raw)
                }
                "boolean" -> o.put(k, raw.equals("true", ignoreCase = true))
                else -> o.put(k, raw)
            }
        }
        return o
    }

    /** 返回 (是否成功, 输出文本)。逻辑同 SkillTools.runScript。 */
    private fun exec(python: String, meta: SkillMeta, args: JSONObject): Pair<Boolean, String> {
        val pb = ProcessBuilder(python, "-u", meta.file.absolutePath)
        pb.directory(meta.file.parentFile)
        pb.redirectErrorStream(false)
        val env = pb.environment()
        if (env["PYTHONIOENCODING"].isNullOrBlank()) env["PYTHONIOENCODING"] = "utf-8"
        if (env["PYTHONUTF8"].isNullOrBlank()) env["PYTHONUTF8"] = "1"

        val p = pb.start()
        try {
            p.outputStream.bufferedWriter(Charsets.UTF_8).use { w ->
                w.write(args.toString())
                w.write("\n")
            }
        } catch (e: Exception) {
            DesktopLogger.warn("Skill", "写脚本 stdin 失败：${e.message}")
        }

        val out = StringBuilder()
        val err = StringBuilder()
        val t1 = Thread {
            runCatching { p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { out.append(it).append("\n") } }
        }
        val t2 = Thread {
            runCatching { p.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { err.append(it).append("\n") } }
        }
        t1.start(); t2.start()

        val finished = p.waitFor(meta.timeout.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            p.destroyForcibly()
            t1.join(1000); t2.join(1000)
            return false to "执行超时（超过 ${meta.timeout} 秒），已强制结束"
        }
        t1.join(2000); t2.join(2000)

        val exit = p.exitValue()
        val stdout = out.toString().trim()
        val stderr = err.toString().trim()

        // 成功判定：退出码为 0，且（若输出了 JSON）ok 字段不为 false
        if (exit != 0) {
            return false to (stderr.ifBlank { stdout }.ifBlank { "退出码 $exit" })
        }
        if (stdout.isNotEmpty()) {
            try {
                val j = JSONObject(stdout)
                if (j.has("ok") && !j.optBoolean("ok", true)) {
                    return false to j.optString("error", "脚本返回 ok=false")
                }
                if (j.has("result")) return true to j.optString("result", "")
            } catch (_: Exception) {
                // 不是 JSON 就直接当文本结果
            }
        }
        return true to stdout.ifBlank { "（无输出）" }
    }

    /** 删除脚本文件。onDone 在主线程回调，参数为错误信息（成功时为 null）。 */
    fun delete(name: String, onDone: (String?) -> Unit) {
        if (_running.value == name) {
            ToastBus.warn("脚本正在执行，无法删除：$name")
            return
        }
        scope.launch {
            val err = SkillStore.delete(name)
            withContext(Dispatchers.Main) {
                if (err == null) ToastBus.info("已删除脚本 $name")
                else ToastBus.error(err)
                onDone(err)
            }
        }
    }

    /** 删除一个加载失败的脚本文件。onDone 在主线程回调。 */
    fun deleteBroken(fileName: String, onDone: (String?) -> Unit) {
        scope.launch {
            val err = SkillStore.deleteBroken(fileName)
            withContext(Dispatchers.Main) {
                if (err == null) ToastBus.info("已清理无效脚本 $fileName")
                else ToastBus.error(err)
                onDone(err)
            }
        }
    }

    /** 批量清理无效脚本。onDone 在主线程回调。 */
    fun deleteBrokenAll(names: List<String>, onDone: () -> Unit) {
        scope.launch {
            var ok = 0
            val fails = mutableListOf<String>()
            names.forEach { n ->
                val err = SkillStore.deleteBroken(n)
                if (err == null) ok++ else fails.add("$n：$err")
            }
            withContext(Dispatchers.Main) {
                if (fails.isEmpty()) ToastBus.info("已清理 $ok 个无效脚本")
                else ToastBus.warn("清理 $ok 个，${fails.size} 个失败：${fails.joinToString("；")}")
                onDone()
            }
        }
    }

    /** 【B21】从示例创建脚本。onDone 在主线程回调，参数为错误信息（成功时 null）。 */
    fun createExample(onDone: (String?) -> Unit) {
        scope.launch {
            val err = SkillStore.createExample()
            withContext(Dispatchers.Main) {
                if (err == null) ToastBus.info("已创建示例脚本 example_hello")
                else ToastBus.error(err)
                onDone(err)
            }
        }
    }

    /** 用系统文件管理器打开脚本目录。 */
    fun openDir(path: String) {
        scope.launch {
            val ok = try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(File(path))
                    true
                } else false
            } catch (e: Exception) {
                DesktopLogger.warn("Skill", "打开脚本目录失败：${e.message}")
                false
            }
            withContext(Dispatchers.Main) {
                if (ok) ToastBus.info("已打开脚本目录")
                else ToastBus.error("无法打开目录：$path")
            }
        }
    }
}