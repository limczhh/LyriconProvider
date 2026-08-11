# === Xposed / YukihookAPI ===
# HookEntry 是模块入口，必须保留
-keep class io.github.proify.lyricon.provider.HookEntry { *; }
-keep class * extends com.highcapable.yukihookapi.hook.entity.YukiBaseHooker { *; }
-keep class com.highcapable.yukihookapi.** { *; }
-keepattributes RuntimeVisibleAnnotations
-keep @com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed class * { *; }

# === Provider 常量（包名、图标等运行时使用）===
-keep class io.github.proify.lyricon.provider.providers.**.Constants { *; }

# === Central Bridge（广播注册、AIDL 通信）===
-keep class io.github.proify.lyricon.central.** { *; }
-keep class io.github.proify.lyricon.subscriber.** { *; }

# === AIDL 生成的接口 ===
-keep class io.github.proify.lyricon.provider.IRemotePlayer { *; }
-keep class io.github.proify.lyricon.provider.IRemoteService { *; }
-keep class io.github.proify.lyricon.provider.IProviderBinder { *; }
-keep class io.github.proify.lyricon.provider.IProviderService { *; }
-keep class io.github.proify.lyricon.subscriber.ISubscriberBinder { *; }
-keep class io.github.proify.lyricon.subscriber.IActivePlayerListener { *; }
-keep class io.github.proify.lyricon.subscriber.IRemoteService { *; }
-keep class io.github.proify.lyricon.provider.ProviderInfo { *; }
-keep class io.github.proify.lyricon.subscriber.ProviderInfo { *; }
-keep class io.github.proify.lyricon.lyric.model.Song { *; }

# === KavaRef 反射库 ===
-dontwarn java.lang.reflect.AnnotatedType

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
