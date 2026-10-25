package net.xinkong.furagent.data

import androidx.core.content.edit
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.shared.ThinkingLevel
import org.json.JSONArray
import org.json.JSONObject

object SettingsStore {
    fun load(prefs: android.content.SharedPreferences): AppSettings {
        val profilesJson = prefs.getString("model_profiles", "") ?: ""
        val profiles: List<ModelProfile> = if (profilesJson.isBlank()) {
            defaultModelProfiles()
        } else {
            try {
                val arr = JSONArray(profilesJson)
                val list = mutableListOf<ModelProfile>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        ModelProfile(
                            id = o.getString("id"),
                            name = o.getString("name"),
                            apiKey = o.optString("apiKey", ""),
                            baseUrl = o.getString("baseUrl"),
                            model = o.getString("model"),
                            isBuiltIn = o.optBoolean("isBuiltIn", false),
                            supportsDisableThinking = o.optBoolean("supportsDisableThinking", true),
                            supportsThinking = o.optBoolean("supportsThinking", true),
                            supportsVision = o.optBoolean("supportsVision", true),
                            temperature = o.optDouble("temperature", 0.1),
                            kind = o.optString("kind", "chat").ifBlank { "chat" }
                        )
                    )
                }
                if (list.isEmpty()) defaultModelProfiles() else list
            } catch (e: Exception) {
                AgentLogger.log(
                    "ERR",
                    "解析模型配置失败，已回退到默认配置（可能丢失用户已配置的模型）：${e.javaClass.simpleName}: ${e.message}"
                )
                defaultModelProfiles()
            }
        }

        val currentId = prefs.getString("current_profile_id", "")
            ?.takeIf { it.isNotBlank() }
            ?: profiles.firstOrNull()?.id ?: ""

        val disabled = prefs.getString("disabled_tools", "")
            ?.split("|")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

        val notifyTools = prefs.getString("notify_tools", "")
            ?.split("|")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

        return AppSettings(
            requireConfirmDelete = prefs.getBoolean("confirm_delete", true),
            requireConfirmWrite = prefs.getBoolean("confirm_write", true),
            requireConfirmShell = prefs.getBoolean("confirm_shell", true),
            requireConfirmPython = prefs.getBoolean("confirm_python", true),
            requireConfirmFfmpegOverwrite = prefs.getBoolean("confirm_ffmpeg_overwrite", true),
            enableMemory = prefs.getBoolean("enable_memory", true),
            allowedPaths = prefs.getString("allowed_paths", "/sdcard")?.split("|")
                ?: listOf("/sdcard"),
            blockedPaths = prefs.getString("blocked_paths", "/data|/system|/vendor|/proc|/sys")?.split("|")
                ?: listOf("/data", "/system", "/vendor", "/proc", "/sys"),
            profiles = profiles,
            currentProfileId = currentId,
            customSystemPrompt = prefs.getString("custom_system", "") ?: "",
            disabledTools = disabled,
            thinkingLevel = if (prefs.contains("thinking_level"))
                ThinkingLevel.fromId(prefs.getString("thinking_level", null))
            else ThinkingLevel.fromLegacyBoolean(prefs.getBoolean("enable_thinking", false)),
            autoSpeakReplies = prefs.getBoolean("auto_speak_replies", false),
            // 【去掉硬编码上限】用户设多少就多少，只保证 >= 1
            maxToolCallLoops = prefs.getInt("max_tool_call_loops", 20).coerceAtLeast(1),
        contextTokenBudgetK = prefs.getInt("context_token_budget_k", 100).coerceIn(0, 2000),
            notificationMode = prefs.getString("notification_mode", "off") ?: "off",
            notifyTools = notifyTools,
            imageCompress = prefs.getBoolean("image_compress", true),
            imageCompressQuality = prefs.getInt("image_compress_quality", 70).coerceIn(30, 100),
            imageCompressMaxDim = prefs.getInt("image_compress_max_dim", 1280).coerceIn(320, 4096),
            pcBridgeEnabled = prefs.getBoolean("pc_bridge_enabled", false),
            pcBridgeHost = prefs.getString("pc_bridge_host", "") ?: "",
            pcBridgePort = prefs.getInt("pc_bridge_port", 8425).coerceIn(1, 65535),
            pcReceiveDir = run {
                val raw = prefs.getString("pc_receive_dir", "/sdcard/FurAgent/downloads")
                    ?.takeIf { it.isNotBlank() } ?: "/sdcard/FurAgent/downloads"
                // 【一次性迁移 2026-10-19】历史默认值 /sdcard/FurAgent/received 是写错的，
                // 读到旧值时自动改成 downloads/ 并写回 prefs，避免每次启动重复判断。
                // 不迁移目录内容：旧 received/ 里已有的文件由用户自行处理。
                if (raw == "/sdcard/FurAgent/received") {
                    val migrated = "/sdcard/FurAgent/downloads"
                    try {
                        prefs.edit().putString("pc_receive_dir", migrated).apply()
                    } catch (_: Exception) { }
                    AgentLogger.log("SYS", "receiveDir 迁移：received → downloads")
                    migrated
                } else raw
            },
            imageProfileId = prefs.getString("image_profile_id", "") ?: "",
            showTokenBar = prefs.getBoolean("show_token_bar", false),
            showModifiedFiles = prefs.getBoolean("show_modified_files", false),
            enableNewSessionSuggest = prefs.getBoolean("enable_new_session_suggest", false),
            fileBackupEnabled = prefs.getBoolean("file_backup_enabled", true),
            // 【P1-15】隐藏的设置分组：用 | 分隔（与 disabledTools 一致）
            hiddenSettings = prefs.getString("hidden_settings", "")
                ?.split("|")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        )
    }

    fun save(prefs: android.content.SharedPreferences, s: AppSettings) {
        val arr = JSONArray()
        s.profiles.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("apiKey", it.apiKey)
                put("baseUrl", it.baseUrl)
                put("model", it.model)
                put("isBuiltIn", it.isBuiltIn)
                put("supportsDisableThinking", it.supportsDisableThinking)
                put("supportsThinking", it.supportsThinking)
                put("supportsVision", it.supportsVision)
                put("temperature", it.temperature)
                put("kind", it.kind)
            })
        }
        prefs.edit {
            putBoolean("confirm_delete", s.requireConfirmDelete)
                .putBoolean("confirm_write", s.requireConfirmWrite)
                .putBoolean("confirm_shell", s.requireConfirmShell)
                .putBoolean("confirm_python", s.requireConfirmPython)
                .putBoolean("confirm_ffmpeg_overwrite", s.requireConfirmFfmpegOverwrite)
                .putBoolean("enable_memory", s.enableMemory)
                .putString("allowed_paths", s.allowedPaths.joinToString("|"))
                .putString("blocked_paths", s.blockedPaths.joinToString("|"))
                .putString("model_profiles", arr.toString())
                .putString("current_profile_id", s.currentProfileId)
                .putString("custom_system", s.customSystemPrompt)
                .putString("disabled_tools", s.disabledTools.joinToString("|"))
                .putString("thinking_level", s.thinkingLevel.id)
                .putBoolean("auto_speak_replies", s.autoSpeakReplies)
                .putInt("max_tool_call_loops", s.maxToolCallLoops.coerceAtLeast(1))
            .putInt("context_token_budget_k", s.contextTokenBudgetK.coerceIn(0, 2000))
                .putString("notification_mode", s.notificationMode)
                .putString("notify_tools", s.notifyTools.joinToString("|"))
                .putBoolean("image_compress", s.imageCompress)
                .putInt("image_compress_quality", s.imageCompressQuality.coerceIn(30, 100))
                .putInt("image_compress_max_dim", s.imageCompressMaxDim.coerceIn(320, 4096))
                .putBoolean("pc_bridge_enabled", s.pcBridgeEnabled)
                .putString("pc_bridge_host", s.pcBridgeHost)
                .putInt("pc_bridge_port", s.pcBridgePort.coerceIn(1, 65535))
                .putString("pc_receive_dir", s.pcReceiveDir)
                .putString("image_profile_id", s.imageProfileId)
                .putBoolean("show_token_bar", s.showTokenBar)
                .putBoolean("show_modified_files", s.showModifiedFiles)
                .putBoolean("enable_new_session_suggest", s.enableNewSessionSuggest)
                .putBoolean("file_backup_enabled", s.fileBackupEnabled)
                .putString("hidden_settings", s.hiddenSettings.joinToString("|"))
        }
    }
}