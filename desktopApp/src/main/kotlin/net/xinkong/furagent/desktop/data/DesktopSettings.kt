package net.xinkong.furagent.desktop.data

import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.shared.SecretCodec
import net.xinkong.furagent.shared.ThinkingLevel
import net.xinkong.furagent.shared.util.FileRecovery
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface
import java.util.*

data class DesktopModelProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String = "",
    val isBuiltIn: Boolean = false,
    /**
     * 模型类型：
     *  - "chat"  对话模型（默认，走 /chat/completions）
     *  - "image" 文生图模型（走 /images/generations，不参与对话切换）
     */
    val kind: String = "chat",
    val supportsDisableThinking: Boolean = true,
    /**
     * 模型是否支持深度思考。关闭时 ChatPage 输入框不显示「深度思考」开关。
     */
    val supportsThinking: Boolean = true,
    val supportsVision: Boolean = true,
    /**
     * 采样温度。默认 0.1（Agent 场景要稳定调工具，低温更可靠）。
     * 写作/创意场景可调高。
     */
    val temperature: Double = 0.1,
    /** 存在惩罚（-2.0 ~ 2.0）。默认 0.0（不干预）。 */
    val presencePenalty: Double = 0.0,
    /** 频率惩罚（-2.0 ~ 2.0）。默认 0.0（不干预）。 */
    val frequencyPenalty: Double = 0.0
)

data class ModelPreset(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val hint: String = ""
)

val BUILTIN_MODEL_PRESETS: List<ModelPreset> = listOf(
    ModelPreset("deepseek", "DeepSeek", "https://api.deepseek.com/chat/completions", "deepseek-flash", "官方"),
    ModelPreset("ollama", "Ollama", "http://localhost:11434/v1/chat/completions", "qwen2.5:7b", "本地"),
    ModelPreset("oneapi", "自建中转站", "http://localhost:3000/v1/chat/completions", "gpt-4o-mini", "自建")
)

fun defaultModelProfiles(): List<DesktopModelProfile> = listOf(
    DesktopModelProfile(
        id = "default-deepseek",
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/chat/completions",
        model = "deepseek-flash",
        isBuiltIn = true
    )
)

