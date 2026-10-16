package net.xinkong.furagent.desktop.core

import kotlinx.coroutines.*
import net.xinkong.furagent.desktop.data.ScheduleStore
import net.xinkong.furagent.desktop.data.ScheduledTask
import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.desktop.system.DesktopNotifier
import net.xinkong.furagent.desktop.tools.DesktopTools
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * 桌面端定时任务调度器。
 *
 * 与 Android 不同：没有系统 AlarmManager，自己每秒轮询。
 * 好处是可以做到秒级精度，且条件评估非常灵活。
 *
 * 关键设计：
 *  - 单例 object，App 生命周期内一直运行
 *  - 每秒 tick 一次，检查所有到期任务
 *  - 条件评估放 Dispatchers.IO，避免阻塞主 loop
 *  - 同一任务并发保护：runningIds 集合
 *  - 执行超时保护：每个任务最多 10 分钟
 *  - ScheduleStore 读写全部走 Dispatchers.IO，不阻塞 tick
 *
 * 【性能修复】原实现里每个到期任务都单独调 ScheduleStore.add / delete，
 * 每次都要读写整个 schedules.json。改为 tick 末尾批量写一次。
 */
object TaskScheduler {

    private const val TICK_INTERVAL_MS = 1000L
    private const val TASK_TIMEOUT_MS = 10 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var loopJob: Job? = null

    private val runningIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var lastTickAt: Long = 0L
        private set

