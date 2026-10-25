package net.xinkong.furagent.desktop.system

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 原生文件选择器（Windows 资源管理器风格）。
 *
 * 【演进历史】
 *   1. 最初用 Swing 的 JFileChooser —— 1995 年老式对话框（"查找范围(I)" 那种），
 *      用户明确表示"丑不拉几"。
 *   2. 改用 java.awt.FileDialog —— 理论上 Windows 上走原生 IFileOpenDialog，
 *      但 Compose Desktop 用 Skiko 渲染，AWT 的 native peer 初始化不完整，
 *      实测仍然退化成 Swing 风格，无效。
 *   3. 最终方案：调 PowerShell 的 System.Windows.Forms.OpenFileDialog。
 *      这是 .NET 对 Windows IFileOpenDialog 的封装，100% 原生外观，
 *      与资源管理器完全一致（带地址栏、快速访问、详细信息列）。
 *
 * 【为什么用 PowerShell 而不是 JNA】
 *   - 零依赖：Windows 自带 PowerShell 5.1，不需要额外 jar
 *   - 稳定：WinForms 的 OpenFileDialog 是微软官方封装，不会随 JDK 版本变化
 *   - 简单：一个脚本搞定，不用写 COM 接口定义
 *
 * 【代价】
 *   每次弹框要启动一个 PowerShell 进程（约 300~600ms）。
 *   文件选择是低频操作，这个延迟可以接受。
 */
object NativeFilePicker {

    /**
     * 选择单个文件。
     *
     * @param title 对话框标题
     * @param extensions 允许的扩展名（不带点），如 listOf("png","jpg")。空表示所有文件
     * @param initialDir 初始目录，null 表示系统默认
     * @return 选中的文件，取消返回 null
     */
    fun pickFile(
        title: String = "选择文件",
        extensions: List<String> = emptyList(),
        initialDir: File? = null
    ): File? = pickFiles(title, extensions, initialDir, multiple = false).firstOrNull()

    /**
     * 选择多个文件。
     *
     * @return 选中的文件列表，取消返回空列表
     */
    fun pickFiles(
        title: String = "选择文件",
        extensions: List<String> = emptyList(),
        initialDir: File? = null,
        multiple: Boolean = true
    ): List<File> {
        val filter = buildFilter(extensions)
        val script = buildString {
            append("Add-Type -AssemblyName System.Windows.Forms\n")
            append("\$d = New-Object System.Windows.Forms.OpenFileDialog\n")
            append("\$d.Title = ").append(psQuote(title)).append('\n')
            append("\$d.Multiselect = \$").append(if (multiple) "true" else "false").append('\n')
            if (filter.isNotBlank()) {
                append("\$d.Filter = ").append(psQuote(filter)).append('\n')
            }
            initialDir?.let { dir ->
                if (dir.isDirectory) {
                    append("\$d.InitialDirectory = ").append(psQuote(dir.absolutePath)).append('\n')
                }
            }
            // 用 TopMost 的临时窗体做 owner，确保对话框弹在最前面
            append("\$f = New-Object System.Windows.Forms.Form\n")
            append("\$f.TopMost = \$true\n")
            append("if (\$d.ShowDialog(\$f) -eq 'OK') {\n")
            append("  \$d.FileNames | ForEach-Object { Write-Output \$_ }\n")
            append("}\n")
            append("\$f.Dispose()\n")
        }
        val out = runPowerShell(script) ?: return emptyList()
        return out.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { File(it) }
            .filter { it.isFile }
            .toList()
    }

