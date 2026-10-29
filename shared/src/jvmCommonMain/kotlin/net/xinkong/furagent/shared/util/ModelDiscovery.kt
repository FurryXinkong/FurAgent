package net.xinkong.furagent.shared.util

/**
 * 模型列表自动发现。
 *
 * 场景：用户在设置里只填了 `https://api.xiaomimimo.com/v1` 这种裸 base，
 * 没填模型名。这时自动请求 `{base}/models` 拿到可用模型列表，
 * 弹窗让用户确认后批量添加。
 *
 * 设计要点：
 *  - 探测不需要 API Key（很多服务商的 /models 是公开的），
 *    但带上 Key 也无妨，能拿到更完整的列表
 *  - 探测失败不报错，静默跳过（用户可能填的是中转站，没实现 /models）
 *  - 只对「裸 base」触发，用户填了完整 /chat/completions 的不打扰
 */
object ModelDiscovery {

    /** 探测结果。 */
    data class Result(
        val ok: Boolean,
        /** 探测到的模型 id 列表 */
        val modelIds: List<String> = emptyList(),
        /** 实际请求的 URL（用于展示/排查） */
        val probeUrl: String = "",
        /** 失败原因 */
        val error: String = ""
    )

    /**
     * 探测某个 base 下的可用模型。
     *
     * @param baseUrl 用户填的地址（裸 base 或完整地址都行）
     * @param protocol "openai" / "anthropic"
     * @param apiKey 可选，带上能拿到更完整的列表
     * @param httpGet 由调用方注入的 HTTP GET（两端 OkHttp 实例不同）
     */
    suspend fun discover(
        baseUrl: String,
        protocol: String,
        apiKey: String,
        httpGet: suspend (url: String, headers: Map<String, String>) -> Pair<Int, String>
    ): Result {
        val chatEndpoint = ApiUrlResolver.toChatEndpoint(baseUrl, protocol)
        val modelsUrl = ApiUrlResolver.toModelsEndpoint(chatEndpoint, protocol)

        val headers = mutableMapOf<String, String>()
        if (apiKey.isNotBlank()) {
            if (ApiProtocol.normalize(protocol) == ApiProtocol.ANTHROPIC) {
                headers["x-api-key"] = apiKey
                headers["anthropic-version"] = AnthropicCompat.API_VERSION
            } else {
                headers["Authorization"] = "Bearer $apiKey"
            }
        }

        return try {
            val (code, body) = httpGet(modelsUrl, headers)
            if (code !in 200..299) {
                return Result(
                    ok = false,
                    probeUrl = modelsUrl,
                    error = "HTTP $code"
                )
            }
            val ids = ApiUrlResolver.parseModelIds(body)
            if (ids.isEmpty()) {
                Result(
                    ok = false,
                    probeUrl = modelsUrl,
                    error = "接口返回了数据，但没解析出模型列表"
                )
            } else {
                Result(ok = true, modelIds = ids, probeUrl = modelsUrl)
            }
        } catch (e: Exception) {
            Result(
                ok = false,
                probeUrl = modelsUrl,
                error = "${e.javaClass.simpleName}: ${e.message ?: "未知错误"}"
            )
        }
    }

    /**
     * 根据探测结果批量生成模型配置。
     *
     * 命名规则：`{服务商名} · {模型id}`，避免多个服务商同名模型混淆。
     * 显示名取模型 id 本身（用户能一眼看出是哪个模型）。
     *
     * @param baseUrl 用户填的原始 base
     * @param protocol 协议
     * @param apiKey API Key
     * @param modelIds 探测到的模型 id
     * @param makeId 生成唯一 id 的回调（两端 UUID 实现不同）
     * @param providerName 服务商显示名（从域名推断）
     */
    fun buildProfiles(
        baseUrl: String,
        protocol: String,
        apiKey: String,
        modelIds: List<String>,
        makeId: (String) -> String,
        providerName: String = ""
    ): List<Map<String, String>> {
        val chatEndpoint = ApiUrlResolver.toChatEndpoint(baseUrl, protocol)
        val prefix = providerName.ifBlank { hostOf(baseUrl) }
        return modelIds.map { mid ->
            mapOf(
                "id" to makeId(mid),
                "name" to if (prefix.isBlank()) mid else "$prefix · $mid",
                "baseUrl" to chatEndpoint,
                "model" to mid,
                "apiKey" to apiKey,
                "protocol" to ApiProtocol.normalize(protocol)
            )
        }
    }

    /** 从 URL 里取域名作为服务商名。 */
    fun hostOf(url: String): String {
        val noScheme = url.trim().substringAfter("://", url.trim())
        val host = noScheme.substringBefore('/').substringBefore('?')
        // api.xiaomimimo.com → xiaomimimo
        return host.removePrefix("api.").substringBefore('.').ifBlank { host }
    }

    /**
     * 从模型 id 推断模型能力，用于自动配置开关。
     *
     * 保守策略：默认都开，只在明确知道不支持时关掉。
     *  - 语音模型（asr/tts）不是对话模型，跳过
     *  - 生图模型（image/dall-e/flux/sd）标记为 kind=image
     */
    fun inferKind(modelId: String): String {
        val id = modelId.lowercase()
        val imageKeywords = listOf("dall-e", "flux", "stable-diffusion", "sd-", "seedream", "image")
        if (imageKeywords.any { id.contains(it) }) return "image"
        return "chat"
    }

    /** 判断是不是非对话模型（语音识别/合成等），这类不该加进对话列表。 */
    fun isNonChatModel(modelId: String): Boolean {
        val id = modelId.lowercase()
        val nonChat = listOf("-asr", "-tts", "whisper", "voiceclone", "voicedesign", "embedding", "rerank")
        return nonChat.any { id.contains(it) }
    }
}
