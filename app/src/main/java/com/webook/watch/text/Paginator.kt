package com.webook.watch.text

import android.graphics.Paint
import android.text.TextPaint

/** 一行文本 */
data class PageLine(
    val text: String,
    val indent: Float,      // 该行左边距（px）
    val paraStart: Boolean, // 是否为段落首行
    val paraIndex: Int = 0  // 属于第几个段落（目录与搜索结果定位用）
)

/** 一页：[start, end) 行区间 */
data class Page(val start: Int, val end: Int) {
    val count: Int get() = end - start
}

object LineBreaker {
    /**
     * 把段落按可用宽度断行。用 TextPaint.breakText，与最终绘制用的是同一支画笔，
     * 所以排版结果和屏幕上的实际渲染完全一致，不会出现半截字。
     */
    fun layout(paragraphs: List<String>, paint: TextPaint, width: Float, firstLineIndent: Float): List<PageLine> {
        val lines = ArrayList<PageLine>()
        val measured = FloatArray(1)
        paragraphs.forEachIndexed { pi, p ->
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
     */
    fun pack(lines: List<PageLine>, pageHeight: Float, lineHeight: Float, paraSpacing: Float): List<Page> {
        if (lines.isEmpty()) return listOf(Page(0, 0))
        val pages = ArrayList<Page>()
        var start = 0
        var y = 0f
        for (i in lines.indices) {
            val gap = if (i > start && lines[i].paraStart) paraSpacing else 0f
            if (i > start && y + lineHeight + gap > pageHeight) {
                pages.add(Page(start, i))
                start = i
                y = 0f
                y += lineHeight
                continue
            }
            y += lineHeight + gap
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
