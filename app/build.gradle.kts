plugins {
    alias(libs.plugins.android.application)
    // C60-P2-2：AGP 9 内置 Kotlin，kotlin-android 插件与之冲突、已去掉；
    // compose 与 serialization 是独立编译器插件，仍需显式应用。
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

import java.io.FileInputStream
import java.util.Properties

// Hilt 2.58 自带的 kotlin-metadata-jvm 2.2.20 最高只读 metadata 2.3.0，
// Kotlin 2.4 编译器产出的 metadata 是 2.4.0，hiltJavaCompile 会炸
//（"maximum supported version is 2.3.0"）。强制全配置用与 Kotlin 编译器
// 同版的 kotlin-metadata-jvm；升 Kotlin 时同步改这里。
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.21")
    }
}

// 第 75 轮：内置 MoonTV 账号密码——构建时注入，绝不进仓库。
// 来源优先级：Gradle 属性 -PmoontvPassword > 环境变量 MOONTV_PASSWORD >
// local.properties 的 moontv.password；都没有时为空串（内置登录关闭，编出的包与旧版行为一致）。
// 注意：local.properties 已在 .gitignore 里；密码只进 BuildConfig，不进任何提交。
// （build.gradle.kts 里不能写 java.util.Properties：java 会被解析成 Java
// 插件扩展，改用 import 后的 Properties。）
fun readMoontvPassword(): String {
    val fromProperty = project.findProperty("moontvPassword") as? String
    if (!fromProperty.isNullOrBlank()) return fromProperty
    val fromEnv = System.getenv("MOONTV_PASSWORD")
    if (!fromEnv.isNullOrBlank()) return fromEnv
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        val localProps = Properties()
        FileInputStream(localFile).use { localProps.load(it) }
        val fromLocal = localProps.getProperty("moontv.password")
        if (!fromLocal.isNullOrBlank()) return fromLocal
    }
    return ""
}

val moontvBuiltinPassword = readMoontvPassword()

/** 把任意字符串转成合法的 Java 字符串字面量（含引号），供 buildConfigField 用。 */
fun javaStringLiteral(value: String): String {
    val sb = StringBuilder("\"")
    for (ch in value) {
        when (ch) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(ch)
        }
    }
    sb.append('"')
    return sb.toString()
}

android {
    namespace = "com.oriontv"
    // compileSdk 37.2（2026.11.01 BOM 起的 Compose 1.13.0 / Coil 3.6.3 /
    // core-ktx 1.19.1 等库的 aar-metadata 要求 minCompileSdk=37；取最新
    // 小版本 37.2。targetSdk 仍是 35，不引入新版运行时行为变更）。
    // AGP 9 新 DSL：compileSdk 用 release(37) { minorApiLevel = 2 } 写法。
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.oriontv"
        minSdk = 24
        targetSdk = 35
        versionCode = 10729
        versionName = "2.0.25"

        // 内置 MoonTV 账号密码（第 75 轮）：构建时注入；空串 = 未配置，内置登录关闭。
        // 密码嵌进 APK 二进制（不在 git 里），这是内置账号固有的取舍，用户已确认要内置登录。
        buildConfigField("String", "MOONTV_BUILTIN_PASSWORD", javaStringLiteral(moontvBuiltinPassword))

        // 只打包 arm64-v8a（用户电视是 arm64），目标 APK < 25MB
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Release 签名只在 CI Secrets 存在时启用；本地无 Secrets 也能编 debug
    val keystoreBase64 = System.getenv("ORION_KEYSTORE_BASE64")
    val keystoreFilePath = System.getenv("ORION_KEYSTORE_FILE")
    val keystorePassword = System.getenv("ORION_KEYSTORE_PASSWORD")
    val keyAlias = System.getenv("ORION_KEY_ALIAS")
    val keyPassword = System.getenv("ORION_KEY_PASSWORD")
    if (keystoreBase64 != null && keystoreFilePath != null &&
        keystorePassword != null && keyAlias != null && keyPassword != null
    ) {
        val keystoreFile = file(keystoreFilePath)
        if (keystoreFile.exists()) {
            signingConfigs {
                create("release") {
                    storeFile = keystoreFile
                    storePassword = keystorePassword
                    this.keyAlias = keyAlias
                    this.keyPassword = keyPassword
                }
            }
        }
    }

    // B4-P2-21：LenientDecode/EpgParser 改用 android.util.Log 后，JVM 单测里
    // 未 mock 的 android.* 方法默认抛错；返回默认值即可（Log 调用返回值无意义）。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // C60-P2-2：AGP 9 删掉了旧 Variant API（applicationVariants /
    // BaseVariantOutputImpl 内部类），产物名改用 androidComponents 新 API；
    // 产物名固定 orionTV.{version}.apk（与 1.7.2 版本接口拼的下载地址一致）。
    // 新 API 的 variant 上没有 versionName，从 defaultConfig 取（唯一事实源）。
    val appVersionName = android.defaultConfig.versionName
    androidComponents {
        onVariants(selector().withBuildType("release")) { variant ->
            variant.outputs.forEach { output ->
                output.outputFileName.set("orionTV.${appVersionName}.apk")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // C60-P2-2：AGP 9 内置 Kotlin 不再注册 kotlin{} 扩展（KotlinAndroidProjectExtension），
    // jvmTarget 改由 compileOptions.targetCompatibility（17）默认给出，故删掉旧的 kotlin{} 块。
    buildFeatures {
        compose = true
        // 版本号（BuildConfig.VERSION_NAME/VERSION_CODE）供设置页“关于”与更新比对用。
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose for TV
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    // 播放器
    implementation(libs.media3.exoplayer)
    // v2.0.6 热修复：聚合源基本全是 m3u8，ExoPlayer 的 DefaultMediaSourceFactory
    // 遇到 .m3u8 会反射加载 HlsMediaSource$Factory；从建仓起就没声明过这个
    // 依赖，dex 里没有该类，点播/直播开播即 ClassNotFoundException 全挂。
    // 与 media3 同版本 1.11.1。
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.common)
    implementation(libs.media3.ui)

    // 网络 / 序列化
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // 图片
    implementation(libs.coil3.compose)
    implementation(libs.coil3.compose.core.android)
    // Coil 3 核心默认不加载网络图，必须加网络 fetcher，否则远程海报全空白
    implementation(libs.coil3.network.okhttp)

    // 本地存储
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.datastore.preferences)
    ksp(libs.room.compiler)

    // DI
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // 协程
    implementation(libs.kotlinx.coroutines.android)

    // 远程输入服务（12346 端口）+ 二维码
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)

    // 登录凭据安全存储（EncryptedSharedPreferences）
    implementation(libs.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.android)
}
