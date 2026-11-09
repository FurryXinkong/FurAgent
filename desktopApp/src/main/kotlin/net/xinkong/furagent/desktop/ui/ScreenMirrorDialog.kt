package net.xinkong.furagent.desktop.ui

import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.xinkong.furagent.desktop.tools.PhoneTools
import net.xinkong.furagent.desktop.ui.theme.Radius
import net.xinkong.furagent.shared.icons.Icons
import net.xinkong.furagent.shared.ui.glassPageBg
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jcodec.codecs.h264.H264Decoder
import org.jcodec.common.model.ColorSpace
import org.jcodec.common.model.Picture
import org.jcodec.scale.AWTUtil
import org.json.JSONObject
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.min

private data class MirrorFrame(val bitmap: ImageBitmap, val width: Int, val height: Int)

/**
 * 实时屏幕投屏窗口（低延迟连续画面，不是「截一张图」）。
 *
 * 数据流：手机 MediaProjection 持续抓帧 → JPEG → AndroidHttpFileServer /screen/stream
 * （`[4字节长度][JPEG]`，手机侧为单槽最新帧 + TCP_NODELAY，低延迟）→ 这里 OkHttp 长连接
 * 逐帧解码成 ImageBitmap 刷新。手机侧落后即跳帧取最新，画面永远向前、不乱序。
 *
 * 交互（真控制）：单击=tap；按住 0.6s=long_press；拖拽=swipe；滚轮=scroll；键盘=text/key。
 *
 * 采集参数（分辨率/帧率/画质=近似码率）在「设置」里用 Chip 选，随 mirror_start 下发手机。
 * 注意：JPEG 传输下分辨率/画质越高，可达帧率越低——1080p + 高画质通常只能到 ~20-30fps，
 * 这是 JPEG 编码的物理上限；追求 1080p60 需要换硬件 H.264（另议）。
 */
