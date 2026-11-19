// 根构建脚本：通过 buildscript classpath 引入 AGP 与 Kotlin(经 mavenCentral 镜像)，
// 避免受限网络下 Gradle 插件 marker 解析失败。
buildscript {
    repositories {
        maven("https://maven.aliyun.com/repository/public/")
        maven("https://maven.aliyun.com/repository/google/")
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        // Kotlin 2.2.10 + Compose Compiler 2.2.10：Compose 1.10.4 / Material3 1.4.0
        // 的 klib 元数据版本需要较新的编译器，否则报「compiled with a newer Kotlin」。
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.10")
    }
}
