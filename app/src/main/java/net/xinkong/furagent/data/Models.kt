package net.xinkong.furagent.data

import net.xinkong.furagent.shared.ThinkingLevel

/**
 * ChatMessage 抽到 shared/data/ChatMessage.kt。
 * 保留 typealias 让既有 import（ChatMessage）继续有效，
 * 同包内引用（AgentRunner / ChatUI / SessionStore 等）无需改动。
 * 下一批再清理 typealias，改成直接 import shared 的 ChatMessage。
 */
typealias ChatMessage = net.xinkong.furagent.shared.data.ChatMessage
// Kotlin 中 typealias 是包级声明，从原文件删除后同包内仍能解析。
typealias Session = net.xinkong.furagent.shared.data.Session
typealias SessionMeta = net.xinkong.furagent.shared.data.SessionMeta
typealias Workflow = net.xinkong.furagent.shared.data.Workflow
typealias WorkflowStep = net.xinkong.furagent.shared.data.WorkflowStep

data class LogEntry(val timestamp: Long, val type: String, val content: String)

/**
 * Agent 事件。
 *
 * [callId] 仅 type == "tool_call" / "tool_result" 时有效，
 * 用于把 API 返回的原始 tool_call_id 传递到 ChatMessage 里保存。
 */
data class AgentEvent(
    val type: String,
    val content: String,
    val toolName: String = "",
    val callId: String = "",
    /** 深度思考内容，仅 type == "assistant_text" 时携带（用于落库）。 */
    val reasoning: String = "",
    /**
     * 并行批次标记，仅 type == "tool_call" / "tool_result" 时携带。
     * 同一轮并行执行的多个工具共享同一个值；串行为空。
     */
    val parallelGroup: String = ""
)

data class PendingConfirm(val title: String, val message: String)

data class ModelProfile(
    val id: String,
    val name: String,
    val apiKey: String = "",
    val baseUrl: String,
    val model: String,
    val isBuiltIn: Boolean = false,
    val supportsDisableThinking: Boolean = true,
    /**
     * 模型是否支持深度思考。
     * 关闭时 ChatPage 输入框不显示「深度思考」开关按钮。
     */
    val supportsThinking: Boolean = true,
    /**
     * 是否支持图片输入（多模态）。
     * 关闭时：
     *  - screenshot / view_image 工具从 tools 列表移除
     *  - 这两个工具在 ToolRegistry.execute 里二次校验后拒绝
     *  - 用户手动发送图片时直接拒绝并提示
     */
    val supportsVision: Boolean = true,
    /**
     * 采样温度。默认 0.1（Agent 场景要稳定调工具，低温更可靠）。
     * 写作/创意场景可调高。
     */
    val temperature: Double = 0.1,
    /**
     * 存在惩罚（-2.0 ~ 2.0）。正值鼓励模型引入新话题，负值鼓励重复已有内容。
     * 默认 0.0（不干预）。
     */
    val presencePenalty: Double = 0.0,
    /**
     * 频率惩罚（-2.0 ~ 2.0）。正值抑制重复用词，负值鼓励重复。
     * 默认 0.0（不干预）。
     */
    val frequencyPenalty: Double = 0.0,
    /**
     * 模型类型："chat"（对话，默认）/ "image"（文生图）。
     * 文生图模型不参与对话切换，仅用于 generate_image。
     */
    val kind: String = "chat"
)

private val DEFAULT_PROFILES: List<ModelProfile> = listOf(
    ModelProfile(
        "builtin-deepseek", "DeepSeek", "",
        "https://api.deepseek.com/chat/completions", "deepseek-flash",
        isBuiltIn = true
    ),
    ModelProfile(
        "builtin-ollama", "Ollama", "",
        "http://localhost:11434/v1/chat/completions", "qwen2.5:7b",
        isBuiltIn = true
    ),
    ModelProfile(
        "builtin-oneapi", "自建中转站", "",
        "http://localhost:3000/v1/chat/completions", "gpt-4o-mini",
        isBuiltIn = true
    )
)

fun defaultModelProfiles(): List<ModelProfile> = DEFAULT_PROFILES

