package com.webook.watch.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * Material You（Material 3 动态取色）
 *
 * 理念是**跟随用户壁纸**：Android 12+ 由系统按壁纸算出一整套色板，
 * 应用调用 dynamicLight/DarkColorScheme 直接拿 —— 用户换壁纸，整个应用换色。
 * 这是 Material You 的立命之本；写死一套紫色只能算"Material 3 风格"，不是 You。
 *
 * 但手表上有两个现实约束，所以做成三级回退：
 *   ① API 31+ 且系统支持动态色 → 用动态色（真正的 Material You）
 *   ② 否则 → 用下面这套**手调的中性紫**（保留了 M3 的角色化命名，
 *      并且在浅色/深色下都核对过对比度）
 *   ③ 用户可在设置里关掉动态色（有些人就是喜欢固定配色，或者系统取色不好看）
 *
 * ⚠️ 动态色只在 API 31+ 存在，minSdk 是 24，必须做版本与可用性双重判断，
 *    否则低版本会因为类不存在直接 NoClassDefFoundError。
 */

/*
 * 内置色板（默认使用）
 *
 * 完全按 Material 3 的**角色化命名**组织：primary / primaryContainer / surfaceContainer 等
 * 各司其职，而不是散落的十六进制值。这样界面只引用角色名，换色板时整站同步变。
 *
 * 色相选了偏青绿的 "Teal" —— 不用默认紫，因为手表上紫调在 OLED 黑底上容易发灰；
 * 也不选高饱和色，长时间阅读时刺眼。所有前景/背景对都按 WCAG AA(4.5:1) 核对过。
 */
private val FallbackPurpleLight = lightColorScheme(
    primary = Color(0xFF00696E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9CF0F6),
    onPrimaryContainer = Color(0xFF002022),
    secondary = Color(0xFF4A6365),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8EA),
    onSecondaryContainer = Color(0xFF051F21),
    tertiary = Color(0xFF4B607C),
    background = Color(0xFFF4FBFC),
    onBackground = Color(0xFF161D1D),
    surface = Color(0xFFF4FBFC),
    onSurface = Color(0xFF161D1D),
    surfaceVariant = Color(0xFFDAE4E5),
    onSurfaceVariant = Color(0xFF3F4949),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEEF5F6),
    surfaceContainer = Color(0xFFE8EFF0),
    surfaceContainerHigh = Color(0xFFE3E9EA),
    surfaceContainerHighest = Color(0xFFDDE4E5),
    outline = Color(0xFF6F7979),
    outlineVariant = Color(0xFFBEC8C9),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val FallbackPurpleDark = darkColorScheme(
    primary = Color(0xFF80D4DA),
    onPrimary = Color(0xFF00363A),
    primaryContainer = Color(0xFF004F53),
    onPrimaryContainer = Color(0xFF9CF0F6),
    secondary = Color(0xFFB1CBCE),
    onSecondary = Color(0xFF1B3437),
    secondaryContainer = Color(0xFF324B4D),
    onSecondaryContainer = Color(0xFFCCE8EA),
    tertiary = Color(0xFFB3C8E8),
    background = Color(0xFF0E1415),
    onBackground = Color(0xFFDDE4E5),
    surface = Color(0xFF0E1415),
    onSurface = Color(0xFFDDE4E5),
    surfaceVariant = Color(0xFF3F4949),
    onSurfaceVariant = Color(0xFFBEC8C9),
    surfaceContainerLowest = Color(0xFF090F10),
    surfaceContainerLow = Color(0xFF161D1D),
    surfaceContainer = Color(0xFF1A2122),
    surfaceContainerHigh = Color(0xFF252B2C),
    surfaceContainerHighest = Color(0xFF303637),
    outline = Color(0xFF889392),
    outlineVariant = Color(0xFF3F4949),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

/** 小屏字阶：整体比手机略大，保证 240px 屏上可读 */
private val WearTypography = Typography(
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp)
)

/**
 * @param dynamicColor 是否跟随系统动态取色。**默认 false** —— 手表 ROM 取色质量参差不齐，
 *   对比度差的配色会让正文读不清。内置色板在哪个系统上都一样，可读性可预期。
 *   想要动态色的人在设置里自己开，并做了异常兜底。
 */
@Composable
fun WearBookTheme(
    darkTheme: Boolean,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val ctx = LocalContext.current
    val scheme = when {
        // Android 12（API 31）起才有动态取色。低版本调用会因类缺失崩溃，必须挡在这里。
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            // 有些定制 ROM（部分 Wear OS / 精简系统）声明了 31 但没提供取色器，
            // 调用会抛异常 —— 兜住并退回固定色板，不能让整屏白掉。
            runCatching {
                if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            }.getOrNull() ?: if (darkTheme) FallbackPurpleDark else FallbackPurpleLight

        darkTheme -> FallbackPurpleDark
        else -> FallbackPurpleLight
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = WearTypography,
        content = content
    )
}
