package com.webook.watch

import com.webook.watch.parser.PalmDoc
import com.webook.watch.text.PageLine
import com.webook.watch.text.PagePacker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯逻辑单测：分页装配 + PalmDOC 解压（不依赖 Android，JVM 直接跑） */
class CoreLogicTest {

    private fun lines(n: Int, paraEvery: Int = Int.MAX_VALUE): List<PageLine> =
        (0 until n).map { i -> PageLine("行$i", 0f, i % paraEvery == 0) }

    @Test
    fun `每页都不超过可用高度`() {
        val ls = lines(40)
        val pages = PagePacker.pack(ls, pageHeight = 100f, lineHeight = 30f, paraSpacing = 18f)
        for (p in pages) {
            var h = 0f
            for (i in p.start until p.end) {
                if (i > p.start && ls[i].paraStart) h += 18f
                h += 30f
            }
            assertTrue("页面高度 $h 超出 100", h <= 100f)
        }
    }

    @Test
    fun `所有行恰好被覆盖一次`() {
        val ls = lines(37)
        val pages = PagePacker.pack(ls, 100f, 30f, 18f)
        var covered = 0
        var prevEnd = 0
        for (p in pages) {
            assertEquals("页与页之间必须连续", prevEnd, p.start)
            covered += p.count
            prevEnd = p.end
        }
        assertEquals(37, covered)
        assertEquals(37, prevEnd)
    }

    @Test
    fun `段间距会被计入`() {
        val ls = lines(10, paraEvery = 1)          // 每行都是段首
        val pages = PagePacker.pack(ls, 100f, 30f, 40f)
        // 30 + (30+40) = 100 恰好放 2 行
        assertTrue("第一行页应放 2 行，实际 ${pages.first().count}", pages.first().count == 2)
    }

    @Test
    fun `空输入不崩`() {
        val pages = PagePacker.pack(emptyList(), 100f, 30f, 18f)
        assertEquals(1, pages.size)
    }

    @Test
    fun `PalmDOC 字面量解压`() {
        val out = PalmDoc.decompress(byteArrayOf(0x03, 65, 66, 67))
        assertEquals("ABC", String(out, Charsets.US_ASCII))
    }

    @Test
    fun `PalmDOC 回溯拷贝解压`() {
        // 0x02 后跟 "AB"，(0x80,0x10) = 距离 2、长度 3 → "ABABA"
        val out = PalmDoc.decompress(byteArrayOf(0x02, 65, 66, 0x80.toByte(), 0x10))
        assertEquals("ABABA", String(out, Charsets.US_ASCII))
    }
}