data class DesktopSettings(
    val profiles: List<DesktopModelProfile> = defaultModelProfiles(),
    val currentProfileId: String = "default-deepseek",

    /**
     * 【P1-2】会话摘要专用模型 id。
     * 空串 = 不启用会话摘要。选项来自 profiles。
     */
    val summaryProfileId: String = "",

    /**
     * 【新增】文生图专用模型 id（kind == "image"）。
     * 空串 = 不启用独立生图模型。
     */
    val imageProfileId: String = "",

    val apiKeySource: String = "profile",

    val maxToolCallLoops: Int = 20,
    val maxTurns: Int = 20,

    /**
     * 【上下文预算】历史的 token 上限（单位：千 token，0 = 不限制）。
     *
     * 【为什么需要】maxTurns 只能按轮次数拦，拦不住“工具输出特别大”的会话：
     * 一轮里读十个大文件就能吃掉几万 token。
     * 这里按 token 预算裁「工具过程」（从旧的开始丢，保留人类消息和助手回复），
     * 既大幅压住 token 消耗，又能让模型知道之前大概改了什么。
     */
    val contextTokenBudgetK: Int = 100,

    val systemPromptMode: String = "append",
    val systemPromptAddon: String = "",
    val systemPromptFull: String = "",

    val autoSpeak: Boolean = false,
    val port: Int = 8425,

    /**
     * 思考强度（全局设置，ChatPage 输入框切换）。
     * 四档：关闭 / 快速 / 标准 / 深度。默认关闭。
     */
    val thinkingLevel: ThinkingLevel = ThinkingLevel.DEFAULT,

    val requireConfirmWrite: Boolean = true,
    val requireConfirmDelete: Boolean = true,
    val requireConfirmShell: Boolean = true,
    val requireConfirmPython: Boolean = true,
    /** 【新增】运行自定义脚本（Skill）前是否弹确认框 */
    val requireConfirmSkill: Boolean = true,
    /** 【B13 新增】FFmpeg 输出文件已存在时是否弹覆盖确认 */
    val requireConfirmFfmpegOverwrite: Boolean = true,

    val customToolPaths: Map<String, String> = emptyMap(),

    val receiveDir: String = defaultReceiveDir(),

    val themeMode: String = "system",

    val imageCompress: Boolean = true,
    val imageCompressQuality: Int = 70,
    val imageCompressMaxDim: Int = 1280,

    val notificationsEnabled: Boolean = false,

    /**
     * 【常驻后台】关闭窗口时收进系统托盘，而不是退出进程。
     * 开着时定时任务、手机互联、文件服务继续在后台跑。
     */
    val closeToTray: Boolean = true,

    val disabledTools: Set<String> = emptySet(),

    val hiddenSettings: Set<String> = emptySet(),

    val collapsedGroups: Set<String> = emptySet(),

    val launchOnBoot: Boolean = false,

    val floatingWindowEnabled: Boolean = true,

    val tempCleanupDays: Int = 7,

    /** 页面底部 token 消耗状态栏（轮次 / 工具步数 / 估算 token / 上下文占用） */
    val showTokenBar: Boolean = false,
    /** 输入区上方「本次修改的文件」折叠卡片 */
    val showModifiedFiles: Boolean = false,
    /** 智能体可建议开启新对话（对话里出现「新对话」按钮） */
    val enableNewSessionSuggest: Boolean = false,
    /** 写前备份 + 修改历史记录 + 一键回档 */
    val fileBackupEnabled: Boolean = true
) {
    fun currentProfile(): DesktopModelProfile? =
        profiles.firstOrNull { it.id == currentProfileId } ?: profiles.firstOrNull()

    /** 对话模型列表（kind == "chat"）。 */
    fun chatProfiles(): List<DesktopModelProfile> =
        profiles.filter { it.kind != "image" }

    /** 文生图模型列表（kind == "image"）。 */
    fun imageProfiles(): List<DesktopModelProfile> =
        profiles.filter { it.kind == "image" }

    /** 当前生效的文生图模型：优先取 imageProfileId 选中的，否则取第一个 kind=="image" 的。 */
    fun currentImageProfile(): DesktopModelProfile? =
        profiles.firstOrNull { it.id == imageProfileId && it.kind == "image" }
            ?: imageProfiles().firstOrNull()

    fun toolPath(toolKey: String): String =
        customToolPaths[toolKey]?.takeIf { it.isNotBlank() } ?: ""

    fun save() {
        try {
            val dir = DesktopPaths.configDir()
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "settings.json")
            val o = JSONObject().apply {
                put("profiles", JSONArray().apply {
                    profiles.forEach { p ->
                        put(JSONObject().apply {
                            put("id", p.id)
                            put("name", p.name)
                            put("baseUrl", p.baseUrl)
                            put("model", p.model)
                            put("apiKey", if (p.apiKey.isBlank()) "" else SecretCodec.obfuscate(p.apiKey))
                            put("isBuiltIn", p.isBuiltIn)
                            put("supportsDisableThinking", p.supportsDisableThinking)
                            put("supportsThinking", p.supportsThinking)
                            put("supportsVision", p.supportsVision)
                            put("temperature", p.temperature)
                            put("presencePenalty", p.presencePenalty)
                            put("frequencyPenalty", p.frequencyPenalty)
                            put("kind", p.kind)
                        })
                    }
                })
                put("currentProfileId", currentProfileId)
                put("summaryProfileId", summaryProfileId)
                put("imageProfileId", imageProfileId)
                put("apiKeySource", apiKeySource)
                put("maxToolCallLoops", maxToolCallLoops)
                put("maxTurns", maxTurns)
        put("contextTokenBudgetK", contextTokenBudgetK)
                put("systemPromptMode", systemPromptMode)
                put("systemPromptAddon", systemPromptAddon)
                put("systemPromptFull", systemPromptFull)
                put("autoSpeak", autoSpeak)
                put("thinkingLevel", thinkingLevel.id)
                put("port", port)
                put("requireConfirmWrite", requireConfirmWrite)
                put("requireConfirmDelete", requireConfirmDelete)
                put("requireConfirmShell", requireConfirmShell)
                put("requireConfirmPython", requireConfirmPython)
                put("requireConfirmSkill", requireConfirmSkill)
                put("requireConfirmFfmpegOverwrite", requireConfirmFfmpegOverwrite)
                put("showTokenBar", showTokenBar)
                put("showModifiedFiles", showModifiedFiles)
                put("enableNewSessionSuggest", enableNewSessionSuggest)
                put("fileBackupEnabled", fileBackupEnabled)
                put("customToolPaths", JSONObject().apply {
                    customToolPaths.forEach { (k, v) -> put(k, v) }
                })
                put("receiveDir", receiveDir)
                put("themeMode", themeMode)
                put("imageCompress", imageCompress)
                put("imageCompressQuality", imageCompressQuality)
                put("imageCompressMaxDim", imageCompressMaxDim)
                put("notificationsEnabled", notificationsEnabled)
        put("closeToTray", closeToTray)
                put("disabledTools", JSONArray().apply { disabledTools.forEach { put(it) } })
                put("hiddenSettings", JSONArray().apply { hiddenSettings.forEach { put(it) } })
                put("collapsedGroups", JSONArray().apply { collapsedGroups.forEach { put(it) } })
                put("launchOnBoot", launchOnBoot)
                put("floatingWindowEnabled", floatingWindowEnabled)
                put("tempCleanupDays", tempCleanupDays)
            }
            val tmp = File(dir, "settings.json.tmp")
            tmp.writeText(o.toString(2), Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(o.toString(2), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            DesktopLogger.error(
                "Settings",
                "保存设置失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    companion object {
        private fun safeDeobfuscate(raw: String): String = try {
            if (raw.isBlank()) "" else SecretCodec.deobfuscate(raw)
        } catch (e: Exception) {
            DesktopLogger.warn(
                "Settings",
                "API Key 解密失败，已置空：${e.javaClass.simpleName}: ${e.message}"
            )
            ""
        }

        fun load(): DesktopSettings {
            val f = File(DesktopPaths.configDir(), "settings.json")
            return try {
                if (!f.exists()) return DesktopSettings()
                val o = JSONObject(f.readText(Charsets.UTF_8))

                val profilesArr = o.optJSONArray("profiles")
                val profiles: List<DesktopModelProfile> = if (profilesArr != null) {
                    val list = mutableListOf<DesktopModelProfile>()
                    for (i in 0 until profilesArr.length()) {
                        val p = profilesArr.optJSONObject(i)
                        if (p == null) {
                            DesktopLogger.warn("Settings", "profiles[$i] 不是对象，已跳过")
                            continue
                        }
                        try {
                            list.add(DesktopModelProfile(
                                id = p.optString("id", "profile_$i"),
                                name = p.optString("name", "模型 $i"),
                                baseUrl = p.optString("baseUrl", ""),
                                model = p.optString("model", ""),
                                apiKey = safeDeobfuscate(p.optString("apiKey", "")),
                                isBuiltIn = p.optBoolean("isBuiltIn", false),
                                supportsDisableThinking = p.optBoolean("supportsDisableThinking", true),
                                supportsThinking = p.optBoolean("supportsThinking", true),
                                supportsVision = p.optBoolean("supportsVision", true),
                                temperature = p.optDouble("temperature", 0.1),
                                presencePenalty = p.optDouble("presencePenalty", 0.0),
                                frequencyPenalty = p.optDouble("frequencyPenalty", 0.0),
                                kind = p.optString("kind", "chat").ifBlank { "chat" }
                            ))
                        } catch (e: Exception) {
                            DesktopLogger.warn(
                                "Settings",
                                "profiles[$i] 解析失败，已跳过：${e.javaClass.simpleName}: ${e.message}"
                            )
                        }
                    }
                    if (list.isEmpty()) defaultModelProfiles() else list
                } else {
                    val rawKey = o.optString("apiKey", "")
                    listOf(DesktopModelProfile(
                        id = "default",
                        name = o.optString("model", "默认模型"),
                        baseUrl = o.optString("baseUrl", "https://api.deepseek.com/chat/completions"),
                        model = o.optString("model", "deepseek-chat"),
                        apiKey = safeDeobfuscate(rawKey),
                        isBuiltIn = false
                    ))
                }

                val currentId = o.optString("currentProfileId", "").takeIf { it.isNotBlank() }
                    ?: profiles.firstOrNull()?.id ?: "default-deepseek"

                val customPaths = mutableMapOf<String, String>()
                o.optJSONObject("customToolPaths")?.let { obj ->
                    obj.keys().forEach { k ->
                        val v = obj.optString(k, "").trim()
                        if (v.isNotBlank()) customPaths[k] = v
                    }
                }

                val disabled = mutableSetOf<String>()
                o.optJSONArray("disabledTools")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val n = arr.optString(i, "").trim()
                        if (n.isNotBlank()) disabled.add(n)
                    }
                }
                val hidden = mutableSetOf<String>()
                o.optJSONArray("hiddenSettings")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val n = arr.optString(i, "").trim()
                        if (n.isNotBlank()) hidden.add(n)
                    }
                }
                val collapsed = mutableSetOf<String>()
                o.optJSONArray("collapsedGroups")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val n = arr.optString(i, "").trim()
                        if (n.isNotBlank()) collapsed.add(n)
                    }
                }

                val receiveDirRaw = o.optString("receiveDir", "").trim()
                val receiveDir = receiveDirRaw.ifBlank { defaultReceiveDir() }

                DesktopSettings(
                    profiles = profiles,
                    currentProfileId = currentId,
                    summaryProfileId = o.optString("summaryProfileId", ""),
                    imageProfileId = o.optString("imageProfileId", ""),
                    apiKeySource = o.optString("apiKeySource", "profile")
                        .takeIf { it in listOf("profile", "env") } ?: "profile",
                    maxToolCallLoops = o.optInt("maxToolCallLoops", 20).coerceAtLeast(1),
                    maxTurns = o.optInt("maxTurns", 20).coerceAtLeast(1),
        contextTokenBudgetK = o.optInt("contextTokenBudgetK", 100).coerceIn(0, 2000),
                    systemPromptMode = o.optString("systemPromptMode", "append")
                        .takeIf { it in listOf("append", "replace") } ?: "append",
                    systemPromptAddon = o.optString("systemPromptAddon", ""),
                    systemPromptFull = o.optString("systemPromptFull", ""),
                    autoSpeak = o.optBoolean("autoSpeak", false),
                    thinkingLevel = if (o.has("thinkingLevel"))
                        ThinkingLevel.fromId(o.optString("thinkingLevel", null))
                    else ThinkingLevel.fromLegacyBoolean(o.optBoolean("enableThinking", false)),
                    port = o.optInt("port", 8425).coerceIn(1, 65535),
                    requireConfirmWrite = o.optBoolean("requireConfirmWrite", true),
                    requireConfirmDelete = o.optBoolean("requireConfirmDelete", true),
                    requireConfirmShell = o.optBoolean("requireConfirmShell", true),
                    requireConfirmPython = o.optBoolean("requireConfirmPython", true),
                    requireConfirmSkill = o.optBoolean("requireConfirmSkill", true),
                    requireConfirmFfmpegOverwrite = o.optBoolean("requireConfirmFfmpegOverwrite", true),
                    showTokenBar = o.optBoolean("showTokenBar", false),
                    showModifiedFiles = o.optBoolean("showModifiedFiles", false),
                    enableNewSessionSuggest = o.optBoolean("enableNewSessionSuggest", false),
                    fileBackupEnabled = o.optBoolean("fileBackupEnabled", true),
                    customToolPaths = customPaths,
                    receiveDir = receiveDir,
                    themeMode = o.optString("themeMode", "system"),
                    imageCompress = o.optBoolean("imageCompress", true),
                    imageCompressQuality = o.optInt("imageCompressQuality", 70).coerceIn(30, 100),
                    imageCompressMaxDim = o.optInt("imageCompressMaxDim", 1280).coerceIn(320, 4096),
                    notificationsEnabled = o.optBoolean("notificationsEnabled", false),
            closeToTray = o.optBoolean("closeToTray", true),
                    disabledTools = disabled,
                    hiddenSettings = hidden,
                    collapsedGroups = collapsed,
                    launchOnBoot = o.optBoolean("launchOnBoot", false),
                    floatingWindowEnabled = o.optBoolean("floatingWindowEnabled", true),
                    tempCleanupDays = o.optInt("tempCleanupDays", 7).coerceIn(0, 3650)
                )
            } catch (e: Exception) {
                DesktopLogger.error(
                    "Settings",
                    "读取设置失败，已备份损坏文件：${e.javaClass.simpleName}: ${e.message}"
                )
                FileRecovery.backupCorrupted(f)
                DesktopSettings()
            }
        }
    }
}

