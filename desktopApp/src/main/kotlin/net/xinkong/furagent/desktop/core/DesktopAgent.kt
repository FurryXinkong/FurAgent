package net.xinkong.furagent.desktop.core

import kotlinx.coroutines.*
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
你是 FurAgent，运行在用户的电脑上。当前时间 {TIME}，操作系统 {OS}。

你可以调用本地工具（文件、命令、系统信息、屏幕控制），也可以通过桥接调用手机端工具。
每个工具的具体用法见其 description。

【工具发现（重要）】
你的 tools 数组只包含"核心工具"和两个元工具 tool_search / tool_load。
其他工具（文件操作、媒体处理、系统控制、手机桥接、MCP 扩展等）需要按需加载：
  - 目录已经在 tool_search 的 description 里列出，先扫一眼
  - 需要某个工具时，调用 tool_load(name="工具名") 直接拉取完整定义
  - 不确定工具名时，用 tool_search(query="描述") 做模糊搜索
  - 加载后下一轮就能直接调用，本轮不要重复搜索
不要因为 tools 数组里没看到某个工具就放弃，先看一眼目录或用 tool_load / tool_search。

【基本规则】
1. 需要执行操作时必须调用工具，禁止用文字声称完成。
2. 只依据工具返回判断成败。返回以"失败"开头才算失败，成功不得说失败。
3. 危险操作（写文件、删文件、执行命令）前先向用户说明你要做什么。
4. 复杂任务先用编号列出简短计划，再逐步调用工具。
5. 用户提供 URL 且要求总结或查看内容时，直接抓网页正文，不要只依赖搜索摘要。
6. 需要看本地图片用 view_image，会自动附加到下一轮上下文。
7. 需要当前时间用 time(op=now)。

【批量编辑（省 token 的核心）】

修改代码必须用 code_edit 一次提交所有改动，禁止分多次调用。

为什么：
- 每多一次工具调用轮次，就要重发一遍完整历史（system + 全部对话 + tools）
- 拆成 N 次调用 = 成本乘以 N，用户也要多等 N 倍时间
- code_edit 的 files 数组就是为此设计的：一次调用可以改任意多个文件的任意多个位置
- 所有文件原子提交：任一锚点失败全部回滚，不会出现"改了一半"的脏状态

反模式（禁止）：
- 先改文件 A，看 diff 对不对，再改文件 B
- 先改 import，再改实现，再改测试
- 每改一个文件就调一次 code_edit
- 在同一条消息里并行调用多个 code_edit（会互相覆盖，必须合并成一次调用）

正确做法：
- 一次 code_edit：files 数组里放 A、B、C，每个文件内 edits 数组放改动 1、改动 2
- 拿到 diff 一次性验证
- 如果本次任务确实只改一个文件，那就只调一次；下次别再一个一个来

【多工具并行（当且仅当互不依赖）】

如果需要执行多个互不依赖的操作，请在**同一条 assistant 消息里返回多个 tool_call**。
系统会并行执行，一次性返回所有结果，避免"调一个等一个"的 N 倍历史重发成本。

可以并行：
- 多个独立的 read_file（同时看多个文件）
- 多个独立的 execute_command（比如 git status / git diff / git log 一起发）
- read_file + list_files 这类纯查询操作

不可以并行：
- 多个 code_edit（会互相覆盖，必须合并到一次调用）
- tool_search / tool_load（会改 tools 数组，系统强制串行）
- 有依赖的命令（build 依赖前一个改动完成后才能跑）

【路径与目录】
路径别名：
- ~ 表示用户主目录
- 桌面 → ~/Desktop
- 下载 → ~/Downloads
- 文档 → ~/Documents
- 图片 → ~/Pictures

FurAgent 自己管理的目录（在 ~/.furagent/ 下）：
- 截图 → ~/.furagent/screenshots/
- 接收文件 → ~/.furagent/received/
- 导出 / 备份 → ~/.furagent/exports/
- 临时文件 → ~/.furagent/temp/
- 日志 → ~/.furagent/logs/
- 自定义脚本 → ~/.furagent/scripts/

其它文件操作应保持在用户主目录内，系统目录会被拒绝。

【失败处理】
工具返回失败时：
- 参数错误 → 修正参数重试一次
- 权限问题 → 说明原因，让用户决定
- 同一工具同一参数连续失败 2 次 → 停止重试，向用户说明，换思路
- 禁止盲目重试同一个调用。

【主动记忆】
主动识别值得长期记住的信息，用 memory(op=save) 保存，不要等用户说"记住"。
值得存：姓名/昵称、职业/身份、偏好/习惯/忌讳、常用路径/应用、用户纠正过你的事实。
不值得存：一次性临时信息、寒暄。
每轮最多存一次。需要回忆时用 memory(op=recall)。

