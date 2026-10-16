package net.xinkong.furagent.tools

import android.content.Context
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.core.ConfirmationBroker
import net.xinkong.furagent.data.AppSettings
import net.xinkong.furagent.data.currentProfile
import net.xinkong.furagent.shared.tools.*
import org.json.JSONArray
import org.json.JSONObject

const val MAX_FILE_READ = 1_000_000L
const val MAX_FILE_PREVIEW = 20_000

/** 需要多模态（vision）能力才能正确工作的工具集合 */
internal val VISION_TOOLS: Set<String> = setOf("screenshot", "view_image")

val SDCARD_PATH: String by lazy {
    try {
        android.os.Environment.getExternalStorageDirectory().absolutePath
    } catch (_: Exception) { "/sdcard" }
}

private val PRIVATE_TOOL_DISPLAY_NAMES: Map<String, String> = mapOf(
    "app" to "应用",
    "device" to "设备",
    "calendar" to "日程",
    "schedule" to "定时任务",
    "workflow" to "工作流",
    "memory" to "记忆",
    "web" to "联网",
    "file_op" to "文件操作",
    "zip" to "压缩包",
    "base64" to "Base64",
    "ffmpeg" to "音视频",
    "pc" to "电脑互联",
    "set_alarm" to "设置闹钟",
    "execute_shell" to "执行 Shell",
    "get_location" to "获取位置",
    "add_contact" to "新建联系人",
    "download_file" to "下载文件",
    "screenshot" to "截图",
    "view_image" to "查看图片",
    "media_session" to "媒体控制",
    "notifications" to "通知",
    "ui" to "屏幕控制",
    "generate_image" to "AI 生图"
)