object DesktopSettingsHolder {
    @Volatile var current: DesktopSettings = DesktopSettings()
}

object DesktopPaths {
    fun rootDir(): File {
        val home = System.getenv("USERPROFILE")
            ?: System.getenv("HOME")
            ?: System.getProperty("user.home")
        if (!home.isNullOrBlank()) {
            return File(home, ".furagent")
        }
        try {
            val jp = System.getProperty("jpackage.app-path")
            if (!jp.isNullOrBlank()) {
                val exeDir = File(jp).parentFile
                if (exeDir != null) {
                    val parent = exeDir.parentFile ?: exeDir
                    return File(parent, ".furagent")
                }
            }
        } catch (_: Throwable) { }
        return File(".", ".furagent")
    }

    fun configDir(): File {
        val d = File(rootDir(), "config"); if (!d.exists()) d.mkdirs(); return d
    }
    fun sessionDir(): File {
        val d = File(rootDir(), "sessions"); if (!d.exists()) d.mkdirs(); return d
    }
    fun screenshotsDir(): File {
        val d = File(rootDir(), "screenshots"); if (!d.exists()) d.mkdirs(); return d
    }
    fun receivedDir(): File {
        val d = File(rootDir(), "downloads"); if (!d.exists()) d.mkdirs(); return d
    }
    fun tempDir(): File {
        val d = File(rootDir(), "temp"); if (!d.exists()) d.mkdirs(); return d
    }
    fun logsDir(): File {
        val d = File(rootDir(), "logs"); if (!d.exists()) d.mkdirs(); return d
    }
    fun exportsDir(): File {
        val d = File(rootDir(), "exports"); if (!d.exists()) d.mkdirs(); return d
    }
    fun scriptsDir(): File {
        val d = File(rootDir(), "scripts"); if (!d.exists()) d.mkdirs(); return d
    }
    fun importedImagesDir(): File {
        val d = File(rootDir(), "imported_images"); if (!d.exists()) d.mkdirs(); return d
    }
}

