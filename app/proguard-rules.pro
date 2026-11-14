# OrionTV-native 混淆规则（骨架阶段）
# Hilt / Room / kotlinx.serialization 的 keep 规则主要由各库自带的
# consumer proguard 规则提供；这里只补业务模型与反射入口。

-keep class com.oriontv.data.** { *; }
-keep class com.oriontv.core.** { *; }
-keep class com.oriontv.feature.** { *; }

# kotlinx.serialization：保留 @Serializable 模型
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.oriontv.** {
    *** Companion;
}
-keepclassmembers class com.oriontv.** {
    @kotlinx.serialization.Serializable <fields>;
}

# ExoPlayer / OkHttp 不需要额外规则；保留行号便于崩溃定位
-keepattributes SourceFile,LineNumberTable