【回复规范】
中文，简洁直接，不用 emoji 或颜文字，代码块用 ```语言 包裹。
"""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val VISION_TOOLS = setOf("screenshot", "view_image")

    /** 图片编码前的字节上限。与 DesktopTools.viewImage 的 5MB 对齐。 */
    private const val MAX_IMAGE_BYTES = 5L * 1024 * 1024

    private const val MIN_KEEP_MESSAGES = 40

    /** 【失败计数】同一工具同一参数连续失败达到此值则中断循环。 */
    private const val TOOL_FAILURE_STREAK_LIMIT = 3

    suspend fun runTurn(
        settings: DesktopSettings,
        history: List<DesktopMessage>,
        onUpdate: (List<DesktopMessage>) -> Unit,
        onThinking: (String) -> Unit,
        onReasoning: (String) -> Unit,
        onStream: (String) -> Unit,
        onStreamCommit: () -> Unit
    ): List<DesktopMessage> = withContext(Dispatchers.IO) {
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
            put("content", buildSystemPrompt(settings))
        })

        val truncated = truncateHistory(current, settings.maxTurns)
        buildHistoryMessages(truncated, messages, settings, supportsVision)

        // 【P0】跨轮次累积"已加载工具"
        val loadedToolNames = mutableSetOf<String>()

        // 【失败计数】同一工具同一参数连续失败 3 次则中断（与手机端 Agent.kt 一致）。
        // 签名忽略翻页/数量类参数，避免模型只改 limit/offset 就绕过计数。
        val toolFailureStreak = mutableMapOf<String, Int>()

        val maxLoops = settings.maxToolCallLoops.coerceAtLeast(1)

        repeat(maxLoops) { loopIndex ->
            // 【中断修复】每轮开头检查取消。用户点“停止”后 currentAgentJob.cancel()
            // 会在这里抛出 CancellationException，立即终止循环，不再继续请求/调工具。
            currentCoroutineContext().ensureActive()

            onThinking(if (loopIndex == 0) "思考中…" else "继续思考…")

            // 【P0】每轮重建 tools（loadedToolNames 会变化）
            val tools = buildToolsArray(settings, supportsVision, loadedToolNames)

            DesktopLogger.info(
                "Agent",
                "第 ${loopIndex + 1} 轮（tools=${tools.length()}，已加载额外工具 ${loadedToolNames.size} 个）"
            )

            val body = JSONObject().apply {
                put("model", profile.model)
                put("messages", messages)
                put("tools", tools)
                // 【可配】温度从 profile 读，默认 0.1（Agent 场景要稳定调工具）
                put("temperature", profile.temperature)
                put("stream", true)
            }
            // 【P1-2】按模型能力强制关闭思考（与手机端 Agent.kt 一致）
            if (settings.enableThinking) {
                net.xinkong.furagent.shared.util.applyEnableThinking(body, profile.baseUrl)
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
                val err = DesktopMessage(role = "assistant", content = "网络请求失败：${e.message}")
                current = current + err
                onUpdate(current)
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
                val err = DesktopMessage(role = "assistant", content = "API 错误 ${response.code}：${errStr.take(300)}")
                current = current + err
                onUpdate(current)
                return@withContext current
            }

            val contentBuilder = StringBuilder()
            val reasoningBuilder = StringBuilder()
            var toolCalls: MutableMap<Int, ToolCallAccumulator> = mutableMapOf()

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
                    role = "assistant", content = contentStr, type = "text",
                    reasoning = reasoningBuilder.toString()
                )
                onUpdate(current)
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
            sortedCalls.forEach { (idx, acc) ->
                val callId = callIds[idx] ?: "call_${loopIndex}_$idx"
                current = current + DesktopMessage(
                    role = "tool", content = "${friendlyName(acc.name)}\n${prettyJson(acc.arguments.toString())}",
                    type = "tool_call", toolCallId = callId
                )
            }
            onUpdate(current)

            // 执行（并行或串行），结果按 idx 收集
            val results = LinkedHashMap<Int, Pair<ToolResult, Long>>()
            if (hasMetaTool || sortedCalls.size <= 1) {
                sortedCalls.forEach { (idx, acc) ->
                    // 【中断修复】每个工具执行前检查取消
                    currentCoroutineContext().ensureActive()
                    val argsJson = acc.arguments.toString()
                    onThinking("正在执行工具：${friendlyName(acc.name)}")
                    DesktopLogger.info("Agent", "调用工具（串行）：${acc.name}")
                    val startMs = System.currentTimeMillis()
                    val r = runOneTool(acc.name, argsJson, settings, loadedToolNames)
                    results[idx] = r to (System.currentTimeMillis() - startMs)
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
                val (result, durationMs) = results[idx] ?: (ToolResult.fail("工具未执行") to 0L)

                current = current + DesktopMessage(
                    role = "tool", content = result.output,
                    type = "tool_result", toolCallId = callId,
                    durationMs = durationMs
                )
                onUpdate(current)

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

    // ============================================================
    // 【P0】构建分层 tools 数组
    // ============================================================

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
            arr.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", t.name)
                    put("description", t.description)
                    put("parameters", JSONObject(t.paramsSchema))
                })
            })
        }

        // PhoneTools：只在已加载时出现
        PhoneTools.TOOLS.forEach { t ->
            if (t.name in disabled) return@forEach
            if (t.name !in loadedNames) return@forEach
            arr.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", t.name)
                    put("description", t.description)
                    put("parameters", JSONObject(t.paramsSchema))
                })
            })
        }

        return arr
    }

    // ============================================================
    // 历史截断 / 重建
    // ============================================================

    private fun truncateHistory(history: List<DesktopMessage>, maxTurns: Int): List<DesktopMessage> {
        if (history.isEmpty()) return history
        if (maxTurns <= 0) return history

        val minKeep = minOf(MIN_KEEP_MESSAGES, maxTurns * 2)
        val startIdx = computeTruncateStart(
            history = history,
            maxTurns = maxTurns,
            minKeep = minKeep,
            roleOf = { it.role },
            typeOf = { it.type }
        )
        if (startIdx <= 0) return history
        return history.subList(startIdx, history.size)
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

                    val hasResult = i + 1 < history.size && history[i + 1].type == "tool_result"
                    if (hasResult) {
                        // 【去掉 degradeToolResult】原样发送工具返回。
                        // 大结果已经在 CommonTools.maybeSpill 里落盘，
                        // 这里再砍一遍中间段会让模型"失忆"，得不偿失。
                        val resultMsg = history[i + 1]
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
                    if ((msg.content.isNotBlank() || msg.imagePaths.isNotEmpty()) &&
                        (msg.role == "user" || msg.role == "assistant")
                    ) messages.put(buildMessageObject(msg, settings, supportsVision))
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

    private fun readAsDataUrl(path: String, settings: DesktopSettings): String? {
        return try {
            val f = File(path)
            if (!f.exists() || !f.isFile) return null
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
            "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
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
     * 【P0】displayName → name 反查。
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
        val maxRetries = 3
        var lastError: Exception? = null
        for (attempt in 0..maxRetries) {
            try {
                val resp = httpClient.newCall(request).execute()
                val code = resp.code
                val retryable = code == 429 || code == 500 || code == 502 || code == 503 || code == 504
                if (!retryable || attempt == maxRetries) return resp
                // 可重试：关掉响应体，退避后重试
                resp.close()
                val baseMs = 1000L shl attempt // 1s, 2s, 4s
                val jitter = (baseMs * 0.3 * (Math.random() * 2 - 1)).toLong()
                val waitMs = (baseMs + jitter).coerceAtLeast(200L)
                DesktopLogger.warn("Agent", "API $code，第 ${attempt + 1} 次重试，等待 ${waitMs}ms")
                onThinking("服务端繁忙（$code），${waitMs / 1000}s 后重试…")
                delay(waitMs.milliseconds)
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
            // 【P0】元工具拦截：本地处理，不进 DesktopTools
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
        } catch (e: Exception) {
            DesktopLogger.error(
                "Agent",
                "工具 $name 执行抛出异常：${e.javaClass.simpleName}: ${e.message}\n" +
                        e.stackTrace.take(5).joinToString("\n") { "  at $it" }
            )
            ToolResult.fail("执行异常：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private suspend fun buildSystemPrompt(settings: DesktopSettings): String {
        // 【缓存关键】时间戳只到天
        val now = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
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

        val mem = MemoryStore.toPromptString()
        if (mem.isNotBlank()) prompt = "$prompt\n\n$mem"

        try {
            val env = EnvDetector.buildPromptSection()
            if (env.isNotBlank()) prompt = "$prompt\n\n$env"
        } catch (e: Exception) {
            DesktopLogger.warn("Agent", "环境检测注入失败：${e.message}")
        }

        if (settings.disabledTools.isNotEmpty()) {
            prompt += "\n\n以下工具已被用户禁用，禁止调用：${settings.disabledTools.joinToString(", ")}"
        }

        if (settings.systemPromptMode != "replace" && settings.systemPromptAddon.isNotBlank()) {
            prompt += "\n\n【用户附加指令】\n" + settings.systemPromptAddon
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

    private fun prettyJson(raw: String): String = try {
        JSONObject(raw).toString(2)
    } catch (_: Exception) { raw.take(500) }
}