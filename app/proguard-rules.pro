# === libxposed API 102 ===
-dontwarn io.github.libxposed.annotation.**
-dontwarn androidx.annotation.NonNull
-dontwarn androidx.annotation.Nullable
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keep class org.luckypray.dexkit.** { *; }

# === AIDL 接口（Binder 接口名必须保持不变）===
-keep class io.github.proify.lyricon.subscriber.ISubscriberBinder { *; }
-keep class io.github.proify.lyricon.subscriber.IActivePlayerListener { *; }
-keep class io.github.proify.lyricon.subscriber.IRemoteService { *; }
-keep class io.github.proify.lyricon.subscriber.ProviderInfo { *; }

# === Kotlin 序列化 ===
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

# === 混淆优化 ===
-repackageclasses ''
-allowaccessmodification
-optimizationpasses 3

# === 保留源码行号（便于 crash 分析）===
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
