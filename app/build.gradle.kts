import java.util.*

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    // compose resources：消费 shared 的 Res.font.* 共享字体
    id("org.jetbrains.compose")
    // 【自定义脚本】Chaquopy：把 CPython 打进 APK，安卓端本地跑 Python 脚本
    id("com.chaquo.python")
}

// 【25.75 修复】签名配置不再硬编码。
// 读取优先级：local.properties（key: furagent.storeFile / furagent.storePassword / furagent.keyPassword）
//            → 环境变量（FURAGENT_STORE_FILE / FURAGENT_STORE_PASSWORD / FURAGENT_KEY_PASSWORD）。
// 密码类字段无兜底默认值（fallback 为空串）：读不到就构建失败，避免真实密码回写源码。
// local.properties 已在 .gitignore 中，不会入库。
// 用 Properties.load(FileInputStream) 读取，可正确处理 `\:` 等转义，
// 避免此前手动解析 local.properties 导致的构建失败。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingSecret(key: String, env: String, fallback: String = ""): String =
    localProps.getProperty(key)
        ?: System.getenv(env)
        ?: fallback

android {
    namespace = "net.xinkong.furagent"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "net.xinkong.furagent"
        minSdk = 30
        targetSdk = 37
        versionCode = rootProject.extra["buildVersionCode"] as Int
        versionName = rootProject.extra["buildVersionName"] as String

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }

        buildConfigField("int", "DEFAULT_BRIDGE_PORT", "8425")
        buildConfigField("String", "DEFAULT_PC_RECEIVE_DIR", "\"/sdcard/FurAgent/downloads\"")
        buildConfigField("int", "LOG_KEEP_DAYS", "7")
        buildConfigField("long", "LOG_MAX_FILE_SIZE", "10485760L")
    }

    signingConfigs {
        create("release") {
            // 路径与别名非敏感，保留默认值；密码类字段无默认值，必须外部配置。
            storeFile = file(signingSecret("furagent.storeFile", "FURAGENT_STORE_FILE", "D:\\JavaSource\\FurAgent\\furagent.jks"))
            storePassword = signingSecret("furagent.storePassword", "FURAGENT_STORE_PASSWORD")
            keyAlias = signingSecret("furagent.keyAlias", "FURAGENT_KEY_ALIAS", "furagent")
            keyPassword = signingSecret("furagent.keyPassword", "FURAGENT_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
}

// 【自定义脚本】Chaquopy：内嵌 CPython 3.12（与构建机 Python 版本一致，
// 避免构建时找不到对应版本导致 .pyc 预编译失败；脚本语法与桌面端兼容）
chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            install("numpy")
            install("pillow")
            install("beautifulsoup4")
            install("pyyaml")
        }
    }
}

dependencies {
    implementation(project(":shared"))

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9")
    // compose resources：shared 的 Res.font.* 消费方依赖
    implementation(compose.components.resources)

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}