object ApiKeyResolver {
    const val ENV_NAME = "FurAgent_APIKEY"
    private const val CONFIG_FILE_NAME = "apikey.txt"

    sealed class Result {
        data class Found(val key: String, val source: String) : Result()
        data class NotFound(val message: String) : Result()
    }

    fun configFile(): File = File(DesktopPaths.configDir(), CONFIG_FILE_NAME)

    fun resolve(settings: DesktopSettings, profile: DesktopModelProfile?): Result {
        return when (settings.apiKeySource) {
            "env" -> {
                val env = try { System.getenv(ENV_NAME) } catch (_: Throwable) { null }
                if (!env.isNullOrBlank()) {
                    return Result.Found(env.trim(), "环境变量 $ENV_NAME")
                }
                val f = configFile()
                if (f.exists()) {
                    try {
                        val line = f.readText(Charsets.UTF_8).lineSequence()
                            .map { it.trim() }
                            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                        if (!line.isNullOrBlank()) {
                            return Result.Found(line, "配置文件 ${f.absolutePath}")
                        }
                    } catch (_: Exception) { }
                }
                Result.NotFound("未识别到环境变量 $ENV_NAME，也未找到 ${f.absolutePath}")
            }
            else -> {
                val k = profile?.apiKey ?: ""
                if (k.isBlank()) Result.NotFound("当前模型未配置 API Key")
                else Result.Found(k, "模型配置")
            }
        }
    }

