package net.xinkong.furagent.desktop.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.desktop.data.DesktopPaths
import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.desktop.tools.CodeEditTools.buildFilesDiagnosis
import net.xinkong.furagent.shared.tools.ToolResult
import net.xinkong.furagent.shared.util.DateFormats
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*

/**
 * 代码编辑工具。
 *
 * 核心思路：把「代码编辑」变成「带校验的字符串手术」。
 *
 * 相比整文件重写（write_file），锚点替换的优势：
 *  - 只动需要动的几行，其余原样保留，不会手滑改错别处
 *  - 锚点唯一性校验（count == 1），锚点不唯一直接拒绝，不会误改
 *  - 一次可改多个文件、多处，省去反复读写
 *
 * 只做编辑，不做编译 / 语法检查 / 格式化。
 *
 * 能力：
 *  - 锚点唯一性校验（count == 1），不唯一直接拒绝
 *  - 锚点失败时给出「最相似位置 + 前后上下文」，便于自我纠错
 *  - 支持新建文件（create=true）
 *  - 支持 verify 校验（contains / not_contains），失败自动回滚
 *  - 自动处理 CRLF / LF 行尾与 UTF-8 BOM
 *  - 备份按相对路径命名，避免同名文件互相覆盖
 */
object CodeEditTools {

    data class ToolDef(
        val name: String,
        val displayName: String,
        val description: String,
        val paramsSchema: String
    )

    val TOOLS: List<ToolDef> = listOf(
        ToolDef(
            name = "code_edit",
            displayName = "代码编辑",
            description = "对代码文件做精确的锚点替换编辑（可改已有文件，也可新建文件）。" +
                    "【强制】一次调用必须把所有要改的文件、所有要改的地方全部放进 files 数组，禁止拆成多次调用。" +
                    "每多拆一次调用，就要重发一遍完整上下文，token 成本成倍增加；能一轮改完就必须一轮改完。" +
                    "files 是数组，每项含 path 和 edits；edits 是数组，每项含 old（原文锚点）和 new（替换后）。" +
                    "每个 old 必须在文件中恰好出现 1 次，否则该文件整体拒绝（不会部分修改）。" +
                    "【按行号改】已知行号时优先用 {\"line_start\":起行,\"line_end\":止行,\"new\":\"替换后\"}（1起、含两端），" +
                    "不需要手抄原文锚点——从 read_file / list_files 拿到的行号可直接用，不会因错一个字符整批回滚。" +
                    "只改一行可只给 line_start（line_end 缺省等于 line_start）。" +
                    "【注意】line_start/line_end 是替换整段，不是插入。要在某行前/后插入新代码，" +
                    "用 {\"insert_before_line\":行号,\"new\":\"新代码\"} 或 {\"insert_after_line\":行号,\"new\":\"新代码\"}，原行不动。" +
                    "【删大段代码】用 edits 项 {\"delete_start\":起行,\"delete_end\":止行}（1 起、含两端），" +
                    "比用 old 锚点逐字复制整段可靠得多；可与 old/new、line_start/line_end 混用。" +
                    "【执行顺序】old/new 与 line_start/line_end 先按顺序应用；delete_* 最后按原行号从后往前执行。" +
                    "新建文件：该项加 \"create\": true，且 edits 只放 1 项，old 留空，new 为整个文件内容。" +
                    "可选 verify 数组做应用后校验：[{\"type\":\"contains\"|\"not_contains\",\"path\":\"...\",\"text\":\"...\"}]，校验失败自动回滚。" +
                    "所有文件全部成功才写盘，任一失败则全部回滚。" +
                    "改前会自动备份到 ~/.furagent/exports/code_backup/。" +
                    "返回内容包含每个改动点的 unified diff（@@ -原行 +新行 @@ 与前后行），可直接自检，不用再读回文件。" +
                    "锚点找不到时会返回最相似位置、前后上下文，以及【逐字符差异】（直接告诉你差在缩进还是内容），据此修正后重试。" +
                    "只做编辑，不做编译 / 语法检查 / 格式化。",
            paramsSchema = """{"type":"object","properties":{"files":{"type":"array","description":"要修改的文件列表","items":{"type":"object","properties":{"path":{"type":"string","description":"文件路径"},"create":{"type":"boolean","description":"true 表示新建文件，此时 edits 只放 1 项，old 留空，new 为整个文件内容"},"edits":{"type":"array","description":"改动列表","items":{"type":"object","properties":{"old":{"type":"string","description":"原文锚点，必须逐字符匹配（含缩进），且在文件中恰好出现 1 次"},"new":{"type":"string","description":"替换后的内容"},"delete_start":{"type":"integer","description":"按行号删除的起始行（1 起，含）。与 delete_end 配对使用，此时不要填 old/new/line_*"},"delete_end":{"type":"integer","description":"按行号删除的结束行（1 起，含）。与 delete_start 配对使用"},"line_start":{"type":"integer","description":"[推荐] 按行号替换的起始行（1 起，含）。不需要 old 锚点，直接用 read_file 拿到的行号"},"line_end":{"type":"integer","description":"按行号替换的结束行（1 起，含）。缺省等于 line_start；使用 line_start 时不需要填 old"},"insert_before_line":{"type":"integer","description":"在指定行之前插入 new（1 起），原行不动"},"insert_after_line":{"type":"integer","description":"在指定行之后插入 new（1 起），原行不动"}},"additionalProperties":false}}},"required":["path","edits"],"additionalProperties":false}},"verify":{"type":"array","description":"应用后校验","items":{"type":"object","properties":{"type":{"type":"string","enum":["contains","not_contains"]},"path":{"type":"string"},"text":{"type":"string"}},"required":["type","path","text"],"additionalProperties":false}},"diff":{"type":"boolean","description":"是否返回 diff，默认 true"}},"required":["files"],"additionalProperties":false}"""
        )
    )

