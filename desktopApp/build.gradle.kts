import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

group = "net.xinkong.furagent"

// 版本号：构建时刻的 yyyy.MM.dd.HHmm（Windows 安装包要求点分数字格式）
val furagentVersion: String = rootProject.extra["buildVersionNameDotted"] as String
version = furagentVersion

dependencies {
    implementation(compose.desktop.currentOs)
    // MiSans 全局字体：compose resources 资源访问器（Res.font.*）
    implementation(compose.components.resources)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation(project(":shared"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("org.json:json:20260814")
    // 修改历史 SQLite 存储：历史一条表（元数据+patch+改前备份），替代 manifest.jsonl
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    // 【文档生成】Apache POI：纯 Java，无外部依赖，同时支持 docx / xlsx / pptx。
    // 仅桌面端引入（Android 端不引，POI 在 Android 上兼容性差且体积大）。
    // 代价：安装包增大约 10MB，换来“零外部依赖、开箱即用”。
    implementation("org.apache.poi:poi-ooxml:5.5.1")
}

kotlin {
    jvmToolchain(21)
}

compose.desktop {
    application {
        mainClass = "net.xinkong.furagent.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Exe)

            packageName = "FurAgent"
            packageVersion = furagentVersion
            description = "FurAgent"
            vendor = "Xinkong"

            modules(
                "java.base",
                "java.sql",
                "java.logging",
                "java.desktop",
                "java.management",
                "java.naming",
                "java.net.http",
                "jdk.crypto.ec",
                "jdk.crypto.cryptoki",
                "jdk.httpserver",
                "jdk.zipfs",
                "java.xml",
                "jdk.unsupported",
                "jdk.charsets",
                "java.security.jgss",
                "java.security.sasl"
            )

            windows {
                // 安装包 / exe / 快捷方式 / 任务栏图标
                // 必须是 .ico（jpackage 不支持 png）
                iconFile.set(project.file("src/main/resources/icon.ico"))

                // 开始菜单
                menu = true
                menuGroup = "FurAgent"

                // 桌面快捷方式
                shortcut = true

                // 允许用户选择安装目录
                dirChooser = true

                // 升级标识。定了就不要改
                upgradeUuid = "0fffffff-ffff-ffff-ffff-fffffffffff0"
            }
        }
    }
}