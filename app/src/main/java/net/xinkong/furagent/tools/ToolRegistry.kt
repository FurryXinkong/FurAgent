package net.xinkong.furagent.tools

import android.content.Context
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.core.ConfirmationBroker
import net.xinkong.furagent.core.runAgentTurn
import net.xinkong.furagent.data.AppSettings
import net.xinkong.furagent.data.HistoryStore
import net.xinkong.furagent.data.ChatMessage
import net.xinkong.furagent.data.Session
import net.xinkong.furagent.data.SessionStore
import net.xinkong.furagent.data.currentProfile
import net.xinkong.furagent.shared.tools.*
import kotlinx.coroutines.launch
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
  {"type":"function","function":{"name":"schedule","description":"定时任务（秒级轮询 / cron / 条件触发）。op=create 创建任务（action 支持 open_app / run_command / notify / run_workflow / run_tool / run_agent，可带 repeat_mode / interval_seconds / cron_expression / conditions；run_agent 让 AI 定时主动说话，需要 prompt，可带 session_id 写回会话；run_tool 需要 tool_name）；op=list 列出所有任务；op=cancel 按 task_id 或 keyword 取消。所有时间/周期/条件触发需求优先使用。","parameters":{"type":"object","properties":{"op":{"type":"string","enum":["create","list","cancel"]},"action":{"type":"string"},"description":{"type":"string"},"package_name":{"type":"string"},"app_name":{"type":"string"},"command":{"type":"string"},"tool_name":{"type":"string"},"tool_args":{"type":"string"},"prompt":{"type":"string"},"session_id":{"type":"string"},"notification_title":{"type":"string"},"notification_content":{"type":"string"},"workflow_name":{"type":"string"},"trigger_time":{"type":"string"},"delay_seconds":{"type":"integer"},"repeat_mode":{"type":"string"},"interval_seconds":{"type":"integer"},"cron_expression":{"type":"string"},"repeat_days":{"type":"string"},"conditions":{"type":"array","items":{"type":"object","properties":{"type":{"type":"string"},"operator":{"type":"string"},"value":{"type":"string"},"value2":{"type":"string"}}}},"condition_mode":{"type":"string"},"skip_if_not_met":{"type":"boolean"},"task_id":{"type":"string"},"keyword":{"type":"string"}},"required":["op"]}}},
  {"type":"function","function":{"name":"workflow","description":"工作流管理。op=create 创建或更新工作流（需要 name 和 steps_json）；op=delete 按名称删除；op=list 列出所有工作流；op=run 执行指定工作流（需要 name，可选 params）。","parameters":{"type":"object","properties":{"op":{"type":"string","enum":["create","delete","list","run"]},"name":{"type":"string"},"description":{"type":"string"},"params":{"type":"object"},"steps_json":{"type":"string"}},"required":["op"]}}},
  {"type":"function","function":{"name":"memory","description":"长期记忆管理。op=save 保存一条记忆（需要 content）；op=recall 按 query 检索（query 为空则列出全部）；op=list 列出所有记忆（带 ID）；op=delete 按 memory_id 或 keyword 删除。当用户透露姓名、职业、偏好、习惯、忌讳或明确纠正你的事实时主动保存，不要等用户说「记住」。","parameters":{"type":"object","properties":{"op":{"type":"string","enum":["save","recall","list","delete"]},"content":{"type":"string"},"query":{"type":"string"},"memory_id":{"type":"string"},"keyword":{"type":"string"}},"required":["op"]}}},
  {"type":"function","function":{"name":"web","description":"联网工具。op=search 搜索（最多 8 条结果，可 page 翻页；可选 engine=bing/baidu/360 指定搜索源，不填按 360→百度→必应 顺序自动回退）；op=fetch 抓取指定 URL 的网页正文（可 max_length 控制长度，默认 8000，支持传 selector 用 CSS 选择器精准提取网页节点）。用户提供 URL 且要求总结或查看内容时，直接用 fetch 抓正文，不要只依赖搜索摘要。","parameters":{"type":"object","properties":{"op":{"type":"string","enum":["search","fetch"]},"query":{"type":"string"},"page":{"type":"integer"},"engine":{"type":"string"},"url":{"type":"string"},"max_length":{"type":"integer"},"selector":{"type":"string"}},"required":["op"]}}},
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

    // 【批次 11 性能】buildToolSearchSpec 缓存。
    // 每轮 Agent 循环都调，每次都走 getCatalogTools + 拼字符串（N 个 CatalogTool 构造 + 几百字符拼接）。
    // 一轮用 5 次工具 = 重建 5 次。缓存后 5 秒内只重建 1 次。
    // 键 = disabled hash + visionEnabled；MCP/skill 工具变化靠 5 秒窗口兜底。
    @Volatile private var cachedSearchSpec: JSONObject? = null
    @Volatile private var cachedSearchSpecKey: String = ""
    @Volatile private var cachedSearchSpecAt: Long = 0L
    private const val SEARCH_SPEC_CACHE_MS = 5000L

    private fun refreshMcpCacheIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - cachedMcpAt < MCP_DISPLAY_CACHE_MS) return
        val list = McpTools.getTools()
        cachedMcpForward = list.associate { it.name to it.displayName }
        cachedMcpReverse = list.associate { it.displayName to it.name }
        cachedMcpAt = now
    }

    // 【批次 18】O(1) 路由表，消灭层层 if-else 判断
    private val PRIVATE_ROUTER: Map<String, (JSONObject, Context, AppSettings) -> String> by lazy {
        val map = mutableMapOf<String, (JSONObject, Context, AppSettings) -> String>()
        SystemTools.TOOLS.forEach { map[it.name] = { a, c, s -> SystemTools.execute(it.name, a, c, s) } }
        FileTools.TOOLS.forEach { map[it.name] = { a, c, s -> FileTools.execute(it.name, a, c, s) } }
        MediaTools.TOOLS.forEach { map[it.name] = { a, c, s -> MediaTools.execute(it.name, a, c, s) } }
        RemoteTools.TOOLS.forEach { map[it.name] = { a, c, s -> RemoteTools.execute(it.name, a, c, s) } }
        ScreenTools.TOOLS.forEach { map[it.name] = { a, c, s -> ScreenTools.execute(it.name, a, c, s) } }
        ImageGenTools.TOOLS.forEach { map[it.name] = { a, c, s -> ImageGenTools.execute(it.name, a, c, s) } }
        map
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

    // 分层工具系统

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

        // 【自定义脚本】pytool 常驻 + 每个脚本 pytool_<name> 动态工具
        out.add(
            CatalogTool(
                name = "pytool",
                displayName = "自定义脚本",
                description = net.xinkong.furagent.shared.skill.SkillTools.TOOLS.first().description,
                paramsSchema = net.xinkong.furagent.shared.skill.SkillTools.TOOLS.first().paramsSchema
            )
        )
        // 【临时执行 Python】与桌面端 python 工具对齐
        out.add(
            CatalogTool(
                name = "python",
                displayName = "执行 Python 代码",
                description = "临时执行一段 Python 代码（不落盘）。适合一次性计算、验证逻辑、处理数据。" +
                        "用 print() 输出结果；最后一行若是 {\"ok\":true,\"result\":\"...\"} 会优先取 result。" +
                        "Chaquopy 内嵌 CPython 3.12，只有标准库。",
                paramsSchema = """{"type":"object","properties":{"code":{"type":"string"},"args":{"type":"object"},"work_dir":{"type":"string"},"timeout":{"type":"integer"}},"required":["code"]}"""
            )
        )
        net.xinkong.furagent.shared.skill.SkillTools.dynamicTools().forEach { st ->
            out.add(
                CatalogTool(
                    name = st.name,
                    displayName = st.displayName,
                    description = st.description,
                    paramsSchema = st.paramsSchema
                )
            )
        }

        return out
    }

    /**
     * 构建 tool_search 工具的 schema。
     *
     * description 里内嵌"工具目录"，让 AI 一眼看到有哪些工具可以用，
     * 不用额外请求。
     */
    private fun buildToolSearchSpec(settings: AppSettings): JSONObject {
        val profile = settings.currentProfile()
        val visionEnabled = profile?.supportsVision ?: true
        // 【批次 11】5 秒缓存：每轮 Agent 循环都调，重建成本 O(N 工具数)。
        val cacheKey = "${settings.disabledTools.hashCode()}_$visionEnabled"
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

        val spec = JSONObject().apply {
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
        // 【批次 11】写入缓存
        cachedSearchSpec = spec
        cachedSearchSpecKey = cacheKey
        cachedSearchSpecAt = now
        return spec
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

        // 【自定义脚本】pytool 常驻 + 每个脚本 pytool_<name> 始终加入，
        // 让 AI 一眼看到有哪些脚本可用（与桌面端一致）。
        if ("pytool" !in disabled) {
            val pt = net.xinkong.furagent.shared.skill.SkillTools.TOOLS.first()
            merged.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", pt.name)
                    put("description", pt.description)
                    put("parameters", JSONObject(pt.paramsSchema))
                })
            })
        }
        // 【临时执行 Python】与桌面端 python 工具对齐：
        // 不落盘，直接跑一段代码。适合“帮我算一下”“验证这段逻辑”。
        if ("python" !in disabled) {
            merged.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "python")
                    put(
                        "description",
                        "临时执行一段 Python 代码（不落盘，用完即弃）。" +
                                "适合一次性计算、验证逻辑、处理数据。" +
                                "\n\n【与 pytool 的区别】pytool 执行 scripts/ 下的持久化脚本；" +
                                "python 直接跑你传入的代码字符串，不会在磁盘上留文件。" +
                                "\n\n【输出】用 print() 输出结果，会原样返回给用户。" +
                                "若最后一行是 JSON {\"ok\":true,\"result\":\"...\"}，会优先取 result 字段。" +
                                "\n\n【环境】Chaquopy 内嵌 CPython 3.12。" +
                                "【预置库】除了标准库，还内置了：numpy(计算), pillow(图像), beautifulsoup4(HTML解析), pyyaml(YAML解析)。" +
                                "【网络请求】不要用 requests（已被移除），如果需要写复杂的 Python 网络脚本，请用标准库 urllib.request，或者直接使用内置的 http_request 工具。" +
                                "\n\n【注意】1. 不要写死循环；2. 不要用 input() 交互；" +
                                "3. 【绝对不要】在代码里尝试 os.system('pip install xxx') 或 subprocess 安装其他库，手机端无法动态安装。"
                    )
                    put(
                        "parameters",
                        JSONObject(
                            """{"type":"object","properties":{"code":{"type":"string","description":"要执行的 Python 源码"},"args":{"type":"object","description":"可选，会以一行 JSON 写入 stdin，代码里用 sys.stdin.readline() 读"},"work_dir":{"type":"string","description":"可选，工作目录（open() 相对路径的基准）"},"timeout":{"type":"integer","description":"超时秒数，默认 60"}},"required":["code"]}"""
                        )
                    )
                })
            })
        }
        // 【修改历史】与桌面端同能力（list / diff / restore，restore 带确认弹窗）
        if ("history" !in disabled) {
            HistoryTools.TOOLS.forEach { ht ->
                merged.put(JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", ht.name)
                        put("description", ht.description)
                        put("parameters", JSONObject(ht.paramsSchema))
                    })
                })
            }
        }
        net.xinkong.furagent.shared.skill.SkillTools.dynamicTools().forEach { st ->
            if (st.name in disabled) return@forEach
            merged.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", st.name)
                    put("description", st.description)
                    put("parameters", JSONObject(st.paramsSchema))
                })
            })
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

    // displayName / allToolNames / findToolNameByDisplay

    fun displayName(name: String): String {
        CommonToolSchemas.DISPLAY_NAMES[name]?.let { return it }
        PRIVATE_TOOL_DISPLAY_NAMES[name]?.let { return it }
        if (name == "pytool") return "自定义脚本"
        if (name == "python") return "执行 Python 代码"
        if (name == "history") return "修改历史"
        if (net.xinkong.furagent.shared.skill.SkillTools.isDynamicTool(name)) {
            return "脚本：${name.removePrefix(net.xinkong.furagent.shared.skill.SkillStore.TOOL_PREFIX)}"
        }
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

    // 日志参数清理

    private fun sanitizeArgsForLog(name: String, args: JSONObject): String {
        // 【批次 11 性能】原实现每次都 `JSONObject(args.toString())`：
        //   - args.toString() 分配一份完整 JSON 字符串（大参数是 MB 级）
        //   - JSONObject(...) 再解析一遍
        // 一次工具调用两次大字符串分配。改为按 key 逐字段复制：
        //   - 不需要截断的字段直接沿用原引用（零拷贝）
        //   - 需要截断的字段只复制截断后的短字符串
        return try {
            val truncMap: Map<String, Int> = when (name) {
                "base64" -> mapOf("content" to 40)
                "execute_shell", "schedule" -> mapOf("command" to 200)
                "ui" -> mapOf("text" to 200)
                else -> emptyMap()
            }

            if (truncMap.isEmpty()) {
                return args.toString(2)
            }

            // 判断是否有字段真的超限，全都没超就零拷贝直接返回
            var needsTruncate = false
            for ((field, limit) in truncMap) {
                val v = args.optString(field, "")
                if (v.length > limit) { needsTruncate = true; break }
            }
            if (!needsTruncate) {
                return args.toString(2)
            }

            // 有超限：逐字段重建，只对超限字段截断，其余字段复用引用
            val copy = JSONObject()
            args.keys().forEach { k ->
                val limit = truncMap[k]
                if (limit != null) {
                    val s = args.optString(k, "")
                    if (s.length > limit) {
                        copy.put(k, s.take(limit) + "...(len=${s.length})")
                    } else {
                        copy.put(k, s)
                    }
                } else {
                    copy.put(k, args.opt(k))
                }
            }
            copy.toString(2)
        } catch (e: Exception) {
            AgentLogger.log(
                "TOOL",
                "参数日志清理失败（$name）：${e.javaClass.simpleName}: ${e.message}"
            )
            try {
                val raw = args.toString(2)
                if (raw.length > 5000) raw.take(5000) + "...(已截断)" else raw
            } catch (_: Exception) {
                "(无法序列化)"
            }
        }
    }

    // write_file 确认

    private suspend fun confirmThenWrite(
        args: JSONObject,
        host: AndroidToolHost,
        settings: AppSettings,
        context: Context
    ): String {
        val pathStr = args.optString("path", "").trim()
        if (pathStr.isBlank()) return "失败：未提供 path"

        val resolved = host.resolvePath(pathStr)
            ?: return "失败：路径不允许访问：$pathStr"

        val mode = args.optString("mode", "text")
        val content = args.optString("content", "")
        val data = args.optString("data", "")
        val append = args.optBoolean("append", false)

        // 【批次 19】子会话不需要确认写入（通过 CoroutineName 识别）
        val isSubSession = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.CoroutineName]?.name == ConfirmationBroker.SUBSESSION_COROUTINE_NAME
        
        if (settings.requireConfirmWrite && !isSubSession) {
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

        // 写前备份：覆盖已有文件时把原内容存进备份目录（设置里可关），
        // 「修改历史 + 一键回档」的数据来源。备份失败不阻断写入。
        if (settings.fileBackupEnabled) {
            val f = java.io.File(resolved)
            if (f.exists()) {
                val backupDir = BackupHelper.backupDirFor(host.trashDir())
                if (backupDir != null) {
                    val oldBytes = try { f.readBytes() } catch (_: Exception) { null }
                    if (oldBytes != null) {
                        HistoryStore.getInstance(context).recordEdit(
                            backupDir, resolved, if (append) "append" else "write",
                            oldBytes, null, if (append) "append" else "write",
                            sessionId = currentSessionId()
                        )
                    }
                }
            }
        }

        val r = CommonTools.execute("write_file", args, host)
            ?: return "失败：内部错误，CommonTools 未处理 write_file"
        return if (r.success) r.output else "失败：${r.output}"
    }

    // 工具执行

    // 子会话执行进度（聊天页顶部功能区实时展示；按 subId 区分并行子会话，避免互踩）
    object SubSessionProgress {
        data class Entry(
            val title: String = "",
            val steps: Int = 0,
            val lastTool: String = "",
            /** 当前阶段：思考中 / 推理中 / 生成中 */
            val phase: String = "",
            /** 最新一行内容，让用户看到它在干什么 */
            val snippet: String = "",
            val startedAt: Long = 0L
        )

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

        /** 【可见性】子代理正在干什么 —— 阶段 + 最新一行内容 */
        @Synchronized fun updatePhase(id: String, phase: String, snippet: String) {
            val e = entries[id] ?: return
            if (e.phase == phase && e.snippet == snippet) return
            entries[id] = e.copy(phase = phase, snippet = snippet)
            refresh()
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

        @Synchronized fun finish(id: String) {
            entries.remove(id)
            refresh()
        }

        @Synchronized fun snapshot(): Map<String, Entry> = LinkedHashMap(entries)

        @Synchronized fun emitCompleted(c: Completed) {
            _events.tryEmit(c)
        }
    }

    // 【批次 19 隔离修复】
    // 废弃全局 CurrentTaskSession.id，改用协程上下文传递。
    // 全局静态变量在并发的子会话中会互相覆盖，导致写文件等历史归属错乱。
    class SessionIdElement(val id: String) : kotlin.coroutines.AbstractCoroutineContextElement(SessionIdElement) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<SessionIdElement>
    }

    suspend fun currentSessionId(): String =
        kotlinx.coroutines.currentCoroutineContext()[SessionIdElement]?.id ?: ""

    // 仅供 UI 层查询当前显示会话，Agent 逻辑不再读写此字段
    object CurrentTaskSession {
        @Volatile var id: String = ""
    }

    // 并行子会话计数（原子操作，杜绝同毫秒并发绕过检查）
    private val subSessionActive = java.util.concurrent.atomic.AtomicInteger(0)
    // 【P1-10】从 shared 统一常量，双端一致
    private const val MAX_PARALLEL_SUB = net.xinkong.furagent.shared.data.SubSessionLimits.MAX_PARALLEL_SUB

    /**
     * 【子会话硬上限】子代理最多跑多少轮。
     * 不继承用户把主会话调到的高轮数，否则几个并行子代理能烧掉整月额度。
     * 【P1-10】从 shared 统一常量，双端一致。
     */
    private const val SUB_MAX_LOOPS = net.xinkong.furagent.shared.data.SubSessionLimits.SUB_MAX_LOOPS

    /**
     * 【截断补总结】子会话被强制截断后，让它自己回头总结一次。
     *
     * 只跑 1 轮：再多就是又一次全量上下文请求，
     * 子代理这时候还在回忆上一轮的工具输出，烧钱而不产出。
     *
     * 什么时候不补：已经有一段正常的 assistant 文字，
     * 说明它自己已经总结过了，再问一次是浪费。
     */
    private suspend fun summarizeIfTruncated(
        history: List<ChatMessage>,
        profile: net.xinkong.furagent.data.ModelProfile,
        context: Context,
        settings: AppSettings,
    ): List<ChatMessage> {
        val lastText = history.lastOrNull { it.type == "text" && it.role == "assistant" }
            ?.content?.trim().orEmpty()
        val truncated = lastText.isBlank() ||
                lastText.contains("已达到最大工具调用轮数")
        if (!truncated) return history

        val nudge = ChatMessage(
            role = "user",
            content = "【系统指令】本次任务已被强制中断（轮数上限 / 任务被停止）。\n" +
                    "请立即用简洁的文字回答整理，不要再调用任何工具：\n" +
                    "1) 你已经完成了什么（具体改了哪些文件 / 得到了什么结论）\n" +
                    "2) 当前状态与卡点\n" +
                    "3) 下一步建议。\n" +
                    "回答直接给结论，不要客套。",
            timestamp = System.currentTimeMillis()
        )

        var out = history
        return try {
            AgentLogger.log("TOOL", "子会话被截断，补一轮总结（msgs=${history.size}）")
            runAgentTurn(
                profile = profile,
                history = history + nudge,
                maxTurns = 1,
                context = context,
                settings = settings,
                onHistoryChanged = { h -> out = h },
                onThinking = {},
                onReasoning = {},
                onStreamChunk = {},
                onStreamCommit = {},
                onFinish = {}
            )
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {}
            out
        } catch (_: Throwable) {
            history
        }
    }

    /** 子会话收尾摘要：必须能看出它干了什么、停在哪。 */
    private fun buildSubSummary(h: List<ChatMessage>, hitLimit: Boolean): String {
        val toolCalls = h.filter { it.type == "tool_call" }
        val lastText = h.lastOrNull { it.type == "text" && it.role == "assistant" }
            ?.content?.trim().orEmpty()
        val errs = h.count { it.type == "tool_result" && it.content.startsWith("失败") }
        val sb = StringBuilder()
        if (hitLimit) {
            sb.append("【未完成】子会话达到轮数上限（$SUB_MAX_LOOPS）被截断，下面是已经做到的部分。\n")
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
     * 【AI 主动终止】正在执行的子会话 Job 表（subId → Job）。
     * sub_session(op=stop) 据此 cancel 对应协程。
     * 子会话结束时自行移除，避免泄漏。
     */
    private val subSessionJobs =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    // 子会话后台执行作用域（独立于主任务，互不阻塞、互不误杀）
    // 【P0-5】打上 CoroutineName 标记：ConfirmationBroker 据此识别"子代理"请求，
    // 在弹窗标题前加"子代理 ·"前缀，用户能知道是谁在请求。
    private val subSessionScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO +
                kotlinx.coroutines.CoroutineName(
                    net.xinkong.furagent.core.ConfirmationBroker.SUBSESSION_COROUTINE_NAME
                )
    )

    /**
     * 子代理：派生一个不携带历史的独立子会话执行 task，跑完存盘并返回结果摘要。
     * tool_result 首行约定 "SUB_SESSION:{subId}"，聊天页据此渲染子会话卡片。
     */
    private suspend fun runSubSession(
        args: JSONObject, context: Context, settings: AppSettings
    ): String {
        val task = args.optString("task", "").trim()
        if (task.isBlank()) return "失败：run_sub_session 需要 task 参数"
        val title = args.optString("title", "").trim().ifBlank { task.take(30) }
        val profile = settings.currentProfile()
            ?: return "失败：未配置模型，无法启动子会话"

        // 并行槽位：原子检查+占位，杜绝同毫秒并发绕过检查
        if (subSessionActive.incrementAndGet() > MAX_PARALLEL_SUB) {
            subSessionActive.decrementAndGet()
            return "失败：并行子会话已达上限（$MAX_PARALLEL_SUB 个），请等待完成后再派发"
        }

        // ID：毫秒时间戳 + 4 位随机 hex，并发派发不撞
        val rndHex = java.lang.String.format(
            "%04x",
            java.util.concurrent.ThreadLocalRandom.current().nextInt(65536)
        )
        val subId = "sub_${System.currentTimeMillis()}_$rndHex"
        val now = System.currentTimeMillis()
        // 【批次 19】从协程上下文读取母会话 ID
        val parentSessionId = currentSessionId().ifBlank { null }
        // 全新上下文：只有这条 task，不带父会话历史
        val initial = listOf(
            ChatMessage(role = "user", content = task, type = "text", timestamp = now)
        )

        // 启动即存盘：子会话立刻出现在「子代理」列表，可随时点入
        try {
            SessionStore.save(
                context,
                Session(
                    id = subId,
                    title = "↳ $title",
                    createdAt = now,
                    updatedAt = now,
                    parentId = parentSessionId,
                    messages = initial
                )
            )
        } catch (e: Exception) {
            AgentLogger.log("ERR", "子会话初始存盘失败：${e.message}")
        }

        // 后台异步执行：主对话立即返回、互不阻塞；完成后覆盖保存全量过程
        SubSessionProgress.start(subId, title)
        val job = subSessionScope.launch {
            // 【批次 19 隔离修复】用协程上下文标记子会话 ID，防止并发串台
            kotlinx.coroutines.withContext(SessionIdElement(subId)) {
                var subHistory: List<ChatMessage> = initial
                // 【硬上限】不继承用户的高轮数设置
                val subLoops = minOf(settings.maxToolCallLoops, SUB_MAX_LOOPS)
                val streamBuf = StringBuilder()
                try {
                    runAgentTurn(
                        profile = profile,
                        history = initial,
                        maxTurns = subLoops,
                        context = context,
                        settings = settings,
                        onHistoryChanged = { h ->
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
                        onStreamChunk = { c ->
                            streamBuf.append(c)
                            SubSessionProgress.updatePhase(
                                subId, "生成中",
                                streamBuf.toString().takeLast(400).replace("\n", " ").trim().takeLast(120)
                            )
                        },
                        onStreamCommit = { streamBuf.setLength(0) },
                        onFinish = {}
                    )
                    // 回调经 mainHandler.post 派发，排空主线程队列确保 subHistory 已最新
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {}
                    // 【截断补总结】跑到轮数上限也得给个交代，
                    // 不能只剩一句「已达到最大工具调用轮数」
                    subHistory = summarizeIfTruncated(subHistory, profile, context, settings)
                } catch (e: Exception) {
                    AgentLogger.log("ERR", "子会话执行异常：${e.message}")
                    // 异常 / 被主 AI 主动中断（CancellationException）：
                    // 同样要求它把现有结果总结完再存盘。
                    // NonCancellable：父任务可能已经被 cancel，不加这个请求会立刻被打断。
                    try {
                        subHistory = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            summarizeIfTruncated(subHistory, profile, context, settings)
                        }
                    } catch (_: Throwable) { }
                } finally {
                    try {
                        SessionStore.save(
                            context,
                            Session(
                                id = subId,
                                title = "↳ $title",
                                createdAt = now,
                                updatedAt = System.currentTimeMillis(),
                                parentId = parentSessionId,
                                messages = subHistory
                            )
                        )
                    } catch (e: Exception) {
                        AgentLogger.log("ERR", "子会话完成存盘失败：${e.message}")
                    }
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
                                hitLimit = subHistory.lastOrNull { it.type == "text" }
                                    ?.content?.contains("已达到最大工具调用轮数") == true
                            )
                        )
                    )
                    AgentLogger.log("TOOL", "子会话完成 subId=$subId msgs=${subHistory.size}")
                }
            }
        }
        subSessionJobs[subId] = job

        AgentLogger.log("TOOL", "子会话已后台启动 subId=$subId")
        return "SUB_SESSION:$subId\n标题：↳ $title\n消息数：1\n" +
                "状态：已在后台启动（可并行最多 $MAX_PARALLEL_SUB 个），主对话继续执行，互不阻塞。\n" +
                "完成后系统会自动在主对话注入「子代理完成」通知；也可用 sub_session 工具查询：" +
                "op=status 看进度、op=result 配套 id 取结果。\n\n子会话结果摘要：\n" +
                "（后台执行中——完成后完整过程写入该子会话。）"
    }

    /** sub_session 工具：查询子会话运行状态 / 列表 / 结果（op=status|list|result） */
    private suspend fun querySubSession(args: JSONObject, context: Context): String {
        val op = args.optString("op", "status").trim().ifBlank { "status" }
        val id = args.optString("id", "").trim()
        return when (op) {
            "status" -> {
                val snap = SubSessionProgress.snapshot()
                if (snap.isEmpty()) {
                    "当前没有正在执行的子会话。"
                } else {
                    snap.entries.joinToString("\n") { (sid, e) ->
                        "$sid | ${e.title} | 已 ${e.steps} 步" +
                                if (e.lastTool.isNotEmpty()) " | 当前：${e.lastTool}" else ""
                    }
                }
            }
            "list" -> {
                // 【批次 19】改为按 parentId 判定，不再依赖 title 前缀
                val list = SessionStore.list(context).filter { it.parentId != null }
                if (list.isEmpty()) {
                    "暂无子会话记录。"
                } else {
                    list.joinToString("\n") {
                        val cnt = SessionStore.load(context, it.id)?.messages?.size ?: 0
                        "${it.id} | ${it.title} | $cnt 条消息"
                    }
                }
            }
            "result" -> {
                if (id.isBlank()) return "失败：op=result 需要 id 参数"
                val s = SessionStore.load(context, id)
                    ?: return "失败：找不到子会话 $id（可用 op=list 查看可用 id）"
                val finalText = s.messages.lastOrNull {
                    it.type == "text" && it.role == "assistant"
                }?.content ?: "（子会话未产生文本结果）"
                "子会话 $id「${s.title}」共 ${s.messages.size} 条消息。\n最终结果：\n" + finalText.take(1500)
            }
            "stop" -> {
                if (id.isBlank()) return "失败：op=stop 需要 id 参数"
                val job = subSessionJobs[id]
                    ?: return "失败：子会话 $id 不在运行中（可能已完成，可用 op=list 查看）"
                job.cancel()
                subSessionJobs.remove(id)
                SubSessionProgress.finish(id)
                subSessionActive.decrementAndGet()
                AgentLogger.log("TOOL", "AI 主动终止子会话 subId=$id")
                "已终止子会话 $id。"
            }
            else -> "失败：op 取值为 status / list / result / stop"
        }
    }

    suspend fun execute(
        name: String, args: JSONObject, context: Context, settings: AppSettings
    ): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // 【批次 20 修复】强制全部工具在 IO 线程执行。
        // 防止个别工具内部（如联网搜索解析、写文件）未切线程导致 Agent 主循环和 UI 卡顿。
        if (name in settings.disabledTools) {
            return@withContext "工具 $name 已被用户在设置中关闭"
        }

        if (name in VISION_TOOLS) {
            val profile = settings.currentProfile()
            if (profile != null && !profile.supportsVision) {
                return "失败：当前模型 [${profile.name}] 不支持图片输入，" +
                        "「${displayName(name)}」已禁用。请到设置里勾选该模型的「支持图片输入」，" +
                        "或换一个支持多模态的模型。"
            }
        }

        // 元工具拦截（必须在其他分发之前）
        // 【批次 18】使用 JsonArgs 语法糖
        val a = JsonArgs(args)

        if (name == "tool_search") {
            val query = a.str("query")
            if (query.isBlank()) return "失败：tool_search 需要 query 参数"
            val category = a.str("category")
            AgentLogger.log("TOOL", "tool_search query=$query category=$category")
            return searchToolsForAgent(query, category, settings)
        }
        if (name == "tool_load") {
            val toolName = a.str("name")
            if (toolName.isBlank()) return "失败：tool_load 需要 name 参数"
            AgentLogger.log("TOOL", "tool_load name=$toolName")
            return loadToolForAgent(toolName, settings)
        }
        if (name == "run_sub_session") {
            return runSubSession(args, context, settings)
        }
        if (name == "sub_session") {
            return querySubSession(args, context)
        }
        if (name == "history") {
            return HistoryTools.execute(
                args,
                context,
                BackupHelper.backupDirFor(getHost(context, settings).trashDir())
            )
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
            } else if (name == "python") {
                // 【临时执行 Python】与桌面端 python 工具对齐。
                // 受 requireConfirmPython 控制（与 pytool run 同一个开关）。
                val code = a.str("code")
                if (code.isBlank()) return "失败：python 需要 code 参数"
                val timeout = a.int("timeout", 60).coerceIn(1, 600)
                val workDir = a.str("work_dir")
                val stdinArgs = a.json("args") ?: JSONObject()

                if (settings.requireConfirmPython) {
                    val preview = if (code.length > 800) code.take(800) + "\n...(已截断)" else code
                    val approved = ConfirmationBroker.request(
                        "执行 Python 代码确认",
                        "AI 请求执行以下 Python 代码：\n\n$preview\n\n" +
                                "超时：$timeout 秒" +
                                if (workDir.isNotBlank()) "\n工作目录：$workDir" else ""
                    )
                    if (!approved) return "用户已拒绝执行 Python 代码"
                }

                val r = net.xinkong.furagent.shared.skill.SkillStore.execCode(
                    code, stdinArgs, workDir, timeout
                )
                if (r.success) r.output else "失败：${r.output}"
            } else if (name == "pytool" || net.xinkong.furagent.shared.skill.SkillTools.isDynamicTool(name)) {
                val op = args.optString("op", "").lowercase()
                val isDynamic = net.xinkong.furagent.shared.skill.SkillTools.isDynamicTool(name)
                val isRun = isDynamic || (name == "pytool" && op == "run")
                val isWriteOp = name == "pytool" && (op == "create" || op == "delete")

                if (settings.requireConfirmPython) {
                    // 1) 脚本文件管理
                    if (isWriteOp) {
                        val targetName = args.optString("name", "").trim()
                        val action = if (op == "create") "创建" else "删除"
                        val approved = ConfirmationBroker.request(
                            "${action}脚本确认",
                            "AI 请求${action}脚本：$targetName\n\n" +
                                    "目录：${net.xinkong.furagent.shared.skill.SkillStore.scriptsDir().absolutePath}"
                        )
                        if (!approved) return "用户已拒绝${action}脚本"
                    }

                    // 2) 执行脚本
                    if (isRun) {
                        val scriptName = if (isDynamic) {
                            name.removePrefix(net.xinkong.furagent.shared.skill.SkillStore.TOOL_PREFIX)
                        } else {
                            args.optString("name", "").trim()
                        }
                        val scriptArgs = if (isDynamic) {
                            args
                        } else {
                            args.optJSONObject("args") ?: JSONObject()
                        }
                        val meta = net.xinkong.furagent.shared.skill.SkillStore.find(scriptName)
                        if (meta != null) {
                            val cfg = net.xinkong.furagent.shared.skill.SkillStore.loadConfig(scriptName)
                            val timeout = net.xinkong.furagent.shared.skill.SkillStore.effectiveTimeout(meta, cfg)
                            val argsPreview = if (scriptArgs.length() == 0) "(无)" else scriptArgs.toString(2)
                            val approved = ConfirmationBroker.request(
                                "执行 Python 脚本确认",
                                "AI 请求运行脚本：\n${meta.name}\n\n" +
                                        "文件：${meta.file.absolutePath}\n" +
                                        "参数：\n$argsPreview\n\n" +
                                        "超时：$timeout 秒"
                            )
                            if (!approved) return "用户已拒绝运行脚本"
                        }
                    }
                }
                val skillResult = net.xinkong.furagent.shared.skill.SkillTools.execute(name, args)
                if (skillResult != null) {
                    if (skillResult.success) skillResult.output
                    else if (skillResult.output.startsWith("失败")) skillResult.output
                    else "失败：${skillResult.output}"
                } else {
                    "未知工具：$name"
                }
            } else {
                val host = getHost(context, settings)

                if (name == "write_file") {
                    confirmThenWrite(args, host, settings, context)
                } else {
                    val commonResult = CommonTools.execute(name, args, host)

                    if (commonResult != null) {
                        if (commonResult.success) commonResult.output
                        else "失败：${commonResult.output}"
                    } else {
                        // 【批次 18】查表路由，O(1) 速度
                        PRIVATE_ROUTER[name]?.invoke(args, context, settings) ?: "未知工具：$name"
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

        // 【批次 11 修复】RESULT 日志截断。
        // 原实现全量写入 AgentLogger.logs，execute_command 跑一次 Gradle 输出几十 MB、
        // grep 返回 5000 条命中、read_file 读 900KB 文件——全进内存 list 会造成：
        //   - UI 日志页渲染卡顿
        //   - 内存持续上涨（跑一天能到几百 MB）
        //   - 日志文件写盘时全量写
        // 现在只记录前 2000 字符 + 完整长度，需要看全文时从工具返回的上下文里找。
        val logResult = if (result.length > 2000) {
            result.take(2000) + "\n...（已截断，完整长度 ${result.length} 字符）"
        } else {
            result
        }
        AgentLogger.log("RESULT", "工具 $name 返回:\n$logResult")
        result
    }
}

internal fun normalizeAlias(path: String): String =
    net.xinkong.furagent.shared.util.PathAlias.expandAndroid(path, SDCARD_PATH)