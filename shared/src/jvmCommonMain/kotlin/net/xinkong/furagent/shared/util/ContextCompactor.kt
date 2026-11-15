package net.xinkong.furagent.shared.util

/**
 * 上下文压缩：把被裁掉的旧工具过程压成**结构化摘要**，而不是直接丢弃。
 *
 * 两条路径：
 *  1. **模型压缩（首选）**：把被丢弃区段交给当前对话模型做一次后台摘要，
 *     由它判断哪些事实对继续干活有用，产出「已完成 / 改动 / 失败 / 待办」结构。
 *     这是真正意义上的压缩——理解语义后重写，而不是抽句子。
 *  2. **抽取式兜底**：模型未配置、超时、返回空时，退回本地规则摘要。
 *     压缩永远必须有确定结果，不能因为一次网络失败就让上下文裸丢。
 *
 * 关键点是保住 AI 继续干活所需的事实：做了什么、改了哪些文件、结果成败、
 * 当前任务清单状态。纯丢弃会让模型反复重做已完成的工作。
 *
 * 放在 jvmCommonMain：需要 LinkedHashMap 的访问顺序缓存做「同段只压一次」，
 * 双端（Android / Desktop）共用同一份实现。
 */
object ContextCompactor {

    /** 摘要正文（不含头尾标记）的字符上限。 */
    const val MAX_DIGEST_CHARS = 2400

    /** 送给模型压缩的原文上限（约 8K token），超出时保留头尾、丢中段。 */
    const val MAX_INPUT_CHARS = 24_000

    private const val MAX_LINE = 160

    /** 压缩标记，提示词里会告诉 AI 看到它该如何继续。 */
    const val HEADER = "【上下文压缩摘要 · 以下为较早的工具过程，已是事实记录】"
    const val FOOTER = "【摘要结束】不要重复执行上面已完成的操作；按任务清单从第一个未完成项继续。"

    /** 压缩模型的系统提示词：只做事实归纳，不推理、不补全。 */
    const val SYSTEM_PROMPT = """你是一个上下文压缩器，负责把 AI 助手与工具之间的旧交互记录压成一份事实摘要，供同一个 AI 在后续轮次继续工作时参考。

规则：
1 只写记录里出现过的事实，禁止推测、禁止补充记录里没有的内容。
2 保留继续工作必需的信息：已完成的操作、被创建/修改/删除的文件路径、执行过的关键命令、工具返回的关键结论、失败原因与错误信息、尚未完成的事项。
3 删掉可以重跑得到的内容：完整文件正文、大段日志、重复的相似调用、寒暄与思考过程。
4 同一文件被多次改动只写最终状态；同一命令重复执行只写一次。
5 用中文，输出四段，每段用短横线列表，没有内容就写「无」：
已完成：
关键改动与产出：
失败与风险：
待办与下一步：
6 总长度不超过 1200 字。不要输出解释、不要用代码块包裹。"""

