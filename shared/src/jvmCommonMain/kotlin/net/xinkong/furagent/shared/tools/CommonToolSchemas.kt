package net.xinkong.furagent.shared.tools

object CommonToolSchemas {

    val SPECS: List<ToolSpec> = listOf(

        // ============================================================
        // 1. 时间日期
        // ============================================================
        ToolSpec(
            name = "time",
            displayName = "时间日期",
            description = "时间和日期工具。op=now 获取当前时间（日期/星期/时区/时间戳）；op=diff 计算两日期相差天数；op=add 计算某日期加减 N 天；op=info 返回某日期的星期和年内第几天；op=to_date 时间戳转日期字符串；op=to_timestamp 日期字符串转时间戳。",
            paramsSchema = """{"type":"object","properties":{"op":{"type":"string","enum":["now","diff","add","info","to_date","to_timestamp"]},"date1":{"type":"string"},"date2":{"type":"string"},"days":{"type":"integer"},"value":{"type":"string"}},"required":["op"]}"""
        ),

        // ============================================================
        // 2. 计算 + 随机
        // ============================================================
        ToolSpec(
            name = "calculate",
            displayName = "计算",
            description = "高精度数学计算与随机生成。type=expr（默认）：计算表达式，支持 + - * / % ^ 括号，函数 sqrt/sin/cos/tan/asin/acos/atan/ln/log/exp/abs/floor/ceil/round，常量 pi/e，百分号 %（50% 即 0.5）。sin/cos/tan 为角度制。可用分号或换行一次传多个表达式。type=random 时生成随机数/字符串/UUID/从选项中选择。",
            paramsSchema = """{"type":"object","properties":{"type":{"type":"string","enum":["expr","random"]},"expression":{"type":"string"},"precision":{"type":"integer"},"random_type":{"type":"string","enum":["int","float","string","uuid","choice"]},"min":{"type":"number"},"max":{"type":"number"},"length":{"type":"integer"},"charset":{"type":"string"},"options":{"type":"string"}},"required":["type"]}"""
        ),

        // ============================================================
        // 3. 转换
        // ============================================================
        ToolSpec(
            name = "convert",
            displayName = "转换",
            description = "格式转换。type=unit 单位换算（长度/重量/温度/存储/时间/面积，支持中文单位名如 米/斤/摄氏度）；type=base 进制转换（2~36 进制，可带 0x/0b/0o 前缀自动识别）；type=color 颜色格式转换（输入 #RRGGBB 或 rgb(r,g,b)，输出 HEX / RGB / HSL / Android ARGB）。",
            paramsSchema = """{"type":"object","properties":{"type":{"type":"string","enum":["unit","base","color"]},"value":{"type":"string"},"from":{"type":"string"},"to":{"type":"string"},"from_base":{"type":"integer"},"to_base":{"type":"integer"},"input":{"type":"string"}},"required":["type"]}"""
        ),

        // ============================================================
        // 4. 统计
        // ============================================================
        ToolSpec(
            name = "statistics",
            displayName = "统计",
            description = "计算一组数字的统计指标：个数、总和、均值、中位数、最小、最大、极差、样本方差、样本标准差。",
            paramsSchema = """{"type":"object","properties":{"numbers":{"type":"string"}},"required":["numbers"]}"""
        ),

        // ============================================================
        // 5. 文本
        // ============================================================
        ToolSpec(
            name = "text",
            displayName = "文本处理",
            description = "文本处理。op=regex 正则匹配（mode=find/match_all/replace/split）；op=diff 比较两段文本行级差异；op=url_encode 编码 URL；op=url_decode 解码 URL。",
            paramsSchema = """{"type":"object","properties":{"op":{"type":"string","enum":["regex","diff","url_encode","url_decode"]},"text":{"type":"string"},"pattern":{"type":"string"},"mode":{"type":"string"},"replacement":{"type":"string"},"limit":{"type":"integer"},"text1":{"type":"string"},"text2":{"type":"string"},"component":{"type":"boolean"}},"required":["op"]}"""
        ),

        // ============================================================
        // 6. JSON
        // ============================================================
        ToolSpec(
            name = "json",
            displayName = "JSON 处理",
            description = "JSON 与 CSV 处理。op=format 美化 JSON；op=minify 压缩；op=validate 校验并返回类型；op=extract 按 path 提取字段（点号分隔）；op=csv_to_json CSV 文本转 JSON 数组；op=json_to_csv JSON 对象数组转 CSV。",
            paramsSchema = """{"type":"object","properties":{"op":{"type":"string","enum":["format","minify","validate","extract","csv_to_json","json_to_csv"]},"text":{"type":"string"},"path":{"type":"string"}},"required":["op","text"]}"""
        ),

        // ============================================================
        // 7. 哈希
        // ============================================================
        ToolSpec(
            name = "hash",
            displayName = "哈希计算",
            description = "计算哈希。给 text 计算文本哈希；给 path 计算文件哈希。支持 md5 / sha1 / sha256 / sha512。",
            paramsSchema = """{"type":"object","properties":{"text":{"type":"string"},"path":{"type":"string"},"algorithm":{"type":"string"}}}"""
        ),

        // ============================================================
        // 8. 浏览 / 搜索文件
        // ============================================================
        ToolSpec(
            name = "list_files",
            displayName = "浏览/搜索文件",
            description = "列出目录内容、按文件名搜、或搜文件正文。mode=name（默认）列举目录；给了 keyword 就按文件名递归搜索；mode=content 时把 keyword 当内容搜（返回 路径/行号/内容，可用于找符号定义、配置项、日志文本）。【搜正文】建议配合 glob 限定文件名（如 *.kt）以提速；keyword 含正则特征字符（| \\ ( ) [ ] { } ^ $ * + ?）时自动按正则处理，也可显式传 regex；会自动跳过 .git/build/node_modules 与二进制文件；拿到行号后可直接用 read_file(offset=行号, limit=N) 跳读上下文。支持路径别名：sdcard、桌面、下载、图片、文档、音乐。默认只返回前 100 条（max_results 默认 100），需要更多请显式传 max_results。需要同时看多个目录/多个关键词时，尽量合并成一次调用，不要拆成多轮。结果超过 8KB 时会自动落盘到 ~/.furagent/temp/，上下文只留前 30 行预览 + 文件路径，要看全文用 read_file 读该路径。",
            paramsSchema = """{"type":"object","properties":{"path":{"type":"string"},"keyword":{"type":"string"},"mode":{"type":"string","enum":["name","content"],"description":"name=按文件名搜（默认）；content=搜文件正文。不传时：传了 keyword 自动按 content，否则列目录"},"glob":{"type":"string","description":"mode=content 时的文件名过滤，如 *.kt 或 *.kt,*.kts"},"regex":{"type":"boolean","description":"mode=content 时把 keyword 当正则（不传时含正则特征字符会自动按正则）"},"context":{"type":"integer","description":"mode=content 时每条命中带前后各 N 行上下文（0-10）"},"max_file_size_kb":{"type":"integer","description":"搜正文时超过该大小的文件先嗅头部判断是否文本，默认 2048"},"max_results":{"type":"integer"}},"required":["path"]}"""
        ),

        // ============================================================
        // 7b. grep 已并入 list_files（mode=content），不再单独暴露。
        // execute() 仍保留 "grep" 入口做向后兼容。
        // ============================================================

        // ============================================================
        // 9. 读取文件
        // ============================================================
        ToolSpec(
            name = "read_file",
            displayName = "读取文件",
            description = "读取文件内容。mode=text（默认）返回文本；传 offset/limit 时按行区间读并带行号（大文件必须用这种方式分页读，否则超过 1MB 会被拒）。mode=base64 返回 base64（跨端传输，限 10MB）；mode=hex 返回十六进制 + ASCII 视图（length 默认 512 字节）。读大文件请显式给 limit（如 limit=100），不要一次拉全文；需要看多个文件时尽量合并成一次调用，不要拆成多轮。结果超过 16KB 时会自动落盘到 ~/.furagent/temp/，上下文只留前 30 行预览 + 文件路径，要看全文用 read_file 读该路径（读落盘文件不会再落盘）。",
            paramsSchema = """{"type":"object","properties":{"path":{"type":"string"},"mode":{"type":"string","enum":["text","base64","hex"]},"offset":{"type":"integer","description":"起始行号，1 起。传了它或 limit 就按行区间读并带行号"},"limit":{"type":"integer","description":"最多读多少行"},"length":{"type":"integer","description":"mode=hex 时显示的字节数"}},"required":["path"]}"""
        ),

        // ============================================================
        // 10. 写入文件
        // ============================================================
        ToolSpec(
            name = "write_file",
            displayName = "写入文件",
            description = "写入文件。mode=text（默认）把 content 写入（可选 append=true 追加到末尾）；mode=base64 把 data 解码后写入（用于跨端接收文件）。" +
                    "\n\n【注意】如果用户要求「做个脚本 / 加个功能 / 以后都用这个流程」，且目标是桌面端的自定义脚本（~/.furagent/scripts/），不要直接用 write_file 往 scripts/ 根目录扔裸 .py。" +
                    "自定义脚本有固定规范（每个脚本一个子目录，内含 skill.json + 入口 .py），必须先用 tool_search(\"自定义脚本\") 或 tool_load(\"skill\") 拿到 skill 工具的完整规范再创建。",
            paramsSchema = """{"type":"object","properties":{"path":{"type":"string"},"mode":{"type":"string","enum":["text","base64"]},"content":{"type":"string"},"data":{"type":"string"},"append":{"type":"boolean"}},"required":["path"]}"""
        ),

        // ============================================================
        // 11. HTTP 请求
        // ============================================================
        ToolSpec(
            name = "http_request",
            displayName = "HTTP 请求",
            description = "发送任意 HTTP 请求。支持 GET/POST/PUT/DELETE/PATCH/HEAD，自定义 headers 和 body。用于调用 REST API、调试接口、抓取 JSON 数据等。响应体完整返回，不截断。",
            paramsSchema = """{"type":"object","properties":{"url":{"type":"string"},"method":{"type":"string"},"headers":{"type":"object"},"body":{"type":"string"},"timeout":{"type":"integer"}},"required":["url"]}"""
        ),

        // ============================================================
        // 12. 环境变量
        // ============================================================
        ToolSpec(
            name = "env_store",
            displayName = "环境变量",
            description = "读写会话级环境变量。operation=get / set / delete / clear。用于在不同对话轮次之间保存/读取数据。",
            paramsSchema = """{"type":"object","properties":{"operation":{"type":"string"},"key":{"type":"string"},"value":{"type":"string"}},"required":["operation"]}"""
        ),

        // ============================================================
        // 13. sleep（等待）
        // ============================================================
        ToolSpec(
            name = "sleep",
            displayName = "等待",
            description = "让 Agent 等待指定时长。用于：轮询状态（比如等 App 加载完成再截图）、限速（避免频繁调用接口被风控）、等待异步任务完成。参数：seconds（秒，可带小数）或 ms（毫秒，整数），二选一。最大 300 秒。可选 reason 说明等待目的（会写进返回文本）。等待期间用户仍可取消任务，取消时立即中断。",
            paramsSchema = """{"type":"object","properties":{"seconds":{"type":"number"},"ms":{"type":"integer"},"reason":{"type":"string"}}}"""
        )
    )

    val DISPLAY_NAMES: Map<String, String> =
        SPECS.associate { it.name to it.displayName }

    val NAME_TO_SPEC: Map<String, ToolSpec> =
        SPECS.associateBy { it.name }

    val COMMON_NAMES: Set<String> =
        SPECS.map { it.name }.toSet()
}