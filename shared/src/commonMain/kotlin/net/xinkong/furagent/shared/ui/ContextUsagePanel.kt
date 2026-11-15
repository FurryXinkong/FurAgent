package net.xinkong.furagent.shared.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.xinkong.furagent.shared.ContextUsage
import net.xinkong.furagent.shared.formatPercent
import net.xinkong.furagent.shared.formatTokenCount

/**
 * 上下文用量面板（参考 WorkBuddy 的「上下文用量」弹层）。
 *
 * 双端共用纯内容，外壳 Dialog 各端自己套（Android AlertDialog / 桌面 AlertDialog
 * 参数差异大，放共享层容易踩平台 API）。用 MaterialTheme 取色，明暗主题都可读。
 */
private val C_SYSTEM = Color(0xFF5B8DEF)
private val C_TOOLS = Color(0xFF34B37E)
private val C_MESSAGES = Color(0xFFF5A623)
private val C_CONNECTOR = Color(0xFF9B6DE8)
private val C_SKILL = Color(0xFFE86A9A)

private data class Part(val label: String, val tokens: Int, val color: Color)

@Composable
fun ContextUsagePanelContent(
    usage: ContextUsage,
    budgetK: Int,
    onBudgetChange: (Int) -> Unit,
    onCompactNow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val parts = listOf(
        Part("系统提示词", usage.systemTokens, C_SYSTEM),
        Part("工具及子智能体", usage.toolsTokens, C_TOOLS),
        Part("对话消息", usage.messagesTokens, C_MESSAGES),
        Part("连接器及 MCP", usage.connectorTokens, C_CONNECTOR),
        Part("技能", usage.skillTokens, C_SKILL)
    )
    val track = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(18.dp)
    ) {
        // 标题行
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "上下文用量",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = onSurface
            )
            Spacer(Modifier.weight(1f))
            Text(
                "×",
                fontSize = 18.sp,
                color = muted,
                modifier = Modifier.clickable { onDismiss() }
            )
        }

        Spacer(Modifier.height(14.dp))

        // 大号百分比 + 已用 / 上限
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                formatPercent(usage.ratio),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = onSurface
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "已使用 ${formatTokenCount(usage.used)}/${formatTokenCount(usage.budget)}",
                fontSize = 12.sp,
                color = muted,
                modifier = Modifier.padding(bottom = 5.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        // 分段进度条：按各类占「预算」的比例填充，剩余是空轨道
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
        ) {
            val w = size.width
            val h = size.height
            drawRect(color = track, topLeft = Offset.Zero, size = Size(w, h))
            var x = 0f
            parts.forEach { p ->
                val pw = w * usage.windowPercent(p.tokens)
                if (pw > 0f) {
                    drawRect(
                        color = p.color,
                        topLeft = Offset(x, 0f),
                        size = Size(pw.coerceAtMost(w - x), h)
                    )
                    x += pw
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 五项明细
        parts.forEach { p ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(p.color, CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Text(p.label, fontSize = 13.sp, color = onSurface)
                Spacer(Modifier.weight(1f))
                Text(
                    formatPercent(usage.windowPercent(p.tokens)),
                    fontSize = 12.sp,
                    color = muted
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
        Spacer(Modifier.height(14.dp))

        // 预算滑块
        // 设置允许 0..2000K（0 = 不限），滑块不能用 0 表示「不限」，
        // 这里统一展示有效预算：0 时按默认窗口显示，拖动后写入具体值。
        val effectiveK = if (budgetK > 0) budgetK else 128
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${effectiveK}K 上下文 · ${usage.level}",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = onSurface
            )
        }
        Slider(
            value = effectiveK.toFloat().coerceIn(16f, 2000f),
            onValueChange = { onBudgetChange(it.toInt()) },
            valueRange = 16f..2000f,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "拖动可调整上下文 token 上限；越接近上限，旧的工具过程会自动压缩成摘要保留。",
            fontSize = 11.sp,
            color = muted
        )

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (usage.compacted) {
                Text(
                    "上轮已自动压缩（省 ${formatTokenCount(usage.compactedChars)} 字符）",
                    fontSize = 11.sp,
                    color = C_TOOLS
                )
                Spacer(Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Box(
                Modifier
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onCompactNow() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    "立即压缩",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

/** 输入区右下角的小胶囊：显示当前占用百分比，点击打开面板。 */
@Composable
fun ContextUsageChip(
    ratio: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = when {
        ratio >= 0.85f -> Color(0xFFE5484D)
        ratio >= 0.65f -> C_MESSAGES
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(14.dp)
            )
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            formatPercent(ratio),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}
