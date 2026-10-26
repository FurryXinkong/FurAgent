package net.xinkong.furagent.shared.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import net.xinkong.furagent.shared.util.DateFormats
import java.util.UUID

/**
 * 会话通用编解码（双端共用）。
 *
 * Android SessionStore 和桌面 DesktopSessionStore 里
 * 有一批逐字相同的纯函数（generateTitle / duplicate / parseMessageFromJson）。
 * 现统一到这里。
 *
 * Base64 编解码参数化：
 *   - Android 用 android.util.Base64.decode(DEFAULT)（宽松，忽略空白）
 *   - 桌面用 java.util.Base64.getDecoder().decode()（严格）
 * 调用方通过 lambda 传入自己的实现。
 *
 * 缓存关键 parseMessageFromJson 必须保留 toolCallId，
 * 否则跨请求 tool_call_id 不一致，DeepSeek/OpenAI 前缀缓存全部失效。
 */
object SessionCodec {

    /**
     * 从消息列表生成会话标题。
     * 取第一条 user 的 text 消息，最多 20 字。
     */
    fun generateTitle(messages: List<ChatMessage>): String {
        val first = messages.firstOrNull { it.role == "user" && it.type == "text" } ?: return "新会话"
        val t = first.content.trim().replace("\n", " ")
        return if (t.length > 20) t.take(20) + "…" else t
    }

    /**
     * 复制一个会话：新 id、新时间戳、新标题（自动加"（副本）"后缀）。
     * 只构造新 Session 对象，不做持久化——持久化由调用方的 save 完成。
     */
    fun duplicateSession(src: Session): Session {
        val newId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val newTitle = when {
            src.title.endsWith("（副本）") || src.title.endsWith("(副本)") -> src.title
            src.title.length > 26 -> src.title.take(26) + "…（副本）"
            else -> "${src.title}（副本）"
        }
        return Session(
            id = newId,
            title = newTitle,
            createdAt = now,
            updatedAt = now,
            messages = src.messages.map { it.copy(id = UUID.randomUUID().toString()) }
        )
    }

    /**
     * 从 JSON 反序列化一条消息。
     *
     * @param importedImagesDir 非 null 时会尝试解码 imageData 里的 base64 图片，
     *                          写入该目录；null 时跳过 imageData（load 场景不需要）。
     * @param decodeBase64 Base64 解码器，Android 传 DEFAULT 宽松版，桌面传严格版。
     * @return 解析成功返回 ChatMessage；单条解析失败返回 null（调用方自行计 skipped）
     */
    fun parseMessageFromJson(
        m: JSONObject,
        importedImagesDir: File?,
        decodeBase64: (String) -> ByteArray
    ): ChatMessage? {
        return try {
            val imagePaths = mutableListOf<String>()

            // 1. 优先尝试 imageData（导出时嵌入的 base64 图片，仅导入场景有）
            if (importedImagesDir != null) {
                val imageDataArr = m.optJSONArray("imageData")
                if (imageDataArr != null && imageDataArr.length() > 0) {
                    for (k in 0 until imageDataArr.length()) {
                        try {
                            val img = imageDataArr.optJSONObject(k) ?: continue
                            val data = img.optString("data", "")
                            val name = img.optString("name", "img_$k")
                            if (data.isBlank()) continue
                            val bytes = decodeBase64(data)
                            val target = File(
                                importedImagesDir,
                                "${System.currentTimeMillis()}_${k}_$name"
                            )
                            target.writeBytes(bytes)
                            imagePaths.add(target.absolutePath)
                        } catch (_: Exception) {
                            // 单张图片解码失败不影响整条消息
                        }
                    }
                }
            }

            // 2. 回退到 imagePaths（已有的本地路径）
            if (imagePaths.isEmpty()) {
                m.optJSONArray("imagePaths")?.let { ipArr ->
                    for (j in 0 until ipArr.length()) {
                        val p = ipArr.optString(j, "")
                        if (p.isNotBlank()) imagePaths.add(p)
                    }
                }
            }

            ChatMessage(
                id = m.optString("id", UUID.randomUUID().toString()),
                role = m.optString("role", "user"),
                content = m.optString("content", ""),
                type = m.optString("type", "text"),
                timestamp = m.optLong("timestamp", System.currentTimeMillis()),
                // 必须反序列化原始 tool_call_id。
                toolCallId = m.optString("toolCallId", ""),
                interrupted = m.optBoolean("interrupted", false),
                imagePaths = imagePaths,
                durationMs = m.optLong("durationMs", 0L),
                reasoning = m.optString("reasoning", ""),
                parallelGroup = m.optString("parallelGroup", "")
            )
        } catch (_: Exception) {
            null
        }
    }

