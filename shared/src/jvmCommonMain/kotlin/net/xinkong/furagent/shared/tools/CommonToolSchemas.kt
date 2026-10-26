package net.xinkong.furagent.shared.tools

object CommonToolSchemas {

    val SPECS: List<ToolSpec> = listOf(

        // 1. 时间日期
        ToolSpec(
            name = "time",
            displayName = "时间日期",
            description = "时间和日期工具。op=now 获取当前时间（日期/星期/时区/时间戳）；op=diff 计算两日期相差天数；op=add 计算某日期加减 N 天；op=info 返回某日期的星期和年内第几天；op=to_date 时间戳转日期字符串；op=to_timestamp 日期字符串转时间戳。",
            paramsSchema = """{"type":"object","properties":{"op":{"type":"string","enum":["now","diff","add","info","to_date","to_timestamp"]},"date1":{"type":"string"},"date2":{"type":"string"},"days":{"type":"integer"},"value":{"type":"string"}},"required":["op"]}"""
        ),

        // 2. 计算 + 随机
        ToolSpec(
            name = "calculate",
            displayName = "计算",
            description = "高精度数学计算与随机生成。type=expr（默认）：计算表达式，支持 + - * / % ^ 括号，函数 sqrt/sin/cos/tan/asin/acos/atan/ln/log/exp/abs/floor/ceil/round，常量 pi/e，百分号 %（50% 即 0.5）。sin/cos/tan 为角度制。可用分号或换行一次传多个表达式。type=random 时生成随机数/字符串/UUID/从选项中选择。",
            paramsSchema = """{"type":"object","properties":{"type":{"type":"string","enum":["expr","random"]},"expression":{"type":"string"},"precision":{"type":"integer"},"random_type":{"type":"string","enum":["int","float","string","uuid","choice"]},"min":{"type":"number"},"max":{"type":"number"},"length":{"type":"integer"},"charset":{"type":"string"},"options":{"type":"string"}},"required":["type"]}"""
        ),

        // 3. 转换
        ToolSpec(
            name = "convert",
            displayName = "转换",
            description = "格式转换。type=unit 单位换算（长度/重量/温度/存储/时间/面积，支持中文单位名如 米/斤/摄氏度）；type=base 进制转换（2~36 进制，可带 0x/0b/0o 前缀自动识别）；type=color 颜色格式转换（输入 #RRGGBB 或 rgb(r,g,b)，输出 HEX / RGB / HSL / Android ARGB）。",
            paramsSchema = """{"type":"object","properties":{"type":{"type":"string","enum":["unit","base","color"]},"value":{"type":"string"},"from":{"type":"string"},"to":{"type":"string"},"from_base":{"type":"integer"},"to_base":{"type":"integer"},"input":{"type":"string"}},"required":["type"]}"""
        ),

        // 4. 统计
        ToolSpec(
            name = "statistics",
            displayName = "统计",
            description = "计算一组数字的统计指标：个数、总和、均值、中位数、最小、最大、极差、样本方差、样本标准差。可以直接传 json_array（如从 CSV 导出的数据）和 field 字段名，直接对复杂 JSON 数组中的某个数值字段进行统计分析。",
            paramsSchema = """{"type":"object","properties":{"numbers":{"type":"string","description":"逗号分隔的数字"},"json_array":{"type":"string","description":"JSON 数组字符串"},"field":{"type":"string","description":"从 JSON 数组中提取的数值字段名"}},"required":[]}"""
        ),

        // 5. 文本处理
        ToolSpec(
            name = "text",
            displayName = "文本处理",
            description = "文本处理。op=regex 正则匹配（mode=find/match_all/replace/split）；op=diff 比较两段文本行级差异；op=url_encode 编码 URL；op=url_decode 解码 URL。使用 op=batch_replace，传入 dir 目录、glob 文件匹配（如 *.kt）、pattern 正则、replacement 替换内容，直接在文件系统上批量修改代码或文本。",
            paramsSchema = """{"type":"object","properties":{"op":{"type":"string","enum":["regex","diff","url_encode","url_decode","batch_replace"]},"text":{"type":"string"},"pattern":{"type":"string"},"mode":{"type":"string"},"replacement":{"type":"string"},"limit":{"type":"integer"},"text1":{"type":"string"},"text2":{"type":"string"},"component":{"type":"boolean"},"dir":{"type":"string"},"glob":{"type":"string"}},"required":["op"]}"""
        ),

        // 6. JSON
        ToolSpec(
            name = "json",
            displayName = "JSON 处理",
            description = "JSON 与 CSV 处理。op=format 美化 JSON；op=minify 压缩；op=validate 校验并返回类型；op=extract 按 path 提取字段（点号分隔）；op=csv_to_json CSV 文本转 JSON 数组；op=json_to_csv JSON 对象数组转 CSV。",
            paramsSchema = """{"type":"object","properties":{"op":{"type":"string","enum":["format","minify","validate","extract","csv_to_json","json_to_csv"]},"text":{"type":"string"},"path":{"type":"string"}},"required":["op","text"]}"""
        ),

        // 7. 哈希
        ToolSpec(
            name = "hash",
            displayName = "哈希计算",
            description = "计算哈希。给 text 计算文本哈希；给 path 计算文件哈希。支持 md5 / sha1 / sha256 / sha512。",
            paramsSchema = """{"type":"object","properties":{"text":{"type":"string"},"path":{"type":"string"},"algorithm":{"type":"string"}}}"""
        ),

        // 8. 浏览 / 搜索文件
        ToolSpec(
            name = "list_files",
            displayName = "浏览/搜索文件",
            description = "列出目录内容、按文件名搜、或搜文件正文。mode=name（默认）列举目录；给了 keyword 就按文件名递归搜索；mode=content 时把 keyword 当内容搜。要找最新文件，直接传 sort_by=\"time\" 和 descending=true。拿到行号后可直接用 read_file(offset=行号) 跳读。默认只返回前 100 条（max_results）。",
            paramsSchema = """{"type":"object","properties":{"path":{"type":"string"},"keyword":{"type":"string"},"mode":{"type":"string","enum":["name","content"],"description":"name=按文件名搜（默认）；content=搜文件正文。不传 keyword 时列目录"},"glob":{"type":"string","description":"mode=content 时的文件名过滤，如 *.kt"},"regex":{"type":"boolean","description":"mode=content 时把 keyword 当正则"},"context":{"type":"integer","description":"mode=content 时每条命中带前后各 N 行上下文（0-10）"},"max_file_size_kb":{"type":"integer"},"max_depth":{"type":"integer","description":"递归最大深度，默认 15。"},"sort_by":{"type":"string","enum":["name","time","size"],"description":"列目录时的排序依据，默认 name"},"descending":{"type":"boolean","description":"是否降序，默认 false"},"max_results":{"type":"integer"}},"required":["path"]}"""
        ),

        // 9. 读取文件
        ToolSpec(
            name = "read_file",
            displayName = "读取文件",
            description = "读取文件内容。mode=text（默认）返回文本；传 offset/limit 按行区间读并带行号。要看日志最后几行，直接用：read_file(path=\"xxx.log\", mode=\"tail\", tail_lines=100)，无需计算 offset。要看多个文件，用 paths=[\"a.txt\", \"b.txt\"]。mode=base64 返回 base64；mode=hex 返回十六进制。",
            paramsSchema = """{"type":"object","properties":{"path":{"type":"string"},"paths":{"type":"array","items":{"type":"string"},"description":"批量读多个文件。与 path 二选一"},"mode":{"type":"string","enum":["text","base64","hex","tail"]},"offset":{"type":"integer","description":"起始行号，1 起"},"limit":{"type":"integer","description":"最多读多少行"},"tail_lines":{"type":"integer","description":"mode=tail 时的行数，默认 100"},"length":{"type":"integer","description":"mode=hex 时显示的字节数"}},"required":[]}"""
        ),

        // 10. 写入文件
        ToolSpec(
            name = "write_file",
            displayName = "写入文件",
            description = "写入文件。mode=text（默认）把 content 写入（可选 append=true 追加到末尾）。如果要拆分或生成多个文件，必须用 files 数组一次性写入，不要分多次调用！示例：write_file(files=[{\"path\":\"A.kt\", \"content\":\"...\"}, {\"path\":\"B.kt\", \"content\":\"...\"}])。",
            paramsSchema = """{"type":"object","properties":{"path":{"type":"string"},"files":{"type":"array","items":{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"},"append":{"type":"boolean"}}},"description":"批量写入的文件列表"},"mode":{"type":"string","enum":["text","base64"]},"content":{"type":"string"},"data":{"type":"string"},"append":{"type":"boolean"}},"required":[]}"""
        ),

        // 11. HTTP 请求（增强版）
        ToolSpec(
            name = "http_request",
            displayName = "HTTP 请求",
            description = "发送任意 HTTP 请求。支持 GET/POST/PUT/DELETE/PATCH/HEAD，自定义 headers 和 body。用于调用 REST API、调试接口、抓取 JSON 数据等。如果传了 urls（字符串数组），会并发请求这多个地址，合并结果返回，极大地节省时间。响应体完整返回，不截断。",
            paramsSchema = """{"type":"object","properties":{"url":{"type":"string"},"urls":{"type":"array","items":{"type":"string"},"description":"批量并发请求的 URL 列表。与 url 二选一"},"method":{"type":"string"},"headers":{"type":"object"},"body":{"type":"string"},"timeout":{"type":"integer"}},"required":[]}"""
        ),

        // 12. 环境变量
        ToolSpec(
            name = "env_store",
            displayName = "环境变量",
            description = "读写会话级环境变量。operation=get / set / delete / clear。用于在不同对话轮次之间保存/读取数据。",
            paramsSchema = """{"type":"object","properties":{"operation":{"type":"string"},"key":{"type":"string"},"value":{"type":"string"}},"required":["operation"]}"""
        ),

        // 13. sleep（等待）
        ToolSpec(
            name = "sleep",
            displayName = "等待",
            description = "让 Agent 等待指定时长。用于：轮询状态（比如等 App 加载完成再截图）、限速（避免频繁调用接口被风控）、等待异步任务完成。参数：seconds（秒，可带小数）或 ms（毫秒，整数），二选一。最大 300 秒。",
            paramsSchema = """{"type":"object","properties":{"seconds":{"type":"number"},"ms":{"type":"integer"},"reason":{"type":"string"}}}"""
        ),

        // 14. 运行子会话
        ToolSpec(
            name = "run_sub_session",
            displayName = "运行子会话",
            description = "派生一个全新的独立子会话（子代理）执行子任务：子会话不携带当前对话历史，以全新上下文独立运行；调用立即返回、不阻塞主对话（可并行最多 4 个）。调用时机：子任务与当前历史无关、或需要隔离上下文独立完成的整块工作。",
            paramsSchema = """{"type":"object","properties":{"task":{"type":"string","description":"子任务完整描述，需自包含全部上下文"},"title":{"type":"string","description":"可选：子会话标题"},"session_id":{"type":"string","description":"可选：复用已有子会话 id"}},"required":["task"]}"""
        ),

        // 15. 查询子会话
        ToolSpec(
            name = "sub_session",
            displayName = "查询子会话",
            description = "查询或控制子代理（子会话）。参数 op：status=列出正在执行的子会话及进度（默认）；list=列出全部子会话记录；result=取指定子会话的最终结果（需配套 id）；stop=终止正在执行的子会话（需配套 id）。",
            paramsSchema = """{"type":"object","properties":{"op":{"type":"string","enum":["status","list","result","stop"],"description":"默认 status"},"id":{"type":"string","description":"op=result / op=stop 时必填"}}}"""
        )
    )

    val DISPLAY_NAMES: Map<String, String> =
        SPECS.associate { it.name to it.displayName }

    val NAME_TO_SPEC: Map<String, ToolSpec> =
        SPECS.associateBy { it.name }

    val COMMON_NAMES: Set<String> =
        SPECS.map { it.name }.toSet()
}