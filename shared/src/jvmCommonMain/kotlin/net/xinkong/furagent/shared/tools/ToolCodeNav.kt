package net.xinkong.furagent.shared.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

/**
 * 代码导航的 JVM 共用实现。
 *
 * 为什么单独一个文件：
 *   ToolHost 声明在 commonMain（多平台共用），而 Kotlin 不允许跨源集
 *   给接口补默认实现。所以把实现放在 jvmCommonMain，Android / Desktop
 *   两个 host 各委托一行。逻辑只此一份，两端行为完全一致。
 *
 * 三件事：
 *   - readTextLines：按行区间流式读，不至于为了看 200 行把 100MB 读进内存
 *   - textLineCount：行数
 *   - searchInFiles：递归搜正文（子串 / 正则 + 文件名 glob）
 */
object ToolCodeNav {

    /**
     * 读指定行区间。
     *
     * 用逐行流式读：达到 offset 之前只读不存，之后最多收集 limit 行就停。
     * 对超大文件也安全。行尾统一去掉 \r，避免 CRLF 文件带出多余字符。
     */
    suspend fun readTextLines(path: String, offset: Int, limit: Int): List<TextLine>? =
        withContext(Dispatchers.IO) {
            try {
                val f = File(path)
                if (!f.isFile) return@withContext null
                val start = if (offset <= 1) 1 else offset
                val out = mutableListOf<TextLine>()
                f.bufferedReader(Charsets.UTF_8).use { r ->
                    var lineNo = 0
                    var line = r.readLine()
                    while (line != null) {
                        lineNo++
                        if (lineNo >= start) {
                            out.add(TextLine(lineNo, line.trimEnd('\r')))
                            if (limit > 0 && out.size >= limit) break
                        }
                        line = r.readLine()
                    }
                }
                out
            } catch (_: Exception) {
                null
            }
        }

