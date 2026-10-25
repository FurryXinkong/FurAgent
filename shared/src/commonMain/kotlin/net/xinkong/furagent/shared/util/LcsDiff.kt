package net.xinkong.furagent.shared.util

/**
 * 双栏 diff 对齐算法（P1 抽 shared）。
 *
 * 合并原因：桌面端 HistoryPage.alignSideBySide + pairRows 和 Android 端
 * HistoryPage.alignRows 是同一套逻辑的两份实现（约 110 行 × 2）。
 * 抽到 shared 后 UI 层只负责渲染 Row。
 *
 * 算法：前缀 / 后缀裁剪公共行，中段 LCS；乘积超阈值时退化为左块 + 右块；
 * 最后把紧邻的 -块 / +块 配对成 ! 行（左红右绿同行）。
 */
object LcsDiff {

    /**
     * 对齐后的一行。
     *
     * @param oldNo   旧文件行号（1 起，无对应行为 null）
     * @param oldText 旧文件文本
     * @param newNo   新文件行号（1 起，无对应行为 null）
     * @param newText 新文件文本
     * @param kind    '=' 相同 / '!' 变更（左红右绿同行）/ '-' 仅旧 / '+' 仅新
     */
    data class Row(
        val oldNo: Int?,
        val oldText: String?,
        val newNo: Int?,
        val newText: String?,
        val kind: Char
    )

    fun alignRows(oldText: String, newText: String): List<Row> {
        val a = oldText.split('\n')
        val b = newText.split('\n')
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val midA = a.subList(pre, a.size - suf)
        val midB = b.subList(pre, b.size - suf)
        val out = ArrayList<Row>(maxOf(a.size, b.size))
        var oldNo = 1
        var newNo = 1
        for (i in 0 until pre) out.add(Row(oldNo++, a[i], newNo++, b[i], '='))
        val n = midA.size
        val m = midB.size
        if (n.toLong() * m > 4_000_000L) {
            for (i in 0 until n) out.add(Row(oldNo++, midA[i], null, null, '-'))
            for (j in 0 until m) out.add(Row(null, null, newNo++, midB[j], '+'))
        } else if (n == 0) {
            for (j in 0 until m) out.add(Row(null, null, newNo++, midB[j], '+'))
        } else if (m == 0) {
            for (i in 0 until n) out.add(Row(oldNo++, midA[i], null, null, '-'))
        } else {
            val dp = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) {
                for (j in m - 1 downTo 0) {
                    dp[i][j] = if (midA[i] == midB[j]) dp[i + 1][j + 1] + 1
                    else maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
            var i = 0
            var j = 0
            while (i < n && j < m) {
                when {
                    midA[i] == midB[j] -> {
                        out.add(Row(oldNo++, midA[i], newNo++, midB[j], '='))
                        i++; j++
                    }
                    dp[i + 1][j] >= dp[i][j + 1] -> {
                        out.add(Row(oldNo++, midA[i], null, null, '-')); i++
                    }
                    else -> {
                        out.add(Row(null, null, newNo++, midB[j], '+')); j++
                    }
                }
            }
            while (i < n) { out.add(Row(oldNo++, midA[i], null, null, '-')); i++ }
            while (j < m) { out.add(Row(null, null, newNo++, midB[j], '+')); j++ }
        }
        for (k in 0 until suf) {
            out.add(Row(oldNo++, a[a.size - suf + k], newNo++, b[b.size - suf + k], '='))
        }

        val paired = ArrayList<Row>(out.size)
        var x = 0
        while (x < out.size) {
            val r = out[x]
            if (r.kind == '-') {
                val dels = ArrayList<Row>()
                while (x < out.size && out[x].kind == '-') { dels.add(out[x]); x++ }
                val adds = ArrayList<Row>()
                while (x < out.size && out[x].kind == '+') { adds.add(out[x]); x++ }
                if (adds.isEmpty()) { paired.addAll(dels); continue }
                val k = maxOf(dels.size, adds.size)
                for (t in 0 until k) {
                    val d = dels.getOrNull(t)
                    val ad = adds.getOrNull(t)
                    when {
                        d != null && ad != null ->
                            paired.add(Row(d.oldNo, d.oldText, ad.newNo, ad.newText, '!'))
                        d != null -> paired.add(d)
                        else -> if (ad != null) paired.add(ad)
                    }
                }
            } else {
                paired.add(r); x++
            }
        }
        return paired
    }
}
