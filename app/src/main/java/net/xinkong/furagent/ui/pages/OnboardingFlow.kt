@file:OptIn(ExperimentalMaterial3Api::class)

package net.xinkong.furagent.ui.pages
import net.xinkong.furagent.ui.theme.AppRadius

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.system.MediaSessionListenerService
import net.xinkong.furagent.system.ScreenAccessibilityService
import net.xinkong.furagent.system.ShizukuHelper
import net.xinkong.furagent.system.notifyToastLong
import kotlin.time.Duration.Companion.milliseconds

// 权限引导

private enum class StepType { RUNTIME, SETTINGS }

private data class OnboardingStep(
    val key: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val type: StepType,
    val grantedCheck: () -> Boolean,
    val runtimePermission: String? = null,
    val openSettings: ((Context) -> Unit)? = null,
    val skipHint: String = "",
)

private fun buildSteps(context: Context): List<OnboardingStep> {
    val steps = mutableListOf<OnboardingStep>()

    // 1. 通知（应用内申请，API 33+）
    if (Build.VERSION.SDK_INT >= 33) {
        steps.add(
            OnboardingStep(
                key = "notification",
                title = "通知权限",
                description = "定时任务触发时弹通知提醒，AI 完成任务时也能及时告知你。",
                icon = Icons.Default.Notifications,
                type = StepType.RUNTIME,
                runtimePermission = Manifest.permission.POST_NOTIFICATIONS,
                grantedCheck = {
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                },
                skipHint = "想改通知权限：系统设置 → 应用 → FurAgent → 通知",
            )
        )
    }

    // 【F1+F8 新增】Android 17（API 37）本地网络保护：
    // 访问局域网（连接电脑、WOL 唤醒、本地 HTTP 文件服务）都需要该权限。
    if (net.xinkong.furagent.system.LocalNetworkPermission.isRequired()) {
        steps.add(
            OnboardingStep(
                key = "local_network",
                title = "本地网络权限",
                description = "Android 17 起访问局域网需要该权限：连接电脑、远程唤醒、本地文件传输都依赖它。",
                icon = Icons.Default.Wifi,
                type = StepType.RUNTIME,
                runtimePermission = net.xinkong.furagent.system.LocalNetworkPermission.PERMISSION,
                grantedCheck = {
                    net.xinkong.furagent.system.LocalNetworkPermission.isGranted(context)
                },
                skipHint = "想开本地网络权限：系统设置 → 应用 → FurAgent → 权限 → 附近的设备",
            )
        )
    }

    // 2. 定位（应用内申请）
    steps.add(
        OnboardingStep(
            key = "location",
            title = "定位权限",
            description = "get_location 工具需要定位权限，才能读取你当前的位置。",
            icon = Icons.Default.LocationOn,
            type = StepType.RUNTIME,
            runtimePermission = Manifest.permission.ACCESS_FINE_LOCATION,
            grantedCheck = {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
            },
            skipHint = "想改定位权限：系统设置 → 应用 → FurAgent → 权限 → 位置信息",
        )
    )

    // 3. 悬浮窗（跳设置）
    steps.add(
        OnboardingStep(
            key = "overlay",
            title = "悬浮窗权限",
            description = "开启后可使用悬浮球（随时唤起对话）和后台启动应用。",
            icon = Icons.Default.BubbleChart,
            type = StepType.SETTINGS,
            grantedCheck = { Settings.canDrawOverlays(context) },
            openSettings = { ctx ->
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        "package:${ctx.packageName}".toUri()
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                } catch (e: Exception) {
                    AgentLogger.log("SYS", "打开悬浮窗权限设置失败：${e.message}")
                }
            },
            skipHint = "想开悬浮窗权限：系统设置 → 应用 → FurAgent → 显示在其他应用上层",
        )
    )

    // 4. 文件访问（跳设置，API 30+）
    if (Build.VERSION.SDK_INT >= 30) {
        steps.add(
            OnboardingStep(
                key = "file_access",
                title = "文件访问权限",
                description = "让 AI 读写 /sdcard 下的文件（截图、下载、生成媒体等）。",
                icon = Icons.Default.Folder,
                type = StepType.SETTINGS,
                grantedCheck = {
                    try { android.os.Environment.isExternalStorageManager() }
                    catch (_: Exception) { false }
                },
                openSettings = { ctx ->
                    try {
                        val appIntent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            "package:${ctx.packageName}".toUri()
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            ctx.startActivity(appIntent)
                        } catch (_: Exception) {
                            val fallback = Intent(
                                Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(fallback)
                        }
                    } catch (e: Exception) {
                        AgentLogger.log("SYS", "打开文件访问权限设置失败：${e.message}")
                    }
                },
                skipHint = "想开文件访问权限：系统设置 → 应用 → FurAgent → 所有文件访问权限",
            )
        )
    }

    // 5. 通知使用权（跳设置）
    steps.add(
        OnboardingStep(
            key = "notification_listener",
            title = "通知使用权",
            description = "读取手机当前播放的音乐 / 播客、系统通知（media_session / notifications 工具需要）。",
            icon = Icons.Default.NotificationsActive,
            type = StepType.SETTINGS,
            grantedCheck = {
                try {
                    val flat = Settings.Secure.getString(
                        context.contentResolver, "enabled_notification_listeners"
                    )
                    if (flat.isNullOrBlank()) {
                        false
                    } else {
                        val component = ComponentName(
                            context, MediaSessionListenerService::class.java
                        )
                        flat.split(":").any {
                            try { ComponentName.unflattenFromString(it) == component }
                            catch (_: Exception) { false }
                        }
                    }
                } catch (_: Exception) { false }
            },
            openSettings = { ctx ->
                try {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                } catch (e: Exception) {
                    AgentLogger.log("SYS", "打开通知使用权设置失败：${e.message}")
                }
            },
            skipHint = "想开通知使用权：系统设置 → 通知 → 通知使用权 → FurAgent",
        )
    )

    // 6. 无障碍（跳设置）
    steps.add(
        OnboardingStep(
            key = "accessibility",
            title = "屏幕控制（无障碍）",
            description = "让 AI 直接读取屏幕 UI 元素并操作，无需截图，Token 消耗低。",
            icon = Icons.Default.TouchApp,
            type = StepType.SETTINGS,
            grantedCheck = { ScreenAccessibilityService.isEnabledInSettings(context) },
            openSettings = { ctx ->
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                } catch (e: Exception) {
                    AgentLogger.log("SYS", "打开无障碍设置失败：${e.message}")
                }
            },
            skipHint = "想开屏幕控制：系统设置 → 无障碍 → 已安装的服务 → FurAgent 屏幕控制",
        )
    )

    // 7. 精确闹钟（跳设置，API 31+）
    if (Build.VERSION.SDK_INT >= 31) {
        steps.add(
            OnboardingStep(
                key = "exact_alarm",
                title = "精确闹钟",
                description = "让定时任务准点触发，否则系统可能延迟几分钟。",
                icon = Icons.Default.Schedule,
                type = StepType.SETTINGS,
                grantedCheck = {
                    try {
                        val am = context.getSystemService(Context.ALARM_SERVICE)
                                as android.app.AlarmManager
                        am.canScheduleExactAlarms()
                    } catch (_: Exception) { false }
                },
                openSettings = { ctx ->
                    try {
                        val intent = Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            "package:${ctx.packageName}".toUri()
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(intent)
                    } catch (e: Exception) {
                        AgentLogger.log("SYS", "打开精确闹钟设置失败：${e.message}")
                    }
                },
                skipHint = "想开精确闹钟：系统设置 → 应用 → FurAgent → 闹钟和提醒",
            )
        )
    }

    // 8. Shizuku（可选项）
    steps.add(
        OnboardingStep(
            key = "shizuku",
            title = "Shizuku（可选）",
            description = "提供 ADB 级权限：执行 shell 命令、后台启动应用、截图。不装也能用大部分功能。",
            icon = Icons.Default.Terminal,
            type = StepType.SETTINGS,
            grantedCheck = { ShizukuHelper.isAvailable() && ShizukuHelper.hasPermission() },
            openSettings = { ctx ->
                val available = ShizukuHelper.isAvailable()
                if (!available) {
                    val launch = ctx.packageManager
                        .getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { ctx.startActivity(launch) } catch (_: Exception) { }
                    } else {
                        try {
                            val market = Intent(
                                Intent.ACTION_VIEW,
                                "market://details?id=moe.shizuku.privileged.api".toUri()
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(market)
                        } catch (_: Exception) {
                            try {
                                val web = Intent(
                                    Intent.ACTION_VIEW,
                                    "https://shizuku.rikka.app/".toUri()
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                ctx.startActivity(web)
                            } catch (_: Exception) { }
                        }
                    }
                }
            },
            skipHint = "想装 Shizuku：https://shizuku.rikka.app/",
        )
    )

    return steps
}