    fun guideText(): String {
        val cfg = configFile().absolutePath
        return $$"""
API Key 获取方式（二选一）：

方式 1：环境变量
  变量名：$$ENV_NAME
  变量值：你的 API Key

  Windows（永久生效）
    系统属性 -> 高级 -> 环境变量 -> 新建用户变量
      变量名：$$ENV_NAME
      变量值：sk-xxxxxxxx

  Windows（PowerShell 临时）
    $env:$$ENV_NAME = "sk-xxxxxxxx"

  macOS / Linux
    export $$ENV_NAME="sk-xxxxxxxx"
    写入 ~/.zshrc 或 ~/.bashrc 永久生效

方式 2：配置文件
  在下面这个路径新建 apikey.txt：
    $$cfg
  第一行写你的 API Key（以 # 开头的行会被忽略）

设置后需要重启 FurAgent 生效。
        """.trimIndent()
    }
}

private val cachedKnownFolders: Map<String, String> by lazy { computeKnownFolders() }

fun getKnownFolder(name: String): String? = cachedKnownFolders[name.lowercase()]

fun getAllKnownFolders(): Map<String, String> = cachedKnownFolders

fun defaultReceiveDir(): String = DesktopPaths.receivedDir().absolutePath

private fun computeKnownFolders(): Map<String, String> {
    val home = System.getProperty("user.home") ?: return emptyMap()
    val os = System.getProperty("os.name").lowercase(Locale.ROOT)
    val out = mutableMapOf<String, String>()
    out["home"] = home

    fun put(key: String, value: String?) {
        if (!value.isNullOrBlank()) out[key] = value
    }

    when {
        os.contains("win") -> {
            put("desktop", queryWindowsShellFolder("{B4BFCC3A-DB2C-424C-B029-7FE99A87C641}"))
            put("downloads", queryWindowsShellFolder("{374DE290-123F-4565-9164-39C4925E467B}"))
            put("documents", queryWindowsShellFolder("{F42EE2D3-909F-4907-8871-4C22FC0BF756}"))
            put("pictures", queryWindowsShellFolder("{33E28130-4E1E-4676-835A-98395C3BC3BB}"))
            put("music", queryWindowsShellFolder("{4BD8D571-6D19-48D3-BE97-422220080E43}"))
            put("videos", queryWindowsShellFolder("{18989B1D-99B5-455B-841C-AB7C74E4DDFC}"))
        }
        os.contains("mac") -> {
            put("desktop", "$home/Desktop")
            put("downloads", "$home/Downloads")
            put("documents", "$home/Documents")
            put("pictures", "$home/Pictures")
            put("music", "$home/Music")
            put("videos", "$home/Movies")
        }
        else -> {
            put("desktop", queryLinuxXdg("DESKTOP"))
            put("downloads", queryLinuxXdg("DOWNLOAD"))
            put("documents", queryLinuxXdg("DOCUMENTS"))
            put("pictures", queryLinuxXdg("PICTURES"))
            put("music", queryLinuxXdg("MUSIC"))
            put("videos", queryLinuxXdg("VIDEOS"))
        }
    }

    val fallbacks = mapOf(
        "desktop" to "Desktop",
        "downloads" to "Downloads",
        "documents" to "Documents",
        "pictures" to "Pictures",
        "music" to "Music",
        "videos" to "Videos"
    )
    for ((k, sub) in fallbacks) {
        if (out[k] == null) {
            val f = File(home, sub)
            if (f.exists() || k == "downloads") out[k] = f.absolutePath
        }
    }

    return out.toMap()
}

