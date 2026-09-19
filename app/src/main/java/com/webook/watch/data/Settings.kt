package com.webook.watch.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 阅读设置（SharedPreferences）
 *
 * 说明：日间/夜间两套排版（字号、行距、背景）默认共用；
 * 关掉「共用布局」后，夜间会单独记一套，互不影响。
 */
class Settings(ctx: Context) {

    private val sp = ctx.getSharedPreferences("wearbook", Context.MODE_PRIVATE)

    /**
     * 设置修订号：每次改设置 +1。
     *
     * 界面里读一下 `settings.rev` 就等于"订阅了设置变化"。不加这个的话，Compose 会把
     * 参数没变的 composable 直接跳过重组（设置值本身存在普通对象里、不是 Compose 状态），
     * 于是出现「点了开关状态不更新，退出界面再进来才正常」的怪毛病。
     */
    var rev by mutableIntStateOf(0)
        internal set

    var fontSize: Float
        get() = sp.getFloat("fs", 18f)
        set(v) = sp.edit().putFloat("fs", v).apply()

    var lineSpacing: Float
        get() = sp.getFloat("lh", 1.7f)
        set(v) = sp.edit().putFloat("lh", v).apply()

    /**
     * 阅读模式：
     *   page   = 翻页（默认，一屏一页，点两侧/左右滑动翻页）
     *   scroll = 滑动（整章连续上下滚动，像看网页一样）
     */
    var readMode: String
        get() = sp.getString("readmode", "page") ?: "page"
        set(v) = sp.edit().putString("readmode", v).apply()

    /** 主题：auto / light / dark */
    var theme: String
        get() = sp.getString("theme", "dark") ?: "dark"
        set(v) = sp.edit().putString("theme", v).apply()

    /** 自动翻页间隔秒，0 = 关闭 */
    var autoSeconds: Int
        get() = sp.getInt("auto", 0)
        set(v) = sp.edit().putInt("auto", v).apply()

    /** 朗读语速 */
    var speechRate: Float
        get() = sp.getFloat("rate", 0.95f)
        set(v) = sp.edit().putFloat("rate", v).apply()

    /** 圆屏安全边距 */
    var roundScreen: Boolean
        get() = sp.getBoolean("round", true)
        set(v) = sp.edit().putBoolean("round", v).apply()

    /** 翻页震动 */
    var vibrate: Boolean
        get() = sp.getBoolean("vib", true)
        set(v) = sp.edit().putBoolean("vib", v).apply()

    /* ---------------- 1.0.5 新增 ---------------- */

    /** 屏幕亮度 0~1；负数表示跟随系统 */
    var brightness: Float
        get() = sp.getFloat("bright", -1f)
        set(v) = sp.edit().putFloat("bright", v).apply()

    /** 阅读时常亮（不自动熄屏） */
    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep", true)
        set(v) = sp.edit().putBoolean("keep", v).apply()

    /** 音量键翻页 */
    var volumeKeys: Boolean
        get() = sp.getBoolean("volkey", false)
        set(v) = sp.edit().putBoolean("volkey", v).apply()

    /** 点击屏幕左右两侧翻页；关闭后点击只用来开关菜单 */
    var tapPaging: Boolean
        get() = sp.getBoolean("tappage", true)
        set(v) = sp.edit().putBoolean("tappage", v).apply()

    /** 文字两端对齐 */
    var justify: Boolean
        get() = sp.getBoolean("justify", false)
        set(v) = sp.edit().putBoolean("justify", v).apply()

    /** 是否显示左侧亮度调节控件 */
    var showBrightness: Boolean
        get() = sp.getBoolean("showbright", true)
        set(v) = sp.edit().putBoolean("showbright", v).apply()

    /** 屏幕方向：auto / portrait / landscape */
    var orientation: String
        get() = sp.getString("orient", "auto") ?: "auto"
        set(v) = sp.edit().putString("orient", v).apply()

    /**
     * 屏蔽屏幕左边缘的「侧滑返回」：很多手表 ROM 会在左边缘划一下就返回。
     * 原理是把左边缘一条窄带声明成系统手势排除区，系统就不再把它当返回手势，
     * 这段滑动会原样交给应用 —— 阅读页左边缘滑动于是变成翻页，而不是返回。
     */
    var blockEdgeSwipe: Boolean
        get() = sp.getBoolean("edge", true)
        set(v) = sp.edit().putBoolean("edge", v).apply()

    /** 隐藏状态栏（沉浸式） */
    var hideStatusbar: Boolean
        get() = sp.getBoolean("hidebar", true)
        set(v) = sp.edit().putBoolean("hidebar", v).apply()

    /** 朗读时保持屏幕常亮 */
    var keepOnSpeaking: Boolean
        get() = sp.getBoolean("keepspk", true)
        set(v) = sp.edit().putBoolean("keepspk", v).apply()

    /** 共用布局：日间/夜间使用同一套字号行距 */
    var sharedLayout: Boolean
        get() = sp.getBoolean("shared", true)
        set(v) = sp.edit().putBoolean("shared", v).apply()

    /** 夜间字号（不共用布局时生效） */
    var fontSizeDark: Float
        get() = sp.getFloat("fs_d", fontSize)
        set(v) = sp.edit().putFloat("fs_d", v).apply()

    /** 夜间行距（不共用布局时生效） */
    var lineSpacingDark: Float
        get() = sp.getFloat("lh_d", lineSpacing)
        set(v) = sp.edit().putFloat("lh_d", v).apply()

    /** 日间背景序号（见 ui.ReaderBackgrounds.light） */
    var bgLight: Int
        get() = sp.getInt("bg_l", 0)
        set(v) = sp.edit().putInt("bg_l", v).apply()

    /** 夜间背景序号（见 ui.ReaderBackgrounds.dark） */
    var bgDark: Int
        get() = sp.getInt("bg_d", 0)
        set(v) = sp.edit().putInt("bg_d", v).apply()

    /** 首次进入阅读页是否显示过操作说明 */
    var readerTipsShown: Boolean
        get() = sp.getBoolean("tips", false)
        set(v) = sp.edit().putBoolean("tips", v).apply()

    fun isDark(systemDark: Boolean): Boolean = when (theme) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }

    /** 当前主题下实际生效的字号 */
    fun fontSizeOf(dark: Boolean): Float = if (dark && !sharedLayout) fontSizeDark else fontSize

    /** 当前主题下实际生效的行距 */
    fun lineSpacingOf(dark: Boolean): Float = if (dark && !sharedLayout) lineSpacingDark else lineSpacing

    fun setFontSizeOf(dark: Boolean, v: Float) {
        if (dark && !sharedLayout) fontSizeDark = v else fontSize = v
    }

    fun setLineSpacingOf(dark: Boolean, v: Float) {
        if (dark && !sharedLayout) lineSpacingDark = v else lineSpacing = v
    }
}
