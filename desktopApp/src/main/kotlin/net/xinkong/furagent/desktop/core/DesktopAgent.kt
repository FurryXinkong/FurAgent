package net.xinkong.furagent.desktop.core

import net.xinkong.furagent.shared.util.DateFormats
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import net.xinkong.furagent.desktop.data.ApiKeyResolver
import net.xinkong.furagent.desktop.data.DesktopMessage
import net.xinkong.furagent.desktop.data.DesktopSettings
import net.xinkong.furagent.desktop.data.MemoryStore
import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.desktop.system.EnvDetector
import net.xinkong.furagent.desktop.tools.DesktopTools
import net.xinkong.furagent.desktop.tools.EditSessionTracker
import net.xinkong.furagent.desktop.tools.PhoneTools
import net.xinkong.furagent.shared.tools.ToolResult
import net.xinkong.furagent.shared.ThinkingLevel
import net.xinkong.furagent.shared.util.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

object DesktopAgent {

    private const val SYSTEM_PROMPT = """
你是 FurAgent，运行在用户电脑上。时间 {TIME}。可调本地工具（文件/命令/系统/屏幕）与手机桥接工具，用法见各 description。

【工具发现】tools 只含核心工具 + tool_search/tool_load。
缺工具→先看 tool_search 目录，再 tool_load(name=) 或 tool_search(query=)。加载后下轮可用，本轮勿重复搜。

【规则】
1 执行操作必须调工具，禁止文字声称完成。
2 只依据工具返回判断成败；以"失败"开头才算失败。
3 危险操作（写/删文件、执行命令）前先说明。
4 复杂任务先列编号计划。
5 用户给 URL 要看内容→直接抓正文，别只看搜索摘要。
6 看图用 view_image；取时间用 time(op=now)。

【批量与并行】互不依赖的操作放同一条消息返回多个 tool_call，系统并行执行。
可并行：多个 read_file、多个 execute_command、read_file+list_files。
必须串行：tool_search/tool_load（改 tools 数组）、有依赖的操作。
改代码用 code_edit 一次提交所有改动（files 可放多文件多处），不要"改一个→看结果→再改下一个"。

【路径】~ 主目录；桌面 ~/Desktop；下载 ~/Downloads；文档 ~/Documents；图片 ~/Pictures。
产出目录（~/.furagent/）：screenshots 截图 / downloads 下载 / images 生成图 / exports 导出 / temp 临时 / logs 日志 / scripts 脚本。
其它文件操作保持主目录内，系统目录会被拒。

【工作目录】execute_command 默认 cwd 是应用启动目录（开发时仓库根，打包后 exe 所在目录），不是主目录也不是项目目录。相对路径先 cd 或用绝对路径。

【失败处理】参数错→修正重试一次；权限问题→说明原因让用户决定；同工具同参数连失 2 次→停止换思路。

【记忆】主动存：姓名/昵称、职业、偏好/习惯/忌讳、常用路径、用户纠正过的事实。不存：一次性信息、寒暄。取用 memory(op=recall)。

【回复】中文，简洁直接，不用 emoji，代码块用 ```语言 包裹。
"""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val VISION_TOOLS = setOf("screenshot", "view_image")

    /** 图片编码前的字节上限。与 DesktopTools.viewImage 的 5MB 对齐。 */
    private const val MAX_IMAGE_BYTES = 5L * 1024 * 1024

    private const val MIN_KEEP_MESSAGES = 40

    /** 【用量监控】单轮 prompt token 超过该值就打 warn 日志（不改变行为）。 */
    private const val PROMPT_TOKEN_WARN_THRESHOLD = 60_000

    /** 【失败计数】同一工具同一参数连续失败达到此值则中断循环。 */
    private const val TOOL_FAILURE_STREAK_LIMIT = 3

    /**
     * API 请求最大重试次数（429 / 5xx / 网络异常）。
     * 提到类级别是因为错误展示处也要用到（告诉用户“已重试 N 次”）。
     */
    private const val API_MAX_RETRIES = 3

