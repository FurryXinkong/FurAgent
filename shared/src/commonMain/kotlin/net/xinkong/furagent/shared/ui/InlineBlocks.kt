package net.xinkong.furagent.shared.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.xinkong.furagent.shared.PlanTasks

/**
 * 对话内富内容块：把 AI 回复里的图表渲染成卡片。
 *
 * 图表格式刻意用极简纯文本（不引 JSON 解析），AI 容易写对：
 * ```chart
 * bar|季度营收
 * Q1:12
 * Q2:19
 * Q3:15
 * ```
 * 支持 bar / line / pie。
 *
 * 注意：任务清单**不在这里渲染**。它属于聊天页顶层功能区（与输入框同层），
 * 由 [TaskProgressBanner] 承载；早期塞在 AI 气泡尾部会导致手机端被挤到
 * 气泡外侧最右边、宽度趋近于零而完全看不见。
 *
 * 双端共用：Android 走 RichMessageText，桌面走 MessageComponents。
 */
private data class ChartData(
    val type: String,
    val title: String,
    val labels: List<String>,
    val values: List<Float>
)

private val CHART = Regex("```chart\\s*\\n([\\s\\S]*?)```")

private fun parseChart(body: String): ChartData? {
    val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return null
    val head = lines[0].split("|", limit = 2)
    val type = head[0].lowercase()
    val title = head.getOrElse(1) { "" }
    val pairs = lines.drop(1).mapNotNull { line ->
        val idx = line.indexOf(':')
        if (idx <= 0) return@mapNotNull null
        val value = line.substring(idx + 1).trim().toFloatOrNull() ?: return@mapNotNull null
        line.substring(0, idx).trim() to value
    }
    if (pairs.isEmpty()) return null
    return ChartData(type, title, pairs.map { it.first }, pairs.map { it.second })
}

/** 把富内容块从正文里去掉，避免 Markdown 又把原始代码块显示一遍。 */
fun stripInlineBlocks(text: String): String = CHART.replace(PlanTasks.strip(text), "").trim()

@Composable
fun InlineBlocks(content: String, modifier: Modifier = Modifier) {
    val charts = remember(content) {
        CHART.findAll(content).mapNotNull { parseChart(it.groupValues[1]) }.toList()
    }
    if (charts.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        charts.forEach { ChartCard(it) }
    }
}

/**
 * 任务进度常驻条 —— 聊天页顶层功能区，与输入框同层，不随消息列表滚动。
 *
 * 粘滞策略（修复「计划执行到一半 UI 突然消失」）：
 *  - 扫描**全量**会话历史。早期只扫最后 40 条，而一轮并行工具会产生十几条
 *    tool_call / tool_result，几轮就把带清单的 assistant 消息挤出窗口；
 *  - 某一轮没解析到清单（纯工具调用、流式代码块未闭合、模型漏写）时，
 *    保留上一次的进度继续显示，不再直接隐藏；
 *  - 只有用户手动关闭、或切换会话才清空。全部完成后仍显示「✓ 全部完成」，
 *    由用户决定何时收起，避免被误判为「执行中消失」。
 *
 * 底色用不透明 surfaceContainerHigh + 描边：早期用半透明 surfaceVariant 叠在
 * 玻璃输入卡片上，两层透明叠加导致文字发虚、几乎看不清。
 */
