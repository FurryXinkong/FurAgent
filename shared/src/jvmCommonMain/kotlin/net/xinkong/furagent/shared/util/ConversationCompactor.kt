package net.xinkong.furagent.shared.util

/**
 * 会话级上下文压缩。
 *
 * 与 [ContextCompactor] 的分工：
 *  - ContextCompactor：压「一轮里过大的工具过程」，摘要插在历史原位；
 *  - ConversationCompactor：压「超出轮次上限的整段旧对话」（用户消息、助手回复、工具过程全包），
 *    摘要作为紧跟 system prompt 的**下一条 system 消息**注入。
 *
 * 为什么必须压整段：只压工具过程的话，被轮次截断丢掉的用户目标、约束、决策、
 * 偏好、结论就永远找不回来了，模型只能看到「最近几轮」，长任务必然跑偏。
 *
 * 增量策略：以会话为 key 缓存上一份摘要。旧对话只会变长，若新增量很小或距上次
 * 压缩不足 60s，就复用旧摘要并把新增的几条原样附在后面，不重复调模型。
 */
object ConversationCompactor {

    const val HEADER = "【会话压缩摘要 · 以下是较早对话的事实总结，由压缩模型生成】"
    const val FOOTER = "【摘要结束】这些是已发生的事实，不要重复已完成的操作；需要具体文件内容/命令输出时重新读取。接续任务时从「下一步」开始。"

    const val SYSTEM_PROMPT = """你是一个上下文压缩器。把下面这段较早的对话压缩成一份事实总结，供同一个 AI 助手在后续轮次继续工作。

规则：
1 只写原文里出现过的事实，禁止推测或补充。
2 必须保住：用户的目标与约束、用户明确表达或纠正过的偏好、已经做出的技术决策、已完成的工作与最终状态、涉及的文件路径/命令/参数/版本、失败原因与错误信息、还没解决的问题、接下来该做什么。
3 必须删掉：寒暄、重复提问、大段文件正文、完整日志、可以重跑得到的中间过程、模型的自我说明。
4 用中文，严格按下面六段输出，每段用短横线列表，没有内容写「无」：
用户目标与约束：
已确认的决策与偏好：
已完成的工作与最终状态：
失败与未解决的问题：
关键事实（路径 / 命令 / 参数 / 错误）：
下一步：
5 总长度不超过 1200 字。不要解释、不要用代码块包裹、不要输出标题以外的自述。"""

    private const val MAX_INPUT_CHARS = 20_000
    private const val MAX_OUTPUT_CHARS = 2600
    private const val RECOMPACT_MIN_NEW_CHARS = 1200
    private const val RECOMPACT_MIN_INTERVAL_MS = 60_000L
    private const val CACHE_MAX = 6

    private data class State(val digest: String, val sourceChars: Int, val at: Long)

    private val cache = object : LinkedHashMap<String, State>(CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, State>?): Boolean =
            size > CACHE_MAX
    }

    /**
     * @param sessionKey 会话标识；同一会话复用缓存
     * @param entries 被轮次截断丢掉的消息（按时间顺序，已用 [formatEntry] 格式化）
     * @param llm 一次性模型调用（system, user -> 正文）
     * @return 可直接放进 system 消息的摘要；失败返回空串
     */
    suspend fun summarize(
        sessionKey: String,
        entries: List<String>,
        llm: suspend (String, String) -> String?
    ): String {
        if (entries.isEmpty()) return ""
        val source = buildInput(entries)
        if (source.length < 300) return ""

        val cached = synchronized(cache) { cache[sessionKey] }
        if (cached != null) {
            val grew = source.length - cached.sourceChars
            val fresh = System.currentTimeMillis() - cached.at >= RECOMPACT_MIN_INTERVAL_MS
            if (grew < RECOMPACT_MIN_NEW_CHARS || !fresh) {
                // 增量兜底：新出现的几条原样附在旧摘要后面，保证新事实不丢，又不额外调模型
                val tail = if (grew > 0) {
                    "\n\n【上次压缩后新增的对话（未压缩）】\n" +
                            clip(entries.takeLast(4).joinToString("\n"), 1600)
                } else ""
                return wrap(cached.digest + tail)
            }
        }

        val raw = try {
            llm(SYSTEM_PROMPT, "下面是需要压缩的较早对话，请按要求输出六段式事实总结。\n\n$source")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        val cleaned = normalize(raw)
        if (cleaned.length < 40) {
            return cached?.let { wrap(it.digest) } ?: ""
        }
        synchronized(cache) {
            cache[sessionKey] = State(cleaned, source.length, System.currentTimeMillis())
        }
        return wrap(cleaned)
    }

    /** 把一条消息格式化成给压缩模型看的记录。 */
    fun formatEntry(role: String, type: String, content: String): String {
        val label = when {
            type == "tool_call" -> "工具调用"
            type == "tool_result" -> "工具结果"
            type == "system_note" -> "系统记录"
            type == "reasoning" -> "助手思考"
            role == "user" -> "用户"
            role == "assistant" -> "助手"
            role == "tool" -> "工具"
            else -> role.ifBlank { "记录" }
        }
        val body = content.trim()
        if (body.isEmpty()) return ""
        return "[$label] " + clip(body, 1200)
    }

    fun reset(sessionKey: String) {
        synchronized(cache) { cache.remove(sessionKey) }
    }

    // 内部

    private fun buildInput(entries: List<String>): String {
        val all = entries.filter { it.isNotBlank() }.joinToString("\n")
        if (all.length <= MAX_INPUT_CHARS) return all
        val head = MAX_INPUT_CHARS * 6 / 10
        return all.take(head) + "\n…（中段略）…\n" + all.takeLast(MAX_INPUT_CHARS - head)
    }

    private fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```").substringAfter('\n', s).removeSuffix("```").trim()
        }
        val first = s.lineSequence().firstOrNull()?.trim().orEmpty()
        if (first.length < 24 &&
            (first.startsWith("好的") || first.startsWith("以下") || first.endsWith("："))
        ) {
            s = s.lineSequence().drop(1).joinToString("\n").trim()
        }
        s = s.replace(Regex("\n{3,}"), "\n\n").trim()
        return if (s.length > MAX_OUTPUT_CHARS) s.take(MAX_OUTPUT_CHARS) + "\n…（摘要已截断）" else s
    }

    private fun wrap(body: String): String = "$HEADER\n$body\n$FOOTER"

    private fun clip(s: String, max: Int): String {
        if (s.length <= max) return s
        val head = max * 2 / 3
        return s.take(head) + "\n…（略）…\n" + s.takeLast(max - head)
    }
}
