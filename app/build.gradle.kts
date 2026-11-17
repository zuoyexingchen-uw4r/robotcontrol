plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
} // kotlin 版本由根 buildscript classpath 提供

android {
    namespace = "com.robomaster.control"
    compileSdk = 36
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.robomaster.control"
        minSdk = 24
        targetSdk = 36
        versionCode = 7
        versionName = "0.6.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // USB 串口库：支持 CH34x（CH340/341 原生驱动；CH343 走 CDC-ACM 驱动）
    implementation("com.github.mik3y:usb-serial-for-android:3.7.0")
}
