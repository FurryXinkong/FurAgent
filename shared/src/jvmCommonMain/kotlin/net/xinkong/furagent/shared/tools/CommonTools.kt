package net.xinkong.furagent.shared.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.time.Duration.Companion.milliseconds

object CommonTools {

    private const val MAX_BINARY_FILE_SIZE = 10L * 1024 * 1024
    private const val MAX_TEXT_FILE_SIZE = 1L * 1024 * 1024
    private const val MAX_SLEEP_MS = 300_000L

    /**
     * 【大结果落盘】超过该字节数的工具结果会被写入临时文件，
     * 上下文里只留摘要 + 文件路径，避免大结果常驻上下文、每轮重发。
     * 16KB 约等于 400 行代码，正常“看一眼”的结果不会触发。
     *
     * 【为什么从 8KB 提到 16KB】8KB 太容易触发，导致模型频繁“读文件→落盘→
     * 再读落盘文件→又落盘”的套娃，反而更费 token。16KB 能覆盖绝大多数
     * “看一眼”的场景，真正的大结果才落盘。
     */
    private const val SPILL_THRESHOLD_BYTES = 16 * 1024

    /** 落盘后摘要里保留的行数。 */
    private const val SPILL_PREVIEW_LINES = 30

    /** 只有这三个“大结果工具”才做落盘，其余工具结果小，落盘反而添乱。 */
    private val SPILL_TOOLS = setOf("grep", "list_files", "read_file")

    /**
     * 落盘文件路径特征：位于 temp 目录且文件名以 tool_ 开头。
     * 命中该特征的 read_file 结果不再二次落盘，根治“读落盘文件又落盘”的套娃。
     */
    private val SPILL_FILE_MARKER = Regex("[/\\\\]temp[/\\\\]tool_[^/\\\\]+\\.txt$")

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private val httpClientByTimeout = ConcurrentHashMap<Int, OkHttpClient>()

    private fun getHttpClientForTimeout(timeoutSec: Int): OkHttpClient =
        httpClientByTimeout.getOrPut(timeoutSec) {
            httpClient.newBuilder()
                .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                .build()
        }

    suspend fun execute(name: String, args: JSONObject, host: ToolHost): ToolResult? {
        val result = when (name) {
            "time" -> doTime(args, host)
            "calculate" -> doCalculate(args)
            "convert" -> doConvert(args)
            "statistics" -> doStatistics(args)
            "text" -> doText(args)
            "json" -> doJson(args)
            "hash" -> doHash(args, host)
            "list_files" -> doListFiles(args, host)
            "read_file" -> doReadFile(args, host)
            "grep" -> doGrep(args, host)
            "write_file" -> doWriteFile(args, host)
            "http_request" -> doHttpRequest(args)
            "env_store" -> doEnvStore(args, host)
            "sleep" -> doSleep(args)
            else -> null
        } ?: return null

        // 【大结果落盘】只对三个大结果工具生效；失败静默降级（返回原文）。
        return if (name in SPILL_TOOLS) maybeSpill(name, args, result, host) else result
    }