    val NAMES: Set<String> = TOOLS.map { it.name }.toSet()

    fun displayName(name: String): String =
        TOOLS.firstOrNull { it.name == name }?.displayName ?: name

    suspend fun execute(name: String, args: JSONObject): ToolResult? {
        if (name != "code_edit") return null
        return doEdit(args)
    }

    // 实现

    private data class EditOp(val old: String, val new: String)

    /**
     * 按行号删除。
     *
     * 删几百行代码时，用 old 锚点要逐字复制整段，极易失败；
     * 用行号区间则稳定得多。
     *
     * @param start 起始行（1 起，含）
     * @param end   结束行（1 起，含）
     */
    private data class DeleteOp(val start: Int, val end: Int)

    /**
     * 按行号替换。
     *
     * 为什么需要 已知行号时还要逐字拄 old 锚点是最大的浪费源：
     * 错一个字符、少一层缩进就整批回滚，而且每次重试都要重发全文。
     * read_file / list_files 本来就给行号，直接拿来替换即可。
     *
     * @param start 起始行（1 起，含）
     * @param end   结束行（1 起，含）
     * @param new   替换后的内容（可为空 = 删除）
     */
    /** 行号类操作的类型。 */
    private enum class LineMode { REPLACE, INSERT_BEFORE, INSERT_AFTER }

    private data class LineOp(
        val start: Int,
        val end: Int,
        val new: String,
        val mode: LineMode = LineMode.REPLACE
    )

    /** 行号区间操作（替换/插入/删除）的统一表示，用于从后往前应用。 */
    private data class LineSpan(
        val start: Int,
        val end: Int,
        val newText: String,
        val mode: LineMode = LineMode.REPLACE
    )

    /**
     * 一处已应用的替换。
     * oldStartLine 是替换前在原文件里的起始行号（1 起），
     * newStartLine 是替换后在新文件里的起始行号（1 起）。
     */
    private data class AppliedEdit(
        val oldText: String,
        val newText: String,
        val oldStartLine: Int,
        val newStartLine: Int
    )

    private data class FilePlan(
        val file: File,
        val original: String,
        val edited: String,
        val appliedCount: Int,
        val edits: List<AppliedEdit>,
        val isCreate: Boolean,
        val hasBom: Boolean
    )

    /**
     * 行尾风格。
     *
     * 为什么需要 Windows 上的源码文件通常是 CRLF，而 AI 写锚点时用的是 LF。
     * 若不做归一化，多行锚点会因 \r\n vs \n 差异而匹配失败，
     * 表现为「明明文件里有这段，却报找不到」。
     *
     * 策略：匹配前把文件与锚点都归一化为 LF，替换后再转回原风格写盘。
     */
    private enum class LineEnding(val seq: String) {
        CRLF("\r\n"),
        LF("\n")
    }

    /** 检测文本的主要行尾风格。无换行时默认 LF。 */
    private fun detectLineEnding(text: String): LineEnding {
        val crlf = text.windowed(2).count { it == "\r\n" }
        val lf = text.count { it == '\n' } - crlf
        return if (crlf > lf) LineEnding.CRLF else LineEnding.LF
    }

    /** 把任意行尾统一成 LF。 */
    private fun toLf(text: String): String = text.replace("\r\n", "\n").replace("\r", "\n")

    /** 把 LF 文本转成指定行尾风格。 */
    private fun fromLf(text: String, ending: LineEnding): String =
        if (ending == LineEnding.LF) text else text.replace("\n", ending.seq)

    private const val BOM = "\uFEFF"

    /** 读取文件，剥离 UTF-8 BOM，返回 (正文, 是否含 BOM)。 */
    private fun readTextWithBom(file: File): Pair<String, Boolean> {
        val raw = file.readText(Charsets.UTF_8)
        return if (raw.startsWith(BOM)) raw.substring(1) to true else raw to false
    }

    /** 写文件，按需补回 BOM。 */
    private fun writeTextWithBom(file: File, content: String, hasBom: Boolean) {
        file.writeText(if (hasBom) BOM + content else content, Charsets.UTF_8)
    }

    // 锚点失败诊断