data class AppSettings(
    val requireConfirmDelete: Boolean = true,
    val requireConfirmWrite: Boolean = true,
    val requireConfirmShell: Boolean = true,
    /**
     * 与桌面端对齐 所有 Python 相关操作前确认：
     * 执行代码（python）、运行脚本（pytool run）、创建/删除脚本。
     *
     * 已删除 requireConfirmSkill 之前拆成两个开关，用户看到两个
     * “权限确认”很困惑，且与桌面端不一致。现在统一由本字段控制。
     */
    val requireConfirmPython: Boolean = true,
    /* FFmpeg 输出文件已存在时是否弹覆盖确认 */
    val requireConfirmFfmpegOverwrite: Boolean = true,
    val enableMemory: Boolean = true,
    val allowedPaths: List<String> = listOf("/sdcard"),
    val blockedPaths: List<String> = listOf("/data", "/system", "/vendor", "/proc", "/sys"),
    val profiles: List<ModelProfile> = DEFAULT_PROFILES,
    val currentProfileId: String = DEFAULT_PROFILES.first().id,
    val customSystemPrompt: String = "",
    val disabledTools: Set<String> = emptySet(),
    /**
     * 思考强度（全局设置，ChatPage 输入框切换）。
     *
     * 四档：关闭 / 快速 / 标准 / 深度。默认关闭。
     * 开启后按厂商加“启用思考”参数，强度按厂商能力降级。
     */
    val thinkingLevel: ThinkingLevel = ThinkingLevel.DEFAULT,
    val autoSpeakReplies: Boolean = false,
    val maxToolCallLoops: Int = 20,
    /**
     * 上下文预算 历史 token 上限（单位：千 token，0 = 不限制）。
     *
     * 为什么需要 maxTurns 只能按轮次拦，拦不住工具输出特别大的会话：
     * 一轮里读十个大文件就能吃掉几万 token。
     * 超预算时从最旧的工具过程开始丢（人类消息和助手回复保留），
     * 既压住 token 消耗，又能让模型知道之前大概改了什么。
     */
    val contextTokenBudgetK: Int = 100,
    val notificationMode: String = "off",
    val notifyTools: Set<String> = emptySet(),

    val pcBridgeEnabled: Boolean = false,
    val pcBridgeHost: String = "",
    val pcBridgePort: Int = 8425,
    val pcReceiveDir: String = "/sdcard/FurAgent/downloads",

    val imageCompress: Boolean = true,
    val imageCompressQuality: Int = 70,
    val imageCompressMaxDim: Int = 1280,

    /**
     * 文生图专用模型 id（kind == "image"）。
     * 空串 = 不启用文生图。
     */
    val imageProfileId: String = "",

    /**
     * 子代理专用模型 id（kind != "image"）。
     *
     * 子代理（run_sub_session）是被派出去干活的：读文件、跑命令、批量改代码。
     * 这类任务量大、轮次多、对推理深度要求不高，用便宜快的模型更划算，
     * 没必要跟主对话抢同一个贵模型。
     *
     * 空串 = 回退到主对话模型（currentProfile），保持旧行为。
     */
    val subAgentProfileId: String = "",

    /** 页面底部 token 消耗状态栏（轮次 / 工具步数 / 估算 token / 上下文占用） */
    val showTokenBar: Boolean = false,
    /** 输入区上方「本次修改的文件」折叠卡片 */
    val showModifiedFiles: Boolean = false,
    /** 智能体可建议开启新对话（对话里出现「新对话」按钮） */
    val enableNewSessionSuggest: Boolean = false,
    /** 写前备份 + 修改历史记录 + 一键回档 */
    val fileBackupEnabled: Boolean = true,

    /**
     * 隐藏的设置分组 key 集合。
     *
     * 与桌面端 DesktopSettings.hiddenSettings 对齐：
     * 用户在「高级」分组里勾选后，对应分组从设置页隐藏。
     * "advanced" 自身不可隐藏（否则用户无法恢复）。
     */
    val hiddenSettings: Set<String> = emptySet()
)

fun AppSettings.currentProfile(): ModelProfile? =
    profiles.firstOrNull { it.id == currentProfileId } ?: profiles.firstOrNull()

/** 所有对话模型（kind != "image"）。 */
fun AppSettings.chatProfiles(): List<ModelProfile> =
    profiles.filter { it.kind != "image" }

/** 所有文生图模型（kind == "image"）。 */
fun AppSettings.imageProfiles(): List<ModelProfile> =
    profiles.filter { it.kind == "image" }

/** 当前生效的文生图模型：优先取 imageProfileId 选中的，否则取第一个 kind=="image" 的。 */
fun AppSettings.currentImageProfile(): ModelProfile? =
    profiles.firstOrNull { it.id == imageProfileId && it.kind == "image" }

/**
 * 当前生效的子代理模型。
 *
 * 优先取 subAgentProfileId 选中的对话模型；没选或选中的已失效时
 * 回退到主对话模型，保证子代理永远能跑起来。
 */
fun AppSettings.currentSubAgentProfile(): ModelProfile? =
    profiles.firstOrNull { it.id == subAgentProfileId && it.kind != "image" }
        ?: currentProfile()
        ?: imageProfiles().firstOrNull()