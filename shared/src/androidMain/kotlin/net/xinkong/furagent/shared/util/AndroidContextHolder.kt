package net.xinkong.furagent.shared.util

import android.content.Context

/**
 * Android 端 Context 提供者。
 *
 * shared 模块不能直接依赖 app 模块，因此由 app 在启动时（Application.onCreate
 * 或 MainActivity.onCreate）调用 [init] 注入 applicationContext。
 *
 * WebView 渲染需要 Context，若未注入则渲染直接返回 null（回退静态抓取）。
 */
object AndroidContextHolder {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun get(): Context? = appContext
}