private const val RAW_PRIVATE_TOOLS_JSON = """
[
  {"type":"function","function":{"name":"app","description":"应用管理。op=list 列出已安装应用（可 keyword 过滤）；op=open 打开应用（需要 package_name，不确定时先 list）；op=open_url 用浏览器打开网址；op=running 列出当前运行中的应用（需要 Shizuku 权限）。","parameters":{"type":"object","properties":{"op":{"type":"string","enum":["list","open","open_url","running"]},"keyword":{"type":"string"},"limit":{"type":"integer"},"package_name":{"type":"string"},"app_name":{"type":"string"},"url":{"type":"string"}},"required":["op"]}}},
  {"type":"function","function":{"name":"device","description":"设备信息与通知。op=status 读取设备状态（电量/存储/内存/网络）；op=info 读取详细设备信息（机型/Android 版本/ABI/屏幕等，需要详细机型信息时用它）；op=clipboard_read 读取剪贴板；op=clipboard_write 写入剪贴板（需要 text）；op=notify 发送系统通知（需要 title 和 content）。","parameters":{"type":"object","properties":{"op":{"type":"string","enum":["status","info","clipboard_read","clipboard_write","notify"]},"text":{"type":"string"},"title":{"type":"string"},"content":{"type":"string"}},"required":["op"]}}},
  {"type":"function","function":{"name":"calendar","description":"日历日程。op=add 打开系统新建日程页面（title + start_time，可选 end_time，用户需手动确认保存）；op=read 读取指定时间段日程（可选 start_time / end_time / limit）。","parameters":{"type":"object","properties":{"op":{"type":"string","enum":["add","read"]},"title":{"type":"string"},"start_time":{"type":"string"},"end_time":{"type":"string"},"limit":{"type":"integer"}},"required":["op"]}}},
  {"type":"function","function":{"name":"schedule","description":"定时任务（秒级轮询 / cron / 条件触发）。op=create 创建任务（action 支持 open_app / run_cron / send_notification / run_workflow，可带 repeat_mode / interval_seconds / cron_expression / conditions）；op=list 列出所有任务；op=cancel 按 task_id 或 keyword 取消。所有时间/周期/条件触发需求优先使用。","parameters":{"type":"object","properties":{"op":{"type":"string","enum":["create","list","cancel"]},"action":{"type":"string"},"description":{"type":"string"},"package_name":{"type":"string"},"app_name":{"type":"string"},"command":{"type":"string"},"notification_title":{"type":"string"},"notification_content":{"type":"string"},"workflow_name":{"type":"string"},"trigger_time":{"type":"string"},"repeat_mode":{"type":"string"},"interval_seconds":{"type":"integer"},"cron_expression":{"type":"string"},"repeat_days":{"type":"string"},"conditions":{"type":"array","items":{"type":"object","properties":{"type":{"type":"string"},"operator":{"type":"string"},"value":{"type":"string"},"value2":{"type":"string"}}}},"condition_mode":{"type":"string"},"skip_if_not_met":{"type":"boolean"},"task_id":{"type":"string"},"keyword":{"type":"string"}},"required":["op"]}}},
  {"type":"function","function":{"name":"workflow","description":"工作流管理。op=create 创建或更新工作流（需要 name 和 steps_json）；op=delete 按名称删除；op=list 列出所有工作流；op=run 执行指定工作流（需要 name，可选 params）。","parameters":{"type":"object","properties":{"op":{"type":"string","enum":["create","delete","list","run"]},"name":{"type":"string"},"description":{"type":"string"},"params":{"type":"object"},"steps_json":{"type":"string"}},"required":["op"]}}},
  {"type":"function","function":{"name":"memory","description":"长期记忆。op=save 保存一条记忆（需要 content）；op=recall 按 query 检索已保存的记忆（query 为空则列出全部）。","parameters":{"type":"object","properties":{"op":{"type":"string","enum":["save","recall"]},"content":{"type":"string"},"query":{"type":"string"}},"required":["op"]}}},
  {"type":"function","function":{"name":"web","description":"联网工具。op=search 搜索（最多 8 条结果，可 page 翻页；可选 engine=bing/baidu/360 指定搜索源，不填按 360→百度→必应 顺序自动回退）；op=fetch 抓取指定 URL 的网页正文（可 max_length 控制长度，默认 8000）。用户提供 URL 且要求总结或查看内容时，直接用 fetch 抓正文，不要只依赖搜索摘要。","parameters":{"type":"object","properties":{"op":{"type":"string","enum":["search","fetch"]},"query":{"type":"string"},"page":{"type":"integer"},"engine":{"type":"string"},"url":{"type":"string"},"max_length":{"type":"integer"}},"required":["op"]}}},
  {"type":"function","function":{"name":"file_op","description":"文件操作（写操作，需确认）。op=create 创建新文件（path + content）；op=delete 删除文件（path）；op=rename 重命名或移动（old_path + new_path）；op=copy 复制（source + dest）；op=edit 覆盖已存在文件（path + content）。","parameters":{"type":"object","properties":{"op":{"type":"string","enum":["create","delete","rename","copy","edit"]},"path":{"type":"string"},"content":{"type":"string"},"old_path":{"type":"string"},"new_path":{"type":"string"},"source":{"type":"string"},"dest":{"type":"string"}},"required":["op"]}}},
  {"type":"function","function":{"name":"zip","description":"压缩与解压。op=zip 把目录压缩为 zip（source_dir + output_zip）；op=unzip 解压 zip（zip_path + target_dir）。需确认。","parameters":{"type":"object","properties":{"op":{"type":"string","enum":["zip","unzip"]},"source_dir":{"type":"string"},"output_zip":{"type":"string"},"zip_path":{"type":"string"},"target_dir":{"type":"string"}},"required":["op"]}}},
  {"type":"function","function":{"name":"base64","description":"Base64 编解码。op=decode 把 base64 字符串解码为文件（content + save_path）；op=encode 把本地文件编码为 base64（path，可选 max_size_kb 默认 512）。","parameters":{"type":"object","properties":{"op":{"type":"string","enum":["decode","encode"]},"content":{"type":"string"},"save_path":{"type":"string"},"path":{"type":"string"},"max_size_kb":{"type":"integer"}},"required":["op"]}}},
  {"type":"function","function":{"name":"ffmpeg","description":"音视频处理。mode=probe 读取媒体文件信息（path，可选 format=text/json）；mode=run 执行 FFmpeg 命令（args，可选 timeout）；mode=preset 使用预设处理（preset + input + output，可选 options）；mode=capabilities 查询 FFmpeg 能力（type=version/codecs/formats/hwaccels，可选 filter）。需确认。手机编解码能力有限：换容器不换内容（mkv→mp4、提取音轨、裁剪不改码）必须用 -c copy；需重编码时优先硬件编码（预设内置 h264_mediacodec）；长视频先截片段再编码；默认 crf=28、preset=veryfast、分辨率 960:-2 或 1280:-2、音频 aac 128k；输出默认 /sdcard/FurAgent/media/。","parameters":{"type":"object","properties":{"mode":{"type":"string","enum":["probe","run","preset","capabilities"]},"path":{"type":"string"},"format":{"type":"string"},"args":{"type":"string"},"timeout":{"type":"integer"},"preset":{"type":"string"},"input":{"type":"string"},"output":{"type":"string"},"options":{"type":"object"},"type":{"type":"string"},"filter":{"type":"string"}},"required":["mode"]}}},
  {"type":"function","function":{"name":"pc","description":"与电脑端 FurAgent 互联（需先在设置里启用并连接）。op=status 查看连接状态；op=list_tools 列出电脑端工具；op=call 让电脑执行工具；op=send_file 发送文件到电脑；op=receive_file 从电脑接收文件；op=wake 通过 WOL 唤醒电脑；op=known 列出曾连接过的电脑；op=relay 通过电脑中转文件给另一台设备；op=relay_status 查询 relay 结果；op=peers 列出通过电脑看到的在线设备。各 op 的参数见 parameters。","parameters":{"type":"object","properties":{"op":{"type":"string","enum":["status","list_tools","call","send_file","receive_file","wake","known","relay","relay_status","peers"]},"tool":{"type":"string"},"args":{"type":"object"},"timeout":{"type":"integer"},"local_path":{"type":"string"},"remote_path":{"type":"string"},"mac":{"type":"string"},"broadcast":{"type":"string"},"target":{"type":"string"},"relay_id":{"type":"string"}},"required":["op"]}}},
  {"type":"function","function":{"name":"set_alarm","description":"设置系统闹钟。","parameters":{"type":"object","properties":{"hour":{"type":"integer"},"minute":{"type":"integer"},"label":{"type":"string"}},"required":["hour","minute"]}}},
  {"type":"function","function":{"name":"execute_shell","description":"通过 Shizuku 执行 shell 命令。需确认。","parameters":{"type":"object","properties":{"command":{"type":"string"},"timeout":{"type":"integer"}},"required":["command"]}}},
  {"type":"function","function":{"name":"get_location","description":"获取当前经纬度和可读地址。","parameters":{"type":"object","properties":{}}}},
  {"type":"function","function":{"name":"add_contact","description":"打开系统新建联系人页面。","parameters":{"type":"object","properties":{"name":{"type":"string"},"phone":{"type":"string"}},"required":["name"]}}},
  {"type":"function","function":{"name":"download_file","description":"从 URL 下载文件。支持多线程分片加速、断点续传、代理（走梯子）。【线程数】threads 参数由你（AI）按情况判断：小文件（<1MB）用 1；大文件（>10MB）且服务器支持 Range 时可用 4~8；不确定就用 1（最稳）。服务器不支持 Range 时会自动降级为单线程。【代理】需要走梯子时传 proxy，如 http://127.0.0.1:7890；不传则自动读环境变量 HTTP_PROXY/HTTPS_PROXY。需确认。","parameters":{"type":"object","properties":{"url":{"type":"string","description":"下载地址"},"save_path":{"type":"string","description":"保存路径"},"threads":{"type":"integer","description":"下载线程数，由 AI 判断。默认 1。大文件可 4~8，服务器不支持 Range 时自动降级为 1"},"proxy":{"type":"string","description":"可选代理，如 http://127.0.0.1:7890（走梯子）。不传则读环境变量 HTTP_PROXY/HTTPS_PROXY"},"resume":{"type":"boolean","description":"断点续传，默认 false"}},"required":["url","save_path"]}}},
  {"type":"function","function":{"name":"generate_image","description":"用 AI 生成图片并保存到本地。需要先在「设置 → 文生图模型」里选中一个生图模型。生成后返回图片路径，可用 view_image 查看。【保存位置】建议存到 /sdcard/FurAgent/images/。save_path 可省略，省略时自动存到 /sdcard/FurAgent/images/ 下（时间戳命名）。","parameters":{"type":"object","properties":{"prompt":{"type":"string","description":"图片描述（越具体越好）"},"save_path":{"type":"string","description":"保存路径。推荐存到 /sdcard/FurAgent/images/xxx.png。可省略"},"size":{"type":"string","description":"尺寸，如 1024x1024 / 1024x1536 / 1792x1024，默认 1024x1024"},"model":{"type":"string","description":"可选，覆盖配置里的生图模型名"}},"required":["prompt"]}}},
  {"type":"function","function":{"name":"screenshot","description":"截取当前屏幕并附加到下一轮上下文。需要 Shizuku 权限 + 当前模型支持图片输入。","parameters":{"type":"object","properties":{}}}},
  {"type":"function","function":{"name":"view_image","description":"加载本地图片并附加到下一轮上下文。需要当前模型支持图片输入。","parameters":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}},
  {"type":"function","function":{"name":"media_session","description":"读取和控制手机上的媒体播放（音乐/播客/视频）。op=status 列出活跃媒体会话；op=now 读当前播放信息；op=play/pause/toggle 播放暂停；op=next/prev 切歌；op=stop 停止；op=seek 跳转。可选 target 指定包名，不填自动选当前播放的。首次使用前需用户在系统设置里开启「通知使用权」给 FurAgent。","parameters":{"type":"object","properties":{"op":{"type":"string","enum":["status","now","play","pause","toggle","next","prev","stop","seek"]},"target":{"type":"string"},"include_art":{"type":"boolean"},"position_seconds":{"type":"number"}},"required":["op"]}}},
  {"type":"function","function":{"name":"notifications","description":"读取手机上的通知。op=list 列出最近的通知（limit 默认 30，最多 100，含应用名/标题/内容/时间）；op=clear 清空已缓存的通知。首次使用前需用户在系统设置里开启「通知使用权」给 FurAgent（与 media_session 是同一个权限）。","parameters":{"type":"object","properties":{"op":{"type":"string","enum":["list","clear"]},"limit":{"type":"integer"}},"required":["op"]}}},
  {"type":"function","function":{"name":"ui","description":"屏幕控制。直接读取屏幕 UI 元素树并操作，不依赖截图，Token 消耗低，坐标不受窗口移动影响。需要用户先在系统设置里开启「FurAgent 屏幕控制」无障碍服务。op=status 查服务状态；op=dump 获取当前 UI 树；op=find 按条件查找元素（AND 语义）；op=action 对元素操作（需 node_id）；op=gesture 手势；op=global 系统动作。各 op 的参数见 parameters。node_id 只在下一次 dump/find 之前有效，UI 变化后需重新获取。典型工作流：先 dump 或 find 拿 id，再 action。UI 树中标记 (invisible) 的元素当前不可见（可能在屏幕外、被折叠、或在其它 Tab 里），不要直接对 (invisible) 元素执行 action，先确认它已变为可见再操作。标记 {gN} 表示这几个节点来自同一个被压平的父容器（如 RecyclerView 里的一行、TabLayout 里的一组 Tab），同一组内的节点通常有并列关系，按顺序或按 text 区分。","parameters":{"type":"object","properties":{"op":{"type":"string","enum":["status","dump","find","action","gesture","global"]},"max_depth":{"type":"integer"},"max_nodes":{"type":"integer"},"text":{"type":"string"},"text_exact":{"type":"boolean"},"regex":{"type":"string"},"class_name":{"type":"string"},"resource_id":{"type":"string"},"clickable":{"type":"boolean"},"editable":{"type":"boolean"},"scrollable":{"type":"boolean"},"enabled":{"type":"boolean"},"max_results":{"type":"integer"},"node_id":{"type":"string"},"action":{"type":"string"},"type":{"type":"string"},"x":{"type":"integer"},"y":{"type":"integer"},"start_x":{"type":"integer"},"start_y":{"type":"integer"},"end_x":{"type":"integer"},"end_y":{"type":"integer"},"duration_ms":{"type":"integer"}},"required":["op"]}}}
]
"""

