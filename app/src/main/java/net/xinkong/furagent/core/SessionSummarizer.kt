package net.xinkong.furagent.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.data.*
import net.xinkong.furagent.shared.util.AnthropicCompat
import net.xinkong.furagent.shared.util.ApiProtocol
import net.xinkong.furagent.shared.util.DateFormats
import net.xinkong.furagent.shared.util.applyDisableThinking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 会话摘要器（Android 版，逻辑对齐桌面端 desktop.core.SessionSummarizer）。
 *
 * 目标：把「历史会话在聊什么」压成一句 20-30 字的标签，存进长期记忆，
 * 让新会话的模型知道「用户之前聊过什么」。
 *
 * 设计（混合方案）：
 *   1. 本地信号提取（零成本守门员）：
 *      - 工具名（tool_call 首行）
 *      - 修改的文件路径
 *      - 执行的命令
 *      - 中文 2-4 字 n-gram 词频（过滤停用词）
 *   2. 信号太弱 → 跳过，不调 LLM
 *   3. 信号够强 → 调「摘要模型」生成一句标签
 *      - 成功 → 存长期记忆（带 sessionId，幂等覆盖）
 *      - 失败 → 跳过，不存
 *
 * 触发：每次 App 启动时异步跑一次（不阻塞 UI）。
 * 脏标记：每个会话根据 session_summary_state.json 里记录的
 *         updatedAt / messageCount 判断是否变化；未变则跳过。
 *         效果上等价于「增量扫描」——只有新消息的会话才会重新摘要，
 *         老会话原样保留，不浪费 LLM 调用。
 *
 * 与桌面端的差异：
 *   - 桌面端用 ApiKeyResolver 取 Key（支持 env / 配置文件 / profile 三种来源），
 *     安卓端只有 profile.apiKey 一种来源，直接用。
 *   - 桌面端支持 anthropic 协议，安卓端摘要固定走 openai 协议
 *     （摘要模型通常是便宜的中转站模型，anthropic 场景极少）。
 */
object SessionSummarizer {

    private val RE_WHITESPACE = Regex("""\s+""")

    private const val TAG = "Summary"

    /** 摘要模型单次请求超时（秒） */
    private const val TIMEOUT_SECONDS = 30L

    /** 会话太短（消息数少于此）不值得摘要 */
    private const val MIN_MESSAGES = 6

    /** 本地信号太弱（有效词数少于此）就跳过 */
    private const val MIN_SIGNAL_WORDS = 3

    /** 涉及文件压缩后的总字符上限（防止路径吃掉太多 token） */
    private const val MAX_PATHS_CHARS = 160

    /** 每次最多处理多少个会话（防止首次运行卡太久） */
    private const val MAX_SESSIONS_PER_RUN = 20

    /** 摘要标签前缀，用于识别「这是会话摘要」 */
    private const val SUMMARY_PREFIX = "[会话摘要]"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** 中文停用词（高频但无信息量） */
    private val STOP_WORDS = setOf(
        "的", "了", "是", "我", "你", "他", "她", "它", "们", "这", "那", "有", "在",
        "和", "与", "就", "都", "也", "还", "但", "而", "或", "被", "把", "给", "让",
        "对", "从", "到", "为", "以", "及", "等", "个", "些", "什么", "怎么", "可以",
        "这个", "那个", "一个", "没有", "不是", "就是", "然后", "因为", "所以", "如果",
        "现在", "已经", "还是", "但是", "而且", "或者", "不过", "只是", "这样", "那样",
        "一下", "一点", "时候", "问题", "东西", "事情", "地方", "方式", "方法", "情况"
    )

    // 脏标记状态（记录每个会话上次摘要时的 updatedAt / messageCount）

    private fun stateFile(context: Context): File =
        File(context.filesDir, "session_summary_state.json")

    private fun loadState(context: Context): MutableMap<String, LongArray> {
        val map = mutableMapOf<String, LongArray>()
        try {
            val f = stateFile(context)
            if (!f.exists()) return map
            val o = JSONObject(f.readText(Charsets.UTF_8))
            o.keys().forEach { k ->
                val arr = o.optJSONArray(k) ?: return@forEach
                if (arr.length() >= 2) {
                    map[k] = longArrayOf(arr.optLong(0), arr.optLong(1))
                }
            }
        } catch (e: Exception) {
            AgentLogger.log(TAG, "读取摘要状态失败：${e.message}")
        }
        return map
    }

