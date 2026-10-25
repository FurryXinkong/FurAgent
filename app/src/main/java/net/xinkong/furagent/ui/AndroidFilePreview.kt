package net.xinkong.furagent.ui

import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.xinkong.furagent.shared.skill.SkillStore
import net.xinkong.furagent.shared.tools.ToolResult
import net.xinkong.furagent.system.notifyToast
import net.xinkong.furagent.ui.theme.AppRadius
import org.json.JSONObject
import java.io.File

/**
 * 内置网页预览页：基于系统 WebView（android.webkit）。
 *
 * 地址栏「只要给路径就行」：
 *   - http(s)://…      原样加载
 *   - /sdcard/x.html   本地绝对路径 → file:// 加载（AI 修改产物 HTML 直接预览）
 *   - localhost:8080   本地服务 → http://
 *   - 裸域名           补 https://
 *
 * 硬件返回键：网页有历史先网页后退，否则退出页面。
 */
@Composable
fun AndroidWebViewerPage(url: String, onBack: () -> Unit) {
    val initial = remember(url) { normalizeWebUrl(url) }
    var currentUrl by remember(url) { mutableStateOf(initial) }
    var addressInput by remember(url) { mutableStateOf(initial) }
    var progress by remember { mutableIntStateOf(0) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    fun loadAddress(raw: String) {
        val target = normalizeWebUrl(raw)
        if (target.isBlank()) return
        currentUrl = target
        addressInput = target
        webViewRef.value?.loadUrl(target)
    }

    // 会话里再次点开另一个 html（url 参数变了但页面未重建）时加载新目标
    LaunchedEffect(url) {
        val t = normalizeWebUrl(url)
        if (t.isNotBlank() && t != currentUrl) loadAddress(t)
    }

    BackHandler(enabled = true) {
        val wv = webViewRef.value
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 顶栏：返回 / 地址栏 / 刷新（避开状态栏）
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Row(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        RoundedCornerShape(AppRadius.PILL)
                    )
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = addressInput,
                    onValueChange = { addressInput = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { loadAddress(addressInput) }),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (addressInput.isBlank()) {
                            Text(
                                "输入 URL 或本地 HTML 路径",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        inner()
                    }
                )
            }
            IconButton(onClick = { webViewRef.value?.reload() }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "刷新",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 固定 2dp 占位：进度条若按需插入/移除会让 WebView 高度抖动，
        // 高度一变 Chromium 就重新布局——正是"渲染位置不在常规位置"的来源
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
        ) {
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    // 加载前透明底：让外层深色背景透出，避免 WebView 默认白底在深色 UI 里闪一下
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = true
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    @Suppress("DEPRECATION")
                    settings.displayZoomControls = false
                    // 对齐系统浏览器（MT 等）默认行为：不用 wide viewport / overview 重排，
                    // 完全按页面自己的 viewport meta 渲染——引入它们反而造成布局视口差异
                    settings.useWideViewPort = false
                    settings.loadWithOverviewMode = false
                    // 下载链接交给系统下载服务（否则点了毫无反应）
                    setDownloadListener { url, _, _, _, _ ->
                        try {
                            val req = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                            req.setNotificationVisibility(
                                android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                            )
                            val name = android.webkit.URLUtil.guessFileName(url, null, null) ?: "download.bin"
                            req.setDestinationInExternalPublicDir(
                                android.os.Environment.DIRECTORY_DOWNLOADS,
                                name
                            )
                            val dm = ctx.getSystemService(android.content.Context.DOWNLOAD_SERVICE)
                                    as android.app.DownloadManager
                            dm.enqueue(req)
                            net.xinkong.furagent.system.notifyToast(ctx, "已加入下载：$name")
                        } catch (e: Throwable) {
                            net.xinkong.furagent.system.notifyToast(ctx, "下载失败：${e.message}")
                        }
                    }
                    // 所有站内/外链跳转都留在内置浏览器里
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val scheme = request.url.scheme?.lowercase()
                            if (scheme != null && scheme !in WEB_SCHEMES) {
                                // tel/market/intent 等深链交给系统处理，处理不了就忽略——
                                // 让 WebView 硬吃非网页 scheme 只会白屏
                                try {
                                    view.context.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            request.url
                                        )
                                    )
                                } catch (_: Throwable) { }
                                return true
                            }
                            val u = request.url.toString()
                            currentUrl = u
                            addressInput = u
                            return false
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            if (url != null) {
                                currentUrl = url
                                addressInput = url
                            }
                            if (progress == 0) progress = 5
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            progress = 0
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }

                        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                            consoleMessage?.message()?.let {
                                net.xinkong.furagent.core.AgentLogger.log("WebView", "JS: $it")
                            }
                            return super.onConsoleMessage(consoleMessage)
                        }
                    }
                    webViewRef.value = this
                    if (currentUrl.isNotBlank()) {
                        // 等 attach + 首帧布局完成后再加载：过早 loadUrl 会出现
                        // "动画合成层画了、背景/SVG 等静态层没画"的首帧渲染异常
                        val target = currentUrl
                        post {
                            post {
                                if (target.isNotBlank()) loadUrl(target)
                            }
                        }
                    }
                }
            },
            update = { /* 目标切换走 LaunchedEffect(url) → loadAddress */ },
            onRelease = { wv ->
                wv.stopLoading()
                wv.destroy()
                webViewRef.value = null
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}

