package net.xinkong.furagent.shared.util

/**
 * API 错误码解释器（双端共用）。
 *
 * 为什么需要
 * 之前两端都是直接把 HTTP 状态码和原始响应体丢给用户：
 *   "API 错误 HTTP 429：{"error":{"message":"..."}}"
 * 用户看不懂，也不知道该干什么。尤其是 429 限流和 503 繁忙，
 * 用户第一反应是"是不是坏了"，其实等一会儿就好。
 *
 * 这里把常见错误码翻译成「原因 + 怎么办」，双端共用同一套文案，
 * 避免两端说法不一致。
 *
 * 设计取舍
 *   - 只做纯函数映射，不依赖任何平台 API，放 shared 模块。
 *   - 保留原始响应体（截断），方便排查非标准错误。
 *   - 不认识的错误码给出通用建议，不硬编。
 */
object ApiErrorInfo {

    /**
     * 错误详情。
     *
     * @param code        HTTP 状态码
     * @param title       一句话标题，如「请求速率达到上限」
     * @param reason      原因说明
     * @param solution    解决办法
     * @param retryable   是否值得重试（UI 可据此显示「重试」按钮）
     * @param retryAfterMs 建议等待时长（毫秒），0 表示无建议
     */
    data class Info(
        val code: Int,
        val title: String,
        val reason: String,
        val solution: String,
        val retryable: Boolean,
        val retryAfterMs: Long = 0L
    )

    /**
     * 根据 HTTP 状态码给出解释。
     *
     * @param code     HTTP 状态码
     * @param rawBody  原始响应体（用于提取服务端的具体错误信息）
     */
    fun explain(code: Int, rawBody: String = ""): Info = when (code) {
        400 -> Info(
            code = 400,
            title = "请求格式错误",
            reason = "请求体格式错误，服务端无法解析。",
            solution = "通常是模型名写错、参数类型不对，或该模型不支持某个参数。" +
                    "请检查「设置 → 模型」里的模型名和接口地址。",
            retryable = false
        )
        401 -> Info(
            code = 401,
            title = "认证失败",
            reason = "API Key 错误或已失效。",
            solution = "请到「设置 → 模型」检查 API Key 是否填写正确、是否已过期。" +
                    "如果 Key 是从别处复制的，注意不要带多余空格。",
            retryable = false
        )
        402 -> Info(
            code = 402,
            title = "余额不足",
            reason = "账号余额不足，无法继续调用。",
            solution = "请到服务商控制台确认账户余额并充值。",
            retryable = false
        )
        403 -> Info(
            code = 403,
            title = "无访问权限",
            reason = "API Key 有效，但没有访问该模型/接口的权限。",
            solution = "请确认该 Key 是否开通了对应模型，或联系服务商开通权限。",
            retryable = false
        )
        404 -> Info(
            code = 404,
            title = "接口不存在",
            reason = "请求的地址或模型不存在。",
            solution = "请检查「设置 → 模型」里的 Base URL 是否完整" +
                    "（通常应以 /v1/chat/completions 结尾），以及模型名是否正确。",
            retryable = false
        )
        408 -> Info(
            code = 408,
            title = "请求超时",
            reason = "服务端等待请求超时。",
            solution = "网络可能不稳定，稍后重试。若持续出现，检查网络或代理设置。",
            retryable = true,
            retryAfterMs = 2000L
        )
        413 -> Info(
            code = 413,
            title = "请求体过大",
            reason = "发送的内容超过了服务端限制。",
            solution = "上下文太长或图片太大。可以新建会话、减少历史消息，或压缩图片后重试。",
            retryable = false
        )
        422 -> Info(
            code = 422,
            title = "参数错误",
            reason = "请求体参数不合法。",
            solution = "通常是某个参数取值超出范围（如 temperature 过大）。" +
                    "请检查「设置 → 模型」里的采样参数。",
            retryable = false
        )
        429 -> {
            // 关键区分 429 有两种完全不同的情况，处理方式相反：
            //   1) 限速（rate limit）：等几秒就好，重试有意义
            //   2) 配额用尽（quota exceeded）：等几秒没用，得等重置或充值
            // 之前混在一起说“稍等片刻再试”，配额用尽时用户白等。
            val quota = detectQuotaExhausted(rawBody)
            if (quota != null) {
                quota
            } else {
                Info(
                    code = 429,
                    title = "请求速率达到上限",
                    reason = "请求速率或并发数超过服务商限制（限速，不是配额用尽）。",
                    solution = "稍等片刻再试。如果经常遇到，可以降低调用频率，" +
                            "或到服务商控制台查看限速规则并升级套餐。",
                    retryable = true,
                    retryAfterMs = 5000L
                )
            }
        }
        500 -> Info(
            code = 500,
            title = "服务器内部故障",
            reason = "服务商服务器内部出错。",
            solution = "这不是你的问题，等待后重试即可。若长时间持续，请联系服务商。",
            retryable = true,
            retryAfterMs = 3000L
        )
        502 -> Info(
            code = 502,
            title = "网关错误",
            reason = "服务商网关无法正常转发请求。",
            solution = "通常是服务端瞬时故障，稍后重试。若用中转站，也可能是中转站本身的问题。",
            retryable = true,
            retryAfterMs = 3000L
        )
        503 -> Info(
            code = 503,
            title = "服务器繁忙",
            reason = "服务端负载过高，暂时无法处理请求。",
            solution = "稍后重试即可。高峰期（如工作日白天）更容易遇到。",
            retryable = true,
            retryAfterMs = 5000L
        )
        504 -> Info(
            code = 504,
            title = "网关超时",
            reason = "服务端处理超时。",
            solution = "可能是请求太复杂或服务端繁忙，稍后重试。",
            retryable = true,
            retryAfterMs = 3000L
        )
        else -> Info(
            code = code,
            title = if (code in 500..599) "服务端错误" else "请求失败",
            reason = "服务端返回了未预期的状态码。",
            solution = if (code in 500..599) "稍后重试。若持续出现，请联系服务商。"
            else "请检查模型配置，或把下面的原始错误信息发给服务商。",
            retryable = code in 500..599
        )
    }

