pluginManagement {
    plugins {
        id("com.android.application") version "8.7.3"
    }
    repositories {
        maven("https://maven.aliyun.com/repository/google/")
        maven("https://maven.aliyun.com/repository/gradle-plugin/")
        maven("https://maven.aliyun.com/repository/public/")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google/")
        maven("https://maven.aliyun.com/repository/public/")
        maven("https://jitpack.io")   // usb-serial-for-android
        google()
        mavenCentral()
    }
}

rootProject.name = "RobotControl"
include(":app")
