package net.xinkong.furagent.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.xinkong.furagent.desktop.core.TaskRuntime
import net.xinkong.furagent.desktop.ui.theme.Radius
import net.xinkong.furagent.desktop.ui.theme.Shadow
import net.xinkong.furagent.shared.util.formatDuration
import kotlin.time.Duration.Companion.milliseconds

/**
 * 右上角工具运行浮层（统一版）。
 *
 * 列表用 LazyColumn 承载（最多 3 条），这样能用 Modifier.animateItem()。
 *
 * 只在有 task 时跑 tick，每秒一次（协程 delay），没有任何 task 时不启动。
 */
@Composable
fun TaskFloatingBar(modifier: Modifier = Modifier) {
    val tasks by TaskRuntime.tasks.collectAsState()

    val hasTasks = tasks.isNotEmpty()

    LaunchedEffect(hasTasks) {
        if (!hasTasks) return@LaunchedEffect
        while (true) {
            delay(1000.milliseconds)
            TaskRuntime.tickCleanup()
        }
    }

    AnimatedVisibility(
        visible = hasTasks,
        enter = fadeIn(tween(180)) + scaleIn(
            animationSpec = tween(220, easing = FastOutSlowInEasing),
            initialScale = 0.94f,
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)
        ),
        exit = fadeOut(tween(140)) + scaleOut(
            animationSpec = tween(180, easing = FastOutSlowInEasing),
            targetScale = 0.94f,
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)
        ),
        modifier = modifier
    ) {
        val visible = tasks.takeLast(3)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.widthIn(max = 620.dp)
        ) {
            items(visible, key = { it.id }) { task ->
                Box(
                    Modifier.animateItem(
                        fadeInSpec = tween(220, easing = FastOutSlowInEasing),
                        placementSpec = tween(260, easing = FastOutSlowInEasing),
                        fadeOutSpec = tween(160, easing = FastOutSlowInEasing)
                    )
                ) {
                    TaskFloatItem(task)
                }
            }
        }
    }
}

@Composable
private fun TaskFloatItem(task: TaskRuntime.RunningTask) {
    var expanded by remember(task.id) { mutableStateOf(false) }
    var showFullDialog by remember(task.id) { mutableStateOf(false) }

    var elapsedMs by remember(task.id) { mutableLongStateOf(task.elapsedMs) }
    LaunchedEffect(task.id, task.running) {
        if (!task.running) {
            elapsedMs = task.elapsedMs
            return@LaunchedEffect
        }
        while (true) {
            elapsedMs = task.elapsedMs
            delay(1000.milliseconds)
        }
    }

    val (icon, tint, title) = when {
        task.running -> Triple(Icons.Default.PlayArrow, AccentColor(), "正在执行：${task.displayName}")
        task.cancelled -> Triple(Icons.Default.Stop, WarningColor(), "${task.displayName} 已取消")
        task.timedOut -> Triple(Icons.Default.Error, WarningColor(), "${task.displayName} 超时")
        task.exitCode == 0 -> Triple(Icons.Default.CheckCircle, ResultColor(), "${task.displayName} 完成")
        else -> Triple(Icons.Default.Error, ErrorColor(), "${task.displayName} 失败（退出码 ${task.exitCode ?: "?"}）")
    }

    val hasOutput = task.output.isNotBlank()

    GlassFloatingCard(modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (task.running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = tint
                    )
                } else {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (task.running) "已运行 ${formatDuration(elapsedMs)}"
                    else "耗时 ${formatDuration(elapsedMs)}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextMuted()
                )
                Spacer(Modifier.weight(1f))

                IconActionButton(
                    Icons.Default.OpenInFull,
                    onClick = { showFullDialog = true },
                    tint = TextSecondary(),
                    size = 24.dp
                )
                if (hasOutput) {
                    IconActionButton(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        onClick = { expanded = !expanded },
                        tint = TextSecondary(),
                        size = 24.dp
                    )
                }
                IconActionButton(
                    Icons.Default.Close,
                    onClick = {
                        if (task.running) TaskRuntime.cancel(task.id)
                        else TaskRuntime.remove(task.id)
                    },
                    tint = if (task.running) ErrorColor() else TextSecondary(),
                    size = 24.dp
                )
            }

            if (task.subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    task.subtitle,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary(),
                    maxLines = if (expanded) 6 else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (task.progress != null && task.running) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = AccentColor(),
                    trackColor = BorderSubtle()
                )
                if (task.detail.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        task.detail,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded && hasOutput,
                enter = fadeIn(tween(180)) +
                        expandVertically(
                            animationSpec = tween(240, easing = FastOutSlowInEasing),
                            expandFrom = Alignment.Top
                        ),
                exit = fadeOut(tween(140)) +
                        shrinkVertically(
                            animationSpec = tween(200, easing = FastOutSlowInEasing),
                            shrinkTowards = Alignment.Top
                        )
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    GlassDivider(color = BorderSubtle())
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 560.dp)
                            .clip(RoundedCornerShape(Radius.XS))
                            .background(BgColor())
                            .padding(8.dp)
                    ) {
                        val vScroll = rememberScrollState()
                        val hScroll = rememberScrollState()
                        LaunchedEffect(task.output) {
                            vScroll.animateScrollTo(vScroll.maxValue)
                        }
                        Text(
                            task.output.takeLast(8000),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary(),
                            lineHeight = 15.sp,
                            softWrap = false,
                            modifier = Modifier
                                .verticalScroll(vScroll)
                                .horizontalScroll(hScroll)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = !expanded && hasOutput && task.progress == null && task.detail.isNotBlank(),
                enter = fadeIn(tween(160)) + expandVertically(tween(200)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(160))
            ) {
                Column {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        task.detail,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            AnimatedVisibility(
                visible = task.running && !hasOutput && task.progress == null,
                enter = fadeIn(tween(160)) + expandVertically(tween(200)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(160))
            ) {
                Column {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "等待输出…",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted(),
                        maxLines = 1
                    )
                }
            }
        }
    }

    if (showFullDialog) {
        TaskFullDialog(task, onDismiss = { showFullDialog = false })
    }
}

