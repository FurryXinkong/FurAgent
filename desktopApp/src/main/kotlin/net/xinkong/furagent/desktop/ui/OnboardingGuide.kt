package net.xinkong.furagent.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.xinkong.furagent.desktop.ui.theme.Radius

/**
 * 桌面端功能导览（一页，可跳过）。
 *
 * 只讲"哪个功能在哪里"，不讲功能本身怎么用。
 * 用户第一次启动桌面端时弹出，看过一次就不再弹。
 */
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
                color = CardColor(),
                shape = RoundedCornerShape(Radius.LG),
                border = BorderStroke(1.dp, BorderColor()),
                shadowElevation = 20.dp,
                modifier = Modifier
                    .widthIn(min = 480.dp, max = 560.dp)
                    .padding(24.dp)
            ) {
                Column(
                    Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "欢迎使用 FurAgent",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = TextPrimary()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "这里是桌面端各功能的位置，看一遍就记住了。",
                        fontSize = 12.sp,
                        color = TextSecondary()
                    )
                    Spacer(Modifier.height(20.dp))

                    GuideItem(
                        Icons.AutoMirrored.Filled.ViewSidebar,
                        "侧边栏",
                        "左侧是全部页面入口：长期记忆、定时任务、MCP、自定义脚本、状态面板、日志。"
                    )
                    GuideItem(
                        Icons.Default.Forum,
                        "会话历史",
                        "侧边栏中段。右键任意会话可重命名 / 复制 / 导出 Markdown。"
                    )
                    GuideItem(
                        Icons.Default.Code,
                        "自定义脚本（Skill）",
                        "侧边栏 → 自定义脚本。把 Python 脚本放进 ~/.furagent/scripts/，AI 就能调用。"
                    )
                    GuideItem(
                        Icons.Default.Extension,
                        "MCP 服务器",
                        "侧边栏 → MCP。接入第三方工具（文件系统、数据库、浏览器等）。"
                    )
                    GuideItem(
                        Icons.Default.PictureInPicture,
                        "桌面悬浮窗",
                        "设置 → 外观 → 桌面悬浮窗。独立置顶窗口，实时显示 AI 状态。"
                    )
                    GuideItem(
                        Icons.Default.Terminal,
                        "日志 / 状态",
                        "侧边栏 → 日志 / 状态面板。日志按天分文件保留 7 天，状态面板显示连接情况。"
                    )

                    Spacer(Modifier.height(20.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        PrimaryButton(text = "开始使用", onClick = onFinished)
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideItem(icon: ImageVector, title: String, desc: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(Radius.XS))
                .background(CardStrongColor()),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = AccentColor(),
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary()
            )
            Spacer(Modifier.height(3.dp))
            Text(
                desc,
                fontSize = 11.sp,
                color = TextSecondary(),
                lineHeight = 17.sp
            )
        }
    }
}