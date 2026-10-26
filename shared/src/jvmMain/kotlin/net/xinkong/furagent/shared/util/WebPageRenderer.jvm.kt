package net.xinkong.furagent.shared.util

/**
 * 桌面端网页渲染器：**已废弃**，直接返回 null。
 *
 * 为什么删掉 JavaFX WebView
 *   1. 它是“半个浏览器”：比 Jsoup 重得多（30MB 原生库 + 启动 JavaFX 工具包），
 *      又比真浏览器（Playwright/Chrome）弱得多（WebKit 版本老，现代 JS/CSS 跟不上）；
 *   2. 线程模型灾难：必须在 JavaFX Application Thread 上操作，逼出一堆
 *      Platform.runLater + CountDownLatch + 超时 + 单例复用的复杂度；
 *   3. 崩溃风险高：原生层（WebKit）崩了 JVM 拦不住，直接进程退出
 *      （典型症状：`deleteFile(const String&) NOT IMPLEMENTED` + run FAILED）；
 *   4. 超时语义模糊：latch 超时后 WebView 仍在后台跑，取消做不到。
 *
 * 现状 桌面端纯 Jsoup 静态抓取（覆盖 90% 场景：新闻/文档/博客/GitHub/维基）。
 * 只有纯前端渲染（SPA/无限滚动）才需要 JS，那类页面后续可考虑调本机 Chrome headless。
 *
 * 保留 expect/actual 结构是因为 Android 端仍用 WebView 实现。
 */
actual suspend fun renderPageHtml(url: String, timeoutMs: Long): String? = null