    /**
     * 结果过大时写入临时文件，上下文里只留摘要 + 路径。
     *
     * 【为什么】大结果一旦进上下文就会常驻，之后每轮都要重发一遍，
     * token 成本成倍增加。落盘后模型只看到摘要，需要全文时再用
     * read_file 读那个路径，按需付费。
     *
     * 【降级】任何一步失败（路径不允许、写盘失败）都直接返回原文，
     * 绝不因为落盘失败而让工具报错。
     */
    private suspend fun maybeSpill(
        toolName: String,
        args: JSONObject,
        result: ToolResult,
        host: ToolHost
    ): ToolResult {
        if (!result.success) return result

        // 【防套娃】如果这次读的本来就是落盘文件（temp/tool_*.txt），
        // 说明模型正在按摘要指引读全文，此时绝不能再落盘，否则会
        // “读落盘文件→又落盘→再读→再落盘”无限套娃，token 反而暴涨。
        if (toolName == "read_file") {
            val rawPath = args.optString("path", "")
            if (SPILL_FILE_MARKER.containsMatchIn(rawPath)) return result
        }

        val text = result.output
        if (text.toByteArray(Charsets.UTF_8).size <= SPILL_THRESHOLD_BYTES) return result

        return try {
            val dir = host.resolvePath("~/.furagent/temp") ?: return result
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date())
            val filePath = "$dir/tool_${toolName}_$stamp.txt"
            if (!host.ensureParentDir(filePath)) return result
            if (!host.writeTextFile(filePath, text)) return result

            val lines = text.lines()
            val preview = lines.take(SPILL_PREVIEW_LINES).joinToString("\n")
            val totalLines = lines.size
            val totalKb = text.toByteArray(Charsets.UTF_8).size / 1024

            val summary = buildString {
                append("【结果过大，已落盘】共 $totalLines 行 / ${totalKb}KB，")
                append("上下文只保留前 $SPILL_PREVIEW_LINES 行。\n")
                append("要看全文：read_file(path=\"$filePath\", offset=1, limit=200)\n")
                append("（该文件在 ~/.furagent/temp/ 下，可随时删除）\n")
                append("---- 前 $SPILL_PREVIEW_LINES 行预览 ----\n")
                append(preview)
            }
            ToolResult.ok(summary)
        } catch (_: Exception) {
            result
        }
    }

    // ============================================================
    // 1. time
    // ============================================================

    private fun doTime(args: JSONObject, host: ToolHost): ToolResult {
        return when (val op = args.optString("op", "now").lowercase(Locale.ROOT)) {
            "now" -> {
                val now = host.now()
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", Locale.CHINA)
                val tz = TimeZone.getDefault()
                ToolResult.ok(
                    buildString {
                        append("当前时间：").append(sdf.format(Date(now))).append("\n")
                        append("时区：").append(tz.id).append("\n")
                        append("时间戳（毫秒）：").append(now)
                    }
                )
            }
            "diff", "add", "info" -> doTimeDateOp(op, args)
            "to_date", "to_timestamp" -> doTimeTimestampOp(op, args)
            else -> ToolResult.fail("op 只能是 now / diff / add / info / to_date / to_timestamp")
        }
    }

    private fun parseDate(s: String): Date? {
        val sdf1 = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val sdf2 = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return try {
            sdf2.parse(s) ?: sdf1.parse(s)
        } catch (_: Exception) {
            try { sdf1.parse(s) } catch (_: Exception) { null }
        }
    }

    private fun doTimeDateOp(op: String, args: JSONObject): ToolResult {
        val date1Str = args.optString("date1", "").trim()
        if (date1Str.isBlank()) return ToolResult.fail("未提供 date1")

        return when (op) {
            "diff" -> {
                val d2 = args.optString("date2", "").trim()
                if (d2.isBlank()) return ToolResult.fail("diff 模式需要 date2")
                val a = parseDate(date1Str) ?: return ToolResult.fail("无法解析 $date1Str")
                val b = parseDate(d2) ?: return ToolResult.fail("无法解析 $d2")
                val diffMs = kotlin.math.abs(b.time - a.time)
                ToolResult.ok(
                    "$date1Str 与 $d2 相差：\n" +
                            "- ${diffMs / 86_400_000L} 天\n" +
                            "- ${diffMs / 3_600_000L} 小时\n" +
                            "- ${diffMs / 60_000L} 分钟"
                )
            }
            "add" -> {
                val days = args.optInt("days", 0)
                val a = parseDate(date1Str) ?: return ToolResult.fail("无法解析 $date1Str")
                val cal = Calendar.getInstance().apply {
                    time = a
                    add(Calendar.DAY_OF_YEAR, days)
                }
                val df = SimpleDateFormat("yyyy-MM-dd EEEE", Locale.CHINA)
                ToolResult.ok("$date1Str 加 $days 天 = ${df.format(cal.time)}")
            }
            "info" -> {
                val a = parseDate(date1Str) ?: return ToolResult.fail("无法解析 $date1Str")
                val df = SimpleDateFormat("yyyy-MM-dd EEEE HH:mm:ss", Locale.CHINA)
                val dayOfYear = SimpleDateFormat("D", Locale.getDefault()).format(a)
                val weekOfYear = SimpleDateFormat("w", Locale.getDefault()).format(a)
                ToolResult.ok("日期：${df.format(a)}\n年内第 $dayOfYear 天\n年内第 $weekOfYear 周")
            }
            else -> ToolResult.fail("不支持的日期操作：$op")
        }
    }

    private fun doTimeTimestampOp(op: String, args: JSONObject): ToolResult {
        val value = args.optString("value", "").trim()
        if (value.isBlank()) return ToolResult.fail("未提供 value")
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sdf.timeZone = TimeZone.getDefault()

        return when (op) {
            "to_date" -> {
                val ts = value.toLongOrNull() ?: return ToolResult.fail("无法解析时间戳 $value")
                val ms = if (value.length >= 13) ts else ts * 1000L
                ToolResult.ok("时间戳 $value → ${sdf.format(Date(ms))}\n（时区：${TimeZone.getDefault().id}）")
            }
            "to_timestamp" -> {
                val d = try { sdf.parse(value) } catch (_: Exception) { null }
                    ?: return ToolResult.fail("无法解析日期 $value，请用 yyyy-MM-dd HH:mm:ss")
                ToolResult.ok("日期 $value →\n秒：${d.time / 1000}\n毫秒：${d.time}")
            }
            else -> ToolResult.fail("不支持的操作：$op")
        }
    }

    // ============================================================
    // 2. calculate
    // ============================================================

    private fun doCalculate(args: JSONObject): ToolResult {
        val type = args.optString("type", "expr").lowercase(Locale.ROOT)
        return when (type) {
            "expr" -> doCalcExpr(args)
            "random" -> doCalcRandom(args)
            else -> ToolResult.fail("type 只能是 expr 或 random")
        }
    }

    private fun doCalcExpr(args: JSONObject): ToolResult {
        val rawExpr = args.optString("expression", "").trim()
        if (rawExpr.isBlank()) return ToolResult.fail("未提供 expression")
        val precision = args.optInt("precision", 10).coerceIn(0, 30)

        val exprs = rawExpr.split('\n', ';').map { it.trim() }.filter { it.isNotBlank() }
        if (exprs.isEmpty()) return ToolResult.fail("未提供有效表达式")

        val sb = StringBuilder()
        for (e in exprs) {
            try {
                val v = evalBigDecimalExpr(e)
                sb.append("$e = ${formatBigDecimal(v, precision)}\n")
            } catch (ex: Exception) {
                sb.append("$e = [错误：${ex.message}]\n")
            }
        }
        return ToolResult.ok(sb.toString().trim())
    }

    private fun doCalcRandom(args: JSONObject): ToolResult {
        val randomType = args.optString("random_type", "int").lowercase(Locale.ROOT)
        return try {
            when (randomType) {
                "int" -> {
                    val min = args.optInt("min", 0)
                    val max = args.optInt("max", 100)
                    if (min > max) return ToolResult.fail("min 不能大于 max")
                    ToolResult.ok("随机整数（$min..$max）：${(min..max).random()}")
                }
                "float" -> {
                    val min = args.optDouble("min", 0.0)
                    val max = args.optDouble("max", 1.0)
                    if (min >= max) return ToolResult.fail("min 必须小于 max")
                    ToolResult.ok("随机小数（$min..$max）：${formatDouble(min + Math.random() * (max - min))}")
                }
                "string" -> {
                    val len = args.optInt("length", 16).coerceIn(1, 4096)
                    val charset = args.optString("charset", "alnum").lowercase(Locale.ROOT)
                    val chars = when (charset) {
                        "alpha" -> "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                        "digit" -> "0123456789"
                        "hex" -> "0123456789abcdef"
                        else -> "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
                    }
                    val sb = StringBuilder()
                    repeat(len) { sb.append(chars.random()) }
                    ToolResult.ok("随机字符串（长度 $len，字符集 $charset）：\n$sb")
                }
                "uuid" -> ToolResult.ok("UUID：${UUID.randomUUID()}")
                "choice" -> {
                    val optionsRaw = args.optString("options", "").trim()
                    if (optionsRaw.isBlank()) return ToolResult.fail("choice 模式需要 options")
                    val opts = optionsRaw.split(',', '，', '|').map { it.trim() }.filter { it.isNotBlank() }
                    if (opts.isEmpty()) return ToolResult.fail("options 为空")
                    ToolResult.ok("从 ${opts.size} 个选项中随机选出：${opts.random()}")
                }
                else -> ToolResult.fail("不支持的 random_type：$randomType")
            }
        } catch (e: Exception) {
            ToolResult.fail("生成失败：${e.message}")
        }
    }

    private fun formatBigDecimal(v: BigDecimal, precision: Int): String {
        if (v.compareTo(BigDecimal.ZERO) == 0) return "0"
        val stripped = v.stripTrailingZeros()
        if (stripped.scale() <= 0) return stripped.toBigInteger().toString()
        val target = stripped.setScale(
            maxOf(precision, stripped.scale()),
            RoundingMode.HALF_UP
        )
        var s = target.toPlainString()
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return if (s.isEmpty() || s == "-") "0" else s
    }

    private fun evalBigDecimalExpr(expr: String): BigDecimal {
        class Parser(val s: String) {
            var i = 0
            fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

            fun parseExpr(): BigDecimal {
                var v = parseTerm()
                while (true) {
                    ws(); if (i >= s.length) break
                    when (s[i]) {
                        '+' -> { i++; v = v.add(parseTerm()) }
                        '-' -> { i++; v = v.subtract(parseTerm()) }
                        else -> break
                    }
                }
                return v
            }

            fun parseTerm(): BigDecimal {
                var v = parseUnary()
                while (true) {
                    ws(); if (i >= s.length) break
                    when (s[i]) {
                        '*' -> { i++; v = v.multiply(parseUnary()) }
                        '/' -> {
                            i++
                            val r = parseUnary()
                            if (r.compareTo(BigDecimal.ZERO) == 0) throw ArithmeticException("除数为零")
                            v = v.divide(r, 30, RoundingMode.HALF_UP)
                        }
                        '%' -> {
                            i++
                            val r = parseUnary()
                            if (r.compareTo(BigDecimal.ZERO) == 0) throw ArithmeticException("取模除数为零")
                            v = v.remainder(r)
                        }
                        else -> break
                    }
                }
                return v
            }

            fun parseUnary(): BigDecimal {
                ws()
                if (i < s.length && s[i] == '-') { i++; return parseUnary().negate() }
                if (i < s.length && s[i] == '+') { i++; return parseUnary() }
                return parsePow()
            }

            fun parsePow(): BigDecimal {
                val base = parsePrimary()
                ws()
                if (i < s.length && s[i] == '^') {
                    i++
                    val exp = parseUnary()
                    val expInt = try { exp.intValueExact() } catch (_: Exception) { null }
                    return if (expInt != null && expInt in 0..10000) base.pow(expInt)
                    else BigDecimal(base.toDouble().pow(exp.toDouble()))
                }
                return base
            }

            fun parsePrimary(): BigDecimal {
                ws()
                if (i < s.length && s[i] == '(') {
                    i++
                    val v = parseExpr()
                    ws()
                    if (i >= s.length || s[i] != ')') throw IllegalArgumentException("缺少右括号")
                    i++
                    ws()
                    if (i < s.length && s[i] == '%') {
                        val save = i
                        i++
                        ws()
                        if (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == '(')) {
                            throw IllegalArgumentException(
                                "'%' 是百分号后缀（如 50% = 0.5），不是取模运算符。" +
                                        "取模请写成 x - (x / y) * y"
                            )
                        }
                        i = save + 1
                        return v.divide(BigDecimal(100), 30, RoundingMode.HALF_UP)
                    }
                    return v
                }

                if (i < s.length && (s[i].isDigit() || s[i] == '.')) {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                        val save = i
                        i++
                        if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                        var hasDigit = false
                        while (i < s.length && s[i].isDigit()) { i++; hasDigit = true }
                        if (!hasDigit) i = save
                    }
                    val numStr = s.substring(start, i)
                    var v = try { BigDecimal(numStr) }
                    catch (e: Exception) { throw IllegalArgumentException("无效数字 $numStr") }
                    ws()
                    if (i < s.length && s[i] == '%') {
                        val save = i
                        i++
                        ws()
                        if (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == '(')) {
                            throw IllegalArgumentException(
                                "'$numStr%' 后面跟了数字/括号，" +
                                        "'%' 在 FurAgent 中是百分号后缀（如 50% = 0.5），" +
                                        "不是取模运算符。取模请写成 x - (x / y) * y"
                            )
                        }
                        i = save + 1
                        v = v.divide(BigDecimal(100), 30, RoundingMode.HALF_UP)
                    }
                    return v
                }

                if (i < s.length && (s[i].isLetter() || s[i] == '_')) {
                    val start = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                    val name = s.substring(start, i).lowercase()
                    ws()
                    if (i < s.length && s[i] == '(') {
                        i++
                        val arg = parseExpr()
                        ws()
                        if (i >= s.length || s[i] != ')') {
                            throw IllegalArgumentException("函数 $name 缺少右括号")
                        }
                        i++
                        return applyFunction(name, arg)
                    }
                    return when (name) {
                        "pi", "π" -> BigDecimal(Math.PI)
                        "e" -> BigDecimal(Math.E)
                        else -> throw IllegalArgumentException("未知标识符：$name")
                    }
                }

                throw IllegalArgumentException("位置 $i 处无法解析")
            }

            fun applyFunction(name: String, arg: BigDecimal): BigDecimal {
                val d = arg.toDouble()
                return when (name) {
                    "sqrt" -> {
                        if (arg < BigDecimal.ZERO) throw ArithmeticException("sqrt 参数不能为负")
                        BigDecimal(sqrt(d))
                    }
                    "abs" -> arg.abs()
                    "sin" -> BigDecimal(sin(Math.toRadians(d)))
                    "cos" -> BigDecimal(cos(Math.toRadians(d)))
                    "tan" -> BigDecimal(tan(Math.toRadians(d)))
                    "asin" -> BigDecimal(Math.toDegrees(asin(d)))
                    "acos" -> BigDecimal(Math.toDegrees(acos(d)))
                    "atan" -> BigDecimal(Math.toDegrees(atan(d)))
                    "ln" -> BigDecimal(ln(d))
                    "log" -> BigDecimal(log10(d))
                    "exp" -> BigDecimal(exp(d))
                    "floor" -> arg.setScale(0, RoundingMode.FLOOR)
                    "ceil" -> arg.setScale(0, RoundingMode.CEILING)
                    "round" -> arg.setScale(0, RoundingMode.HALF_UP)
                    else -> throw IllegalArgumentException("未知函数：$name")
                }
            }
        }

        val p = Parser(expr)
        val v = p.parseExpr()
        p.ws()
        if (p.i < expr.length) {
            throw IllegalArgumentException("位置 ${p.i} 处有意外字符 '${expr[p.i]}'")
        }
        return v
    }

    private fun formatDouble(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return v.toString()
        if (v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e15) return v.toLong().toString()
        val s = String.format(Locale.US, "%.10f", v).trimEnd('0').trimEnd('.')
        return s.ifEmpty { "0" }
    }

    // ============================================================
    // 3. convert
    // ============================================================

    private fun doConvert(args: JSONObject): ToolResult {
        val type = args.optString("type", "").lowercase(Locale.ROOT)
        return when (type) {
            "unit" -> doConvertUnit(args)
            "base" -> doConvertBase(args)
            "color" -> doConvertColor(args)
            else -> ToolResult.fail("type 只能是 unit / base / color")
        }
    }

    private fun doConvertUnit(args: JSONObject): ToolResult {
        val value = args.optString("value", "").trim().toDoubleOrNull()
            ?: return ToolResult.fail("value 必须是数字")
        val from = args.optString("from", "").trim()
        val to = args.optString("to", "").trim()
        if (from.isBlank() || to.isBlank()) return ToolResult.fail("需提供 from 和 to")

        val tempAliases = mapOf(
            "c" to "C", "°c" to "C", "摄氏度" to "C", "摄氏" to "C",
            "f" to "F", "°f" to "F", "华氏度" to "F", "华氏" to "F",
            "k" to "K", "开尔文" to "K", "开氏度" to "K"
        )
        val fromT = tempAliases[from.lowercase()]
        val toT = tempAliases[to.lowercase()]
        if (fromT != null || toT != null) {
            if (fromT == null || toT == null) return ToolResult.fail("温度单位必须都是 C/F/K")
            val c = when (fromT) {
                "C" -> value
                "F" -> (value - 32) * 5.0 / 9.0
                "K" -> value - 273.15
                else -> value
            }
            val result = when (toT) {
                "C" -> c
                "F" -> c * 9.0 / 5.0 + 32
                "K" -> c + 273.15
                else -> c
            }
            return ToolResult.ok("${formatDouble(value)} °$fromT = ${formatDouble(result)} °$toT")
        }

        data class U(val category: String, val factor: Double)
        val units = mapOf(
            "m" to U("length", 1.0), "米" to U("length", 1.0),
            "km" to U("length", 1000.0), "千米" to U("length", 1000.0), "公里" to U("length", 1000.0),
            "cm" to U("length", 0.01), "厘米" to U("length", 0.01),
            "mm" to U("length", 0.001), "毫米" to U("length", 0.001),
            "um" to U("length", 1e-6), "μm" to U("length", 1e-6), "微米" to U("length", 1e-6),
            "nm" to U("length", 1e-9), "纳米" to U("length", 1e-9),
            "mile" to U("length", 1609.344), "mi" to U("length", 1609.344), "英里" to U("length", 1609.344),
            "yard" to U("length", 0.9144), "yd" to U("length", 0.9144), "码" to U("length", 0.9144),
            "foot" to U("length", 0.3048), "ft" to U("length", 0.3048), "英尺" to U("length", 0.3048),
            "inch" to U("length", 0.0254), "in" to U("length", 0.0254), "英寸" to U("length", 0.0254),
            "kg" to U("weight", 1.0), "千克" to U("weight", 1.0), "公斤" to U("weight", 1.0),
            "g" to U("weight", 0.001), "克" to U("weight", 0.001),
            "mg" to U("weight", 1e-6), "毫克" to U("weight", 1e-6),
            "ton" to U("weight", 1000.0), "t" to U("weight", 1000.0), "吨" to U("weight", 1000.0),
            "jin" to U("weight", 0.5), "斤" to U("weight", 0.5),
            "liang" to U("weight", 0.05), "两" to U("weight", 0.05),
            "lb" to U("weight", 0.45359237), "磅" to U("weight", 0.45359237),
            "oz" to U("weight", 0.028349523125), "盎司" to U("weight", 0.028349523125),
            "b" to U("storage", 1.0), "byte" to U("storage", 1.0), "字节" to U("storage", 1.0),
            "kb" to U("storage", 1024.0), "千字节" to U("storage", 1024.0),
            "mb" to U("storage", 1048576.0), "兆字节" to U("storage", 1048576.0),
            "gb" to U("storage", 1073741824.0), "吉字节" to U("storage", 1073741824.0),
            "tb" to U("storage", 1099511627776.0), "太字节" to U("storage", 1099511627776.0),
            "s" to U("time", 1.0), "sec" to U("time", 1.0), "秒" to U("time", 1.0),
            "ms" to U("time", 0.001), "毫秒" to U("time", 0.001),
            "min" to U("time", 60.0), "分钟" to U("time", 60.0),
            "h" to U("time", 3600.0), "hr" to U("time", 3600.0), "hour" to U("time", 3600.0), "小时" to U("time", 3600.0),
            "d" to U("time", 86400.0), "day" to U("time", 86400.0), "天" to U("time", 86400.0),
            "week" to U("time", 604800.0), "周" to U("time", 604800.0),
            "m2" to U("area", 1.0), "平方米" to U("area", 1.0),
            "km2" to U("area", 1e6), "平方千米" to U("area", 1e6), "平方公里" to U("area", 1e6),
            "cm2" to U("area", 1e-4), "平方厘米" to U("area", 1e-4),
            "mu" to U("area", 666.6667), "亩" to U("area", 666.6667),
            "ha" to U("area", 10000.0), "公顷" to U("area", 10000.0)
        )

        val fromU = units[from.lowercase()] ?: return ToolResult.fail("未知单位 $from")
        val toU = units[to.lowercase()] ?: return ToolResult.fail("未知单位 $to")
        if (fromU.category != toU.category) {
            return ToolResult.fail("$from 和 $to 不属于同一类别（${fromU.category} vs ${toU.category}）")
        }
        val baseValue = value * fromU.factor
        val result = baseValue / toU.factor
        return ToolResult.ok("${formatDouble(value)} $from = ${formatDouble(result)} $to")
    }

    private fun doConvertBase(args: JSONObject): ToolResult {
        val raw = args.optString("value", "").trim()
        var fromBase = args.optInt("from_base", -1)
        var toBase = args.optInt("to_base", -1)
        if (raw.isBlank()) return ToolResult.fail("未提供 value")

        var v = raw
        if (fromBase !in 2..36) {
            when {
                v.startsWith("0x", true) -> { fromBase = 16; v = v.substring(2) }
                v.startsWith("0b", true) -> { fromBase = 2; v = v.substring(2) }
                v.startsWith("0o", true) -> { fromBase = 8; v = v.substring(2) }
                else -> fromBase = 10
            }
        }
        if (toBase !in 2..36) toBase = 10

        return try {
            val big = BigInteger(v.replace(" ", "").replace("_", ""), fromBase)
            val result = big.toString(toBase).uppercase(Locale.ROOT)
            val prefix = when (toBase) { 2 -> "0b"; 8 -> "0o"; 16 -> "0x"; else -> "" }
            if (prefix.isNotBlank()) {
                ToolResult.ok("$fromBase 进制的 $raw = $toBase 进制的 $result\n带前缀：${prefix}${result.lowercase()}")
            } else {
                ToolResult.ok("$fromBase 进制的 $raw = $toBase 进制的 $result")
            }
        } catch (e: Exception) {
            ToolResult.fail("无法解析 $raw 为 $fromBase 进制（${e.message}）")
        }
    }

    private fun doConvertColor(args: JSONObject): ToolResult {
        val input = args.optString("input", "").trim()
        if (input.isBlank()) return ToolResult.fail("未提供 input")
        val rgb = parseColorInput(input) ?: return ToolResult.fail("无法解析颜色：$input")
        val (r, g, b) = rgb
        val hex = String.format(Locale.US, "#%02X%02X%02X", r, g, b)
        val (h, s, l) = rgbToHsl(r, g, b)
        return ToolResult.ok(
            buildString {
                append("输入：$input\n")
                append("HEX：$hex\n")
                append("RGB：rgb($r, $g, $b)\n")
                append("HSL：hsl(${h.toInt()}, ${(s * 100).toInt()}%, ${(l * 100).toInt()}%)\n")
                append("Android：0xFF${String.format(Locale.US, "%02X%02X%02X", r, g, b)}")
            }
        )
    }

    private fun parseColorInput(input: String): Triple<Int, Int, Int>? {
        val t = input.trim()
        if (t.startsWith("#")) {
            val hex = t.substring(1)
            return try {
                when (hex.length) {
                    3 -> Triple(
                        hex[0].toString().repeat(2).toInt(16),
                        hex[1].toString().repeat(2).toInt(16),
                        hex[2].toString().repeat(2).toInt(16)
                    )
                    6 -> Triple(
                        hex.substring(0, 2).toInt(16),
                        hex.substring(2, 4).toInt(16),
                        hex.substring(4, 6).toInt(16)
                    )
                    8 -> Triple(
                        hex.substring(2, 4).toInt(16),
                        hex.substring(4, 6).toInt(16),
                        hex.substring(6, 8).toInt(16)
                    )
                    else -> null
                }
            } catch (_: Exception) { null }
        }
        val rgbMatch = Regex("""rgb\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)""", RegexOption.IGNORE_CASE)
            .matchEntire(t)
        if (rgbMatch != null) {
            return try {
                Triple(
                    rgbMatch.groupValues[1].toInt().coerceIn(0, 255),
                    rgbMatch.groupValues[2].toInt().coerceIn(0, 255),
                    rgbMatch.groupValues[3].toInt().coerceIn(0, 255)
                )
            } catch (_: Exception) { null }
        }
        return null
    }

    private fun rgbToHsl(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val max = maxOf(rf, gf, bf); val min = minOf(rf, gf, bf)
        val l = (max + min) / 2f
        if (max == min) return Triple(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        val h = when (max) {
            rf -> ((gf - bf) / d + (if (gf < bf) 6f else 0f)) * 60f
            gf -> ((bf - rf) / d + 2f) * 60f
            else -> ((rf - gf) / d + 4f) * 60f
        }
        return Triple(h, s, l)
    }

    // ============================================================
    // 4. statistics
    // ============================================================

    private fun doStatistics(args: JSONObject): ToolResult {
        val raw = args.optString("numbers", "").trim()
        if (raw.isBlank()) return ToolResult.fail("未提供 numbers")
        val parts = raw.split(',', ' ', '\n', '\t', ';').map { it.trim() }.filter { it.isNotBlank() }
        val nums = parts.mapNotNull { try { it.toDouble() } catch (_: Exception) { null } }
        if (nums.isEmpty()) return ToolResult.fail("没有有效数字")
        if (nums.size != parts.size) return ToolResult.fail("部分项不是数字（${parts.size - nums.size} 项无效）")

        val count = nums.size
        val sum = nums.sum()
        val mean = sum / count
        val sorted = nums.sorted()
        val median = if (count % 2 == 1) sorted[count / 2]
        else (sorted[count / 2 - 1] + sorted[count / 2]) / 2.0
        val min = sorted.first()
        val max = sorted.last()
        val range = max - min
        val variance = if (count > 1) nums.sumOf { (it - mean) * (it - mean) } / (count - 1) else 0.0
        val stddev = sqrt(variance)

        return ToolResult.ok(
            buildString {
                append("个数：$count\n")
                append("总和：${formatDouble(sum)}\n")
                append("均值：${formatDouble(mean)}\n")
                append("中位数：${formatDouble(median)}\n")
                append("最小：${formatDouble(min)}\n")
                append("最大：${formatDouble(max)}\n")
                append("极差：${formatDouble(range)}\n")
                append("样本方差：${formatDouble(variance)}\n")
                append("样本标准差：${formatDouble(stddev)}")
            }
        )
    }

    // ============================================================
    // 5. text
    // ============================================================

    private fun doText(args: JSONObject): ToolResult {
        val op = args.optString("op", "").lowercase(Locale.ROOT)
        return when (op) {
            "regex" -> doTextRegex(args)
            "diff" -> doTextDiff(args)
            "url_encode" -> doUrlCodec(args, encode = true)
            "url_decode" -> doUrlCodec(args, encode = false)
            else -> ToolResult.fail("op 只能是 regex / diff / url_encode / url_decode")
        }
    }

    private fun doTextRegex(args: JSONObject): ToolResult {
        val text = args.optString("text", "")
        val pattern = args.optString("pattern", "")
        val mode = args.optString("mode", "find").lowercase(Locale.ROOT)
        val replacement = args.optString("replacement", "")
        // limit 默认 0 = 不限。用户可显式传正整数限制
        val limit = args.optInt("limit", 0).coerceAtLeast(0)
        if (pattern.isBlank()) return ToolResult.fail("未提供 pattern")

        val regex = try { Regex(pattern) } catch (e: Exception) {
            return ToolResult.fail("正则语法错误（${e.message}）")
        }

        return try {
            when (mode) {
                "find" -> {
                    val m = regex.find(text) ?: return ToolResult.ok("未匹配到内容")
                    val groups = m.groupValues
                    ToolResult.ok(
                        buildString {
                            append("匹配到：${m.value}\n")
                            append("位置：${m.range.first}..${m.range.last}\n")
                            if (groups.size > 1) {
                                append("捕获组：\n")
                                for (i in 1 until groups.size) append("  $i: ${groups[i]}\n")
                            }
                        }.trim()
                    )
                }
                "match_all" -> {
                    val seq = regex.findAll(text)
                    val all = if (limit > 0) seq.take(limit).toList() else seq.toList()
                    if (all.isEmpty()) return ToolResult.ok("未匹配到内容")
                    ToolResult.ok(
                        buildString {
                            append("共 ${all.size} 个匹配：\n")
                            all.forEachIndexed { i, m ->
                                append("${i + 1}. [${m.range.first}..${m.range.last}] ${m.value}\n")
                            }
                        }.trim()
                    )
                }
                "replace" -> {
                    val count = regex.findAll(text).count()
                    val out = regex.replace(text, replacement)
                    ToolResult.ok("替换了 $count 处。\n结果：\n$out")
                }
                "split" -> {
                    val parts = regex.split(text)
                    ToolResult.ok("分割为 ${parts.size} 段：\n${parts.joinToString("\n")}")
                }
                else -> ToolResult.fail("不支持的模式 $mode")
            }
        } catch (e: Exception) {
            ToolResult.fail("处理失败：${e.message}")
        }
    }

    private fun doTextDiff(args: JSONObject): ToolResult {
        val a = args.optString("text1", "")
        val b = args.optString("text2", "")
        if (a.isEmpty() && b.isEmpty()) return ToolResult.fail("两个文本都为空")

        val linesA = a.split('\n')
        val linesB = b.split('\n')
        val m = linesA.size
        val n = linesB.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) for (j in 1..n) {
            dp[i][j] = if (linesA[i - 1] == linesB[j - 1]) dp[i - 1][j - 1] + 1
            else maxOf(dp[i - 1][j], dp[i][j - 1])
        }

        var i = m; var j = n
        val result = mutableListOf<String>()
        while (i > 0 && j > 0) {
            if (linesA[i - 1] == linesB[j - 1]) {
                result.add(0, "  ${linesA[i - 1]}")
                i--; j--
            } else if (dp[i - 1][j] >= dp[i][j - 1]) {
                result.add(0, "- ${linesA[i - 1]}")
                i--
            } else {
                result.add(0, "+ ${linesB[j - 1]}")
                j--
            }
        }
        while (i > 0) { result.add(0, "- ${linesA[i - 1]}"); i-- }
        while (j > 0) { result.add(0, "+ ${linesB[j - 1]}"); j-- }

        val sb = StringBuilder()
        sb.append("差异（+ 新增，- 删除）：\n\n")
        result.forEach { sb.append(it).append("\n") }
        return ToolResult.ok(sb.toString())
    }

    private fun doUrlCodec(args: JSONObject, encode: Boolean): ToolResult {
        val text = args.optString("text", "")
        if (text.isBlank()) return ToolResult.fail("未提供 text")
        val component = args.optBoolean("component", true)

        return try {
            if (encode) {
                if (component) {
                    ToolResult.ok(java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20"))
                } else {
                    val keep = ":/?#[]@!$&'()*+,;="
                    val result = text.split(Regex("([$keep])"))
                        .joinToString("") { seg ->
                            if (seg.length == 1 && keep.contains(seg)) seg
                            else java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
                        }
                    ToolResult.ok(result)
                }
            } else {
                ToolResult.ok(java.net.URLDecoder.decode(text, "UTF-8"))
            }
        } catch (e: Exception) {
            ToolResult.fail("${e.message}")
        }
    }

    // ============================================================
    // 6. json
    // ============================================================

    private fun doJson(args: JSONObject): ToolResult {
        val op = args.optString("op", "").lowercase(Locale.ROOT)
        val text = args.optString("text", "")
        if (text.isBlank()) return ToolResult.fail("未提供 text")
        val path = args.optString("path", "").trim()

        return when (op) {
            "format", "minify", "validate", "extract" -> doJsonBasic(op, text, path)
            "csv_to_json" -> doCsvToJson(text)
            "json_to_csv" -> doJsonToCsv(text)
            else -> ToolResult.fail("op 只能是 format / minify / validate / extract / csv_to_json / json_to_csv")
        }
    }

    private fun doJsonBasic(op: String, text: String, path: String): ToolResult {
        return try {
            val trimmed = text.trimStart()
            val root: Any = when {
                trimmed.startsWith("{") -> JSONObject(text)
                trimmed.startsWith("[") -> JSONArray(text)
                else -> return ToolResult.fail("不是合法 JSON（必须以 { 或 [ 开头）")
            }
            when (op) {
                "format" -> ToolResult.ok(
                    when (root) {
                        is JSONObject -> root.toString(2)
                        is JSONArray -> root.toString(2)
                        else -> text
                    }
                )
                "minify" -> ToolResult.ok(
                    when (root) {
                        is JSONObject -> root.toString()
                        is JSONArray -> root.toString()
                        else -> text
                    }
                )
                "validate" -> ToolResult.ok("JSON 合法。顶层类型：${if (root is JSONObject) "对象" else "数组"}")
                "extract" -> {
                    if (path.isBlank()) return ToolResult.fail("extract 模式需要 path")
                    var cur: Any? = root
                    for (seg in path.split('.')) {
                        cur = when (cur) {
                            is JSONObject -> cur.opt(seg)
                            is JSONArray -> seg.toIntOrNull()?.let { i ->
                                if (i in 0 until cur.length()) cur.opt(i) else null
                            }
                            else -> null
                        }
                        if (cur == null) return ToolResult.fail("路径 $path 在 $seg 处中断")
                    }
                    ToolResult.ok(
                        when (cur) {
                            is JSONObject -> cur.toString(2)
                            is JSONArray -> cur.toString(2)
                            else -> cur.toString()
                        }
                    )
                }
                else -> ToolResult.fail("不支持的 op：$op")
            }
        } catch (e: Exception) {
            ToolResult.fail("JSON 解析错误（${e.message}）")
        }
    }

    private fun doCsvToJson(text: String): ToolResult {
        return try {
            val lines = text.split('\n').filter { it.isNotBlank() }
            if (lines.isEmpty()) return ToolResult.fail("无有效数据")
            val headers = lines[0].split(',').map { it.trim().trim('"') }
            val arr = JSONArray()
            for (i in 1 until lines.size) {
                val values = lines[i].split(',').map { it.trim().trim('"') }
                val obj = JSONObject()
                headers.forEachIndexed { idx, h -> obj.put(h, values.getOrNull(idx) ?: "") }
                arr.put(obj)
            }
            ToolResult.ok(arr.toString(2))
        } catch (e: Exception) {
            ToolResult.fail("${e.message}")
        }
    }

    private fun doJsonToCsv(text: String): ToolResult {
        return try {
            val arr = JSONArray(text)
            if (arr.length() == 0) return ToolResult.fail("空 JSON 数组")
            val first = arr.getJSONObject(0)
            val headers = first.keys().asSequence().toList()
            val sb = StringBuilder()
            sb.append(headers.joinToString(",")).append("\n")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                sb.append(headers.joinToString(",") { h ->
                    val v = o.optString(h, "")
                    if (v.contains(',') || v.contains('"')) "\"${v.replace("\"", "\"\"")}\"" else v
                }).append("\n")
            }
            ToolResult.ok(sb.toString())
        } catch (e: Exception) {
            ToolResult.fail("${e.message}")
        }
    }

    // ============================================================
    // 7. hash
    // ============================================================

    private suspend fun doHash(args: JSONObject, host: ToolHost): ToolResult {
        val text = args.optString("text", "")
        val pathRaw = args.optString("path", "").trim()
        val algo = args.optString("algorithm", "sha256").lowercase(Locale.ROOT)

        if (text.isBlank() && pathRaw.isBlank()) {
            return ToolResult.fail("需要提供 text 或 path")
        }

        val algorithmName = when (algo) {
            "md5" -> "MD5"
            "sha1", "sha-1" -> "SHA-1"
            "sha256", "sha-256" -> "SHA-256"
            "sha512", "sha-512" -> "SHA-512"
            else -> return ToolResult.fail("不支持的算法 $algo")
        }

        if (text.isNotBlank()) {
            return try {
                val digest = MessageDigest.getInstance(algorithmName)
                val hex = digest.digest(text.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                ToolResult.ok("$algo(\"$text\") = $hex")
            } catch (e: Exception) {
                ToolResult.fail("${e.message}")
            }
        }

        val path = host.resolvePath(pathRaw) ?: return ToolResult.fail("路径不允许访问：$pathRaw")
        if (!host.exists(path)) return ToolResult.fail("文件不存在")
        if (host.isDirectory(path)) return ToolResult.fail("这是目录")
        return try {
            val bytes = host.readBytes(path) ?: return ToolResult.fail("读取失败")
            val digest = MessageDigest.getInstance(algorithmName)
            val hex = digest.digest(bytes).joinToString("") { "%02x".format(it) }
            val fileName = path.substringAfterLast('/').substringAfterLast('\\')
            ToolResult.ok("$algo($fileName) = $hex\n（${host.formatSize(bytes.size.toLong())}）")
        } catch (e: Exception) {
            ToolResult.fail("失败：${e.message}")
        }
    }

    /**
     * 【错误自愈】路径类错误时，给出最相近的候选，让模型一次纠对。
     *
     * 【为什么】“文件不存在：xxx”这种错误，模型只能瞎猜重试，
     * 往往要 2-3 轮才找对。直接给出“你是不是想找：yyy”，一轮就能纠对。
     *
     * @param target 用户给的（不存在的）路径
     * @param wantDir true=想要目录，false=想要文件
     * @return 提示文本（可能为空字符串，表示没找到候选）
     */
    private suspend fun pathHint(target: String, wantDir: Boolean, host: ToolHost): String {
        return try {
            val f = File(target)
            val parent = f.parent ?: return ""
            if (!host.exists(parent) || !host.isDirectory(parent)) return ""
            val entries = host.listDirectory(parent) ?: return ""
            if (entries.isEmpty()) return ""

            val wanted = f.name.lowercase(Locale.ROOT)
            val candidates = entries
                .filter { if (wantDir) it.isDirectory else !it.isDirectory }
                .map { it to similarity(wanted, it.name.lowercase(Locale.ROOT)) }
                .filter { it.second > 0.3 }
                .sortedByDescending { it.second }
                .take(5)
                .map { it.first.name }

            if (candidates.isEmpty()) {
                // 没有相近的，至少把父目录里的东西列出来
                val all = entries.take(10).joinToString("、") { it.name }
                return "\n父目录 $parent 下有：$all"
            }
            return "\n你是不是想找：${candidates.joinToString("、")}"
        } catch (_: Exception) {
            ""
        }
    }

    /** 简单的名字相似度（0~1）：前缀匹配 + 长度接近度。 */
    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        // 公共前缀长度
        var common = 0
        val n = minOf(a.length, b.length)
        while (common < n && a[common] == b[common]) common++
        val prefixScore = common.toDouble() / maxOf(a.length, b.length)
        // 包含关系加分
        val containScore = if (a.contains(b) || b.contains(a)) 0.5 else 0.0
        // 长度接近度
        val lenScore = 1.0 - kotlin.math.abs(a.length - b.length).toDouble() / maxOf(a.length, b.length)
        return maxOf(prefixScore, containScore) * 0.7 + lenScore * 0.3
    }

    // ============================================================
    // 8. list_files
    // ============================================================

    private suspend fun doListFiles(args: JSONObject, host: ToolHost): ToolResult {
        val raw = args.optString("path", "").trim()
        val keyword = args.optString("keyword", "").trim()
        val maxResults = args.optInt("max_results", 100).coerceIn(1, 2000)

        // 【mode 推断】
        // 显式传了 mode 就听用户的；没传时：
        //   - 传了 keyword → 按 content 搜正文（传 keyword 的意图几乎总是搜内容，
        //     而不是搜文件名；搜文件名应该用 mode=name 且 keyword 是文件名片段）
        //   - 没传 keyword → 列目录
        // 之前默认 name，导致 list_files(path=某文件, keyword=xxx) 被当成搜文件名，
        // 白跑一轮。
        val mode = if (args.has("mode")) {
            args.optString("mode", "name").trim().lowercase(Locale.ROOT)
        } else if (keyword.isNotBlank()) {
            "content"
        } else {
            "name"
        }

        val path = if (raw.isBlank()) {
            host.resolvePath("~") ?: host.resolvePath("sdcard")
            ?: return ToolResult.fail("无法确定根目录")
        } else {
            host.resolvePath(raw) ?: return ToolResult.fail("路径不允许访问：$raw")
        }

        // mode=content：搜文件正文，返回文件路径 + 行号 + 内容
        if (mode == "content" || mode == "grep") {
            return doContentSearch(args, host, path, keyword, maxResults)
        }
        if (mode != "name") {
            return ToolResult.fail("mode 只能是 name（按文件名）/ content（搜正文）")
        }

        if (keyword.isNotBlank()) {
            val hits = host.searchFiles(path, keyword, maxResults)
            if (hits.isEmpty()) {
                return ToolResult.ok(
                    "未找到文件名匹配 \"$keyword\" 的文件\n" +
                            "搜索范围：$path\n" +
                            "提示：这是按【文件名】搜。要搜文件【内容】请传 mode=content。"
                )
            }
            val sb = StringBuilder("找到 ${hits.size} 个匹配（最多 $maxResults）：\n\n")
            hits.forEach { sb.append("$it\n") }
            return ToolResult.ok(sb.toString().trim())
        }

        if (!host.exists(path)) return ToolResult.fail("目录不存在：$path" + pathHint(path, true, host))
        if (!host.isDirectory(path)) return ToolResult.fail("这不是一个目录：$path" + pathHint(path, true, host))

        val files = host.listDirectory(path) ?: return ToolResult.fail("无法读取目录内容")
        if (files.isEmpty()) return ToolResult.ok("目录为空：$path")

        val sb = StringBuilder()
        sb.append("目录：$path\n共 ${files.size} 项\n\n")
        val sorted = files.sortedWith(
            compareByDescending<FileEntry> { it.isDirectory }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )
        // 【不截断】全部列出
        for ((name, isDirectory, size1) in sorted) {
            val type = if (isDirectory) "[目录]" else "[文件]"
            val size = if (isDirectory) "" else " (${host.formatSize(size1)})"
            sb.append("$type $name$size\n")
        }
        return ToolResult.ok(sb.toString().trim())
    }

    // ============================================================
    // 8b. 搜文件正文（grep）
    // ============================================================

    /**
     * 在目录里递归搜正文。
     *
     * 输出格式刻意做成“文件路径 + 缩进行号: 内容”，
     * AI 拿到后可以直接 read_file(offset=行号) 跳读，一次定位。
     */
    private suspend fun doContentSearch(
        args: JSONObject,
        host: ToolHost,
        root: String,
        keyword: String,
        maxResults: Int
    ): ToolResult {
        if (keyword.isBlank()) {
            return ToolResult.fail("搜正文需要提供 keyword")
        }
        val regex = resolveRegexFlag(args, keyword)
        val nameGlob = args.optString("glob", "").trim()
        val maxFileSizeKb = args.optInt("max_file_size_kb", 2048).coerceIn(1, 102400)
        val contextLines = args.optInt("context", 0).coerceIn(0, 10)

        // 【单文件特判】root 指向一个文件时，取 parent 当目录、name 当 glob，只搜这个文件。
        // 之前这个特判只在 doGrep 里，导致 list_files(mode=content, path=某文件) 搜不到。
        // 下沉到这里，两条入口共用。
        val (searchRoot, searchGlob) = if (!host.isDirectory(root)) {
            val f = File(root)
            (f.parent ?: root) to f.name
        } else {
            root to nameGlob
        }

        val hits = host.searchInFiles(
            root = searchRoot,
            keyword = keyword,
            regex = regex,
            nameGlob = searchGlob,
            maxResults = maxResults,
            maxFileSize = maxFileSizeKb.toLong() * 1024,
            contextLines = contextLines
        )
        return ToolResult.ok(renderContentHits(hits, root, keyword, searchGlob, maxResults, regex))
    }

    /**
     * 决定这次搜索走不走正则。
     *
     * 【为什么】我（AI）写 grep 时第一反应就是写 `a|b` 这种正则，
     * 但默认 regex=false 会当成纯文本，结果“未找到”，白跑一轮。
     *
     * 策略：
     *   - 显式传了 regex 就听用户的
     *   - 没传时，keyword 里出现正则特征字符（| \\ ( ) [ ] { } ^ $ * + ?）就自动按正则
     *   - 自动识别后正则编译失败，自动回退纯文本（不报错）
     */
    private fun resolveRegexFlag(args: JSONObject, keyword: String): Boolean {
        if (args.has("regex")) return args.optBoolean("regex", false)
        val looksRegex = keyword.any { it in "|\\()[]{}^$*+?" }
        if (!looksRegex) return false
        return try {
            Regex(keyword)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 统一的命中渲染：按文件分组，带行号；有上下文时一并展示。 */
    private fun renderContentHits(
        hits: List<ContentMatch>,
        root: String,
        keyword: String,
        nameGlob: String,
        maxResults: Int,
        regex: Boolean = false
    ): String {
        if (hits.isEmpty()) {
            val hasMeta = keyword.any { it in "|\\()[]{}^$*+?" }
            val hint = when {
                // 含正则特征字符但走了纯文本：提醒可以按正则搜
                !regex && hasMeta ->
                    "\n提示：keyword 里含正则特征字符，已按纯文本搜。要按正则搜请传 regex=true。"
                // 走了正则但没命中：提醒检查正则
                regex ->
                    "\n提示：已按正则搜（忽略大小写）。没命中可能是正则写法问题，可先传 regex=false 按纯文本试。"
                else -> ""
            }
            return "未找到包含 \"$keyword\" 的文件" +
                    (if (nameGlob.isNotBlank()) "（限定文件名 $nameGlob）" else "") +
                    "\n搜索范围：$root" + hint
        }
        val byFile = LinkedHashMap<String, MutableList<ContentMatch>>()
        hits.forEach { h -> byFile.getOrPut(h.path) { mutableListOf() }.add(h) }

        val sb = StringBuilder()
        sb.append("在 $root 中搜 \"$keyword\"")
        if (regex) sb.append("（正则）")
        if (nameGlob.isNotBlank()) sb.append("（文件名 $nameGlob）")
        sb.append("：命中 ${hits.size} 行，分布在 ${byFile.size} 个文件\n\n")
        byFile.forEach { (file, list) ->
            sb.append("$file\n")
            list.forEach { h ->
                // 上下文行用行号 + 空格前缀，命中行用行号 + 冒号，一眼能区分
                h.before.forEachIndexed { i, t ->
                    val ln = h.line - h.before.size + i
                    sb.append("  $ln- $t\n")
                }
                sb.append("  ${h.line}: ${h.text}\n")
                h.after.forEachIndexed { i, t ->
                    sb.append("  ${h.line + i + 1}- $t\n")
                }
            }
            sb.append("\n")
        }
        if (hits.size >= maxResults) {
            sb.append("（已达 max_results=$maxResults 上限，可能还有更多。可提高 max_results，或缩小 path / 加 glob）")
        }
        return sb.toString().trim()
    }

    /**
     * 独立工具 grep：与 read_file 同级的代码导航入口。
     *
     * 为什么除了 list_files(mode=content) 还要一个独立工具：
     *   搜正文是高频动作，工具名直接叫 grep 时，AI 不用先想“该用哪个 op/mode”。
     *   两者实现共用，不增加维护成本。
     */
    private suspend fun doGrep(args: JSONObject, host: ToolHost): ToolResult {
        val raw = args.optString("path", "").trim()
        val keyword = args.optString("keyword", "").trim()
        if (keyword.isBlank()) return ToolResult.fail("未提供 keyword")
        val maxResults = args.optInt("max_results", 50).coerceIn(1, 2000)

        val root = if (raw.isBlank()) {
            host.resolvePath("~") ?: return ToolResult.fail("无法确定根目录")
        } else {
            host.resolvePath(raw) ?: return ToolResult.fail("路径不允许访问：$raw")
        }
        if (!host.exists(root)) return ToolResult.fail("路径不存在：$root" + pathHint(root, false, host))

        // 单文件特判已下沉到 doContentSearch，这里直接交给它
        return doContentSearch(args, host, root, keyword, maxResults)
    }

    // ============================================================
    // 9. read_file
    // ============================================================

    private suspend fun doReadFile(args: JSONObject, host: ToolHost): ToolResult {
        val raw = args.optString("path", "").trim()
        val mode = args.optString("mode", "text").lowercase(Locale.ROOT)
        val length = args.optInt("length", 512).coerceIn(1, 4096)
        if (raw.isBlank()) return ToolResult.fail("未提供 path")

        val path = host.resolvePath(raw) ?: return ToolResult.fail("路径不允许访问：$raw")
        if (!host.exists(path)) return ToolResult.fail("文件不存在：$path" + pathHint(path, false, host))
        if (host.isDirectory(path)) return ToolResult.fail("这是一个目录而非文件：$path" + pathHint(path, false, host))

        return when (mode) {
            "text" -> {
                val offset = args.optInt("offset", 1)
                val limit = args.optInt("limit", 0)

                // 【新增】带 offset/limit 时走行区间读取：
                //   - 大文件不再被 1MB 硬拦，想看 200 行就给 200 行
                //   - 返回带行号，方便后续 code_edit / grep 定位
                if (offset > 1 || limit > 0) {
                    return readFileRange(host, path, offset, limit)
                }

                val size = host.fileSize(path)
                if (size > MAX_TEXT_FILE_SIZE) {
                    val total = host.textLineCount(path)
                    val lines = if (total > 0) "\n当前文件共 $total 行。" else ""
                    return ToolResult.fail(
                        "文件过大（${host.formatSize(size)}），超过 1MB 上限，不整文件返回。" +
                                "请改用行区间分页读：read_file(path=..., offset=1, limit=200)" + lines
                    )
                }
                val content = host.readTextFile(path) ?: return ToolResult.fail("读取失败")
                // 【不截断】完整返回；顺带报总行数，方便判断要不要分页
                val total = host.textLineCount(path)
                val lineInfo = if (total > 0) "\n行数：$total" else ""
                ToolResult.ok("文件：$path\n大小：${host.formatSize(size)}$lineInfo\n内容：\n$content")
            }
            "base64" -> {
                val size = host.fileSize(path)
                if (size > MAX_BINARY_FILE_SIZE) {
                    return ToolResult.fail("文件过大（${host.formatSize(size)}），上限 ${host.formatSize(MAX_BINARY_FILE_SIZE)}")
                }
                val bytes = host.readBytes(path) ?: return ToolResult.fail("读取失败")
                val b64 = Base64.getEncoder().encodeToString(bytes)
                ToolResult.ok(b64)
            }
            "hex" -> {
                val bytes = host.readBytes(path) ?: return ToolResult.fail("读取失败")
                val shown = bytes.take(length).toByteArray()
                if (shown.isEmpty()) return ToolResult.ok("文件为空")
                val sb = StringBuilder()
                sb.append("文件：$path\n")
                sb.append("显示前 ${shown.size} 字节（共 ${bytes.size} 字节）\n\n")
                for (i in shown.indices step 16) {
                    val chunk = shown.copyOfRange(i, minOf(i + 16, shown.size))
                    sb.append(String.format(Locale.US, "%08X  ", i))
                    for (b in chunk) sb.append(String.format(Locale.US, "%02X ", b))
                    for (j in chunk.size until 16) sb.append("   ")
                    sb.append(" |")
                    for (b in chunk) {
                        val c = b.toInt() and 0xFF
                        sb.append(if (c in 32..126) c.toChar() else '.')
                    }
                    sb.append("|\n")
                }
                ToolResult.ok(sb.toString())
            }
            else -> ToolResult.fail("mode 只能是 text / base64 / hex")
        }
    }

    /**
     * 按行区间读文本文件，返回带行号的正文。
     *
     * 行号前缀是 `123| `，宽度对齐到最后一行。
     * AI 能直接把行号回喂给 offset，也能在 grep 结果里对照位置。
     */
    private suspend fun readFileRange(
        host: ToolHost,
        path: String,
        offset: Int,
        limit: Int
    ): ToolResult {
        val start = if (offset <= 0) 1 else offset
        val take = if (limit <= 0) 0 else limit
        val total = host.textLineCount(path)
        val lines = host.readTextLines(path, start, take)
            ?: return ToolResult.fail("读取失败（可能不是文本文件）")

        if (lines.isEmpty()) {
            if (total in 1 until start) {
                return ToolResult.fail("offset=$start 超出文件范围（共 $total 行）")
            }
            return ToolResult.ok("文件：$path\n（该区间无内容）")
        }

        val end = lines.last().number
        val width = end.toString().length
        val nextLimit = if (take > 0) take else 200
        val size = host.fileSize(path)
        val sb = StringBuilder()
        sb.append("文件：$path\n")
        sb.append("行 $start-$end")
        if (total >= 0) sb.append(" / 共 $total 行")
        if (size > 0) sb.append(" / ${host.formatSize(size)}")
        sb.append("\n\n")
        lines.forEach { l ->
            sb.append(l.number.toString().padStart(width))
                .append("| ")
                .append(l.text)
                .append("\n")
        }
        if (total >= 0 && end < total) {
            sb.append("\n（未完，还有 ${total - end} 行。继续读：")
                .append("read_file(path=..., offset=${end + 1}, limit=$nextLimit)）")
        }
        return ToolResult.ok(sb.toString().trimEnd())
    }

    // ============================================================
    // 10. write_file
    // ============================================================
    private suspend fun doWriteFile(args: JSONObject, host: ToolHost): ToolResult {
        val raw = args.optString("path", "").trim()
        val mode = args.optString("mode", "text").lowercase(Locale.ROOT)
        if (raw.isBlank()) return ToolResult.fail("未提供 path")

        val path = host.resolvePath(raw) ?: return ToolResult.fail("路径不允许访问：$raw")

        return when (mode) {
            "text" -> {
                val content = args.optString("content", "")
                val append = args.optBoolean("append", false)
                if (append) {
                    val existing = host.readTextFile(path) ?: ""
                    if (host.writeTextFile(path, existing + content)) {
                        ToolResult.ok("已追加 ${content.length} 字符到 $path")
                    } else {
                        ToolResult.fail("写入失败")
                    }
                } else {
                    // 【体验优化 B5】写前先判存在，返回结果里标注"覆盖"或"新建"，
                    // 让 AI 和用户都能看到实际动作，不至于误以为是无害的新建。
                    val existed = host.exists(path)
                    if (host.writeTextFile(path, content)) {
                        val tag = if (existed) "（覆盖已有文件）" else "（新建）"
                        ToolResult.ok("已写入 ${content.length} 字符到 $path$tag")
                    } else {
                        ToolResult.fail("写入失败")
                    }
                }
            }
            "base64" -> {
                val data = args.optString("data", "")
                if (data.isBlank()) return ToolResult.fail("未提供 data")
                try {
                    val bytes = Base64.getDecoder().decode(data)
                    if (bytes.size > MAX_BINARY_FILE_SIZE) {
                        return ToolResult.fail("数据过大（${host.formatSize(bytes.size.toLong())}）")
                    }
                    host.ensureParentDir(path)
                    if (host.writeBytes(path, bytes)) {
                        ToolResult.ok("已写入 ${bytes.size} 字节到 $path")
                    } else {
                        ToolResult.fail("写入失败")
                    }
                } catch (e: Exception) {
                    ToolResult.fail("解码/写入失败：${e.message}")
                }
            }
            else -> ToolResult.fail("mode 只能是 text / base64")
        }
    }

    // ============================================================
    // 11. http_request
    // ============================================================

    private suspend fun doHttpRequest(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val url = args.optString("url", "").trim()
        if (url.isBlank()) return@withContext ToolResult.fail("未提供 url")
        val method = args.optString("method", "GET").uppercase(Locale.ROOT)
        val headers = args.optJSONObject("headers")
        val body = args.optString("body", "")
        val timeout = args.optInt("timeout", 30).coerceIn(1, 120)

        val builder = Request.Builder().url(url)
        headers?.keys()?.forEach { k ->
            try {
                builder.header(k, headers.optString(k, ""))
            } catch (e: Exception) {
                ToolLogger.log("CommonTools", "HTTP header '$k' 非法，已跳过：${e.message}")
            }
        }
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "HEAD" -> builder.head()
            "POST", "PUT", "PATCH" -> {
                val ct = headers?.optString("Content-Type") ?: "application/json"
                val mt = try { ct.toMediaTypeOrNull() } catch (e: Exception) {
                    ToolLogger.log("CommonTools", "Content-Type '$ct' 非法：${e.message}")
                    null
                }
                builder.method(method, body.toRequestBody(mt))
            }
            else -> return@withContext ToolResult.fail("不支持的 method：$method")
        }

        return@withContext try {
            val client = getHttpClientForTimeout(timeout)
            val resp = client.newCall(builder.build()).execute()

            val code = resp.code
            val headersStr = resp.headers.names().take(20).joinToString("\n") { n ->
                "$n: ${resp.headers[n]}"
            }
            val bodyStr = resp.use { it.body.string() }

            ToolResult.ok(
                buildString {
                    append("HTTP $code\n")
                    if (headersStr.isNotBlank()) append("响应头：\n$headersStr\n\n")
                    // 【不截断】完整返回
                    append("响应体：\n")
                    append(bodyStr)
                }
            )
        } catch (e: Exception) {
            ToolResult.fail("请求失败：${e.message}")
        }
    }

    // ============================================================
    // 12. env_store
    // ============================================================

    private suspend fun doEnvStore(args: JSONObject, host: ToolHost): ToolResult {
        val op = args.optString("operation", "get").lowercase(Locale.ROOT)
        val key = args.optString("key", "").trim()

        return when (op) {
            "get" -> {
                if (key.isBlank()) {
                    val all = host.kvListAll()
                    if (all.isEmpty()) return ToolResult.ok("当前没有变量")
                    ToolResult.ok(
                        buildString {
                            append("共 ${all.size} 个变量：\n")
                            // 【不截断】完整列出
                            all.forEach { (k, v) ->
                                append("$k = $v\n")
                            }
                        }.trim()
                    )
                } else {
                    val v = host.kvGet(key) ?: return ToolResult.fail("变量 $key 不存在")
                    ToolResult.ok("$key = $v")
                }
            }
            "set" -> {
                if (key.isBlank()) return ToolResult.fail("key 不能为空")
                val value = args.optString("value", "")
                host.kvSet(key, value)
                ToolResult.ok("已设置 $key = $value")
            }
            "delete" -> {
                if (key.isBlank()) return ToolResult.fail("key 不能为空")
                host.kvRemove(key)
                ToolResult.ok("已删除变量 $key")
            }
            "clear" -> {
                host.kvClear()
                ToolResult.ok("已清空所有变量")
            }
            else -> ToolResult.fail("operation 只能是 get / set / delete / clear")
        }
    }

    // ============================================================
    // 13. sleep
    // ============================================================

    private suspend fun doSleep(args: JSONObject): ToolResult {
        val seconds = args.optDouble("seconds", -1.0)
        val ms = args.optLong("ms", -1L)
        val reason = args.optString("reason", "").trim()

        val totalMs: Long = when {
            ms > 0 -> ms
            seconds > 0 -> (seconds * 1000).toLong()
            else -> return ToolResult.fail("需要 seconds 或 ms 参数")
        }

        if (totalMs > MAX_SLEEP_MS) {
            return ToolResult.fail(
                "单次等待不能超过 ${MAX_SLEEP_MS / 1000} 秒（收到 ${totalMs / 1000}s）"
            )
        }

        val start = System.currentTimeMillis()
        delay(totalMs.milliseconds)
        val actual = System.currentTimeMillis() - start
        val note = if (reason.isNotBlank()) "（$reason）" else ""
        return ToolResult.ok("已等待 ${actual}ms$note")
    }
}