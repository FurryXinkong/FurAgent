package net.xinkong.furagent.shared.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Paths
import java.util.*

/**
 * 桌面端 ToolHost。
 *
 * 不做任何路径白名单/黑名单检查。
 * resolvePath 只负责别名展开（~ / 桌面 / 下载 / 文档 / 图片 / 音乐）与绝对化。
 */
class DesktopToolHost : ToolHost {

    private val envFile: File by lazy {
        val dir = File(System.getProperty("user.home") ?: ".", ".furagent/config")
        if (!dir.exists()) dir.mkdirs()
        File(dir, "env.json")
    }

    // env.json 读写的串行保护。
    // 原来读和写都无锁，两个协程同时 kvSet 会互踩同一个 .tmp 文件。
    private val envFileLock = Any()

    override fun now(): Long = System.currentTimeMillis()

    override fun resolvePath(input: String): String? {
        return try {
            val t = input.trim().trim('"', '\'', '`')
            if (t.isBlank()) return null

            val home = System.getProperty("user.home") ?: return null
            val expanded = if (t.startsWith("~")) home + t.substring(1) else t

            val aliased = expandAlias(expanded, home)

            try {
                Paths.get(aliased).toAbsolutePath().normalize().toString()
            } catch (e: Exception) {
                ToolLogger.log(
                    "ToolHost",
                    "路径绝对化失败，回退原值：$aliased - ${e.javaClass.simpleName}: ${e.message}"
                )
                aliased
            }
        } catch (e: Exception) {
            ToolLogger.log(
                "ToolHost",
                "路径解析异常：$input - ${e.javaClass.simpleName}: ${e.message}"
            )
            null
        }
    }

    private fun expandAlias(path: String, home: String): String =
        net.xinkong.furagent.shared.util.PathAlias.expandDesktop(path, home)

    override fun log(tag: String, message: String) {
        ToolLogger.log(tag, message)
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

    /** 回收站根目录：~/.furagent/temp/trash */
    private val trashRoot: File by lazy {
        val home = System.getProperty("user.home") ?: "."
        File(home, ".furagent/temp/trash")
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

    override suspend fun searchFiles(
        root: String, keyword: String, maxResults: Int
    ): List<String> = withContext(Dispatchers.IO) {
        try {
            val rootFile = File(root)
            if (!rootFile.exists() || !rootFile.isDirectory) return@withContext emptyList()
            var visited = 0
            rootFile.walkTopDown()
                .maxDepth(15)
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
        contextLines: Int,
        maxDepth: Int
    ): List<ContentMatch> = ToolCodeNav.searchInFiles(
        root, keyword, regex, nameGlob, maxResults, maxFileSize, contextLines, maxDepth
    ) { msg -> ToolLogger.log("ToolHost", msg) }

    // KV 存储（全部经 envFileLock 串行化）

    override suspend fun kvGet(key: String): String? = withContext(Dispatchers.IO) {
        try {
            synchronized(envFileLock) { readEnvFileLocked()[key] }
        } catch (e: Exception) {
            ToolLogger.log(
                "ToolHost",
                "读取变量失败：$key - ${e.javaClass.simpleName}: ${e.message}"
            )
            null
        }
    }

    override suspend fun kvSet(key: String, value: String) = withContext(Dispatchers.IO) {
        try {
            synchronized(envFileLock) {
                val map = readEnvFileLocked().toMutableMap()
                map[key] = value
                writeEnvFileLocked(map)
            }
        } catch (e: Exception) {
            ToolLogger.log(
                "ToolHost",
                "写入变量失败：$key - ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    override suspend fun kvRemove(key: String) = withContext(Dispatchers.IO) {
        try {
            synchronized(envFileLock) {
                val map = readEnvFileLocked().toMutableMap()
                map.remove(key)
                writeEnvFileLocked(map)
            }
        } catch (e: Exception) {
            ToolLogger.log(
                "ToolHost",
                "删除变量失败：$key - ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    override suspend fun kvListAll(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            synchronized(envFileLock) { readEnvFileLocked() }
        } catch (e: Exception) {
            ToolLogger.log(
                "ToolHost",
                "列出变量失败 - ${e.javaClass.simpleName}: ${e.message}"
            )
            emptyMap()
        }
    }

    override suspend fun kvClear() = withContext(Dispatchers.IO) {
        try {
            synchronized(envFileLock) { writeEnvFileLocked(emptyMap()) }
        } catch (e: Exception) {
            ToolLogger.log(
                "ToolHost",
                "清空变量失败 - ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    /** 无锁版本：必须在持有 envFileLock 时调用。 */
    private fun readEnvFileLocked(): Map<String, String> {
        if (!envFile.exists()) return emptyMap()
        return try {
            val o = JSONObject(envFile.readText(Charsets.UTF_8))
            val map = mutableMapOf<String, String>()
            o.keys().forEach { k -> map[k] = o.optString(k, "") }
            map
        } catch (e: Exception) {
            ToolLogger.log(
                "ToolHost",
                "env.json 解析失败：${envFile.absolutePath} - ${e.javaClass.simpleName}: ${e.message}"
            )
            emptyMap()
        }
    }

    /** 无锁版本：必须在持有 envFileLock 时调用。 */
    private fun writeEnvFileLocked(map: Map<String, String>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        val tmp = File(envFile.parentFile, envFile.name + ".tmp")
        try {
            tmp.writeText(o.toString(2), Charsets.UTF_8)
            if (!tmp.renameTo(envFile)) {
                envFile.writeText(o.toString(2), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            ToolLogger.log(
                "ToolHost",
                "写入 env.json 失败：${envFile.absolutePath} - ${e.javaClass.simpleName}: ${e.message}"
            )
            try { tmp.delete() } catch (_: Exception) { }
        }
    }
}