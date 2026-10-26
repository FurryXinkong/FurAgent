package net.xinkong.furagent.desktop.tools

import net.xinkong.furagent.shared.util.DateFormats
import net.xinkong.furagent.desktop.core.DesktopConfirmation
import net.xinkong.furagent.desktop.data.HistoryStore
import net.xinkong.furagent.shared.tools.ToolResult
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 修改历史（内置 git）。
 *
 * 数据来自 BackupHelper 的 manifest：每次 write_file / code_edit 都会留一条
 * 带 unified patch 的记录（+N/-M）。这里把它暴露成 AI 可调用的工具：
 *   - op=list    时间线（可按 path 过滤）
 *   - op=diff    看某条记录的 patch
 *   - op=restore 回档到某条记录（会弹确认）
 *
 * 设计取舍：不做真正的 git 仓库（不建 .git、不做分支/提交树），
 * 只保留「每次编辑 = 一条带 patch 的快照」，够用且零依赖。
 */
object HistoryTools {

    data class ToolDef(
        val name: String,
        val displayName: String,
        val description: String,
        val paramsSchema: String
    )

    val TOOLS: List<ToolDef> = listOf(
        ToolDef(
            name = "history",
            displayName = "修改历史",
            description = "查看/回档文件修改历史（内置 git）。每次 AI 写入或编辑文件都会留下一条带 patch 的记录。" +
                    "op=list 列出时间线（可选 path 过滤、limit 限制条数）；" +
                    "op=diff 查看某条记录的 unified patch（用 index 或 path 指定）；" +
                    "op=restore 把文件回档到某条记录之前的状态（会弹确认，index 必填）。" +
                    "index 是 list 返回的序号（0 = 最近一次）。" +
                    "scope 控制范围：session（默认）= 只看当前会话；all = 所有会话。",
            paramsSchema = """{"type":"object","properties":{"op":{"type":"string","enum":["list","diff","restore"]},"scope":{"type":"string","enum":["session","all"],"description":"session（默认）= 只看当前会话；all = 所有会话"},"path":{"type":"string"},"index":{"type":"integer"},"limit":{"type":"integer"}},"required":["op"]}"""
        )
    )

    private val timeFmt = DateFormats.shortDateTimeSecFormatter()

