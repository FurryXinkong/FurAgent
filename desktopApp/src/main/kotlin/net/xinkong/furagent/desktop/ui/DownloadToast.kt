package net.xinkong.furagent.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.xinkong.furagent.desktop.core.DownloadManager
import net.xinkong.furagent.desktop.ui.theme.Radius
import net.xinkong.furagent.desktop.ui.theme.Shadow
import net.xinkong.furagent.shared.util.formatFileSize
import kotlin.time.Duration.Companion.milliseconds

/**
 * 下载浮层（右下角 Toast）。
 *
 * 【拆分】从 Glass.kt 抽出（原文件 1400+ 行，职责过杂）。同包，引用零改动。
 */

@Composable
fun DownloadToastBar(modifier: Modifier = Modifier) {
    val tasks by DownloadManager.tasks.collectAsState()

    fun isRecent(task: DownloadManager.Task): Boolean {
        return (task.status == DownloadManager.Status.COMPLETED ||
                task.status == DownloadManager.Status.FAILED) &&
                System.currentTimeMillis() - task.finishedAt < 6000
    }

    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tasks) {
        while (tasks.any { isRecent(it) }) {
            kotlinx.coroutines.delay(500.milliseconds)
            tick++
        }
    }
    @Suppress("UNUSED_EXPRESSION") tick

    val active = tasks.filter {
        it.status == DownloadManager.Status.PENDING ||
                it.status == DownloadManager.Status.DOWNLOADING
    }
    val recentlyFinished = tasks.filter { isRecent(it) }
    // 【体验优化 B25】优先保留所有活跃任务。
    // 活跃任务 >= 3 个时全部显示（用户的下载不该被顶掉）；
    // 否则按顺序用最近完成的任务补齐到 3 个。
    val shown = if (active.size >= 3) {
        active
    } else {
        active + recentlyFinished.takeLast(3 - active.size)
    }

    AnimatedVisibility(
        visible = shown.isNotEmpty(),
        enter = fadeIn(tween(200)) + slideInVertically(
            animationSpec = tween(280, easing = FastOutSlowInEasing),
            initialOffsetY = { it / 2 }
        ),
        exit = fadeOut(tween(200)) + slideOutVertically(
            animationSpec = tween(240, easing = FastOutSlowInEasing),
            targetOffsetY = { it / 2 }
        ),
        modifier = modifier
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            shown.forEach { task ->
                key(task.id) {
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(tween(200)) + slideInVertically(
                            animationSpec = tween(260, easing = FastOutSlowInEasing),
                            initialOffsetY = { it }
                        ) + expandVertically(
                            animationSpec = tween(260, easing = FastOutSlowInEasing),
                            expandFrom = Alignment.Bottom
                        ),
                        exit = fadeOut(tween(180)) + shrinkVertically(
                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                            shrinkTowards = Alignment.Bottom
                        )
                    ) {
                        DownloadToastItem(task)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadToastItem(task: DownloadManager.Task) {
    val status = task.status
    val icon = when (status) {
        DownloadManager.Status.COMPLETED -> Icons.Default.CheckCircle
        DownloadManager.Status.FAILED -> Icons.Default.Error
        else -> Icons.Default.Download
    }
    val iconTint by animateColorAsState(
        when (status) {
            DownloadManager.Status.COMPLETED -> ResultColor()
            DownloadManager.Status.FAILED -> ErrorColor()
            else -> AccentColor()
        },
        tween(240), label = "downloadIconTint"
    )

    val animatedProgress by animateFloatAsState(
        targetValue = task.progress.coerceIn(0f, 1f),
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "downloadProgress"
    )

    val animatedDownloaded by animateFloatAsState(
        targetValue = task.downloadedBytes.toFloat(),
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "downloadBytes"
    )

    GlassFloatingCard(modifier = Modifier.width(340.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    task.fileName,
                    fontSize = 12.sp,
                    color = TextPrimary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                HoverTip(
                    if (status == DownloadManager.Status.PENDING ||
                        status == DownloadManager.Status.DOWNLOADING
                    ) "取消下载" else "移除记录"
                ) {
                    IconActionButton(
                        Icons.Default.Close,
                        onClick = {
                            if (status == DownloadManager.Status.PENDING ||
                                status == DownloadManager.Status.DOWNLOADING
                            ) {
                                DownloadManager.cancel(task.id)
                            } else {
                                DownloadManager.remove(task.id)
                            }
                        },
                        tint = TextSecondary(),
                        size = 22.dp
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            when (status) {
                DownloadManager.Status.DOWNLOADING -> {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = AccentColor(),
                        trackColor = BorderSubtle()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildString {
                            append(formatFileSize(animatedDownloaded.toLong()))
                            if (task.totalBytes > 0) append(" / ").append(formatFileSize(task.totalBytes))
                            append(" · ").append(formatSpeed(task.speedBps))
                            if (task.etaSeconds >= 0) append(" · 剩余 ").append(formatEta(task.etaSeconds))
                        },
                        fontSize = 10.sp,
                        color = TextMuted(),
                        fontFamily = FontFamily.Monospace
                    )
                }
                DownloadManager.Status.PENDING -> {
                    Text("准备中…", fontSize = 10.sp, color = TextMuted())
                }
                DownloadManager.Status.COMPLETED -> {
                    Text(
                        "已完成 · ${formatFileSize(task.totalBytes)}",
                        fontSize = 10.sp, color = ResultColor(), fontFamily = FontFamily.Monospace
                    )
                }
                DownloadManager.Status.FAILED -> {
                    Text("失败：${task.error ?: "未知错误"}", fontSize = 10.sp, color = ErrorColor())
                }
                DownloadManager.Status.CANCELLED -> {
                    Text("已取消", fontSize = 10.sp, color = TextMuted())
                }
            }
        }
    }
}
