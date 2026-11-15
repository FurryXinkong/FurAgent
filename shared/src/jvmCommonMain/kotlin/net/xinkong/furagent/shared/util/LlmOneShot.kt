package net.xinkong.furagent.shared.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 一次性（非流式）模型调用。
 *
 * 用途：压缩摘要、日记补全、标题生成这类「后台小请求」。
 * 与主对话的区别：
 *   - 非流式，一次拿到完整文本；
 *   - 不带 tools，避免模型去做工具调用；
 *   - 强制关闭深度思考（推理内容会把 max_tokens 吃光，正文变空）；
 *   - 独立超时，失败返回 null，调用方自己决定降级方案。
 *
 * 双端共用，避免 Android / Desktop 各写一份协议转换。
 */
data class OneShotConfig(
    /** 完整端点 URL（含 /chat/completions 或 /v1/messages） */
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** ApiProtocol.OPENAI / ANTHROPIC */
    val protocol: String = ApiProtocol.OPENAI,
    val maxTokens: Int = 2000,
    val temperature: Double = 0.2,
    val timeoutSeconds: Long = 60,
    /** 是否尝试关闭深度思考（仅 OpenAI 协议有效，Anthropic 忽略） */
    val disableThinking: Boolean = true
) {
    val usable: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

object LlmOneShot {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 发一次非流式补全。
     *
     * @param system 系统提示词；为空则只发 user
     * @return 正文文本；失败/空返回 null（**不抛异常**，后台任务不该炸主流程）
     */
    suspend fun complete(
        cfg: OneShotConfig,
        system: String,
        user: String
    ): String? {
        if (!cfg.usable) return null
        return try {
            withContext(Dispatchers.IO) { runBlockingCall(cfg, system, user) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }

    private fun runBlockingCall(cfg: OneShotConfig, system: String, user: String): String? {
        val isAnthropic = ApiProtocol.normalize(cfg.protocol) == ApiProtocol.ANTHROPIC
        val messages = JSONArray().apply {
            if (system.isNotBlank()) put(JSONObject().apply {
                put("role", "system")
                put("content", system)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", user)
            })
        }

        val body = if (isAnthropic) {
            AnthropicCompat.buildRequestBody(
                model = cfg.model,
                openAiMessages = messages,
                openAiTools = JSONArray(),
                temperature = cfg.temperature,
                maxTokens = cfg.maxTokens,
                stream = false
            )
        } else {
            JSONObject().apply {
                put("model", cfg.model)
                put("messages", messages)
                put("temperature", cfg.temperature)
                put("stream", false)
                put("max_tokens", cfg.maxTokens)
            }.also { b ->
                if (cfg.disableThinking) applyDisableThinking(b, cfg.baseUrl)
            }
        }

        val builder = Request.Builder()
            .url(cfg.baseUrl)
            .addHeader("Content-Type", "application/json")
        if (isAnthropic) {
            builder.addHeader("x-api-key", cfg.apiKey)
            builder.addHeader("anthropic-version", AnthropicCompat.API_VERSION)
        } else {
            builder.addHeader("Authorization", "Bearer ${cfg.apiKey}")
        }
        val request = builder
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val text = resp.body.string()
            val content = if (isAnthropic) {
                AnthropicCompat.parseNonStreamResponse(text).first
            } else {
                val msg = JSONObject(text).optJSONArray("choices")
                    ?.optJSONObject(0)?.optJSONObject("message")
                val c = msg?.optString("content", "").orEmpty()
                if (c.isBlank()) msg?.optString("reasoning_content", "").orEmpty() else c
            }
            return content.trim().ifBlank { null }
        }
    }
}
