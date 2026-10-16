package net.xinkong.furagent.shared.tools

/**
 * 工具日志桥。
 *
 * 问题：shared 里的工具（CommonTools 等）无法直接引用
 * Android 的 AgentLogger 或桌面的 DesktopLogger（模块依赖方向不允许）。
 *
 * 方案：shared 只提供一个 hook 机制，两端在 App 启动时注册实现。
 * 之后 shared 里任何地方都可以通过 ToolLogger.log(tag, msg) 打日志，
 * 自动流到对应平台的日志系统。
 *
 * 未注册时 fallback 到 println，不丢。
 */
object ToolLogger {

    @Volatile
    private var hook: ((String, String) -> Unit)? = null

    fun setHook(h: ((String, String) -> Unit)?) {
        hook = h
    }

    fun log(tag: String, message: String) {
        val h = hook
        if (h != null) {
            try {
                h(tag, message)
                return
            } catch (_: Throwable) { }
        }
        // 兜底
        try {
            println("[$tag] $message")
        } catch (_: Throwable) { }
    }
}