    /**
     * 行数。空文件算 0 行；结尾无换行时补上最后一行。失败返回 -1。
     *
     * 原实现逐行 readLine() 走 UTF-8 解码，100MB 文件要几秒。
     * 改成字节级 count(0x0A)，快 10 倍以上。
     */
    suspend fun textLineCount(path: String): Int = withContext(Dispatchers.IO) {
        try {
            val f = File(path)
            if (!f.isFile) return@withContext -1
            if (f.length() == 0L) return@withContext 0
            var newlineCount = 0L
            var lastByte = -1
            f.inputStream().buffered(64 * 1024).use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    for (i in 0 until n) {
                        if (buf[i] == 0x0A.toByte()) newlineCount++
                    }
                    lastByte = buf[n - 1].toInt() and 0xFF
                }
            }
            // 末尾以 \n 结尾：行数 = \n 数；末尾非 \n：行数 = \n 数 + 1
            val lines = if (lastByte == 0x0A) newlineCount else newlineCount + 1
            lines.toInt()
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * 递归搜正文。
     *
     * 设计取舍：
     *  - 跳过 .git / node_modules / build 这类目录（进去只会浪费时间）
     *  - 跳过超大文件（默认 2MB），否则一个日志就把搜索拖死
     *  - 达到 maxResults 立即停（上游是 Sequence，不白跑）
     *  - 命中行文本裁到 400 字符，防止一行超长把上下文灌爆
     */
    /**
     * 递归搜正文。
     *
     * 设计取舍：
     *  - 跳过 .git / node_modules / build 这类目录（进去只会浪费时间）
     *  - 超过 maxFileSize 的文件：头部没有 NUL 字节才当成文本继续搜（
     *    大日志、大 csv 往往很值得搜，一刀切跳过反而会漏东西）
     *  - 达到 maxResults 立即停（上游是 Sequence，不白跑）
     *  - 命中行文本裁到 400 字符，防止一行超长把上下文灌爆
     *
     * @param contextLines 每条命中额外带上前后各 N 行（0 = 不带）。
     *   有上下文时就不用为了看一个函数头再 read_file 一次。
     */
    suspend fun searchInFiles(
        root: String,
        keyword: String,
        regex: Boolean,
        nameGlob: String,
        maxResults: Int,
        maxFileSize: Long,
        contextLines: Int = 0,
        maxDepth: Int = 15,
        log: (String) -> Unit
    ): List<ContentMatch> = withContext(Dispatchers.IO) {
        try {
            val rootFile = File(root)
            if (!rootFile.exists() || !rootFile.isDirectory) return@withContext emptyList()

            val matchFileName = buildNameMatcher(nameGlob)
            val pattern = if (regex) {
                try {
                    Regex(keyword, RegexOption.IGNORE_CASE)
                } catch (e: Exception) {
                    log("正则非法，已回退为纯文本搜索：$keyword - ${e.message}")
                    null
                }
            } else null
            val needle = keyword.lowercase(Locale.ROOT)
            val ctx = contextLines.coerceIn(0, 10)

            val out = mutableListOf<ContentMatch>()
            var visited = 0
            var scanned = 0
            var skippedBinary = 0

            rootFile.walkTopDown()
                .maxDepth(maxDepth)
                .onEnter { dir ->
                    visited++
                    visited < 50000 && dir.canRead() && dir.name !in SKIP_DIRS
                }
                .filter { it.isFile && it.canRead() }
                .filter { matchFileName(it.name) }
                // takeWhile 让"命中够了"真正终止上游遍历。
                // 原实现 `return@forEach` 只跳过当前文件，walkTopDown 照样递归完剩下的 4 万多个目录。
                // 这是"搜正文慢"的主因：命中够了还在白遍历。
                .takeWhile { out.size < maxResults }
                .forEach { f ->
                    if (out.size >= maxResults) return@forEach
                    try {
                        // 小文件直接读；大文件才先嗅头部判断是不是文本。
                        // 之前无论大小都开流嗅一次，几千个小文件时是无谓开销。
                        val isLarge = f.length() > maxFileSize
                        if (isLarge && looksBinary(f)) {
                            skippedBinary++
                            return@forEach
                        }
                        scanned++
                        f.bufferedReader(Charsets.UTF_8).use { r ->
                            var lineNo = 0
                            var line = r.readLine()
                            val ring = ArrayDeque<String>()   // 命中行前面的 ctx 行
                            var pending = 0                    // 命中行后面还要收集几行
                            while (line != null) {
                                lineNo++
                                // 原 `line.lowercase().contains(needle)` 每行分配新字符串。
                            // 50000 行 = 50000 次分配。改成 contains(ignoreCase=true)：
                            // 底层走 regionMatches，零分配。语义等价。
                            val hit = if (pattern != null) pattern.containsMatchIn(line)
                                else line.contains(keyword, ignoreCase = true)
                                if (hit) {
                                    out.add(
                                        ContentMatch(
                                            f.absolutePath,
                                            lineNo,
                                            line.trim().take(400),
                                            ring.toList(),
                                            emptyList()
                                        )
                                    )
                                    pending = ctx
                                    if (out.size >= maxResults) break
                                } else if (pending > 0) {
                                    // 把后续行接到最后一条命中上
                                    val last = out.last()
                                    out[out.size - 1] = last.copy(
                                        after = last.after + line.trim().take(400)
                                    )
                                    pending--
                                }
                                if (ctx > 0) {
                                    ring.addLast(line.trim().take(400))
                                    while (ring.size > ctx) ring.removeFirst()
                                }
                                line = r.readLine()
                            }
                        }
                    } catch (e: Exception) {
                        log("搜正文跳过文件：${f.absolutePath} - ${e.javaClass.simpleName}: ${e.message}")
                    }
                }

            if (skippedBinary > 0) log("因疑似二进制跳过 $skippedBinary 个文件")
            if (out.size >= maxResults) log("已达 maxResults=$maxResults 上限，可能还有更多命中")
            out
        } catch (e: Exception) {
            log("搜正文失败：$root（keyword=$keyword） - ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }

    /**
     * 这些目录名不进去。命中率高、进去没价值。
     *
     * P2 扩充 原实现只覆盖 11 个，实际开发机上还有大量编译产物 / 缓存目录：
     *   IDE：.vs / .vscode
     *   Rust/Java：target
     *   JS 框架：.next / .nuxt / .turbo / .parcel-cache
     *   Python：venv（小写无点）/ .pytest_cache / .mypy_cache / .ruff_cache / htmlcov
     *   PHP/Go：vendor
     *   Android NDK：.cxx / .externalNativeBuild
     */
    private val SKIP_DIRS = setOf(
        // 版本控制 + IDE
        ".git", ".svn", ".hg", ".idea", ".vscode", ".vs", ".fleet",
        // 构建 / 缓存
        "build", "out", "dist", "target", "bin", "obj",
        ".gradle", ".kotlin", ".cache", ".turbo", ".parcel-cache",
        ".next", ".nuxt", ".svelte-kit", ".angular",
        ".cxx", ".externalNativeBuild",
        // JS 依赖
        "node_modules", "bower_components",
        // Python
        "__pycache__", ".venv", "venv", ".pytest_cache", ".mypy_cache",
        ".ruff_cache", ".coverage", "htmlcov", ".tox", ".nox",
        // PHP / Go / Ruby
        "vendor", ".bundle",
        // 其它
        ".stack-work", "_build", "deps"
    )

    /** 头部 8KB 里出现 NUL 就认为是二进制。 */
    private fun looksBinary(f: File): Boolean {
        return try {
            val buf = ByteArray(8192)
            f.inputStream().use { ins ->
                val n = ins.read(buf)
                if (n <= 0) return false
                for (i in 0 until n) if (buf[i] == 0.toByte()) return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /** 把 "*.kt,*.kts" 这类 glob 编译成文件名判定函数。空 glob = 全部通过。 */
    private fun buildNameMatcher(glob: String): (String) -> Boolean {
        val patterns = glob.split(',', ' ', ';')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (patterns.isEmpty()) return { true }

        val regexes = patterns.map { g ->
            val sb = StringBuilder()
            for (c in g) {
                when (c) {
                    '*' -> sb.append(".*")
                    '?' -> sb.append('.')
                    '.' -> sb.append("\\.")
                    else -> if (c.isLetterOrDigit() || c == '_' || c == '-') sb.append(c)
                    else sb.append(Regex.escape(c.toString()))
                }
            }
            Regex("^$sb$", RegexOption.IGNORE_CASE)
        }
        return { name -> regexes.any { it.matches(name) } }
    }
}