/** 允许 WebView 自行加载的 scheme；其余（tel/market/intent…）交系统处理或忽略。 */
private val WEB_SCHEMES = setOf("http", "https", "file", "about", "data")

/** 输入归一化：本地路径 / 裸域名 / localhost 都能直接开。 */
private fun normalizeWebUrl(raw: String): String {
    val t = raw.trim()
    if (t.isBlank()) return ""
    if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("file://")) return t
    // 本地绝对路径：先做百分号编码（保留 /），空格、#、?、中文才不会被当 URL 语法截断
    if (t.startsWith("/")) return "file://" + android.net.Uri.encode(t, "/")
    if (t.startsWith("localhost") || t.matches(Regex("""^[\d.]+(:\d+)?(/.*)?$"""))) return "http://$t"
    return "https://$t"
}

/**
 * 内置文本阅读器：等宽字体、可滚动、可选中复制。
 * 超过 512KB 截断显示前段（AI 修改产物通常是全文可读的规模）。
 */
@Composable
fun AndroidTextFileDialog(path: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var content by remember { mutableStateOf<String?>(null) }
    var truncated by remember { mutableStateOf(false) }
    val fileName = remember(path) { File(path).name }

    LaunchedEffect(path) {
        content = withContext(Dispatchers.IO) {
            try {
                val text = File(path).readText(Charsets.UTF_8)
                if (text.length > 512 * 1024) {
                    truncated = true
                    text.take(512 * 1024)
                } else {
                    text
                }
            } catch (e: Exception) {
                "读取失败：${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    PocoAlertDialog(
        onDismiss = onDismiss,
        title = fileName,
        text = {
            Column {
                Text(
                    path,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                val c = content
                if (c == null) {
                    Text(
                        "加载中…",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp, max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (truncated) {
                            Text(
                                "超过 512KB，仅显示前段",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        SelectionContainer {
                            Text(
                                c,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        },
        confirmText = "关闭",
        onConfirm = onDismiss,
        dismissText = "复制",
        onDismissClick = {
            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("file", content ?: ""))
            notifyToast(context, "已复制")
        }
    )
}

/**
 * 内置 Python 执行器：Chaquopy 运行时（SkillStore.execCode）。
 * 只读执行当前 .py 文件，工作目录 = 文件所在目录，超时 30 秒。
 */
@Composable
fun AndroidPyRunDialog(path: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    val file = remember(path) { File(path) }

    PocoAlertDialog(
        onDismiss = onDismiss,
        title = "运行 Python 脚本",
        text = {
            Column {
                Text(
                    file.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    path,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                val r = result
                if (r == null) {
                    Text(
                        if (running) "执行中…"
                        else "用内置 Python 运行器执行本文件，超时 30 秒，工作目录为文件所在目录。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (r.success) "执行完成" else "执行失败",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (r.success) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                        if (r.output.length > 1000) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "${r.output.length} 字符",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp, max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        SelectionContainer {
                            Text(
                                r.output.ifBlank { "（无输出）" },
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        },
        confirmText = if (result == null) "运行" else "重新运行",
        confirmEnabled = !running,
        onConfirm = {
            running = true
            result = null
            scope.launch(Dispatchers.IO) {
                val r = try {
                    val code = file.readText(Charsets.UTF_8)
                    SkillStore.execCode(
                        code,
                        JSONObject(),
                        file.parent ?: "/",
                        30
                    )
                } catch (e: Exception) {
                    ToolResult.fail("执行异常：${e.javaClass.simpleName}: ${e.message}")
                }
                result = r
                running = false
            }
        },
        dismissText = "关闭",
        onDismissClick = onDismiss
    )
}
