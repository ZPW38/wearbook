package com.webook.watch.data

import android.content.Context

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

    /** 形如 `WearBook/1.1.0 (Android 10)`；拿不到版本号时退化成 `WearBook (Android)` */
    fun userAgent(ctx: Context): String {
        val name = runCatching {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val os = runCatching { android.os.Build.VERSION.RELEASE }.getOrNull()?.takeIf { it.isNotBlank() }
        return buildString {
            append("WearBook")
            if (name != null) append("/").append(name)
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
