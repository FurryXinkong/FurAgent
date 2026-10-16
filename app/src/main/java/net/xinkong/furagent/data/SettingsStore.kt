package net.xinkong.furagent.data

import androidx.core.content.edit
import net.xinkong.furagent.core.AgentLogger
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
            disableThinking = prefs.getBoolean("disable_thinking", false),
            autoSpeakReplies = prefs.getBoolean("auto_speak_replies", false),
            // 【去掉硬编码上限】用户设多少就多少，只保证 >= 1
            maxToolCallLoops = prefs.getInt("max_tool_call_loops", 20).coerceAtLeast(1),
            notificationMode = prefs.getString("notification_mode", "off") ?: "off",
            notifyTools = notifyTools,
            imageCompress = prefs.getBoolean("image_compress", true),
            imageCompressQuality = prefs.getInt("image_compress_quality", 70).coerceIn(30, 100),
            imageCompressMaxDim = prefs.getInt("image_compress_max_dim", 1280).coerceIn(320, 4096),
            pcBridgeEnabled = prefs.getBoolean("pc_bridge_enabled", false),
            pcBridgeHost = prefs.getString("pc_bridge_host", "") ?: "",
            pcBridgePort = prefs.getInt("pc_bridge_port", 8425).coerceIn(1, 65535),
            pcReceiveDir = prefs.getString("pc_receive_dir", "/sdcard/FurAgent/received")
                ?.takeIf { it.isNotBlank() } ?: "/sdcard/FurAgent/received",
            imageProfileId = prefs.getString("image_profile_id", "") ?: ""
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
                put("supportsVision", it.supportsVision)
                put("temperature", it.temperature)
                put("kind", it.kind)
            })
        }
        prefs.edit {
            putBoolean("confirm_delete", s.requireConfirmDelete)
                .putBoolean("confirm_write", s.requireConfirmWrite)
                .putBoolean("confirm_shell", s.requireConfirmShell)
                .putBoolean("confirm_ffmpeg_overwrite", s.requireConfirmFfmpegOverwrite)
                .putBoolean("enable_memory", s.enableMemory)
                .putString("allowed_paths", s.allowedPaths.joinToString("|"))
                .putString("blocked_paths", s.blockedPaths.joinToString("|"))
                .putString("model_profiles", arr.toString())
                .putString("current_profile_id", s.currentProfileId)
                .putString("custom_system", s.customSystemPrompt)
                .putString("disabled_tools", s.disabledTools.joinToString("|"))
                .putBoolean("disable_thinking", s.disableThinking)
                .putBoolean("auto_speak_replies", s.autoSpeakReplies)
                .putInt("max_tool_call_loops", s.maxToolCallLoops.coerceAtLeast(1))
                .putString("notification_mode", s.notificationMode)
                .putString("notify_tools", s.notifyTools.joinToString("|"))
                .putBoolean("image_compress", s.imageCompress)
                .putInt("image_compress_quality", s.imageCompressQuality.coerceIn(30, 100))
                .putInt("image_compress_max_dim", s.imageCompressMaxDim.coerceIn(320, 4096))
                .putBoolean("pc_bridge_enabled", s.pcBridgeEnabled)
                .putString("pc_bridge_host", s.pcBridgeHost)
                .putInt("pc_bridge_port", s.pcBridgePort.coerceIn(1, 65535))
                .putString("pc_receive_dir", s.pcReceiveDir)
        }
    }
}