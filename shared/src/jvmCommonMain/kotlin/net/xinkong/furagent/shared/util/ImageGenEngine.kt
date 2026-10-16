package net.xinkong.furagent.shared.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 跨端文生图引擎（桌面端 + 手机端共用）。
 *
 * 只依赖 OkHttp + org.json + java.io，**不依赖任何平台 UI / 设置存储**。
 * 调用方负责：从设置里取出 baseUrl / model / apiKey，以及保存路径解析。
 *
 * 走 OpenAI 兼容的 `POST {baseUrl}/images/generations` 接口：
 *   请求：{"model":"...","prompt":"...","size":"1024x1024","n":1}
 *   返回：{"data":[{"url":"..."}]} 或 {"data":[{"b64_json":"..."}]}
 */
object ImageGenEngine {

    /**
     * 生图结果。
     *
     * @param ok            是否成功
     * @param message       失败原因（ok=true 时为空）
     * @param bytes         图片字节数
     * @param revisedPrompt 模型改写后的提示词（可能为空）
     */
    data class Result(
        val ok: Boolean,
        val message: String = "",
        val bytes: Long = 0L,
        val revisedPrompt: String = ""
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)   // 生图较慢，给足时间
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 推导生图接口地址：
     *   填了完整 endpoint（含 /images/generations）→ 直接用
     *   否则当作 base，拼上 /images/generations
     */
    fun resolveImageUrl(baseUrl: String): String? {
        val explicit = baseUrl.trim()
        if (explicit.isBlank()) return null
        if (explicit.contains("/images/generations")) return explicit
        return explicit.trimEnd('/') + "/images/generations"
    }

    /**
     * 生成图片并保存到 target。
     *
     * @param baseUrl  生图接口地址（或可推导的 base）
     * @param model    模型名
     * @param apiKey   API Key
     * @param prompt   提示词
     * @param size     尺寸，如 1024x1024
     * @param target   保存目标文件（父目录自动创建）
     */
    suspend fun generate(
        baseUrl: String,
        model: String,
        apiKey: String,
        prompt: String,
        size: String,
        target: File
    ): Result = withContext(Dispatchers.IO) {
        try {
            val url = resolveImageUrl(baseUrl)
                ?: return@withContext Result(false, "无法推导生图接口地址，请检查「接口地址」")

            val bodyJson = JSONObject().apply {
                put("model", model)
                put("prompt", prompt)
                put("size", size)
                put("n", 1)
            }.toString()

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                val respBody = resp.body.string()
                if (!resp.isSuccessful) {
                    return@withContext Result(false, "生图接口返回 ${resp.code}：${respBody.take(500)}")
                }
                val root = try {
                    JSONObject(respBody)
                } catch (_: Exception) {
                    return@withContext Result(false, "生图响应解析失败：${respBody.take(300)}")
                }
                val data = root.optJSONArray("data")
                if (data == null || data.length() == 0) {
                    return@withContext Result(false, "生图响应无 data：${respBody.take(300)}")
                }
                val first = data.optJSONObject(0)
                    ?: return@withContext Result(false, "生图响应 data[0] 为空")

                target.parentFile?.mkdirs()

                val b64 = first.optString("b64_json", "")
                if (b64.isNotBlank()) {
                    val bytes = Base64.getDecoder().decode(b64)
                    target.writeBytes(bytes)
                } else {
                    val imgUrl = first.optString("url", "")
                    if (imgUrl.isBlank()) {
                        return@withContext Result(false, "生图响应既无 url 也无 b64_json")
                    }
                    val imgReq = Request.Builder().url(imgUrl).build()
                    client.newCall(imgReq).execute().use { imgResp ->
                        if (!imgResp.isSuccessful) {
                            return@withContext Result(false, "下载生成图失败：HTTP ${imgResp.code}")
                        }
                        target.writeBytes(imgResp.body.bytes())
                    }
                }

                Result(
                    ok = true,
                    bytes = target.length(),
                    revisedPrompt = first.optString("revised_prompt", "")
                )
            }
        } catch (e: Exception) {
            Result(false, "生图失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
