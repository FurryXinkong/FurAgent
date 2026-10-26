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

    /**
     * 平台标识。决定从 Release 附件里挑哪种安装包。
     *
     * 一个 Release 同时挂 apk 和 exe，如果只按"第一个匹配的扩展名"取，
     * 两端都会命中排在前面的 apk。必须按平台精确过滤。
     */
    enum class Platform(val extensions: List<String>) {
        ANDROID(listOf(".apk")),
        DESKTOP(listOf(".exe", ".msi"))
    }

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
     * @param currentVersion 本地版本号。双端统一为 yy.mm.build（如 26.10.24119），
     *                       纯数字分段，可以直接按段比较大小。
     * @param force true 时忽略缓存，强制重新请求（用户手动点「检查更新」时用）
     */
    suspend fun check(
        currentVersion: String,
        platform: Platform,
        force: Boolean = false
    ): Result<UpdateInfo> =
        withContext(Dispatchers.IO) {
            // 缓存命中且未过期
            if (!force) {
                val c = cached
                if (c != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
                    return@withContext Result.success(
                        c.copy(
                            currentVersion = currentVersion,
                            downloadUrl = pickAsset(c.assets, platform)
                        )
                    )
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
                                htmlUrl = PROJECT_URL,
                                assets = emptyList()
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

                    // 收集所有附件（名称 + 直链），再按平台挑。
                    // 不能边遍历边 break：一个 Release 同时挂 apk 和 exe，
                    // 谁排在前面就命中谁，桌面端会下到安卓包。
                    val assets = mutableListOf<Asset>()
                    json.optJSONArray("assets")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val a = arr.optJSONObject(i) ?: continue
                            val name = a.optString("name")
                            val url = a.optString("browser_download_url")
                            if (name.isNotBlank() && url.isNotBlank()) {
                                assets += Asset(name, url)
                            }
                        }
                    }

                    val info = UpdateInfo(
                        hasUpdate = compareVersion(latest, currentVersion) > 0,
                        latestVersion = latest,
                        currentVersion = currentVersion,
                        releaseNotes = notes,
                        downloadUrl = pickAsset(assets, platform),
                        htmlUrl = htmlUrl,
                        assets = assets
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

    /**
     * 从附件列表里挑出当前平台该下的那个。
     *
     * 只认本平台的扩展名，避免安卓包被桌面端命中。
     * 找不到返回 null，调用方退回跳 Release 页面。
     */
    private fun pickAsset(assets: List<Asset>, platform: Platform): String? =
        assets.firstOrNull { a ->
            val lower = a.name.lowercase()
            platform.extensions.any { lower.endsWith(it) }
        }?.url

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
    /** 当前平台对应的安装包直链，没有则为 null */
    val downloadUrl: String?,
    val htmlUrl: String,
    /** Release 的全部附件，缓存命中时用它重新按平台挑 */
    val assets: List<Asset> = emptyList()
)

/** Release 附件：文件名 + 下载直链 */
data class Asset(val name: String, val url: String)
