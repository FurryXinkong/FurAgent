package net.xinkong.furagent.shared.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.xinkong.furagent.shared.tools.ToolLogger
import okhttp3.*
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * 多源搜索与网页抓取（双端共用）。
 *
 * 搜索回退链（高 → 低优先级）：
 *   360 → 百度 → 必应
 *
 * 【设计原则：简单优先】
 *   只给 UA + Accept + Accept-Language 三个 header，不加 Sec-Fetch-* 等。
 *   不判降级 —— 有结果就返回，避免"查询词没在标题里出现"被误杀。
 */
object WebSearchEngine {

    private val RE_WHITESPACE = Regex("""\s+""")

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private const val ACCEPT_HTML =
        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    private const val ACCEPT_LANG = "zh-CN,zh;q=0.9,en;q=0.8"

    // Cookie 容器
    private val cookieJar = object : CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = store.getOrPut(url.host) { mutableListOf() }
            synchronized(list) {
                for (c in cookies) {
                    list.removeAll { it.name == c.name }
                    list.add(c)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val list = store[url.host] ?: return emptyList()
            return synchronized(list) { list.toList() }
        }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    // 分域名限速
    private val lastRequestAt = ConcurrentHashMap<String, Long>()
    private val hostMutex = ConcurrentHashMap<String, Mutex>()

    private suspend fun throttle(host: String) {
        val mutex = hostMutex.getOrPut(host) { Mutex() }
        mutex.withLock {
            val last = lastRequestAt[host] ?: 0L
            val minGap = 500L + Random.nextLong(0, 500)
            val wait = minGap - (System.currentTimeMillis() - last)
            if (wait > 0) {
                delay(wait.milliseconds)
            }
            lastRequestAt[host] = System.currentTimeMillis()
        }
    }

    // 极简请求
    private fun buildRequest(
        url: String,
        referer: String? = null,
        extra: Map<String, String> = emptyMap()
    ): Request {
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", ACCEPT_HTML)
            .header("Accept-Language", ACCEPT_LANG)
        if (referer != null) b.header("Referer", referer)
        for ((k, v) in extra) b.header(k, v)
        return b.build()
    }

    data class SearchResult(val title: String, val url: String, val snippet: String)

    // 对外入口

    /**
     * @param engine 可选：bing / baidu / 360。为空时按"360 → 百度 → 必应"顺序自动回退。
     */
    suspend fun search(
        query: String,
        page: Int,
        engine: String = ""
    ): String = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext "失败：未提供搜索关键词"

        val e = engine.trim().lowercase(Locale.ROOT)

        if (e.isNotBlank()) {
            return@withContext when (e) {
                "bing", "必应" -> {
                    val r = trySearchBing(query, page)
                    if (!r.isNullOrEmpty()) formatResults("必应", query, page, r)
                    else "搜索失败：必应源不可用（可能触发风控或页面结构变化）"
                }
                "baidu", "百度" -> {
                    val r = trySearchBaidu(query, page)
                    if (!r.isNullOrEmpty()) formatResults("百度", query, page, r)
                    else "搜索失败：百度源不可用"
                }
                "360", "so", "so360" -> {
                    val r = trySearch360(query, page)
                    if (!r.isNullOrEmpty()) formatResults("360", query, page, r)
                    else "搜索失败：360 源不可用"
                }
                else -> "失败：engine 只能是 bing / baidu / 360（不填则自动回退）"
            }
        }

        // 自动回退链：360 → 百度 → 必应
        val so360Results = trySearch360(query, page)
        if (!so360Results.isNullOrEmpty()) {
            ToolLogger.log("SEARCH", "360 命中 ${so360Results.size} 条")
            return@withContext formatResults("360", query, page, so360Results)
        }
        ToolLogger.log("SEARCH", "360 失败，尝试百度")

        val baiduResults = trySearchBaidu(query, page)
        if (!baiduResults.isNullOrEmpty()) {
            ToolLogger.log("SEARCH", "百度命中 ${baiduResults.size} 条")
            return@withContext formatResults("百度", query, page, baiduResults)
        }
        ToolLogger.log("SEARCH", "百度失败，尝试必应")

        val bingResults = trySearchBing(query, page)
        if (!bingResults.isNullOrEmpty()) {
            ToolLogger.log("SEARCH", "必应命中 ${bingResults.size} 条")
            return@withContext formatResults("必应", query, page, bingResults)
        }

        ToolLogger.log("SEARCH", "所有搜索源均失败")
        "搜索失败：所有搜索源（360 / 百度 / 必应）均不可用。" +
                "本次不再重试，请直接告知用户网络搜索暂时不可用。"
    }

