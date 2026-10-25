package net.xinkong.furagent.shared.util

import java.util.Calendar
import java.util.Locale

/**
 * 定时任务条件模型与评估逻辑（从两端 ScheduleStore 提取的公共部分）。
 *
 * TaskCondition / ConditionResult 数据类 + eval* 纯函数，
 * 不依赖 Android Context 或桌面端 PowerShell，两端共享。
 */

data class TaskCondition(
    val type: String,
    val operator: String = "eq",
    val value: String = "",
    val value2: String = ""
)

data class ConditionResult(
    val met: Boolean,
    val reason: String = ""
)

object ConditionEvaluator {

    // 通用条件评估（纯逻辑，无平台依赖）

    fun evalNumeric(actual: Double, cond: TaskCondition): ConditionResult {
        val target = cond.value.toDoubleOrNull()
            ?: return ConditionResult(false, "条件值不是数字：${cond.value}")
        val ok = when (cond.operator) {
            "eq" -> actual == target
            "ne" -> actual != target
            "gt" -> actual > target
            "lt" -> actual < target
            "gte" -> actual >= target
            "lte" -> actual <= target
            "between" -> {
                val target2 = cond.value2.toDoubleOrNull()
                    ?: return ConditionResult(false, "between 需要 value2")
                actual in target..target2
            }
            else -> return ConditionResult(false, "数值不支持的操作符：${cond.operator}")
        }
        return if (ok) ConditionResult(true)
        else ConditionResult(
            false,
            "${cond.type} 当前=$actual，不满足 ${cond.operator} ${cond.value}" +
                    if (cond.value2.isNotEmpty()) "..${cond.value2}" else ""
        )
    }

    /** "true/1/yes/是/on" 视为 true，其余 false。 */
    private val BOOL_TRUE_VALUES = setOf("true", "1", "yes", "是", "on")

    fun evalBool(actual: Boolean, cond: TaskCondition): ConditionResult {
        val target = cond.value.lowercase() in BOOL_TRUE_VALUES
        val ok = if (cond.operator == "ne") actual != target else actual == target
        return if (ok) ConditionResult(true)
        else ConditionResult(
            false,
            "${cond.type} 当前=$actual，期望${if (cond.operator == "ne") "不等于" else "等于"} $target"
        )
    }

    fun evalString(actual: String?, cond: TaskCondition): ConditionResult {
        val a = actual ?: ""
        val ok = when (cond.operator) {
            "eq" -> a == cond.value
            "ne" -> a != cond.value
            "contains" -> a.contains(cond.value)
            "matches" -> try {
                Regex(cond.value).containsMatchIn(a)
            } catch (_: Exception) { false }
            "in" -> cond.value.split(",").map { it.trim() }.contains(a)
            else -> a == cond.value
        }
        return if (ok) ConditionResult(true)
        else ConditionResult(false, "${cond.type} 当前='$a'，不满足 ${cond.operator} '${cond.value}'")
    }

    fun evalTimeBetween(cond: TaskCondition): ConditionResult {
        val parts = cond.value.split("-").map { it.trim() }
        if (parts.size != 2) return ConditionResult(false, "time_between 需要 HH:mm-HH:mm")
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val startMin = parseHm(parts[0]) ?: return ConditionResult(false, "无效起始 ${parts[0]}")
        val endMin = parseHm(parts[1]) ?: return ConditionResult(false, "无效结束 ${parts[1]}")
        val ok = if (startMin <= endMin) nowMin in startMin..endMin
        else nowMin !in (endMin + 1)..<startMin
        val hh = String.format(Locale.US, "%02d:%02d", nowMin / 60, nowMin % 60)
        return if (ok) ConditionResult(true)
        else ConditionResult(false, "当前 $hh 不在 ${cond.value} 内")
    }

    private fun parseHm(s: String): Int? {
        return try {
            val p = s.split(":")
            p[0].toInt() * 60 + p[1].toInt()
        } catch (_: Exception) { null }
    }

    fun evalWeekdayIn(cond: TaskCondition): ConditionResult {
        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1
        val days = cond.value.split(",").mapNotNull { it.trim().toIntOrNull() }
        val ok = days.contains(today)
        val names = listOf("日", "一", "二", "三", "四", "五", "六")
        return if (ok) ConditionResult(true)
        else ConditionResult(false, "今天是周${names[today]}，不在 ${cond.value} 内")
    }
}
