package net.xinkong.furagent.shared.util

import net.xinkong.furagent.shared.data.ChatMessage
import org.json.JSONObject

/**
 * 会话统计（双端共用）：
 *   - 本次会话修改过的文件列表（聚合写类 tool_call）
 *   - 底部状态栏：轮数 / 工具步数 / 估算 token
 *
 * tool_call 消息 content 格式（双端一致）：`显示名\n{args pretty JSON}`。
 * 显示名经过 friendlyName 转换，因此按「JSON 里是否携带路径字段」+ 名称关键词
 * 双条件识别，避免中文显示名与原工具名对不上。
 */
object SessionAnalytics {

    /** 会话里被写类工具动过的文件。 */
    data class ModifiedFile(
        val path: String,
        /** 被操作次数（同路径多次写入累计） */
        val count: Int,
        /** 最近一次动作（write / edit / delete / move / other） */
        val lastAction: String
    )

    data class SessionStats(
        val rounds: Int,
        val toolSteps: Int,
        val estTokens: Int,
        val imageCount: Int
    )

    /** 写类工具的显示名/原名关键词（小写匹配）。 */
    private val WRITE_HINTS = listOf(
        "write", "写入", "创建文件", "edit", "编辑", "code_edit", "代码编辑",
        "delete", "删除", "move", "移动", "rename", "重命名", "copy", "复制",
        "append", "追加", "download", "下载"
    )

    /**
     * 聚合本会话修改过的文件（保序去重，按首次出现排序）。
     * code_edit 的 files[].path、write/move 的 path/newPath/dst 都收。
     */
    fun collectModifiedFiles(messages: List<ChatMessage>): List<ModifiedFile> {
        val linked = LinkedHashMap<String, ModifiedFile>()
        messages.forEach { m ->
            if (m.type != "tool_call") return@forEach
            val nl = m.content.indexOf('\n')
            val namePart = if (nl >= 0) m.content.substring(0, nl) else m.content
            val argsPart = if (nl >= 0) m.content.substring(nl + 1) else ""
            val lower = namePart.lowercase()
            val looksWrite = WRITE_HINTS.any { lower.contains(it) }
            if (!looksWrite) return@forEach

            val paths = extractPaths(argsPart)
            if (paths.isEmpty()) return@forEach

            val action = when {
                lower.contains("delete") || lower.contains("删除") -> "delete"
                lower.contains("move") || lower.contains("rename") || lower.contains("移动")
                    || lower.contains("重命名") -> "move"
                lower.contains("edit") || lower.contains("编辑") -> "edit"
                else -> "write"
            }
            paths.forEach { p ->
                val prev = linked[p]
                linked[p] = if (prev == null) ModifiedFile(p, 1, action)
                else prev.copy(count = prev.count + 1, lastAction = action)
            }
        }
        return linked.values.toList()
    }

    /** 从 tool_call 参数 JSON 提取所有路径字段。 */
    private fun extractPaths(argsJson: String): List<String> {
        if (argsJson.isBlank()) return emptyList()
        return try {
            val o = JSONObject(argsJson)
            val out = LinkedHashSet<String>()
            fun add(v: String?) {
                if (!v.isNullOrBlank()) out.add(v)
            }
            add(o.optString("path", "").ifBlank { null })
            add(o.optString("newPath", "").ifBlank { null })
            add(o.optString("oldPath", "").ifBlank { null })
            add(o.optString("dst", "").ifBlank { null })
            add(o.optString("to", "").ifBlank { null })
            o.optJSONArray("files")?.let { arr ->
                for (i in 0 until arr.length()) {
                    add(arr.optJSONObject(i)?.optString("path", "")?.ifBlank { null })
                }
            }
            out.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 会话统计。
     * token 用 TokenEstimator 经验公式估算（CJK 1.5 字符/tok、其他 4 字符/tok），
     * 仅供 UI 展示，误差约 ±15%，不做计费依据。
     */
    fun sessionStats(messages: List<ChatMessage>): SessionStats {
        var rounds = 0
        var toolSteps = 0
        var images = 0
        val sb = StringBuilder()
        messages.forEach { m ->
            when {
                m.role == "user" && m.type == "text" && m.content.isNotBlank() -> rounds++
                m.type == "tool_call" -> toolSteps++
            }
            images += m.imagePaths.size
            if (m.type != "tool_call" && m.type != "tool_result") {
                sb.append(m.content)
            } else {
                // 工具消息也占上下文，一并估算
                sb.append(m.content)
            }
        }
        val textTokens = TokenEstimator.estimate(sb.toString())
        return SessionStats(
            rounds = rounds,
            toolSteps = toolSteps,
            estTokens = textTokens + images * TokenEstimator.PER_IMAGE_TOKENS,
            imageCount = images
        )
    }

    /**
     * 会话真实开销（按 Agent 实际请求重放算出来）。
     *
     * 【为什么不能用内容长度之和】
     * Agent 每轮请求都要把当时的完整历史重发一次：
     * 10 轮对话里，第 1 轮那句用户消息会被重发 10 次。
     * 所以真实输入量远大于把消息拼起来的长度，
     * 旧版那个“累计 token”严重低估了花费。
     */
    data class CostStats(
        /** 请求次数（含工具循环里的每一轮） */
        val requests: Int,
        /** 累计输入 token（每轮完整 prompt 之和） */
        val promptTokens: Long,
        /** 累计输出 token（助手正文 + 工具调用参数） */
        val completionTokens: Long
    ) {
        val totalTokens: Long get() = promptTokens + completionTokens
    }

    fun costStats(messages: List<ChatMessage>, systemPromptTokens: Int = 3000): CostStats {
        var requests = 0
        var promptTokens = 0L
        var completionTokens = 0L
        // window = 本轮请求里已存在的内容，即历史长度
        var window = systemPromptTokens.toLong()
        var inSameResponse = false

        messages.forEach { m ->
            val isAssistantOutput =
                (m.role == "assistant" && m.type == "text") || m.type == "tool_call"
            val tokens = estMessageTokens(m).toLong()
            if (isAssistantOutput) {
                if (!inSameResponse) {
                    // 新一次请求：当前历史全部重发
                    requests++
                    promptTokens += window
                }
                completionTokens += tokens
                window += tokens
                inSameResponse = true
            } else {
                window += tokens
                inSameResponse = false
            }
        }
        return CostStats(requests, promptTokens, completionTokens)
    }

    /** 单条消息的 token 估算（正文 + 图片）。 */
    private fun estMessageTokens(m: ChatMessage): Int =
        TokenEstimator.estimate(m.content) + m.imagePaths.size * TokenEstimator.PER_IMAGE_TOKENS

    /**
     * 修改文件的总大小（字节）。读文件元数据，失败/不存在按 0 计。
     * 供「任务完成结果卡」显示总大小（如 602.2 KB）。
     */
    fun totalSizeBytes(files: List<ModifiedFile>): Long {
        var total = 0L
        files.forEach { f ->
            try {
                val file = java.io.File(f.path)
                if (file.exists() && file.isFile) total += file.length()
            } catch (_: Exception) {
            }
        }
        return total
    }
}