@Composable
fun TaskProgressBanner(
    historyTexts: List<String>,
    sessionKey: String = "",
    modifier: Modifier = Modifier
) {
    val parsed = remember(historyTexts) { PlanTasks.latestForUi(historyTexts) }

    // 粘滞：只在解析到新清单时更新；解析不到时保持上一次进度。
    var sticky by remember(sessionKey) { mutableStateOf<List<PlanTasks.Task>>(emptyList()) }
    LaunchedEffect(sessionKey, parsed) {
        if (parsed.isNotEmpty()) sticky = parsed
    }
    val tasks = if (parsed.isNotEmpty()) parsed else sticky
    if (tasks.isEmpty()) return

    // 关闭判定只认「清单内容」，不认状态：任务每轮把 [ ] 改成 [x] 时
    // 不该把用户手动关掉的进度条又弹出来；只有出现一份不同的新清单才重现。
    // 展开状态同理，不随每轮进度刷新被重置。
    val planKey = remember(tasks) { tasks.joinToString("|") { it.title } }
    var expanded by remember(sessionKey) { mutableStateOf(false) }
    var dismissedKey by remember(sessionKey) { mutableStateOf("") }
    if (dismissedKey == planKey) return

    val total = tasks.size
    val done = tasks.count { it.status == PlanTasks.Status.DONE }
    val allDone = done == total
    val current = tasks.firstOrNull { it.status == PlanTasks.Status.DOING }
        ?: tasks.firstOrNull { it.status == PlanTasks.Status.PENDING }
    val ratio = if (total > 0) done.toFloat() / total else 0f

    val shape = RoundedCornerShape(12.dp)
    val bg = MaterialTheme.colorScheme.surfaceContainerHigh
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val accent = if (allDone) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bg, shape)
            .border(1.dp, borderColor, shape)
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (allDone) "✓ 全部完成" else "任务  $done/$total",
                fontSize = 12.sp,
                color = accent
            )
            Text(
                text = current?.let { "▶ ${it.title}" } ?: "全部完成",
                fontSize = 11.sp,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (expanded) "收起" else "展开",
                fontSize = 11.sp,
                color = accent
            )
            Text(
                "×",
                fontSize = 13.sp,
                color = muted,
                modifier = Modifier.clickable { dismissedKey = planKey }
            )
        }
        Spacer(Modifier.height(7.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(4.dp)) {
            drawRect(color = track, size = Size(size.width, size.height))
            drawRect(
                color = accent,
                topLeft = Offset.Zero,
                size = Size(size.width * ratio, size.height)
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            TaskLines(tasks)
        }
    }
}

@Composable
private fun TaskLines(tasks: List<PlanTasks.Task>) {
    val done = Color(0xFF34C759)
    val doing = MaterialTheme.colorScheme.primary
    val pending = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tasks.forEach { task ->
            val mark = when (task.status) {
                PlanTasks.Status.DONE -> "✓"
                PlanTasks.Status.DOING -> "▶"
                PlanTasks.Status.PENDING -> "○"
            }
            val color = when (task.status) {
                PlanTasks.Status.DONE -> done
                PlanTasks.Status.DOING -> doing
                PlanTasks.Status.PENDING -> pending
            }
            Text("$mark  ${task.title}", fontSize = 12.sp, color = color)
        }
    }
}

@Composable
private fun ChartCard(chart: ChartData) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        if (chart.title.isNotBlank()) {
            Text(chart.title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
        }
        if (chart.values.isEmpty()) return@Column
        Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            val max = (chart.values.maxOfOrNull { it } ?: 1f).coerceAtLeast(1f)
            val n = chart.values.size
            when (chart.type) {
                "pie" -> {
                    val total = chart.values.sum().coerceAtLeast(1f)
                    var start = -90f
                    val side = size.height.coerceAtMost(size.width)
                    chart.values.forEachIndexed { i, v ->
                        val sweep = v / total * 360f
                        drawArc(
                            color = accent.copy(alpha = 1f - i * 0.12f),
                            startAngle = start,
                            sweepAngle = sweep,
                            useCenter = true,
                            topLeft = Offset((size.width - side) / 2f, 0f),
                            size = Size(side, side)
                        )
                        start += sweep
                    }
                }
                "line" -> {
                    val step = if (n > 1) size.width / (n - 1) else 0f
                    val points = chart.values.mapIndexed { i, v ->
                        Offset(i * step, size.height - (v / max) * (size.height - 12f))
                    }
                    for (i in 1 until points.size) {
                        drawLine(accent, points[i - 1], points[i], strokeWidth = 4f)
                    }
                    points.forEach { drawCircle(accent, radius = 5f, center = it) }
                }
                else -> {
                    val slot = size.width / n
                    val barWidth = slot * 0.6f
                    chart.values.forEachIndexed { i, v ->
                        val h = (v / max) * (size.height - 12f)
                        drawRect(
                            color = accent,
                            topLeft = Offset(i * slot + (slot - barWidth) / 2f, size.height - h),
                            size = Size(barWidth, h)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            chart.labels.forEach { label ->
                Text(label, fontSize = 10.sp, color = muted)
            }
        }
    }
}
