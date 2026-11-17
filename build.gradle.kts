// Top-level build file
plugins {
    alias(libs.plugins.android.application) apply false
    // C60-P2-2：AGP 9 内置 Kotlin，kotlin-android 插件与之冲突、必须去掉
    //（只保留 compose 与 serialization 两个独立编译器插件）。
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
