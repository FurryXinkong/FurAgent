package net.xinkong.furagent.system

import android.content.pm.PackageManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import net.xinkong.furagent.core.AgentLogger
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

object ShizukuHelper {

    const val PERMISSION_REQUEST_CODE = 1001

    @Volatile
    private var permissionDeferred: CompletableDeferred<Boolean>? = null

    fun isAvailable(): Boolean {
        return try { Shizuku.pingBinder() } catch (_: Exception) { false }
    }

    fun hasPermission(): Boolean {
        return try {
            if (!isAvailable()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
    }

    suspend fun requestPermission(): Boolean {
        if (!isAvailable()) return false
        if (hasPermission()) return true

        val deferred = CompletableDeferred<Boolean>()
        permissionDeferred = deferred

        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PERMISSION_REQUEST_CODE) {
                permissionDeferred?.complete(grantResult == PackageManager.PERMISSION_GRANTED)
                permissionDeferred = null
            }
        }

        return try {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            withTimeoutOrNull(60_000L.milliseconds) { deferred.await() } ?: false
        } catch (e: Exception) {
            AgentLogger.log("ERR", "Shizuku 权限请求失败：${e.message}")
            false
        } finally {
            try { Shizuku.removeRequestPermissionResultListener(listener) } catch (_: Exception) { }
        }
    }

    fun startActivityByPackage(packageName: String): Boolean {
        return !(!isAvailable() || !hasPermission()) && try {
            val result = executeCommand(
                "monkey -p $packageName -c android.intent.category.LAUNCHER 1",
                timeoutSeconds = 10
            )
            AgentLogger.log("SYS", "Shizuku monkey 结果：$result")
            !result.contains("aborted", ignoreCase = true) &&
                    !result.contains("Error", ignoreCase = true) &&
                    !result.contains("Exception", ignoreCase = true)
        } catch (e: Exception) {
            AgentLogger.log("ERR", "Shizuku 打开应用失败：${e.message}")
            false
        }
    }

    /**
     * 通过 Shizuku 执行 shell 命令。
     *
     * 用"两个流都读到 EOF"作为结束信号，不用 process.waitFor（Binder 代理不可靠）。
     * 支持取消：等待期间每 50ms 检查一次 isCancelled。
     * 支持实时回调：每读到一行通过 onLine 回调出去。
     *
     * 【不截断】返回的 stdout / stderr 完整拼接，不设长度上限。
     *  用户如果想控制发给 AI 的长度，应该在 Agent 层处理，而不是在工具实现层偷砍。
     */
    fun executeCommand(
        command: String,
        timeoutSeconds: Int = 30,
        isCancelled: () -> Boolean = { false },
        onLine: ((String) -> Unit)? = null
    ): String {
        if (!isAvailable()) return "失败：Shizuku 服务未运行"
        if (!hasPermission()) return "失败：未获得 Shizuku 权限"

        return try {
            val process = createShizukuProcess(command)
                ?: return "失败：无法创建 Shizuku 进程"

            val stdoutBuf = StringBuilder()
            val stderrBuf = StringBuilder()
            val stdoutDone = CountDownLatch(1)
            val stderrDone = CountDownLatch(1)

            val stdoutThread = Thread {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            stdoutBuf.appendLine(line)
                            try { onLine?.invoke(line) } catch (_: Exception) { }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    stdoutDone.countDown()
                }
            }
            val stderrThread = Thread {
                try {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            stderrBuf.appendLine(line)
                            try { onLine?.invoke("[err] $line") } catch (_: Exception) { }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    stderrDone.countDown()
                }
            }

            stdoutThread.start()
            stderrThread.start()

            val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
            var stdoutFinished = false
            var stderrFinished = false
            var cancelled = false

            while (System.currentTimeMillis() < deadline) {
                if (isCancelled()) {
                    cancelled = true
                    break
                }
                if (!stdoutFinished) {
                    stdoutFinished = stdoutDone.await(50, TimeUnit.MILLISECONDS)
                }
                if (!stderrFinished) {
                    stderrFinished = stderrDone.await(50, TimeUnit.MILLISECONDS)
                }
                if (stdoutFinished && stderrFinished) break
            }

            if (cancelled) {
                forceKill(process)
                return "已取消"
            }

            if (!stdoutFinished || !stderrFinished) {
                forceKill(process)
                val sb = StringBuilder()
                sb.append("失败：命令执行超时（${timeoutSeconds} 秒）\n")
                if (stdoutBuf.isNotEmpty()) {
                    sb.append("已收到的 stdout：\n")
                    sb.append(stdoutBuf.toString())
                }
                if (stderrBuf.isNotEmpty()) {
                    sb.append("\n已收到的 stderr：\n")
                    sb.append(stderrBuf.toString())
                }
                if (stdoutBuf.isEmpty() && stderrBuf.isEmpty()) sb.append("（无任何输出）")
                return sb.toString()
            }

            val exitCode = try { process.exitValue() } catch (_: Exception) { 0 }

            val sb = StringBuilder()
            sb.append("退出码：$exitCode\n")
            if (stdoutBuf.isNotEmpty()) {
                sb.append("stdout:\n")
                sb.append(stdoutBuf.toString())
            }
            if (stderrBuf.isNotEmpty()) {
                sb.append("\nstderr:\n")
                sb.append(stderrBuf.toString())
            }
            if (stdoutBuf.isEmpty() && stderrBuf.isEmpty()) sb.append("(无输出)")
            sb.toString()
        } catch (e: Exception) {
            AgentLogger.log("ERR", "Shizuku 执行命令异常：${e.message}")
            "执行失败：${e.message ?: "未知错误"}"
        }
    }

    /**
     * 强制终止 Shizuku 子进程。
     * Shizuku 是 Binder 代理，直接 destroy() 未必能杀掉远端 shell，
     * 所以：
     *  1. 先给 process 的 stdin 发 Ctrl+C（0x03），前台 shell 会响应中断
     *  2. 再调 destroy() / destroyForcibly()
     */
    private fun forceKill(process: Process) {
        try {
            // 尝试发 Ctrl+C
            try {
                process.outputStream.write(0x03)
                process.outputStream.flush()
            } catch (_: Throwable) { }

            // 稍等一下让 shell 处理 Ctrl+C
            try { Thread.sleep(150) } catch (_: InterruptedException) { }

            try { process.destroy() } catch (_: Throwable) { }
            try { Thread.sleep(150) } catch (_: InterruptedException) { }
            try { process.destroyForcibly() } catch (_: Throwable) { }
        } catch (_: Throwable) { }
    }

    private fun createShizukuProcess(command: String): Process? {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val result = method.invoke(null, arrayOf("sh", "-c", command), null, null)
            result as? Process
        } catch (e: Exception) {
            try {
                val shellClass = Class.forName("rikka.shizuku.Shell")
                val method = shellClass.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                method.isAccessible = true
                val result = method.invoke(null, arrayOf("sh", "-c", command), null, null)
                result as? Process
            } catch (e2: Exception) {
                AgentLogger.log("ERR", "Shizuku 反射调用失败：${e2.message}")
                null
            }
        }
    }
}