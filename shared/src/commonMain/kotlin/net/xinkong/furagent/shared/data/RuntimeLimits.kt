package net.xinkong.furagent.shared.data

/**
 * 跨端共享的运行时限制常量（P1-10）。
 *
 * 抽离原因：这些常量原先在桌面端和 Android 端各有一份，改一处要动两个文件，
 * 且容易出现数值漂移（桌面端 20、Android 端 25 这种）。统一到 shared 后，
 * 任一端修改都立即生效，且不可能出现不一致。
 */

/**
 * 子会话（子代理）限制。
 *
 * 双端共享语义：子代理不继承用户的 maxToolCallLoops，
 * 否则并行派 4 个子代理 × 100 轮 = 一次就烧掉整月额度。
 */
object SubSessionLimits {
    /** 子代理最多跑多少轮。 */
    const val SUB_MAX_LOOPS = 20

    /** 同时最多几个子代理并行。 */
    const val MAX_PARALLEL_SUB = 4
}

/**
 * 定时任务重复间隔限制（秒）。
 *
 * 覆盖桌面端 TaskScheduler 和 Android 端 AlarmManager 两个调度器。
 */
object ScheduleLimits {
    /** 最小间隔 1 秒。 */
    const val MIN_INTERVAL_SECONDS = 1

    /** 最大间隔 30 天。 */
    const val MAX_INTERVAL_SECONDS = 60 * 60 * 24 * 30
}
