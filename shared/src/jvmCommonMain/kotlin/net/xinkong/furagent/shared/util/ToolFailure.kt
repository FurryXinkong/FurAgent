package net.xinkong.furagent.shared.util

/**
 * 工具返回是否算"失败"。
 *
 * 契约见 README 4.12 / 15.1：
 *   1. 以 "失败" 开头
 *   2. 等于 "已取消"
 *   3. 包含特定的关键词
 *
 * 【冗余统一 #3】三端统一到这一处。
 */
fun isToolFailure(result: String): Boolean {
    if (result.startsWith("失败")) return true
    if (result == "已取消") return true

    // 【防误判】下面这些关键词只在"结果开头"附近匹配。
    // 原因：工具成功返回的正文里可能恰好包含"异常：""权限不足"等字样
    // （比如读日志、读代码、读报错文本），若全文 contains 会被误判成失败，
    // 进而触发"连续失败 3 次中断"，把正常任务打断。
    // 真正的失败信息一定出现在结果最前面，所以只看开头 200 字符。
    val head = if (result.length > 200) result.substring(0, 200) else result
    if (head.contains("bad base-64")) return true
    if (head.contains("执行异常")) return true
    if (head.contains("工具执行异常")) return true
    if (head.contains("异常：")) return true
    if (head.contains("权限不足")) return true
    if (head.contains("操作取消")) return true
    return false
}

/**
 * 从工具失败结果中提取简短原因摘要（最多 50 字符），用于 UI 显示。
 * 返回空串表示无法提取有意义的原因。
 */
fun extractFailureReason(result: String): String {
    if (result == "已取消") return "已取消"
    // 取第一行，截断到 50 字符
    val firstLine = result.lineSequence().firstOrNull()?.trim() ?: return ""
    if (firstLine.length <= 50) return firstLine
    return firstLine.substring(0, 47) + "…"
}