    /**
     * 锚点找不到 / 不唯一时，输出足够多的上下文，让人（和模型）一眼看出差异。
     *
     * 思路：取锚点首行，在文件里按相似度找最像的几行，
     * 每处附上前后 contextLines 行原文，并标出锚点自身的前若干行。
     */
    private fun anchorFailureHint(workingText: String, old: String, contextLines: Int = 15): String {
        val oldLinesRaw = old.split('\n')
        val oldLines = oldLinesRaw.map { it.trim() }.filter { it.isNotEmpty() }
        if (oldLines.isEmpty()) return "    （锚点为空）"

        val first = oldLines.first()
        val last = if (oldLines.size > 1) oldLines.last() else null

        val sb = StringBuilder()
        sb.append("    锚点首行：").append(first.take(120)).append('\n')
        sb.append("    锚点尾行：").append(last?.take(120) ?: "(单行)").append('\n')
        sb.append("    锚点总行数：").append(oldLinesRaw.size).append('\n')

        val textLines = workingText.split('\n')
        val total = textLines.size

        // 找相似行
        val cands = mutableListOf<Triple<Int, Double, String>>()
        for ((i, line) in textLines.withIndex()) {
            val stripped = line.trim()
            if (stripped.isEmpty()) continue
            val ratio = similarity(first, stripped)
            if (ratio >= 0.3) cands.add(Triple(i + 1, ratio, stripped))
        }
        cands.sortByDescending { it.second }
        val top = cands.take(10)

        if (top.isEmpty()) {
            sb.append("    [错误] 文件里没有相似行，可能整段不存在\n")
            return sb.toString()
        }

        sb.append("\n    ═══════ 锚点自身前 15 行 ═══════\n")
        oldLinesRaw.take(15).forEachIndexed { i, l ->
            sb.append("      A").append((i + 1).toString().padStart(3)).append("| ").append(l.take(120)).append('\n')
        }

        sb.append("\n    ═══════ 文件里最相似的 ").append(minOf(top.size, 3))
            .append(" 处（每处附前后 ").append(contextLines).append(" 行原文）═══════\n")

        top.take(3).forEachIndexed { ci, (ln, ratio, content) ->
            sb.append("\n    ─── 候选 ").append(ci + 1).append("：L").append(ln)
                .append("（相似度 ").append(String.format(Locale.US, "%.2f", ratio)).append("）：")
                .append(content.take(120)).append(" ───\n")
            val start = maxOf(1, ln - contextLines)
            val end = minOf(total, ln + contextLines)
            for (idx in start..end) {
                val marker = if (idx == ln) ">>>" else "   "
                sb.append("      ").append(marker).append(" L").append(idx.toString().padStart(4))
                    .append("| ").append(textLines[idx - 1].take(120)).append('\n')
            }
            // 对最佳候选（ci==0）额外输出逐字符差异，
            // 直接告诉模型是缩进差还是内容差，不用自己肉眼找。
            if (ci == 0) {
                val fileSlice = textLines.subList(ln - 1, minOf(total, ln - 1 + oldLinesRaw.size))
                val hint = diffHint(oldLinesRaw, fileSlice, ln)
                if (hint.isNotBlank()) {
                    sb.append("\n    逐字符差异（候选 1）\n").append(hint)
                }
                // 直接给出可用的修改方式，避免模型照着差异手抄。
                sb.append("\n    可直接用（候选 1 真实原文，L").append(ln)
                    .append(" 起共 ").append(fileSlice.size).append(" 行）\n")
                sb.append("    方式A 按行号改：{\"line_start\": ").append(ln)
                    .append(", \"line_end\": ").append(ln + fileSlice.size - 1)
                    .append(", \"new\": \"<替换内容>\"}\n")
                sb.append("    方式B 继续用 old：把 old 整段换成下面这份原文：\n")
                fileSlice.forEach { l -> sb.append("      |").append(l).append('\n') }
            }
        }
        return sb.toString()
    }

    /** 把空白字符可见化，便于一眼看出缩进差异。 */
    private fun visible(s: String): String = buildString {
        for (c in s.take(160)) {
            when (c) {
                ' ' -> append('·')
                '\t' -> append('→')
                else -> append(c)
            }
        }
        if (s.length > 160) append("…")
    }

    private fun visibleChar(c: Char): String = when (c) {
        ' ' -> "空格"
        '\t' -> "制表符"
        '\n' -> "换行"
        else -> c.toString()
    }

