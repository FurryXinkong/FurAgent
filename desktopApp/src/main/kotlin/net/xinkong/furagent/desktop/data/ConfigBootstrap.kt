package net.xinkong.furagent.desktop.data

import net.xinkong.furagent.desktop.system.DesktopLogger
import java.io.File

/**
 * 首次运行配置初始化。
 *
 * 从 `Main.kt` 抽出来，落到 `data/` 包（TOAI §22.1）。
 *
 * 职责：
 *   - 触发所有子目录 mkdirs（rootDir / config / sessions / screenshots /
 *     received / temp / logs / exports / scripts / imported_images）
 *   - 首次启动时创建 5 个空 JSON 文件（内容写 `[]`，标准 JSON 不支持注释）
 *
 * 幂等：已存在的文件不动。任何异常都只记日志，不阻塞启动。
 */
object ConfigBootstrap {

    /**
     * 确保所有目录和初始配置文件存在。
     */
    fun ensureAllConfigFiles() {
        try {
            // 触发所有目录 mkdirs
            DesktopPaths.rootDir().mkdirs()
            DesktopPaths.configDir()
            DesktopPaths.sessionDir()
            DesktopPaths.screenshotsDir()
            DesktopPaths.receivedDir()
            DesktopPaths.tempDir()
            DesktopPaths.logsDir()
            DesktopPaths.exportsDir()
            DesktopPaths.scriptsDir()
            DesktopPaths.importedImagesDir()

            val initialFiles = listOf(
                File(DesktopPaths.configDir(), "mcp.json"),
                File(DesktopPaths.configDir(), "direct_devices.json"),
                File(DesktopPaths.rootDir(), "workflows.json"),
                File(DesktopPaths.rootDir(), "schedules.json"),
                File(DesktopPaths.rootDir(), "memory.json")
            )
            var created = 0
            initialFiles.forEach { f ->
                if (!f.exists()) {
                    try {
                        f.writeText("[]", Charsets.UTF_8)
                        created++
                    } catch (e: Exception) {
                        DesktopLogger.warn(
                            "SYS",
                            "创建初始文件失败：${f.absolutePath} - ${e.message}"
                        )
                    }
                }
            }
            if (created > 0) {
                DesktopLogger.info("SYS", "首次运行初始化：创建 $created 个配置文件")
            }
        } catch (e: Exception) {
            DesktopLogger.warn("SYS", "初始化配置目录失败：${e.message}")
        }
    }
}