@Composable
private fun TaskFullDialog(
    task: TaskRuntime.RunningTask,
    onDismiss: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.92f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "fullDialogScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "fullDialogAlpha"
    )
    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "fullDialogScrim"
    )

    var elapsedMs by remember(task.id) { mutableLongStateOf(task.elapsedMs) }
    LaunchedEffect(task.id, task.running) {
        if (!task.running) {
            elapsedMs = task.elapsedMs
            return@LaunchedEffect
        }
        while (true) {
            elapsedMs = System.currentTimeMillis() - task.startedAt
            delay(1000.milliseconds)
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = scrimAlpha }
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = CardColor(),
                shape = RoundedCornerShape(Radius.MD),
                border = BorderStroke(1.dp, BorderColor()),
                shadowElevation = Shadow.XL,
                modifier = Modifier
                    .widthIn(min = 900.dp, max = 1600.dp)
                    .heightIn(min = 600.dp, max = 1000.dp)
                    .padding(24.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { }
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${task.displayName} 输出",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary()
                        )
                        Spacer(Modifier.width(12.dp))
                        if (task.subtitle.isNotBlank()) {
                            Text(
                                task.subtitle,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextMuted(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        Text(
                            if (task.running) "已运行 ${formatDuration(elapsedMs)}"
                            else "耗时 ${formatDuration(elapsedMs)}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted()
                        )
                        Spacer(Modifier.width(8.dp))
                        IconActionButton(
                            Icons.Default.Close,
                            onClick = onDismiss,
                            tint = TextSecondary(),
                            size = 28.dp
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    GlassDivider(color = BorderSubtle())
                    Spacer(Modifier.height(10.dp))

                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(Radius.XS))
                            .background(BgColor())
                            .padding(12.dp)
                    ) {
                        val vScroll = rememberScrollState()
                        val hScroll = rememberScrollState()
                        LaunchedEffect(task.output) {
                            vScroll.animateScrollTo(vScroll.maxValue)
                        }
                        val displayText = when {
                            task.output.isNotBlank() -> task.output
                            task.running -> "(等待输出…)"
                            else -> "(无输出)"
                        }
                        Text(
                            displayText,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary(),
                            lineHeight = 16.sp,
                            softWrap = false,
                            modifier = Modifier
                                .verticalScroll(vScroll)
                                .horizontalScroll(hScroll)
                        )
                    }
                }
            }
        }
    }
}