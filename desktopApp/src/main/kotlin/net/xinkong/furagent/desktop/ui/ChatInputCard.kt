package net.xinkong.furagent.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.desktop.system.NativeFilePicker
import net.xinkong.furagent.desktop.ui.theme.Radius
import net.xinkong.furagent.desktop.ui.theme.Shadow
import net.xinkong.furagent.shared.ThinkingLevel
import net.xinkong.furagent.shared.icons.Icons
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File

/**
 * 聊天输入卡片（从 ChatPage.kt 拆分）。
 *
 * 包含 ChatInputCard / ChipSurface / ThinkingToggleChip / ModelQuickSelector / AttachmentThumb。
 */

@Composable
fun ChatInputCard(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isLoading: Boolean,
    enabled: Boolean,
    readOnlyHint: String? = null,
    attachments: List<PendingAttachment>,
    onAttachmentsChange: (List<PendingAttachment>) -> Unit,
    onImageClick: (String) -> Unit,
    modelName: String,
    thinkingLevel: ThinkingLevel,
    supportsThinking: Boolean,
    chatProfiles: List<Pair<String, String>>,
    currentProfileId: String,
    onThinkingLevelChange: (ThinkingLevel) -> Unit,
    onSelectProfile: (String) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }

    var fieldValue by remember {
        mutableStateOf(
            TextFieldValue(inputText, TextRange(inputText.length))
        )
    }
    LaunchedEffect(inputText) {
        // Guard: don't overwrite while IME composition is active,
        // which would forcibly end the composition (pinyin flattened to plain text).
        if (fieldValue.text != inputText && fieldValue.composition == null) {
            fieldValue = TextFieldValue(inputText, TextRange(inputText.length))
        }
    }

    val currentAttachments by rememberUpdatedState(attachments)
    val currentOnAttachmentsChange by rememberUpdatedState(onAttachmentsChange)
    DisposableEffect(Unit) {
        val dispatcher = object : java.awt.KeyEventDispatcher {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (!ChatFocusTracker.chatPageVisible) return false
                if (e.id == KeyEvent.KEY_PRESSED &&
                    e.keyCode == KeyEvent.VK_V &&
                    (e.modifiersEx and InputEvent.CTRL_DOWN_MASK) != 0
                ) {
                    val img = SafeClipboard.getImage() ?: return false
                    try {
                        val w = img.getWidth(null)
                        val h = img.getHeight(null)
                        if (w <= 0 || h <= 0) return false
                        val tmp = File.createTempFile("paste_", ".png")
                        val buffered = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                        val g = buffered.createGraphics()
                        g.drawImage(img, 0, 0, null)
                        g.dispose()
                        javax.imageio.ImageIO.write(buffered, "png", tmp)
                        currentOnAttachmentsChange(
                            currentAttachments + PendingAttachment(tmp.absolutePath, tmp.name, true)
                        )
                        return true
                    } catch (ex: Exception) {
                        lastError = "粘贴失败：${ex.message}"
                        return false
                    }
                }
                return false
            }
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
        onDispose {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
        }
    }

    fun pickImages() {
        val files = NativeFilePicker.pickFiles(
            title = "选择图片",
            extensions = listOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        )
        if (files.isEmpty()) return
        onAttachmentsChange(attachments + files.map {
            PendingAttachment(path = it.absolutePath, name = it.name, isImage = true)
        })
    }

    fun pickFiles() {
        val files = NativeFilePicker.pickFiles(title = "选择文件")
        if (files.isEmpty()) return
        onAttachmentsChange(attachments + files.map {
            val ext = it.extension.lowercase()
            PendingAttachment(path = it.absolutePath, name = it.name, isImage = ext in IMAGE_EXTS)
        })
    }

    val shadowColor = glassShadowColor()
    Box(
        Modifier
            .fillMaxWidth()
            .shadow(
                elevation = Shadow.XL,
                shape = RoundedCornerShape(Radius.LG),
                clip = false,
                ambientColor = shadowColor,
                spotColor = shadowColor
            )
    ) {
        Surface(
            color = CardColor(),
            shape = RoundedCornerShape(Radius.LG),
            border = BorderStroke(
                1.dp,
                if (focused) AccentColor().copy(alpha = 0.65f)
                else BorderColor()
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {

                AnimatedVisibility(
                    visible = attachments.isNotEmpty(),
                    enter = fadeIn(tween(180)) +
                            expandVertically(
                                animationSpec = tween(220, easing = FastOutSlowInEasing),
                                expandFrom = Alignment.Top
                            ),
                    exit = fadeOut(tween(140)) +
                            shrinkVertically(
                                animationSpec = tween(180, easing = FastOutSlowInEasing),
                                shrinkTowards = Alignment.Top
                            )
                ) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        attachments.forEach { att ->
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(tween(160)) +
                                        expandVertically(tween(200)) +
                                        expandHorizontally(tween(200)),
                                exit = fadeOut(tween(120)) +
                                        shrinkVertically(tween(160)) +
                                        shrinkHorizontally(tween(160))
                            ) {
                                AttachmentThumb(
                                    att = att,
                                    onClick = { if (att.isImage) onImageClick(att.path) },
                                    onRemove = { onAttachmentsChange(attachments - att) }
                                )
                            }
                        }
                    }
                }

                BasicTextField(
                    value = fieldValue,
                    onValueChange = { newValue ->
                        fieldValue = newValue
                        if (enabled) onInputChange(newValue.text)
                    },
                    enabled = enabled,
                    textStyle = TextStyle(color = TextPrimary(), fontSize = 15.sp, lineHeight = 24.sp),
                    cursorBrush = SolidColor(AccentColor()),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp, max = 200.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            event.type == KeyEventType.KeyDown && event.key == Key.Enter && if (event.isShiftPressed) {
                                false
                            } else {
                                if (!isLoading &&
                                    (fieldValue.text.isNotBlank() || attachments.isNotEmpty())
                                ) {
                                    onSend()
                                }
                                true
                            }
                        },
                    maxLines = 10,
                    decorationBox = { inner ->
                        Box {
                            if (fieldValue.text.isEmpty()) {
                                Text(
                                    if (enabled) "输入消息…（Shift+Enter 换行）"
                                    else readOnlyHint ?: "请先在设置中配置 API Key",
                                    fontSize = 15.sp, color = TextMuted()
                                )
                            }
                            inner()
                        }
                    }
                )

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 模型胶囊：点开弹面板（模型选择 + 推理强度滑块）
                    ModelSettingsButton(
                        modelName = modelName,
                        profiles = chatProfiles,
                        currentProfileId = currentProfileId,
                        onSelectProfile = onSelectProfile,
                        thinkingLevel = thinkingLevel,
                        supportsThinking = supportsThinking,
                        onThinkingLevelChange = onThinkingLevelChange
                    )

                    Spacer(Modifier.weight(1f))

                    Box {
                        HoverTip("添加附件") {
                            IconActionButton(
                                Icons.Default.Add,
                                onClick = { menuOpen = true },
                                contentDescription = "更多",
                                tint = TextSecondary()
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            containerColor = CardColor()
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Image, null, tint = TextSecondary(), modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text("选择图片", fontSize = 13.sp, color = TextPrimary())
                                    }
                                },
                                onClick = { menuOpen = false; pickImages() }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = TextSecondary(), modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text("选择文件", fontSize = 13.sp, color = TextPrimary())
                                    }
                                },
                                onClick = { menuOpen = false; pickFiles() }
                            )
                        }
                    }

                    if (isLoading) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(ErrorColor())
                                .clickable(onClick = onStop),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Stop, "停止", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    } else {
                        val canSend = (fieldValue.text.isNotBlank() || attachments.isNotEmpty()) && enabled
                        // 渐变淡入：从「不可发」到「可发」时不会硬切
                        val sendGradAlpha by androidx.compose.animation.core.animateFloatAsState(
                            targetValue = if (canSend) 1f else 0f,
                            animationSpec = tween(200),
                            label = "sendButtonGradient"
                        )
                        Box(
                            Modifier.size(36.dp).clip(CircleShape)
                                .background(SolidColor(CardStrongColor()))
                                .background(FluidGradient.diagonal(alpha = sendGradAlpha))
                                .clickable(enabled = canSend) { onSend() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send, "发送",
                                tint = if (canSend) OnAccent() else TextMuted(),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                lastError?.let { msg ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(msg, fontSize = 11.sp, color = ErrorColor(), modifier = Modifier.weight(1f))
                        Text("×", fontSize = 14.sp, color = TextSecondary(),
                            modifier = Modifier.clickable { lastError = null })
                    }
                }
            }
        }
    }
}

