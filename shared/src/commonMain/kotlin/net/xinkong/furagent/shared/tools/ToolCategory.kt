package net.xinkong.furagent.shared.tools

/**
 * 工具分类与分层策略。
 *
 * 【P0 优化：分层工具系统】
 *
 * 背景：
 *   现有实现把所有工具的完整 JSON Schema 塞进每次 API 请求的 `tools` 数组。
 *   仅内置工具约 35 个就是 4K+ token；加上几个 MCP server 轻松过万。
 *   每次请求都为这些"99% 用不到"的工具付 token 成本。
 *
 * 方案（参考 MCP 官方 Progressive Discovery、Anthropic Tool Search Tool）：
 *   Layer 1：核心工具（约 12 个）—— 始终发送完整 schema
 *   Layer 2：工具目录 —— 只发"名字 + 一句话描述"，全部拼进 tool_search 的 description
 *   Layer 3：完整定义 —— AI 通过 tool_search / tool_load 按需拉取
 *
 * 效果：
 *   内置工具场景：~4200 → ~1800 token（省 57%）
 *   带 MCP 场景：~11000 → ~2500 token（省 77%）
 */
object ToolCategory {

    /**
     * 始终发送完整 schema 的核心工具。
     *
     * 挑选原则：
     *   - 几乎所有对话都会用到（time / calculate / read_file / write_file）
     *   - 或作为分层机制本身的入口（tool_search / tool_load）
     *
     * tool_search / tool_load 本身是元工具，必须始终在列表里，
     * 否则 AI 无法发现其他工具。
     */
    val CORE_TOOL_NAMES: Set<String> = setOf(
        // 基础
        "time",
        "calculate",
        // 文件基础读写
        "list_files",
        "read_file",
        "write_file",
        // 【P2-2】grep 已并入 list_files（mode=content），不再常驻。
        // execute() 仍保留 "grep" 入口做向后兼容，但不再占 tools 数组。
        // 网络
        "web",
        "http_request",
        // 记忆
        "memory",
        // 等待
        "sleep",
        // 屏幕控制（高频，UI 交互场景都要用）
        "ui",
        // 元工具
        "tool_search",
        "tool_load",
    )

    /**
     * 工具的中文关键词。
     *
     * 【为什么需要】ToolCatalog.score 原本只拿 query 去撞 name / displayName /
     * description 的英文子串，中文 query（如“搜代码”“找定义”）永远 0 分，
     * 中文用户和中文思考的 AI 第一跳必踩空。
     *
     * 补法：给每个工具挂几个用户真正会说的中文词，搜的时候一并撞。
     * 不改工具名、不加工具数量，纯提升命中率。
     */
    private val ZH_KEYWORDS: Map<String, List<String>> = mapOf(
        "grep" to listOf("搜内容", "搜索代码", "找代码", "找定义", "找函数", "查函数", "查找文本", "全局搜索", "关键字搜索", "代码搜索", "搜代码"),
        "read_file" to listOf("读文件", "看文件", "查看文件", "读代码", "看代码", "行号", "分页读"),
        "list_files" to listOf("列目录", "浏览文件", "找文件", "文件列表", "搜文件名"),
        "write_file" to listOf("写文件", "保存文件", "新建文件"),
        "code_edit" to listOf("改代码", "编辑代码", "修改代码", "改文件", "替换代码", "打补丁", "diff"),
        "execute_command" to listOf("执行命令", "运行命令", "命令行", "shell", "跑命令", "终端"),
        "delete_file" to listOf("删文件", "删除文件", "回收站", "移入回收站"),
        "text" to listOf("文本处理", "正则", "替换文本", "比较文本"),
        "json" to listOf("格式化", "美化", "json", "csv"),
        "hash" to listOf("哈希", "校验", "摘要", "md5"),
        "web" to listOf("搜索", "联网", "搜网页", "查资料", "抓网页"),
        "http_request" to listOf("请求接口", "调接口", "api", "http"),
        "convert" to listOf("单位换算", "进制转换", "颜色转换", "换算"),
        "statistics" to listOf("统计", "平均值", "方差", "中位数"),
        "memory" to listOf("记忆", "记住", "回忆"),
        "ui" to listOf("屏幕控制", "点屏幕", "界面操作", "点击"),
        "screenshot" to listOf("截图", "截屏", "屏幕截图"),
        "view_image" to listOf("看图", "查看图片", "图片识别"),
        "ffmpeg" to listOf("视频", "音频", "转码", "剪辑", "压缩视频"),
        "python" to listOf("python", "跑脚本", "执行代码"),
        "skill" to listOf("skill", "脚本", "自定义脚本"),
        "pytool" to listOf("脚本", "自定义脚本", "pytool", "跑脚本"),
        "apps" to listOf("应用", "打开应用", "启动程序"),
        "system" to listOf("系统信息", "音量", "剪贴板", "壁纸"),
        "sleep" to listOf("等待", "延时", "等一会"),
        "schedule" to listOf("定时", "定时任务", "计划任务"),
        "workflow" to listOf("工作流", "流程", "自动化"),
        "env_store" to listOf("变量", "临时存储", "环境变量"),
        "document" to listOf("文档", "生成文档", "word", "excel", "ppt", "表格", "幻灯片", "报告", "docx", "xlsx", "pptx"),
        "generate_image" to listOf("生图", "画图", "生成图片", "ai画图", "文生图", "绘图", "出图"),
        "download_file" to listOf("下载", "下载文件", "下载链接", "拉取文件", "多线程下载")
    )

