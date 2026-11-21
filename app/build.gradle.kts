plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * 正式签名配置。
 *
 * 密码和 keystore 路径都不进仓库（.gitignore 已排除 *.jks / *.keystore），
 * 从下面两个地方按顺序找：
 *   1. 环境变量 WEARBOOK_STORE_PASS（CI 用）
 *   2. 本机 ~/.android/keys/release-password.txt（开发机用）
 *
 * 找不到就不签名、继续产出 unsigned 包 —— 这样没有密钥的机器（或 CI 的未授权 PR）
 * 依然能编译，不会因为缺配置直接构建失败。
 *
 * ⚠️ keystore 一旦丢失，之前发出去的包就再也无法覆盖升级（签名不同，安卓会拒绝安装）。
 *    务必备份 ~/.android/keys/ 整个目录。
 */
val releaseKeystore: File? = File(System.getProperty("user.home"), ".android/keys/wearbook-release.jks")
    .takeIf { it.exists() }

val releaseStorePass: String? = System.getenv("WEARBOOK_STORE_PASS")
    ?: File(System.getProperty("user.home"), ".android/keys/release-password.txt")
        .takeIf { it.exists() }
        ?.readText()
        // ⚠️ 必须去掉 BOM：PowerShell 的 `Out-File -Encoding utf8`（PS 5.1）会在文件头写
        // EF BB BF，混进密码里就变成"keystore password was incorrect"，
        // 而且错误信息只字不提 BOM，极难排查（本项目已踩过一次）。
        ?.trim('\uFEFF', '\u0000', ' ', '\t', '\r', '\n')
        ?.takeIf { it.isNotBlank() }

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
        versionCode = 2091
        versionName = "1.9.1"
        resourceConfigurations += listOf("zh", "en")
        // 手表几乎都是 ARM：出 32 位（armeabi-v7a）并兼容 64 位（arm64-v8a）。
        // x86_64 是为**官方安卓模拟器**留的：模拟器镜像是 x86_64，只有带上它才能在
        // 320×360 / Android 10 的手表规格虚拟设备上装包、跑自动化测试（截图为证）。
        // 代价只有一个 9KB 的 libandroidx.graphics.path.so，ARM 设备完全不受影响。
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64") }
    }

    // ⚠️ signingConfigs 必须写在 buildTypes **之前**：Kotlin DSL 按声明顺序求值，
    // buildTypes 里引用 getByName("release") 时它得已经存在，否则报
    // "SigningConfig with name 'release' not found"。
    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = releaseKeystore
                storePassword = releaseStorePass
                keyAlias = "wearbook"
                keyPassword = releaseStorePass
                // 三个方案都开：v1 给老设备（minSdk 24），v2/v3 给新系统并支持密钥轮换
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true          // R8：去掉未用到的 Compose/图标代码，手表存储更友好
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // 有正式 keystore 就直接由 Gradle 签名；没有则留空，产出 unsigned 包交人工处理
            signingConfig = if (releaseKeystore != null && !releaseStorePass.isNullOrBlank()) {
                signingConfigs.getByName("release")
            } else null
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