    /**
     * 选择目录。
     *
     * 用 WinForms 的 FolderBrowserDialog（同样是原生外观）。
     */
    fun pickDirectory(title: String = "选择文件夹", initialDir: File? = null): File? {
        val script = buildString {
            append("Add-Type -AssemblyName System.Windows.Forms\n")
            append("\$d = New-Object System.Windows.Forms.FolderBrowserDialog\n")
            append("\$d.Description = ").append(psQuote(title)).append('\n')
            initialDir?.let { dir ->
                if (dir.isDirectory) {
                    append("\$d.SelectedPath = ").append(psQuote(dir.absolutePath)).append('\n')
                }
            }
            append("\$f = New-Object System.Windows.Forms.Form\n")
            append("\$f.TopMost = \$true\n")
            append("if (\$d.ShowDialog(\$f) -eq 'OK') { Write-Output \$d.SelectedPath }\n")
            append("\$f.Dispose()\n")
        }
        val out = runPowerShell(script)?.trim().orEmpty()
        if (out.isBlank()) return null
        val f = File(out)
        return if (f.isDirectory) f else null
    }

    /**
     * 保存文件（另存为）。
     *
     * @param defaultName 默认文件名
     * @return 目标文件，取消返回 null
     */
    fun saveFile(
        title: String = "保存文件",
        defaultName: String = "",
        extensions: List<String> = emptyList(),
        initialDir: File? = null
    ): File? {
        val filter = buildFilter(extensions)
        val script = buildString {
            append("Add-Type -AssemblyName System.Windows.Forms\n")
            append("\$d = New-Object System.Windows.Forms.SaveFileDialog\n")
            append("\$d.Title = ").append(psQuote(title)).append('\n')
            if (defaultName.isNotBlank()) {
                append("\$d.FileName = ").append(psQuote(defaultName)).append('\n')
            }
            if (filter.isNotBlank()) {
                append("\$d.Filter = ").append(psQuote(filter)).append('\n')
            }
            initialDir?.let { dir ->
                if (dir.isDirectory) {
                    append("\$d.InitialDirectory = ").append(psQuote(dir.absolutePath)).append('\n')
                }
            }
            append("\$f = New-Object System.Windows.Forms.Form\n")
            append("\$f.TopMost = \$true\n")
            append("if (\$d.ShowDialog(\$f) -eq 'OK') { Write-Output \$d.FileName }\n")
            append("\$f.Dispose()\n")
        }
        val out = runPowerShell(script)?.trim().orEmpty()
        if (out.isBlank()) return null
        return File(out)
    }

    // 内部

    /**
     * 执行 PowerShell 脚本，返回 stdout。
     *
     * 用 -STA 是因为 WinForms 对话框要求单线程单元（STA）模式，
     * 默认的 MTA 会直接抛异常。
     */
    private fun runPowerShell(script: String): String? {
        return try {
            // 用 -EncodedCommand 避免引号转义地狱（UTF-16LE + Base64）
            val encoded = java.util.Base64.getEncoder()
                .encodeToString(script.toByteArray(Charsets.UTF_16LE))
            val pb = ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-STA",
                "-EncodedCommand",
                encoded
            )
            pb.redirectErrorStream(false)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val finished = proc.waitFor(10, TimeUnit.MINUTES)
            if (!finished) {
                proc.destroyForcibly()
                return null
            }
            out
        } catch (e: Exception) {
            DesktopLoggerHolder.log("NativeFilePicker 执行失败：${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * 构造 WinForms 的 Filter 字符串。
     *
     * 格式："图片|*.png;*.jpg|所有文件|*.*"
     */
    private fun buildFilter(extensions: List<String>): String {
        if (extensions.isEmpty()) return "所有文件|*.*"
        val patterns = extensions.joinToString(";") { "*.$it" }
        val label = extensions.joinToString("/") { it.uppercase() }
        return "$label|$patterns|所有文件|*.*"
    }

    /** PowerShell 单引号字符串转义（单引号内单引号要写两遍）。 */
    private fun psQuote(s: String): String = "'" + s.replace("'", "''") + "'"

    /** 延迟取 DesktopLogger，避免循环依赖。 */
    private object DesktopLoggerHolder {
        fun log(msg: String) {
            try {
                net.xinkong.furagent.desktop.system.DesktopLogger.warn("FilePicker", msg)
            } catch (_: Throwable) { }
        }
    }
}