    /** 取某个工具的中文关键词（没有就空表）。 */
    fun zhKeywords(name: String): List<String> = ZH_KEYWORDS[name] ?: emptyList()

    /** 分类的中文别名，让 tool_search 的 category 也接受中文。 */
    private val CATEGORY_ALIASES: Map<String, String> = mapOf(
        "文件" to "file", "文件操作" to "file",
        "系统" to "system", "系统控制" to "system",
        "媒体" to "media", "音视频" to "media",
        "网络" to "network", "联网" to "network",
        "屏幕" to "screen", "屏幕控制" to "screen",
        "远程" to "remote", "桥接" to "remote", "手机" to "remote",
        "记忆" to "memory",
        "代码" to "code", "脚本" to "skill"
    )

    /** 把用户传的 category 归一化成内部值（支持中文）。 */
    fun normalizeCategory(raw: String): String {
        val t = raw.trim().lowercase()
        if (t.isBlank()) return ""
        return CATEGORY_ALIASES[t] ?: t
    }

    /**
     * 按工具名推断分类。用于 tool_search 的 category 过滤和目录展示。
     *
     * 为什么不做成 ToolSpec 的字段：
     *   CommonToolSchemas.SPECS 是 shared 里的静态数据，加字段要改所有 spec 定义。
     *   用名字映射更轻量，也方便后续调整。新增工具只要在这里补一行即可。
     */
    fun categorize(name: String): String = when (name) {
        // 文件
        "list_files", "read_file", "write_file", "hash", "grep",
        "file_op", "zip", "base64", "delete_file", "download_file" -> "file"

        // 系统
        "time", "calculate", "convert", "statistics", "text", "json",
        "env_store", "sleep", "run_sub_session", "sub_session",
        "app", "device", "calendar", "schedule", "workflow",
        "execute_shell", "execute_command", "set_alarm", "get_location",
        "add_contact", "notifications", "open_url", "apps" -> "system"

        // 媒体
        "ffmpeg", "screenshot", "view_image", "media_session" -> "media"

        // 文档生成（仅桌面端）
        "document" -> "file"

        // AI 生图（仅桌面端）
        "generate_image" -> "media"
        // 网络
        "web", "http_request" -> "network"

        // 屏幕
        "ui" -> "screen"

        // 远程 / 桥接
        "pc", "phone_list_tools", "phone_call", "phone_status",
        "phone_send_file", "phone_receive_file" -> "remote"

        // 记忆
        "memory" -> "memory"

        // 代码
        "code_edit" -> "code"

        // 脚本
        "skill" -> "skill"

        // MCP 动态工具
        else -> if (name.startsWith("mcp__")) "mcp" else "other"
    }
}