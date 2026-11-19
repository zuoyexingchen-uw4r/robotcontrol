plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
} // kotlin / compose 插件版本由根 buildscript classpath 提供

// 本地 release 签名（密钥文件与口令放 gradle.properties，勿同步到公共仓库）
val ksFile: String = (project.findProperty("langya.storeFile") as String?) ?: "keystore/langya-release.jks"
val ksStorePass: String = (project.findProperty("langya.storePassword") as String?) ?: "langya2026"
val ksKeyAlias: String = (project.findProperty("langya.keyAlias") as String?) ?: "langya"
val ksKeyPass: String = (project.findProperty("langya.keyPassword") as String?) ?: "langya2026"

android {
    namespace = "com.robomaster.control"
    compileSdk = 36
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.robomaster.control"
        minSdk = 24
        targetSdk = 36
        versionCode = 10
        versionName = "0.8.1"
    }

    signingConfigs {
        create("release") {
            val f = rootProject.file(ksFile)
            if (f.exists()) {
                storeFile = f
                storePassword = ksStorePass
                keyAlias = ksKeyAlias
                keyPassword = ksKeyPass
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    lint {
        // AGP 8.7.3 的 lint 分析器与 Kotlin 2.2 的 K2 前端不兼容（NonNullableMutableLiveData
        // 检测器会在 DataBridge.kt 上抛 KaCallableMemberCall 断言失败）。release 打包不跑
        // lintVital，改由 assembleDebug + 手工审查保证质量。
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ── Jetpack Compose + Material 3 Expressive ──
    // material3 1.4.0 起提供 MaterialExpressiveTheme / MotionScheme / ButtonGroup /
    // ToggleButton / 粗轨道 Slider 等 expressive API。
    implementation("androidx.compose.ui:ui:1.10.4")
    implementation("androidx.compose.ui:ui-graphics:1.10.4")
    implementation("androidx.compose.ui:ui-tooling-preview:1.10.4")
    implementation("androidx.compose.foundation:foundation:1.10.4")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    debugImplementation("androidx.compose.ui:ui-tooling:1.10.4")

    // USB 串口库：支持 CH34x（CH340/341 原生驱动；CH343 走 CDC-ACM 驱动）
    implementation("com.github.mik3y:usb-serial-for-android:3.7.0")

    // 二维码解码（纯 Java，无 NDK）：扫码走视觉码流的 Y 平面，不额外开摄像头
    implementation("com.google.zxing:core:3.5.3")
}
