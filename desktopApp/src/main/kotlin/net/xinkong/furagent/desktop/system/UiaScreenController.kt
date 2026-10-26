package net.xinkong.furagent.desktop.system

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.xinkong.furagent.desktop.data.DesktopPaths
import net.xinkong.furagent.shared.util.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Windows UI Automation 屏幕控制器（常驻模式 v2）。
 *
 * 常驻 PowerShell
 *   从「每次调用 spawn 一个 powershell.exe，处理一条指令后退出」
 *   改成「init() 时 spawn 一次，之后所有调用通过 stdin/stdout 复用」。
 *
 *   冷启动代价（PowerShell 启动 + UIA assembly 加载）只付一次，
 *   后续每条指令 50~200ms。
 *
 * 历史关键修复 （保留）
 *  1. PowerShell 启动加 `-STA`：UIA 的 AutomationElement.RootElement 必须在 STA 线程访问。
 *  2. dump/find 只从前台窗口起：从 RootElement 起会遍历整个桌面。
 *  3. find 在 PowerShell 侧过滤。
 *  4. performAction 传给 PS 侧 resourceId/text/bounds，RuntimeId 找不到时 fallback。
 *  5. 首次探活失败时把 PS 的 error 暴露出来。
 *
 * 常驻模式的关键点
 *  - 脚本每轮读 stdin 一行 JSON，写 stdout 一行 JSON
 *  - Kotlin 侧维护一个 responseQueue，reader 线程把每行塞进去
 *  - 每条命令串行（callMutex）；发命令 → 等响应（超时 30s）
 *  - 超时/进程死 → 杀掉重启
 */
object UiaScreenController : ScreenController {

    private val callMutex = Mutex()

    @Volatile private var initialized = false
    @Volatile private var scriptFile: File? = null
    @Volatile private var initError: String? = null
    @Volatile private var availabilityCache: Boolean? = null
    @Volatile private var availabilityMissAt: Long = 0L
    @Volatile private var lastAvailabilityError: String? = null

    private const val AVAILABILITY_MISS_TTL_MS = 30_000L
    private const val SCRIPT_NAME = "furagent_uia.ps1"

    /** 单条命令的超时（秒）。dump 大树时可能接近几秒，给足 */
    private const val COMMAND_TIMEOUT_SEC = 30

    /** 探活超时（秒），比常规短一点 */
    private const val PROBE_TIMEOUT_SEC = 12

    // 常驻进程状态

    private val psScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val psLock = Any()

    @Volatile private var psProcess: Process? = null
    @Volatile private var psWriter: BufferedWriter? = null
    @Volatile private var psReaderJob: Job? = null
    @Volatile private var psStderrJob: Job? = null
    @Volatile private var psWatchdogJob: Job? = null

    /** 每行响应入队；每发一条命令，等一个响应 */
    private val psResponseQueue = LinkedBlockingQueue<String>(32)

    fun getLastError(): String? = initError ?: lastAvailabilityError

    // 脚本文件

