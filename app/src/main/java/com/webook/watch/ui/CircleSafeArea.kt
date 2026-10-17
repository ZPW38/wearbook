package com.webook.watch.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 圆屏安全区。
 *
 * 圆屏不是"四个角被切成圆的矩形"，而是**整条边都在往里收**：越靠近上下端，可用宽度越窄。
 * 所以固定 `padding(10.dp)` 是不够的 —— 顶部标题、底部按钮、列表第一行和最后一行，
 * 恰好落在弧线切得最狠的位置，看起来就是"字被咬掉一块"。
 *
 * 这里按屏幕短边等比推算内缩量，而不是写死几个 dp：
 * - **上下留得多**（0.070 × 短边，320 屏约 22dp）：上下端弧线最狠，标题和底部栏都在那儿；
 * - **左右留得少**（0.028 × 短边，320 屏约 9dp）：屏幕正中间可用宽度其实是满的，
 *   左右留太多会白白浪费本来就窄的版面。
 *
 * 之所以不做"内接矩形"那种严格算法：320×360 的屏内接矩形只有约 226×226，
 * 意味着顶部要空出 60dp 以上，正文被压成一条缝，得不偿失。
 * 手表的实际做法是"接受四角轻微擦边，但保证文字行和可点元素不贴弧线"，这里取的就是这个平衡。
 */
data class CircleInsets(
    val top: Dp,
    val bottom: Dp,
    val start: Dp,
    val end: Dp
) {
    /**
     * 内容可用的竖向余量比例。给阅读页判断"这行文字会不会贴到弧线"用：
     * 圆屏越矮，同一行文字离弧线越近，调用方据此决定要不要再收一点。
     */
    val verticalSlack: Float get() = 1f
}

object CircleSafeArea {

    private const val TOP_BOTTOM_RATIO = 0.070f
    private const val SIDE_RATIO = 0.028f

    /**
     * 屏幕最短边（px）→ 安全内缩。
     * 需要 density 是因为 px 和 dp 的换算离不开它 —— 直接拿 px 当 dp 用，
     * 在 density 2.0 的设备上会得到两倍的留白。
     */
    fun of(shortSidePx: Int, density: Float): CircleInsets {
        val dp = shortSidePx.toFloat().coerceAtLeast(1f) / density.coerceAtLeast(0.5f)
        return ofDp(dp)
    }

    /** 直接按 dp 算（横竖屏切换后短边可能变化，所以每次调用都重新算，别缓存成常量） */
    fun ofDp(shortSideDp: Float): CircleInsets = CircleInsets(
        top = (shortSideDp * TOP_BOTTOM_RATIO).dp,
        bottom = (shortSideDp * TOP_BOTTOM_RATIO).dp,
        start = (shortSideDp * SIDE_RATIO).dp,
        end = (shortSideDp * SIDE_RATIO).dp
    )
}