    // 调试日志：只在"未提取到结果"时打，避免污染正常日志
    private fun debugHtmlOnFailure(tag: String, html: String) {
        val head = html.take(300).replace(RE_WHITESPACE, " ")
        ToolLogger.log("SEARCH", "[$tag] 未提取到结果，HTML 长度=${html.length} 前 300 字符：$head")
    }

    // 源 1：360 搜索
    private suspend fun trySearch360(query: String, page: Int): List<SearchResult>? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val pn = (page - 1) * 10
            val url = "https://www.so.com/s?q=$encoded&pn=$pn"

            throttle("www.so.com")
            val request = buildRequest(url = url, referer = "https://www.so.com/")

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                ToolLogger.log("SEARCH", "360 HTTP ${response.code}")
                return null
            }
            val html = response.use { it.body.string() }

            if (html.contains("请输入验证码", true) ||
                html.contains("访问验证", true) ||
                html.contains("安全验证", true) ||
                html.contains("captcha", true)
            ) {
                ToolLogger.log("SEARCH", "360 触发安全验证")
                return null
            }

            val doc = Jsoup.parse(html)

            var items = doc.select("li.res-list, div.res-list, li.result, div.result")
            if (items.isEmpty()) {
                items = doc.select("#main .result, #main > div > .res-list")
            }
            if (items.isEmpty()) {
                debugHtmlOnFailure("SO360", html)
                ToolLogger.log("SEARCH", "360 未匹配到结果卡片")
                return null
            }

            val results = mutableListOf<SearchResult>()
            val seenUrls = mutableSetOf<String>()

            for (item in items) {
                if (results.size >= 8) break

                if (item.hasClass("res-list-ad") ||
                    item.attr("data-ad").isNotBlank() ||
                    item.selectFirst("span.ec_ad, div.ec_ad, [data-ad-id]") != null
                ) continue

                val titleEl = item.selectFirst("h3 a") ?: item.selectFirst("h3 > a") ?: continue
                val title = titleEl.text().replace(RE_WHITESPACE, " ").trim()
                if (title.isBlank()) continue

                var link = titleEl.attr("data-mdurl").trim()
                if (link.isBlank()) link = decode360Link(titleEl.attr("href").trim())
                if (link.isBlank() || link in seenUrls) continue
                seenUrls.add(link)

                val desc = (
                        item.selectFirst("p.res-desc") ?:
                        item.selectFirst("div.res-desc") ?:
                        item.selectFirst("span.res-desc") ?:
                        item.selectFirst("div.summary") ?:
                        item.selectFirst("p")
                        )?.text()
                    ?.replace(RE_WHITESPACE, " ")
                    ?.trim()
                    ?.let { if (it.length > 200) it.take(200) + "..." else it }
                    ?: ""

                results.add(SearchResult(title, link, desc))
            }

            if (results.isEmpty()) {
                debugHtmlOnFailure("SO360", html)
                ToolLogger.log("SEARCH", "360 未提取到有效结果（cards=${items.size}）")
                return null
            }
            results
        } catch (e: Exception) {
            ToolLogger.log("ERR", "360 搜索异常：${e.message}")
            null
        }
    }

    private fun decode360Link(href: String): String {
        if (href.isBlank()) return ""

        if (href.startsWith("http://") || href.startsWith("https://")) {
            if (href.contains("so.com/link?")) {
                val idx = href.indexOf("url=")
                if (idx >= 0) {
                    val after = href.substring(idx + 4)
                    val amp = after.indexOf('&')
                    val encoded = if (amp >= 0) after.substring(0, amp) else after
                    return try {
                        URLDecoder.decode(encoded, "UTF-8")
                    } catch (_: Exception) { href }
                }
            }
            return href
        }

        return when {
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> "https://www.so.com$href"
            else -> href
        }
    }

    // 源 2：百度
    private suspend fun trySearchBaidu(query: String, page: Int): List<SearchResult>? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val pn = (page - 1) * 10
            val url = "https://www.baidu.com/s?ie=utf-8&wd=$encoded&pn=$pn"

            throttle("www.baidu.com")
            val request = buildRequest(url = url, referer = "https://www.baidu.com/")

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                ToolLogger.log("SEARCH", "百度 HTTP ${response.code}")
                return null
            }
            val html = response.use { it.body.string() }

            if (html.contains("百度安全验证", true) ||
                html.contains("wappass.baidu.com", true) ||
                html.contains("网络不给力", true)
            ) {
                ToolLogger.log("SEARCH", "百度触发风控")
                return null
            }

            val doc = Jsoup.parse(html)

            var items = doc.select("div.result, div.result-op, div.c-container")
            if (items.isEmpty()) {
                items = doc.select("#content_left > div")
            }
            if (items.isEmpty()) {
                debugHtmlOnFailure("BAIDU", html)
                ToolLogger.log("SEARCH", "百度未匹配到结果卡片")
                return null
            }

            val results = mutableListOf<SearchResult>()
            val seenUrls = mutableSetOf<String>()

            for (item in items) {
                if (results.size >= 8) break

                if (item.attr("data-click").contains("ad") ||
                    item.selectFirst("[data-tuiguang]") != null
                ) continue

                val titleEl = item.selectFirst("h3.t a")
                    ?: item.selectFirst("h3 a")
                    ?: item.selectFirst("h3 > a")
                    ?: continue
                val title = titleEl.text().replace(RE_WHITESPACE, " ").trim()
                if (title.isBlank()) continue

                var link = titleEl.attr("data-landurl").trim()
                if (link.isBlank()) link = decodeBaiduLink(titleEl.attr("href").trim())
                if (link.isBlank() || link in seenUrls) continue
                seenUrls.add(link)

                val desc = (
                        item.selectFirst("div.c-abstract") ?:
                        item.selectFirst("span.c-abstract") ?:
                        item.selectFirst("div.c-span-last") ?:
                        item.selectFirst("div.c-row")
                        )?.text()
                    ?.replace(RE_WHITESPACE, " ")
                    ?.trim()
                    ?.let { if (it.length > 200) it.take(200) + "..." else it }
                    ?: ""

                results.add(SearchResult(title, link, desc))
            }

            if (results.isEmpty()) {
                debugHtmlOnFailure("BAIDU", html)
                ToolLogger.log("SEARCH", "百度未提取到有效结果（cards=${items.size}）")
                return null
            }
            results
        } catch (e: Exception) {
            ToolLogger.log("ERR", "百度搜索异常：${e.message}")
            null
        }
    }

    private fun decodeBaiduLink(href: String): String {
        if (href.isBlank()) return ""
        return when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> "https://www.baidu.com$href"
            else -> href
        }
    }

    // 源 3：必应
    private suspend fun trySearchBing(query: String, page: Int): List<SearchResult>? {
        return try {
            val first = 1 + (page - 1) * 10
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.bing.com/search?q=$encoded&first=$first&count=10"

            throttle("www.bing.com")
            val request = buildRequest(url = url, referer = "https://www.bing.com/")

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                ToolLogger.log("SEARCH", "必应 HTTP ${response.code}")
                return null
            }
            val html = response.use { it.body.string() }

            if (html.contains("captcha", true) || html.contains("Bing 验证", true)) {
                ToolLogger.log("SEARCH", "必应触发人机验证")
                return null
            }

            val doc = Jsoup.parse(html)

            var items = doc.select("li.b_algo")
            if (items.isEmpty()) {
                items = doc.select("#b_results > li.b_algo")
            }
            if (items.isEmpty()) {
                items = doc.select("#b_results > li")
            }
            if (items.isEmpty()) {
                debugHtmlOnFailure("BING", html)
                ToolLogger.log("SEARCH", "必应未匹配到结果卡片")
                return null
            }

            val results = mutableListOf<SearchResult>()
            for (item in items) {
                if (results.size >= 8) break
                val titleEl = item.selectFirst("h2 a") ?: item.selectFirst("h2 > a")
                ?: item.selectFirst("a[href^=http]") ?: continue
                val title = titleEl.text().trim()
                val link = titleEl.attr("href").trim()
                if (title.isBlank() || link.isBlank()) continue

                val desc = (item.selectFirst(".b_caption p")
                    ?: item.selectFirst(".b_caption")
                    ?: item.selectFirst("p"))
                    ?.text()
                    ?.replace(RE_WHITESPACE, " ")
                    ?.trim()
                    ?.let { if (it.length > 200) it.take(200) + "..." else it }
                    ?: ""

                results.add(SearchResult(title, link, desc))
            }

            if (results.isEmpty()) {
                debugHtmlOnFailure("BING", html)
                ToolLogger.log("SEARCH", "必应未提取到有效结果（cards=${items.size}）")
                return null
            }
            results
        } catch (e: Exception) {
            ToolLogger.log("ERR", "必应搜索异常：${e.message}")
            null
        }
    }

    // 结果格式化
    private fun formatResults(
        source: String,
        query: String,
        page: Int,
        results: List<SearchResult>
    ): String {
        val sb = StringBuilder()
        sb.append("搜索源：$source\n")
        sb.append("关键词：$query（第 $page 页）\n\n")
        results.forEachIndexed { i, r ->
            sb.append("${i + 1}. ${r.title}\n")
            sb.append("   链接: ${r.url}\n")
            if (r.snippet.isNotBlank()) sb.append("   摘要: ${r.snippet}\n")
            sb.append("\n")
        }
        sb.append("提示：如需了解某条结果的详细内容，请调用 fetch_web_content 工具并传入对应链接。")
        return sb.toString()
    }

    // 网页正文抓取
    /**
     * 抓取网页正文并转成 Markdown。
     *
     * 策略：
     *   1. 先尝试平台渲染（[renderPageHtml]）——Android 端用 WebView 能拿到 JS 动态内容；
     *      桌面端已废弃渲染，直接返回 null，走静态抓取。
     *   2. 渲染不可用或失败时，回退到静态 HTTP 抓取（OkHttp + Jsoup）。
     *
     * 输出为 Markdown，保留标题 / 列表 / 链接 / 代码块 / 表格等结构，
     * 信息密度高，因此 [maxLength] 只作为安全上限，正常页面不会触发截断。
     */
    suspend fun fetchContent(url: String, maxLength: Int): String = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext "失败：未提供 URL"
        val validUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"

        // ---- 第一级：浏览器渲染 ----
        val renderedHtml: String? = try {
            renderPageHtml(validUrl, RENDER_TIMEOUT_MS)
        } catch (e: Exception) {
            ToolLogger.log("FETCH", "渲染失败，回退静态抓取：${e.message}")
            null
        }

        val html: String = if (!renderedHtml.isNullOrBlank()) {
            ToolLogger.log("FETCH", "渲染成功，HTML 长度=${renderedHtml.length}")
            renderedHtml
        } else {
            // ---- 第二级：静态 HTTP 抓取 ----
            try {
                val host = try { URI(validUrl).host ?: "" } catch (_: Exception) { "" }
                if (host.isNotBlank()) throttle(host)
                val referer = if (host.isNotBlank()) "https://$host/" else null
                val request = buildRequest(url = validUrl, referer = referer)
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    return@withContext "失败：HTTP ${response.code}"
                }
                response.use { it.body.string() }
            } catch (e: Exception) {
                return@withContext "失败：${e.message ?: "网络错误"}"
            }
        }

        if (html.isBlank()) return@withContext "失败：页面内容为空"

        try {
            val safeHtml = html.take(4_000_000)
            val doc = Jsoup.parse(safeHtml, validUrl)
            val title = doc.title().trim()

            // 先备份一份未清理的 body 文本，作为最终兜底
            val rawBodyText = doc.body().text().replace(RE_WHITESPACE, " ").trim()

            HtmlToMarkdown.cleanDocument(doc)
            val root = HtmlToMarkdown.pickContentRoot(doc)
            var markdown = HtmlToMarkdown.convert(root)

            // 多级回退：正文根没内容时，尝试最大文本块
            if (markdown.length < 100) {
                val biggest = HtmlToMarkdown.findLargestTextBlock(doc)
                if (biggest != null) {
                    val alt = HtmlToMarkdown.convert(biggest)
                    if (alt.length > markdown.length) markdown = alt
                }
            }
            // 最终兜底：用未清理的 body 纯文本
            if (markdown.length < 100 && rawBodyText.isNotBlank()) {
                markdown = rawBodyText
            }

            if (markdown.isBlank()) {
                // 调试：把 HTML 结构打到日志，便于定位
                val head = safeHtml.take(3000).replace(RE_WHITESPACE, " ")
                ToolLogger.log(
                    "FETCH",
                    "提取失败。HTML 长度=${safeHtml.length}，body 文本长度=${rawBodyText.length}，" +
                            "前 3000 字符：$head"
                )
                return@withContext "页面获取成功，但无法提取正文"
            }

            val truncated = if (markdown.length > maxLength) {
                markdown.take(maxLength) + "\n\n...[已截断，原文 ${markdown.length} 字符]"
            } else markdown

            buildString {
                if (title.isNotBlank()) append("标题：$title\n")
                append("URL：$validUrl\n")
                append("正文（Markdown）：\n$truncated")
            }
        } catch (e: Exception) {
            "失败：${e.message ?: "解析错误"}"
        }
    }

    /** 浏览器渲染超时（毫秒）。 */
    private const val RENDER_TIMEOUT_MS = 15_000L
}