package net.xinkong.furagent.desktop.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.xinkong.furagent.desktop.core.DesktopAgent
import net.xinkong.furagent.desktop.core.DesktopConfirmation
import net.xinkong.furagent.desktop.core.TaskRuntime
import net.xinkong.furagent.desktop.core.currentSessionId
import net.xinkong.furagent.desktop.data.*
import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.desktop.system.OutputCompressor
import net.xinkong.furagent.shared.tools.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

object DesktopTools {

    data class DesktopTool(
        val name: String,
        val displayName: String,
        val description: String,
        val paramsSchema: String
    )

    private val VISION_TOOLS = setOf("screenshot", "view_image")

    /**
     * 图片编码前的字节上限。
     * 必须与 DesktopAgent.readAsDataUrl 的 MAX_IMAGE_BYTES 保持一致，
     * 否则会出现「view_image 说加载成功，但下一轮 AI 看不到图」的静默失败。
     */
    private const val MAX_IMAGE_BYTES = 5L * 1024 * 1024

    private val baseTools: List<DesktopTool> by lazy {
        val common = CommonToolSchemas.SPECS.map {
            DesktopTool(it.name, it.displayName, it.description, it.paramsSchema)
        }
        val private = listOf(
            DesktopTool("delete_file", "删除文件",
                "删除电脑上的文件。文件不会真正消失，而是移入回收站（~/.furagent/temp/trash），由用户决定何时清空。",
                """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""),
            DesktopTool("execute_command", "执行命令",
                "在电脑上执行系统命令。危险操作，默认 30 秒超时。执行过程中的实时输出可在日志页和右上角浮层查看。",
                """{"type":"object","properties":{"command":{"type":"string"},"timeout":{"type":"integer"},"cwd":{"type":"string"}},"required":["command"]}"""),
            DesktopTool("open_url", "打开网址",
                "用电脑上的默认浏览器打开网址。",
                """{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}"""),
            DesktopTool("view_image", "查看图片",
                "加载本地图片并附加到下一轮上下文，让 AI 直接看到图片内容。需要当前模型支持图片输入。" +
                        "原图超过 5MB 时会被拒绝（可先压缩或裁剪）。",
                """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""")
        )
        val workflow = WorkflowTools.TOOLS.map {
            DesktopTool(it.name, it.displayName, it.description, it.paramsSchema)
        }
        val schedule = ScheduleTools.TOOLS.map {
            DesktopTool(it.name, it.displayName, it.description, it.paramsSchema)
        }
        val system = SystemIntegrationTools.TOOLS.map {
            DesktopTool(it.name, it.displayName, it.description, it.paramsSchema)
        }
        val apps = AppTools.TOOLS.map {
            DesktopTool(it.name, it.displayName, it.description, it.paramsSchema)
        }
        val screenshot = ScreenshotTools.TOOLS.map {
            DesktopTool(it.name, it.displayName, it.description, it.paramsSchema)
        }
        val memory = MemoryTools.TOOLS.map {
            DesktopTool(it.name, it.displayName, it.description, it.paramsSchema)
        }
        val ffmpeg = FfmpegTools.TOOLS.map {
            DesktopTool(it.name, it.displayName, it.description, it.paramsSchema)
        }
        val python = PythonTools.TOOLS.map {
            DesktopTool(it.name, it.displayName, it.description, it.paramsSchema)
        }
        val web = WebTools.TOOLS.map {
            DesktopTool(it.name, it.displayName, it.description, it.paramsSchema)
        }
        val screen = ScreenTools.TOOLS.map {
            DesktopTool(it.name, it.displayName, it.description, it.paramsSchema)
        }
        val codeEdit = CodeEditTools.TOOLS.map {
            DesktopTool(it.name, it.displayName, it.description, it.paramsSchema)
        }
        val skill = SkillTools.TOOLS.map {
            DesktopTool(it.name, it.displayName, it.description, it.paramsSchema)
        }
        // 动态脚本工具 每个自定义脚本注册成一个独立工具 pytool_<name>。
        // 脚本增删后需刷新缓存（见 invalidateCache）。
        val dynamicSkills = SkillTools.dynamicTools().map {
            DesktopTool(it.name, it.displayName, it.description, it.paramsSchema)
        }
        val document = DocumentTools.TOOLS.map {
            DesktopTool(it.name, it.displayName, it.description, it.paramsSchema)
        }
        val imageGen = ImageGenTools.TOOLS.map {
            DesktopTool(it.name, it.displayName, it.description, it.paramsSchema)
        }
        val download = DownloadTools.TOOLS.map {
            DesktopTool(it.name, it.displayName, it.description, it.paramsSchema)
        }
        val history = HistoryTools.TOOLS.map {
            DesktopTool(it.name, it.displayName, it.description, it.paramsSchema)
        }

        common + private + workflow + schedule + system + apps + screenshot +
                memory + ffmpeg + python + web + screen + codeEdit + skill + dynamicSkills + document + imageGen + download + history
    }

    @Volatile private var cachedToolList: List<DesktopTool>? = null
    @Volatile private var cacheExpireAt: Long = 0L

    /**
     * 全量工具列表（含 MCP）。1 秒缓存，避免频繁读 McpManager。
     *
     * P0 改造 Agent 循环已不再直接调用此方法构建 tools 数组，
     * 改用 getToolListWithLoaded。但本方法仍被 UI / 桥接 / allToolNames 使用。
     */
    fun getToolList(): List<DesktopTool> {
        val now = System.currentTimeMillis()
        val cached = cachedToolList
        if (cached != null && now < cacheExpireAt) return cached

        val mcp = McpTools.getTools().map {
            DesktopTool(it.name, it.displayName, it.description, it.paramsSchema)
        }
        val result = if (mcp.isEmpty()) baseTools else baseTools + mcp
        cachedToolList = result
        cacheExpireAt = now + 1000
        return result
    }

    val TOOLS: List<DesktopTool> get() = getToolList()

    // 分层工具系统

    /**
     * 核心工具 + 已加载工具 + 元工具。
     *
     * Agent 循环里每轮调用，loadedNames 是跨轮次累积的"已加载工具名集合"。
     */
    fun getToolListWithLoaded(loadedNames: Set<String>): List<DesktopTool> {
        val result = mutableListOf<DesktopTool>()
        // 脚本配置 2026-10-18 脚本开关已从 disabledTools 迁到各自的 config.json，
        // dynamicTools() 已按配置过滤，这里不再重复判断。
        getToolList().forEach { t ->
            val isCore = t.name in ToolCategory.CORE_TOOL_NAMES
            val isLoaded = t.name in loadedNames
            // 动态脚本工具 pytool_<name> 始终加入，让 AI 一眼看到有哪些脚本可用。
            val isDynamicSkill = SkillTools.isDynamicTool(t.name)
            if (isCore || isLoaded || isDynamicSkill) result.add(t)
        }
        result.add(buildToolSearchSpec())
        result.add(buildToolLoadSpec())
        return result
    }

    /**
     * 把当前所有可用工具（含 PhoneTools 和 MCP）转成 CatalogTool。
     *
     * 用于构建目录文本和搜索。不做 disabled / vision 过滤
     * （过滤条件在 ToolCatalog 的调用点传）。
     */
    private fun getCatalogTools(): List<CatalogTool> {
        val out = mutableListOf<CatalogTool>()

        // 桌面工具（含 MCP）
        getToolList().forEach { t ->
            out.add(
                CatalogTool(
                    name = t.name,
                    displayName = t.displayName,
                    description = t.description,
                    paramsSchema = t.paramsSchema
                )
            )
        }

        // 手机桥接工具
        PhoneTools.TOOLS.forEach { pt ->
            out.add(
                CatalogTool(
                    name = pt.name,
                    displayName = pt.displayName,
                    description = pt.description,
                    paramsSchema = pt.paramsSchema
                )
            )
        }

        return out
    }

    /**
     * 构建 tool_search 的 spec（description 内嵌目录）。
     */
    // buildToolSearchSpec 缓存。
    // getToolListWithLoaded 每轮 Agent 循环都调，每次都走 getCatalogTools + 拼字符串。
    // 一轮用 5 次工具 = 重建 5 次。缓存后 5 秒内只重建 1 次。
    @Volatile private var cachedSearchSpec: DesktopTool? = null
    @Volatile private var cachedSearchSpecKey: String = ""
    @Volatile private var cachedSearchSpecAt: Long = 0L
    private const val SEARCH_SPEC_CACHE_MS = 5000L

    private fun buildToolSearchSpec(): DesktopTool {
        val settings = DesktopSettingsHolder.current
        val profile = settings.currentProfile()
        val visionEnabled = profile?.supportsVision ?: true
        val disabled = settings.disabledTools

        // 缓存键 = disabled hash + visionEnabled；MCP/skill 变化靠 5 秒窗口兜底
        val cacheKey = "${disabled.hashCode()}_$visionEnabled"
        val now = System.currentTimeMillis()
        val cached = cachedSearchSpec
        if (cached != null && cachedSearchSpecKey == cacheKey &&
            now - cachedSearchSpecAt < SEARCH_SPEC_CACHE_MS
        ) {
            return cached
        }

        val catalog = getCatalogTools()

        val catalogText = ToolCatalog.buildCatalogText(
            tools = catalog,
            disabled = disabled,
            visionEnabled = visionEnabled,
            visionTools = VISION_TOOLS
        )

        val desc = buildString {
            // token 优化 精简说明：与系统提示词重复的部分不再赘述，
            // 只保留“怎么用”和目录本身。
            append("搜索非核心工具的完整定义。找到后下一轮可直接调用。\n")
            append("category 可选：file/system/media/network/screen/remote/mcp/skill/other\n")
            append("可用工具目录：\n")
            append(catalogText)
        }

        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("query", JSONObject().apply {
                    put("type", "string")
                    put("description", "自然语言描述你需要做什么")
                })
                put("category", JSONObject().apply {
                    put("type", "string")
                    put("description", "可选，按分类过滤")
                })
            })
            put("required", JSONArray().apply { put("query") })
        }.toString()

        val spec = DesktopTool("tool_search", "工具搜索", desc, schema)
        // 写入缓存
        cachedSearchSpec = spec
        cachedSearchSpecKey = cacheKey
        cachedSearchSpecAt = now
        return spec
    }

    private fun buildToolLoadSpec(): DesktopTool {
        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("name", JSONObject().apply {
                    put("type", "string")
                    put("description", "工具名")
                })
            })
            put("required", JSONArray().apply { put("name") })
        }.toString()

        return DesktopTool(
            "tool_load", "加载工具",
            "直接加载指定工具的完整定义。如果你已经从 tool_search 的目录里看到工具名，用这个。",
            schema
        )
    }

    /**
     * 供 Agent 循环调用：搜索工具，返回 AI 易读的文本。
     */
    fun searchToolsForAgent(query: String, category: String): String {
        val catalog = getCatalogTools()
        val settings = DesktopSettingsHolder.current
        val profile = settings.currentProfile()
        val visionEnabled = profile?.supportsVision ?: true

        val results = ToolCatalog.search(
            query = query,
            category = category,
            tools = catalog,
            disabled = settings.disabledTools,
            visionEnabled = visionEnabled,
            visionTools = VISION_TOOLS,
            limit = 5
        )
        return ToolCatalog.renderSearchResult(results)
    }

    /**
     * 供 Agent 循环调用：加载单个工具详情。
     */
    fun loadToolForAgent(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return "失败：未提供工具名"

        val settings = DesktopSettingsHolder.current
        val profile = settings.currentProfile()
        val visionEnabled = profile?.supportsVision ?: true
        if (!visionEnabled && trimmed in VISION_TOOLS) {
            return "失败：当前模型不支持图片输入，「$trimmed」已禁用。"
        }
        if (trimmed in settings.disabledTools) {
            return "失败：工具 $trimmed 已被用户在设置中关闭。"
        }

        val tool = getCatalogTools().firstOrNull { it.name == trimmed }
            ?: return "失败：未找到工具 $trimmed。可以先调用 tool_search 查看可用工具。"

        return ToolCatalog.renderToolDetail(tool)
    }

    // displayName / findToolNameByDisplay

    fun displayName(name: String): String {
        // 元工具显式映射（不在 baseTools 里）
        when (name) {
            "tool_search" -> return "工具搜索"
            "tool_load" -> return "加载工具"
        }
        baseTools.firstOrNull { it.name == name }?.let { return it.displayName }
        if (name.startsWith("mcp__")) {
            McpTools.getTools().firstOrNull { it.name == name }?.let { return it.displayName }
        }
        if (name.startsWith("phone_")) {
            PhoneTools.TOOLS.firstOrNull { it.name == name }?.let { return it.displayName }
        }
        return name
    }

    fun allToolNames(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        getToolList().forEach { out.add(it.name to it.displayName) }
        PhoneTools.TOOLS.forEach { out.add(it.name to it.displayName) }
        return out
    }

    // 执行分发

    private val host: ToolHost = DesktopToolHost()

    /**
     * 执行工具。
     *
     * sessionId 走 nullable 参数（默认 null）：
     *   - null = 读当前协程上下文里的会话 id（SessionIdElement），主对话默认行为
     *   - "" = 明确不归属任何会话（定时任务、系统级后台执行）
     *   - 非空 = 显式指定
     *
     * 为什么不是 String = currentSessionId() Kotlin 不允许在默认参数值里
     * 调 suspend 函数（编译报 "Suspend function call in default parameter value is unsupported"）。
     */
    suspend fun execute(
        name: String,
        argsJson: String,
        sessionId: String? = null
    ): ToolResult {
        val sid = sessionId ?: currentSessionId()
        val subtitle: String = when (name) {
            "execute_command" -> try {
                if (argsJson.isBlank()) "" else JSONObject(argsJson).optString("command", "")
            } catch (_: Exception) { "" }
            else -> ""
        }
        return TaskRuntime.withTask(name, displayName(name), subtitle, sid) {
            doExecute(name, argsJson)
        }
    }

    // 子会话执行进度（聊天页顶部功能区实时展示；按 subId 区分并行子会话，避免互踩）
    object SubSessionProgress {
        data class Entry(
            val title: String = "",
            val steps: Int = 0,
            val lastTool: String = "",
            /** 当前阶段：思考中 / 生成中 / 执行工具 */
            val phase: String = "",
            /** 最新一行内容（思考 / 输出 / 工具结果），用户能看到它在干什么 */
            val snippet: String = "",
            val startedAt: Long = 0L
        ) {
            val elapsedMs: Long get() = if (startedAt > 0) System.currentTimeMillis() - startedAt else 0L
        }

        data class State(
            val running: Boolean = false,
            val activeCount: Int = 0,
            val title: String = "",
            val steps: Int = 0,
            val lastTool: String = "",
            val phase: String = "",
            val snippet: String = ""
        )

        /** 子会话完成事件：回流主对话注入「子代理完成」通知 */
        data class Completed(
            val originId: String,
            val subId: String,
            val title: String,
            val msgCount: Int,
            val summary: String
        )

        private val entries = LinkedHashMap<String, Entry>()
        private val _state = kotlinx.coroutines.flow.MutableStateFlow(State())
        val state: kotlinx.coroutines.flow.StateFlow<State> = _state
        private val _events =
            kotlinx.coroutines.flow.MutableSharedFlow<Completed>(extraBufferCapacity = 32)
        val events: kotlinx.coroutines.flow.SharedFlow<Completed> = _events

        private fun refresh() {
            val newest = entries.values.lastOrNull()
            _state.value = State(
                running = entries.isNotEmpty(),
                activeCount = entries.size,
                title = newest?.title ?: "",
                steps = newest?.steps ?: 0,
                lastTool = newest?.lastTool ?: "",
                phase = newest?.phase ?: "",
                snippet = newest?.snippet ?: ""
            )
        }

        @Synchronized fun start(id: String, t: String) {
            entries[id] = Entry(title = t, startedAt = System.currentTimeMillis())
            refresh()
        }

        @Synchronized fun update(id: String, steps: Int, lastTool: String) {
            val e = entries[id] ?: return
            entries[id] = e.copy(steps = steps, lastTool = lastTool)
            refresh()
        }

        /* 可见性 子代理正在干什么 —— 阶段 + 最新一行内容 */
        @Synchronized fun updatePhase(id: String, phase: String, snippet: String) {
            val e = entries[id] ?: return
            if (e.phase == phase && e.snippet == snippet) return
            entries[id] = e.copy(phase = phase, snippet = snippet)
            refresh()
        }

        @Synchronized fun lastTool(id: String): String = entries[id]?.lastTool ?: ""

        @Synchronized fun finish(id: String) {
            entries.remove(id)
            refresh()
        }

        @Synchronized fun snapshot(): Map<String, Entry> = LinkedHashMap(entries)

        @Synchronized fun emitCompleted(c: Completed) {
            _events.tryEmit(c)
        }
    }

    // 原 object CurrentTaskSession 的语义已拆成两部分：
    //   - 工具归属 → 协程级 SessionIdElement（见 SessionContext.kt），随协程结束自动清空
    //   - UI 显示 → 下面的过渡 shim（Main.kt 的 UI 层单向写入，Agent 主逻辑不再读写）
    /**
     * 2 过渡 shim UI 层「当前显示中的会话 id」。
     *
     * 只代表"用户此刻在看哪个会话"，由 Main.kt 的 UI 层在 currentSessionId 变化时写入。
     * Agent 主逻辑已改用协程上下文（SessionIdElement），不再读写此字段。
     * 目前供 HistoryPage 等 UI 页面按会话过滤用。
     * 下批会把 currentSessionId 作为参数传到各 UI 页面，届时可删除本 object。
     */
    object CurrentTaskSession {
        @Volatile var id: String = ""
    }

    // 并行子会话计数（原子操作，杜绝同毫秒并发绕过检查）
    private val subSessionActive = java.util.concurrent.atomic.AtomicInteger(0)
    // 从 shared 统一常量，双端一致
    private const val MAX_PARALLEL_SUB = net.xinkong.furagent.shared.data.SubSessionLimits.MAX_PARALLEL_SUB
    /**
     * 子会话硬上限 子代理最多跑多少轮。
     *
     * 为什么不能直接继承 settings.maxToolCallLoops：
     * 用户把主会话调到 100 轮是为了自己长任务，但主代理可能一口气
     * 派出好几个子代理，每个都跑 100 轮 → 一次就烧掉整个月的额度。
     * 从 shared 统一常量，双端一致。
     */
    private const val SUB_MAX_LOOPS = net.xinkong.furagent.shared.data.SubSessionLimits.SUB_MAX_LOOPS

    /**
     * 截断补总结 子会话被强制截断后，让它自己回头总结一次。
     *
     * 为什么只跑 1 轮：再多就是又一次全量上下文请求，
     * 子代理这时候还在回忆上一轮的工具输出，烧钱而不产出。
     * 1 轮足够它把「做了什么 / 卡在哪」说清楚。
     *
     * 什么时候不补：已经有一段正常的 assistant 文字。
     * 那说明它自己已经总结过了，再问一次是浪费。
     */
    private suspend fun summarizeIfTruncated(
        history: List<DesktopMessage>,
        settings: DesktopSettings,
    ): List<DesktopMessage> {
        val lastText = history.lastOrNull { it.type == "text" && it.role == "assistant" }
            ?.content?.trim().orEmpty()
        val truncated = lastText.isBlank() ||
                lastText.contains("已达到最大工具调用轮数")
        if (!truncated) return history

        val nudge = DesktopMessage(
            role = "user",
            content = "【系统指令】本次任务已被强制中断（轮数上限 / 任务被停止）。\n" +
                    "请立即用简洁的文字回答整理，不要再调用任何工具：\n" +
                    "1) 你已经完成了什么（具体改了哪些文件 / 得到了什么结论）\n" +
                    "2) 当前状态与卡点\n" +
                    "3) 下一步建议。\n" +
                    "回答直接给结论，不要客套。",
            timestamp = System.currentTimeMillis()
        )
        return try {
            DesktopAgent.runTurn(
                settings = settings,
                history = history + nudge,
                maxLoopsOverride = 1,
                onUpdate = {},
                onThinking = {},
                onReasoning = {},
                onStream = {},
                onStreamCommit = {}
            )
        } catch (_: Throwable) {
            history
        }
    }

    /** 子会话收尾摘要：必须能看出它干了什么、停在哪。 */
    private fun buildSubSummary(h: List<DesktopMessage>, hitLimit: Boolean): String {
        val toolCalls = h.filter { it.type == "tool_call" }
        val lastText = h.lastOrNull { it.type == "text" && it.role == "assistant" }
            ?.content?.trim().orEmpty()
        val errs = h.count { it.type == "tool_result" && it.content.startsWith("失败") }
        val sb = StringBuilder()
        if (hitLimit) {
            sb.append("【未完成】子会话达到轮数上限（$SUB_MAX_LOOPS）被截断，下面是它已经做到的部分。\n")
        }
        sb.append("工具调用 ${toolCalls.size} 次")
        if (errs > 0) sb.append("，失败 $errs 次")
        val tools = toolCalls.mapNotNull {
            it.content.lineSequence().firstOrNull()?.trim()?.takeIf { n -> n.isNotBlank() }
        }.distinct().take(8)
        if (tools.isNotEmpty()) sb.append("：").append(tools.joinToString("、"))
        sb.append("\n")
        if (lastText.isNotBlank()) {
            sb.append(lastText.take(600))
        } else {
            sb.append("（子会话没有文本回答——只调了工具，点入子会话看完整过程）")
        }
        return sb.toString()
    }


    /**
     * AI 主动终止 正在执行的子会话 Job 表（subId → Job）。
     * sub_session(op=stop) 据此 cancel 对应协程。
     */
    private val subSessionJobs =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    // 子会话后台执行作用域（独立于主任务，互不阻塞、互不误杀）
    private val subSessionScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO +
                // 打上子会话标记：确认弹窗在该协程里会被自动拒绝，避免卡 300 秒
                kotlinx.coroutines.CoroutineName(
                    DesktopConfirmation.SUBSESSION_COROUTINE_NAME
                )
    )

    /**
     * 子代理：派生一个不携带历史的独立子会话执行 task，后台并行跑完存盘。
     * tool_result 首行约定 "SUB_SESSION:{subId}"，聊天页据此渲染子会话卡片。
     *
     * 可复用 传 session_id 时复用已有子会话（带上它的历史继续跑），
     * 不传则新建。这样主代理可以追问同一个子代理，而不是每次都开新的。
     */
    private suspend fun runSubSession(args: JSONObject): ToolResult {
        val task = args.optString("task", "").trim()
        if (task.isBlank()) return ToolResult.fail("run_sub_session 需要 task 参数")
        val reuseId = args.optString("session_id", "").trim()

        // ============ 复用已有子会话 ============
        if (reuseId.isNotEmpty()) {
            val existing = DesktopSessionStore.load(reuseId)
                ?: return ToolResult.fail("找不到子会话 $reuseId（可用 sub_session op=list 查看）")
            if (existing.parentId == null) {
                return ToolResult.fail("$reuseId 不是子会话，不能复用")
            }
            // 正在跑的子会话不能重复派任务
            if (SubSessionProgress.snapshot().containsKey(reuseId)) {
                return ToolResult.fail("子会话 $reuseId 正在执行中，请等它完成后再追问")
            }
            if (subSessionActive.incrementAndGet() > MAX_PARALLEL_SUB) {
                subSessionActive.decrementAndGet()
                return ToolResult.fail("并行子会话已达上限（$MAX_PARALLEL_SUB 个），请等待完成后再派发")
            }

            val now = System.currentTimeMillis()
            val parentSessionId = existing.parentId
            val title = existing.title.removePrefix("↳ ").ifBlank { task.take(30) }
            // 带上已有历史 + 新任务
            val initial = existing.messages + DesktopMessage(role = "user", content = task, timestamp = now)

            SubSessionProgress.start(reuseId, title)
            subSessionScope.launch {
                // sessionId 通过协程上下文传递（见 SessionContext.kt），
                // 已删除全局 CurrentTaskSession 变量。runTurn 内部
                // withContext(SessionIdElement(reuseId))，所有工具执行、
                // 历史归属都归属到 reuseId，不再与并行子会话/主会话互相覆盖。
                var subHistory: List<DesktopMessage> = initial
                try {
                    val finalHistory = DesktopAgent.runTurn(
                        settings = DesktopSettingsHolder.current,
                        history = initial,
                        sessionId = reuseId,
                        // 子代理模型：设置里单独指定过就用它，否则回退主对话模型
                        profileOverride = DesktopSettingsHolder.current.currentSubAgentProfile(),
                        onUpdate = { h ->
                            subHistory = h
                            SubSessionProgress.update(
                                reuseId,
                                h.count { m -> m.type == "tool_call" },
                                h.lastOrNull { m -> m.type == "tool_call" }
                                    ?.content?.lineSequence()?.firstOrNull() ?: ""
                            )
                        },
                        onThinking = {},
                        onReasoning = {},
                        onStream = {},
                        onStreamCommit = {}
                    )
                    subHistory = finalHistory
                } catch (_: Exception) {
                } finally {
                    try {
                        DesktopSessionStore.save(
                            DesktopSession(
                                id = reuseId,
                                title = existing.title,
                                createdAt = existing.createdAt,
                                updatedAt = System.currentTimeMillis(),
                                parentId = parentSessionId,
                                messages = subHistory
                            )
                        )
                    } catch (_: Exception) { }
                    SubSessionProgress.finish(reuseId)
                    subSessionActive.decrementAndGet()
                    SubSessionProgress.emitCompleted(
                        SubSessionProgress.Completed(
                            originId = parentSessionId ?: "",
                            subId = reuseId,
                            title = title,
                            msgCount = subHistory.size,
                            summary = subHistory.lastOrNull {
                                it.type == "text" && it.role == "assistant"
                            }?.content?.take(800) ?: "（子会话未产生文本输出）"
                        )
                    )
                }
            }

            return ToolResult.ok(
                "SUB_SESSION:$reuseId\n标题：↳ $title\n" +
                        "状态：已复用该子会话并在后台继续执行（携带原有 ${existing.messages.size} 条历史）。\n" +
                        "完成后会自动在主对话注入「子代理完成」通知。\n\n" +
                        "（后台执行中——完成后完整过程写入该子会话。）"
            )
        }

        // ============ 新建子会话 ============
        val title = args.optString("title", "").trim().ifBlank { task.take(30) }

        // 并行槽位：原子检查+占位，杜绝同毫秒并发绕过检查
        if (subSessionActive.incrementAndGet() > MAX_PARALLEL_SUB) {
            subSessionActive.decrementAndGet()
            return ToolResult.fail("并行子会话已达上限（$MAX_PARALLEL_SUB 个），请等待完成后再派发")
        }

        // ID：毫秒时间戳 + 4 位随机 hex，并发派发不撞
        val rndHex = java.lang.String.format(
            "%04x",
            java.util.concurrent.ThreadLocalRandom.current().nextInt(65536)
        )
        val subId = "sub_${System.currentTimeMillis()}_$rndHex"
        val now = System.currentTimeMillis()
        // 母会话快照：从协程上下文读，不再读全局 var
        // （runSubSession 是被主 Agent 调用的，主协程上下文里 SessionIdElement 就是母会话 id）
        val parentSessionId = currentSessionId().ifBlank { null }
        // 全新上下文：只有这条 task，不带父会话历史
        val initial = listOf(
            DesktopMessage(role = "user", content = task, timestamp = now)
        )

        // 启动即存盘：子会话立刻出现在「子代理」列表，可随时点入
        try {
            DesktopSessionStore.save(
                DesktopSession(
                    id = subId,
                    title = "↳ $title",
                    createdAt = now,
                    updatedAt = now,
                    parentId = parentSessionId,
                    messages = initial
                )
            )
        } catch (e: Exception) {
            subSessionActive.decrementAndGet()
            return ToolResult.fail("子会话初始存盘失败：${e.message}")
        }

        // 后台异步执行：主对话立即返回、互不阻塞；完成后覆盖保存全量过程
        SubSessionProgress.start(subId, title)
        val job = subSessionScope.launch {
            // sessionId 通过协程上下文传递：
            // runTurn 内部 withContext(SessionIdElement(subId))，
            // 子会话的每个工具、每次历史写入都归属 subId，
            // 不再需要临时改写全局，也不会与并行子会话/主会话互相覆盖。
            var subHistory: List<DesktopMessage> = initial
            // 可见性 持续累积流式内容，让用户能看到它在写什么
            val streamBuf = StringBuilder()
            // 硬上限 子会话不继承用户的高轮数设置
            val subLoops = minOf(DesktopSettingsHolder.current.maxToolCallLoops, SUB_MAX_LOOPS)
            try {
                val finalHistory = DesktopAgent.runTurn(
                    settings = DesktopSettingsHolder.current,
                    history = initial,
                    maxLoopsOverride = subLoops,
                    sessionId = subId,
                    // 子代理模型：设置里单独指定过就用它，否则回退主对话模型
                    profileOverride = DesktopSettingsHolder.current.currentSubAgentProfile(),
                    onUpdate = { h ->
                        subHistory = h
                        SubSessionProgress.update(
                            subId,
                            h.count { m -> m.type == "tool_call" },
                            h.lastOrNull { m -> m.type == "tool_call" }
                                ?.content?.lineSequence()?.firstOrNull() ?: ""
                        )
                    },
                    onThinking = { t ->
                        SubSessionProgress.updatePhase(subId, "思考中", t.take(120))
                    },
                    onReasoning = { r ->
                        val line = r.takeLast(400).replace("\n", " ").trim()
                        if (line.isNotBlank()) {
                            SubSessionProgress.updatePhase(subId, "推理中", line.takeLast(120))
                        }
                    },
                    onStream = { c ->
                        streamBuf.append(c)
                        SubSessionProgress.updatePhase(
                            subId, "生成中",
                            streamBuf.toString().takeLast(400).replace("\n", " ").trim().takeLast(120)
                        )
                    },
                    onStreamCommit = { streamBuf.setLength(0) }
                )
                subHistory = finalHistory
                // 截断补总结 跑到轮数上限也得给个交代，不能只剩一句
                // 「已达到最大工具调用轮数」就完事
                subHistory = summarizeIfTruncated(subHistory, DesktopSettingsHolder.current)
            } catch (e: Exception) {
                // 异常 / 被主 AI 主动中断（CancellationException）：
                // 同样要求它把现有结果总结完再存盘。
                // NonCancellable：父任务已经被 cancel，不加这个请求会立刻被打断。
                try {
                    subHistory = withContext(kotlinx.coroutines.NonCancellable) {
                        summarizeIfTruncated(subHistory, DesktopSettingsHolder.current)
                    }
                } catch (_: Throwable) { }
            } finally {
                try {
                    DesktopSessionStore.save(
                        DesktopSession(
                            id = subId,
                            title = "↳ $title",
                            createdAt = now,
                            updatedAt = System.currentTimeMillis(),
                            parentId = parentSessionId,
                            messages = subHistory
                        )
                    )
                } catch (_: Exception) { }
                // 不再恢复全局：协程上下文自动随协程结束
                SubSessionProgress.finish(subId)
                subSessionActive.decrementAndGet()
                subSessionJobs.remove(subId)
                // 完成事件回流：主对话收到「子代理完成」通知
                SubSessionProgress.emitCompleted(
                    SubSessionProgress.Completed(
                        originId = parentSessionId ?: "",
                        subId = subId,
                        title = title,
                        msgCount = subHistory.size,
                        summary = buildSubSummary(
                            subHistory,
                            hitLimit = subHistory.isNotEmpty() &&
                                    subHistory.lastOrNull { it.type == "text" }?.content
                                        ?.contains("已达到最大工具调用轮数") == true
                        )
                    )
                )
            }
        }
        subSessionJobs[subId] = job

        return ToolResult.ok(
            "SUB_SESSION:$subId\n标题：↳ $title\n" +
                    "状态：已在后台启动（可并行最多 $MAX_PARALLEL_SUB 个），主对话继续执行，互不阻塞。\n" +
                    "完成后系统会自动在主对话注入「子代理完成」通知；也可用 sub_session 工具查询：" +
                    "op=status 看进度、op=result 配套 id 取结果。\n\n子会话结果摘要：\n" +
                    "（后台执行中——完成后完整过程写入该子会话。）"
        )
    }

    /** sub_session 工具：查询子会话运行状态 / 列表 / 结果（op=status|list|result） */
    private fun querySubSession(args: JSONObject): ToolResult {
        val op = args.optString("op", "status").trim().ifBlank { "status" }
        val id = args.optString("id", "").trim()
        return when (op) {
            "status" -> {
                val snap = SubSessionProgress.snapshot()
                if (snap.isEmpty()) {
                    ToolResult.ok("当前没有正在执行的子会话。")
                } else {
                    ToolResult.ok(
                        snap.entries.joinToString("\n") { (sid, e) ->
                            "$sid | ${e.title} | 已 ${e.steps} 步" +
                                    if (e.lastTool.isNotEmpty()) " | 当前：${e.lastTool}" else ""
                        }
                    )
                }
            }
            "list" -> {
                val list = DesktopSessionStore.list().filter { it.parentId != null }
                if (list.isEmpty()) {
                    ToolResult.ok("暂无子会话记录。")
                } else {
                    ToolResult.ok(
                        list.joinToString("\n") { m ->
                            val cnt = DesktopSessionStore.load(m.id)?.messages?.size ?: 0
                            "${m.id} | ${m.title} | $cnt 条消息"
                        }
                    )
                }
            }
            "result" -> {
                if (id.isBlank()) return ToolResult.fail("op=result 需要 id 参数")
                val s = DesktopSessionStore.load(id)
                    ?: return ToolResult.fail("找不到子会话 $id（可用 op=list 查看可用 id）")
                val finalText = s.messages.lastOrNull {
                    it.type == "text" && it.role == "assistant"
                }?.content ?: "（子会话未产生文本结果）"
                ToolResult.ok(
                    "子会话 $id「${s.title}」共 ${s.messages.size} 条消息。\n最终结果：\n" +
                            finalText.take(1500)
                )
            }
            "stop" -> {
                if (id.isBlank()) return ToolResult.fail("op=stop 需要 id 参数")
                val job = subSessionJobs[id]
                    ?: return ToolResult.fail("子会话 $id 不在运行中（可能已完成，可用 op=list 查看）")
                job.cancel()
                subSessionJobs.remove(id)
                SubSessionProgress.finish(id)
                subSessionActive.decrementAndGet()
                ToolResult.ok("已终止子会话 $id。")
            }
            else -> ToolResult.fail("op 取值为 status / list / result / stop")
        }
    }

    private suspend fun doExecute(name: String, argsJson: String): ToolResult {
        return try {
            val args = if (argsJson.isBlank()) JSONObject() else JSONObject(argsJson)

            // 元工具拦截（在 disabled / vision 检查之前，避免误拦）
            if (name == "tool_search") {
                val query = args.optString("query", "").trim()
                if (query.isBlank()) return ToolResult.fail("tool_search 需要 query 参数")
                val category = args.optString("category", "").trim()
                return ToolResult.ok(searchToolsForAgent(query, category))
            }
            if (name == "tool_load") {
                val toolName = args.optString("name", "").trim()
                if (toolName.isBlank()) return ToolResult.fail("tool_load 需要 name 参数")
                val text = loadToolForAgent(toolName)
                return if (text.startsWith("失败")) {
                    ToolResult.fail(text.removePrefix("失败：").trim())
                } else {
                    ToolResult.ok(text)
                }
            }
            if (name == "run_sub_session") {
                return runSubSession(args)
            }
            if (name == "sub_session") {
                return querySubSession(args)
            }
            if (name == "history") {
                return HistoryTools.execute(args, BackupHelper.backupDirFor(host.trashDir()))
            }

            if (name in VISION_TOOLS) {
                val profile = DesktopSettingsHolder.current.currentProfile()
                if (profile != null && !profile.supportsVision) {
                    return ToolResult.fail(
                        "当前模型 [${profile.name}] 不支持图片输入，「${displayName(name)}」已禁用。" +
                                "请到设置里勾选该模型的「支持图片输入」，或换一个支持多模态的模型。"
                    )
                }
            }

            McpTools.execute(name, args)?.let { return it }

            if (name == "write_file") {
                return confirmThenWrite(args)
            }

            CommonTools.execute(name, args, host)?.let { return it }

            if (name == "workflow") {
                WorkflowTools.execute(name, args) { toolName, toolArgs ->
                    execute(toolName, toolArgs.toString())
                }?.let { return it }
            }

            if (name == "schedule") {
                // 传当前协程上下文的会话 id（schedule 创建 run_agent 时会绑定它）
                ScheduleTools.execute(name, args, currentSessionId())?.let { return it }
            }
            if (name == "system") {
                SystemIntegrationTools.execute(name, args)?.let { return it }
            }
            if (name == "apps") {
                AppTools.execute(name, args)?.let { return it }
            }
            if (name == "screenshot") {
                ScreenshotTools.execute(name, args)?.let { return it }
            }
            if (name == "memory") {
                MemoryTools.execute(name, args)?.let { return it }
            }
            if (name == "ffmpeg") {
                FfmpegTools.execute(name, args)?.let { return it }
            }
            if (name == "python") {
                PythonTools.execute(name, args)?.let { return it }
            }
            if (name == "web") {
                WebTools.execute(name, args)?.let { return it }
            }
            if (name == "ui") {
                ScreenTools.execute(name, args)?.let { return it }
            }
            if (name == "code_edit") {
                return confirmThenEdit(args)
            }
            if (name == "skill" || name == "pytool" || SkillTools.isDynamicTool(name)) {
                SkillTools.execute(name, args)?.let { return it }
            }
            if (name == "document") {
                DocumentTools.execute(name, args, host)?.let { return it }
            }
            if (name == "generate_image") {
                ImageGenTools.execute(name, args, host)?.let { return it }
            }
            if (name == "download_file") {
                DownloadTools.execute(name, args, host)?.let { return it }
            }

            when (name) {
                "delete_file" -> deleteFile(args)
                "execute_command" -> executeCommand(args)
                "open_url" -> openUrl(args)
                "view_image" -> viewImage(args)
                else -> ToolResult.fail("未知工具：$name")
            }
        } catch (e: Exception) {
            DesktopLogger.error(
                "DesktopTools",
                "doExecute 异常：${e.javaClass.simpleName}: ${e.message}"
            )
            ToolResult.fail("执行异常：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // write_file 确认

    private suspend fun confirmThenWrite(args: JSONObject): ToolResult {
        val pathStr = args.optString("path", "").trim()
        if (pathStr.isBlank()) return ToolResult.fail("未提供 path")
        val resolved = host.resolvePath(pathStr)
            ?: return ToolResult.fail("无法解析路径：$pathStr")

        val mode = args.optString("mode", "text")
        val content = args.optString("content", "")
        val data = args.optString("data", "")
        val append = args.optBoolean("append", false)

        val settings = DesktopSettingsHolder.current
        if (settings.requireConfirmWrite) {
            val preview = when (mode) {
                "base64" -> "（base64 数据 ${data.length} 字符）"
                else -> if (content.length > 200) content.take(200) + "..." else content
            }
            val op = if (append) "追加" else "写入"
            val approved = DesktopConfirmation.request(
                "写入文件确认",
                "AI 请求$op：\n$resolved\n\n内容预览：\n$preview\n\n是否允许？"
            )
            if (!approved) return ToolResult.fail("用户已拒绝写入操作，文件未改动")
        }

        // 内置 git 改前内容先读进内存，写入后与改后内容做行级 diff 存进历史。
        // 备份失败不阻断写入。
        val target = File(resolved)
        val oldBytes = try {
            if (target.exists()) target.readBytes() else null
        } catch (_: Exception) { null }

        val result = CommonTools.execute("write_file", args, host)
            ?: ToolResult.fail("通用工具未处理")

        if (settings.fileBackupEnabled && result.success) {
            BackupHelper.backupDirFor(host.trashDir())?.let { dir ->
                val newBytes = try {
                    if (target.exists()) target.readBytes() else null
                } catch (_: Exception) { null }
                HistoryStore.recordEdit(
                    dir, resolved, if (append) "append" else "write",
                    oldBytes, newBytes, "write_file",
                    sessionId = currentSessionId()
                )
            }
        }
        return result
    }

    // code_edit 确认 + 备份

    /**
     * code_edit 走与 write 同级的确认流（此前直通执行，等于绕过了
     * 「写入前确认」的防护）：列出本次涉及的文件让用户确认，确认后
     * 逐个把已存在文件的当前内容备份，再交回 CodeEditTools 执行。
     */
    private suspend fun confirmThenEdit(args: JSONObject): ToolResult {
        val files = args.optJSONArray("files")
        val paths = mutableListOf<String>()
        if (files != null) {
            for (i in 0 until files.length()) {
                files.optJSONObject(i)?.optString("path", "")?.takeIf { it.isNotBlank() }
                    ?.let { paths.add(it) }
            }
        }
        if (paths.isEmpty()) return ToolResult.fail("code_edit 未提供 files")

        val settings = DesktopSettingsHolder.current
        if (settings.requireConfirmWrite) {
            val list = paths.joinToString("\n") { "· $it" }
            val approved = DesktopConfirmation.request(
                "编辑文件确认",
                "AI 请求编辑 ${paths.size} 个文件：\n$list\n\n是否允许？"
            )
            if (!approved) return ToolResult.fail("用户已拒绝编辑操作，文件未改动")
        }

        // 内置 git 执行前把旧内容读进内存（执行后原内容就没了），
        // 执行成功后与改后内容做行级 diff，写入修改历史。
        val oldMap = LinkedHashMap<String, ByteArray?>()
        if (settings.fileBackupEnabled) {
            paths.forEach { p ->
                val resolved = host.resolvePath(p) ?: return@forEach
                val f = File(resolved)
                oldMap[resolved] = try {
                    if (f.exists()) f.readBytes() else null
                } catch (_: Exception) { null }
            }
        }

        val result = CodeEditTools.execute("code_edit", args)
            ?: ToolResult.fail("code_edit 工具未处理")

        if (settings.fileBackupEnabled && result.success) {
            BackupHelper.backupDirFor(host.trashDir())?.let { dir ->
                oldMap.forEach { (resolved, old) ->
                    val newBytes = try {
                        val f = File(resolved)
                        if (f.exists()) f.readBytes() else null
                    } catch (_: Exception) { null }
                    HistoryStore.recordEdit(
                        dir, resolved, "code_edit", old, newBytes, "code_edit",
                        sessionId = currentSessionId()
                    )
                }
            }
        }
        return result
    }

    // 私有工具实现

    private suspend fun deleteFile(args: JSONObject): ToolResult {
        val pathStr = args.optString("path", "").trim()
        if (pathStr.isBlank()) return ToolResult.fail("未提供 path")
        val resolved = host.resolvePath(pathStr)
            ?: return ToolResult.fail("无法解析路径：$pathStr")
        val file = File(resolved)
        if (!file.exists()) return ToolResult.fail("文件不存在")

        val settings = DesktopSettingsHolder.current
        if (settings.requireConfirmDelete) {
            val approved = DesktopConfirmation.request(
                "删除文件确认",
                "AI 请求删除：\n${file.absolutePath}\n\n文件会移入回收站（可恢复），是否允许？"
            )
            if (!approved) return ToolResult.fail("用户已拒绝删除操作，文件未删除")
        }

        // 不真删，移入回收站，由用户决定何时清空。
        val r = host.moveToTrash(file.absolutePath)
        return if (r.ok) {
            ToolResult.ok("已移入回收站：${file.absolutePath}\n回收站位置：${r.trashPath}")
        } else {
            ToolResult.fail("删除失败：${r.message}")
        }
    }

    /**
     * 检测命令里是否包含删除/破坏性操作。
     *
     * 为什么 AI 可以通过 execute_command 跑 `del` / `rm` / `Remove-Item`
     * 绕过 delete_file 的回收站机制，直接真删。这里做一道拦截。
     *
     * 返回 null 表示安全；非 null 是拒绝文案。
     */
    private val DESTRUCTIVE_CMD_PATTERNS = listOf(
        Regex("""(^|[\s&|;(])(del|erase)(\s|$)"""),
        Regex("""(^|[\s&|;(])(rm|rmdir|rd)(\s|$)"""),
        Regex("""(^|[\s&|;(])remove-item(\s|$)"""),
        Regex("""(^|[\s&|;(])ri(\s|$)"""),
        Regex("""shutil\.rmtree"""),
        Regex("""os\.remove"""),
        Regex("""os\.unlink"""),
        Regex("""os\.rmdir"""),
        Regex("""pathlib.*\.unlink"""),
        Regex("""files\.delete"""),
        Regex("""delete-recursively""")
    )

    private fun detectDestructiveCommand(command: String): String? {
        val c = command.lowercase()
        val hit = DESTRUCTIVE_CMD_PATTERNS.firstOrNull { it.containsMatchIn(c) } ?: return null
        return "已拦截：命令包含删除操作（${hit.pattern}）。\n" +
                "AI 不得直接删除文件。如需删除，请改用 delete_file 工具，" +
                "文件会被移入回收站，由用户决定何时清空。"
    }

    private suspend fun executeCommand(args: JSONObject): ToolResult {
        val command = args.optString("command", "").trim()
        if (command.isBlank()) return ToolResult.fail("未提供 command")
        val timeout = args.optInt("timeout", 30).coerceIn(1, 300)
        val grepFilter = args.optString("grep_filter", "").trim()
        val filterRegex = if (grepFilter.isNotBlank()) Regex(grepFilter, RegexOption.IGNORE_CASE) else null

        detectDestructiveCommand(command)?.let { return ToolResult.fail(it) }

        val settings = DesktopSettingsHolder.current
        if (settings.requireConfirmShell) {
            val preview = if (command.length > 300) command.take(300) + "..." else command
            val approved = DesktopConfirmation.request(
                "执行命令确认",
                "AI 请求执行以下命令：\n\n$preview\n\n是否允许？"
            )
            if (!approved) return ToolResult.fail("用户已拒绝执行命令")
        }

        return withContext(Dispatchers.IO) {
            val taskId = TaskRuntime.currentId()
                ?: return@withContext ToolResult.fail("任务状态丢失")

            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val shell = if (isWindows) arrayOf("cmd.exe", "/c", "chcp 65001 >nul && $command")
            else arrayOf("sh", "-c", command)
            val cwdStr = args.optString("cwd", "").trim()
            val cwd = if (cwdStr.isBlank()) null else host.resolvePath(cwdStr)?.let { File(it) }

            var process: Process? = null

            TaskRuntime.registerCancel(taskId) {
                val p = process
                if (p != null) {
                    try {
                        val descendants = p.toHandle().descendants().toList()
                        descendants.forEach { h ->
                            try { h.destroyForcibly() } catch (_: Exception) { }
                        }
                        p.destroyForcibly()
                    } catch (_: Throwable) { }

                    if (isWindows) {
                        try {
                            val pid = try { p.pid() } catch (_: Throwable) { -1L }
                            if (pid > 0) {
                                ProcessBuilder("taskkill", "/F", "/T", "/PID", pid.toString())
                                    .redirectErrorStream(true)
                                    .start()
                            }
                        } catch (_: Throwable) { }
                    }
                }
            }

            try {
                val pb = ProcessBuilder(*shell)
                if (cwd != null && cwd.isDirectory) pb.directory(cwd)
                pb.redirectErrorStream(false)

                val env = pb.environment()
                if (env["LANG"].isNullOrBlank()) env["LANG"] = "zh_CN.UTF-8"
                if (env["LC_ALL"].isNullOrBlank()) env["LC_ALL"] = "zh_CN.UTF-8"

                val p = pb.start()
                process = p

                val stdoutSb = StringBuilder()
                val stderrSb = StringBuilder()

                val stdoutThread = Thread {
                    try {
                        readLinesSmart(p.inputStream) { line ->
                            TaskRuntime.appendOutput(taskId, line)
                            if (filterRegex == null || filterRegex.containsMatchIn(line)) {
                                if (stdoutSb.length < 100_000_000) stdoutSb.append(line).append("\n")
                            }
                        }
                    } catch (e: Exception) {
                        DesktopLogger.warn("DesktopTools", "读命令 stdout 失败：${e.message}")
                    }
                }
                val stderrThread = Thread {
                    try {
                        readLinesSmart(p.errorStream) { line ->
                            TaskRuntime.appendOutput(taskId, "[err] $line")
                            if (filterRegex == null || filterRegex.containsMatchIn(line)) {
                                if (stderrSb.length < 100_000_000) stderrSb.append(line).append("\n")
                            }
                        }
                    } catch (e: Exception) {
                        DesktopLogger.warn("DesktopTools", "读命令 stderr 失败：${e.message}")
                    }
                }
                stdoutThread.start()
                stderrThread.start()

                val finished = p.waitFor(timeout.toLong(), TimeUnit.SECONDS)

                if (!finished) {
                    try {
                        val descendants = p.toHandle().descendants().toList()
                        descendants.forEach { try { it.destroyForcibly() } catch (_: Exception) { } }
                        p.destroyForcibly()
                    } catch (_: Throwable) { }
                    if (isWindows) {
                        try {
                            val pid = try { p.pid() } catch (_: Throwable) { -1L }
                            if (pid > 0) ProcessBuilder("taskkill", "/F", "/T", "/PID", pid.toString())
                                .redirectErrorStream(true).start()
                        } catch (_: Throwable) { }
                    }
                    TaskRuntime.finishWithCode(taskId, -1, timedOut = true)
                    return@withContext ToolResult.fail(
                        "命令执行超时（${timeout}秒），已强制终止\n\n" +
                                "已收到的输出：\n" + stdoutSb.toString().take(3000)
                    )
                }

                try { stdoutThread.join(500) } catch (_: Exception) { }
                try { stderrThread.join(500) } catch (_: Exception) { }

                val exitCode = p.exitValue()

                val wasCancelled = TaskRuntime.tasks.value
                    .firstOrNull { it.id == taskId }?.cancelled == true

                if (!wasCancelled) {
                    TaskRuntime.finishWithCode(taskId, exitCode, timedOut = false)
                }

                val compressedStdout = OutputCompressor.compress(stdoutSb.toString())
                val compressedStderr = OutputCompressor.compress(stderrSb.toString())

                val fullSb = StringBuilder()
                fullSb.append("退出码：$exitCode\n")
                if (compressedStdout.isNotEmpty()) {
                    fullSb.append("stdout：\n").append(compressedStdout)
                }
                if (compressedStderr.isNotEmpty()) {
                    fullSb.append("\nstderr：\n").append(compressedStderr)
                }
                if (compressedStdout.isEmpty() && compressedStderr.isEmpty()) fullSb.append("(无输出或已被过滤)")

                if (wasCancelled) {
                    ToolResult.fail("用户已取消命令执行\n\n已收到的输出：\n" + fullSb.toString().take(2000))
                } else {
                    ToolResult(exitCode == 0, fullSb.toString())
                }
            } catch (e: Exception) {
                DesktopLogger.error("DesktopTools", "executeCommand 异常：${e.javaClass.simpleName}: ${e.message}")
                TaskRuntime.finishWithCode(taskId, -1, timedOut = false)
                ToolResult.fail("执行失败：${e.message}")
            }
        }
    }

    private suspend fun openUrl(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val url = args.optString("url", "").trim()
        if (url.isBlank()) return@withContext ToolResult.fail("未提供 url")
        val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        val os = System.getProperty("os.name").lowercase()
        return@withContext try {
            val cmd = when {
                os.contains("win") -> arrayOf("rundll32", "url.dll,FileProtocolHandler", normalized)
                os.contains("mac") -> arrayOf("open", normalized)
                else -> arrayOf("xdg-open", normalized)
            }
            ProcessBuilder(*cmd).start()
            ToolResult.ok("已在浏览器打开：$normalized")
        } catch (e: Exception) {
            DesktopLogger.warn("DesktopTools", "打开 URL 失败：$normalized - ${e.message}")
            ToolResult.fail("打开失败：${e.message}")
        }
    }

    private suspend fun viewImage(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val pathStr = args.optString("path", "").trim()
        if (pathStr.isBlank()) return@withContext ToolResult.fail("未提供 path")
        val resolved = host.resolvePath(pathStr)
            ?: return@withContext ToolResult.fail("无法解析路径：$pathStr")
        val file = File(resolved)
        if (!file.exists()) return@withContext ToolResult.fail("图片不存在：$resolved")
        if (!file.isFile) return@withContext ToolResult.fail("不是文件")
        val ext = file.extension.lowercase()
        if (ext !in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")) {
            return@withContext ToolResult.fail("不支持的图片格式 .$ext（支持 jpg/png/gif/webp/bmp）")
        }
        if (file.length() > MAX_IMAGE_BYTES) {
            return@withContext ToolResult.fail(
                "图片过大（${host.formatSize(file.length())}），上限 ${host.formatSize(MAX_IMAGE_BYTES)}。" +
                        "请先压缩或裁剪后再试。"
            )
        }
        net.xinkong.furagent.desktop.core.PendingImages.push(resolved)
        ToolResult.ok("已加载图片：$resolved（${host.formatSize(file.length())}）。图片已附加到下一轮对话，AI 能看到。")
    }

    // Windows 命令输出智能解码

    private fun readLinesSmart(input: java.io.InputStream, onLine: (String) -> Unit) {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (true) {
            val n = input.read(chunk)
            if (n <= 0) break
            var start = 0
            for (i in 0 until n) {
                if (chunk[i] == '\n'.code.toByte()) {
                    buf.write(chunk, start, i - start)
                    var bytes = buf.toByteArray()
                    if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                        bytes = bytes.copyOfRange(0, bytes.size - 1)
                    }
                    onLine(decodeSmart(bytes))
                    buf.reset()
                    start = i + 1
                }
            }
            if (start < n) {
                buf.write(chunk, start, n - start)
            }
            if (buf.size() > 10_000_000) {
                var bytes = buf.toByteArray()
                if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                    bytes = bytes.copyOfRange(0, bytes.size - 1)
                }
                onLine(decodeSmart(bytes))
                buf.reset()
            }
        }
        if (buf.size() > 0) {
            var bytes = buf.toByteArray()
            if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                bytes = bytes.copyOfRange(0, bytes.size - 1)
            }
            onLine(decodeSmart(bytes))
        }
    }

    private fun decodeSmart(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return if (isValidUtf8(bytes)) {
            String(bytes, Charsets.UTF_8)
        } else {
            try {
                String(bytes, java.nio.charset.Charset.forName("GBK"))
            } catch (_: Exception) {
                String(bytes, Charsets.UTF_8)
            }
        }
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b < 0x80 -> i++
                b in 0xC2..0xDF -> {
                    if (i + 1 >= bytes.size) return false
                    if ((bytes[i + 1].toInt() and 0xFF) !in 0x80..0xBF) return false
                    i += 2
                }
                b in 0xE0..0xEF -> {
                    if (i + 2 >= bytes.size) return false
                    if ((bytes[i + 1].toInt() and 0xFF) !in 0x80..0xBF) return false
                    if ((bytes[i + 2].toInt() and 0xFF) !in 0x80..0xBF) return false
                    i += 3
                }
                b in 0xF0..0xF4 -> {
                    if (i + 3 >= bytes.size) return false
                    if ((bytes[i + 1].toInt() and 0xFF) !in 0x80..0xBF) return false
                    if ((bytes[i + 2].toInt() and 0xFF) !in 0x80..0xBF) return false
                    if ((bytes[i + 3].toInt() and 0xFF) !in 0x80..0xBF) return false
                    i += 4
                }
                else -> return false
            }
        }
        return true
    }
}