    @Volatile
    var totalExecutions: Long = 0L
        private set

    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            log("调度器已启动（${TICK_INTERVAL_MS}ms 轮询）")
            while (isActive) {
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("tick 异常：${e.javaClass.simpleName}: ${e.message}")
                }
                delay(TICK_INTERVAL_MS.milliseconds)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        log("调度器已停止")
    }

    fun isRunning(): Boolean = loopJob?.isActive == true

    // ============================================================
    // 核心 tick
    // ============================================================

    private suspend fun tick() {
        lastTickAt = System.currentTimeMillis()
        val now = lastTickAt

        val tasks = withContext(Dispatchers.IO) { ScheduleStore.list() }
        if (tasks.isEmpty()) return

        // 【性能修复】本次 tick 内所有 reschedule 操作先收集到内存，
        // 末尾一次性 batchUpdate 写盘，避免每个到期任务都读写整个 JSON。
        val pendingUpdates = mutableListOf<ScheduledTask>()
        val pendingDeletes = mutableListOf<String>()

        for (task in tasks) {
            if (!task.enabled) continue
            if (task.triggerTime > now) continue
            if (task.id in runningIds) continue

            // 条件评估放 IO 线程（可能触发外部命令）
            val condResult = withContext(Dispatchers.IO) {
                ScheduleStore.evaluateConditions(task)
            }

            val label = task.description.ifBlank { task.id.take(8) }

            if (!condResult.met && task.skipIfNotMet) {
                log("跳过「$label」：${condResult.reason}")
                val next = ScheduleStore.calculateNextTrigger(task)
                if (next != null) {
                    pendingUpdates.add(task.copy(triggerTime = next))
                } else if (task.repeatMode == "cron") {
                    // 【体验优化 B8】cron 非法：不再静默删任务，改成日志 + 通知，让用户自己改
                    DesktopLogger.warn(
                        "Schedule",
                        "任务「$label」cron 表达式可能非法（${task.cronExpression}），已保留但暂停调度"
                    )
                } else {
                    pendingDeletes.add(task.id)
                }
                continue
            }

            // 派发执行
            runningIds.add(task.id)
            scope.launch {
                try {
                    withTimeoutOrNull(TASK_TIMEOUT_MS.milliseconds) {
                        executeTask(task)
                    } ?: log("任务执行超时（${TASK_TIMEOUT_MS / 1000}s）：${task.id}")
                } catch (e: Exception) {
                    log("任务执行异常：${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    runningIds.remove(task.id)
                    totalExecutions++
                }
            }

            // 立即排下次（避免下一秒重复触发）
            val next = ScheduleStore.calculateNextTrigger(task)
            if (next != null) {
                pendingUpdates.add(task.copy(triggerTime = next))
            } else if (task.repeatMode == "cron") {
                // 【B8】cron 非法：不删，只日志
                DesktopLogger.warn(
                    "Schedule",
                    "任务「$label」cron 表达式可能非法（${task.cronExpression}），保留但不排下次"
                )
            } else {
                pendingDeletes.add(task.id)
            }
        }

        if (pendingUpdates.isNotEmpty() || pendingDeletes.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                ScheduleStore.batchUpdate(pendingUpdates, pendingDeletes)
            }
            // 【修复 B1】不用解构赋值（字段顺序一变就静默错位），改用属性访问。
            for (t in pendingUpdates) {
                val label = t.description.ifBlank { t.id.take(8) }
                log("「$label」下次：${ScheduleStore.formatTime(t.triggerTime)}")
            }
            for (id in pendingDeletes) {
                log("「${id.take(8)}」已完成，移除")
            }
        }
    }

    // ============================================================
    // 任务执行
    // ============================================================

    private suspend fun executeTask(task: ScheduledTask) {
        log("触发「${task.description.ifBlank { task.id.take(8) }}」action=${task.action}")

        when (task.action) {
            "notify" -> {
                DesktopNotifier.notify(
                    task.notificationTitle.ifBlank { "FurAgent 提醒" },
                    task.notificationContent.ifBlank { task.description }
                )
            }

            "run_tool" -> {
                if (task.toolName.isBlank()) {
                    log("run_tool 缺少 toolName")
                    return
                }
                val args = try {
                    if (task.toolArgs.isBlank()) JSONObject()
                    else JSONObject(task.toolArgs)
                } catch (e: Exception) {
                    log("toolArgs 解析失败：${e.message}")
                    return
                }
                val result = DesktopTools.execute(task.toolName, args.toString())
                log("工具 ${task.toolName} 结果：${result.output.take(200)}")
                if (!result.success) {
                    DesktopNotifier.notify(
                        "任务执行失败",
                        "${task.description.ifBlank { task.toolName }}：${result.output.take(150)}"
                    )
                }
            }

            "run_workflow" -> {
                if (task.workflowName.isBlank()) {
                    log("run_workflow 缺少 workflowName")
                    return
                }
                // 【修复 B2】工具名是 "workflow"（WorkflowTools 注册名），
                // 旧代码写 "run_workflow" 会返回「未知工具」，功能完全不可用。
                val args = JSONObject().apply {
                    put("op", "run")
                    put("name", task.workflowName)
                }
                val result = DesktopTools.execute("workflow", args.toString())
                log("工作流 ${task.workflowName} 结果：${result.output.take(200)}")
                if (!result.success) {
                    DesktopNotifier.notify(
                        "工作流执行失败",
                        "${task.workflowName}：${result.output.take(150)}"
                    )
                }
            }

            "open_app" -> {
                if (task.appPath.isBlank()) {
                    log("open_app 缺少 appPath")
                    return
                }
                // 【修复 B3】工具名是 "apps"（AppTools 注册名），
                // 旧代码写 "open_app" 会返回「未知工具」，功能完全不可用。
                val args = JSONObject().apply {
                    put("op", "open")
                    put("path", task.appPath)
                }
                val result = DesktopTools.execute("apps", args.toString())
                log("打开应用结果：${result.output.take(200)}")
                if (!result.success) {
                    DesktopNotifier.notify(
                        "打开应用失败",
                        "${task.appPath}：${result.output.take(150)}"
                    )
                }
            }

            "run_command" -> {
                if (task.command.isBlank()) {
                    log("run_command 缺少 command")
                    return
                }
                val args = JSONObject().apply {
                    put("command", task.command)
                    put("timeout", 60)
                }
                val result = DesktopTools.execute("execute_command", args.toString())
                log("命令结果：${result.output.take(200)}")
            }

            else -> log("未知 action：${task.action}")
        }
    }

    private fun log(msg: String) {
        DesktopLogger.info("TaskScheduler", msg)
    }
}