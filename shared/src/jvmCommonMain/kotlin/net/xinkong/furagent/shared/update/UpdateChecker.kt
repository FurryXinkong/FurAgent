package net.xinkong.furagent.shared.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 检查更新 —— 双端共用。
 *
 * 数据源是 GitHub Releases：
 *   GET https://api.github.com/repos/{owner}/{repo}/releases/latest
 *
 * 返回的 tag_name 就是版本号。仓库转 public 后匿名请求即可，
 * 不需要内置 token（token 随安装包分发等于公开，没有意义）。
 *
 * 设计约束
 *   - 全部是 suspend 函数，内部切 Dispatchers.IO，调用方直接 launch 就不会卡主线程
 *   - 任何异常都吞掉返回 Result.failure，静默检查不该因为网络问题打扰用户
 *   - 超时 6 秒，避免弱网下挂太久
 */
object UpdateChecker {

    const val OWNER = "FurryXinkong"
    const val REPO = "FurAgent"

    /** 项目主页（没检测到更新时 GitHub 按钮跳这里） */
    const val PROJECT_URL = "https://github.com/$OWNER/$REPO"

    /** 更新页（检测到更新时 GitHub 按钮跳这里） */
    const val RELEASES_URL = "https://github.com/$OWNER/$REPO/releases/latest"

    /** QQ 群邀请链接，双端「关于我们」共用 */
    const val QQ_GROUP_URL = "https://qm.qq.com/q/izzWIINmqA"

    /** 检查结果缓存时长：6 小时内不重复请求，避免每次冷启动都打 GitHub */
    private const val CACHE_TTL_MS = 6L * 60 * 60 * 1000

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** 进程内缓存，双端各自持有一份 */
    @Volatile
    private var cached: UpdateInfo? = null

    @Volatile
    private var cachedAt: Long = 0L

    /**
     * 检查更新。
     *
     * @param currentVersion 本地版本号。Android 传 versionName（yyyyMMddHHmm），
     *                       桌面传 version（yy.mm.build）。两者都是纯数字分段，
     *                       可以直接按段比较大小。
     * @param force true 时忽略缓存，强制重新请求（用户手动点「检查更新」时用）
     */
    suspend fun check(currentVersion: String, force: Boolean = false): Result<UpdateInfo> =
        withContext(Dispatchers.IO) {
            // 缓存命中且未过期
            if (!force) {
                val c = cached
                if (c != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
                    return@withContext Result.success(c.copy(currentVersion = currentVersion))
                }
            }

            try {
                val url = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "FurAgent-UpdateChecker")
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        // 404 = 还没有任何 Release，属于正常状态，不算错误
                        if (resp.code == 404) {
                            val info = UpdateInfo(
                                hasUpdate = false,
                                latestVersion = currentVersion,
                                currentVersion = currentVersion,
                                releaseNotes = "",
                                downloadUrl = null,
                                htmlUrl = PROJECT_URL
                            )
                            cached = info
                            cachedAt = System.currentTimeMillis()
                            return@withContext Result.success(info)
                        }
                        return@withContext Result.failure(
                            IllegalStateException("HTTP ${resp.code}")
                        )
                    }

                    val body = resp.body.string()
                    val json = JSONObject(body)
                    val tag = json.optString("tag_name").trim()
                    val latest = normalizeVersion(tag)
                    val notes = json.optString("body").trim()
                    val htmlUrl = json.optString("html_url").ifBlank { RELEASES_URL }

                    // 优先找 .apk（Android）/ .exe 或 .msi（桌面），找不到就退回更新页
                    var downloadUrl: String? = null
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val a = assets.optJSONObject(i) ?: continue
                            val name = a.optString("name").lowercase()
                            if (name.endsWith(".apk") || name.endsWith(".exe") || name.endsWith(".msi")) {
                                downloadUrl = a.optString("browser_download_url").ifBlank { null }
                                if (downloadUrl != null) break
                            }
                        }
                    }

                    val info = UpdateInfo(
                        hasUpdate = compareVersion(latest, currentVersion) > 0,
                        latestVersion = latest,
                        currentVersion = currentVersion,
                        releaseNotes = notes,
                        downloadUrl = downloadUrl,
                        htmlUrl = htmlUrl
                    )
                    cached = info
                    cachedAt = System.currentTimeMillis()
                    Result.success(info)
                }
            } catch (e: Exception) {
                // 静默失败：网络不通、超时、JSON 解析失败都走这里
                Result.failure(e)
            }
        }

    /** 清掉缓存，测试用 */
    fun clearCache() {
        cached = null
        cachedAt = 0L
    }

    /**
     * 去掉 tag 前缀。GitHub 上常见 v1.2.3 / release-1.2.3 这类写法，
     * 统一剥成纯数字分段再比较。
     */
    private fun normalizeVersion(raw: String): String {
        val s = raw.trim().removePrefix("v").removePrefix("V")
        // 只保留数字和点，其余字符（如 -beta）截断
        val sb = StringBuilder()
        for (ch in s) {
            if (ch.isDigit() || ch == '.') sb.append(ch) else break
        }
        return sb.toString().trimEnd('.')
    }

    /**
     * 按数字分段比较版本号。
     *
     * 返回 >0 表示 a 比 b 新，<0 表示旧，0 表示相同。
     * 段数不同时短的一方补 0（1.2 == 1.2.0）。
     */
    fun compareVersion(a: String, b: String): Int {
        val pa = normalizeVersion(a).split('.').map { it.toIntOrNull() ?: 0 }
        val pb = normalizeVersion(b).split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}

/**
 * 一次检查的结果。
 *
 * @param hasUpdate 是否有比本地更新的版本
 * @param latestVersion 远端最新版本号（已剥掉 v 前缀）
 * @param currentVersion 本地版本号
 * @param releaseNotes Release 说明正文（Markdown）
 * @param downloadUrl 直链下载地址，没有附件时为 null
 * @param htmlUrl Release 页面地址
 */
data class UpdateInfo(
    val hasUpdate: Boolean,
    val latestVersion: String,
    val currentVersion: String,
    val releaseNotes: String,
    val downloadUrl: String?,
    val htmlUrl: String
)
