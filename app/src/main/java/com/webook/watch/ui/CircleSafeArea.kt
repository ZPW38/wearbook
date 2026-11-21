package com.webook.watch.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 圆屏安全区（按真实圆弧计算）。
 *
 * ## 为什么固定 padding 不够
 *
 * 圆屏不是"四角被切圆的矩形"，而是**整条边都在往里收**：越靠近上下端，可用宽度越窄。
 * 设屏幕为直径 D 的圆、中心在 (D/2, D/2)，则距中心 |dy| 处的可用半宽是：
 *
 *     halfW(dy) = √( (D/2)² − dy² )
 *
 * 取 450px 的圆屏实际算一下就很清楚：
 *
 * | y 位置 | 可用半宽 | 左边界 x |
 * |---|---|---|
 * | 225（正中） | 225 | 0 |
 * | 160 | 215 | 10 |
 * | 120 | 199 | 26 |
 * | 80 | 172 | 53 |
 * | 40 | 128 | 97 |
 * | 24（标题栏处） | **101** | **124** |
 *
 * 也就是说标题栏所在的 y=24，左右各只剩 101px —— 而固定内缩只有 12.6px，
 * 标题从 x=13 开始，**整段超出圆外**。这是旧实现（只给四边加固定 padding）的根本缺陷。
 *
 * ## 这里的做法
 *
 * 不追求"内接矩形"（320×360 的内接矩形只有约 226×226，顶部要空 60dp 以上，
 * 正文会被压成一条缝）。而是：
 * 1. **按元素实际高度算可用宽度** —— 每个区块用自己的 y 区间求最窄处的半宽，
 *    这样中间宽、两端窄，符合圆形；
 * 2. 给出 [CircleSafeArea.horizontalInsetAt] 让调用方按需取任意高度的内缩；
 * 3. 保留 [_globalInset] 作为整棵树的兜底，避免某个页面忘记处理时内容真的被切掉。
 */
data class CircleInsets(
    val top: Dp,
    val bottom: Dp,
    val start: Dp,
    val end: Dp
)

object CircleSafeArea {

    /**
     * 全局兜底内缩比例（相对短边）。
     *
     * 只用来保证"至少留这么多"，真正的精细控制交给 [horizontalInsetAt]。
     * 取 3%（320 屏约 9.6dp）：再多会白白浪费本来就窄的版面。
     */
    private const val GLOBAL_RATIO = 0.030f

    /** 竖向留白比例：上下端弧线最狠，标题和底部栏都在那儿 */
    private const val TOP_BOTTOM_RATIO = 0.055f

    /**
     * 计算**某段竖直区间**在圆内需要的内缩。
     *
     * @param shortSideDp 屏幕短边（dp）
     * @param topDp      该区块距屏幕顶部的 dp
     * @param heightDp   该区块高度 dp（越小越精确；传 0 表示只看 topDp 那一行）
     * @return 该区间最窄处需要的水平内缩
     */
    fun horizontalInsetAt(
        shortSideDp: Float,
        topDp: Float,
        heightDp: Float
    ): Dp {
        val r = shortSideDp / 2f
        if (r <= 0f) return 0.dp
        // 该区间内离圆心最远的那个 y（即最窄处）：取区间两端中距离更大的
        val centerY = r
        val y1 = topDp
        val y2 = topDp + max(0f, heightDp)
        val worstDy = max(abs(y1 - centerY), abs(y2 - centerY))
        if (worstDy >= r) return r.dp      // 整段都在圆外，返回最大内缩
        val halfW = sqrt(r * r - worstDy * worstDy)
        return (r - halfW).coerceAtLeast(0f).dp
    }

    /**
     * 屏幕最短边（px）→ 全局兜底安全内缩。
     * 需要 density 是因为 px 和 dp 的换算离不开它 —— 直接拿 px 当 dp 用，
     * 在 density 2.0 的设备上会得到两倍的留白。
     */
    fun of(shortSidePx: Int, density: Float): CircleInsets {
        val dp = shortSidePx.toFloat().coerceAtLeast(1f) / density.coerceAtLeast(0.5f)
        return ofDp(dp)
    }

    /** 直接按 dp 算（横竖屏切换后短边可能变化，所以每次调用都重新算，别缓存成常量） */
    fun ofDp(shortSideDp: Float): CircleInsets {
        val side = (shortSideDp * GLOBAL_RATIO).coerceAtLeast(6f)
        val tb = (shortSideDp * TOP_BOTTOM_RATIO).coerceAtLeast(10f)
        return CircleInsets(top = tb.dp, bottom = tb.dp, start = side.dp, end = side.dp)
    }

    /**
     * 圆屏下，一个**整行**元素（标题栏、底部工具条）的最大可用宽度。
     *
     * 标题栏通常贴顶，那里弧线最狠；用它来决定标题文字能占多宽、要不要省略。
     */
    fun usableWidthAt(shortSideDp: Float, topDp: Float, heightDp: Float): Dp {
        val inset = horizontalInsetAt(shortSideDp, topDp, heightDp)
        return (shortSideDp - inset.value * 2f).coerceAtLeast(0f).dp
    }

    /** 弧线在某个高度是否已经开始明显收窄（用来决定要不要给元素额外让位） */
    fun isNarrowAt(shortSideDp: Float, topDp: Float, heightDp: Float): Boolean {
        val inset = horizontalInsetAt(shortSideDp, topDp, heightDp).value
        return inset > shortSideDp * 0.06f      // 被压缩超过 6% 就算窄
    }

    /** 一行文字的可用高度内，最窄处的半宽（px 友好版，给 Canvas 类绘制用） */
    fun halfWidthPx(shortSidePx: Float, yCenterPx: Float): Float {
        val r = shortSidePx / 2f
        val dy = abs(yCenterPx - r)
        if (dy >= r) return 0f
        return sqrt(r * r - dy * dy)
    }
}
