package net.xinkong.furagent.data

import java.util.*

/**
 * 【冗余清理 Batch 8】ChatMessage 抽到 shared/data/ChatMessage.kt。
 * 保留 typealias 让既有 import（ChatMessage）继续有效，
 * 同包内引用（AgentRunner / ChatUI / SessionStore 等）无需改动。
 * 下一批再清理 typealias，改成直接 import shared 的 ChatMessage。
 */
typealias ChatMessage = net.xinkong.furagent.shared.data.ChatMessage
// 【冗余清理 Batch 13】以下 typealias 从 SessionStore / WorkflowStore 集中到这里。
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
    val callId: String = ""
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
     * 模型类型："chat"（对话，默认）/ "image"（文生图）。
     * 文生图模型不参与对话切换，仅用于 generate_image。
     */
    val kind: String = "chat"
)

private val DEFAULT_PROFILES: List<ModelProfile> = listOf(
    ModelProfile(
        "builtin-deepseek", "DeepSeek", "",
        "https://api.deepseek.com/chat/completions", "deepseek-chat",
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
    /** 【B13 新增】FFmpeg 输出文件已存在时是否弹覆盖确认 */
    val requireConfirmFfmpegOverwrite: Boolean = true,
    val enableMemory: Boolean = true,
    val allowedPaths: List<String> = listOf("/sdcard"),
    val blockedPaths: List<String> = listOf("/data", "/system", "/vendor", "/proc", "/sys"),
    val profiles: List<ModelProfile> = DEFAULT_PROFILES,
    val currentProfileId: String = DEFAULT_PROFILES.first().id,
    val customSystemPrompt: String = "",
    val disabledTools: Set<String> = emptySet(),
    val disableThinking: Boolean = false,
    val autoSpeakReplies: Boolean = false,
    val maxToolCallLoops: Int = 20,
    val notificationMode: String = "off",
    val notifyTools: Set<String> = emptySet(),

    val pcBridgeEnabled: Boolean = false,
    val pcBridgeHost: String = "",
    val pcBridgePort: Int = 8425,
    val pcReceiveDir: String = "/sdcard/FurAgent/received",

    val imageCompress: Boolean = true,
    val imageCompressQuality: Int = 70,
    val imageCompressMaxDim: Int = 1280,

    /**
     * 文生图专用模型 id（kind == "image"）。
     * 空串 = 不启用文生图。
     */
    val imageProfileId: String = ""
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
        ?: imageProfiles().firstOrNull()