private fun queryWindowsShellFolder(guid: String): String? {
    return try {
        val process = ProcessBuilder(
            "reg", "query",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Shell Folders",
            "/v", guid
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        process.waitFor()
        output.lineSequence()
            .firstOrNull { it.contains("REG_SZ", ignoreCase = true) }
            ?.let { line ->
                val idx = line.indexOf("REG_SZ", ignoreCase = true)
                val v = line.substring(idx + "REG_SZ".length).trim()
                if (v.isNotBlank() && File(v).isAbsolute) v else null
            }
    } catch (_: Exception) { null }
}

private fun queryLinuxXdg(kind: String): String? {
    return try {
        val p = ProcessBuilder("xdg-user-dir", kind).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
        p.waitFor()
        if (output.isNotBlank() && output.startsWith("/") && File(output).exists()) output else null
    } catch (_: Exception) { null }
}

fun getSystemHostname(): String = try {
    java.net.InetAddress.getLocalHost().hostName
} catch (_: Exception) { "PC" }

@Volatile private var cachedMac: String? = null

private val VIRTUAL_NIC_KEYWORDS = listOf(
    "virtualbox", "vmware", "hyper-v", "vethernet",
    "loopback", "tap-", "tun", "docker", "wsl",
    "vmnet", "vbox", "tailscale", "zerotier"
)

private val VIRTUAL_MAC_PREFIXES = listOf(
    "0A:00:27", "08:00:27",
    "00:05:69", "00:0C:29", "00:1C:14", "00:50:56",
    "00:15:5D", "00:03:FF",
    "52:54:00", "00:FF",
    "02:00:4C", "00:1C:42"
)

fun primaryMacAddress(): String {
    cachedMac?.let { return it }
    val mac = try {
        val allNics = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .filter { it.hardwareAddress?.isNotEmpty() == true }

        val afterNameFilter = allNics.filter { nic ->
            val name = nic.name.lowercase(Locale.ROOT)
            VIRTUAL_NIC_KEYWORDS.none { name.contains(it) }
        }

        val afterMacFilter = afterNameFilter.filter { nic ->
            val mac = nic.hardwareAddress?.joinToString(":") { "%02X".format(it) } ?: ""
            VIRTUAL_MAC_PREFIXES.none { mac.startsWith(it, ignoreCase = true) }
        }

        val candidates = afterMacFilter.ifEmpty { afterNameFilter.ifEmpty { allNics } }

        val wired = candidates.firstOrNull { nic ->
            val n = nic.name.lowercase(Locale.ROOT)
            (n.startsWith("eth") || n.startsWith("en") ||
                    n.contains("ethernet") || n.contains("以太网")) &&
                    !n.startsWith("wlan") && !n.startsWith("wl")
        }
        val wifi = candidates.firstOrNull { nic ->
            val n = nic.name.lowercase(Locale.ROOT)
            n.startsWith("wlan") || n.startsWith("wl") ||
                    n == "en0" || n == "en1" ||
                    n.contains("wi-fi") || n.contains("wifi")
        }

        val chosen = wired ?: wifi ?: candidates.firstOrNull()
        chosen?.hardwareAddress?.joinToString(":") { "%02X".format(it) } ?: ""
    } catch (_: Exception) {
        ""
    }
    cachedMac = mac
    return mac
}

private const val AUTOSTART_REG_KEY =
    "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
private const val AUTOSTART_VALUE_NAME = "FurAgent"

fun isAutoStartSupported(): Boolean =
    System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")

fun isAutoStartEnabled(): Boolean {
    return isAutoStartSupported() && try {
        val p = ProcessBuilder("reg", "query", AUTOSTART_REG_KEY, "/v", AUTOSTART_VALUE_NAME)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val finished = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) { p.destroyForcibly(); return false }
        p.exitValue() == 0 && out.contains(AUTOSTART_VALUE_NAME)
    } catch (_: Exception) { false }
}