    /**
     * 生成给用户看的简短文案（单行）。
     *
     * 为什么这么短
     * 错误信息现在渲染成状态小气泡（与「重试中」同款），不是 AI 聊天气泡。
     * 小气泡里塞一大段「原因/怎么办」很拥挤，而且用户真正需要知道的
     * 就是「什么错」+「是不是配额用尽」。详细排查信息看日志即可。
     *
     * 格式：`HTTP 429 · 用量配额已用尽`
     */
    fun format(code: Int, rawBody: String = "", attempt: Int = 0): String {
        val info = explain(code, rawBody)
        return "HTTP $code · ${info.title}"
    }

    /**
     * 生成详细文案（Markdown，含原因/怎么办/原始信息）。
     * 供日志、详情弹窗等需要完整信息的场景使用。
     */
    fun formatDetailed(code: Int, rawBody: String = "", attempt: Int = 0): String {
        val info = explain(code, rawBody)
        val sb = StringBuilder()
        sb.append("**HTTP $code · ${info.title}**\n\n")
        sb.append("**原因**：${info.reason}\n\n")
        sb.append("**怎么办**：${info.solution}")

        // 只有真的重试过才提重试次数（配额用尽不重试，提了反而误导）
        if (attempt > 0 && info.retryable) {
            sb.append("\n\n已自动重试 $attempt 次，仍然失败。")
        }

        val detail = extractServerMessage(rawBody)
        if (detail.isNotBlank()) {
            sb.append("\n\n---\n服务端原始信息：\n```\n")
            sb.append(detail.take(500))
            sb.append("\n```")
        }
        return sb.toString()
    }

