package net.xinkong.furagent.shared.tools

data class FileEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val absolutePath: String
)

/** 文本文件里的一行 + 它的行号（1 起）。 */
data class TextLine(
    val number: Int,
    val text: String
)

/** 正文搜索的一条命中。 */
data class ContentMatch(
    val path: String,
    val line: Int,
    val text: String,
    /** 命中行前面的上下文行（从远到近），contextLines=0 时为空。 */
    val before: List<String> = emptyList(),
    /** 命中行后面的上下文行（从近到远），contextLines=0 时为空。 */
    val after: List<String> = emptyList()
)

/**
 * 移入回收站的结果。
 *
 * @param ok        是否成功移入回收站
 * @param trashPath 移入后的实际路径（回收站里的新路径）
 * @param message   失败原因（ok=true 时为空）
 */
data class TrashResult(
    val ok: Boolean,
    val trashPath: String = "",
    val message: String = ""
)

/**
 * 平台能力接口。
 */
interface ToolHost {

    // 时间
    fun now(): Long

    // 路径
    fun resolvePath(input: String): String?

    // 日志
    fun log(tag: String, message: String)

    // 文件系统
    suspend fun exists(path: String): Boolean
    suspend fun isDirectory(path: String): Boolean
    suspend fun fileSize(path: String): Long
    suspend fun readTextFile(path: String): String?
    suspend fun readBytes(path: String): ByteArray?
    suspend fun writeTextFile(path: String, content: String): Boolean
    suspend fun writeBytes(path: String, data: ByteArray): Boolean
    suspend fun deleteFile(path: String): Boolean

    /**
     * 【安全删除】把文件/目录移入回收站，而不是真正删除。
     *
     * 回收站位置：~/.furagent/temp/trash/（Android 为 app 私有目录下的 temp/trash）。
     * 每次移入会加时间戳前缀防重名，并写一条 manifest 记录原始路径，
     * 由用户决定何时真正清空。
     *
     * AI 侧的所有删除操作都必须走这里，禁止直接 deleteFile。
     */
    suspend fun moveToTrash(path: String): TrashResult

    /** 回收站目录的绝对路径（不存在时返回 null）。 */
    fun trashDir(): String?

    suspend fun listDirectory(path: String): List<FileEntry>?
    suspend fun searchFiles(root: String, keyword: String, maxResults: Int): List<String>
    suspend fun ensureParentDir(path: String): Boolean

    // 代码导航：按行区间读 + 搜正文

    /**
     * 读取文本文件的指定行区间。
     *
     * @param offset 起始行号（1 起）。≤0 视为从头开始。
     * @param limit  最多读多少行。≤0 视为不限。
     */
    suspend fun readTextLines(path: String, offset: Int = 1, limit: Int = 0): List<TextLine>?

    /** 文本文件的行数。失败返回 -1。 */
    suspend fun textLineCount(path: String): Int

    /**
     * 在文件正文里搜关键字。
     *
     * @param root        搜索根目录
     * @param keyword     关键字（regex=false 时纯文本子串，大小写不敏感）
     * @param regex       true 时把 keyword 当正则（忽略大小写）
     * @param nameGlob    文件名过滤，如 "*.kt" 或 "*.kt,*.kts"；空表示不过滤
     * @param maxResults  命中行数上限，达到即停
     * @param maxFileSize 单文件超过该字节数就跳过
     */
    suspend fun searchInFiles(
        root: String,
        keyword: String,
        regex: Boolean = false,
        nameGlob: String = "",
        maxResults: Int = 200,
        maxFileSize: Long = 2L * 1024 * 1024,
        contextLines: Int = 0,
        maxDepth: Int = 15
    ): List<ContentMatch>

    // 显示
    fun formatSize(bytes: Long): String

    // 键值存储（env_store 用）
    // 两端各持久化到自己的位置：Android 用 SharedPreferences，桌面用 JSON 文件
    // key 会自动加前缀 "env_"，这里不用自己加
    suspend fun kvGet(key: String): String?
    suspend fun kvSet(key: String, value: String)
    suspend fun kvRemove(key: String)
    suspend fun kvListAll(): Map<String, String>
    suspend fun kvClear()
}