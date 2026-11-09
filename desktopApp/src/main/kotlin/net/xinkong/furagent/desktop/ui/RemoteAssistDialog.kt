package net.xinkong.furagent.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import net.xinkong.furagent.desktop.tools.PhoneTools
import net.xinkong.furagent.desktop.ui.theme.Radius
import net.xinkong.furagent.shared.icons.Icons
import net.xinkong.furagent.shared.ui.glassPageBg
import org.json.JSONObject

/**
 * Windows → 安卓远程协助面板（人工操作入口）。
 *
 * 底层与 Agent 用的 phone_remote 工具是同一条链路：
 * 桌面 → BridgeServer → 手机 PcBridgeClient → RemoteControlTools(adb)。
 * 因此这里不新增协议、不需要 USB，也不绕过手机端的权限与确认策略。
 *
 * UI 统一走 Glass.kt 的设计体系（GlassTextField / PrimaryButton / SecondaryButton /
 * SectionLabel / HintText / furColors 配色 / Radius 圆角 / rememberDialogEnter 进场动画），
 * 与全站其它弹窗保持一致的视觉语言。
 */
@Composable
fun RemoteAssistDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var clientId by remember { mutableStateOf("") }
    var xText by remember { mutableStateOf("540") }
    var yText by remember { mutableStateOf("1200") }
    var inputText by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("先点「查看状态」确认手机端控制通道是否就绪。") }
    var busy by remember { mutableStateOf(false) }

    fun runOp(op: String, extra: JSONObject.() -> Unit = {}) {
        if (busy) return
        scope.launch {
            busy = true
            val args = JSONObject().apply {
                put("op", op)
                if (clientId.isNotBlank()) put("client_id", clientId.trim())
                extra()
            }
            val r = try {
                PhoneTools.execute("phone_remote", args.toString())
            } catch (e: Exception) {
                result = "失败：${e.javaClass.simpleName}: ${e.message}"
                busy = false
                return@launch
            }
            result = if (r.success) r.output else "失败：${r.output}"
            busy = false
        }
    }

    val enter = rememberDialogEnter()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = true)
    ) {
        Box(
            Modifier.fillMaxSize().background(BgColor().copy(alpha = 0.62f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .size(780.dp, 720.dp)
                    .then(enter)
                    .background(glassPageBg(), RoundedCornerShape(Radius.MD))
                    .border(1.dp, BorderColor(), RoundedCornerShape(Radius.MD))
                    .padding(18.dp)
            ) {
                // ── 标题栏 ──────────────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("远程协助 · 控制安卓手机", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary())
                        Spacer(Modifier.height(2.dp))
                        Text("复用现有桥接通道，无需 USB；手机需开启无障碍或 Shizuku", fontSize = 11.sp, color = TextMuted())
                    }
                    IconActionButton(Icons.Default.Close, onClick = onDismiss, contentDescription = "关闭", tint = TextSecondary())
                }
                Spacer(Modifier.height(10.dp))

                GlassTextField(
                    value = clientId, onValueChange = { clientId = it },
                    placeholder = "client_id（可留空，默认第一台在线手机）"
                )
                Spacer(Modifier.height(10.dp))

                SectionLabel("设备操作")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("查看状态", enabled = !busy, onClick = { runOp("status") })
                    SecondaryButton("读取 UI 树", enabled = !busy, onClick = { runOp("dump") })
                    SecondaryButton("截屏", enabled = !busy, onClick = { runOp("screencap") })
                }
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        GlassTextField(
                            value = xText, onValueChange = { xText = it.filter { c -> c.isDigit() } },
                            placeholder = "x"
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        GlassTextField(
                            value = yText, onValueChange = { yText = it.filter { c -> c.isDigit() } },
                            placeholder = "y"
                        )
                    }
                    SecondaryButton("点击", enabled = !busy, onClick = {
                        val x = xText.toIntOrNull() ?: 0
                        val y = yText.toIntOrNull() ?: 0
                        runOp("tap") { put("x", x); put("y", y) }
                    })
                    SecondaryButton("上滑", enabled = !busy, onClick = {
                        val x = xText.toIntOrNull() ?: 0
                        val y = yText.toIntOrNull() ?: 0
                        runOp("swipe") {
                            put("start_x", x); put("start_y", y + 600)
                            put("end_x", x); put("end_y", y)
                            put("duration_ms", 400)
                        }
                    })
                }
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        GlassTextField(
                            value = inputText, onValueChange = { inputText = it },
                            placeholder = "输入文字"
                        )
                    }
                    SecondaryButton("输入", enabled = !busy && inputText.isNotBlank(), onClick = {
                        runOp("text") { put("text", inputText) }
                    })
                }
                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("back" to "返回", "home" to "主页", "recents" to "最近", "enter" to "回车").forEach { (k, label) ->
                        SecondaryButton(label, enabled = !busy, onClick = { runOp("key") { put("key", k) } })
                    }
                }
                Spacer(Modifier.height(10.dp))

                Text(if (busy) "执行中…" else "结果", fontSize = 12.sp, color = TextSecondary())
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(CardStrongColor(), RoundedCornerShape(Radius.SM))
                        .border(1.dp, BorderSubtle(), RoundedCornerShape(Radius.SM))
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    Text(result, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = TextPrimary())
                }
            }
        }
    }

}
