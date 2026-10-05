package com.webook.watch

import com.webook.watch.data.ImageRef
import com.webook.watch.data.imageParagraph
import com.webook.watch.data.parseImagePara
import com.webook.watch.parser.BookParser
import com.webook.watch.parser.MobiParser
import com.webook.watch.parser.PalmDoc
import com.webook.watch.text.PageLine
import com.webook.watch.ui.findLineFor
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
            PageLine("", 0f, true, 1, 0, ImageRef("a.jpg", 800, 600), 100f, 90f),
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
            PageLine("", 0f, true, 0, 0, ImageRef("a.jpg", 800, 600), 100f, 90f),
            PageLine("", 0f, true, 1, 0, ImageRef("b.jpg", 800, 600), 100f, 90f),
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

    /* ---------- MOBI 章节切分：目录锚点优先，无目录退回分页符（1.0.20） ---------- */

    private fun be(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    /** 造一个最小的、compression=1 的合法 MOBI，正文就一段 */
    private fun buildMobi(body: String): ByteArray {
        val text = body.toByteArray(Charsets.UTF_8)
        val rec0 = ByteArray(40)
        rec0[1] = 1                                   // compression = 1（不压缩）
        be(text.size).copyInto(rec0, 4)                 // textLength
        rec0[9] = 1                                    // recordCount = 1（正文记录数）
        rec0[10] = 0x10                               // recordSize = 4096
        "MOBI".toByteArray().copyInto(rec0, 16)
        be(24).copyInto(rec0, 20)                      // headerLength
        be(2).copyInto(rec0, 24)                       // mobiType
        be(65001).copyInto(rec0, 28)                   // textEncoding = UTF-8
        val db = ByteArray(78 + 16)
        "BOOKMOBI".toByteArray().copyInto(db, 60)
        db[77] = 2                                     // numRecords = 2
        val off0 = db.size
        be(off0).copyInto(db, 78)
        be(off0 + rec0.size).copyInto(db, 86)
        return db + rec0 + text
    }

    /** filepos 是字节偏移，中文一字 3 字节，所以不能用 String.length */
    private fun blen(s: String) = s.toByteArray(Charsets.UTF_8).size

    @Test
    fun mobi_splitsChaptersByTocAnchors() {
        // 真实结构：目录区的锚点 + 依次排开的正文，filepos 指向该章正文的起点
        val c1 = "甲".repeat(400)
        val c2 = "乙".repeat(400)
        val prefix = "<html><body><a filepos=0000000000>第一章</a>"
        val mid = "<p>$c1</p><a filepos=0000000000>第二章</a>"
        val a1 = blen(prefix)
        val a2 = a1 + blen(mid)
        val body = (prefix + mid + "<p>$c2</p></body></html>")
            .replaceFirst("0000000000", a1.toString().padStart(10, '0'))
            .replaceFirst("0000000000", a2.toString().padStart(10, '0'))
        val r = MobiParser.parse(buildMobi(body), "t.mobi")
        assertEquals("应按两个锚点切出两章", 2, r.chapters.size)
        assertEquals("第一章", r.chapters[0].title)
        assertEquals("第二章", r.chapters[1].title)
    }

    @Test
    fun mobi_dropsJunkTocTitles() {
        val c1 = "甲".repeat(400)
        val c2 = "乙".repeat(400)
        val c3 = "丙".repeat(400)
        val prefix = "<html><body>"
        val seg1 = "<a filepos=0000000000>第一章</a><p>$c1</p>"
        val seg2 = "<a filepos=0000000000>1.</a><a filepos=0000000000>第二章</a><p>$c2</p>"
        val seg3 = "<a filepos=0000000000>《某书》（点击链接跳转详情页面）</a><p>$c3</p>"
        val a1 = blen(prefix)
        val a2 = a1 + blen(seg1)
        val a3 = a2 + blen(seg2)
        val a4 = a3 + blen(seg3)
        val body = (prefix + seg1 + seg2 + seg3 + "</body></html>")
            .replaceFirst("0000000000", a1.toString().padStart(10, '0'))
            .replaceFirst("0000000000", a2.toString().padStart(10, '0'))
            .replaceFirst("0000000000", a3.toString().padStart(10, '0'))
            .replaceFirst("0000000000", a4.toString().padStart(10, '0'))
        val r = MobiParser.parse(buildMobi(body), "t.mobi")
        val titles = r.chapters.map { it.title }
        assertTrue("纯序号标题应被丢弃: $titles", titles.none { it == "1." })
        assertTrue("推广链接标题应被丢弃: $titles", titles.none { it.contains("点击链接") })
        assertTrue("正常标题要保留: $titles", titles.contains("第一章"))
        assertTrue("正常标题要保留: $titles", titles.contains("第二章"))
    }

    @Test
    fun mobi_fallsBackToPageBreakWhenNoToc() {
        val body = "<html><body><p>" + "甲".repeat(120) +
            "</p><mbp:pagebreak/><p>" + "乙".repeat(120) + "</p></body></html>"
        val r = MobiParser.parse(buildMobi(body), "t.mobi")
        assertTrue("没有目录时应退回分页符切分，实际 ${r.chapters.size} 章", r.chapters.size >= 2)
    }

    /* ---------- 精确续读：段落 + 段内偏移 -> 行（1.0.21） ---------- */

    /** 造一个「段落 -> 若干行」的行表，每行的 charStart 是它在段落内的偏移 */
    private fun paraLines(vararg paraLens: Int): List<PageLine> {
        val out = ArrayList<PageLine>()
        paraLens.forEachIndexed { pi, len ->
            var start = 0
            while (start < len) {
                val n = minOf(10, len - start)
                out.add(
                    PageLine(
                        text = "x".repeat(n), indent = 0f, paraStart = start == 0,
                        paraIndex = pi, charStart = start
                    )
                )
                start += n
            }
        }
        return out
    }

    @Test
    fun findLine_locateByParagraphAndOffset() {
        val ls = paraLines(25, 25)          // 每段 25 字 -> 每段 3 行（10/10/5）
        // 第 1 段第 0 字 -> 第 3 行（第 0 段占 0..2）
        assertEquals(3, findLineFor(ls, 1, 0))
        // 第 1 段第 12 字 -> 第 4 行（10..19 这一行）
        assertEquals(4, findLineFor(ls, 1, 12))
        // 第 0 段第 20 字 -> 第 2 行（20..24 这一行）
        assertEquals(2, findLineFor(ls, 0, 20))
    }

    @Test
    fun findLine_survivesReflow() {
        // 换字号后每行装的字数变了（每行 7 字），段内偏移依然能对上
        val ls = ArrayList<PageLine>()
        ls.add(PageLine("x".repeat(7), 0f, true, 0, 0))
        ls.add(PageLine("x".repeat(7), 0f, false, 0, 7))
        ls.add(PageLine("x".repeat(6), 0f, false, 0, 14))
        // 存下来的是「第 0 段第 12 字」——重排后落在 7..13 这一行
        assertEquals(1, findLineFor(ls, 0, 12))
        // 第 20 字 -> 14..19 这一行
        assertEquals(2, findLineFor(ls, 0, 20))
    }

    @Test
    fun findLine_fallsBackWhenOffsetBeyondParagraph() {
        val ls = paraLines(25)
        // 字号变小、段落变短了：偏移超出该段 -> 退到该段最后一行，而不是跑飞
        assertEquals(2, findLineFor(ls, 0, 999))
    }

    @Test
    fun findLine_returnsMinusOneForMissingParagraph() {
        val ls = paraLines(25)
        assertEquals(-1, findLineFor(ls, 5, 0))
        assertEquals(-1, findLineFor(emptyList(), 0, 0))
        assertEquals(-1, findLineFor(ls, -1, 0))
    }
}