/**
 * 权限引导流程。逐个弹 Dialog，授权后自动前进，可"稍后再说"跳过。
 * 全部走完（或全部跳过）后回调 [onFinished]。
 */
@Composable
fun OnboardingPermissionsFlow(onFinished: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val steps = remember { buildSteps(context) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }

    // 从系统设置页返回时刷新授权状态
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshKey++
    }

    // 全部走完 → 结束
    if (currentIndex >= steps.size) {
        LaunchedEffect(Unit) { onFinished() }
        return
    }

    val step = steps[currentIndex]
    val granted = remember(refreshKey, currentIndex) { step.grantedCheck() }

    // 已授权 → 自动前进
    LaunchedEffect(granted, currentIndex) {
        if (granted) {
            delay(180.milliseconds)
            currentIndex++
        }
    }

    Dialog(
        onDismissRequest = { /* 禁止点外/返回键关闭 */ },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(AppRadius.DIALOG),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                ),
                shadowElevation = 20.dp,
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth(0.9f)
                    .padding(horizontal = 16.dp)
            ) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "${currentIndex + 1} / ${steps.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    // 【体验优化 B16】进度条
                    LinearProgressIndicator(
                        progress = { (currentIndex + 1).toFloat() / steps.size.toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))

                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            step.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))

                    Text(
                        step.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        step.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )

                    Spacer(Modifier.height(24.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = {
                                if (step.skipHint.isNotBlank()) {
                                    notifyToastLong(context, step.skipHint)
                                }
                                currentIndex++
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                "稍后再说",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = {
                                when (step.type) {
                                    StepType.RUNTIME -> {
                                        val perm = step.runtimePermission
                                        if (perm != null) runtimeLauncher.launch(perm)
                                    }
                                    StepType.SETTINGS -> {
                                        step.openSettings?.invoke(context)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(AppRadius.SM)
                        ) {
                            Text(
                                when (step.type) {
                                    StepType.RUNTIME -> "立即授权"
                                    StepType.SETTINGS -> "前往设置"
                                },
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

// 功能导览（一页）

@Composable
fun OnboardingGuideDialog(onFinished: () -> Unit) {
    Dialog(
        onDismissRequest = onFinished,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(AppRadius.DIALOG),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                ),
                shadowElevation = 20.dp,
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth(0.92f)
                    .padding(horizontal = 16.dp)
            ) {
                Column(
                    Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "欢迎使用 FurAgent",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "这里告诉你哪个功能在哪里，看一遍就记住了。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))

                    GuideItem(
                        "打开侧边栏",
                        "点左上角 FurAgent 标题，或从屏幕左边往右滑动。侧边栏里有全部功能入口。"
                    )
                    GuideItem(
                        "长期记忆",
                        "侧边栏 → 长期记忆。AI 记住你的事（姓名、偏好、忌讳）都在这里。"
                    )
                    GuideItem(
                        "定时任务",
                        "侧边栏 → 定时任务。支持秒级 / 每天 / cron / 条件触发。"
                    )
                    GuideItem(
                        "MCP 服务器",
                        "侧边栏 → MCP 服务器。接入第三方工具，让 AI 有更多能力。"
                    )
                    GuideItem(
                        "高级设置 · 权限",
                        "侧边栏 → 高级设置 → 权限。在这里可以随时改各项系统权限。"
                    )
                    GuideItem(
                        "日志 / 会话列表",
                        "主界面右上角：终端图标看日志，列表图标管理会话。"
                    )
                    GuideItem(
                        "悬浮助手",
                        "侧边栏 → 开启悬浮助手。悬浮球随时唤起对话，不打断当前应用。"
                    )

                    Spacer(Modifier.height(20.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = onFinished,
                            shape = RoundedCornerShape(AppRadius.SM)
                        ) {
                            Text("开始使用", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideItem(title: String, desc: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .padding(top = 8.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}