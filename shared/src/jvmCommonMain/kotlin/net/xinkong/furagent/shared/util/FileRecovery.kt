package net.xinkong.furagent.shared.util

import java.io.File

/**
 * JSON / 数据文件损坏后的恢复辅助。
 *
 * 反序列化失败时不应静默返回 emptyList()（那等于用户数据被无声清空），
 * 而是把损坏文件重命名为 .corrupt.<timestamp> 保留现场，
 * 让用户后续有机会手动恢复。
 */
object FileRecovery {

    /**
     * 把损坏文件重命名为 <name>.corrupt.<ts>，保留现场。
     * 返回备份文件名（含扩展名），失败返回 null。
     */
    fun backupCorrupted(file: File): String? {
        if (!file.exists()) return null
        return try {
            val ts = System.currentTimeMillis()
            val bak = File(file.parentFile, "${file.name}.corrupt.$ts")
            if (file.renameTo(bak)) bak.name else null
        } catch (_: Exception) {
            null
        }
    }
}