package net.xinkong.furagent.desktop.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.desktop.core.DesktopConfirmation
import net.xinkong.furagent.desktop.core.TaskRuntime
import net.xinkong.furagent.desktop.data.DesktopSettingsHolder
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
        // 【动态脚本工具】每个自定义脚本注册成一个独立工具 pytool_<name>。
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

        common + private + workflow + schedule + system + apps + screenshot +
                memory + ffmpeg + python + web + screen + codeEdit + skill + dynamicSkills + document + imageGen + download
    }

    @Volatile private var cachedToolList: List<DesktopTool>? = null
    @Volatile private var cacheExpireAt: Long = 0L

    /**
     * 全量工具列表（含 MCP）。1 秒缓存，避免频繁读 McpManager。
     *
     * 【P0 改造】Agent 循环已不再直接调用此方法构建 tools 数组，
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

    // ============================================================
    // 【P0】分层工具系统
    // ============================================================

    /**
     * 核心工具 + 已加载工具 + 元工具。
     *
     * Agent 循环里每轮调用，loadedNames 是跨轮次累积的"已加载工具名集合"。
     */
    fun getToolListWithLoaded(loadedNames: Set<String>): List<DesktopTool> {
        val result = mutableListOf<DesktopTool>()
        getToolList().forEach { t ->
            val isCore = t.name in ToolCategory.CORE_TOOL_NAMES
            val isLoaded = t.name in loadedNames
            // 【动态脚本工具】pytool_<name> 始终加入，让 AI 一眼看到有哪些脚本可用。
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
    private fun buildToolSearchSpec(): DesktopTool {
        val catalog = getCatalogTools()
        val profile = DesktopSettingsHolder.current.currentProfile()
        val visionEnabled = profile?.supportsVision ?: true
        val disabled = DesktopSettingsHolder.current.disabledTools

        val catalogText = ToolCatalog.buildCatalogText(
            tools = catalog,
            disabled = disabled,
            visionEnabled = visionEnabled,
            visionTools = VISION_TOOLS
        )

        val desc = buildString {
            // 【token 优化】精简说明：与系统提示词重复的部分不再赘述，
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

        return DesktopTool("tool_search", "工具搜索", desc, schema)
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

    // ============================================================
    // displayName / findToolNameByDisplay
    // ============================================================

    fun displayName(name: String): String {
        // 【P0】元工具显式映射（不在 baseTools 里）
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

    // ============================================================
    // 执行分发
    // ============================================================

    private val host: ToolHost = DesktopToolHost()

    suspend fun execute(name: String, argsJson: String): ToolResult {
        val subtitle: String = when (name) {
            "execute_command" -> try {
                if (argsJson.isBlank()) "" else JSONObject(argsJson).optString("command", "")
            } catch (_: Exception) { "" }
            else -> ""
        }
        return TaskRuntime.withTask(name, displayName(name), subtitle) {
            doExecute(name, argsJson)
        }
    }

    private suspend fun doExecute(name: String, argsJson: String): ToolResult {
        return try {
            val args = if (argsJson.isBlank()) JSONObject() else JSONObject(argsJson)

            // 【P0】元工具拦截（在 disabled / vision 检查之前，避免误拦）
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
                ScheduleTools.execute(name, args)?.let { return it }
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
                CodeEditTools.execute(name, args)?.let { return it }
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

    // ============================================================
    // write_file 确认
    // ============================================================

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

        return CommonTools.execute("write_file", args, host)
            ?: ToolResult.fail("通用工具未处理")
    }

    // ============================================================
    // 私有工具实现
    // ============================================================

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

        // 【安全删除】不真删，移入回收站，由用户决定何时清空。
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
     * 【为什么】AI 可以通过 execute_command 跑 `del` / `rm` / `Remove-Item`
     * 绕过 delete_file 的回收站机制，直接真删。这里做一道拦截。
     *
     * 返回 null 表示安全；非 null 是拒绝文案。
     */
    private fun detectDestructiveCommand(command: String): String? {
        val c = command.lowercase()
        // 常见删除命令（Windows + Unix + PowerShell + Python）
        val patterns = listOf(
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
        val hit = patterns.firstOrNull { it.containsMatchIn(c) } ?: return null
        return "已拦截：命令包含删除操作（${hit.pattern}）。\n" +
                "AI 不得直接删除文件。如需删除，请改用 delete_file 工具，" +
                "文件会被移入回收站，由用户决定何时清空。"
    }

    private suspend fun executeCommand(args: JSONObject): ToolResult {
        val command = args.optString("command", "").trim()
        if (command.isBlank()) return ToolResult.fail("未提供 command")
        val timeout = args.optInt("timeout", 30).coerceIn(1, 300)

        // 【安全删除】拦截命令行里的删除操作。
        // AI 不得以任何方式直接删除文件，包括通过 shell 绕过 delete_file。
        // 命中则拒绝，并引导改用 delete_file（会移入回收站）。
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
                            if (stdoutSb.length < 100_000_000) stdoutSb.append(line).append("\n")
                            TaskRuntime.appendOutput(taskId, line)
                        }
                    } catch (e: Exception) {
                        DesktopLogger.warn("DesktopTools", "读命令 stdout 失败：${e.message}")
                    }
                }
                val stderrThread = Thread {
                    try {
                        readLinesSmart(p.errorStream) { line ->
                            if (stderrSb.length < 100_000_000) stderrSb.append(line).append("\n")
                            TaskRuntime.appendOutput(taskId, "[err] $line")
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

                // 【输出裁剪】几百行日志会进入历史，每轮对话都要重发一遍。
                // 压缩到"关键行 + 上下文 + 尾部"，等量级缩减 80%+。
                // 完整输出仍可通过 read_file 读取落盘日志。
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
                if (compressedStdout.isEmpty() && compressedStderr.isEmpty()) fullSb.append("(无输出)")

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

    // ============================================================
    // Windows 命令输出智能解码
    // ============================================================

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