    /**
     * 逐行对比锚点与候选，指出具体差在哪。
     *
     * 之前只给“最相似 3 处 + 前后 15 行”，模型得自己肉眼找差异，
     * 经常看不出是缩进差 4 个空格还是多了个空行，反复重试。
     */
    private fun diffHint(oldLines: List<String>, fileLines: List<String>, fileStart: Int): String {
        val sb = StringBuilder()
        val n = minOf(oldLines.size, fileLines.size)
        var firstDiff = -1
        for (i in 0 until n) {
            if (oldLines[i] != fileLines[i]) { firstDiff = i; break }
        }
        if (firstDiff < 0) {
            if (oldLines.size != fileLines.size) {
                sb.append("    行数不一致：锚点 ${oldLines.size} 行，文件对应位置 ${fileLines.size} 行\n")
            }
            return sb.toString()
        }

        val a = oldLines[firstDiff]
        val b = fileLines[firstDiff]
        sb.append("    首个差异在第 ").append(firstDiff + 1).append(" 行（文件 L")
            .append(fileStart + firstDiff).append("）\n")

        val kind = when {
            a.trim() == b.trim() -> "缩进/空白不同（内容相同）"
            a.trimStart() == b.trimStart() -> "行首缩进不同"
            a.trimEnd() == b.trimEnd() -> "行尾空白不同"
            else -> "内容不同"
        }
        sb.append("    差异类型：").append(kind).append('\n')
        sb.append("    锚点行：").append(visible(a)).append('\n')
        sb.append("    文件行：").append(visible(b)).append('\n')

        val maxCmp = minOf(a.length, b.length)
        var ci = 0
        while (ci < maxCmp && a[ci] == b[ci]) ci++
        if (ci < maxCmp || a.length != b.length) {
            sb.append("    首个字符差异在第 ").append(ci + 1).append(" 列")
            if (ci < a.length) sb.append("，锚点字符='").append(visibleChar(a[ci])).append('\'')
            if (ci < b.length) sb.append("，文件字符='").append(visibleChar(b[ci])).append('\'')
            sb.append('\n')
        }
        return sb.toString()
    }