fun setAutoStartEnabled(enabled: Boolean): Pair<Boolean, String> {
    if (!isAutoStartSupported()) return false to "当前系统暂不支持（仅 Windows）"
    return if (enabled) enableAutoStart() else disableAutoStart()
}

private fun enableAutoStart(): Pair<Boolean, String> {
    val exe = autoStartExecutablePath()
        ?: return false to "无法确定可执行文件路径，请手动把 FurAgent 加到启动项"
    if (!File(exe).exists()) {
        return false to "可执行文件不存在：$exe"
    }
    return try {
        val p = ProcessBuilder(
            "reg", "add", AUTOSTART_REG_KEY,
            "/v", AUTOSTART_VALUE_NAME,
            "/t", "REG_SZ",
            "/d", "\"$exe\"",
            "/f"
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val finished = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) { p.destroyForcibly(); return false to "reg 执行超时" }
        if (p.exitValue() == 0) true to "已启用开机自启动"
        else false to "写入注册表失败：${out.take(200)}"
    } catch (e: Exception) {
        false to "启用失败：${e.message}"
    }
}

private fun disableAutoStart(): Pair<Boolean, String> {
    return try {
        val p = ProcessBuilder("reg", "delete", AUTOSTART_REG_KEY, "/v", AUTOSTART_VALUE_NAME, "/f")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val finished = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) { p.destroyForcibly(); return false to "reg 执行超时" }
        true to "已禁用开机自启动"
    } catch (e: Exception) {
        false to "禁用失败：${e.message}"
    }
}

fun autoStartExecutablePath(): String? {
    try {
        val jp = System.getProperty("jpackage.app-path")
        if (!jp.isNullOrBlank()) return jp
    } catch (_: Throwable) { }
    return try {
        ProcessHandle.current().info().command().orElse(null)
    } catch (_: Throwable) { null }
}