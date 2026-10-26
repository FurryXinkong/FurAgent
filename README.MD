# FurAgent

> **这是一个作者自用的个人项目。**
> FurAgent 不是商业产品，也不是面向大众的成熟软件。它的功能是按作者自己的使用习惯长出来的，代码里有一些临时方案和已知瑕疵，界面和接口也可能随时改动。开源出来是希望给同样想折腾本地 Agent 的人一个参考，欢迎在 Issue 里反馈问题，但请**不要按生产级软件的预期来要求它**。

[![Platform](https://img.shields.io/badge/platform-Android%2011%2B%20%7C%20Windows%2010%2B-blue.svg)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-7F52FF.svg)](#)
[![Compose](https://img.shields.io/badge/Compose-1.12.1-4285F4.svg)](#)
[![License](https://img.shields.io/badge/license-Apache%202.0-green.svg)](LICENSE)

FurAgent 是一个基于 Kotlin Multiplatform (KMP) 开发的、跑在你自己设备上的 Tool-Using Agent。它不只停留在对话层面，而是能实际调用系统 API 操作你的手机和电脑。

它不依赖云端沙盒：所有推理通过你自己的 API Key 直连大模型，所有数据（会话、记忆、配置、文件）都完全存储在你的本地设备上。

## 核心特性

### 跨端协同与网络通信
- 手机与电脑在同一局域网下双向通信、工具互调。
- 文件传输采用 HTTP 流式通道，支持断点续传、多线程分片下载，应用层不设文件大小限制（实际受磁盘和网络条件约束）。
- 手机端可通过 Wake-on-LAN (WOL) 远程唤醒休眠中的电脑。

### 执行能力与工具系统
- **分层工具系统**：核心工具常驻内存，其余工具通过 `tool_search` / `tool_load` 动态加载，节省上下文 Token。
- **原生高性能工具**：HTTP 并发请求、CSS Selector 网页内容提取、文件末端读取、多文件批量写入、命令执行输出正则过滤。
- **Android 端 Python 环境**：集成 Chaquopy，预置 `numpy`、`pillow` 等常用科学计算与图像处理库，支持按需执行定制脚本（受 Android 平台与依赖限制）。
- **子代理系统**：可派生独立上下文的子会话，并行执行最多 4 个子任务，结果自动回流主会话。
- **工作流与定时任务**：支持多工具串联的工作流；支持基于 cron、时间或环境条件（电量、网络状态）触发的秒级定时任务。
- **长期记忆与代码编辑**：自动提取并跨会话注入长期记忆；支持行级 patch 的文件修改历史与一键回档。

### 平台深度集成
- **Android**：基于无障碍服务 (Accessibility Service) 直接读取与操作屏幕 UI 树；通过 Shizuku 调用 ADB 级权限；媒体播放控制与通知捕获。
- **桌面端 (Windows)**：基于 UI Automation (UIA) 的窗口控制；系统级媒体控制 (SMTC) 与 TTS；纯本地文档生成（Word / Excel / PPT）。

## 平台能力对比

| 能力 | Android | Windows |
|---|---|---|
| 模型直连 (BYOK) | 支持 | 支持 |
| 局域网互联 / 文件传输 | 支持 | 支持 |
| Wake-on-LAN | 支持 | — |
| 屏幕 UI 操作 | 无障碍服务 | UIA |
| ADB 级权限 | Shizuku | — |
| Python 脚本 | Chaquopy | 见构建说明 |
| 媒体控制 / TTS | 媒体控制、通知捕获 | SMTC、TTS |
| 文档生成 | — | Word / Excel / PPT |

## 环境要求

- **Android 端**：Android 11 (API 30) 及以上，设备架构需为 `arm64-v8a`。
- **桌面端**：Windows 10 (1809) 及以上，内嵌精简版 JRE，无需自行安装 Java。

## 快速开始

### 1. 获取模型 API

FurAgent 采用 BYOK (Bring Your Own Key) 模式。你需要准备一个兼容 OpenAI 规范的接口（如 DeepSeek、Ollama、火山方舟等）的 `Base URL`、`Model Name` 和 `API Key`。

### 2. 安装

- **Android**：安装编译好的 APK，依次授予通知、无障碍、悬浮窗、本地网络等权限（应用内对每项权限的用途有说明），然后在设置页填入模型配置。
- **桌面端**：运行打包后的 exe，在设置中填入模型配置。若需与手机互联，确保两端在同一局域网，并在手机端填入电脑的局域网 IP。

### 3. 从源码构建

确保构建环境已安装 JDK 21 和 Android SDK (compileSdk 37)。

```bash
git clone https://github.com/FurryXinkong/FurAgent.git
cd FurAgent

# 编译 Android Debug 版本（输出在 app/build/outputs/apk/debug/）
./gradlew :app:assembleDebug

# 编译并启动桌面端
./gradlew :desktopApp:run

# 打包桌面端安装包（输出在 desktopApp/build/compose/binaries/）
./gradlew :desktopApp:packageExe
```

## 权限说明（Android）

| 权限 | 用途 | 风险提示 |
|---|---|---|
| 通知 | 前台服务、任务状态提示 | 低 |
| 无障碍服务 | 读取 UI 树、模拟点击 | **高**，可读取屏幕内容 |
| 悬浮窗 | 悬浮入口 / 状态展示 | 中 |
| 本地网络 | 与桌面端互联、文件传输 | 中 |
| Shizuku | 调用 ADB 级权限 | **高**，接近系统级操作 |
| 通知捕获 / 媒体控制 | 读取通知、控制播放 | 中 |

## 安全与隐私

- **数据本地化**：会话、记忆、配置、文件均存储在本地设备。
- **网络请求**：除你主动配置的模型 API 请求，以及局域网内的设备互联外，不包含云端跟踪或数据回传逻辑。
- **API Key**：按当前实现存储在本地配置中，请自行评估设备安全；不建议在共享设备上使用。
- **高权限风险**：无障碍、Shizuku、命令执行、文件写入都属于高权限能力，请只在你信任的设备上授予，并注意模型自动调用工具时可能产生的副作用。
- **定时任务 / 子代理**：可能在你不在场时自动执行工具，建议先从低风险任务开始试用。

## 已知限制

作为自用项目，以下问题作者清楚但未必会优先处理：

- 界面与交互未做系统性的打磨和适配。
- 部分错误处理较粗糙，异常时可能需要看日志排查。
- 两端功能不完全对称，部分工具只在单一平台可用。
- 文档可能落后于代码。
- 未做多用户、多设备并发场景的充分测试。

## FAQ

**Q：可以把它当日常助手用吗？**
A：可以，但请自行承担风险。作者自己就是这么用的，但这不等于它适合所有人。

**Q：为什么有些功能看起来半成品？**
A：因为作者只在需要时实现自己用得到的那部分。

**Q：会接受 PR 吗？**
A：欢迎，但合并与否取决于是否符合作者的使用场景和代码风格。

**Q：支持其他模型 / 平台吗？**
A：只要兼容 OpenAI 规范且支持 Tool Calling 的接口理论上都能接；平台层面目前只覆盖 Android 和 Windows。

## 联系方式

- **作者**：是新空啊
- **QQ 群**：529750578 —— [点击加入群聊【FurAgent】](https://qm.qq.com/q/izzWIINmqA)
- **Bilibili**：[@Xinkong](https://space.bilibili.com/454325752)
- **Issues**：[github.com/FurryXinkong/FurAgent/issues](https://github.com/FurryXinkong/FurAgent/issues)

## 开源协议

本项目基于 **Apache License 2.0** 开源，许可证全文见 [LICENSE](LICENSE)。你可以随时审查代码或自行编译分发。

### 第三方资源

本项目内置的 **MiSans 字体**（`shared/src/commonMain/composeResources/font/misans_vf.ttf`）由小米科技有限责任公司开发，面向全球免费商用，**不受本项目 Apache-2.0 约束**，其权利归小米所有。根据小米官方要求，使用方应在软件中注明使用了 MiSans 字体——详见 [NOTICE](NOTICE)。

其它第三方组件与完整声明见 [NOTICE](NOTICE)。