    /**
     * 抽取式摘要（兜底路径）。
     *
     * @param entries 被裁掉的消息文本（按时间顺序）
     * @return 压缩后的摘要；内容为空时返回空串（调用方退回原来的「省略 N 条」文案）
     */
    fun digest(entries: List<String>): String {
        if (entries.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')

        var used = 0
        var kept = 0
        val seen = HashSet<String>(entries.size * 2)

        for (raw in entries) {
            if (used >= MAX_DIGEST_CHARS) break
            val line = extractLine(raw)
            if (line.isBlank()) continue
            val key = line.take(48)
            if (!seen.add(key)) continue          // 同一工具反复调用只留一条
            val status = statusMark(raw)
            val out = "$status ${line.take(MAX_LINE)}".trim()
            if (out.isBlank()) continue
            sb.append("- ").append(out).append('\n')
            used += out.length + 3
            kept++
        }

        if (kept == 0) return ""
        if (used >= MAX_DIGEST_CHARS) sb.append("- …（更早的过程已进一步省略）\n")
        sb.append(FOOTER)
        return sb.toString()
    }

    /**
     * 优先用模型压缩，失败则退回 [digest]。
     *
     * @param llm 一次性模型调用（system, user -> 正文）；为 null 表示不启用模型压缩
     */
    suspend fun summarize(
        entries: List<String>,
        llm: (suspend (system: String, user: String) -> String?)? = null
    ): String {
        if (entries.isEmpty()) return ""
        if (llm == null) return digest(entries)

        val raw = buildInput(entries)
        if (raw.length < 400) return digest(entries)

        cacheGet(raw)?.let { return it }

        val out = try {
            llm(SYSTEM_PROMPT, buildPrompt(raw))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        val cleaned = normalizeLlmOutput(out)
        // 模型偶尔只回一句「无」或空串：这种摘要没有信息量，宁可退回抽取式。
        val result = if (cleaned.length >= 40) wrap(cleaned) else digest(entries)
        if (result.isNotBlank()) cachePut(raw, result)
        return result
    }

    // 内部实现

    /** 把多条记录拼成送给模型的原文，超出上限时保留头 60% + 尾 40%。 */
    private fun buildInput(entries: List<String>): String {
        val sb = StringBuilder()
        entries.forEachIndexed { i, t ->
            val body = t.trim()
            if (body.isEmpty()) return@forEachIndexed
            sb.append("--- 记录 ").append(i + 1).append(" ---\n")
            // 单条超长（例如整份文件内容）只留头尾，避免一条就吃满预算
            sb.append(clip(body, 1500)).append('\n')
        }
        val all = sb.toString()
        if (all.length <= MAX_INPUT_CHARS) return all
        val head = MAX_INPUT_CHARS * 6 / 10
        val tail = MAX_INPUT_CHARS - head
        return all.take(head) + "\n…（中间部分略）…\n" + all.takeLast(tail)
    }

    private fun buildPrompt(raw: String): String = buildString {
        appendLine("下面是需要压缩的旧交互记录。请按要求输出四段式事实摘要。")
        appendLine()
        appendLine(raw)
    }

    /** 清洗模型输出：去掉代码块围栏、多余空行、自述性前缀。 */
    fun normalizeLlmOutput(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```")
            // 去掉语言标记行
            s = s.substringAfter('\n', s)
            s = s.removeSuffix("```").trim()
        }
        // 模型偶尔会先写一句「好的，以下是摘要：」，去掉这类前缀
        val firstLine = s.lineSequence().firstOrNull()?.trim().orEmpty()
        if (firstLine.length < 20 &&
            (firstLine.startsWith("好的") || firstLine.startsWith("以下") || firstLine.endsWith("："))
        ) {
            s = s.lineSequence().drop(1).joinToString("\n").trim()
        }
        s = s.replace(Regex("\n{3,}"), "\n\n").trim()
        return if (s.length > MAX_DIGEST_CHARS) s.take(MAX_DIGEST_CHARS) + "\n…（摘要已截断）" else s
    }

    private fun wrap(body: String): String = "$HEADER\n$body\n$FOOTER"

    private fun clip(s: String, max: Int): String {
        if (s.length <= max) return s
        val head = max * 2 / 3
        val tail = max - head
        return s.take(head) + "\n…（略）…\n" + s.takeLast(tail)
    }

    // 摘要缓存：同一段被丢弃的记录在连续几轮里可能重复压缩，命中即省一次模型调用
    private const val CACHE_MAX = 6
    private val cache = object : LinkedHashMap<String, String>(CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > CACHE_MAX
    }

    private fun cacheKey(raw: String): String = "${raw.length}:${raw.hashCode()}"

    private fun cacheGet(raw: String): String? = synchronized(cache) { cache[cacheKey(raw)] }

    private fun cachePut(raw: String, value: String) {
        synchronized(cache) { cache[cacheKey(raw)] = value }
    }

    /**
     * 从一条工具消息里抽出最有信息量的一行。
     * tool_call 的首行是工具名，第二行起是参数 JSON；tool_result 首行往往是结论。
     */
    private fun extractLine(raw: String): String {
        val lines = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(4)
            .toList()
        if (lines.isEmpty()) return ""
        val first = lines[0]
        // 参数行：优先保留带路径 / 命令 / 关键字的片段
        val args = lines.drop(1).firstOrNull { a ->
            a.startsWith("{") || a.contains("path", true) || a.contains("command", true)
        }
        return if (args != null) {
            val compact = args
                .replace(Regex("\\s+"), " ")
                .take(MAX_LINE - first.length.coerceAtMost(60))
            "$first  $compact"
        } else first
    }

    private fun statusMark(raw: String): String {
        val head = raw.take(240)
        return when {
            head.startsWith("失败") || head.startsWith("错误") || head.contains("执行异常") -> "[失败]"
            head.contains("用户已拒绝") -> "[被拒]"
            head.contains("成功") || head.startsWith("已") -> "[完成]"
            else -> "[记录]"
        }
    }
}