    private fun saveState(context: Context, map: Map<String, LongArray>) {
        try {
            val o = JSONObject()
            map.forEach { (k, v) ->
                o.put(k, JSONArray().apply { put(v[0]); put(v[1]) })
            }
            val f = stateFile(context)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(o.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            AgentLogger.log(TAG, "保存摘要状态失败：${e.message}")
        }
    }

    // 入口：启动时异步跑一次

    /**
     * App 启动时调用。内部异步，不阻塞调用方。
     * 若未配置摘要模型，直接返回。
     * 通过脏标记过滤，只有新消息的会话才会重新摘要。
     */
    suspend fun runOnStartup(context: Context, settings: AppSettings) {
        val profile = settings.currentSummaryProfile()
        if (profile == null) {
            AgentLogger.log(TAG, "未配置摘要模型，跳过会话摘要")
            return
        }
        if (profile.apiKey.isBlank()) {
            AgentLogger.log(TAG, "摘要模型未配置 API Key，跳过")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val sessions = SessionStore.list(context)
                if (sessions.isEmpty()) return@withContext

                val state = loadState(context)
                var processed = 0
                var summarized = 0

                for (meta in sessions) {
                    if (processed >= MAX_SESSIONS_PER_RUN) break
                    val id = meta.id
                    val updatedAt = meta.updatedAt
                    val messageCount = meta.messageCount
                    // 脏标记：没变化就跳过
                    val prev = state[id]
                    if (prev != null && prev[0] == updatedAt && prev[1] == messageCount.toLong()) {
                        continue
                    }
                    processed++

                    val session = SessionStore.load(context, id) ?: continue
                    val result = summarizeOne(session, profile)
                    if (result != null) {
                        summarized++
                        // 幂等覆盖：先删同 sessionId 的旧摘要，再存新的
                        MemoryStore.removeBySessionId(context, id)
                        MemoryStore.addSessionSummary(context, id, result.first, result.second)
                    }
                    // 无论成功与否都记脏标记，避免反复重试同一个失败会话
                    state[id] = longArrayOf(updatedAt, messageCount.toLong())
                }

                saveState(context, state)
                AgentLogger.log(TAG, "会话摘要完成：处理 $processed 个，成功 $summarized 个")
            } catch (e: Exception) {
                AgentLogger.log(TAG, "会话摘要异常：${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * 会话删除后，摘要本体由 SessionStore.delete 级联清掉，
     * 但 session_summary_state.json 里的脏标记会变成孤儿键（永久累积、无限增长）。
     * 删除会话 / 清空会话后调用本方法，把已不存在会话的标记清掉。
     */
    fun pruneState(context: Context) {
        try {
            val alive = SessionStore.list(context).map { it.id }.toHashSet()
            val state = loadState(context)
            if (state.isEmpty()) return
            val before = state.size
            state.keys.retainAll(alive)
            if (state.size != before) {
                saveState(context, state)
                AgentLogger.log(TAG, "清理孤儿摘要状态 ${before - state.size} 条")
            }
        } catch (e: Exception) {
            AgentLogger.log(TAG, "清理摘要状态失败：${e.message}")
        }
    }

    // 单个会话摘要

    private fun summarizeOne(
        session: net.xinkong.furagent.shared.data.Session,
        profile: ModelProfile
    ): Pair<String, String>? {
        if (session.messages.size < MIN_MESSAGES) {
            AgentLogger.log(TAG, "会话 ${session.id} 消息太少（${session.messages.size} < $MIN_MESSAGES），跳过")
            return null
        }

        val signals = extractSignals(session)
        // 守门员：看总信号量（工具 + 路径 + 高频词），不是只看中文词。
        // 纯代码调试会话可能没有中文正文，但工具/路径很丰富，恰恰最该摘要。
        val signalCount = signals.tools.size + signals.paths.size + signals.words.size
        if (signalCount < MIN_SIGNAL_WORDS) {
            AgentLogger.log(TAG, "会话 ${session.id} 信号太弱（$signalCount 项），跳过")
            return null
        }

        val prompt = buildSummaryPrompt(signals)
        val raw = callSummaryModel(profile, prompt) ?: return null
        val label = raw.trim().replace("\n", " ").take(130)
        if (label.isBlank()) return null
        AgentLogger.log(TAG, "会话 ${session.id} 摘要结果：$label")

        val date = DateFormats.formatDate(session.updatedAt)
        val content = "$SUMMARY_PREFIX[$date] $label"
        // 构造摘要详情 JSON，供 UI 展示「摘要怎么来的」
        val detail = try {
            JSONObject().apply {
                put("tools", JSONArray(signals.tools))
                put("paths", JSONArray(signals.paths))
                put("commands", JSONArray(signals.commands))
                put("words", JSONArray(signals.words))
                put("prompt", prompt)
                put("raw", raw.trim())
                put("messageCount", session.messages.size)
            }.toString()
        } catch (_: Exception) { "" }
        return content to detail
    }

    // 本地信号提取

    private data class Signals(
        val tools: List<String>,
        val paths: List<String>,
        val commands: List<String>,
        val words: List<String>
    )

    // 注意 不能用原始字符串 """..."""，否则 \u4e00 / \s / \d 会被当字面量。
    private val PATH_REGEX = Regex("[A-Za-z]:\\\\[^\\s\"'<>|*?]+|/[A-Za-z0-9_\\-./]{4,}")
    private val CJK_REGEX = Regex("[\u4e00-\u9fa5]{2,4}")

    private fun extractSignals(session: net.xinkong.furagent.shared.data.Session): Signals {
        val toolCounts = LinkedHashMap<String, Int>()
        val pathCounts = LinkedHashMap<String, Int>()
        val commandList = mutableListOf<String>()
        val wordCounts = LinkedHashMap<String, Int>()

        for (msg in session.messages) {
            val content = msg.content
            val type = msg.type
            when (type) {
                "tool_call" -> {
                    // content 首行是工具显示名，第二行起是 args JSON
                    val lines = content.split("\n", limit = 2)
                    val toolName = lines.getOrNull(0)?.trim().orEmpty()
                    if (toolName.isNotBlank()) {
                        toolCounts[toolName] = (toolCounts[toolName] ?: 0) + 1
                    }
                    val args = lines.getOrNull(1).orEmpty()
                    // 命令：execute_command 的 args 里含 command 字段
                    if (toolName.contains("命令") || toolName.contains("command", true)) {
                        Regex(""""command"\s*:\s*"([^"]+)"""").find(args)?.groupValues?.getOrNull(1)?.let {
                            if (it.isNotBlank()) commandList.add(it.take(80))
                        }
                    }
                    // 路径
                    PATH_REGEX.findAll(args).forEach { m ->
                        // args 是 JSON 字符串，路径里的反斜杠被转义成 \\，还原成单反斜杠
                        val p = m.value.replace("\\\\", "\\")
                        if (p.length in 4..200) pathCounts[p] = (pathCounts[p] ?: 0) + 1
                    }
                }
                "text" -> {
                    // 用户/助手正文：中文 n-gram 词频
                    CJK_REGEX.findAll(content).forEach { m ->
                        val w = m.value
                        if (w !in STOP_WORDS) wordCounts[w] = (wordCounts[w] ?: 0) + 1
                    }
                }
            }
        }

        val tools = toolCounts.entries.sortedByDescending { it.value }.take(8).map { it.key }
        val paths = compactPaths(pathCounts)
        val words = wordCounts.entries
            .filter { it.value >= 2 }   // 至少出现 2 次才算「高频」
            .sortedByDescending { it.value }
            .take(10)
            .map { it.key }

        return Signals(tools, paths, compactCommands(commandList), words)
    }

    /**
     * 路径压缩：把完整路径压成「末两段」，并合并同目录。
     *
     * 为什么 完整路径动辄上百字符，8 条就能吃掉上千 token，
     * 而模型只需要知道「在改哪个文件」。
     *
     * 策略：
     *   1. 每条路径只保留最后两段（目录名/文件名）；
     *   2. 同一目录下的多个文件合并成 "目录名/{a.kt,b.kt}"；
     *   3. 总长度限制在 MAX_PATHS_CHARS 内，超出就截断。
     */
    private fun compactPaths(pathCounts: Map<String, Int>): List<String> {
        // 按出现次数排序，取前 8 个
        val top = pathCounts.entries.sortedByDescending { it.value }.take(8).map { it.key }

        // 目录名 -> 文件名列表（保持顺序）
        val byDir = LinkedHashMap<String, MutableList<String>>()
        for (p in top) {
            val norm = p.replace('\\', '/')
            val segs = norm.split('/').filter { it.isNotBlank() }
            if (segs.isEmpty()) continue
            val file = segs.last()
            val dir = if (segs.size >= 2) segs[segs.size - 2] else ""
            byDir.getOrPut(dir) { mutableListOf() }.add(file)
        }

        val out = mutableListOf<String>()
        var total = 0
        for ((dir, files) in byDir) {
            val distinct = files.distinct()
            val item = if (dir.isBlank()) distinct.joinToString(",")
            else if (distinct.size == 1) "$dir/${distinct[0]}"
            else "$dir/{${distinct.joinToString(",")}}"
            if (total + item.length > MAX_PATHS_CHARS) break
            out.add(item)
            total += item.length + 1
        }
        return out
    }

    /**
     * 命令压缩：去重 + 截断。
     *
     * 为什么 同一会话里 AI 常反复跑几乎相同的 cd / 构建命令，
     * 原样拼进去既重复又长。
     */
    private fun compactCommands(commands: List<String>): List<String> {
        return commands
            .map { it.trim().replace(RE_WHITESPACE, " ") }
            .filter { it.isNotBlank() }
            .distinct()
            .take(3)
            .map { if (it.length > 60) it.take(60) + "..." else it }
    }

    private fun buildSummaryPrompt(s: Signals): String {
        val sb = StringBuilder()
        sb.append("下面是一个 AI 助手会话的本地统计信号，请用一句 60-100 字的中文概括")
        sb.append("这个会话在聊什么、用户可能的状态或需求。")
        sb.append("严格不超过 120 字，只输出这一句话，不要解释、不要换行。\n\n")
        if (s.tools.isNotEmpty()) sb.append("高频工具：${s.tools.joinToString("、")}\n")
        if (s.paths.isNotEmpty()) sb.append("涉及文件：${s.paths.joinToString("、")}\n")
        if (s.commands.isNotEmpty()) sb.append("执行命令：${s.commands.joinToString("；")}\n")
        if (s.words.isNotEmpty()) sb.append("高频词：${s.words.joinToString("、")}\n")
        return sb.toString()
    }

    // 调用摘要模型（非流式）

    private fun callSummaryModel(profile: ModelProfile, prompt: String): String? {
        val isAnthropic = ApiProtocol.normalize(profile.protocol) == ApiProtocol.ANTHROPIC
        return try {
            val body = if (isAnthropic) {
                // Anthropic Messages 协议：消息结构、system 位置、max_tokens 必填都不同，
                // 复用 shared 的 AnthropicCompat 统一转换，不手写。
                AnthropicCompat.buildRequestBody(
                    model = profile.model,
                    openAiMessages = JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    },
                    openAiTools = JSONArray(),
                    temperature = 1.0,
                    maxTokens = 2000,
                    stream = false
                )
            } else {
                JSONObject().apply {
                    put("model", profile.model)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    })
                    put("temperature", 1.0)
                    put("stream", false)
                    // 推理模型会先输出 reasoning_content，max_tokens 太小会被思考吃光
                    put("max_tokens", 2000)
                }
            }
            // 按模型能力强制关闭思考（复用 shared 的实现）。
            // Anthropic 不支持这些字段，跳过。
            if (!isAnthropic && profile.supportsDisableThinking) {
                applyDisableThinking(body, profile.baseUrl)
            }

            val request = Request.Builder()
                .url(profile.baseUrl)
                .addHeader("Content-Type", "application/json")
                .apply {
                    if (isAnthropic) {
                        // Anthropic 用 x-api-key，不是 Bearer
                        addHeader("x-api-key", profile.apiKey)
                        addHeader("anthropic-version", AnthropicCompat.API_VERSION)
                    } else {
                        addHeader("Authorization", "Bearer ${profile.apiKey}")
                    }
                }
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val resp = httpClient.newCall(request).execute()
            resp.use {
                if (!it.isSuccessful) {
                    AgentLogger.log(TAG, "摘要模型返回 ${it.code}，跳过")
                    return null
                }
                val text = it.body.string()
                val o = JSONObject(text)
                if (isAnthropic) {
                    // Anthropic 响应：{"content":[{"type":"text","text":"..."}]}
                    val arr = o.optJSONArray("content")
                    val sb = StringBuilder()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val blk = arr.optJSONObject(i) ?: continue
                            if (blk.optString("type") == "text") {
                                sb.append(blk.optString("text", ""))
                            }
                        }
                    }
                    sb.toString().takeIf { c -> c.isNotBlank() }
                } else {
                    val choice = o.optJSONArray("choices")?.optJSONObject(0)
                    val msg = choice?.optJSONObject("message")
                    val content = msg?.optString("content", "").orEmpty()
                    // 推理模型正文在 content，若为空则回退 reasoning_content
                    val reasoning = msg?.optString("reasoning_content", "").orEmpty()
                    val picked = content.ifBlank { reasoning }
                    picked.takeIf { c -> c.isNotBlank() }
                }
            }
        } catch (e: Exception) {
            AgentLogger.log(TAG, "摘要模型调用失败：${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}