    private fun ensureScript(): File? {
        if (initialized) return scriptFile
        synchronized(this) {
            if (initialized) return scriptFile
            initialized = true
            try {
                // 临时脚本落 ~/.furagent/temp/furagent_uia.ps1。
                val target = File(DesktopPaths.tempDir(), SCRIPT_NAME)
                // 每次都覆盖，保证版本一致
                val stream = javaClass.getResourceAsStream("/$SCRIPT_NAME")
                    ?: run {
                        initError = "内置脚本资源 /$SCRIPT_NAME 未找到（打包时可能漏了 resources）"
                        DesktopLogger.error("UIA", initError!!)
                        return null
                    }
                stream.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                if (!target.exists() || target.length() == 0L) {
                    initError = "写入脚本到 ${target.absolutePath} 失败"
                    DesktopLogger.error("UIA", initError!!)
                    return null
                }
                scriptFile = target
                DesktopLogger.info("UIA", "脚本已就绪：${target.absolutePath}")
                return target
            } catch (e: Exception) {
                initError = "准备脚本异常：${e.javaClass.simpleName}: ${e.message}"
                DesktopLogger.error("UIA", initError!!)
                return null
            }
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    // 常驻进程生命周期

    /**
     * 确保常驻进程在运行；不在运行就启动。
     * 返回 true 表示进程正在运行（可能刚启动），false 表示启动失败。
     */
    private fun ensurePersistentProcess(): Boolean {
        val cur = psProcess
        if (cur != null && cur.isAlive) return true

        synchronized(psLock) {
            val again = psProcess
            if (again != null && again.isAlive) return true

            val script = ensureScript() ?: return false

            return try {
                val p = ProcessBuilder(
                    "powershell.exe",
                    "-STA",                      // 关键 UIA 需要 STA
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-File", script.absolutePath,
                ).redirectErrorStream(false).start()

                psProcess = p
                psWriter = p.outputStream.bufferedWriter(Charsets.UTF_8)
                psResponseQueue.clear()

                startReaderThread(p)
                startStderrThread(p)
                startWatchdog(p)

                DesktopLogger.info("UIA", "常驻 PowerShell 已启动，pid=${p.pid()}")
                true
            } catch (e: Exception) {
                DesktopLogger.error(
                    "UIA",
                    "启动常驻 PowerShell 失败：${e.javaClass.simpleName}: ${e.message}"
                )
                psProcess = null
                psWriter = null
                false
            }
        }
    }

    /**
     * 读 stdout 的协程。每读到一行非空文本就入队。
     * 读到 EOF（进程退出）时清空进程状态。
     */
    private fun startReaderThread(p: Process) {
        psReaderJob?.cancel()
        psReaderJob = psScope.launch {
            try {
                val reader: BufferedReader = p.inputStream.bufferedReader(Charsets.UTF_8)
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    // 队列满时丢弃最旧一条（极端情况），保证新响应能进来
                    if (!psResponseQueue.offer(line)) {
                        psResponseQueue.poll()
                        psResponseQueue.offer(line)
                    }
                }
            } catch (e: Exception) {
                DesktopLogger.warn("UIA", "读 stdout 异常：${e.javaClass.simpleName}: ${e.message}")
            }
            onProcessGone(p)
        }
    }

    private fun startStderrThread(p: Process) {
        psStderrJob?.cancel()
        psStderrJob = psScope.launch {
            try {
                p.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    if (line.isNotBlank()) {
                        DesktopLogger.debug("UIA", "stderr: $line")
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun startWatchdog(p: Process) {
        psWatchdogJob?.cancel()
        psWatchdogJob = psScope.launch {
            try {
                val code = p.waitFor()
                DesktopLogger.info("UIA", "常驻 PowerShell 退出，code=$code")
            } catch (_: Exception) { }
            onProcessGone(p)
        }
    }

    private fun onProcessGone(p: Process) {
        synchronized(psLock) {
            if (psProcess === p) {
                psProcess = null
                try { psWriter?.close() } catch (_: Exception) { }
                psWriter = null
                psResponseQueue.clear()
            }
        }
    }

    // 发一条命令并等响应

    /**
     * 通过常驻进程发一条命令并等响应。
     *
     * 串行保证：callMutex 保护从"写 stdin"到"读到响应"的整个过程。
     * 超时：COMMAND_TIMEOUT_SEC 秒内没读到响应 → 杀进程 → 下次调用重建。
     */
    private suspend fun sendCommand(cmd: JSONObject, timeoutSec: Int): JSONObject? =
        withContext(Dispatchers.IO) {
            callMutex.withLock {
                if (!ensurePersistentProcess()) return@withLock null

                val writer = psWriter ?: return@withLock null
                val p = psProcess ?: return@withLock null

                // 清空历史残留（极端情况下上一次超时没读完）
                psResponseQueue.clear()

                try {
                    writer.write(cmd.toString())
                    writer.write("\n")
                    writer.flush()
                } catch (e: Exception) {
                    DesktopLogger.error(
                        "UIA",
                        "写 stdin 失败：${e.javaClass.simpleName}: ${e.message}，重启常驻进程"
                    )
                    killProcess(p)
                    return@withLock null
                }

                val deadline = System.currentTimeMillis() + timeoutSec * 1000L
                while (System.currentTimeMillis() < deadline) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    val pollMs = remaining.coerceAtMost(200L)
                    val line = try {
                        psResponseQueue.poll(pollMs, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) { null }

                    if (line != null) {
                        // 忽略明显不是 JSON 的行（脚本理论上只输出 JSON，但防御一下）
                        val trimmed = line.trim()
                        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                            DesktopLogger.debug("UIA", "忽略非 JSON 输出：${trimmed.take(200)}")
                            continue
                        }
                        return@withLock try {
                            JSONObject(trimmed)
                        } catch (e: Exception) {
                            DesktopLogger.warn(
                                "UIA",
                                "解析响应 JSON 失败：${e.message}，内容：${trimmed.take(300)}"
                            )
                            null
                        }
                    }
                }

                // 超时：杀进程
                DesktopLogger.warn("UIA", "命令超时（${timeoutSec}s），重启常驻进程")
                killProcess(p)
                null
            }
        }

    private fun killProcess(p: Process) {
        onProcessGone(p)
        try { p.destroy() } catch (_: Exception) { }
        try {
            if (!p.waitFor(500, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
            }
        } catch (_: Exception) { }
    }

    // ScreenController 实现

    override fun isAvailable(): Boolean {
        if (!isWindows()) return false
        availabilityCache?.let { return it }
        val now = System.currentTimeMillis()
        if (availabilityMissAt > 0 && now - availabilityMissAt < AVAILABILITY_MISS_TTL_MS) {
            return false
        }

        val ok = runBlocking {
            try {
                // 探活：dump 一个极浅的树
                val resp = sendCommand(
                    JSONObject().apply {
                        put("op", "dump")
                        put("maxDepth", 1)
                        put("maxNodes", 1)
                    },
                    PROBE_TIMEOUT_SEC
                )
                when {
                    resp == null -> {
                        lastAvailabilityError = "常驻 PowerShell 无响应（可能未安装 .NET UIAutomationClient，或 PowerShell 被策略拦截）"
                        false
                    }
                    !resp.optBoolean("ok", false) -> {
                        lastAvailabilityError = resp.optString("error", "未知错误")
                        DesktopLogger.warn("UIA", "探活失败：$lastAvailabilityError")
                        false
                    }
                    else -> {
                        lastAvailabilityError = null
                        true
                    }
                }
            } catch (e: Exception) {
                lastAvailabilityError = "${e.javaClass.simpleName}: ${e.message}"
                false
            }
        }

        if (ok) availabilityCache = true
        else availabilityMissAt = now
        return ok
    }

    /**
     * 上次 dump / find 返回的节点，用于 performAction 时提供 fallback 信息。
     * key = id，value = UiNode（含 resourceId / text / bounds）。
     */
    private val lastNodes = java.util.concurrent.ConcurrentHashMap<String, UiNode>()

    override suspend fun dumpScreen(maxDepth: Int, maxNodes: Int): ScreenDump {
        val cmd = JSONObject().apply {
            put("op", "dump")
            put("maxDepth", maxDepth.coerceIn(1, 50))
            put("maxNodes", maxNodes.coerceIn(10, 5000))
        }
        val resp = sendCommand(cmd, COMMAND_TIMEOUT_SEC)
            ?: return ScreenDump("", 0, 0, emptyList(), 0, false)
        if (!resp.optBoolean("ok", false)) {
            DesktopLogger.warn("UIA", "dump 失败：${resp.optString("error")}")
            return ScreenDump("", 0, 0, emptyList(), 0, false)
        }
        return parseDumpToScreenDump(resp)
    }

    override suspend fun findNodes(query: UiQuery): List<UiNode> {
        if (query.isEmpty) return emptyList()
        val cmd = JSONObject().apply {
            put("op", "find")
            if (query.text.isNotBlank()) put("text", query.text)
            put("textExact", query.textExact)
            if (query.textRegex.isNotBlank()) put("regex", query.textRegex)
            if (query.className.isNotBlank()) put("className", query.className)
            if (query.resourceId.isNotBlank()) put("resourceId", query.resourceId)
            query.clickable?.let { put("clickable", it) }
            query.editable?.let { put("editable", it) }
            query.scrollable?.let { put("scrollable", it) }
            query.enabled?.let { put("enabled", it) }
            put("maxResults", query.maxResults.coerceIn(1, 500))
        }
        val resp = sendCommand(cmd, COMMAND_TIMEOUT_SEC) ?: return emptyList()
        if (!resp.optBoolean("ok", false)) {
            DesktopLogger.warn("UIA", "find 失败：${resp.optString("error")}")
            return emptyList()
        }
        val flat = parseFlatNodes(resp)
        // 缓存起来，供 performAction 用
        flat.forEach { lastNodes[it.id] = it }
        return flat
    }

    override suspend fun performAction(
        nodeId: String, action: UiAction, text: String,
    ): ActionResult {
        val cached = lastNodes[nodeId]
        val cmd = JSONObject().apply {
            put("op", "action")
            put("nodeId", nodeId)
            put("action", action.name)
            put("text", text)
            cached?.let { c ->
                if (c.resourceId.isNotBlank()) put("resourceId", c.resourceId)
                if (c.text.isNotBlank()) put("textForMatch", c.text)
                if (c.bounds.isValid) {
                    put("boundsLeft", c.bounds.left)
                    put("boundsTop", c.bounds.top)
                }
            }
        }
        val resp = sendCommand(cmd, COMMAND_TIMEOUT_SEC)
            ?: return ActionResult.fail("PowerShell 调用失败")
        return if (resp.optBoolean("ok", false)) {
            ActionResult.ok(resp.optString("message", "已执行 $action"))
        } else {
            ActionResult.fail(resp.optString("error", "未知错误"))
        }
    }

    override suspend fun dispatchGesture(gesture: UiGesture): ActionResult {
        val type = gesture.type.lowercase()
        if (type !in listOf("tap", "long_press", "swipe")) {
            return ActionResult.fail("type 只能是 tap / long_press / swipe")
        }
        val cmd = JSONObject().apply {
            put("op", "gesture")
            put("type", type)
            put("x", gesture.startX)
            put("y", gesture.startY)
            put("startX", gesture.startX)
            put("startY", gesture.startY)
            put("endX", gesture.endX)
            put("endY", gesture.endY)
            put("durationMs", gesture.durationMs)
        }
        val resp = sendCommand(cmd, COMMAND_TIMEOUT_SEC)
            ?: return ActionResult.fail("PowerShell 调用失败")
        return if (resp.optBoolean("ok", false)) {
            ActionResult.ok(resp.optString("message", "手势已执行"))
        } else {
            ActionResult.fail(resp.optString("error", "未知错误"))
        }
    }

    override suspend fun globalAction(action: GlobalAction): ActionResult {
        val cmd = JSONObject().apply {
            put("op", "global")
            put("action", action.name)
        }
        val resp = sendCommand(cmd, COMMAND_TIMEOUT_SEC)
            ?: return ActionResult.fail("PowerShell 调用失败")
        return if (resp.optBoolean("ok", false)) {
            ActionResult.ok(resp.optString("message", "$action 已执行"))
        } else {
            ActionResult.fail(resp.optString("error", "未知错误"))
        }
    }

    // JSON → ScreenDump（树形重建）

    private class NodeBuilder(
        val id: String,
        val parentId: String,
        val baseNode: UiNode,
    ) {
        val children = mutableListOf<NodeBuilder>()
    }

    private fun parseDumpToScreenDump(json: JSONObject): ScreenDump {
        val nodesArr = json.optJSONArray("nodes") ?: JSONArray()
        val builders = LinkedHashMap<String, NodeBuilder>()
        val order = mutableListOf<String>()

        for (i in 0 until nodesArr.length()) {
            val o = nodesArr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isBlank()) continue
            val parentId = o.optString("parentId", "")
            val node = buildUiNode(o)
            builders[id] = NodeBuilder(id, parentId, node)
            order.add(id)
            lastNodes[id] = node
        }

        if (builders.isEmpty()) {
            return ScreenDump(
                packageName = "",
                screenWidth = json.optInt("screenWidth", 0),
                screenHeight = json.optInt("screenHeight", 0),
                nodes = emptyList(),
                totalNodes = json.optInt("totalNodes", 0),
                truncated = json.optBoolean("truncated", false),
            )
        }

        // 按 order 保证同一父节点下子节点顺序与原脚本一致
        val roots = mutableListOf<NodeBuilder>()
        for (id in order) {
            val b = builders[id] ?: continue
            val parent = builders[b.parentId]
            if (parent != null) parent.children.add(b) else roots.add(b)
        }

        fun build(b: NodeBuilder): UiNode = b.baseNode.copy(
            children = b.children.map { build(it) }
        )

        val tree = roots.map { build(it) }

        return ScreenDump(
            packageName = "",
            screenWidth = json.optInt("screenWidth", 0),
            screenHeight = json.optInt("screenHeight", 0),
            nodes = tree,
            totalNodes = json.optInt("totalNodes", 0),
            truncated = json.optBoolean("truncated", false),
        )
    }

    /**
     * find 返回的是平铺列表，不做树重建。
     */
    private fun parseFlatNodes(json: JSONObject): List<UiNode> {
        val nodesArr = json.optJSONArray("nodes") ?: return emptyList()
        val out = ArrayList<UiNode>(nodesArr.length())
        for (i in 0 until nodesArr.length()) {
            val o = nodesArr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isBlank()) continue
            out.add(buildUiNode(o))
        }
        return out
    }

    private fun buildUiNode(o: JSONObject): UiNode {
        val boundsArr = o.optJSONArray("bounds")
        val rect = if (boundsArr != null && boundsArr.length() >= 4) {
            UiRect(
                left = boundsArr.optInt(0, 0),
                top = boundsArr.optInt(1, 0),
                right = boundsArr.optInt(2, 0),
                bottom = boundsArr.optInt(3, 0),
            )
        } else UiRect()

        return UiNode(
            id = o.optString("id", ""),
            className = o.optString("className", ""),
            text = o.optString("text", ""),
            contentDesc = o.optString("contentDesc", ""),
            resourceId = o.optString("resourceId", ""),
            bounds = rect,
            packageName = o.optString("packageName", ""),
            clickable = o.optBoolean("clickable", false),
            longClickable = false,
            scrollable = o.optBoolean("scrollable", false),
            editable = o.optBoolean("editable", false),
            checkable = o.optBoolean("checkable", false),
            checked = false,
            selected = false,
            enabled = o.optBoolean("enabled", true),
            focused = o.optBoolean("focused", false),
            visible = o.optBoolean("visible", true),
        )
    }

    // 生命周期

    fun init() {
        if (!isWindows()) {
            DesktopLogger.info("UIA", "当前系统非 Windows，屏幕控制不可用")
            return
        }
        ScreenControlRegistry.register(this)
        // 提前启动常驻进程 + 探活（不阻塞主线程）
        Thread {
            try {
                val ok = isAvailable()
                if (ok) {
                    DesktopLogger.info("UIA", "屏幕控制探活：成功（常驻 PowerShell）")
                } else {
                    DesktopLogger.warn("UIA", "屏幕控制探活失败：${getLastError() ?: "未知原因"}")
                }
            } catch (e: Exception) {
                DesktopLogger.warn("UIA", "探活异常：${e.message}")
            }
        }.apply { isDaemon = true; start() }
    }

    fun shutdown() {
        // 通知脚本退出（关闭 stdin → ReadLine 返回 null → break）
        synchronized(psLock) {
            val p = psProcess
            try { psWriter?.close() } catch (_: Exception) { }
            psWriter = null
            try {
                if (p != null && !p.waitFor(500, TimeUnit.MILLISECONDS)) {
                    p.destroy()
                    if (!p.waitFor(500, TimeUnit.MILLISECONDS)) {
                        p.destroyForcibly()
                    }
                }
            } catch (_: Exception) { }
            psProcess = null
            psResponseQueue.clear()
        }

        psReaderJob?.cancel()
        psReaderJob = null
        psStderrJob?.cancel()
        psStderrJob = null
        psWatchdogJob?.cancel()
        psWatchdogJob = null
        psScope.cancel()

        if (ScreenControlRegistry.get() === this) {
            ScreenControlRegistry.register(null)
        }
    }
}