# ============================================================
# 通用
# ============================================================
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# ============================================================
# OkHttp / Okio（官方推荐的精简版）
# ============================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ============================================================
# Shizuku（JNI 回调 + Binder 代理）
# ============================================================
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# ============================================================
# org.json（保留也无害，静态调用其实可以不 keep）
# ============================================================
-keep class org.json.** { *; }

# ============================================================
# kotlinx.serialization（反射查找 serializer）
# ============================================================
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-dontwarn kotlinx.**
-keepclassmembers class kotlinx.** {
    volatile <fields>;
}
-keep,includedescriptorclasses class net.xinkong.furagent.**$$serializer { *; }
-keepclassmembers class net.xinkong.furagent.** {
    *** Companion;
}
-keepclasseswithmembers class net.xinkong.furagent.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ============================================================
# 显式反射入口：ShizukuHelper 反射调 Shizuku.newProcess
# ============================================================
-keepclassmembers class net.xinkong.furagent.system.ShizukuHelper {
    *** createShizukuProcess(...);
}

# ============================================================
# Manifest 声明的组件（系统通过反射创建）
# ============================================================
-keep class net.xinkong.furagent.MainActivity { *; }
-keep class net.xinkong.furagent.FloatingChatActivity { *; }
-keep class net.xinkong.furagent.TaskReceiver { *; }
-keep class net.xinkong.furagent.BootReceiver { *; }
-keep class net.xinkong.furagent.service.** { *; }

# ============================================================
# 通用 Android 组件（系统反射调用）
# ============================================================
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# ============================================================

# ============================================================
# Compose
# ============================================================
-dontwarn androidx.compose.**