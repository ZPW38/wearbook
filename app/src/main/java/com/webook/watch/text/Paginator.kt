package com.webook.watch.text

import android.graphics.Paint
import android.text.TextPaint
import com.webook.watch.data.ImageRef
import com.webook.watch.data.parseImagePara

/** 一行文本（或一张插图） */
data class PageLine(
    val text: String,
    val indent: Float,      // 该行左边距（px）
    val paraStart: Boolean, // 是否为段落首行
    val paraIndex: Int = 0, // 属于第几个段落（目录与搜索结果定位用）
    /** 插图行：非 null 时这一行画的是一张图，text 为空 */
    val img: ImageRef? = null,
    val imgW: Float = 0f,   // 该图的绘制宽度（px）
    val imgH: Float = 0f    // 该图的绘制高度（px），同时也是这一行的占高
) {
    /** 这一行实际占的高度：图片行用它自己的高度，文字行用统一行高 */
    fun height(lineHeight: Float): Float = if (img != null && imgH > 0f) imgH else lineHeight
}

/** 一页：[start, end) 行区间 */
data class Page(val start: Int, val end: Int) {
    val count: Int get() = end - start
}

object LineBreaker {
    /**
     * 把段落按可用宽度断行。用 TextPaint.breakText，与最终绘制用的是同一支画笔，
     * 所以排版结果和屏幕上的实际渲染完全一致，不会出现半截字。
     *
     * @param maxImgH 插图的最大显示高度；超过就按比例缩小宽度（一张长图不占满整页）
     */
    fun layout(
        paragraphs: List<String>,
        paint: TextPaint,
        width: Float,
        firstLineIndent: Float,
        maxImgH: Float = 0f
    ): List<PageLine> {
        val lines = ArrayList<PageLine>()
        val measured = FloatArray(1)
        paragraphs.forEachIndexed { pi, p ->
            val ref = parseImagePara(p)
            if (ref != null) {
                var w = width
                var h = if (ref.w > 0 && ref.h > 0) width * ref.h.toFloat() / ref.w else width * 0.75f
                if (maxImgH > 0f && h > maxImgH) {
                    h = maxImgH
                    w = if (ref.w > 0 && ref.h > 0) maxImgH * ref.w.toFloat() / ref.h else h * 4f / 3f
                }
                if (w > width) w = width
                lines.add(PageLine("", 0f, true, pi, ref, w, h))
                return@forEachIndexed
            }
            var start = 0
            var indent = firstLineIndent
            var isFirst = true
            while (start < p.length) {
                val maxW = (width - indent).coerceAtLeast(paint.textSize * 1.2f)
                var n = paint.breakText(p, start, p.length, true, maxW, measured)
                if (n <= 0) n = 1
                var end = start + n
                if (end < p.length && p[end] == ' ') end++     // 行首不留空格
                lines.add(PageLine(p.substring(start, end), indent, isFirst, pi))
                start = end
                indent = 0f
                isFirst = false
            }
        }
        return lines
    }
}

object PagePacker {
    /**
     * 把行装进页（纯函数，不依赖 Android，可 JVM 单测）。
     * 段落之间额外留 paraSpacing；页首行不补间距，保证每页刚好放下整数行。
     * 插图行按它自己的高度算（见 PageLine.height）。
     */
    fun pack(lines: List<PageLine>, pageHeight: Float, lineHeight: Float, paraSpacing: Float): List<Page> {
        if (lines.isEmpty()) return listOf(Page(0, 0))
        val pages = ArrayList<Page>()
        var start = 0
        var y = 0f
        for (i in lines.indices) {
            val gap = if (i > start && lines[i].paraStart) paraSpacing else 0f
            val lh = lines[i].height(lineHeight)
            if (i > start && y + lh + gap > pageHeight) {
                pages.add(Page(start, i))
                start = i
                y = 0f
                y += lh
                continue
            }
            y += lh + gap
        }
        if (start < lines.size) pages.add(Page(start, lines.size))
        return pages
    }
}

/** 供绘制使用的画笔 */
fun readerPaint(sizePx: Float, color: Int): TextPaint =
    TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = sizePx
        this.color = color
    }
