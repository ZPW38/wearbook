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
        versionCode = 17
        versionName = "1.0.16"
        resourceConfigurations += listOf("zh", "en")
        // 手表几乎都是 ARM：只出 32 位（armeabi-v7a）并兼容 64 位（arm64-v8a）
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
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