    suspend fun execute(args: JSONObject, backupDir: File?): ToolResult {
        if (backupDir == null) return ToolResult.fail("备份目录不可用（未开启写前备份？）")
        val op = args.optString("op", "list").trim().ifBlank { "list" }
        val pathFilter = args.optString("path", "").trim()
        val limit = args.optInt("limit", 30).coerceIn(1, 200)
        // 【会话隔离】scope=session 只看当前会话（含子代理），scope=all 看全部
        val scope = args.optString("scope", "session").trim().ifBlank { "session" }
        // 【P0-2 收尾】从协程上下文读 sessionId，不再读已删除的全局 var
        val currentSession = net.xinkong.furagent.desktop.core.currentSessionId()

        val all = HistoryStore.listHistory(backupDir, 500)
        val scoped = if (scope == "all" || currentSession.isBlank()) all
        else all.filter { it.sessionId == currentSession }
        val filtered = if (pathFilter.isBlank()) scoped
        else scoped.filter { it.path.contains(pathFilter, ignoreCase = true) }

        return when (op) {
            "list" -> {
                if (filtered.isEmpty()) {
                    ToolResult.ok("暂无修改记录。开启「写前备份与回档」后，AI 每次写入/编辑文件都会留下 patch 记录。")
                } else {
                    val sb = StringBuilder()
                    sb.append("修改历史（新 → 旧，共 ${filtered.size} 条，范围：")
                        .append(if (scope == "all") "全部会话" else "本会话")
                        .append("）：\n")
                    filtered.take(limit).forEachIndexed { i, e ->
                        sb.append("#$i  ")
                            .append(timeFmt.format(Date(e.ts)))
                            .append("  ")
                            .append(File(e.path).name)
                            .append("  [").append(actionLabel(e.action)).append("]")
                        if (e.added > 0 || e.removed > 0) {
                            sb.append("  +").append(e.added).append(" -").append(e.removed)
                        }
                        sb.append("\n    ").append(e.path).append("\n")
                    }
                    sb.append("\n看具体改动：history(op=diff, index=N)；回档：history(op=restore, index=N)")
                    ToolResult.ok(sb.toString())
                }
            }
            "diff" -> {
                val idx = args.optInt("index", -1)
                val entry = when {
                    idx >= 0 -> filtered.getOrNull(idx)
                    pathFilter.isNotBlank() -> filtered.firstOrNull()
                    else -> null
                } ?: return ToolResult.fail("找不到对应记录（先用 op=list 看序号）")
                // 【性能】patch 现在是独立文件，统一走 readPatch（兼容旧的内联记录）
                val patchText = HistoryStore.readPatch(entry)
                if (patchText.isBlank()) {
                    ToolResult.ok(
                        "该记录没有文本 patch（二进制文件，或新建/删除文件）。\n" +
                                "文件：${entry.path}\n动作：${actionLabel(entry.action)}\n" +
                                "时间：${timeFmt.format(Date(entry.ts))}"
                    )
                } else {
                    ToolResult.ok(
                        "文件：${entry.path}\n动作：${actionLabel(entry.action)}  +${entry.added} -${entry.removed}\n" +
                                "时间：${timeFmt.format(Date(entry.ts))}\n\n${patchText.take(6000)}"
                    )
                }
            }
            "restore" -> {
                val idx = args.optInt("index", -1)
                if (idx < 0) return ToolResult.fail("op=restore 需要 index（先用 op=list 看序号）")
                val entry = filtered.getOrNull(idx)
                    ?: return ToolResult.fail("找不到序号 $idx 的记录")
                val restoreBytes = HistoryStore.readBackup(entry)
                if (restoreBytes == null) {
                    return ToolResult.fail("该记录没有备份内容（新建文件无法回档到「不存在」状态）")
                }
                // 【语义说明】回档 = 把文件恢复到「这条记录发生之前」的状态，
                // 不是重放 patch，也不是顺序回滚。只影响这一个文件。
                // 如果该文件之后还有 N 条记录，回档会覆盖掉那 N 次修改。
                val laterCount = all.count { it.path == entry.path && it.ts > entry.ts }
                val approved = DesktopConfirmation.request(
                    "回档确认",
                    "把文件回档到 ${timeFmt.format(Date(entry.ts))} 之前的状态：\n${entry.path}\n\n" +
                            if (laterCount > 0)
                                "[警告] 该文件之后还有 $laterCount 次修改，回档会覆盖它们（但历史记录仍保留，可再回档撤销）。\n\n是否允许？"
                            else "是否允许？"
                )
                if (!approved) return ToolResult.fail("用户已拒绝回档")
                val bytes = restoreBytes
                return try {
                    val target = File(entry.path)
                    // 【可撤销】回档前先把当前内容也备份一份，并记一条 restore 记录，
                    // 这样回档本身也能被回档（否则回档后历史里没有任何痕迹）。
                    val currentBytes = try {
                        if (target.exists()) target.readBytes() else null
                    } catch (_: Exception) { null }
                    target.parentFile?.mkdirs()
                    target.writeBytes(bytes)
                    HistoryStore.recordEdit(
                        backupDir, entry.path, "restore",
                        currentBytes, bytes, "history",
                        sessionId = net.xinkong.furagent.desktop.core.currentSessionId()
                    )
                    ToolResult.ok(
                        "已回档：${entry.path}\n" +
                                "恢复到 ${timeFmt.format(Date(entry.ts))} 之前的状态。\n" +
                                if (laterCount > 0)
                                    "（覆盖了之后的 $laterCount 次修改；本次回档已记入历史，可再回档撤销）"
                                else "（本次回档已记入历史，可再回档撤销）"
                    )
                } catch (e: Exception) {
                    ToolResult.fail("回档失败：${e.message}")
                }
            }
            else -> ToolResult.fail("op 取值为 list / diff / restore")
        }
    }

    private fun actionLabel(action: String): String = when (action) {
        "write" -> "写入"
        "append" -> "追加"
        "code_edit" -> "编辑"
        "restore" -> "回档"
        else -> action
    }
}