    /**
     * 识别 429 到底是「配额用尽」还是「限速」。
     *
     * 返回 null 表示是普通限速；返回非 null 表示配额用尽，附带原因和解决办法。
     *
     * 识别依据
     *   - 服务端 code 字段：AccountQuotaExceeded / InsufficientQuota / QuotaExceeded 等
     *   - 文案关键词：quota / 配额 / 余额 / 额度 / 用量 / 充值 / 升级套餐
     *   - 重置时间：reset at / 重置 / 恢复
     */
    private fun detectQuotaExhausted(rawBody: String): Info? {
        if (rawBody.isBlank()) return null
        val lower = rawBody.lowercase()

        // 1) 服务端错误码（最可靠）
        val codeHit = listOf(
            "accountquotaexceeded", "insufficientquota", "quotaexceeded",
            "insufficient_quota", "quota_exceeded", "billing_hard_limit_reached",
            "insufficient_balance", "arrearage"
        ).any { lower.contains(it) }

        // 2) 文案关键词
        val textHit = listOf(
            "quota", "配额", "额度", "余额", "用量", "充值", "升级套餐",
            "exceeded your current quota", "billing"
        ).any { lower.contains(it) }

        if (!codeHit && !textHit) return null

        // 尝试提取重置时间，告诉用户什么时候能恢复
        val resetHint = extractResetTime(rawBody)

        val reason = if (codeHit) {
            "账号的用量配额已用尽（不是限速，重试无用）。"
        } else {
            "账号的用量配额或余额不足（不是限速，重试无用）。"
        }

        val solution = buildString {
            append("需要等配额重置，或充值/升级套餐后才能继续。")
            if (resetHint != null) {
                append("\n\n服务端提示重置时间：")
                append(resetHint)
            }
        }

        return Info(
            code = 429,
            title = "用量配额已用尽",
            reason = reason,
            solution = solution,
            retryable = false
        )
    }

    /**
     * 从响应体里提取重置时间（如 "reset at 2026-10-18 21:13:56 +0800 CST"）。
     * 提取不到返回 null。
     */
    private fun extractResetTime(rawBody: String): String? {
        val patterns = listOf(
            Regex("reset\\s+at\\s+([0-9]{4}-[0-9]{2}-[0-9]{2}[^.,;\"]*)", RegexOption.IGNORE_CASE),
            Regex("(?:重置|恢复)(?:时间|于)?[:：]?\\s*([0-9]{4}-[0-9]{2}-[0-9]{2}[^.,;\"]*)"),
            Regex("([0-9]{4}-[0-9]{2}-[0-9]{2}\\s+[0-9]{2}:[0-9]{2}:[0-9]{2}[^.,;\"]*)")
        )
        for (p in patterns) {
            val m = p.find(rawBody) ?: continue
            val t = m.groupValues.getOrNull(1)?.trim()
            if (!t.isNullOrBlank()) return t
        }
        return null
    }

    /**
     * 从响应体里提取服务端的具体错误信息。
     *
     * 兼容几种常见格式：
     *   {"error":{"message":"..."}}
     *   {"message":"..."}
     *   {"error":"..."}
     * 提取不到就返回原始文本（截断）。
     */
    fun extractServerMessage(rawBody: String): String {
        val body = rawBody.trim()
        if (body.isEmpty()) return ""
        return try {
            val o = org.json.JSONObject(body)
            // {"error":{"message":"...","code":"..."}}
            o.optJSONObject("error")?.let { e ->
                val msg = e.optString("message", "")
                val code = e.optString("code", "")
                if (msg.isNotBlank()) {
                    return if (code.isNotBlank()) "[$code] $msg" else msg
                }
            }
            // {"message":"..."}
            val msg = o.optString("message", "")
            if (msg.isNotBlank()) return msg
            // {"error":"..."}
            val err = o.optString("error", "")
            if (err.isNotBlank()) return err
            body.take(500)
        } catch (_: Exception) {
            // 不是 JSON（可能是 HTML 错误页），原样返回截断
            body.take(500)
        }
    }

    /**
     * 重试中的提示文案（用于 onThinking 实时显示）。
     *
     * @param code     HTTP 状态码
     * @param attempt  第几次重试（1 起）
     * @param maxAttempts 总尝试次数
     * @param waitMs   本次等待毫秒
     */
    fun retryHint(code: Int, attempt: Int, maxAttempts: Int, waitMs: Long): String {
        val info = explain(code)
        val secs = (waitMs / 1000.0)
        val secsText = if (secs >= 1) "${secs.toInt()}s" else "${waitMs}ms"
        return "${info.title}（HTTP $code），${secsText} 后重试（$attempt/$maxAttempts）…"
    }
}
