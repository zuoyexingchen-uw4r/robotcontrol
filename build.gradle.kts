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
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
    }
}
