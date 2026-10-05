package com.webook.watch

import com.webook.watch.data.ImageRef
import com.webook.watch.data.imageParagraph
import com.webook.watch.data.parseImagePara
import com.webook.watch.parser.BookParser
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

    /* ---------------- 插图行（1.0.18） ---------------- */

    @Test
    fun `插图段落能被识别`() {
        val p = imageParagraph("OEBPS/images/a.jpg", 800, 600)
        val ref = parseImagePara(p)!!
        assertEquals("OEBPS/images/a.jpg", ref.path)
        assertEquals(800, ref.w)
        assertEquals(600, ref.h)
        // 普通文字段落不该被误判成图片
        assertEquals(null, parseImagePara("普通文字段落"))
        assertEquals(null, parseImagePara(""))
    }

    @Test
    fun `插图行按自己的高度参与分页`() {
        // 页高 100、行高 30：一张 90 高的图 + 上下各一行文字 → 恰好切成 3 页
        val ls = listOf(
            PageLine("文字", 0f, true),
            PageLine("", 0f, true, 1, ImageRef("a.jpg", 800, 600), 100f, 90f),
            PageLine("文字2", 0f, true, 2)
        )
        val pages = PagePacker.pack(ls, pageHeight = 100f, lineHeight = 30f, paraSpacing = 0f)
        assertEquals(3, pages.size)
        assertEquals("中间那页只放那张图", 1, pages[1].count)
        assertEquals(1, pages[1].start)
    }

    @Test
    fun `插图不会把页面撑破`() {
        val ls = listOf(
            PageLine("", 0f, true, 0, ImageRef("a.jpg", 800, 600), 100f, 90f),
            PageLine("", 0f, true, 1, ImageRef("b.jpg", 800, 600), 100f, 90f),
            PageLine("文字", 0f, false, 2)
        )
        val pages = PagePacker.pack(ls, 100f, 30f, 0f)
        for (p in pages) {
            var h = 0f
            for (i in p.start until p.end) h += ls[i].height(30f)
            assertTrue("页面高度 $h 超出 100", h <= 100f)
        }
    }

    /* ---------- 编码识别：坏字节不该拖垮整本书（1.0.19） ---------- */

    @Test
    fun decode_normalUtf8KeepsChinese() {
        val raw = "科幻小说里的中文句子，读客经典文库。".repeat(200)
        assertEquals(raw, BookParser.decode(raw.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun decode_gbkStillWorks() {
        val raw = "这是一本纯中文的电子书正文，应该用 GBK 解码。".repeat(200)
        assertEquals(raw, BookParser.decode(raw.toByteArray(charset("GBK"))))
    }

    @Test
    fun decode_preferCharsetWins() {
        val raw = "三体全集".repeat(50)
        val bytes = raw.toByteArray(Charsets.UTF_8)
        assertEquals(raw, BookParser.decode(bytes, "UTF-8"))
    }

    /** 三体的 mobi 里混进了约 0.07% 的坏字节，旧逻辑会整本退回 GBK 变成「鐩綍褰」 */
    @Test
    fun decode_brokenUtf8StaysChineseInsteadOfGbk() {
        val good = "这是《三体》的正文，读客经典文库出版，写得非常好。".repeat(400)
        val bytes = good.toByteArray(Charsets.UTF_8).toMutableList()
        // 每 400 字节插一个非法字节（模拟文件损坏）
        var i = 10
        while (i < bytes.size) {
            bytes[i] = 0xFF.toByte()
            i += 400
        }
        val out = BookParser.decode(bytes.toByteArray())
        val cn = out.count { it.code in 0x4E00..0x9FFF }
        assertTrue("坏字节不该让整本变 GBK 乱码，实际中文字数=$cn", cn > out.length / 2)
        assertTrue("不该出现 GBK 乱码特征", !out.contains("鐩"))
    }
}
