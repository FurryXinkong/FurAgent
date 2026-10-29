package net.xinkong.furagent.shared.util

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.shared.tools.ToolLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Android 端网页渲染器：基于 WebView。
 *
 * 实现要点：
 *   - WebView 必须在主线程创建与操作；
 *   - 用 WebViewClient.onPageFinished 判定加载完成；
 *   - 加载完成后用 evaluateJavascript 取 document.documentElement.outerHTML；
 *   - 全程带超时，超时/异常返回 null（调用方回退静态抓取）。
 */
actual suspend fun renderPageHtml(url: String, timeoutMs: Long): String? = withContext(Dispatchers.Main) {
    val context = AndroidContextHolder.get()
    if (context == null) {
        ToolLogger.log("FETCH", "Android Context 未注入，跳过 WebView 渲染")
        return@withContext null
    }

    try {
        renderWithWebView(context, url, timeoutMs)
    } catch (e: Exception) {
        // 用 Exception 而非 Throwable：避免吞 OutOfMemoryError / StackOverflowError，
        // 那会让 JVM / ART 处于不确定状态。
        ToolLogger.log("FETCH", "WebView 渲染异常：${e.message}")
        null
    }
}

@SuppressLint("SetJavaScriptEnabled")
private suspend fun renderWithWebView(
    context: android.content.Context,
    url: String,
    timeoutMs: Long
): String? {
    val htmlRef = AtomicReference<String?>(null)
    val latch = CountDownLatch(1)
    val webView = WebView(context)

    return try {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString =
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/130.0.0.0 Mobile Safari/537.36"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // 页面加载完成，延迟一小段等 JS 渲染，再取 DOM
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        view?.evaluateJavascript(
                            "(function(){try{return document.documentElement.outerHTML;}catch(e){return '';}})()"
                        ) { result ->
                            val html = decodeJsString(result)
                            if (!html.isNullOrBlank()) htmlRef.set(html)
                            latch.countDown()
                        }
                    } catch (_: Throwable) {
                        latch.countDown()
                    }
                }, 800L)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? = null
        }

        webView.loadUrl(url)

        // 等待加载完成（在 Main 线程上阻塞会死锁，因此用 withContext(IO) 等待）
        val ok = withContext(Dispatchers.IO) {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        }
        if (!ok) {
            ToolLogger.log("FETCH", "WebView 渲染超时（${timeoutMs}ms）")
        }

        htmlRef.get()?.takeIf { it.isNotBlank() }
    } finally {
        try {
            webView.stopLoading()
            webView.destroy()
        } catch (_: Throwable) { }
    }
}

/** evaluateJavascript 返回的是 JSON 字符串（带引号、转义），需要解码。 */
private fun decodeJsString(raw: String?): String? {
    if (raw.isNullOrBlank() || raw == "null") return null
    return try {
        org.json.JSONTokener(raw).nextValue() as? String
    } catch (_: Throwable) {
        raw.trim('"')
    }
}
