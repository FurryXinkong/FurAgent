package net.xinkong.furagent.shared.tools

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

class AndroidToolHost(
    private val context: Context,
    private val allowedPaths: List<String>,
    private val blockedPaths: List<String>
) : ToolHost {

    private val sdcardPath: String by lazy {
        try {
            Environment.getExternalStorageDirectory().absolutePath
        } catch (e: Exception) {
            ToolLogger.log(
                "ToolHost",
                "获取外部存储路径失败，回退到 /sdcard：${e.javaClass.simpleName}: ${e.message}"
            )
            "/sdcard"
        }
    }

    private val envPrefs by lazy {
        context.getSharedPreferences("FurAgentEnv", Context.MODE_PRIVATE)
    }

    override fun now(): Long = System.currentTimeMillis()

    override fun resolvePath(input: String): String? {
        val t = input.trim().trim('"', '\'', '`')
        if (t.isBlank()) return null

        val expanded = expandAlias(t)

        val result = PathResolver.check(expanded, allowedPaths, blockedPaths)
        if (!result.ok) {
            ToolLogger.log("ToolHost", "路径被拒绝：$input（${result.error}）")
            return null
        }
        return result.resolved
    }

    private fun expandAlias(path: String): String =
        net.xinkong.furagent.shared.util.PathAlias.expandAndroid(path, sdcardPath)

    override fun log(tag: String, message: String) {
        try { android.util.Log.d(tag, message) } catch (_: Exception) { }
        try { ToolLogger.log(tag, message) } catch (_: Exception) { }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        try { File(path).exists() } catch (_: Exception) { false }
    }

    override suspend fun isDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        try { File(path).isDirectory } catch (_: Exception) { false }
    }

    override suspend fun fileSize(path: String): Long = withContext(Dispatchers.IO) {
        try {
            val f = File(path)
            if (f.exists()) f.length() else -1L
        } catch (_: Exception) { -1L }
    }

    override suspend fun readTextFile(path: String): String? = withContext(Dispatchers.IO) {
        try {
            File(path).readText(Charsets.UTF_8)
        } catch (e: Exception) {
            ToolLogger.log(
                "ToolHost",
                "读取文本文件失败：$path - ${e.javaClass.simpleName}: ${e.message}"
            )
            null
        }
    }

    override suspend fun readBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            File(path).readBytes()
        } catch (e: Exception) {
            ToolLogger.log(
                "ToolHost",
                "读取二进制文件失败：$path - ${e.javaClass.simpleName}: ${e.message}"
            )
            null
        }
    }

    override suspend fun writeTextFile(path: String, content: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val f = File(path)
            f.parentFile?.mkdirs()
            f.writeText(content, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            ToolLogger.log(
                "ToolHost",
                "写入文本文件失败：$path - ${e.javaClass.simpleName}: ${e.message}"
            )
            false
        }
    }

    override suspend fun writeBytes(path: String, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val f = File(path)
            f.parentFile?.mkdirs()
            f.writeBytes(data)
            true
        } catch (e: Exception) {
            ToolLogger.log(
                "ToolHost",
                "写入二进制文件失败：$path（${data.size} 字节） - ${e.javaClass.simpleName}: ${e.message}"
            )
            false
        }
    }

    override suspend fun deleteFile(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            File(path).delete()
        } catch (e: Exception) {
            ToolLogger.log(
                "ToolHost",
                "删除文件失败：$path - ${e.javaClass.simpleName}: ${e.message}"
            )
            false
        }
    }

    // 回收站

    /** 回收站根目录：app 私有目录下的 temp/trash */
    private val trashRoot: File by lazy {
        File(context.filesDir, "temp/trash")
    }

    override fun trashDir(): String? = trashRoot.absolutePath

    override suspend fun moveToTrash(path: String): TrashResult = withContext(Dispatchers.IO) {
        TrashHelper.moveToTrash(File(path), trashRoot)
    }

    override suspend fun listDirectory(path: String): List<FileEntry>? = withContext(Dispatchers.IO) {
        try {
            val dir = File(path)
            if (!dir.isDirectory) return@withContext null
            val files = dir.listFiles() ?: return@withContext null
            files.map { f ->
                FileEntry(
                    name = f.name,
                    isDirectory = f.isDirectory,
                    size = if (f.isFile) f.length() else 0L,
                    absolutePath = f.absolutePath
                )
            }
        } catch (e: Exception) {
            ToolLogger.log(
                "ToolHost",
                "列出目录失败：$path - ${e.javaClass.simpleName}: ${e.message}"
            )
            null
        }
    }

    /**
     * 【性能修复】原来 `forEach` 里 `if (hits.size < maxResults) hits.add(...)`
     * 不会提前退出，仍要遍历完 5 万个文件才结束。
     *
     * 改用 Sequence.take(maxResults)：达到数量上限时自动停止上游遍历。
     */
    override suspend fun searchFiles(
        root: String, keyword: String, maxResults: Int
    ): List<String> = withContext(Dispatchers.IO) {
        try {
            val rootFile = File(root)
            if (!rootFile.exists() || !rootFile.isDirectory) return@withContext emptyList()
            var visited = 0
            rootFile.walkTopDown()
                .onEnter {
                    visited++
                    visited < 50000 && it.canRead()
                }
                .filter { it.isFile && it.name.contains(keyword, ignoreCase = true) }
                .map { it.absolutePath }
                .take(maxResults)
                .toList()
        } catch (e: Exception) {
            ToolLogger.log(
                "ToolHost",
                "搜索文件失败：$root（keyword=$keyword） - ${e.javaClass.simpleName}: ${e.message}"
            )
            emptyList()
        }
    }

    override suspend fun ensureParentDir(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            File(path).parentFile?.mkdirs()
            true
        } catch (e: Exception) {
            ToolLogger.log(
                "ToolHost",
                "创建父目录失败：$path - ${e.javaClass.simpleName}: ${e.message}"
            )
            false
        }
    }

    // 【P1-11】统一到 shared/util/Format.formatFileSize
    // 原来 host 版是 %.1fGB，shared 版是 %.2fGB，同一个文件在两个位置显示不同精度。
    override fun formatSize(bytes: Long): String =
        net.xinkong.furagent.shared.util.formatFileSize(bytes)

    // 代码导航（实现共用 ToolCodeNav）

    override suspend fun readTextLines(path: String, offset: Int, limit: Int): List<TextLine>? =
        ToolCodeNav.readTextLines(path, offset, limit)

    override suspend fun textLineCount(path: String): Int =
        ToolCodeNav.textLineCount(path)

    override suspend fun searchInFiles(
        root: String,
        keyword: String,
        regex: Boolean,
        nameGlob: String,
        maxResults: Int,
        maxFileSize: Long,
        contextLines: Int
    ): List<ContentMatch> = ToolCodeNav.searchInFiles(
        root, keyword, regex, nameGlob, maxResults, maxFileSize, contextLines
    ) { msg -> ToolLogger.log("ToolHost", msg) }

    // KV 存储（env_ 前缀）

    override suspend fun kvGet(key: String): String? = withContext(Dispatchers.IO) {
        envPrefs.getString("env_$key", null)
    }

    override suspend fun kvSet(key: String, value: String) = withContext(Dispatchers.IO) {
        envPrefs.edit().putString("env_$key", value).apply()
    }

    override suspend fun kvRemove(key: String) = withContext(Dispatchers.IO) {
        envPrefs.edit().remove("env_$key").apply()
    }

    override suspend fun kvListAll(): Map<String, String> = withContext(Dispatchers.IO) {
        envPrefs.all
            .filterKeys { it.startsWith("env_") }
            .mapKeys { it.key.removePrefix("env_") }
            .mapValues { it.value.toString() }
    }

    override suspend fun kvClear() = withContext(Dispatchers.IO) {
        val e = envPrefs.edit()
        envPrefs.all.keys.filter { it.startsWith("env_") }.forEach { e.remove(it) }
        e.apply()
    }
}