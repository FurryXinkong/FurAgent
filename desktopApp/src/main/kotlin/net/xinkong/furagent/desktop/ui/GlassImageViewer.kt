package net.xinkong.furagent.desktop.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import net.xinkong.furagent.shared.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 图片查看器。
 *
 * 拆分 从 Glass.kt 抽出（原文件 1400+ 行，职责过杂）。同包，引用零改动。
 */

@Composable
fun ImageViewerDialog(
    path: String,
    onDismiss: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "imgViewerScrim"
    )
    val contentScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.92f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "imgViewerScale"
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "imgViewerAlpha"
    )

    val loaded by produceState<Result<BufferedImage>?>(null, path) {
        value = withContext(Dispatchers.IO) {
            try {
                val f = File(path)
                if (!f.exists() || !f.isFile) {
                    Result.failure(Exception("文件不存在：$path"))
                } else {
                    val img = ImageIO.read(f)
                    if (img == null) {
                        Result.failure(Exception("无法解码图片"))
                    } else {
                        Result.success(img)
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

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
                .background(Color.Black.copy(alpha = 0.94f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = contentScale
                        scaleY = contentScale
                        this.alpha = contentAlpha
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.5f, 8f)
                            if (scale > 1f) {
                                offset += pan
                            } else {
                                offset = Offset.Zero
                            }
                        }
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { },
                contentAlignment = Alignment.Center
            ) {
                val state = loaded
                when {
                    state == null -> CircularProgressIndicator(color = Color.White)
                    state.isFailure -> Text(
                        "无法加载图片：${state.exceptionOrNull()?.message ?: "未知错误"}",
                        color = Color.White, fontSize = 14.sp
                    )
                    else -> {
                        val buffered: BufferedImage? = state.getOrNull()
                        if (buffered == null) {
                            Text("图片为空", color = Color.White)
                        } else {
                            val imageBitmap: ImageBitmap = remember(buffered) {
                                buffered.toComposeImageBitmap()
                            }
                            Image(
                                bitmap = imageBitmap,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(48.dp)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = offset.x
                                        translationY = offset.y
                                    }
                            )
                        }
                    }
                }
            }

            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
                    .graphicsLayer { this.alpha = contentAlpha },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (scale != 1f || offset != Offset.Zero) {
                    HoverTip("重置缩放与位置") {
                        IconActionButton(
                            icon = Icons.Default.RestartAlt,
                            onClick = { scale = 1f; offset = Offset.Zero },
                            tint = Color.White,
                            size = 40.dp
                        )
                    }
                }
                HoverTip("关闭") {
                    IconActionButton(
                        icon = Icons.Default.Close,
                        onClick = onDismiss,
                        tint = Color.White,
                        size = 40.dp
                    )
                }
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
                    .graphicsLayer { this.alpha = contentAlpha },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    File(path).name,
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (scale != 1f) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${(scale * 100).toInt()}%",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
