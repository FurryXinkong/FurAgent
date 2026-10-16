# FurAgent

> 跨设备、本地优先、MCP 可扩展的个人 AI Agent。

![Platform](https://img.shields.io/badge/platform-Android%2010%2B%20%7C%20Windows%2010%2B-blue)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-7F52FF)
![Compose](https://img.shields.io/badge/Compose-1.12.1-4285F4)
![License](https://img.shields.io/badge/license-Apache%202.0-green)

---

## 这是什么

FurAgent 是一个跑在你自己设备上的 AI Agent。它不是聊天机器人，而是一个能**实际操作**你手机和电脑的助手。

你告诉它要做什么，它会调用工具去完成——读写文件、执行命令、处理音视频、定时执行任务、控制手机和电脑上的应用、联网搜索、读取通知。所有推理通过你自己的 API Key 完成，所有数据存在你自己的设备上。

---

## 关于本项目

**本项目源自作者的一个奇妙小幻想**

## **FurAgent 的代码几乎完全由 AI 编写。**

---

## 和普通 AI 助手的区别

| 普通 AI 助手 | FurAgent |
|---|---|
| 云端沙盒，只能处理文本 | 跑在真机上，能操作文件、命令、应用 |
| 单设备 | 手机 ↔ 电脑双向协作，互相调用工具 |
| 厂商提供 API | 用你自己的 OpenAI 兼容接口（BYOK） |
| 封闭工具集 | 支持 MCP 协议接入第三方工具 |
| 数据在云端 | 会话、记忆、配置全部本地存储 |

---

## 核心特性

### 通用
- **通用 Agent 循环**——多轮工具调用、失败重试、防伪造工具调用
- **三种工具来源**——内置通用工具、平台私有工具、MCP 动态工具
- **长期记忆**——主动识别值得记住的信息，跨会话注入上下文
- **定时任务**——支持秒级轮询 / cron / 条件触发（电量、网络、时间段等）
- **工作流**——把多个工具调用串成命名流程，带条件分支和循环
- **会话搜索**——全文检索所有历史消息
- **会话管理**——复制会话、单会话导入导出、全量备份恢复
- **模型能力配置**——可开关"是否支持图片输入"，不支持时自动禁用截图与图片工具
- **Markdown 渲染**——标题、列表、引用、代码块、表格、链接等基础语法
- **Token 估算**——不依赖 API 的 usage 字段，本地估算 prompt / completion

### Android 端
- **22 个私有工具**——应用管理、设备信息、日历、日程、剪贴板、定位、联系人、下载、截图、通知读取、媒体控制
- **Shizuku 集成**——调用 ADB 级权限执行 shell、后台启动应用
- **FFmpeg 音视频处理**——换容器、压缩、裁剪、转码、加水印、提取音频等 50+ 预设
- **媒体控制**——读取并控制当前播放的音乐 / 播客（MediaSession）
- **通知读取**——缓存最近通知，供 AI 通过 `notifications` 工具读取
- **免提模式**——语音识别 → AI → TTS 朗读，全自动循环
- **悬浮助手**——悬浮球一键唤起对话，不打断当前应用
- **电脑互联**——通过局域网 WebSocket 调用电脑端工具，大文件走 HTTP 通道
- **直连传输**——不经电脑中转，两台手机 / 手机与电脑直接互传文件
- **消息组折叠**——连续工具调用自动折叠，长会话更清爽
- **WOL 唤醒**——远程唤醒局域网内的电脑

### 桌面端
- **16 个私有工具**——文件删除、命令执行、打开应用 / 网址、联网搜索、系统集成
- **联网搜索**——360 / 百度 / 必应多源自动回退，可显式指定搜索源
- **Python 集成**——执行任意 Python 3 代码，支持 pip 安装
- **FFmpeg 音视频处理**——同 Android，参数针对桌面优化
- **系统集成**——音量、媒体控制、窗口控制、电源、锁屏、通知、剪贴板、壁纸
- **SMTC 媒体控制**——Windows 系统级媒体控制，比键盘事件可靠
- **系统通知**——通过 AWT SystemTray 弹原生通知
- **手机桥接**——调用手机端工具、互传文件
- **relay 中转**——手机 A → 电脑 → 手机 B 的文件转发
- **TTS**——Windows SAPI 语音合成
- **开机自启动**——Windows 注册表一键配置
- **直连传输**——不经中转，电脑与手机 / 电脑与电脑直接互传

### 跨端协作
- **工具互调**——手机可以让电脑执行命令，电脑可以让手机截图
- **文件互传**——小文件走 WebSocket base64，大文件走 HTTP 通道
- **多设备支持**——同时连接多台设备，工具调用可指定 `client_id`
- **设备列表**——`pc(op=peers)` 列出所有在线设备（含 client_id / MAC / 端类型）
- **配置双向同步**——导出的备份跨端字段对齐，模型列表 / 设置项可互导

---

## 系统要求

### Android 端
- **Android 10 (API 30) 或更高**
- **arm64-v8a** 架构（暂不支持 32 位）
- 可选：Shizuku（如需 shell 执行和后台启动应用）

### 桌面端
- **Windows 10 1809 或更高**（SMTC 媒体控制需要此版本）
- 内嵌 JRE，无需另装 Java

---

## 快速开始

### 第一步：准备一个 OpenAI 兼容接口

FurAgent 不提供模型，你需要自己准备一个。支持所有 OpenAI 兼容接口：

| 服务 | Base URL 示例 |
|---|---|
| DeepSeek | `https://api.deepseek.com/chat/completions` |
| 火山方舟 | `https://ark.cn-beijing.volces.com/api/v3/chat/completions` |
| Ollama（本地） | `http://localhost:11434/v1/chat/completions` |
| LM Studio（本地） | `http://localhost:1234/v1/chat/completions` |
| 自建中转站 | `http://your-server/v1/chat/completions` |

拿到 API Key 后，在 FurAgent 设置里填入。

### 第二步：安装

**Android 端**

从 [Releases](https://github.com/FurryXinkong/FurAgent/releases) 下载最新 APK，安装后授予必要权限。

**桌面端**

从 [Releases](https://github.com/FurryXinkong/FurAgent/releases) 下载 `FurAgent-x.x.x.exe`，双击安装。

### 第三步：配置

1. 打开 FurAgent
2. 进入设置 → 模型 → 填入 Base URL、模型名、API Key
3. 如果模型不支持图片输入（比如纯文本模型），取消勾选"支持图片输入"
4. 返回主界面，开始对话

### 可选：安装 Shizuku（Android）

Shizuku 让 FurAgent 获得 ADB 级权限，可以执行 shell 命令、后台启动应用、读取通知。

1. 从 [Shizuku 官网](https://shizuku.rikka.app/) 下载安装
2. 按 Shizuku 指引激活（USB 调试 / 无线调试 / Root 三种方式）
3. 在 FurAgent 权限页申请 Shizuku 权限

**注意**：不装 Shizuku 也可以用，只是 `execute_shell`、`screenshot`、`app(op=running)` 等工具会不可用。

---

## 权限说明

FurAgent 要求一些敏感权限。以下是每一项的**具体用途**：

| 权限 | 用途 | 是否可以拒绝 |
|---|---|---|
| **文件读写** | 读写 `/sdcard` 下的文件（文件工具、截图、下载） | 拒绝则文件工具不可用 |
| **悬浮窗** | 显示悬浮球、后台启动应用 | 拒绝则悬浮助手和定时打开应用不可用 |
| **通知** | 定时任务提醒、工具调用通知 | 拒绝则无通知 |
| **通知使用权** | 读取媒体播放信息、系统通知（`media_session` / `notifications` 工具） | 拒绝则媒体控制和通知读取不可用 |
| **定位** | `get_location` 工具 | 拒绝则定位不可用 |
| **Shizuku** | 执行 shell、后台启动应用 | 拒绝则高级功能不可用 |
| **精确闹钟** | 准点执行定时任务 | 拒绝则任务可能延后 |

**重要**：FurAgent 不做任何路径白名单限制。它有能力读写你设备上的任何文件，就像你自己的终端一样。**这是设计上的选择**——AI 需要和你平等的操作能力，而不是被关在沙盒里。请自己判断风险。

---

## 使用示例

### 示例 1：处理视频

> **你**：把 D 盘那个 4K 视频压缩到 1080p，音频保持原样
>
> **FurAgent**：
> 1. `list_files` 找到视频文件
> 2. `ffmpeg(mode=preset, preset=video_compress, input=..., output=...)`
> 3. 返回压缩后的文件路径和大小

### 示例 2：跨设备操作

> **你（在手机上）**：帮我把电脑上的项目编译一下
>
> **FurAgent**：
> 1. `pc(op=list_tools)` 查看电脑端可用工具
> 2. `pc(op=call, tool=execute_command, args={command: "gradlew build"})`
> 3. 实时显示编译输出
> 4. 返回编译结果

### 示例 3：定时任务

> **你**：每天早上 9 点，如果我在家（连着自己家 WiFi），就把卧室的灯打开
>
> **FurAgent**：
> 1. `schedule(op=create, action=run_tool, tool_name=..., conditions=[{type: wifi_ssid, value: "MyHome"}])`
> 2. 任务已创建

### 示例 4：手机直连传文件

> **你**：把这张照片传给我室友的手机
>
> **FurAgent**（在「设备互联 → 直连传输」里已添加室友手机）：
> 1. 找到照片路径
> 2. 用「直连传输」标签页的"上传"按钮选择
> 3. 文件走局域网 HTTP 直传，不经任何中转

### 示例 5：通过电脑中转

> **你（在手机 A 上）**：把这份 PDF 通过电脑发到我另一台手机
>
> **FurAgent**：
> 1. `pc(op=peers)` 列出在线设备，拿到手机 B 的 client_id
> 2. `pc(op=relay, target=<B_id>, local_path=...)`
> 3. 文件先上传到电脑，电脑再转发给手机 B
> 4. `pc(op=relay_status, relay_id=...)` 查询结果

### 示例 6：MCP 工具

> **你**：用 MCP 的 filesystem 服务器帮我找一下 /tmp 下所有大于 100MB 的文件
>
> **FurAgent**：
> 1. 调用 `mcp__xxx__search_files`
> 2. 返回匹配结果

---

## 常见问题

**Q: FurAgent 收集我的数据吗？**

A: 不收集。所有会话、记忆、配置都存在你自己的设备上。AI 推理通过你自己的 API Key 直连模型服务商，中间没有 FurAgent 的服务器。

**Q: 我用的是国产模型（通义 / 智谱 / Moonshot / 火山），能用吗？**

A: 只要它们提供 OpenAI 兼容接口就能用。在设置里填对应的 Base URL 和模型名即可。火山方舟用 `https://ark.cn-beijing.volces.com/api/v3/chat/completions`。

**Q: 模型不支持图片输入怎么办？**

A: 在设置 → 模型里编辑该模型，取消勾选"支持图片输入"。FurAgent 会自动从工具列表里移除 `screenshot` 和 `view_image`，避免 API 返回 400。

**Q: 一定要装 Shizuku 吗？**

A: 不一定。不装 Shizuku 也能用大部分功能，只是 `execute_shell`、`screenshot`、`app(op=running)` 会不可用。

**Q: Android 和桌面端必须都装吗？**

A: 不是。两端都可以单独使用。只有"电脑互联"功能需要两边都装。

**Q: 我的 API Key 安全吗？**

A: 存储在本地配置文件中，写盘时做了混淆（`SecretCodec`）。**这不是加密**，只是防止肉眼直接看到。如果设备被 root 或恶意软件入侵，Key 可能泄露。使用环境变量模式可以避免把 Key 写进配置文件。

**Q: 为什么我的桌面端控制媒体不管用？**

A: SMTC 需要 Windows 10 1809 或更高，且部分播放器不支持 SMTC 协议。FurAgent 会自动降级到键盘事件模拟。

**Q: WOL 唤醒失败？**

A: 检查四点：
1. 电脑 BIOS 里开启 Wake on LAN
2. 网卡属性里勾选"魔术封包唤醒"
3. **关闭 Windows 快速启动**（最容易漏）
4. 电脑需要有线网卡，且电源线接着

**Q: 直连传输和电脑中转有什么区别？**

A:
- **直连传输**：两台设备直接 HTTP 传文件，不需要任何第三方设备。适合手机 ↔ 手机、电脑 ↔ 手机、电脑 ↔ 电脑。
- **电脑中转（relay）**：手机 A → 电脑 → 手机 B。适合两台设备不在同一局域网、但都能连到电脑的场景。电脑会在文件转发完成后自动清理中转目录。

**Q: 导入电脑导出的备份，为什么模型列表是空的？**

A: 早期版本的字段名不一致导致的。现在已修复，两端的备份格式完全对齐，可以互导。

---

## 技术栈

| 层 | 技术 |
|---|---|
| 语言 | Kotlin 2.4.20 |
| 构建 | Gradle 9.8.0 + AGP 9.4.1 |
| 移动 UI | Jetpack Compose (Material 3) |
| 桌面 UI | Compose Multiplatform 1.12.1 |
| 跨平台共享 | Kotlin Multiplatform |
| 网络 | OkHttp 4.12.0（shared）/ 5.5.0（desktop） + Java-WebSocket 1.6.0 |
| 序列化 | kotlinx.serialization 1.7.3 + org.json |
| HTML 解析 | jsoup 1.23.2（shared） |
| 媒体 | FFmpeg / FFmpegKit |
| 高权限 | Shizuku 13.1.5 |
| 脚本 | Python 3.x |
| 协程 | kotlinx.coroutines |

---

## 项目结构

```
FurAgent/
├── app/                # Android 应用
│   └── src/main/java/net/xinkong/furagent/
│       ├── core/       # Agent 循环、日志、MCP
│       ├── data/       # 会话、设置、记忆、定时任务、直连设备
│       ├── service/    # 前台服务、悬浮服务
│       ├── system/     # Shizuku、TTS、通知、权限、媒体监听
│       ├── tools/      # 平台私有工具
│       └── ui/         # Compose UI
│
├── desktopApp/         # 桌面应用
│   └── src/main/kotlin/net/xinkong/furagent/desktop/
│       ├── bridge/     # WebSocket 服务、HTTP 文件服务
│       ├── core/       # Agent、任务运行时、下载管理、MCP
│       ├── data/       # 会话、设置、直连设备
│       ├── system/     # 日志、通知、TTS、环境检测、SMTC
│       ├── tools/      # 桌面私有工具
│       └── ui/         # Compose Desktop UI
│
└── shared/             # KMP 共享库
    └── src/
        ├── commonMain/     # 纯 Kotlin（协议定义、接口）
        ├── jvmCommonMain/  # JVM 通用（通用工具、路径解析、联网搜索、直连传输、Agent 共用逻辑）
        ├── androidMain/    # Android 平台实现
        └── jvmMain/        # 桌面平台实现
```

---

## 构建

### 环境要求
- JDK 21
- Android SDK（compileSdk 37）
- Gradle 9.8.0

### 构建命令

```bash
./gradlew :desktopApp:run              # 直接运行
./gradlew :desktopApp:packageExe       # 打包成 exe
./gradlew :app:assembleDebug           # 打包 Android debug
./gradlew :app:assembleRelease         # 打包 Android release
```

---

## 已知限制

- **桌面端通知**：目前通过 AWT SystemTray 弹原生通知，某些 Linux 桌面环境可能不支持
- **Android 端 MCP**：只支持 HTTP 传输，不支持 stdio（Android 无法 spawn 子进程）
- **SMTC 媒体控制**：仅 Windows，需要 Windows 10 1809+
- **WOL 唤醒**：仅局域网，跨网段需要路由器支持 VPN / 端口转发
- **文件互传**：base64 通道单文件上限 10MB，大文件走 HTTP 通道
- **Android 端**：只支持 arm64-v8a 架构
- **直连传输**：需要设备在同一局域网，且双方 HTTP 服务都在运行

---

## 与 AI 协作开发（可选）

FurAgent 的代码几乎完全由 AI 编写。为了降低大范围改动的 token 成本，本项目在 `tool/` 目录提供了一个通用补丁应用器 `apply_patch.py`。

**工作流**：

1. AI 把改动写成 `tool/patch.json`
2. 双击 `tool/apply_patch.py`（或命令行 `python tool/apply_patch.py`）
3. 脚本自动备份到 `~/.furagent/temp/patch_backup/`，应用全部改动，全部成功后自动编译

**特性**：

- 原子提交：任一锚点失败全部回滚，不会改一半
- 自动备份：每次运行前备份被改的文件，支持一键回滚
- 自动编译：在 `patch.json` 里声明编译命令即可
- 回滚：`python tool/apply_patch.py --rollback-last`
- 兼容 CRLF / LF / UTF-8 BOM

**patch.json 格式**：

```json
{
  "project_root": "D:/JavaSource/FurAgent",
  "comment": "批次 1：修复 xxx",
  "compile": {
    "enabled": true,
    "command": "gradlew.bat :app:assembleDebug",
    "timeout_sec": 600
  },
  "files": [
    {
      "path": "app/src/main/java/net/xinkong/furagent/.../X.kt",
      "edits": [
        {"old": "原文锚点（必须唯一）", "new": "替换后"}
      ]
    }
  ]
}
```

**子命令**：

```
python tool/apply_patch.py                      # 自动读同目录 patch.json
python tool/apply_patch.py 别的/path.json       # 指定 patch 文件
Get-Clipboard | python tool/apply_patch.py -    # 从剪贴板读
python tool/apply_patch.py --dry-run            # 只校验不写盘
python tool/apply_patch.py --no-compile         # 跳过编译
python tool/apply_patch.py --list               # 列出所有备份
python tool/apply_patch.py --rollback-last      # 回滚最近一次
python tool/apply_patch.py --rollback 20261015_143022
```

详见 `TOAI.MD` §0.5。

---

## 贡献

欢迎提 Issue 和 PR。

---

## 许可证

本项目采用 **[Apache License 2.0](LICENSE)** 许可证。

---

## 致谢

- **[DeepSeek](https://www.deepseek.com/)** —— 本项目主要开发助手，代码生成、重构、审查、文档撰写
- [Shizuku](https://shizuku.rikka.app/) —— 让无 Root 设备也能获得 ADB 级权限
- [FFmpeg](https://ffmpeg.org/) —— 音视频处理基石
- [JetBrains](https://www.jetbrains.com/) —— Kotlin 和 Compose Multiplatform
- [jsoup](https://jsoup.org/) —— HTML 解析
- [Model Context Protocol](https://modelcontextprotocol.io/) —— 让 AI 工具可扩展

---

## 联系方式

- **作者**：是新空啊
- **QQ 群**：529750578 —— [点击加入群聊【FurAgent】](https://qm.qq.com/q/izzWIINmqA)
- **Bilibili**：[@Xinkong](https://space.bilibili.com/454325752)
- **Issues**：[github.com/FurryXinkong/FurAgent/issues](https://github.com/FurryXinkong/FurAgent/issues)