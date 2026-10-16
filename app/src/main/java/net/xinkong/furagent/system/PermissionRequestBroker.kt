package net.xinkong.furagent.system

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.xinkong.furagent.core.AgentLogger
import kotlin.time.Duration.Companion.seconds

/**
 * 权限请求协调器。
 *
 * 使用场景：工具执行需要某个权限但没有时，不让 AI 直接失败返回，
 * 而是弹对话框询问用户是否前往设置授予。用户同意后打开设置页，
 * 协程挂起等待 App 回到前台并检测到权限已授予；用户拒绝或超时则返回 false。
 */
object PermissionRequestBroker {

    data class PendingPermission(
        val title: String,
        val message: String,
        val permission: String,
        val openSettingsAction: (Context) -> Unit
    )

    private var _pending by mutableStateOf<PendingPermission?>(null)
    val pending: PendingPermission? get() = _pending

    private var deferred: CompletableDeferred<Boolean>? = null
    private var waitingPermission: String? = null

    suspend fun request(
        title: String,
        message: String,
        permission: String,
        openSettingsAction: (Context) -> Unit
    ): Boolean {
        val d = CompletableDeferred<Boolean>()
        deferred = d
        waitingPermission = permission

        withContext(Dispatchers.Main) {
            _pending = PendingPermission(title, message, permission, openSettingsAction)
        }

        val result = withTimeoutOrNull(300.seconds) { d.await() } ?: false

        withContext(Dispatchers.Main) { _pending = null }
        deferred = null
        waitingPermission = null
        return result
    }

    fun onUserApproved(context: Context) {
        val p = _pending ?: return
        try {
            p.openSettingsAction(context)
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "打开权限设置页失败（${p.permission}）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
        _pending = null
    }

    fun onUserRejected() {
        deferred?.complete(false)
        _pending = null
    }

    fun onAppResumed(context: Context) {
        val perm = waitingPermission ?: return
        val granted = try {
            androidx.core.content.ContextCompat.checkSelfPermission(context, perm) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
        if (granted) {
            deferred?.complete(true)
        }
    }
}