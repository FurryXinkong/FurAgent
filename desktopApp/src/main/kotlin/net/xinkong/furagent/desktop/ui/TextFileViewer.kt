package net.xinkong.furagent.desktop.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xinkong.furagent.shared.icons.Icons
import net.xinkong.furagent.shared.ui.LineNumberedTextViewer
import net.xinkong.furagent.shared.ui.glassPageBg
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

/** 桌面端阅读器单次最多加载的行数，与安卓端保持一致。 */
private const val DESKTOP_READER_MAX_LINES = 20_000

/** 交给内置阅读器的扩展名；其余仍交给系统默认程序打开。 */
private val VIEWER_TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "log", "json", "jsonl", "xml", "yaml", "yml", "toml", "ini", "cfg",
    "conf", "properties", "csv", "tsv", "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx",
    "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "php", "sh", "bat", "ps1", "sql",
    "html", "htm", "css", "scss", "less", "vue", "gradle", "gitignore", "env", "diff", "patch",
    "tex", "rst", "adoc"
)

/** 是否用内置带行号阅读器打开（无扩展名的文件也按文本处理，避免误丢给系统）。 */
fun shouldOpenWithTextViewer(path: String): Boolean {
    val f = File(path)
    if (!f.isFile) return false
    val ext = f.extension.lowercase()
    return ext.isBlank() || ext in VIEWER_TEXT_EXTENSIONS
}

/**
 * 桌面端内置文本阅读器。
 *
 * 与安卓端共用 shared/ui/LineNumberedText.kt：带行号、LazyColumn 逐行渲染、
 * 可选中复制、跟随 MaterialTheme 深色/浅色。以前桌面端打开文件一律
 * rundll32/open/xdg-open 丢给外部程序，项目内没有自己的阅读器。
 */
@Composable
fun TextFileViewerDialog(path: String, onDismiss: () -> Unit) {
    var lines by remember { mutableStateOf<List<String>?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var copyText by remember { mutableStateOf("") }
    var readError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path) {
        val result = withContext(Dispatchers.IO) {
            try {
                val acc = ArrayList<String>()
                val buf = StringBuilder()
                var total = 0
                File(path).useLines { seq ->
                    val it = seq.iterator()
                    while (it.hasNext()) {
                        val line = it.next()
                        total++
                        if (acc.size < DESKTOP_READER_MAX_LINES) {
                            acc.add(line)
                            buf.append(line).append('\n')
                        }
                    }
                }
                ReaderLoadResult(acc, total > acc.size, buf.toString(), null)
            } catch (e: Exception) {
                ReaderLoadResult(
                    emptyList(), false, "",
                    "${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
        lines = result.lines
        hasMore = result.hasMore
        copyText = result.copyText
        readError = result.error
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .size(920.dp, 640.dp)
                    .background(glassPageBg(), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            File(path).name,
                            fontSize = 16.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            path,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭阅读器",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                val err = readError
                val loaded = lines
                when {
                    err != null -> Text(
                        "读取失败：$err",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                    loaded == null -> Text(
                        "加载中…",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> LineNumberedTextViewer(
                        lines = loaded,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        hasMore = hasMore,
                        loadedLineCount = loaded.size
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            try {
                                Toolkit.getDefaultToolkit().systemClipboard
                                    .setContents(StringSelection(copyText), null)
                                ToastBus.info("已复制全文")
                            } catch (e: Exception) {
                                ToastBus.error("复制失败：${e.message}")
                            }
                        },
                        enabled = copyText.isNotEmpty()
                    ) { Text("复制已加载内容") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

private data class ReaderLoadResult(
    val lines: List<String>,
    val hasMore: Boolean,
    val copyText: String,
    val error: String?
)
