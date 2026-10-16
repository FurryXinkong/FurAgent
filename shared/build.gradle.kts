plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
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
        }

        // ★ 中间源集：jvmCommonMain
        val jvmCommonMain = create("jvmCommonMain") {
            dependsOn(commonMain)
        }
        jvmCommonMain.dependencies {
            api("org.json:json:20260814")
            api("com.squareup.okhttp3:okhttp:5.5.0")
            api("org.jsoup:jsoup:1.23.2")
        }

        // ★ 关键：让 androidMain 和 jvmMain 显式继承 jvmCommonMain
        getByName("androidMain").dependsOn(jvmCommonMain)
        getByName("jvmMain").dependsOn(jvmCommonMain)
    }
}