    // 消息序列化

    /**
     * 消息 → JSONObject（只含 imagePaths，不含 imageData）。
     * 用于 save / doWriteSessionsNow 等"本机持久化"场景。
     *
     * 缓存关键 必须序列化 toolCallId（原始 API call_id），
     * 否则重启后前缀缓存全部 miss。
     */
    fun messageToJson(m: ChatMessage): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("role", m.role)
        put("content", m.content)
        put("type", m.type)
        put("timestamp", m.timestamp)
        put("toolCallId", m.toolCallId)
        put("interrupted", m.interrupted)
        if (m.durationMs > 0) put("durationMs", m.durationMs)
        if (m.reasoning.isNotBlank()) put("reasoning", m.reasoning)
        if (m.parallelGroup.isNotBlank()) put("parallelGroup", m.parallelGroup)
        if (m.imagePaths.isNotEmpty()) {
            put("imagePaths", JSONArray().apply {
                m.imagePaths.forEach { p -> put(p) }
            })
        }
    }

    /**
     * 消息 → JSONObject（含 imageData：base64 内嵌图片）。
     * 用于 backup 导出（跨设备打开会话仍能看到图片）。
     *
     * @param maxImageBytes 单张图片字节上限，超过则跳过
     * @param encodeBase64 Base64 编码器（Android / 桌面各自实现）
     */
    fun messageToJsonWithImages(
        m: ChatMessage,
        maxImageBytes: Long,
        encodeBase64: (ByteArray) -> String
    ): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("role", m.role)
        put("content", m.content)
        put("type", m.type)
        put("timestamp", m.timestamp)
        put("toolCallId", m.toolCallId)
        put("interrupted", m.interrupted)
        if (m.durationMs > 0) put("durationMs", m.durationMs)
        if (m.reasoning.isNotBlank()) put("reasoning", m.reasoning)
        if (m.parallelGroup.isNotBlank()) put("parallelGroup", m.parallelGroup)
        if (m.imagePaths.isNotEmpty()) {
            put("imagePaths", JSONArray().apply {
                m.imagePaths.forEach { p -> put(p) }
            })
            val imagesArr = JSONArray()
            m.imagePaths.forEach { p ->
                try {
                    val f = File(p)
                    if (f.exists() && f.isFile && f.length() in 1..maxImageBytes) {
                        val bytes = f.readBytes()
                        imagesArr.put(JSONObject().apply {
                            put("path", p)
                            put("name", f.name)
                            put("data", encodeBase64(bytes))
                        })
                    }
                } catch (_: Exception) { }
            }
            if (imagesArr.length() > 0) put("imageData", imagesArr)
        }
    }

    // Markdown 导出

    /**
     * 图片文件 → data URL（base64 内嵌）。
     * 超过 maxBytes 或读取失败返回 null。
     */
    private fun tryEmbedImageAsDataUrl(
        path: String,
        maxBytes: Long,
        encodeBase64: (ByteArray) -> String
    ): String? {
        return try {
            val f = File(path)
            if (!f.exists() || !f.isFile) return null
            if (f.length() > maxBytes) return null
            val bytes = f.readBytes()
            val mime = when (f.extension.lowercase()) {
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "bmp" -> "image/bmp"
                else -> "image/jpeg"
            }
            "data:$mime;base64,${encodeBase64(bytes)}"
        } catch (_: Exception) { null }
    }

    /**
     * 会话 → Markdown 文本。
     *
     * Android 端 tool_result 不显示耗时（showDuration=false），
     * 桌面端显示（showDuration=true）。这是两端既有的行为差异，不做对齐。
     *
     * @param hideToolMessages 是否隐藏工具调用/返回消息
     * @param maxImageBytes 单张图片字节上限
     * @param encodeBase64 Base64 编码器
     * @param showDuration tool_result 是否显示耗时
     */
    fun sessionToMarkdown(
        session: Session,
        hideToolMessages: Boolean,
        maxImageBytes: Long,
        encodeBase64: (ByteArray) -> String,
        showDuration: Boolean
    ): String {
        val sb = StringBuilder()
        sb.append("# ${session.title}\n\n")
        sb.append("导出时间：")
            .append(DateFormats.formatDateTime())
            .append("\n\n---\n\n")
        session.messages.forEach { m ->
            if (hideToolMessages && (m.type == "tool_call" || m.type == "tool_result")) return@forEach
            when (m.type) {
                "text" -> {
                    val r = if (m.role == "user") "用户" else "AI"
                    sb.append("**$r：**\n\n${m.content}\n\n")
                    m.imagePaths.forEach { p ->
                        val embedded = tryEmbedImageAsDataUrl(p, maxImageBytes, encodeBase64)
                        if (embedded != null) {
                            sb.append("![image]($embedded)\n\n")
                        } else {
                            sb.append("![image]($p)\n\n")
                        }
                    }
                }
                "tool_call" -> sb.append("> 工具调用：\n> ")
                    .append(m.content.replace("\n", "\n> ")).append("\n\n")
                "tool_result" -> {
                    if (showDuration && m.durationMs > 0) {
                        sb.append("> 工具返回（耗时 ${m.durationMs}ms）：\n> ")
                    } else {
                        sb.append("> 工具返回：\n> ")
                    }
                    sb.append(m.content.replace("\n", "\n> ")).append("\n\n")
                }
            }
        }
        return sb.toString()
    }

    // Markdown 导入

    /**
     * 解析 Markdown 文本为消息列表（不涉及文件读取 / 持久化）。
     *
     * 格式：
     *   # 标题
     *   导出时间：...
     *   ---
     *   **用户：** 内容
     *   **AI：** 内容
     *   > 工具调用：...
     *   > 工具返回：...
     *
     * 注意：md 是有损格式，id / timestamp / 图片会丢失，导入时重新生成。
     *
     * @param text Markdown 文本
     * @param fallbackTitle 没有 `# 标题` 时的回退标题（一般传文件名）
     * @return (title, messages)；解析失败或消息为空时返回 null
     */
    fun parseMarkdownSession(text: String, fallbackTitle: String): Pair<String, List<ChatMessage>>? {
        return try {
            val lines = text.split("\n")

            // 标题：第一个 "# xxx"
            var title = fallbackTitle
            for (l in lines) {
                val t = l.trim()
                if (t.startsWith("# ")) { title = t.removePrefix("# ").trim(); break }
                if (t.isNotEmpty() && !t.startsWith("#")) break
            }

            val msgs = mutableListOf<ChatMessage>()
            val now = System.currentTimeMillis()
            var i = 0
            // 跳过头部（标题 + 导出时间 + ---）
            while (i < lines.size) {
                val t = lines[i].trim()
                if (t == "---") { i++; break }
                i++
            }

            val sb = StringBuilder()
            var curRole: String? = null   // user / assistant
            var curType = "text"          // text / tool_call / tool_result

            fun flush() {
                val body = sb.toString().trim('\n', ' ', '\t')
                sb.setLength(0)
                val role = curRole ?: return
                if (body.isBlank()) return
                msgs.add(
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = role,
                        content = body,
                        type = curType,
                        timestamp = now + msgs.size
                    )
                )
            }

            while (i < lines.size) {
                val raw = lines[i]
                val t = raw.trim()
                when {
                    t == "**用户：**" -> { flush(); curRole = "user"; curType = "text" }
                    t == "**AI：**" -> { flush(); curRole = "assistant"; curType = "text" }
                    t == "> 工具调用：" -> { flush(); curRole = "assistant"; curType = "tool_call" }
                    t.startsWith("> 工具返回") -> { flush(); curRole = "assistant"; curType = "tool_result" }
                    t.startsWith("> ") -> sb.append(t.removePrefix("> ")).append('\n')
                    t == ">" -> sb.append('\n')
                    else -> sb.append(raw).append('\n')
                }
                i++
            }
            flush()

            if (msgs.isEmpty()) return null
            title to msgs
        } catch (_: Exception) {
            null
        }
    }
}
