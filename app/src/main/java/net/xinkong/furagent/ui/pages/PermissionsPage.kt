@file:OptIn(ExperimentalMaterial3Api::class)

package net.xinkong.furagent.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import net.xinkong.furagent.system.PermissionChecker
import net.xinkong.furagent.system.PermissionItem
import net.xinkong.furagent.system.ShizukuHelper
import net.xinkong.furagent.system.notifyToast
import net.xinkong.furagent.ui.ApplyBackgroundBlur
import net.xinkong.furagent.ui.GlassSheen
import net.xinkong.furagent.ui.PocoCard
import net.xinkong.furagent.ui.PocoPageTopBar
import net.xinkong.furagent.ui.SuccessColor
import net.xinkong.furagent.ui.glassPageBg
import net.xinkong.furagent.ui.theme.AppRadius

/**
 * 权限管理 —— 整屏页面。
 *
 * UI 统一约定
 *   - 卡片统一 PocoCard
 *   - 页背景 glassPageBg() + 顶部 GlassSheen
 *   - 已授权绿 / 待授权走 colorScheme.error
 */
@Composable
fun PermissionsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshKey by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissions = remember(refreshKey) { PermissionChecker.checkAll(context) }
    val allGranted = permissions.all { it.granted }
    ApplyBackgroundBlur(true)

    Scaffold(
        topBar = {
            PocoPageTopBar(
                title = "权限",
                subtitle = if (allGranted) "所有权限已就绪"
                    else "${permissions.count { !it.granted }} 项待授权",
                onBack = onBack
            )
        },
        containerColor = glassPageBg(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
        ) {
            GlassSheen(
                modifier = Modifier.align(Alignment.TopCenter),
                height = 100.dp,
                alpha = 0.06f
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    PocoCard {
                        Column {
                            permissions.forEachIndexed { index, p ->
                                PermissionRow(p) {
                                    if (p.key == "shizuku") {
                                        scope.launch {
                                            if (ShizukuHelper.isAvailable()) {
                                                val ok = ShizukuHelper.requestPermission()
                                                refreshKey++
                                                notifyToast(
                                                    context,
                                                    if (ok) "已授权" else "授权未完成"
                                                )
                                            } else p.onOpenSettings(context)
                                        }
                                    } else p.onOpenSettings(context)
                                }
                                if (index != permissions.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 56.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    PocoCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "自启动管理",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "小米、华为、OPPO 等需手动允许自启动，才能保证定时任务可靠执行。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { PermissionChecker.openAutoStartSettings(context) },
                                shape = RoundedCornerShape(AppRadius.MD_MINUS),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    contentColor = MaterialTheme.colorScheme.primary
                                )
                            ) { Text("打开应用详情页", fontWeight = FontWeight.Medium) }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

// 权限行

@Composable
private fun PermissionRow(item: PermissionItem, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (item.granted) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (item.granted) SuccessColor() else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                item.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        if (item.granted) {
            Text(
                "已授权",
                style = MaterialTheme.typography.labelMedium,
                color = SuccessColor(),
                fontWeight = FontWeight.Medium
            )
        } else {
            TextButton(
                onClick = onOpen,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    "授权",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}