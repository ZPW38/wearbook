package com.webook.watch.data

import android.content.Context
import com.webook.watch.BuildConfig

/**
 * 应用版本号的运行时读取。
 *
 * 存在的意义：**版本号只该有一个来源** —— `app/build.gradle.kts` 的 `versionName`。
 * 以前 User-Agent 里写死了 "WearBook/1.0"，那是手写死值，改版本时八成会忘，
 * 结果服务端看到的永远是老版本号 —— 这类"沉默漂移"最难查。
 *
 * 现在改成从 PackageManager 读真实版本号，读不到（极端情况）才退回一个不带版本号的 UA。
 */
object AppVersion {

    /**
     * 构建号（内部迭代号），由 `build-number.txt` 在构建时自增，见 app/build.gradle.kts。
     *
     * 与 versionName（发布号）分开：发一个版本可能要构建十几次，
     * 用户报问题时「1.9.2 (build 2103)」能精确对应到某一次构建，
     * 只说「1.9.2」则无法区分。
     */
    fun buildNumber(): Int = runCatching { BuildConfig.BUILD_NUMBER }.getOrDefault(0)

    /**
     * 形如 `WearBook/1.1.0 (Android 10)`；
     * 带构建号时形如 `WearBook/1.1.0+2103 (Android 10)`（+ 是 semver 的构建元数据写法）。
     */
    fun userAgent(ctx: Context): String {
        val name = runCatching {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val os = runCatching { android.os.Build.VERSION.RELEASE }.getOrNull()?.takeIf { it.isNotBlank() }
        val build = buildNumber()
        return buildString {
            append("WearBook")
            if (name != null) append("/").append(name)
            if (build > 0) append("+").append(build)
            append(" (Android")
            if (os != null) append(" ").append(os)
            append(")")
        }
    }

    /** 显示用的版本名，如 "1.1.0"；读不到返回 "?" */
    fun display(ctx: Context): String = runCatching {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "?"
}
