package net.xinkong.furagent.shared.util

/**
 * 分平台网页渲染器：用真实浏览器内核加载页面（执行 JS），返回渲染后的 HTML。
 *
 * - 桌面端（jvmMain）：**已废弃**，直接返回 null（纯 Jsoup 静态抓取）。
 *   原因：JavaFX WebView 体积大、线程模型复杂、原生层崩溃风险高（详见 jvm 实现注释）。
 * - Android 端（androidMain）：WebView。
 *
 * 约定：
 *   - 返回 null 表示「渲染不可用 / 失败 / 超时」，调用方应回退到静态 HTTP 抓取。
 *   - 实现必须自行处理超时，不得无限阻塞。
 *   - 实现必须线程安全（可能被并发调用）。
 */
expect suspend fun renderPageHtml(url: String, timeoutMs: Long): String?
