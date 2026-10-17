plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.webook.watch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.webook.watch"
        minSdk = 24          // 覆盖绝大多数安卓手表（普通手机也能装）
        targetSdk = 34
        // 版本号规则见 VERSIONING.md：三段式语义化版本 major.minor.patch。
        // versionName 是**唯一的版本号来源**，别处（README / CHANGELOG / User-Agent）都不许硬编码。
        // versionCode 必须单调递增才能覆盖安装，这里按 1000×major + 10×minor + patch + 1000 推，
        // 免得纯靠人手记（1.1.0 -> 2010）。
        versionCode = 2020
        versionName = "1.3.0"
        resourceConfigurations += listOf("zh", "en")
        // 手表几乎都是 ARM：出 32 位（armeabi-v7a）并兼容 64 位（arm64-v8a）。
        // x86_64 是为**官方安卓模拟器**留的：模拟器镜像是 x86_64，只有带上它才能在
        // 320×360 / Android 10 的手表规格虚拟设备上装包、跑自动化测试（截图为证）。
        // 代价只有一个 9KB 的 libandroidx.graphics.path.so，ARM 设备完全不受影响。
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true          // R8：去掉未用到的 Compose/图标代码，手表存储更友好
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // 先产出未签名 APK，最后用 apksigner 以默认 debug keystore 强制 v1+v2 签名
            signingConfig = null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")     // 分页与解析逻辑的 JVM 单测
}
