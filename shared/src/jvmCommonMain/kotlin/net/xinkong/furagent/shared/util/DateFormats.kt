package net.xinkong.furagent.shared.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 集中管理常用日期格式化器。
 *
 * SimpleDateFormat 非线程安全，原先各模块在方法内每次 new，
 * 本对象用 ThreadLocal 缓存，避免重复创建。
 */
object DateFormats {

    // ThreadLocal 缓存实例

    /** yyyy-MM-dd HH:mm:ss.SSS */
    private val tlFullMs = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    }

    /** yyyy-MM-dd */
    private val tlDate = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }

    /** yyyy-MM-dd HH:mm:ss */
    private val tlDateTime = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    /** yyyyMMdd_HHmmss_SSS */
    private val tlFileTs = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
    }

    /** HHmmss */
    private val tlHourTs = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HHmmss", Locale.getDefault())
    }

    /** yyyy-MM-dd HH:mm */
    private val tlDateTimeNoSec = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

    /** yyyy-MM-dd HH:mm:ss EEEE (带星期) */
    private val tlDateTimeWeekday = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", Locale.CHINA)
    }

    /** yyyy-MM-dd EEEE (带星期) */
    private val tlDateWeekday = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd EEEE", Locale.CHINA)
    }

    /** MM-dd HH:mm */
    private val tlShortDateTime = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }

    /** MM-dd HH:mm:ss */
    private val tlShortDateTimeSec = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    }

    /** HH:mm:ss */
    private val tlTime = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }

    /** HH:mm */
    private val tlTimeShort = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm", Locale.getDefault())
    }

    /** yyyyMMdd_HHmmss (无毫秒) */
    private val tlFileTsNoMs = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    }

    /** HH:mm:ss.SSS */
    private val tlTimeMs = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    }

    /** yyyy-MM-dd HH:mm:ss EEEE (带星期，中国 Locale) */
    private val tlDateTimeWeekdayCN = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd EEEE HH:mm:ss", Locale.CHINA)
    }

    // 格式化快捷方法

    fun formatFullMs(millis: Long): String = tlFullMs.get()!!.format(Date(millis))
    fun formatFullMs(): String = tlFullMs.get()!!.format(Date())

    fun formatDate(millis: Long): String = tlDate.get()!!.format(Date(millis))
    fun formatDate(): String = tlDate.get()!!.format(Date())

    fun formatDateTime(millis: Long): String = tlDateTime.get()!!.format(Date(millis))
    fun formatDateTime(): String = tlDateTime.get()!!.format(Date())

    fun formatFileTs(): String = tlFileTs.get()!!.format(Date())
    fun formatFileTs(millis: Long): String = tlFileTs.get()!!.format(Date(millis))

    fun formatHourTs(millis: Long): String = tlHourTs.get()!!.format(Date(millis))

    fun formatDateTimeNoSec(millis: Long): String = tlDateTimeNoSec.get()!!.format(Date(millis))
    fun formatDateTimeNoSec(): String = tlDateTimeNoSec.get()!!.format(Date())

    fun formatDateTimeWeekday(millis: Long): String = tlDateTimeWeekday.get()!!.format(Date(millis))

    fun formatDateWeekday(millis: Long): String = tlDateWeekday.get()!!.format(Date(millis))

    fun formatShortDateTime(millis: Long): String = tlShortDateTime.get()!!.format(Date(millis))

    fun formatShortDateTimeSec(millis: Long): String = tlShortDateTimeSec.get()!!.format(Date(millis))

    fun formatTime(millis: Long): String = tlTime.get()!!.format(Date(millis))
    fun formatTime(): String = tlTime.get()!!.format(Date())

    fun formatTimeShort(millis: Long): String = tlTimeShort.get()!!.format(Date(millis))

    fun formatFileTsNoMs(): String = tlFileTsNoMs.get()!!.format(Date())
    fun formatFileTsNoMs(millis: Long): String = tlFileTsNoMs.get()!!.format(Date(millis))

    fun formatTimeMs(millis: Long): String = tlTimeMs.get()!!.format(Date(millis))

    fun formatDateTimeWeekdayCN(millis: Long): String = tlDateTimeWeekdayCN.get()!!.format(Date(millis))

    // 公共 SDF 实例访问器（供需要 SimpleDateFormat 实例的调用方）
    fun fullMsFormatter(): SimpleDateFormat = tlFullMs.get()!!
    fun dateFormatter(): SimpleDateFormat = tlDate.get()!!
    fun dateTimeFormatter(): SimpleDateFormat = tlDateTime.get()!!
    fun fileTsFormatter(): SimpleDateFormat = tlFileTs.get()!!
    fun dateTimeNoSecFormatter(): SimpleDateFormat = tlDateTimeNoSec.get()!!
    fun dateTimeWeekdayFormatter(): SimpleDateFormat = tlDateTimeWeekday.get()!!
    fun dateWeekdayFormatter(): SimpleDateFormat = tlDateWeekday.get()!!
    fun shortDateTimeFormatter(): SimpleDateFormat = tlShortDateTime.get()!!
    fun shortDateTimeSecFormatter(): SimpleDateFormat = tlShortDateTimeSec.get()!!
    fun timeFormatter(): SimpleDateFormat = tlTime.get()!!
    fun timeShortFormatter(): SimpleDateFormat = tlTimeShort.get()!!
    fun fileTsNoMsFormatter(): SimpleDateFormat = tlFileTsNoMs.get()!!
    fun timeMsFormatter(): SimpleDateFormat = tlTimeMs.get()!!
    fun hourTsFormatter(): SimpleDateFormat = tlHourTs.get()!!
    fun dateTimeWeekdayCNFormatter(): SimpleDateFormat = tlDateTimeWeekdayCN.get()!!

    // 解析方法

    /** 解析 yyyy-MM-dd HH:mm:ss.SSS 格式的时间戳字符串 */
    fun parseFullMs(text: String): Long? = try {
        tlFullMs.get()!!.parse(text)?.time
    } catch (_: Exception) { null }

    /** 解析 yyyy-MM-dd HH:mm:ss 格式的时间戳字符串 */
    fun parseDateTime(text: String): Long? = try {
        tlDateTime.get()!!.parse(text)?.time
    } catch (_: Exception) { null }
}
