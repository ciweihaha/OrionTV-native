plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.oriontv"
    // compileSdk/targetSdk 35：CONTRACT 写的是 34，但 CONTRACT 指定的依赖本身要求 35
    //（androidx.tv:tv-foundation:1.0.0 与 androidx.core:core-ktx:1.15.0 的
    // aar-metadata.properties 都是 minCompileSdk=35；34 下 :app:checkDebugAarMetadata
    // 必失败）。minSdk 仍是 24，电视安装不受影响。待总协调追认。
    compileSdk = 35

    defaultConfig {
        applicationId = "com.oriontv"
        minSdk = 24
        targetSdk = 35
        versionCode = 10703
        versionName = "2.0.0"

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
            // 产物名固定 orionTV.{version}.apk（与 1.7.2 版本接口拼的下载地址一致）
            applicationVariants.all {
                outputs.all {
                    (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                        .outputFileName = "orionTV.${versionName}.apk"
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
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
    implementation(libs.androidx.material.icons.core)
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

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.android)
}