@Composable
fun ScreenMirrorDialog(clientId: String?, onDismiss: () -> Unit) {
    val streamUrl = remember(clientId) { PhoneTools.screenStreamUrl(clientId) }
    val infoUrl = remember(clientId) { PhoneTools.screenInfoUrl(clientId) }

    var frame by remember { mutableStateOf<MirrorFrame?>(null) }
    var status by remember { mutableStateOf("正在连接…") }
    var frameCount by remember { mutableIntStateOf(0) }
    var measuredFps by remember { mutableIntStateOf(0) }
    var realW by remember { mutableIntStateOf(0) }
    var realH by remember { mutableIntStateOf(0) }
    var mirrorActive by remember { mutableStateOf(false) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var retryKey by remember { mutableIntStateOf(0) }
    var actionResult by remember { mutableStateOf("") }
    var cursor by remember { mutableStateOf(Offset.Zero) }
    var showCursor by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(true) }

    // 采集参数
    var resolution by remember { mutableIntStateOf(720) }
    var fps by remember { mutableIntStateOf(30) }
    var quality by remember { mutableIntStateOf(70) }
    var textInput by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    LaunchedEffect(infoUrl, retryKey) {
        val url = infoUrl ?: run {
            status = "未找到已配对的手机 HTTP 通道，请先在状态面板确认设备在线。"
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            try {
                val c = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS).build()
                c.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    val o = JSONObject(resp.body.string())
                    realW = o.optInt("real_width", 0)
                    realH = o.optInt("real_height", 0)
                    mirrorActive = o.optBoolean("active", false)
                    if (!mirrorActive) status = "手机端投屏未开启。点「开启投屏」并在手机上完成一次系统授权。"
                }
            } catch (e: Exception) {
                status = "读取屏幕信息失败：${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    LaunchedEffect(streamUrl, retryKey) {
        val url = streamUrl ?: return@LaunchedEffect
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val call = client.newCall(Request.Builder().url(url).build())
        status = "正在连接实时画面…"
        withContext(Dispatchers.IO) {
            try {
                val resp = call.execute()
                if (!resp.isSuccessful) {
                    status = "连接失败：HTTP ${resp.code}"
                    return@withContext
                }
                val input = DataInputStream(resp.body.byteStream())
                status = "实时画面已连接"
                val decoder = H264Decoder()
                var codecConfig: ByteArray? = null
                var padW = 0
                var padH = 0
                var colorSpace: ColorSpace? = null
                var synced = false
                var fpsCount = 0
                var fpsAt = System.currentTimeMillis()

                fun decodeAndShow(annexB: ByteArray) {
                    val cs = colorSpace ?: return
                    if (padW <= 0 || padH <= 0) return
                    // jcodec 会把传入缓冲当作解码输出帧的 planes，且可能作为后续 P 帧的参考帧，
                    // 所以每帧必须新分配（复用会导致参考帧被覆盖 → 花屏）。尺寸按宏块对齐。
                    val buf = try { Picture.create(padW, padH, cs).data } catch (e: Exception) { return }
                    val pic = try {
                        decoder.decodeFrame(ByteBuffer.wrap(annexB), buf)
                    } catch (e: Exception) { null } ?: return
                    val img = try { AWTUtil.toBufferedImage(pic) } catch (e: Exception) { return }
                    frame = MirrorFrame(img.toComposeImageBitmap(), img.width, img.height)
                    frameCount++
                    fpsCount++
                    val now = System.currentTimeMillis()
                    if (now - fpsAt >= 1000) {
                        measuredFps = ((fpsCount * 1000L) / (now - fpsAt)).toInt().coerceIn(0, 120)
                        fpsCount = 0
                        fpsAt = now
                    }
                }

                while (true) {
                    val type = input.readUnsignedByte()
                    val len = input.readInt()
                    if (type == 2) continue  // 心跳
                    if (len < 0 || len > 8 * 1024 * 1024) { status = "包长度异常，已断开"; break }
                    val payload = ByteArray(len)
                    input.readFully(payload)
                    when (type) {
                        0 -> {  // SPS/PPS：解析帧尺寸与色彩空间，用于分配解码缓冲
                            codecConfig = payload
                            try {
                                val meta = decoder.getCodecMeta(ByteBuffer.wrap(payload))
                                val w = meta.size.width
                                val h = meta.size.height
                                padW = (w + 15) / 16 * 16
                                padH = (h + 15) / 16 * 16
                                colorSpace = meta.color ?: ColorSpace.YUV420
                            } catch (e: Exception) {
                                padW = 0; padH = 0; colorSpace = null
                            }
                        }
                        1 -> {  // 关键帧（IDR）：必须已有 SPS/PPS
                            val cfg = codecConfig ?: continue
                            synced = true
                            decodeAndShow(cfg + payload)
                        }
                        3 -> { if (synced) decodeAndShow(payload) }  // 增量帧（P）
                    }
                }
            } catch (e: java.io.EOFException) {
                status = "画面流已结束，点「重新连接」"
            } catch (e: java.io.IOException) {
                if (!call.isCanceled()) status = "画面流中断：${e.message}"
            } catch (e: Exception) {
                status = "画面异常：${e.javaClass.simpleName}: ${e.message}"
            } finally {
                call.cancel()
            }
        }
    }

    fun remote(op: String, extra: JSONObject.() -> Unit = {}) {
        scope.launch {
            val args = JSONObject().apply {
                put("op", op)
                if (!clientId.isNullOrBlank()) put("client_id", clientId)
                extra()
            }
            val r = try {
                PhoneTools.execute("phone_remote", args.toString())
            } catch (e: Exception) {
                actionResult = "失败：${e.message}"
                return@launch
            }
            actionResult = if (r.success) r.output else "失败：${r.output}"
            if (op == "mirror_start" || op == "mirror_stop") retryKey++
        }
    }

    fun mapPoint(pos: Offset): Pair<Int, Int>? {
        val f = frame ?: return null
        if (boxSize.width <= 0 || boxSize.height <= 0 || f.width <= 0 || f.height <= 0) return null
        val scale = min(boxSize.width.toFloat() / f.width, boxSize.height.toFloat() / f.height)
        val left = (boxSize.width - f.width * scale) / 2f
        val top = (boxSize.height - f.height * scale) / 2f
        val fx = ((pos.x - left) / scale).coerceIn(0f, f.width.toFloat())
        val fy = ((pos.y - top) / scale).coerceIn(0f, f.height.toFloat())
        val rw = if (realW > 0) realW else f.width
        val rh = if (realH > 0) realH else f.height
        return (fx / f.width * rw).toInt() to (fy / f.height * rh).toInt()
    }

    fun sendTap(pos: Offset) { val (x, y) = mapPoint(pos) ?: return; remote("tap") { put("x", x); put("y", y) } }
    fun sendLongPress(pos: Offset) { val (x, y) = mapPoint(pos) ?: return; remote("long_press") { put("x", x); put("y", y); put("duration_ms", 800) } }
    fun sendSwipe(a: Offset, b: Offset, d: Long) {
        val p1 = mapPoint(a) ?: return
        val p2 = mapPoint(b) ?: return
        remote("swipe") {
            put("start_x", p1.first); put("start_y", p1.second)
            put("end_x", p2.first); put("end_y", p2.second)
            put("duration_ms", d.coerceIn(60L, 1500L))
        }
    }
    fun sendScroll(pos: Offset, deltaY: Float) {
        val (x, y) = mapPoint(pos) ?: return
        val dy = -(deltaY * 3f).toInt().coerceIn(-1200, 1200)
        if (dy == 0) return
        remote("scroll") { put("x", x); put("y", y); put("dx", 0); put("dy", dy) }
    }

    val enter = rememberDialogEnter()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Box(
            Modifier.fillMaxSize().background(BgColor().copy(alpha = 0.64f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .size(900.dp, 900.dp)
                    .then(enter)
                    .background(glassPageBg(), RoundedCornerShape(Radius.MD))
                    .border(1.dp, BorderColor(), RoundedCornerShape(Radius.MD))
                    .padding(16.dp)
            ) {
                // 标题栏
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("手机实时投屏", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary())
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "$status · ${measuredFps}fps · 已收 $frameCount 帧" +
                                    if (realW > 0) " · 手机 ${realW}×$realH" else "",
                            fontSize = 11.sp, color = TextMuted()
                        )
                    }
                    SecondaryButton(if (showSettings) "收起设置" else "设置", onClick = { showSettings = !showSettings })
                    Spacer(Modifier.width(8.dp))
                    IconActionButton(Icons.Default.Close, onClick = onDismiss, contentDescription = "关闭", tint = TextSecondary())
                }
                Spacer(Modifier.height(8.dp))

                // 设置（可折叠）
                if (showSettings) {
                    GlassCard(padding = PaddingValues(12.dp)) {
                        SectionLabel("采集参数（修改后需停止并重新开启投屏生效）")
                        Spacer(Modifier.height(6.dp))
                        ParamRow("分辨率") {
                            ChipRow(listOf(480 to "480p", 720 to "720p", 1080 to "1080p"), resolution) { resolution = it }
                        }
                        Spacer(Modifier.height(6.dp))
                        ParamRow("帧率") {
                            ChipRow(listOf(15 to "15fps", 30 to "30fps", 60 to "60fps"), fps) { fps = it }
                        }
                        Spacer(Modifier.height(6.dp))
                        ParamRow("画质/码率") {
                            ChipRow(listOf(50 to "流畅", 70 to "标准", 85 to "高清", 95 to "超清"), quality) { quality = it }
                        }
                        Spacer(Modifier.height(6.dp))
                        HintText("JPEG 传输下分辨率/画质越高，可达帧率越低；1080p+高画质通常约 20-30fps，属 JPEG 物理上限。")
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // 工具栏
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton("开启投屏", onClick = {
                        remote("mirror_start") { put("resolution", resolution); put("fps", fps); put("quality", quality) }
                    })
                    SecondaryButton("停止", onClick = { remote("mirror_stop") })
                    SecondaryButton("重新连接", onClick = { retryKey++ })
                    Spacer(Modifier.weight(1f))
                    SecondaryButton("返回", onClick = { remote("key") { put("key", "back") } })
                    SecondaryButton("主页", onClick = { remote("key") { put("key", "home") } })
                    SecondaryButton("最近", onClick = { remote("key") { put("key", "recents") } })
                }
                Spacer(Modifier.height(8.dp))

                // 实时画面（真控制）
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(CardStrongColor(), RoundedCornerShape(Radius.SM))
                        .border(1.dp, BorderSubtle(), RoundedCornerShape(Radius.SM))
                        .onSizeChanged { boxSize = it }
                        .pointerInput(Unit) {
                            var pressStart: Offset? = null
                            var pressAt = 0L
                            var moved = false
                            awaitPointerEventScope {
                                while (true) {
                                    val e = awaitPointerEvent(PointerEventPass.Main)
                                    val ch = e.changes.firstOrNull() ?: continue
                                    when (e.type) {
                                        PointerEventType.Scroll -> {
                                            val d = ch.scrollDelta
                                            if (d.y != 0f) sendScroll(ch.position, d.y)
                                            e.changes.forEach { it.consume() }
                                        }
                                        PointerEventType.Press -> {
                                            pressStart = ch.position; pressAt = System.currentTimeMillis(); moved = false
                                            cursor = ch.position; showCursor = true
                                            e.changes.forEach { it.consume() }
                                        }
                                        PointerEventType.Move -> {
                                            cursor = ch.position; showCursor = true
                                            val s = pressStart
                                            if (s != null && (ch.position - s).getDistance() > 12f) moved = true
                                        }
                                        PointerEventType.Release -> {
                                            val s = pressStart
                                            if (s != null) {
                                                val held = System.currentTimeMillis() - pressAt
                                                when {
                                                    moved -> sendSwipe(s, ch.position, held)
                                                    held > 600 -> sendLongPress(s)
                                                    else -> sendTap(s)
                                                }
                                            }
                                            pressStart = null
                                            e.changes.forEach { it.consume() }
                                        }
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val f = frame
                    if (f != null) {
                        ComposeImage(
                            bitmap = f.bitmap,
                            contentDescription = "手机实时屏幕，点击/拖拽/滚轮可直接操作",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (showCursor) {
                            Box(
                                Modifier
                                    .offset { IntOffset(cursor.x.toInt() - 6, cursor.y.toInt() - 6) }
                                    .size(12.dp)
                                    .background(AccentColor().copy(alpha = 0.35f), CircleShape)
                                    .border(1.5.dp, AccentColor(), CircleShape)
                            )
                        }
                    } else {
                        Text(
                            "等待手机画面…\n若长时间无画面，请点「开启投屏」并在手机上完成系统授权",
                            fontSize = 13.sp, color = TextMuted(), textAlign = TextAlign.Center
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))

                // 键盘输入
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        GlassTextField(
                            value = textInput, onValueChange = { textInput = it },
                            placeholder = "输入文字后发送到手机当前输入框"
                        )
                    }
                    SecondaryButton("发送", onClick = {
                        if (textInput.isNotEmpty()) { remote("text") { put("text", textInput) }; textInput = "" }
                    })
                    SecondaryButton("删除", onClick = { remote("key") { put("key", "delete") } })
                    SecondaryButton("回车", onClick = { remote("key") { put("key", "enter") } })
                }
                Spacer(Modifier.height(6.dp))
                HintText(
                    "点击/拖拽画面操控手机，滚轮滚动，键盘输入文字。" +
                            if (actionResult.isNotBlank()) "  最近操作：$actionResult" else ""
                )
            }
        }
    }
}

@Composable
private fun ParamRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = TextSecondary(), modifier = Modifier.width(72.dp))
        content()
    }
}

@Composable
private fun ChipRow(options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, text) ->
            GlassChip(text = text, selected = selected == value, onClick = { onSelect(value) })
        }
    }
}
