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

    /**
     * 并行请求保护：按 permission 名做 key。
     *
     * 旧实现是单个 `deferred` + `waitingPermission` 变量，两个工具同时请求
     * 不同权限时，后到的会覆盖先到的，先到的协程永远等不到结果（只能等 300s 超时）。
     */
    private val pendingMap =
        java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    suspend fun request(
        title: String,
        message: String,
        permission: String,
        openSettingsAction: (Context) -> Unit
    ): Boolean {
        val d = CompletableDeferred<Boolean>()
        // 同一权限并发请求时复用同一个 deferred，避免重复弹框
        val existing = pendingMap.putIfAbsent(permission, d)
        if (existing != null) {
            return withTimeoutOrNull(300.seconds) { existing.await() } ?: false
        }

        withContext(Dispatchers.Main) {
            _pending = PendingPermission(title, message, permission, openSettingsAction)
        }

        val result = withTimeoutOrNull(300.seconds) { d.await() } ?: false

        withContext(Dispatchers.Main) {
            // 只清自己这个权限的弹框，不要误清其他并行请求的
            if (_pending?.permission == permission) _pending = null
        }
        pendingMap.remove(permission)
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
        val p = _pending ?: return
        pendingMap.remove(p.permission)?.complete(false)
        _pending = null
    }

    fun onAppResumed(context: Context) {
        // 并行场景下可能有多个权限在等，逐个检查
        if (pendingMap.isEmpty()) return
        val granted = mutableListOf<String>()
        for (perm in pendingMap.keys.toList()) {
            val ok = try {
                androidx.core.content.ContextCompat.checkSelfPermission(context, perm) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) { false }
            if (ok) granted.add(perm)
        }
        for (perm in granted) {
            pendingMap.remove(perm)?.complete(true)
            if (_pending?.permission == perm) _pending = null
        }
    }
}