package net.xinkong.furagent.system

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import net.xinkong.furagent.core.AgentLogger

/**
 * Android 17（API 37）本地网络保护（Local Network Protection）适配。
 *
 * 背景：从 targetSdk 37 开始，访问局域网需要 ACCESS_LOCAL_NETWORK 运行时权限。
 * 受影响的常见操作包括：
 *   - 建立出站 TCP 连接（桥接 ws://PC:8425）
 *   - 接受入站 TCP 连接（本机 HTTP 文件服务 :8426）
 *   - 收发 UDP 单播/多播/广播（WOL）
 *
 * 该限制在**网络栈深处**实现，对 OkHttp 等所有网络库一视同仁。未授权时
 * 数据包被**静默丢弃**，所以客户端表现为「连接超时」而不是「连接被拒绝」。
 *
 * targetSdk < 37 时系统对 INTERNET 权限隐式授予该权限，因此本工具在
 * 低版本上恒视为已授权，不做任何弹窗。
 */
object LocalNetworkPermission {

    /** 用字符串字面量，避免低版本编译目标 SDK 缺少该常量导致编译失败 */
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** 与 MainActivity 中 POST_NOTIFICATIONS 的请求码（2001）错开 */
    const val REQUEST_CODE = 2002

    /** 该权限仅 API 37+ 需要 */
    fun isRequired(): Boolean = Build.VERSION.SDK_INT >= 37

    fun isGranted(context: Context?): Boolean {
        if (!isRequired()) return true
        if (context == null) return false
        return try {
            ContextCompat.checkSelfPermission(context, PERMISSION) ==
                    PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            // ROM 未定义该权限等情况：不阻塞，交由系统在真正访问局域网时决定
            AgentLogger.log(
                "SYS",
                "查询 $PERMISSION 失败：${e.javaClass.simpleName}: ${e.message}"
            )
            true
        }
    }

    /**
     * 需要时弹出系统权限对话框。
     *
     * @return true 表示已授权（无需弹窗）；false 表示已发起请求或无法请求，
     *         调用方应等待 [REQUEST_CODE] 的回调
     */
    fun ensure(activity: Activity?): Boolean {
        if (!isRequired()) return true
        if (activity == null) return false
        if (isGranted(activity)) return true
        return try {
            ActivityCompat.requestPermissions(activity, arrayOf(PERMISSION), REQUEST_CODE)
            false
        } catch (e: Exception) {
            AgentLogger.log(
                "SYS",
                "请求 $PERMISSION 失败：${e.javaClass.simpleName}: ${e.message}"
            )
            false
        }
    }

    /** 从任意 Context 向上找 Activity，需要时弹窗。适合 Compose 页面调用。 */
    fun ensureFromContext(context: Context?): Boolean {
        return !isRequired() || ensure(context?.findActivity())
    }

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
