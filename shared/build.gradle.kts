plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    // CMP ≥1.6.10 强制要求 compose compiler 插件；compose runtime 由 commonMain 依赖提供
    id("org.jetbrains.kotlin.plugin.compose")
    // 全局字体等共享资源：composeResources/font 只存一份，双端经 Res.font 访问
    id("org.jetbrains.compose")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        namespace = "net.xinkong.furagent.shared"
        compileSdk = 37
        minSdk = 30
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val commonMain = getByName("commonMain")
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            // compose compiler 要求 runtime 在 classpath；同时提供共享资源访问器（Res.font.*）
            implementation(compose.runtime)
            implementation(compose.components.resources)
            // 全局字体 FontFamily 共享 API（rememberMiSansFontFamily）
            implementation(compose.ui)
            // Glass 颜色/组件共享（MaterialTheme.colorScheme）
            implementation(compose.material3)
        }

        // ★ 中间源集：jvmCommonMain
        val jvmCommonMain = create("jvmCommonMain") {
            dependsOn(commonMain)
        }
        jvmCommonMain.dependencies {
            api("com.squareup.okhttp3:okhttp:5.5.0")
            api("org.jsoup:jsoup:1.23.2")
        }

        val androidMain = getByName("androidMain")
        androidMain.dependsOn(jvmCommonMain)
        androidMain.dependencies {
            compileOnly("org.json:json:20260814")
        }

        val jvmMain = getByName("jvmMain")
        jvmMain.dependsOn(jvmCommonMain)
        jvmMain.dependencies {
            api("org.json:json:20260814")
        }
    }
}