    suspend fun runTurn(
        settings: DesktopSettings,
        history: List<DesktopMessage>,
        onUpdate: (List<DesktopMessage>) -> Unit,
        onThinking: (String) -> Unit,
        /**
         * 【错误回调】API 错误（HTTP 错误码 / 网络异常）。
         *
         * 与 onThinking 分开：错误要渲染成状态小气泡并保留到下次发消息，
         * 而 thinking 在任务结束时会被清空。也不走 onUpdate（不进 AI 聊天气泡）。
         */
        onError: (String) -> Unit = {},
        onReasoning: (String) -> Unit,
        onStream: (String) -> Unit,
        onStreamCommit: () -> Unit,
        /** 会话专用提示词：非空时追加到系统提示词末尾（每轮实时读取） */
        sessionPrompt: String = "",
        /**
         * 【子会话限轮】传非空时覆盖 settings.maxToolCallLoops。
         *
         * 为什么需要：子代理是被派出去干活的，它跑 100 轮也不会自己停下来，
         * 主会话设多少轮它就跑多少轮，然后全部烧掉。子会话必须有独立的硬上限。
         */
        maxLoopsOverride: Int? = null,
        /**
         * 【P0-2】会话 id：通过协程上下文（SessionIdElement）传给内部所有工具调用。
         * 多会话并发时，不同 runTurn 各自的 sessionId 互不干扰，工具归属正确，
         * 停止时按会话精确取消。
         * 空串表示「不归属任何会话」（如子会话收尾总结）。
         */
        sessionId: String = ""
    ): List<DesktopMessage> = withContext(Dispatchers.IO + SessionIdElement(sessionId)) {
        var current = history

        val profile = settings.currentProfile()
        if (profile == null) {
            val err = DesktopMessage(role = "assistant", content = "请先在设置里配置模型")
            val newList = current + err
            onUpdate(newList)
            return@withContext newList
        }

        val apiKeyResult = ApiKeyResolver.resolve(settings, profile)
        if (apiKeyResult !is ApiKeyResolver.Result.Found) {
            val reason = (apiKeyResult as ApiKeyResolver.Result.NotFound).message
            val err = DesktopMessage(
                role = "assistant",
                content = "无法获取 API Key：$reason\n\n${ApiKeyResolver.guideText()}"
            )
            val newList = current + err
            onUpdate(newList)
            return@withContext newList
        }
        val effectiveApiKey = apiKeyResult.key

        DesktopLogger.info("Agent", "开始对话 model=${profile.name} / ${profile.model} (key来源：${apiKeyResult.source})")

        PendingImages.clear()
        // 【会话开始】清空 code_edit 计数器
        EditSessionTracker.reset()

        val supportsVision = profile.supportsVision

        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", buildSystemPrompt(settings, sessionPrompt))
        })

        val envSection = try {
            EnvDetector.buildPromptSection()
        } catch (e: Exception) {
            DesktopLogger.warn("Agent", "环境检测注入失败：${e.message}")
            ""
        }
        if (envSection.isNotBlank()) {
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", "【环境信息】\n$envSection")
            })
        }

        val truncated = truncateHistory(current, settings.maxTurns, settings.contextTokenBudgetK)
        buildHistoryMessages(truncated, messages, settings, supportsVision)

        // 跨轮次累积"已加载工具"
        val loadedToolNames = mutableSetOf<String>()

        // 【失败计数】同一工具同一参数连续失败 3 次则中断（与手机端 Agent.kt 一致）。
        // 签名忽略翻页/数量类参数，避免模型只改 limit/offset 就绕过计数。
        val toolFailureStreak = mutableMapOf<String, Int>()

        val maxLoops = (maxLoopsOverride ?: settings.maxToolCallLoops).coerceAtLeast(1)

        repeat(maxLoops) { loopIndex ->
            // 【中断修复】每轮开头检查取消。用户点“停止”后 currentAgentJob.cancel()
            // 会在这里抛出 CancellationException，立即终止循环，不再继续请求/调工具。
            currentCoroutineContext().ensureActive()

            onThinking(if (loopIndex == 0) "思考中…" else "继续思考…")

            // 每轮重建 tools（loadedToolNames 会变化）
            val tools = buildToolsArray(settings, supportsVision, loadedToolNames)

            // 【用量监控】每轮请求前估算 prompt token，超阈值告警，便于定位烧钱点。
            val promptTokens = estimateMessagesTokens(messages)
            if (promptTokens > PROMPT_TOKEN_WARN_THRESHOLD) {
                DesktopLogger.warn(
                    "Agent",
                    "上下文偏大：约 $promptTokens tokens（${messages.length()} 条消息），" +
                            "本轮将按此计费，建议检查是否有大结果未落盘"
                )
            }

            DesktopLogger.info(
                "Agent",
                "第 ${loopIndex + 1} 轮（tools=${tools.length()}，已加载额外工具 ${loadedToolNames.size} 个，" +
                        "prompt≈$promptTokens tokens）"
            )

            val body = JSONObject().apply {
                put("model", profile.model)
                put("messages", messages)
                put("tools", tools)
                // 温度从 profile 读，默认 0.1（Agent 场景要稳定调工具）
                put("temperature", profile.temperature)
                // 惩罚参数：只在非默认值时发送（部分中转站不认，无脑发会 400）
                if (profile.presencePenalty != 0.0) put("presence_penalty", profile.presencePenalty)
                if (profile.frequencyPenalty != 0.0) put("frequency_penalty", profile.frequencyPenalty)
                put("stream", true)
            }
            // 【P1-2】按模型能力强制关闭/开启思考（与手机端 Agent.kt 一致）
            if (settings.thinkingLevel != ThinkingLevel.OFF) {
                applyEnableThinking(body, profile, settings.thinkingLevel)
            } else {
                applyDisableThinking(body, profile)
            }

            val request = Request.Builder()
                .url(profile.baseUrl)
                .addHeader("Authorization", "Bearer $effectiveApiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            // 【P1-5】带指数退避的重试：只重试可恢复错误（429/5xx/网络异常）
            val response = try {
                executeWithRetry(request, onThinking)
            } catch (e: Exception) {
                DesktopLogger.error("Agent", "网络请求失败（已重试）：${e.javaClass.simpleName}: ${e.message}")
                onThinking("")
                // 不走 onUpdate，避免占 AI 聊天气泡
                onError("网络请求失败 · ${e.message}")
                return@withContext current
            }

            if (!response.isSuccessful) {
                val errStr = try {
                    response.use { it.body.string() }
                } catch (e: Exception) {
                    DesktopLogger.error("Agent", "读取错误响应体失败：${e.message}")
                    ""
                }
                DesktopLogger.error("Agent", "API 错误 ${response.code}：${errStr.take(200)}")
                onThinking("")
                // 简短文案：`HTTP 429 · 用量配额已用尽`
                // 不走 onUpdate，避免占 AI 聊天气泡
                onError(
                    net.xinkong.furagent.shared.util.ApiErrorInfo
                        .format(response.code, errStr)
                )
                return@withContext current
            }

            val contentBuilder = StringBuilder()
            val reasoningBuilder = StringBuilder()
            var toolCalls: MutableMap<Int, ToolCallAccumulator> = mutableMapOf()

            try {
                // 【中断修复】流式读取放到可取消包装里：
                // readUtf8Line() 是阻塞读，协程取消不会中断它。
                // 取消时主动 response.close()，让阻塞读抛异常退出。
                withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext()[Job]
                    val handle = job?.invokeOnCompletion { cause ->
                        if (cause is CancellationException) {
                            try { response.close() } catch (_: Exception) { }
                        }
                    }
                    try {
                        response.body.source().use { source ->
                            toolCalls = parseSseStream(
                                readLine = { source.readUtf8Line() },
                                onContent = { content ->
                                    contentBuilder.append(content)
                                    onStream(content)
                                },
                                onReasoning = { r ->
                                    reasoningBuilder.append(r)
                                    onReasoning(r)
                                },
                                onParseError = { cls, data ->
                                    DesktopLogger.warn("Agent", "SSE 单行 JSON 解析失败：$cls，行：${data.take(120)}")
                                }
                            )
                        }
                    } finally {
                        handle?.dispose()
                    }
                }
            } catch (e: CancellationException) {
                // 用户取消：向上传播，不当作错误处理
                throw e
            } catch (e: Exception) {
                DesktopLogger.error("Agent", "SSE 流式解析失败：${e.javaClass.simpleName}: ${e.message}")
                onThinking("")
                val err = DesktopMessage(role = "assistant", content = "流式解析失败：${e.message}")
                current = current + err
                onUpdate(current)
                return@withContext current
            }

            val contentStr = contentBuilder.toString()

            if (toolCalls.isEmpty()) {
                onThinking("")
                val final = contentStr.ifBlank { "(无回复)" }
                current = current + DesktopMessage(
                    role = "assistant", content = final, type = "text",
                    reasoning = reasoningBuilder.toString()
                )
                onUpdate(current)
                onStreamCommit()
                DesktopLogger.info("Agent", "对话结束")
                return@withContext current
            }

            if (contentStr.isNotBlank()) {
                current = current + DesktopMessage(
                    role = "assistant", content = contentStr, type = "text"
                )
                onUpdate(current)
                onStreamCommit()
            }

            // 本轮 reasoning 作为独立消息落库（先于工具调用）。
            // 这样历史会话里能看到「思考过程 → 工具调用 → 思考过程 → ...」的完整过程。
            if (reasoningBuilder.isNotEmpty()) {
                current = current + DesktopMessage(
                    role = "assistant", content = reasoningBuilder.toString(), type = "reasoning"
                )
                onUpdate(current)
                // 【清空实时缓冲】本轮思考已落库成独立 reasoning 消息，
                // 必须把运行时缓冲一并清掉。之前只有“无工具调用 + 有正文”才走
                // onStreamCommit，所以“只有思考 + 工具调用”的轮次缓冲从不清：
                // 最后一条工作过程会把旧思考一直悬着，越积越长，
                // 既与已保存的消息重复，又看不到最新的思考。
                onStreamCommit()
            }

            // 给每个 tool_call 算一个稳定的 id（三处统一）
            val callIds = mutableMapOf<Int, String>()
            val toolCallsForApi = JSONArray().apply {
                toolCalls.toSortedMap().forEach { (idx, acc) ->
                    val callId = acc.id.ifBlank { "call_${loopIndex}_$idx" }
                    callIds[idx] = callId
                    put(JSONObject().apply {
                        put("id", callId)
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", acc.name)
                            put("arguments", acc.arguments.toString())
                        })
                    })
                }
            }
            messages.put(JSONObject().apply {
                put("role", "assistant")
                put("content", contentStr.ifBlank { "" })
                put("tool_calls", toolCallsForApi)
            })

            // 【P1-3】工具并行执行：
            //   - 元工具（tool_search / tool_load）会改 loadedToolNames，必须串行；
            //   - 只要本轮含元工具，就整体串行（保守，避免竞态）；
            //   - 否则并行执行，UI 更新仍按原顺序串行，保证展示顺序稳定。
            val sortedCalls = toolCalls.toSortedMap().toList()
            val hasMetaTool = sortedCalls.any { it.second.name == "tool_search" || it.second.name == "tool_load" }

            // 先按顺序把 tool_call 占位消息推给 UI（保持展示顺序）
            // 【并行可见性】并行批次给同批调用写同一个 parallelGroup，
            // UI 据此把同批条目収进「并行 N」分组，用户能看出这几个是一起跑的。
            val parallelTag = if (!hasMetaTool && sortedCalls.size > 1) {
                "pg_${System.currentTimeMillis()}_$loopIndex"
            } else ""
            sortedCalls.forEach { (idx, acc) ->
                val callId = callIds[idx] ?: "call_${loopIndex}_$idx"
                current = current + DesktopMessage(
                    role = "tool", content = "${friendlyName(acc.name)}\n${prettyJson(acc.arguments.toString())}",
                    type = "tool_call", toolCallId = callId, parallelGroup = parallelTag
                )
            }
            onUpdate(current)

            // 执行（并行或串行），结果按 idx 收集
            val results = LinkedHashMap<Int, Pair<ToolResult, Long>>()
            // 【工作过程实时化】
            // 原来所有工具跑完才把结果一次性追加进历史、一次性 onUpdate，
            // 所以磨刀那一段时间 UI 完全不动；等它跑完又“刷”地一下全出来。
            // 现在每个工具一结束就立即往历史里追一条并刷 UI。
            val uiLock = Any()
            fun reportToolResult(idx: Int, r: ToolResult, durationMs: Long) {
                val callId = callIds[idx] ?: "call_${loopIndex}_$idx"
                val snap: List<DesktopMessage>
                synchronized(uiLock) {
                    results[idx] = r to durationMs
                    current = current + DesktopMessage(
                        role = "tool", content = r.output,
                        type = "tool_result", toolCallId = callId,
                        durationMs = durationMs, parallelGroup = parallelTag
                    )
                    snap = current
                }
                onUpdate(snap)
            }

            if (hasMetaTool || sortedCalls.size <= 1) {
                sortedCalls.forEach { (idx, acc) ->
                    // 【中断修复】每个工具执行前检查取消
                    currentCoroutineContext().ensureActive()
                    val argsJson = acc.arguments.toString()
                    onThinking("正在执行工具：${friendlyName(acc.name)}")
                    DesktopLogger.info("Agent", "调用工具（串行）：${acc.name}")
                    val startMs = System.currentTimeMillis()
                    val r = runOneTool(acc.name, argsJson, settings, loadedToolNames)
                    reportToolResult(idx, r, System.currentTimeMillis() - startMs)
                }
            } else {
                onThinking("正在并行执行 ${sortedCalls.size} 个工具…")
                DesktopLogger.info("Agent", "并行执行 ${sortedCalls.size} 个工具：${sortedCalls.map { it.second.name }}")
                // 【并行卡死修复】
                //   1) 用 supervisorScope 替代 coroutineScope：单个子协程失败不再取消整批；
                //   2) 每个 async 内部 try/catch 兜底，把异常（含 CancellationException 之外的
                //      一切 Throwable）转成 ToolResult.fail，保证子协程永不向外抛异常，
                //      awaitAll 一定能正常返回，不会出现“某个工具卡住导致整批 awaitAll 挂起”。
                //   3) 注意：CancellationException 必须原样重抛，否则会吞掉用户的中断信号。
                val executed = supervisorScope {
                    sortedCalls.map { (idx, acc) ->
                        async {
                            // 【中断修复】并行分支也要检查取消。
                            // 之前只在串行路径和收尾阶段检查，并行时用户点停止后
                            // 所有分支仍会全部跑完（工具副作用已发生）。
                            currentCoroutineContext().ensureActive()
                            // 【实时阶段】并行时也告诉 UI 现在跑哪个工具，
                            // 否则并行跑一大堆工具时界面一直停在“并行执行 N 个工具”。
                            onThinking("正在执行工具：${friendlyName(acc.name)}")
                            val startMs = System.currentTimeMillis()
                            val r = try {
                                runOneTool(acc.name, acc.arguments.toString(), settings, loadedToolNames)
                            } catch (ce: kotlinx.coroutines.CancellationException) {
                                throw ce
                            } catch (t: Throwable) {
                                DesktopLogger.error(
                                    "Agent",
                                    "并行工具 ${acc.name} 抛出异常：${t.javaClass.simpleName}: ${t.message}"
                                )
                                ToolResult.fail("执行异常：${t.javaClass.simpleName}: ${t.message}")
                            }
                            // 【实时】这个工具一结束就刷 UI，不等其他并行工具
                            reportToolResult(idx, r, System.currentTimeMillis() - startMs)
                            Triple(idx, r, System.currentTimeMillis() - startMs)
                        }
                    }.awaitAll()
                }
                executed.forEach { (idx, r, dur) -> results[idx] = r to dur }
            }

            // 串行收尾：UI 更新 + messages.put + 图片附加（保持顺序，drain 只做一次）
            sortedCalls.forEach { (idx, acc) ->
                // 【中断修复】工具跑完后、发结果前再检查一次取消。
                // 否则用户已点停止，工具结果仍会被推给 UI（“中断像安慰人”的元凶之一）。
                currentCoroutineContext().ensureActive()
                val callId = callIds[idx] ?: "call_${loopIndex}_$idx"
                val result = results[idx]?.first ?: ToolResult.fail("工具未执行")

                // tool_result 已在 reportToolResult 里追加并刷过 UI，这里只补模型侧消息

                // 工具返回原样送给模型
                messages.put(JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", callId)
                    put("content", result.output)
                })

                // 【失败计数】元工具跳过；同一签名连续失败 3 次则中断循环。
                if (acc.name != "tool_search" && acc.name != "tool_load") {
                    val argsObj = try { JSONObject(acc.arguments.toString()) } catch (_: Exception) { JSONObject() }
                    val sig = toolSignature(acc.name, argsObj)
                    if (isToolFailure(result.output)) {
                        val streak = (toolFailureStreak[sig] ?: 0) + 1
                        toolFailureStreak[sig] = streak
                        DesktopLogger.warn("Agent", "工具 ${acc.name} 失败第 $streak 次（签名：${sig.take(80)}）")
                        if (streak >= TOOL_FAILURE_STREAK_LIMIT) {
                            DesktopLogger.error("Agent", "工具连续失败 $TOOL_FAILURE_STREAK_LIMIT 次，中断循环")
                            onThinking("")
                            val msg = "我连续 $TOOL_FAILURE_STREAK_LIMIT 次用相同参数调用" +
                                    "「${friendlyName(acc.name)}」都失败了，停止重试。\n\n" +
                                    "最后一次错误：${result.output.take(300)}"
                            current = current + DesktopMessage(role = "assistant", content = msg, type = "text")
                            onUpdate(current)
                            onStreamCommit()
                            return@withContext current
                        }
                    } else {
                        toolFailureStreak.remove(sig)
                    }
                }
            }

            // vision 二次拦截：本轮所有工具跑完后统一 drain 一次
            val pending = PendingImages.drain()
            if (pending.isEmpty()) {
                // nothing
            } else if (supportsVision) {
                val arr = JSONArray()
                arr.put(JSONObject().apply {
                    put("type", "text")
                    put("text", "以下是我刚获取的图片，请基于图片内容继续。")
                })
                var attached = 0
                pending.forEach { p ->
                    val dataUrl = readAsDataUrl(p, settings)
                    if (dataUrl == null) {
                        DesktopLogger.warn("Agent", "图片未能附加（过大或读取失败）：$p")
                        return@forEach
                    }
                    attached++
                    arr.put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().put("url", dataUrl))
                    })
                }
                if (attached > 0) {
                    messages.put(JSONObject().apply {
                        put("role", "user")
                        put("content", arr)
                    })
                } else {
                    DesktopLogger.warn("Agent", "本轮 ${pending.size} 张图片全部附加失败，未注入上下文")
                }
            } else {
                DesktopLogger.info(
                    "Agent",
                    "模型 [${profile.name}] 不支持 vision，已丢弃 ${pending.size} 张待附加图片"
                )
            }
        }

        onThinking("")
        val finalMsg = DesktopMessage(
            role = "assistant",
            content = "已达到最大工具调用轮数（$maxLoops），请重试"
        )
        current = current + finalMsg
        onUpdate(current)
        current
    }

    // 构建分层 tools 数组

    /**
     * 核心工具 + 已加载工具 + 元工具（DesktopTools 侧）
     * + 已加载的手机桥接工具（PhoneTools 侧）。
     *
     * PhoneTools 目前全部走"按需"（不在 CORE_TOOL_NAMES 里），
     * 只有 AI 通过 tool_load 加载后才出现在 tools 数组里。
     */
    private fun buildToolsArray(
        settings: DesktopSettings,
        supportsVision: Boolean,
        loadedNames: Set<String>
    ): JSONArray {
        val arr = JSONArray()
        val disabled = settings.disabledTools

        // DesktopTools（含 MCP）：核心 + 已加载 + 两个元工具
        DesktopTools.getToolListWithLoaded(loadedNames).forEach { t ->
            if (t.name in disabled) return@forEach
            if (!supportsVision && t.name in VISION_TOOLS) return@forEach
            // 单个工具的 schema 坏了只跳过它，不能炸掉整个对话
            val params = try {
                JSONObject(t.paramsSchema)
            } catch (e: Exception) {
                DesktopLogger.warn(
                    "Agent",
                    "工具 ${t.name} paramsSchema 解析失败，本轮已跳过：${e.message}"
                )
                return@forEach
            }
            arr.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", t.name)
                    put("description", t.description)
                    put("parameters", params)
                })
            })
        }

        // PhoneTools：只在已加载时出现
        PhoneTools.TOOLS.forEach { t ->
            if (t.name in disabled) return@forEach
            if (t.name !in loadedNames) return@forEach
            val params = try {
                JSONObject(t.paramsSchema)
            } catch (e: Exception) {
                DesktopLogger.warn(
                    "Agent",
                    "工具 ${t.name} paramsSchema 解析失败，本轮已跳过：${e.message}"
                )
                return@forEach
            }
            arr.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", t.name)
                    put("description", t.description)
                    put("parameters", params)
                })
            })
        }

        return arr
    }

    // 历史截断 / 重建

    private fun truncateHistory(
        history: List<DesktopMessage>,
        maxTurns: Int,
        budgetKTokens: Int
    ): List<DesktopMessage> {
        if (history.isEmpty()) return history
        if (maxTurns <= 0) return history

        // 原为 minOf，方向反了：maxTurns 调小时 minKeep 反而变小，
        // 与“至少保留 MIN_KEEP_MESSAGES 条”的意图相反。改为 maxOf。
        val minKeep = maxOf(MIN_KEEP_MESSAGES, maxTurns * 2)
        val startIdx = computeTruncateStart(
            history = history,
            maxTurns = maxTurns,
            minKeep = minKeep,
            roleOf = { it.role },
            typeOf = { it.type }
        )
        // 【token 预算二次裁剪】见 trimByTokenBudget
        val byTurns = if (startIdx <= 0) history else history.subList(startIdx, history.size)
        return trimByTokenBudget(byTurns, budgetKTokens)
    }


    /** 单条消息的 token 粗估（正文 + 图片）。 */
    private fun estimateMessageTokens(msg: DesktopMessage): Int =
        TokenEstimator.estimate(msg.content) +
            msg.imagePaths.size * TokenEstimator.PER_IMAGE_TOKENS

    /**
     * 【token 预算二次裁剪】按轮次截断之后可能仍然巨大：
     * 一轮里读十个大文件，光工具输出就能吃掉几万 token，maxTurns 完全拦不住。
     * 这里再按 token 预算从最旧的「工具过程」开始丢，人类消息与助手文字回复一律保留。
     */
    private fun trimByTokenBudget(
        history: List<DesktopMessage>,
        budgetKTokens: Int
    ): List<DesktopMessage> {
        val budget = budgetKTokens * 1000
        if (budget <= 0) return history
        return trimToolProcessByBudget(
            history = history,
            budgetTokens = budget,
            entryTokens = { estimateMessageTokens(it) },
            isToolEntry = {
                it.type == "tool_call" || it.type == "tool_result" || it.type == "system_note"
            },
            makeElidedNote = { n ->
                DesktopMessage(
                    role = "assistant",
                    content = "（此处省略 $n 条工具调用记录，为节省上下文未逐条保留）",
                    type = "system_note"
                )
            }
        ).kept
    }

    private fun buildHistoryMessages(
        history: List<DesktopMessage>,
        messages: JSONArray,
        settings: DesktopSettings,
        supportsVision: Boolean
    ) {
        var i = 0
        while (i < history.size) {
            val msg = history[i]
            when (msg.type) {
                "tool_call" -> {
                    val firstLine = msg.content.substringBefore('\n').trim()
                    val argsPart = msg.content.substringAfter('\n', "").trim()
                    val toolName = resolveToolName(firstLine)
                    val argsJson = try {
                        if (argsPart.isBlank()) "{}" else JSONObject(argsPart).toString()
                    } catch (_: Exception) { "{}" }
                    val callId = msg.toolCallId.ifBlank {
                        "call_${msg.id.replace("-", "")}"
                    }

                    messages.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", "")
                        put("tool_calls", JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", callId)
                                put("type", "function")
                                put("function", JSONObject().apply {
                                    put("name", toolName)
                                    put("arguments", argsJson)
                                })
                            })
                        })
                    })

                    // 【关键修复】按 toolCallId 配对，不能只看位置相邻。
                    // 并行跑一批工具时，历史里是「A的call, B的call, C的call,
                    // B的result, A的result, C的result」这种错开的顺序。
                    // 旧逻辑只看下一条是不是 tool_result，于是 A 被判成
                    // “未记录返回结果”，模型以为工具失败 → 重跑一遍（重复读
                    // 同一个文件、重复跑同一个命令），又慢又浪费钱。
                    val resultMsg = history.firstOrNull {
                        it.type == "tool_result" && it.toolCallId.isNotBlank() &&
                                it.toolCallId == callId
                    } ?: run {
                        // 兼容旧数据（没存 toolCallId）：退回位置相邻判断
                        val pos = history.indexOf(msg) + 1
                        history.getOrNull(pos)
                            ?.takeIf { it.type == "tool_result" && it.toolCallId.isBlank() }
                    }

                    if (resultMsg != null) {
                        messages.put(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", callId)
                            put("content", resultMsg.content)
                        })
                        i += 2
                    } else {
                        messages.put(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", callId)
                            put("content", "（该工具调用未记录返回结果）")
                        })
                        i += 1
                    }
                }
                "tool_result" -> i += 1
                "system_note" -> i += 1
                else -> {
                    val hasBody = msg.content.isNotBlank() || msg.imagePaths.isNotEmpty()
                    if (hasBody && (msg.role == "user" || msg.role == "assistant")) {
                        messages.put(buildMessageObject(msg, settings, supportsVision))
                    } else if (hasBody && msg.role == "system") {
                        // 注入给模型的系统通知（如「子代理完成」回流）此前被这里丢弃，
                        // 模型永远看不到，只能自己调 sub_session 去查。
                        // 请求中不允许中途插入 system 角色，故以 user 身份附带并标明来源。
                        messages.put(JSONObject().apply {
                            put("role", "user")
                            put("content", "【系统通知】\n" + msg.content)
                        })
                    }
                    i += 1
                }
            }
        }
    }

    private fun buildMessageObject(
        msg: DesktopMessage,
        settings: DesktopSettings,
        supportsVision: Boolean
    ): JSONObject {
        val obj = JSONObject()
        obj.put("role", msg.role)
        if (msg.imagePaths.isEmpty() || !supportsVision) {
            obj.put("content", msg.content)
            return obj
        }
        val arr = JSONArray()
        if (msg.content.isNotBlank()) {
            arr.put(JSONObject().apply { put("type", "text"); put("text", msg.content) })
        }
        var hasImage = false
        for (path in msg.imagePaths) {
            val dataUrl = readAsDataUrl(path, settings) ?: continue
            hasImage = true
            arr.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", dataUrl))
            })
        }
        obj.put("content", if (hasImage || msg.content.isNotBlank()) arr else msg.content)
        return obj
    }

    // 【批次 14 性能】图片 Base64 缓存。
    // 原实现每次 buildMessageObject 都会重新读文件、压缩、转 Base64。
    // 当历史里有图片时，每一轮对话（多次请求）都会重复这套极其耗 CPU 和 IO 的操作。
    private val dataUrlCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun readAsDataUrl(path: String, settings: DesktopSettings): String? {
        return try {
            val f = File(path)
            if (!f.exists() || !f.isFile) return null

            // 缓存 Key 包含文件路径、修改时间、压缩参数，任何一个变了都重新生成
            val cacheKey = "${f.absolutePath}_${f.lastModified()}_${settings.imageCompress}_${settings.imageCompressQuality}_${settings.imageCompressMaxDim}"
            dataUrlCache[cacheKey]?.let { return it }

            val effectiveMaxDim = if (settings.imageCompress) settings.imageCompressMaxDim else 1920
            val effectiveQuality = if (settings.imageCompress) settings.imageCompressQuality else 90

            val bytes = if (isImageExtension(f.extension.lowercase())) {
                compressImageBytes(f, effectiveQuality, effectiveMaxDim) ?: f.readBytes()
            } else {
                f.readBytes()
            }
            if (bytes.size > MAX_IMAGE_BYTES) {
                DesktopLogger.warn(
                    "Agent",
                    "图片过大（${bytes.size} 字节 > $MAX_IMAGE_BYTES 字节）跳过：$path"
                )
                return null
            }
            val ext = path.substringAfterLast('.', "jpg").lowercase()
            val mime = when (ext) {
                "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"
                else -> "image/jpeg"
            }
            val dataUrl = "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
            dataUrlCache[cacheKey] = dataUrl
            
            // 防内存泄漏：缓存超过 100 个时清空
            if (dataUrlCache.size > 100) dataUrlCache.clear()
            
            dataUrl
        } catch (e: Exception) {
            DesktopLogger.error("Agent", "读取图片失败：$path - ${e.message}")
            null
        }
    }

    private fun isImageExtension(ext: String): Boolean =
        ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    private fun compressImageBytes(src: File, quality: Int, maxDim: Int): ByteArray? {
        return try {
            val img = javax.imageio.ImageIO.read(src) ?: return null
            val scale = minOf(
                maxDim.toFloat() / img.width,
                maxDim.toFloat() / img.height,
                1f
            )
            val newW = (img.width * scale).toInt().coerceAtLeast(1)
            val newH = (img.height * scale).toInt().coerceAtLeast(1)
            val buffered = java.awt.image.BufferedImage(
                newW, newH, java.awt.image.BufferedImage.TYPE_INT_RGB
            )
            val g = buffered.createGraphics()
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
            )
            g.drawImage(img, 0, 0, newW, newH, null)
            g.dispose()

            val bos = java.io.ByteArrayOutputStream()
            val writers = javax.imageio.ImageIO.getImageWritersByFormatName("jpg")
            if (writers.hasNext()) {
                val writer = writers.next()
                val ios = javax.imageio.ImageIO.createImageOutputStream(bos)
                writer.output = ios
                val param = writer.defaultWriteParam
                param.compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
                param.compressionQuality = (quality / 100f).coerceIn(0.1f, 1f)
                writer.write(
                    null,
                    javax.imageio.IIOImage(buffered, null, null),
                    param
                )
                ios.close()
                writer.dispose()
            } else {
                javax.imageio.ImageIO.write(buffered, "jpg", bos)
            }
            bos.toByteArray()
        } catch (e: Exception) {
            DesktopLogger.warn("Agent", "压缩图片失败：${e.message}")
            null
        }
    }

    /**
     * displayName → name 反查。
     * 加 tool_search / tool_load 两个元工具映射。
     */
    private fun resolveToolName(displayOrName: String): String {
        when (displayOrName) {
            "工具搜索" -> return "tool_search"
            "加载工具" -> return "tool_load"
        }
        if (displayOrName == "tool_search" || displayOrName == "tool_load") return displayOrName
        if (DesktopTools.TOOLS.any { it.name == displayOrName }) return displayOrName
        if (PhoneTools.TOOLS.any { it.name == displayOrName }) return displayOrName
        DesktopTools.TOOLS.firstOrNull { it.displayName == displayOrName }?.let { return it.name }
        PhoneTools.TOOLS.firstOrNull { it.displayName == displayOrName }?.let { return it.name }
        return displayOrName
    }

    /**
     * 【P1-5】带指数退避的 HTTP 请求。
     *
     * 只重试“可恢复”错误：
     *   - 网络异常（IOException / 超时）
     *   - HTTP 429（限流）、500/502/503/504（服务端瞬时故障）
     * 不重试 4xx（除 429）——那是请求本身的问题，重试无意义。
     *
     * 退避：1s → 2s → 4s（带 ±30% 抖动，避免惊群）。
     */
    private suspend fun executeWithRetry(
        request: Request,
        onThinking: (String) -> Unit
    ): Response {
        val maxRetries = API_MAX_RETRIES
        var lastError: Exception? = null
        for (attempt in 0..maxRetries) {
            try {
                val resp = executeCancellable(httpClient.newCall(request))
                val code = resp.code
                // 429 要区分「限速」和「配额用尽」：
                //   限速 → 重试有意义
                //   配额用尽 → 重试纯浪费（要等重置或充值）
                val retryable = if (code == 429) {
                    val body = try {
                        resp.peekBody(4096).string()
                    } catch (_: Exception) { "" }
                    net.xinkong.furagent.shared.util.ApiErrorInfo.explain(429, body).retryable
                } else {
                    code == 500 || code == 502 || code == 503 || code == 504
                }
                if (!retryable || attempt == maxRetries) return resp
                // 可重试：关掉响应体，退避后重试
                resp.close()
                val baseMs = 1000L shl attempt // 1s, 2s, 4s
                val jitter = (baseMs * 0.3 * (Math.random() * 2 - 1)).toLong()
                val waitMs = (baseMs + jitter).coerceAtLeast(200L)
                DesktopLogger.warn("Agent", "API $code，第 ${attempt + 1} 次重试，等待 ${waitMs}ms")
                // 【友好提示】用共享错误解释器，与安卓端文案一致
                onThinking(
                    net.xinkong.furagent.shared.util.ApiErrorInfo
                        .retryHint(code, attempt + 1, maxRetries + 1, waitMs)
                )
                delay(waitMs.milliseconds)
            } catch (e: CancellationException) {
                // 用户取消：直接向上传播，不重试
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt == maxRetries) throw e
                val baseMs = 1000L shl attempt
                val jitter = (baseMs * 0.3 * (Math.random() * 2 - 1)).toLong()
                val waitMs = (baseMs + jitter).coerceAtLeast(200L)
                DesktopLogger.warn("Agent", "网络异常（${e.javaClass.simpleName}），第 ${attempt + 1} 次重试，等待 ${waitMs}ms")
                onThinking("网络异常，${waitMs / 1000}s 后重试…")
                delay(waitMs.milliseconds)
            }
        }
        throw lastError ?: IllegalStateException("请求失败且无异常信息")
    }

    /**
     * 可取消的 OkHttp 同步调用。
     *
     * OkHttp 的 execute() 是阻塞调用，不响应协程取消。这里用
     * suspendCancellableCoroutine 包装，协程取消时主动 call.cancel()，
     * 立即中断阻塞，避免「用户点了停止但请求还在跑」。
     */
    private suspend fun executeCancellable(call: okhttp3.Call): Response =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                try {
                    val resp = call.execute()
                    if (cont.isActive) cont.resume(resp) else resp.close()
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        }

    /**
     * 【P1-3】执行单个工具（含元工具拦截）。
     * 抽出来供串行 / 并行两条路径复用。
     */
    private suspend fun runOneTool(
        name: String,
        argsJson: String,
        settings: DesktopSettings,
        loadedToolNames: MutableSet<String>
    ): ToolResult {
        return when (name) {
            // 元工具拦截：本地处理，不进 DesktopTools
            "tool_search" -> {
                val args = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
                val query = args.optString("query", "").trim()
                val category = args.optString("category", "").trim()
                if (query.isBlank()) {
                    ToolResult.fail("tool_search 需要 query 参数")
                } else {
                    val text = DesktopTools.searchToolsForAgent(query, category)
                    val names = parseToolNamesFromSearchResult(text)
                    names.forEach { loadedToolNames.add(it) }
                    if (names.isNotEmpty()) {
                        DesktopLogger.info(
                            "Agent",
                            "tool_search 命中 ${names.size} 个：$names，已加入本轮 tools"
                        )
                    }
                    ToolResult.ok(text)
                }
            }
            "tool_load" -> {
                val args = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
                val toolName = args.optString("name", "").trim()
                if (toolName.isBlank()) {
                    ToolResult.fail("tool_load 需要 name 参数")
                } else {
                    val text = DesktopTools.loadToolForAgent(toolName)
                    if (text.startsWith("失败")) {
                        ToolResult.fail(text.removePrefix("失败：").trim())
                    } else {
                        loadedToolNames.add(toolName)
                        DesktopLogger.info("Agent", "tool_load 加载 $toolName 成功")
                        ToolResult.ok(text)
                    }
                }
            }
            else -> executeTool(name, argsJson, settings)
        }
    }

    private suspend fun executeTool(
        name: String, argsJson: String, settings: DesktopSettings
    ): ToolResult {
        // 【中断修复】工具执行前再确认一次未取消。
        // 调用方（runOneTool）已经检查过，但元工具分支可能耗时（tool_search 扫目录），
        // 这里再兵一道，避免“用户已点停止但工具仍被执行”。
        currentCoroutineContext().ensureActive()
        if (name in settings.disabledTools) {
            DesktopLogger.warn("Agent", "拒绝调用已禁用工具：$name")
            return ToolResult.fail("工具 $name 已被禁用")
        }
        return try {
            if (name in PhoneTools.TOOLS.map { it.name }) {
                PhoneTools.execute(name, argsJson)
            } else {
                DesktopTools.execute(name, argsJson)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消信号必须原样上抛，不能当成工具失败
            throw e
        } catch (e: Exception) {
            DesktopLogger.error(
                "Agent",
                "工具 $name 执行抛出异常：${e.javaClass.simpleName}: ${e.message}\n" +
                        e.stackTrace.take(5).joinToString("\n") { "  at $it" }
            )
            ToolResult.fail("执行异常：${e.javaClass.simpleName}: ${e.message}")
        } catch (e: LinkageError) {
            // 类加载失败（NoClassDefFoundError 等 Error，catch Exception 接不住）：
            // 典型场景是运行中触发增量构建、build/classes 输出目录被重写的窗口期。
            // 转成工具失败回传 AI 可重试，而不是穿透协程把进程打崩
            DesktopLogger.error(
                "Agent",
                "工具 $name 类加载失败：${e.javaClass.simpleName}: ${e.message}\n" +
                        e.stackTrace.take(5).joinToString("\n") { "  at $it" }
            )
            ToolResult.fail("工具类加载失败：${e.message}（可能正在重新构建，稍后重试即可）")
        }
    }

    private suspend fun buildSystemPrompt(settings: DesktopSettings, sessionPrompt: String = ""): String {
        // 时间戳只到天
        val now = DateFormats.formatDate()
        val os = "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})"

        val base = if (settings.systemPromptMode == "replace" && settings.systemPromptFull.isNotBlank()) {
            settings.systemPromptFull
                .replace("{TIME}", now)
                .replace("{OS}", os)
        } else {
            SYSTEM_PROMPT
                .replace("{TIME}", now)
                .replace("{OS}", os)
        }

        var prompt = base

        // 记忆留在 system（遵循度敏感）；环境信息已挪到首条 user 消息，
        // 见 chat() 里的 envSection 处理，此处不再拼接。
        val mem = MemoryStore.toPromptString()
        if (mem.isNotBlank()) prompt = "$prompt\n\n$mem"

        if (settings.disabledTools.isNotEmpty()) {
            prompt += "\n\n以下工具已被用户禁用，禁止调用：${settings.disabledTools.joinToString(", ")}"
        }

        if (settings.systemPromptMode != "replace" && settings.systemPromptAddon.isNotBlank()) {
            prompt += "\n\n【用户附加指令】\n" + settings.systemPromptAddon
        }

        // 会话专用提示词：只作用于当前会话，追加在全局指令之后
        if (sessionPrompt.isNotBlank()) {
            prompt += "\n\n【本会话专用指令】\n" + sessionPrompt
        }
        return prompt
    }

    private fun friendlyName(toolName: String): String =
        DesktopTools.displayName(toolName).takeIf { it != toolName }
            ?: PhoneTools.TOOLS.firstOrNull { it.name == toolName }?.displayName
            ?: toolName

    /**
     * 【P1-2】按模型能力强制关闭思考。
     * 仅当 profile.supportsDisableThinking 为 true 时才加参数。
     * 具体字段分派逻辑在 shared 的 applyDisableThinking（与手机端共用）。
     * internal：供 SessionSummarizer 复用。
     */
    internal fun applyDisableThinking(
        body: JSONObject,
        profile: net.xinkong.furagent.desktop.data.DesktopModelProfile
    ) {
        if (!profile.supportsDisableThinking) return
        net.xinkong.furagent.shared.util.applyDisableThinking(body, profile.baseUrl)
    }

    /**
     * 【与 applyDisableThinking 对称】按模型能力主动开启思考。
     * supportsThinking=false 的模型（如 DeepSeek-V3 非思考版）不发参数，避免 API 400。
     */
    internal fun applyEnableThinking(
        body: JSONObject,
        profile: net.xinkong.furagent.desktop.data.DesktopModelProfile,
        level: ThinkingLevel
    ) {
        if (!profile.supportsThinking) return
        net.xinkong.furagent.shared.util.applyEnableThinking(body, profile.baseUrl, level)
    }

    private fun prettyJson(raw: String): String = try {
        JSONObject(raw).toString(2)
    } catch (_: Exception) { raw.take(500) }
}