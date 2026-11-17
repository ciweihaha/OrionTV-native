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

// Hilt 2.58 自带的 kotlin-metadata-jvm 2.2.20 最高只读 metadata 2.3.0，
// Kotlin 2.4 编译器产出的 metadata 是 2.4.0，hiltJavaCompile 会炸
//（"maximum supported version is 2.3.0"）。强制全配置用与 Kotlin 编译器
// 同版的 kotlin-metadata-jvm；升 Kotlin 时同步改这里。
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.21")
    }
}

android {
    namespace = "com.oriontv"
    // compileSdk 37.2（2026.09.00 BOM 起的 Compose 1.12.1 / Coil 3.6.3 /
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
        versionCode = 10706
        versionName = "2.0.3"

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
