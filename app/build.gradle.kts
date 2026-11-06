plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.shawnbugs.kaudio"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shawnbugs.kaudio"
        // Android 10+：导出用 MediaStore 写「音乐」目录，不需要存储权限
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        // ffmpeg 只编了 arm64（近几年的手机都是），包不至于翻倍
        ndk { abiFilters += "arm64-v8a" }
    }

    // 固定签名（仓库内 app/debug.keystore，本 App 独立生成，不与其他 App 共用）。
    // debug / release 共用：新包能直接覆盖安装。自用侧载、不上应用商店。
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // libffmpeg.so 其实是 ffmpeg 可执行文件（CI 编出来后放进 jniLibs）。
    // 必须按老方式解压到 nativeLibraryDir，系统才允许直接执行它（App 私有目录里的文件不能执行）。
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

base {
    archivesName.set("KAudio-${android.defaultConfig.versionName}")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// 没有 ffmpeg 的包装起来也打不开文件，直接让构建失败，免得发出去一个空壳
val checkFfmpeg by tasks.registering {
    val so = file("src/main/jniLibs/arm64-v8a/libffmpeg.so")
    doLast {
        if (!so.exists()) throw GradleException("缺少 $so ——先跑 ffmpeg-android/build.sh（CI 里自动做）")
    }
}
tasks.named("preBuild") { dependsOn(checkFfmpeg) }

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
