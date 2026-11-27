import java.io.File

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

/**
 * 读取并**自增**构建号（build-number.txt），返回本次构建使用的号。
 *
 * ⚠️ 必须缓存：这个函数在 defaultConfig 里会被调用两次（versionCode 和
 * buildConfigField）。如果不缓存，同一次构建里 versionCode 与 BuildConfig.BUILD_NUMBER
 * 会差 1，用户看到的号和系统判断升级用的号对不上。
 *
 * 设计取舍：
 *   - 文件不存在时从 1 开始，不会崩（新克隆仓库也能构建）；
 *   - 写入用原子替换（先写 .tmp 再 rename），避免构建被中断时留下半个文件；
 *   - 只在**真正构建时**递增。`gradle tasks` 这类配置阶段也会执行它 ——
 *     这是 Gradle 的固有行为，代价是偶尔多跳几个号。号断了不影响正确性，
 *     只要保证单调递增即可（versionCode 的唯一要求）。
 */
var cachedBuildNumber: Int? = null

fun readBuildNumber(): Int {
    cachedBuildNumber?.let { return it }
    val f = File(rootDir, "build-number.txt")
    val prev = f.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
    val next = prev + 1
    // 原子写：先落 .tmp 再替换，防止中断留下空文件导致下次从 1 重新开始
    val tmp = File(rootDir, "build-number.txt.tmp")
    tmp.writeText("$next\n")
    if (f.exists()) f.delete()
    tmp.renameTo(f)
    cachedBuildNumber = next
    return next
}

android {
    namespace = "com.webook.watch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.webook.watch"
        minSdk = 24          // 覆盖绝大多数安卓手表（普通手机也能装）
        targetSdk = 34

        /*
         * ============ 版本号：双轨制 ============
         *
         * 【发布号】versionName —— 给人看的语义化版本（1.9.2），对应 VERSIONING.md 的规则。
         *   只有在**对外发版**时才改它。设置页显示、User-Agent、git tag 都用这个。
         *
         * 【构建号】versionCode —— 给安卓系统看的，必须单调递增才能覆盖安装。
         *   现在由 `build-number.txt` 自动维护：每次构建 +1，不需要人记。
         *
         * 为什么要拆开（以前是一个数，两个职责）：
         *   1. 想给用户发 1.9.2，但自己要先跑几个内部包试（1.9.2-rc1/rc2），
         *      versionName 只能写一个值，写哪个都别扭；
         *   2. versionCode 靠「1000×major + 10×minor + patch + 1000」推算，
         *      实际已经漂了（1.2.0 是 2012、1.2.1 却是 2013，1.3.0 又跳回 2020），
         *      公式和事实对不上，人算容易错；
         *   3. 用户问「我这是什么版本」时，需要能同时看到"发布版本"和"这次构建"。
         *
         * 现在的规则：
         *   - versionName 手写，只在发版时改；
         *   - versionCode 自动 +1，保证永远递增、永不重复；
         *   - 构建号也写进 APK（见 buildConfigField），设置页显示成「1.9.2 (build 2103)」。
         *
         * ⚠️ build-number.txt 必须提交进仓库，否则换机器构建会从旧号重来，
         *    导致 versionCode 回退 → 安卓拒绝安装「版本低于已安装版本」。
         */
        versionName = "1.11.2"
        versionCode = readBuildNumber()
        resourceConfigurations += listOf("zh", "en")
        // 构建号也塞进 BuildConfig，界面和 User-Agent 都能读到
        buildConfigField("int", "BUILD_NUMBER", readBuildNumber().toString())
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
    // buildConfig 必须显式打开：AGP 8 起默认关闭（官方为了加快构建）。
    // 我们要用 BuildConfig.BUILD_NUMBER 把构建号带进 APK。
    buildFeatures { compose = true; buildConfig = true }
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
