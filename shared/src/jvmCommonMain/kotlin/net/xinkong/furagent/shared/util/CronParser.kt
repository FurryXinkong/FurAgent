package net.xinkong.furagent.shared.util

import java.util.Calendar

/**
 * 标准 5 段 cron 解析（分 时 日 月 周）。
 *
 * Android ScheduleStore 和桌面 ScheduleStore 各有一份逐字相同的
 * nextCronTime / matchSpec / matchDow 实现（共约 180 行）。现统一到这里。
 *
 * 语法支持：
 *   *          任意值
 *   5          具体值
 *   1-5        范围
 *   1,3,5      列表
 *   *\/5        步长
 *   1-10/2     范围内步长
 *   周日既接受 0 也接受 7（内部统一归一化为 0=周日）
 *
 * 不支持：L / W / # / ? 等扩展语法。
 *
 * 与两端原有实现的行为一致：
 *   - 从 fromMs + 60_000L 开始（下一分钟）
 *   - 秒和毫秒归零
 *   - 最多向前搜 525_600 分钟（一年）
 *   - 找不到返回 null
 */
object CronParser {

    private val RE_WHITESPACE = Regex("""\s+""")

    /**
     * 计算下一次触发时间。
     *
     * @param expr 标准 5 段 cron："分 时 日 月 周"
     * @param fromMs 起始时间戳（毫秒）
     * @return 下一次触发的毫秒时间戳；表达式非法或一年内无匹配时返回 null
     */
    fun nextCronTime(expr: String, fromMs: Long): Long? {
        val parts = expr.trim().split(RE_WHITESPACE)
        if (parts.size < 5) return null

        val cal = Calendar.getInstance().apply {
            timeInMillis = fromMs + 60_000L
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        repeat(525_600) {
            val m = cal.get(Calendar.MINUTE)
            val h = cal.get(Calendar.HOUR_OF_DAY)
            val d = cal.get(Calendar.DAY_OF_MONTH)
            val mo = cal.get(Calendar.MONTH) + 1
            val dow = cal.get(Calendar.DAY_OF_WEEK) - 1

            if (matchSpec(parts[0], m, 0, 59) &&
                matchSpec(parts[1], h, 0, 23) &&
                matchSpec(parts[2], d, 1, 31) &&
                matchSpec(parts[3], mo, 1, 12) &&
                matchDow(parts[4], dow)
            ) {
                return cal.timeInMillis
            }
            cal.add(Calendar.MINUTE, 1)
        }
        return null
    }

    /**
     * 匹配一个数值字段（分 / 时 / 日 / 月）。
     */
    private fun matchSpec(spec: String, value: Int, min: Int, max: Int): Boolean {
        if (spec == "*") return true
        for (seg in spec.split(',')) {
            val s = seg.trim()
            if (s.isEmpty()) continue
            val stepParts = s.split('/')
            val range = stepParts[0]
            val step = if (stepParts.size > 1) stepParts[1].toIntOrNull() ?: 1 else 1
            if (step < 1) continue
            val (lo, hi) = when {
                range == "*" -> min to max
                range.contains('-') -> {
                    val ab = range.split('-')
                    val a = ab.getOrNull(0)?.toIntOrNull() ?: continue
                    val b = ab.getOrNull(1)?.toIntOrNull() ?: continue
                    a to b
                }
                else -> {
                    val v = range.toIntOrNull() ?: continue
                    v to v
                }
            }
            var x = lo
            while (x <= hi) {
                if (x == value) return true
                x += step
            }
        }
        return false
    }

    /**
     * 匹配星期字段。0 和 7 都表示周日。
     *
     * @param dow 0=周日，1=周一，…，6=周六
     */
    private fun matchDow(spec: String, dow: Int): Boolean {
        if (spec == "*") return true
        for (seg in spec.split(',')) {
            val s = seg.trim()
            if (s.isEmpty()) continue

            val stepParts = s.split('/')
            val range = stepParts[0]
            val step = if (stepParts.size > 1) stepParts[1].toIntOrNull() ?: 1 else 1

            val (lo, hi) = when {
                range == "*" -> 0 to 6
                range.contains('-') -> {
                    val ab = range.split('-')
                    val a = ab.getOrNull(0)?.toIntOrNull() ?: continue
                    val b = ab.getOrNull(1)?.toIntOrNull() ?: continue
                    a to b
                }
                else -> {
                    val v = range.toIntOrNull() ?: continue
                    v to v
                }
            }
            var x = lo
            while (x <= hi) {
                val norm = if (x == 7) 0 else x
                if (norm == dow) return true
                x += step
            }
        }
        return false
    }
}
