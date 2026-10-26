package net.xinkong.furagent.shared.tools

/**
 * 行级 diff（unified patch 风格），给「修改历史」当内置 git 用。
 *
 * 为什么自己写：不想为了一个 patch 引入第三方 diff 库（Android/Desktop 共用，
 * 依赖越少越好）。行级 LCS 对代码文件足够，几百行的文件毫秒级完成。
 *
 * 输出格式（与 git diff 接近，便于人读）：
 * ```
 * --- a/D:/x/Foo.kt
 * +++ b/D:/x/Foo.kt
 * @@ -12,4 +12,5 @@
 *  context
 * -old line
 * +new line
 * ```
 */
object LineDiff {

    data class Result(
        /** unified patch 文本；二进制/无变化时为空串 */
        val patch: String,
        val added: Int,
        val removed: Int
    ) {
        val hasChange: Boolean get() = added > 0 || removed > 0
    }

    /**
     * 中间差异区超过这个行数就不做 LCS（O(n*m) 会吃内存）。
     * 注意：先做公共前后缀裁剪，所以只有「整文件重写」才会碰到这个上限。
     */
    private const val MAX_LCS_LINES = 4000

    /** 上下文行数（patch 里变更前后各保留几行） */
    private const val CONTEXT = 3

    fun diff(oldText: String, newText: String, path: String): Result {
        if (oldText == newText) return Result("", 0, 0)
        val a = oldText.lines()
        val b = newText.lines()

        // 关键优化 先裁掉公共前缀/后缀，只对中间差异区做 LCS。
        // 代码编辑通常只改局部，裁剪后中间区往往只有几十行，
        // 这样 3000+ 行的大文件也能给出精确 patch（之前直接退化成整文件替换）。
        var prefix = 0
        val maxPrefix = minOf(a.size, b.size)
        while (prefix < maxPrefix && a[prefix] == b[prefix]) prefix++

        var suffix = 0
        val maxSuffix = minOf(a.size, b.size) - prefix
        while (suffix < maxSuffix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++

        val aMid = a.subList(prefix, a.size - suffix)
        val bMid = b.subList(prefix, b.size - suffix)

        val ops = ArrayList<Op>(a.size + b.size)
        // 前缀作为上下文
        for (k in 0 until prefix) ops.add(Op(' '.code, a[k]))

        if (aMid.size > MAX_LCS_LINES || bMid.size > MAX_LCS_LINES) {
            // 中间区仍然巨大（整文件重写）：整段替换，但统计用真实行数
            aMid.forEach { ops.add(Op('-'.code, it)) }
            bMid.forEach { ops.add(Op('+'.code, it)) }
        } else {
            ops.addAll(lcsOps(aMid, bMid))
        }

        // 后缀作为上下文
        for (k in 0 until suffix) ops.add(Op(' '.code, a[a.size - suffix + k]))

        val added = ops.count { it.kind == '+'.code }
        val removed = ops.count { it.kind == '-'.code }
        if (added == 0 && removed == 0) return Result("", 0, 0)

        val sb = StringBuilder()
        sb.append("--- a/").append(path).append('\n')
        sb.append("+++ b/").append(path).append('\n')

        // 按 hunk 切分：变更点前后各 CONTEXT 行
        var i = 0
        while (i < ops.size) {
            if (ops[i].kind == ' '.code) {
                i++
                continue
            }
            // 找到本 hunk 的起点（往前回退 CONTEXT 行）
            var start = i
            var back = 0
            while (start > 0 && back < CONTEXT && ops[start - 1].kind == ' '.code) {
                start--
                back++
            }
            // 往后扩展：直到连续 CONTEXT 行未变更
            var end = i
            var quiet = 0
            while (end < ops.size) {
                if (ops[end].kind == ' '.code) {
                    quiet++
                    if (quiet > CONTEXT * 2) break
                } else {
                    quiet = 0
                }
                end++
            }
            // 收尾：去掉尾部多余的上下文
            var tail = end
            var trim = 0
            while (tail > start && trim < CONTEXT && ops[tail - 1].kind == ' '.code) {
                tail--
                trim++
            }

            val oldStart = ops.subList(0, start).count { it.kind != '+'.code } + 1
            val newStart = ops.subList(0, start).count { it.kind != '-'.code } + 1
            val oldLen = ops.subList(start, tail).count { it.kind != '+'.code }
            val newLen = ops.subList(start, tail).count { it.kind != '-'.code }
            sb.append("@@ -").append(oldStart).append(',').append(oldLen)
                .append(" +").append(newStart).append(',').append(newLen).append(" @@\n")

            for (k in start until tail) {
                val op = ops[k]
                sb.append(op.kind.toChar()).append(op.text).append('\n')
            }
            i = tail
        }
        return Result(sb.toString(), added, removed)
    }

    private data class Op(val kind: Int, val text: String)

    /**
     * 行级 LCS → 操作序列（' ' 保留 / '-' 删除 / '+' 新增）。
     * 用滚动数组算长度，再回溯出操作序列。
     */
    private fun lcsOps(a: List<String>, b: List<String>): List<Op> {
        val n = a.size
        val m = b.size
        // dp[i][j] = a[i..] 与 b[j..] 的 LCS 长度
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val out = ArrayList<Op>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    out.add(Op(' '.code, a[i])); i++; j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    out.add(Op('-'.code, a[i])); i++
                }
                else -> {
                    out.add(Op('+'.code, b[j])); j++
                }
            }
        }
        while (i < n) { out.add(Op('-'.code, a[i])); i++ }
        while (j < m) { out.add(Op('+'.code, b[j])); j++ }
        return out
    }
}