// ── 输入区小控件 ────────────────────────────────────────

@Composable
private fun ChipSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    accent: Color = AccentColor(),
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Surface(
        color = when {
            active -> AccentSoft()
            hovered -> CardHoverColor()
            else -> Color.Transparent
        },
        shape = RoundedCornerShape(Radius.SM),
        border = BorderStroke(
            1.dp,
            when {
                active -> accent.copy(alpha = 0.45f)
                hovered -> BorderColor()
                else -> BorderSubtle()
            }
        ),
        modifier = modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

// ── 附件缩略图 ──────────────────────────────────────────

@Composable
private fun AttachmentThumb(
    att: PendingAttachment,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Box(
        Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(Radius.SM))
            .background(CardStrongColor())
            .border(1.dp, BorderColor(), RoundedCornerShape(Radius.SM))
    ) {
        if (att.isImage) {
            val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, att.path) {
                value = withContext(Dispatchers.IO) {
                    try {
                        val img = javax.imageio.ImageIO.read(File(att.path))
                            ?: return@withContext null
                        val maxDim = 128
                        val scale = minOf(
                            maxDim.toFloat() / img.width,
                            maxDim.toFloat() / img.height,
                            1f
                        )
                        val w = (img.width * scale).toInt().coerceAtLeast(1)
                        val h = (img.height * scale).toInt().coerceAtLeast(1)
                        val buffered = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                        val g = buffered.createGraphics()
                        g.drawImage(img, 0, 0, w, h, null)
                        g.dispose()
                        buffered.toComposeImageBitmap()
                    } catch (_: Exception) { null }
                }
            }
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = att.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clickable(onClick = onClick)
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Image, null, tint = TextMuted(), modifier = Modifier.size(22.dp))
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = TextSecondary(), modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(att.name.take(6), fontSize = 8.sp, color = TextMuted(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Close, "移除", tint = Color.White, modifier = Modifier.size(12.dp))
        }
    }
}