    /** 两行的相似度（0~1），基于最长公共子序列长度占比。 */
    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val m = a.length
        val n = b.length
        // 为省内存，用滚动数组求 LCS 长度
        var prev = IntArray(n + 1)
        var cur = IntArray(n + 1)
        for (i in 1..m) {
            for (j in 1..n) {
                cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
            }
            val t = prev; prev = cur; cur = t
            java.util.Arrays.fill(cur, 0)
        }
        val lcs = prev[n]
        return 2.0 * lcs / (m + n)
    }

    /**
     * 把「顶层 path + edits」的单文件写法自动包成 files 数组。
     *
     * 只在语义完全确定时生效（顶层有 path 且有 edits），不猜其他形状。
     * 返回 null 表示不是可安全纠正的形状，交给 [buildFilesDiagnosis] 报错。
     */
    private fun normalizeTopLevelSingleFile(args: JSONObject): JSONArray? {
        val path = args.optString("path", "").trim()
        if (path.isEmpty()) return null
        // edits 可能在顶层，也可能只有单个 old/new
        val topEdits = args.optJSONArray("edits")
        val single = args.optJSONObject("edits")
        val edits: JSONArray = when {
            topEdits != null && topEdits.length() > 0 -> topEdits
            single != null -> JSONArray().put(single)
            args.has("old") || args.has("new") || args.has("line_start") ||
                    args.has("delete_start") -> JSONArray().put(
                JSONObject().apply {
                    args.keys().forEach { k ->
                        if (k != "path" && k != "create") put(k, args.opt(k))
                    }
                }
            )
            else -> return null
        }
        return JSONArray().put(
            JSONObject().apply {
                put("path", path)
                if (args.has("create")) put("create", args.optBoolean("create", false))
                put("edits", edits)
            }
        )
    }

    /**
     * files 参数缺失/类型错误时的诊断信息。
     *
     * 之前只报“未提供 files（应为数组）”，
     * 模型看不出是自己传了顶层 path 还是 JSON 结构错了，只能盲猜重试。
     */
    private fun buildFilesDiagnosis(args: JSONObject): String {
        val sb = StringBuilder("files 参数无效。\n")
        val keys = args.keys().asSequence().toList()
        sb.append("收到的参数名：").append(if (keys.isEmpty()) "（无）" else keys.joinToString(", "))
        sb.append('\n')

        when {
            args.has("path") -> {
                sb.append("\n[错误] 检测到你传了顶层 `path`。code_edit 不支持单文件模式，必须包成数组：\n")
                sb.append("{\"files\":[{\"path\":\"...\",\"edits\":[{\"old\":\"...\",\"new\":\"...\"}]}]}\n")
            }
            args.has("file") -> {
                sb.append("\n[错误] 参数名应为 `files`（复数），不是 `file`。\n")
            }
            args.has("edits") -> {
                sb.append("\n[错误] `edits` 必须放在 files 数组的每一项里，不能放顶层。\n")
            }
            args.opt("files") != null -> {
                val v = args.opt("files")
                sb.append("\n[错误] `files` 存在但类型不对（实际是 ").append(v?.javaClass?.simpleName).append("），必须是 JSON 数组。\n")
            }
            else -> {
                sb.append("\n[错误] 完全没有 `files` 参数。\n")
            }
        }
        sb.append("\n正确结构：\n")
        sb.append("{\"files\":[{\"path\":\"文件路径\",\"edits\":[{\"old\":\"原文锚点\",\"new\":\"替换后\"}]}]}\n")
        return sb.toString()
    }

    private suspend fun doEdit(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        // files 缺失时给出具体诊断，而不是笼统的“未提供 files”。
        // 常见错误：
        //   1) 顶层传了 path（单文件习惯），忘了包成 files 数组
        //   2) files 传成了字符串（JSON 序列化错误）
        //   3) 参数名拼错（file / edits）
        // 自动纠正单文件写法
        // 常见错：把 path / edits 直接放在顶层（write_file 的习惯）。
        // 以前这种情况一律报「未提供 files」，模型只能盲猜重试，白白浪费一轮。
        // 但顶层 path 的含义是完全确定的，没必要报错，直接包成 files 即可。
        val filesArr = args.optJSONArray("files") ?: normalizeTopLevelSingleFile(args)
        if (filesArr == null) {
            return@withContext ToolResult.fail(buildFilesDiagnosis(args))
        }
        if (filesArr.length() == 0) return@withContext ToolResult.fail("files 为空数组，没有要改的内容")

        // ---------- 阶段 1：全部校验 + 在内存里算出结果，不碰磁盘 ----------
        val plans = mutableListOf<FilePlan>()
        val errors = mutableListOf<String>()

        for (i in 0 until filesArr.length()) {
            val item = filesArr.optJSONObject(i)
            if (item == null) {
                errors.add("files[$i] 不是对象")
                continue
            }
            val pathStr = item.optString("path", "").trim()
            if (pathStr.isBlank()) {
                errors.add("files[$i] 缺少 path")
                continue
            }
            val file = File(pathStr)
            val isCreate = item.optBoolean("create", false)

            val editsArr = item.optJSONArray("edits")
            if (editsArr == null || editsArr.length() == 0) {
                errors.add("$pathStr：edits 为空")
                continue
            }

            // ---------- 新建文件 ----------
            if (isCreate) {
                if (file.exists()) {
                    errors.add("$pathStr：create=true 但文件已存在")
                    continue
                }
                if (editsArr.length() != 1) {
                    errors.add("$pathStr：create=true 时 edits 必须只有 1 项")
                    continue
                }
                val e0 = editsArr.optJSONObject(0)
                if (e0 == null || !e0.has("new")) {
                    errors.add("$pathStr：create=true 时 edits[0] 缺少 new")
                    continue
                }
                val content = toLf(e0.optString("new", ""))
                plans.add(
                    FilePlan(
                        file = file,
                        original = "",
                        edited = content,
                        appliedCount = 1,
                        edits = listOf(AppliedEdit("", content, 0, 1)),
                        isCreate = true,
                        hasBom = false
                    )
                )
                continue
            }

            // ---------- 修改已有文件 ----------
            if (!file.exists()) {
                errors.add("$pathStr：文件不存在（新建文件请加 \"create\": true）")
                continue
            }
            if (!file.isFile) {
                errors.add("$pathStr：不是文件")
                continue
            }

            val ops = mutableListOf<EditOp>()
            val delOps = mutableListOf<DeleteOp>()
            val lineOps = mutableListOf<LineOp>()
            // 行号类操作（line_* / insert_* / delete_*）的上限校验靠它。
            // 插入允许落在最后一行之后（行数+1），所以这里给得宽松一点。
            val fileLineCountHint = try {
                file.readText(Charsets.UTF_8).count { it == '\n' }
            } catch (_: Exception) {
                Int.MAX_VALUE
            }
            var opError = false
            for (j in 0 until editsArr.length()) {
                val e = editsArr.optJSONObject(j)
                if (e == null) {
                    errors.add("$pathStr：edits[$j] 不是对象")
                    opError = true
                    break
                }
                // 插入 在某行前/后插入新代码，原行不动。
                // 之前只能替换，想插一行得把目标行也写一遍，很容易误删原行。
                if (e.has("insert_before_line") || e.has("insert_after_line")) {
                    val before = e.has("insert_before_line")
                    if (before && e.has("insert_after_line")) {
                        errors.add("$pathStr：edits[$j] 不能同时给 insert_before_line 和 insert_after_line")
                        opError = true
                        break
                    }
                    val ln = if (before) e.optInt("insert_before_line", -1)
                    else e.optInt("insert_after_line", -1)
                    if (ln < 1 || ln > fileLineCountHint) {
                        errors.add("$pathStr：edits[$j] 的 insert 行号无效（$ln，要求 1..$fileLineCountHint）")
                        opError = true
                        break
                    }
                    if (!e.has("new")) {
                        errors.add("$pathStr：edits[$j] 用了 insert_* 但缺少 new")
                        opError = true
                        break
                    }
                    lineOps.add(
                        LineOp(
                            start = ln,
                            end = ln,
                            new = e.optString("new", ""),
                            mode = if (before) LineMode.INSERT_BEFORE else LineMode.INSERT_AFTER
                        )
                    )
                    continue
                }
                // 支持 line_start / line_end 按行号替换（不需要 old 锚点）
                if (e.has("line_start")) {
                    val s = e.optInt("line_start", -1)
                    val en = e.optInt("line_end", s)
                    if (s < 1 || en < s) {
                        errors.add("$pathStr：edits[$j] 的 line_start/line_end 无效（line_start=$s, line_end=$en，要求 1 <= line_start <= line_end）")
                        opError = true
                        break
                    }
                    if (!e.has("new")) {
                        errors.add("$pathStr：edits[$j] 用了 line_start 但缺少 new（删除请用 delete_start/delete_end）")
                        opError = true
                        break
                    }
                    lineOps.add(LineOp(s, en, e.optString("new", "")))
                    continue
                }
                // 支持 delete_start / delete_end 按行号删除（扁平字段，避免嵌套 object 触发 schema 校验失败）
                if (e.has("delete_start") || e.has("delete_end")) {
                    val s = e.optInt("delete_start", -1)
                    val en = e.optInt("delete_end", -1)
                    if (s < 1 || en < s) {
                        errors.add("$pathStr：edits[$j] 的 delete_start/delete_end 无效（delete_start=$s, delete_end=$en，要求 1 <= delete_start <= delete_end）")
                        opError = true
                        break
                    }
                    delOps.add(DeleteOp(s, en))
                    continue
                }
                val old = e.optString("old", "")
                val new = e.optString("new", "")
                if (old.isEmpty()) {
                    errors.add("$pathStr：edits[$j] 的 old 为空（新建文件请用 create=true；按行号删除请用 delete_start/delete_end）")
                    opError = true
                    break
                }
                ops.add(EditOp(old, new))
            }
            if (opError) continue

            val (original, hasBom) = try {
                readTextWithBom(file)
            } catch (e: Exception) {
                errors.add("$pathStr：读取失败 - ${e.message}")
                continue
            }

            // 行尾归一化 文件与锚点都转成 LF 再匹配，避免 CRLF/LF 差异导致误报「找不到」。
            val ending = detectLineEnding(original)
            val originalLf = toLf(original)

            // 混用检查：line_* / insert_* / delete_* 与 old/new 不能在同一文件内混用。
            // 工具描述说"old/new 先应用，line_* 后应用"，实际实现是"line_* 先应用，old/new 后应用"。
            // 混用时 old 锚点基于原文件，但会在 line_* 修改后的内容里找 → 容易找不到或找到错位置。
            // 宁可让 AI 看到明确错误重试，也不要基于错位的锚点静默做错误编辑。
            if ((lineOps.isNotEmpty() || delOps.isNotEmpty()) && ops.isNotEmpty()) {
                errors.add(
                    "$pathStr：同一文件不能混用行号操作（line_* / insert_* / delete_*）和锚点操作（old/new）。" +
                            "请分两次 code_edit 调用，或统一用一种方式。"
                )
                continue
            }

            // 逐个锚点校验唯一性，并在内存里累积替换
            var applied = 0
            var anchorError = false
            val appliedEdits = mutableListOf<AppliedEdit>()

            // 行号类操作先做 line_start/line_end 与 delete_start/delete_end 都以
            // 「原文件行号」为准，所以必须在锚点替换之前、按行号从后往前应用。
            // 否则锚点替换改变行数后行号就错位了（旧代码的 delete 就有这个隐患）。
            var working = originalLf
            val lineSpans = mutableListOf<LineSpan>()
            lineOps.forEach { lineSpans.add(LineSpan(it.start, it.end, toLf(it.new), it.mode)) }
            delOps.forEach { lineSpans.add(LineSpan(it.start, it.end, "")) }
            if (lineSpans.isNotEmpty()) {
                val lines = working.split('\n').toMutableList()
                var rangeError = false
                for (s in lineSpans) {
                    if (s.end > lines.size) {
                        errors.add(
                            "$pathStr：按行号操作的行号超出文件行数（start=${s.start}, end=${s.end}, 文件共 ${lines.size} 行）"
                        )
                        rangeError = true
                        break
                    }
                }
                if (rangeError) continue
                for (s in lineSpans.sortedByDescending { it.start }) {
                    val newLines = if (s.newText.isEmpty()) emptyList() else s.newText.split('\n')
                    when (s.mode) {
                        LineMode.REPLACE -> {
                            val removed = lines.subList(s.start - 1, s.end).toList()
                            for (i in s.end - 1 downTo s.start - 1) lines.removeAt(i)
                            lines.addAll(s.start - 1, newLines)
                            appliedEdits.add(
                                AppliedEdit(removed.joinToString("\n"), s.newText, s.start, s.start)
                            )
                        }
                        LineMode.INSERT_BEFORE, LineMode.INSERT_AFTER -> {
                            // 插入点：前插在 start-1，后插在 end（即 start）
                            val at = if (s.mode == LineMode.INSERT_BEFORE) s.start - 1 else s.end
                            lines.addAll(at, newLines)
                            appliedEdits.add(
                                AppliedEdit("", s.newText, at + 1, at + 1)
                            )
                        }
                    }
                    applied++
                }
                working = lines.joinToString("\n")
            }
            for ((j, op) in ops.withIndex()) {
                val oldLf = toLf(op.old)
                val newLf = toLf(op.new)
                val count = countOccurrences(working, oldLf)
                if (count == 0) {
                    errors.add("$pathStr：edits[$j] 的 old 在文件中找不到\n" + anchorFailureHint(working, oldLf))
                    anchorError = true
                    break
                }
                if (count > 1) {
                    // 短锚点重复时，额外提示“锚点太短”，
                    // 引导模型扩大范围而不是盲目重试。
                    val shortHint = if (oldLf.lines().size <= 2) {
                        "\n    [警告] 锚点只有 ${oldLf.lines().size} 行，太短容易重复。建议带上前后各 1~2 行上下文。"
                    } else ""
                    errors.add(
                        "$pathStr：edits[$j] 的 old 在文件中出现 $count 次（必须恰好 1 次），请扩大锚点范围" +
                                shortHint + "\n" + anchorFailureHint(working, oldLf)
                    )
                    anchorError = true
                    break
                }
                val at = working.indexOf(oldLf)
                val oldStartLine = working.substring(0, at).count { it == '\n' } + 1
                // 只替换第一处（前面已校验唯一，这里显式限次更防御）
                working = working.replaceFirst(oldLf, newLf)
                // 替换起点行号不变，新内容就从原锚点起始行开始
                appliedEdits.add(AppliedEdit(oldLf, newLf, oldStartLine, oldStartLine))
                applied++
            }
            if (anchorError) continue

            // 转回原行尾风格
            val edited = fromLf(working, ending)
            plans.add(FilePlan(file, original, edited, applied, appliedEdits, false, hasBom))
        }

        if (errors.isNotEmpty()) {
            return@withContext ToolResult.fail(
                "编辑已取消（未修改任何文件）：\n" + errors.joinToString("\n") { "- $it" }
            )
        }
        if (plans.isEmpty()) {
            return@withContext ToolResult.fail("没有可执行的编辑")
        }

        // ---------- 阶段 2：备份 + 写盘 ----------
        val backupDir = File(DesktopPaths.exportsDir(), "code_backup")
        val stamp = DateFormats.formatFileTs()

        val written = mutableListOf<FilePlan>()
        try {
            for (plan in plans) {
                // 备份原文件（新建文件无需备份）
                if (!plan.isCreate) {
                    try {
                        backupDir.mkdirs()
                        // 用相对路径做 key，避免不同目录下的同名文件互相覆盖
                        val bak = File(backupDir, "${stamp}_${backupKey(plan.file)}")
                        bak.parentFile?.mkdirs()
                        writeTextWithBom(bak, plan.original, plan.hasBom)
                    } catch (e: Exception) {
                        DesktopLogger.warn("CodeEdit", "备份失败（继续）：${plan.file.absolutePath} - ${e.message}")
                    }
                }

                plan.file.parentFile?.mkdirs()
                writeTextWithBom(plan.file, plan.edited, plan.hasBom)
                written.add(plan)
            }
        } catch (e: Exception) {
            // 回滚已写入的文件
            DesktopLogger.error("CodeEdit", "写盘失败，开始回滚：${e.message}")
            var rollbackFailed = 0
            for (plan in written) {
                try {
                    if (plan.isCreate) {
                        plan.file.delete()
                    } else {
                        writeTextWithBom(plan.file, plan.original, plan.hasBom)
                    }
                } catch (re: Exception) {
                    rollbackFailed++
                    DesktopLogger.error("CodeEdit", "回滚失败：${plan.file.absolutePath} - ${re.message}")
                }
            }
            val msg = buildString {
                append("写盘失败：${e.message}\n")
                if (rollbackFailed == 0) append("已回滚全部已写入文件，磁盘内容未变。")
                else append("警告：$rollbackFailed 个文件回滚失败，请手动检查。")
            }
            return@withContext ToolResult.fail(msg)
        }

        // ---------- 阶段 2.5：verify 校验（失败自动回滚） ----------
        val verifyArr = args.optJSONArray("verify")
        if (verifyArr != null && verifyArr.length() > 0) {
            val vmsgs = mutableListOf<String>()
            var vok = true
            for (i in 0 until verifyArr.length()) {
                val v = verifyArr.optJSONObject(i)
                if (v == null) {
                    vmsgs.add("[错误] verify[$i] 不是对象")
                    vok = false
                    continue
                }
                val vtype = v.optString("type", "contains")
                val vpath = v.optString("path", "")
                val vtext = v.optString("text", "")
                val vf = File(vpath)
                if (!vf.isFile) {
                    vmsgs.add("[错误] verify[$i] 文件不存在：$vpath")
                    vok = false
                    continue
                }
                val content = try {
                    vf.readText(Charsets.UTF_8)
                } catch (e: Exception) {
                    vmsgs.add("[错误] verify[$i] 读取失败：${e.message}")
                    vok = false
                    continue
                }
                // verify 之前直接拿原始文件内容（CRLF）去 contains
                // 模型传的 vtext 是 LF，多行校验必然失败（明明存在却报“不含”）。
                // 两边都归一化成 LF 再比。
                val contentLf = toLf(content)
                val vtextLf = toLf(vtext)
                when (vtype) {
                    "contains" -> if (contentLf.contains(vtextLf)) {
                        vmsgs.add("[通过] verify[$i] $vpath 含目标字符串")
                    } else {
                        vmsgs.add("[错误] verify[$i] $vpath 不含：${vtext.take(80)}")
                        vok = false
                    }
                    "not_contains" -> if (!contentLf.contains(vtextLf)) {
                        vmsgs.add("[通过] verify[$i] $vpath 不含目标字符串")
                    } else {
                        vmsgs.add("[错误] verify[$i] $vpath 仍含：${vtext.take(80)}")
                        vok = false
                    }
                    else -> {
                        vmsgs.add("[错误] verify[$i] 未知 type：$vtype")
                        vok = false
                    }
                }
            }
            if (!vok) {
                // 回滚
                for (plan in written) {
                    try {
                        if (plan.isCreate) plan.file.delete()
                        else writeTextWithBom(plan.file, plan.original, plan.hasBom)
                    } catch (re: Exception) {
                        DesktopLogger.error("CodeEdit", "verify 回滚失败：${plan.file.absolutePath} - ${re.message}")
                    }
                }
                return@withContext ToolResult.fail(
                    "校验失败，已自动回滚（磁盘内容未变）：\n" + vmsgs.joinToString("\n") { "- $it" }
                )
            }
        }

        // ---------- 阶段 3：汇报 ----------
        val wantDiff = args.optBoolean("diff", true)
        val sb = StringBuilder()
        sb.append("编辑成功：${written.size} 个文件，共 ${written.sumOf { it.appliedCount }} 处替换\n")
        written.forEach { plan ->
            val tag = if (plan.isCreate) "（新建）" else "（${plan.appliedCount} 处）"
            sb.append("\n- ${plan.file.absolutePath}$tag")
        }
        if (wantDiff) {
            written.forEach { plan ->
                sb.append("\n\n===== ${plan.file.absolutePath} =====\n")
                plan.edits.forEach { e -> sb.append(renderUnifiedDiff(e)) }
            }
        }
        // 会话反馈 先记录调用（备份目录打印需要 callCount）
        val snapshot = EditSessionTracker.record(
            files = written.size,
            edits = written.sumOf { it.appliedCount },
        )

        // 备份目录只在首次调用时打印，避免每次刷屏占 token。
        // 后续调用需要时自己去 ~/.furagent/exports/code_backup/ 找。
        if (snapshot.callCount <= 1) {
            sb.append("\n\n备份目录：${backupDir.absolutePath}")
        }

        val hint = buildSessionHint(snapshot, written.size)
        if (hint.isNotBlank()) {
            sb.append("\n\n").append(hint)
        }

        ToolResult.ok(sb.toString())
    }

    /**
     * 根据会话累计调用次数生成成本提示。
     *
     * 为什么这么做 光在 system prompt 里写"必须合并"模型不理会，
     * 但在工具返回值里写"你已经浪费了 57K token"模型会立刻调整行为。
     *
     * 什么时候不提 避免刷屏：
     *   - 第 1 次调用：不提（还没有浪费）
     *   - 本次一次性改了很多文件（>= 3）：不提（说明这次就是批量改的）
     */
    private fun buildSessionHint(
        s: EditSessionTracker.Snapshot,
        currentFiles: Int,
    ): String {
        if (s.callCount < 2) return ""
        if (currentFiles >= 3) return ""

        val wastedK = s.tokensWasted / 1000

        return buildString {
            append("[警告] 这是本次会话第 ").append(s.callCount).append(" 次 code_edit。")
            append("累计已改 ").append(s.filesChanged).append(" 个文件、")
            append(s.editsApplied).append(" 处替换。")
            append("\n估算已浪费约 ").append(wastedK).append("K token")
            append("（每次额外调用都要把完整历史重发一遍）。")
            append("\n\n如果本次任务还有其他文件要改，请**立即在下一轮一次性提交**：")
            append("files 数组可以放任意多个文件，每个文件内的 edits 数组可以放任意多处替换。")
            append("所有文件会原子提交，任一锚点失败全部回滚，不会出现改一半的脏状态。")
        }
    }

    /**
     * 把一处替换渲染成 unified diff 片段。
     *
     * 不引 diff 库：编辑场景下“换了哪几行”就是 old/new 的直接对比，
     * 逐行加 -/+ 前缀即可，简单且零依赖。行号是新文件里的起始行。
     */
    private fun renderUnifiedDiff(e: AppliedEdit): String {
        val oldLines = e.oldText.split('\n')
        val newLines = if (e.newText.isEmpty()) emptyList() else e.newText.split('\n')
        val sb = StringBuilder()
        // - 侧用原文件行号，+ 侧用新文件行号
        sb.append("@@ -").append(e.oldStartLine).append(',').append(oldLines.size)
            .append(" +").append(e.newStartLine).append(',').append(newLines.size).append(" @@\n")
        oldLines.forEach { sb.append("-").append(it).append("\n") }
        // newText 为空（delete_lines 删除）时不输出空的 + 行
        newLines.forEach { sb.append("+").append(it).append("\n") }
        return sb.toString()
    }

    /** 把文件绝对路径转成可安全用作备份文件名的相对 key。 */
    private fun backupKey(file: File): String {
        val abs = file.absolutePath.replace('\\', '/').replace(':', '_')
        return abs.trimStart('/').replace(Regex("[\\/:*?\"<>|]"), "_")
    }

    /** 统计 needle 在 haystack 中出现的次数（非重叠）。 */
    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = haystack.indexOf(needle, idx + needle.length)
        }
        return count
    }
}