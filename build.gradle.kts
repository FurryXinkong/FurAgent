import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Properties

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.kotlin.multiplatform.library") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.4.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false
    id("org.jetbrains.compose") version "1.12.1" apply false
}

// ============================================================
// 【版本号自动化】
// Android versionName：构建时刻的 yyyyMMddHHmm（如 202610161751）。
// 桌面端 version：jpackage 要求 MAJOR.MINOR.BUILD 三段，且
//   MAJOR≤255 / MINOR≤255 / BUILD≤65535，故用：
//   MAJOR = 年后两位（26）
//   MINOR = 月（10）
//   BUILD = 日*1440 + 时*60 + 分（分钟级唯一且单调递增）
//   例：2026-10-16 17:59 → 26.10.24119
// versionCode：持久化计数器，每次打包（assemble/bundle/package）自动 +1。
// 计数器存于根目录 version.properties（需入库，保证跨机器延续）。
// ============================================================
val buildTimestamp = Date()
val buildVersionName: String = SimpleDateFormat("yyyyMMddHHmm").format(buildTimestamp)
val cal = Calendar.getInstance().apply { time = buildTimestamp }
val buildVersionNameDotted: String = run {
    val yy = cal.get(Calendar.YEAR) % 100
    val mm = cal.get(Calendar.MONTH) + 1
    val build = cal.get(Calendar.DAY_OF_MONTH) * 1440 +
        cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    "$yy.$mm.$build"
}

val versionPropsFile = file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) versionPropsFile.inputStream().use { load(it) }
}
val currentVersionCode: Int = versionProps.getProperty("versionCode")?.toIntOrNull() ?: 1

// 仅在真正打包时递增 versionCode，避免每次 sync / 跑任意 task 都 +1。
gradle.taskGraph.whenReady {
    val isPackaging = allTasks.any { t ->
        val n = t.name
        n.startsWith("assemble") || n.startsWith("bundle") || n.startsWith("package")
    }
    if (isPackaging) {
        val next = currentVersionCode + 1
        versionProps.setProperty("versionCode", next.toString())
        versionPropsFile.outputStream().use {
            versionProps.store(it, "FurAgent versionCode counter (auto-incremented on packaging)")
        }
        logger.lifecycle("[FurAgent] versionCode 递增：$currentVersionCode -> $next")
    }
}

extra["buildVersionName"] = buildVersionName
extra["buildVersionNameDotted"] = buildVersionNameDotted
extra["buildVersionCode"] = currentVersionCode