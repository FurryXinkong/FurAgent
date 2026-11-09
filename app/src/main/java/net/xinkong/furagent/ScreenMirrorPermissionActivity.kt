package net.xinkong.furagent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.service.MirrorConfig
import net.xinkong.furagent.service.ScreenMirrorFrames
import net.xinkong.furagent.service.ScreenMirrorService

/**
 * 透明授权中转页。
 *
 * Android 要求 MediaProjection 的录屏授权必须由前台 Activity 弹系统对话框，
 * 后台服务/广播无法直接发起。桌面端点「开始投屏」时，手机端会拉起这个
 * 一闪而过的透明页面，用户点一次「立即开始」，授权结果就交给
 * ScreenMirrorService 长期持有并开始推流。
 *
 * 电脑端也可以不通过远程触发：手机顶栏的「投屏」按钮同样走这个页面。
 */
class ScreenMirrorPermissionActivity : ComponentActivity() {

    /** 从启动本页面的意图里取出投屏配置（由桌面端经桥下发）。 */
    private fun readConfig(): MirrorConfig {
        val i = intent
        val res = i.getIntExtra(ScreenMirrorService.EXTRA_RESOLUTION, 0)
        val fps = i.getIntExtra(ScreenMirrorService.EXTRA_FPS, 0)
        val q = i.getIntExtra(ScreenMirrorService.EXTRA_QUALITY, 0)
        val def = MirrorConfig()
        return MirrorConfig(
            targetWidth = if (res <= 0) def.targetWidth else res,
            fps = if (fps <= 0) def.fps else fps,
            quality = if (q <= 0) def.quality else q
        )
    }

    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                ScreenMirrorService.start(this, result.resultCode, data, readConfig())
                AgentLogger.log("MIRROR", "录屏授权已获得，启动投屏采集")
            } else {
                ScreenMirrorFrames.deactivate()
                AgentLogger.log("MIRROR", "用户拒绝了录屏授权，投屏未启动")
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 旋转/重建时不要重复弹授权框
        if (savedInstanceState != null) {
            finish()
            return
        }
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            launcher.launch(mgr.createScreenCaptureIntent())
        } catch (e: Exception) {
            AgentLogger.log("MIRROR", "无法发起录屏授权：${e.javaClass.simpleName}: ${e.message}")
            ScreenMirrorFrames.deactivate()
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask 复用时若已有授权在进行，直接结束避免反复弹窗
        if (ScreenMirrorFrames.isActive()) finish()
    }
}
