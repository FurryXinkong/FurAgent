package net.xinkong.furagent.shared.util

/**
 * API 协议类型。
 *
 * 同一个服务商可能同时提供两种兼容协议（如小米 MiMo 同时支持
 * OpenAI 和 Anthropic 格式），请求体、响应体、鉴权头都不同，
 * 必须显式区分。
 */
object ApiProtocol {
    /** OpenAI Chat Completions 兼容（/chat/completions，Bearer 鉴权） */
    const val OPENAI = "openai"

    /** Anthropic Messages 兼容（/v1/messages，x-api-key + anthropic-version 鉴权） */
    const val ANTHROPIC = "anthropic"

    fun normalize(raw: String): String =
        if (raw.trim().lowercase() == ANTHROPIC) ANTHROPIC else OPENAI

    fun displayName(p: String): String =
        if (normalize(p) == ANTHROPIC) "Anthropic" else "OpenAI"
}

/**
 * Base URL 归一化与模型列表探测。
 *
 */
object ApiUrlResolver {

    /** 已带完整路径的 OpenAI 端点后缀 */
    private const val OPENAI_CHAT_SUFFIX = "/chat/completions"

    /** 已带完整路径的 Anthropic 端点后缀 */
    private const val ANTHROPIC_MSG_SUFFIX = "/v1/messages"

    /**
     * 判断这个 URL 是不是「只填到版本号」的裸 base。
     *
     * 裸 base 的特征：路径最后一段是 v1 / v2 / v3 这类纯版本号，
     * 或者路径为空 / 只有域名，或者以 /anthropic 结尾。
     *
     * 只有裸 base 才需要自动探测模型列表——用户已经填了完整
     * /chat/completions 的，说明他知道自己在干什么，不用打扰。
     */
    fun isBareBase(url: String): Boolean {
        val u = url.trim().trimEnd('/')
        if (u.isBlank()) return false
        if (u.endsWith(OPENAI_CHAT_SUFFIX)) return false
        if (u.endsWith(ANTHROPIC_MSG_SUFFIX)) return false
        if (u.endsWith("/messages")) return false
        if (u.endsWith("/completions")) return false

        val path = pathOf(u)
        if (path.isBlank()) return true
        val last = path.substringAfterLast('/')
        // v1 / v2 / v3 / v1beta / v4 这类版本号
        if (Regex("""^v\d+[a-z0-9]*$""", RegexOption.IGNORE_CASE).matches(last)) return true
        // /anthropic 这种协议根
        if (last.equals("anthropic", ignoreCase = true)) return true
        return false
    }

    /** 取 URL 的路径部分（不含域名和查询串）。 */
    private fun pathOf(url: String): String {
        val noScheme = url.substringAfter("://", url)
        val slash = noScheme.indexOf('/')
        if (slash < 0) return ""
        return noScheme.substring(slash).substringBefore('?').trimEnd('/')
    }

    /**
     * 把用户填的 base 补全成完整的对话请求地址。
     *
     * @param base 用户填的地址（可能是裸 base，也可能已完整）
     * @param protocol [ApiProtocol.OPENAI] 或 [ApiProtocol.ANTHROPIC]
     */
    fun toChatEndpoint(base: String, protocol: String): String {
        val u = base.trim().trimEnd('/')
        if (u.isBlank()) return u

        return if (ApiProtocol.normalize(protocol) == ApiProtocol.ANTHROPIC) {
            when {
                u.endsWith(ANTHROPIC_MSG_SUFFIX) -> u
                u.endsWith("/messages") -> u
                // /anthropic → /anthropic/v1/messages
                u.endsWith("/anthropic", ignoreCase = true) -> "$u/v1/messages"
                // 裸域名或 /v1 → 补 /v1/messages
                else -> "$u/v1/messages"
            }
        } else {
            when {
                u.endsWith(OPENAI_CHAT_SUFFIX) -> u
                u.endsWith("/completions") -> u
                else -> "$u$OPENAI_CHAT_SUFFIX"
            }
        }
    }

    /**
     * 从对话地址反推模型列表地址。
     *
     * OpenAI：{base}/models
     * Anthropic：{base}/v1/models（官方有，很多中转站没有）
     */
    fun toModelsEndpoint(chatEndpoint: String, protocol: String): String {
        val u = chatEndpoint.trim().trimEnd('/')
        return if (ApiProtocol.normalize(protocol) == ApiProtocol.ANTHROPIC) {
            when {
                u.endsWith(ANTHROPIC_MSG_SUFFIX) ->
                    u.substringBeforeLast(ANTHROPIC_MSG_SUFFIX) + "/v1/models"
                u.endsWith("/messages") ->
                    u.substringBeforeLast("/messages") + "/models"
                else -> "$u/models"
            }
        } else {
            when {
                u.endsWith(OPENAI_CHAT_SUFFIX) ->
                    u.substringBeforeLast(OPENAI_CHAT_SUFFIX) + "/models"
                u.endsWith("/completions") ->
                    u.substringBeforeLast("/completions") + "/models"
                else -> "$u/models"
            }
        }
    }

    /**
     * 从模型列表响应里提取模型 id 列表。
     *
     * 兼容几种常见形状：
     *   {"data":[{"id":"mimo-v2.6-pro"},...]}   OpenAI 标准
     *   {"models":[{"name":"...","id":"..."}]}  部分中转站
     *   {"data":["mimo-v2.6-pro", ...]}         少数实现
     */
    fun parseModelIds(json: String): List<String> {
        val ids = LinkedHashSet<String>()
        try {
            val root = org.json.JSONObject(json)
            val arr = root.optJSONArray("data")
                ?: root.optJSONArray("models")
                ?: root.optJSONArray("result")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    when (val item = arr.opt(i)) {
                        is String -> if (item.isNotBlank()) ids.add(item.trim())
                        is org.json.JSONObject -> {
                            val id = item.optString("id", "").ifBlank {
                                item.optString("name", "").ifBlank {
                                    item.optString("model", "")
                                }
                            }
                            if (id.isNotBlank()) ids.add(id.trim())
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        return ids.toList()
    }
}