object ToolRegistry {

    @Volatile
    private var cachedHost: AndroidToolHost? = null

    @Volatile
    private var cachedHostKey: String = ""

    // MCP 工具列表每次从 McpManager.allTools() 重建，displayName / findToolNameByDisplay
    // 是高频调用路径。加 2 秒 TTL 缓存。
    @Volatile private var cachedMcpForward: Map<String, String> = emptyMap()
    @Volatile private var cachedMcpReverse: Map<String, String> = emptyMap()
    @Volatile private var cachedMcpAt: Long = 0L
    private const val MCP_DISPLAY_CACHE_MS = 2000L

    private fun refreshMcpCacheIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - cachedMcpAt < MCP_DISPLAY_CACHE_MS) return
        val list = McpTools.getTools()
        cachedMcpForward = list.associate { it.name to it.displayName }
        cachedMcpReverse = list.associate { it.displayName to it.name }
        cachedMcpAt = now
    }

    private fun getHost(context: Context, settings: AppSettings): AndroidToolHost {
        val key = settings.allowedPaths.joinToString("|") + "::" +
                settings.blockedPaths.joinToString("|")
        val existing = cachedHost
        if (existing != null && cachedHostKey == key) return existing
        val host = AndroidToolHost(
            context.applicationContext,
            settings.allowedPaths,
            settings.blockedPaths
        )
        cachedHost = host
        cachedHostKey = key
        return host
    }

    // 懒加载解析，失败时退化为空列表并打日志。
    private val privateToolsJson: JSONArray by lazy {
        try {
            JSONArray(RAW_PRIVATE_TOOLS_JSON.trimIndent())
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "私有工具 JSON 解析失败，全部私有工具将不可用：${e.javaClass.simpleName}: ${e.message}"
            )
            JSONArray()
        }
    }

    private val allToolsJson: JSONArray by lazy {
        val merged = JSONArray()

        CommonToolSchemas.SPECS.forEach { spec ->
            merged.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", spec.name)
                    put("description", spec.description)
                    put("parameters", JSONObject(spec.paramsSchema))
                })
            })
        }

        for (i in 0 until privateToolsJson.length()) {
            merged.put(privateToolsJson.getJSONObject(i))
        }

        merged
    }

    // ============================================================
    // 【P0】分层工具系统
    // ============================================================

    /**
     * 把当前所有可用工具（common + private + mcp）转成 CatalogTool 列表。
     *
     * 用于构建目录文本和搜索。不含过滤（disabled / vision），
     * 因为不同调用场景过滤条件不同。
     */
    private fun getCatalogTools(): List<CatalogTool> {
        val out = mutableListOf<CatalogTool>()

        // 内置（common + private）
        for (i in 0 until allToolsJson.length()) {
            val t = allToolsJson.optJSONObject(i) ?: continue
            val fn = t.optJSONObject("function") ?: continue
            val name = fn.optString("name", "")
            if (name.isBlank()) continue
            out.add(
                CatalogTool(
                    name = name,
                    displayName = displayName(name),
                    description = fn.optString("description", ""),
                    paramsSchema = fn.optJSONObject("parameters")?.toString() ?: "{}"
                )
            )
        }

        // MCP 动态
        McpTools.getTools().forEach { mt ->
            out.add(
                CatalogTool(
                    name = mt.name,
                    displayName = mt.displayName,
                    description = mt.description,
                    paramsSchema = mt.paramsSchema
                )
            )
        }

        return out
    }

    /**
     * 【P0】构建 tool_search 工具的 schema。
     *
     * description 里内嵌"工具目录"，让 AI 一眼看到有哪些工具可以用，
     * 不用额外请求。
     */
    private fun buildToolSearchSpec(settings: AppSettings): JSONObject {
        val catalog = getCatalogTools()
        val profile = settings.currentProfile()
        val visionEnabled = profile?.supportsVision ?: true

        val catalogText = ToolCatalog.buildCatalogText(
            tools = catalog,
            disabled = settings.disabledTools,
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

        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "tool_search")
                put("description", desc)
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "string")
                            put("description", "自然语言描述你需要做什么（如「读取文件」「压缩目录」「屏幕控制」）")
                        })
                        put("category", JSONObject().apply {
                            put("type", "string")
                            put("description", "可选，按分类过滤。取值：file / system / media / network / screen / remote / mcp / skill / other")
                        })
                    })
                    put("required", JSONArray().apply { put("query") })
                })
            })
        }
    }

    private fun buildToolLoadSpec(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "tool_load")
                put("description", "直接加载指定工具的完整定义。如果你已经从 tool_search 的目录里看到工具名，用这个。")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("name", JSONObject().apply {
                            put("type", "string")
                            put("description", "工具名")
                        })
                    })
                    put("required", JSONArray().apply { put("name") })
                })
            })
        }
    }

    /**
     * 【P0 向后兼容】只返回核心工具 + 元工具。
     *
     * 老调用方（如果有）用这个，不包含 tool_search 加载的工具。
     * 新调用方用 getToolsWithLoaded。
     */
    fun getTools(settings: AppSettings): JSONArray = getToolsWithLoaded(settings, emptySet())

    /**
     * 【P0 新增】核心工具 + 元工具 + 已加载工具的完整 schema。
     *
     * Agent 循环里每轮调用，传 loadedToolNames 让上一轮通过 tool_search /
     * tool_load 加载过的工具出现在 tools 数组里，AI 才能调用它们。
     */
    fun getToolsWithLoaded(settings: AppSettings, loadedNames: Set<String>): JSONArray {
        val disabled = settings.disabledTools
        val profile = settings.currentProfile()
        val visionEnabled = profile?.supportsVision ?: true

        val merged = JSONArray()

        // Layer 1：核心工具
        for (i in 0 until allToolsJson.length()) {
            val t = allToolsJson.optJSONObject(i) ?: continue
            val name = t.optJSONObject("function")?.optString("name", "") ?: continue
            if (name !in ToolCategory.CORE_TOOL_NAMES) continue
            if (name in disabled) continue
            if (!visionEnabled && name in VISION_TOOLS) continue
            merged.put(t)
        }

        // Layer 3：已通过 tool_search / tool_load 加载的工具
        if (loadedNames.isNotEmpty()) {
            for (i in 0 until allToolsJson.length()) {
                val t = allToolsJson.optJSONObject(i) ?: continue
                val name = t.optJSONObject("function")?.optString("name", "") ?: continue
                if (name in ToolCategory.CORE_TOOL_NAMES) continue  // 已加
                if (name !in loadedNames) continue
                if (name in disabled) continue
                if (!visionEnabled && name in VISION_TOOLS) continue
                merged.put(t)
            }
            // MCP 里已加载的
            McpTools.getTools().forEach { mt ->
                if (mt.name !in loadedNames) return@forEach
                if (mt.name in disabled) return@forEach
                merged.put(JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", mt.name)
                        put("description", mt.description)
                        put("parameters", JSONObject(mt.paramsSchema))
                    })
                })
            }
        }

        // Layer 2 入口：元工具（始终发送）
        merged.put(buildToolSearchSpec(settings))
        merged.put(buildToolLoadSpec())

        return merged
    }

    /**
     * 【P0 新增】供 Agent 循环调用：搜索工具，返回 AI 易读的文本。
     */
    fun searchToolsForAgent(query: String, category: String, settings: AppSettings): String {
        val catalog = getCatalogTools()
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
     * 【P0 新增】供 Agent 循环调用：加载单个工具的详情。
     */
    fun loadToolForAgent(name: String, settings: AppSettings): String {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return "失败：未提供工具名"

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

    /**
     * 【P0 新增】按名查找 ToolSpec。
     *
     * 只在工具已经在 tools 数组里时使用（用于 checkVision 等），
     * 不涉及分层。
     */
    fun findToolSpec(name: String): ToolSpec? {
        CommonToolSchemas.NAME_TO_SPEC[name]?.let { return it }
        return null
    }

    // ============================================================
    // displayName / allToolNames / findToolNameByDisplay
    // ============================================================

    fun displayName(name: String): String {
        CommonToolSchemas.DISPLAY_NAMES[name]?.let { return it }
        PRIVATE_TOOL_DISPLAY_NAMES[name]?.let { return it }
        if (name.startsWith("mcp__")) {
            refreshMcpCacheIfNeeded()
            cachedMcpForward[name]?.let { return it }
        }
        return name
    }

    fun allToolNames(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until allToolsJson.length()) {
            val name = allToolsJson.optJSONObject(i)
                ?.optJSONObject("function")?.optString("name", "") ?: continue
            if (name.isBlank()) continue
            out.add(name to displayName(name))
        }
        McpTools.getTools().forEach { mt ->
            out.add(mt.name to mt.displayName)
        }
        return out
    }

    fun findToolNameByDisplay(display: String): String? {
        CommonToolSchemas.DISPLAY_NAMES.forEach { (name, dn) ->
            if (dn == display) return name
        }
        PRIVATE_TOOL_DISPLAY_NAMES.forEach { (name, dn) ->
            if (dn == display) return name
        }
        if (display.startsWith("[MCP:")) {
            refreshMcpCacheIfNeeded()
            cachedMcpReverse[display]?.let { return it }
        }
        return null
    }

    // ============================================================
    // 日志参数清理
    // ============================================================

    private fun sanitizeArgsForLog(name: String, args: JSONObject): String {
        return try {
            val copy = JSONObject(args.toString())
            when (name) {
                "base64" -> {
                    val c = copy.optString("content", "")
                    if (c.length > 40) copy.put("content", c.take(40) + "...(len=${c.length})")
                }
                "execute_shell" -> {
                    val c = copy.optString("command", "")
                    if (c.length > 200) copy.put("command", c.take(200) + "...")
                }
                "schedule" -> {
                    val c = copy.optString("command", "")
                    if (c.length > 200) copy.put("command", c.take(200) + "...")
                }
                "ui" -> {
                    val t = copy.optString("text", "")
                    if (t.length > 200) copy.put("text", t.take(200) + "...")
                }
            }
            copy.toString(2)
        } catch (e: Exception) {
            AgentLogger.log(
                "TOOL",
                "参数日志清理失败（$name）：${e.javaClass.simpleName}: ${e.message}"
            )
            args.toString(2)
        }
    }

    // ============================================================
    // write_file 确认
    // ============================================================

    private suspend fun confirmThenWrite(
        args: JSONObject,
        host: AndroidToolHost,
        settings: AppSettings
    ): String {
        val pathStr = args.optString("path", "").trim()
        if (pathStr.isBlank()) return "失败：未提供 path"

        val resolved = host.resolvePath(pathStr)
            ?: return "失败：路径不允许访问：$pathStr"

        val mode = args.optString("mode", "text")
        val content = args.optString("content", "")
        val data = args.optString("data", "")
        val append = args.optBoolean("append", false)

        if (settings.requireConfirmWrite) {
            val preview = when (mode) {
                "base64" -> "（base64 数据 ${data.length} 字符）"
                else -> if (content.length > 200) content.take(200) + "..." else content
            }
            val op = if (append) "追加" else "写入"
            val approved = ConfirmationBroker.request(
                "写入文件确认",
                "AI 请求$op：\n$resolved\n\n内容预览：\n$preview\n\n是否允许？"
            )
            if (!approved) return "用户已拒绝写入操作，文件未改动"
        }

        val r = CommonTools.execute("write_file", args, host)
            ?: return "失败：内部错误，CommonTools 未处理 write_file"
        return if (r.success) r.output else "失败：${r.output}"
    }

    // ============================================================
    // 工具执行
    // ============================================================

    suspend fun execute(
        name: String, args: JSONObject, context: Context, settings: AppSettings
    ): String {
        if (name in settings.disabledTools) {
            return "工具 $name 已被用户在设置中关闭"
        }

        if (name in VISION_TOOLS) {
            val profile = settings.currentProfile()
            if (profile != null && !profile.supportsVision) {
                return "失败：当前模型 [${profile.name}] 不支持图片输入，" +
                        "「${displayName(name)}」已禁用。请到设置里勾选该模型的「支持图片输入」，" +
                        "或换一个支持多模态的模型。"
            }
        }

        // 【P0】元工具拦截（必须在其他分发之前）
        if (name == "tool_search") {
            val query = args.optString("query", "").trim()
            if (query.isBlank()) return "失败：tool_search 需要 query 参数"
            val category = args.optString("category", "").trim()
            AgentLogger.log("TOOL", "tool_search query=$query category=$category")
            return searchToolsForAgent(query, category, settings)
        }
        if (name == "tool_load") {
            val toolName = args.optString("name", "").trim()
            if (toolName.isBlank()) return "失败：tool_load 需要 name 参数"
            AgentLogger.log("TOOL", "tool_load name=$toolName")
            return loadToolForAgent(toolName, settings)
        }

        AgentLogger.log("TOOL", "调用工具 $name\n参数:\n${sanitizeArgsForLog(name, args)}")

        val result = try {
            val mcpResult = McpTools.execute(name, args)
            if (mcpResult != null) {
                // 防止 output 已经以"失败"开头时再补前缀，导致 "失败：失败：xxx"。
                when {
                    mcpResult.success -> mcpResult.output
                    mcpResult.output.startsWith("失败") -> mcpResult.output
                    else -> "失败：${mcpResult.output}"
                }
            } else {
                val host = getHost(context, settings)

                if (name == "write_file") {
                    confirmThenWrite(args, host, settings)
                } else {
                    val commonResult = CommonTools.execute(name, args, host)

                    if (commonResult != null) {
                        if (commonResult.success) commonResult.output
                        else "失败：${commonResult.output}"
                    } else {
                        when (name) {
                            in SystemTools.TOOLS -> SystemTools.execute(name, args, context, settings)
                            in FileTools.TOOLS -> FileTools.execute(name, args, context, settings)
                            in MediaTools.TOOLS -> MediaTools.execute(name, args, context, settings)
                            in RemoteTools.TOOLS -> RemoteTools.execute(name, args, context, settings)
                            in ScreenTools.TOOLS -> ScreenTools.execute(name, args, context, settings)
                            in ImageGenTools.TOOLS -> ImageGenTools.execute(name, args, context, settings)
                            else -> "未知工具：$name"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "工具 $name 执行异常：${e.javaClass.simpleName}: ${e.message}"
            )
            "工具执行异常：${e.message ?: "未知错误"}"
        }

        AgentLogger.log("RESULT", "工具 $name 返回:\n$result")
        return result
    }
}

// 【2026-10-15 去重】实现已抽到 shared PathAlias，且修复"别名/子路径"不被展开的问题
internal fun normalizeAlias(path: String): String =
    net.xinkong.furagent.shared.util.PathAlias.expandAndroid(path, SDCARD_PATH)