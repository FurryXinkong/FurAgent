package net.xinkong.furagent.desktop.system

import net.xinkong.furagent.desktop.system.UiWatchdog.CHECK_INTERVAL_MS
import net.xinkong.furagent.desktop.system.UiWatchdog.STUCK_THRESHOLD_MS
import net.xinkong.furagent.shared.util.DateFormats
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * UI 卡死看门狗。
 *
 * 为什么需要
 * 用户反馈「运行中突然整个 JVM 卡死，必须杀进程」。卡死时日志也写不进去，
 * 事后完全无法定位。jstack 只能抓「正在卡」的现场，而卡死是偶发的，
 * 人不可能一直守着。
 *
 * 做法
 * 起一个独立的守护线程（不依赖任何协程调度器，避免调度器本身被卡住时失效），
 * 每 [CHECK_INTERVAL_MS] 检查一次心跳时间戳。UI 侧每帧调 [beat] 更新心跳。
 * 如果超过 [STUCK_THRESHOLD_MS] 没更新，说明 UI 线程被阻塞了，
 * 立刻把**全 JVM 线程栈** dump 到 ~/.furagent/logs/stuck-*.log。
 *
 * 关键点：dump 用的是 Thread.getAllStackTraces()，它不需要目标线程配合，
 * 即使目标线程死锁/死循环也能拿到栈。
 *
 * 只 dump 一次（每次卡死周期），避免卡死期间疯狂写盘把磁盘写满。
 * 心跳恢复后重置标志，下次卡死还能再抓。
 */
object UiWatchdog {

    /** 检查间隔：1 秒。太密浪费 CPU，太疏会漏掉短时卡顿。 */
    private const val CHECK_INTERVAL_MS = 1000L

    /** 超过这个时长没心跳就判定卡死。5 秒足够排除正常的 GC 停顿和重活。 */
    private const val STUCK_THRESHOLD_MS = 5000L

    /** 卡死期间最多 dump 几次（防止长时间卡死写爆磁盘）。 */
    private const val MAX_DUMPS_PER_STUCK = 3

    /** 两次 dump 的最小间隔。 */
    private const val DUMP_COOLDOWN_MS = 30_000L

    private val lastBeat = AtomicLong(System.currentTimeMillis())
    private val lastDump = AtomicLong(0L)
    private val dumpCount = AtomicLong(0L)

    @Volatile
    private var started = false

    /** UI 线程每帧调用。开销只有一次 volatile 写，可以放心高频调。 */
    fun beat() {
        lastBeat.set(System.currentTimeMillis())
        // 心跳恢复：重置 dump 计数，让下次卡死能重新抓
        if (dumpCount.get() != 0L) dumpCount.set(0L)
    }

    /** 在 main() 里调一次。重复调用无副作用。 */
    fun start() {
        if (started) return
        started = true
        val t = Thread({
            while (true) {
                try {
                    Thread.sleep(CHECK_INTERVAL_MS)
                    val now = System.currentTimeMillis()
                    val idle = now - lastBeat.get()
                    if (idle >= STUCK_THRESHOLD_MS) {
                        val count = dumpCount.get()
                        val sinceDump = now - lastDump.get()
                        if (count < MAX_DUMPS_PER_STUCK && sinceDump >= DUMP_COOLDOWN_MS) {
                            dumpCount.incrementAndGet()
                            lastDump.set(now)
                            dumpStacks(idle, count + 1)
                        }
                    }
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {
                    // 看门狗自己绝不能挂
                }
            }
        }, "Furagent-UiWatchdog")
        t.isDaemon = true
        t.priority = Thread.MAX_PRIORITY
        t.start()
        DesktopLogger.info("WATCHDOG", "UI 看门狗已启动（阈值 ${STUCK_THRESHOLD_MS}ms）")
    }

    private fun dumpStacks(idleMs: Long, seq: Long) {
        try {
            val sb = StringBuilder()
            sb.appendLine("===== UI 卡死现场 =====")
            sb.appendLine("时间：${DateFormats.formatFullMs()}")
            sb.appendLine("UI 已无心跳：${idleMs}ms（第 $seq 次 dump）")
            sb.appendLine("JVM 运行时长：${ManagementFactory.getRuntimeMXBean().uptime / 1000}s")
            sb.appendLine("可用处理器：${Runtime.getRuntime().availableProcessors()}")
            sb.appendLine("堆内存：已用 ${Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / 1024 / 1024 }}MB / 上限 ${Runtime.getRuntime().maxMemory() / 1024 / 1024}MB")
            sb.appendLine()

            // 死锁检测：JDK 自带，能直接指出互相等待的线程
            try {
                val deadlocked = ManagementFactory.getThreadMXBean().findDeadlockedThreads()
                if (deadlocked != null && deadlocked.isNotEmpty()) {
                    sb.appendLine("!!! 检测到死锁线程：${deadlocked.joinToString()}")
                    val infos = ManagementFactory.getThreadMXBean().getThreadInfo(deadlocked, true, true)
                    for (info in infos) {
                        sb.appendLine("--- 死锁线程 ${info.threadName} (id=${info.threadId}) 状态=${info.threadState}")
                        sb.appendLine("    阻塞在：${info.lockName} 持有者=${info.lockOwnerName}")
                        info.stackTrace.forEach { sb.appendLine("      at $it") }
                    }
                    sb.appendLine()
                } else {
                    sb.appendLine("未检测到 JVM 级死锁（可能是单线程死循环或外部 IO 阻塞）")
                    sb.appendLine()
                }
            } catch (_: Throwable) { }

            // 全量线程栈：按线程名排序，把 UI/EDT 相关线程排前面
            val traces = Thread.getAllStackTraces()
            val sorted = traces.entries.sortedByDescending { (t, _) ->
                val n = t.name.lowercase()
                when {
                    n.contains("awt-eventqueue") || n.contains("edt") -> 3
                    n.contains("main") -> 2
                    n.contains("skiko") || n.contains("render") -> 1
                    else -> 0
                }
            }
            sb.appendLine("===== 全部线程（共 ${sorted.size} 个）=====")
            for ((thread, stack) in sorted) {
                sb.appendLine()
                sb.appendLine("\"${thread.name}\" id=${thread.id} 状态=${thread.state} 守护=${thread.isDaemon} 优先级=${thread.priority}")
                if (stack.isEmpty()) {
                    sb.appendLine("    (无栈帧)")
                } else {
                    stack.forEach { sb.appendLine("    at $it") }
                }
            }

            val dir = File(System.getProperty("user.home"), ".furagent/logs")
            dir.mkdirs()
            val f = File(dir, "stuck-${System.currentTimeMillis()}.log")
            f.writeText(sb.toString())
            DesktopLogger.error("WATCHDOG", "UI 卡死 ${idleMs}ms，线程栈已写入 ${f.absolutePath}")
        } catch (e: Throwable) {
            try {
                DesktopLogger.error("WATCHDOG", "dump 线程栈失败：${e.javaClass.simpleName}: ${e.message}")
            } catch (_: Throwable) { }
        }
    }
}
