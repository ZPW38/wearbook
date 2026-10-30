package com.webook.watch

import com.webook.watch.data.Chapter
import com.webook.watch.data.Progress
import com.webook.watch.data.BookMerge
import com.webook.watch.data.ImageRef
import com.webook.watch.data.BookScanner
import com.webook.watch.data.UrlImport
import com.webook.watch.data.chapterFitsBook
import com.webook.watch.data.collectImages
import com.webook.watch.data.imageParagraph
import com.webook.watch.data.paraFitsChapter
import java.io.File
import com.webook.watch.data.parseImagePara
import com.webook.watch.parser.BookParser
import com.webook.watch.parser.MobiParser
import com.webook.watch.parser.PalmDoc
import com.webook.watch.text.PageLine
import com.webook.watch.ui.ComicDetect
import com.webook.watch.ui.comicPages
import com.webook.watch.ui.findLineFor
import com.webook.watch.text.PagePacker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
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

    /* ---------- 全书插图序列：查看器多图切换的数据源（1.0.24） ---------- */

    @Test
    fun collectImages_keepsReadingOrder() {
        val chapters = listOf(
            Chapter("一", listOf("正文", imageParagraph("a.jpg", 10, 10), "正文")),
            Chapter("二", listOf(imageParagraph("b.jpg", 10, 10), imageParagraph("c.jpg", 10, 10)))
        )
        assertEquals(
            listOf("a.jpg", "b.jpg", "c.jpg"),
            collectImages(chapters).map { it.path }
        )
    }

    @Test
    fun collectImages_dedupesSameFile() {
        // 同一张图被两章引用（封面 / 插图目录 / 复用）时，只在第一次出现的位置留一个，
        // 否则用户在查看器里来回切会看到"同一张图连着两遍"
        val chapters = listOf(
            Chapter("一", listOf(imageParagraph("a.jpg", 1, 1), imageParagraph("b.jpg", 1, 1))),
            Chapter("二", listOf(imageParagraph("a.jpg", 1, 1)))
        )
        assertEquals(listOf("a.jpg", "b.jpg"), collectImages(chapters).map { it.path })
    }

    @Test
    fun collectImages_emptyForTextOnlyBook() {
        assertTrue(collectImages(listOf(Chapter("一", listOf("只有文字")))).isEmpty())
        assertTrue(collectImages(emptyList()).isEmpty())
    }

    /* ---------- 存档自检：段落号得属于它记录的那一章（1.0.26） ---------- */

    private fun sampleChapters() = listOf(
        Chapter("一", List(10) { "甲$it" }),
        Chapter("二", List(3) { "乙$it" })
    )

    @Test
    fun paraFits_acceptsValidRange() {
        val cs = sampleChapters()
        assertTrue(paraFitsChapter(cs, 0, 0))
        assertTrue(paraFitsChapter(cs, 0, 9))     // 刚好卡在边界上
        assertTrue(paraFitsChapter(cs, 1, 2))
    }

    @Test
    fun paraFits_rejectsStaleParaFromAnotherChapter() {
        val cs = sampleChapters()
        // 第二章只有 3 段：7 是第一章的段落号 —— 正是「新章号 + 旧章段落号」那种脏档案
        assertTrue(!paraFitsChapter(cs, 1, 7))
        assertTrue(!paraFitsChapter(cs, 1, -1))     // 老版本记录：没有精确位置
        assertTrue(!paraFitsChapter(cs, 9, 0))      // 章号越界（书被重导过？）
        assertTrue(!paraFitsChapter(emptyList(), 0, 0))
    }

    @Test
    fun chapterFits_rejectsOutOfRange() {
        val cs = sampleChapters()
        assertTrue(chapterFitsBook(cs, 0))
        assertTrue(chapterFitsBook(cs, 1))
        assertTrue(!chapterFitsBook(cs, 2))
        assertTrue(!chapterFitsBook(cs, -1))
        assertTrue(!chapterFitsBook(emptyList(), 0))
    }

    /* ---------- 链接导入：只认直链，其余一律不放行（1.0.28） ---------- */

    @Test
    fun url_normalize_addsSchemeAndRejectsJunk() {
        assertEquals("https://example.com/a.epub", UrlImport.normalize("example.com/a.epub"))
        assertEquals("http://example.com/a.epub", UrlImport.normalize("HTTP://example.com/a.epub"))
        assertEquals(null, UrlImport.normalize(""))
        assertEquals("   ", UrlImport.normalize("   ").orEmpty().ifEmpty { "   " })
        // 非 http(s) 协议一律拒绝 —— 这是"只做通用直链下载"这条边界的代码化
        assertEquals(null, UrlImport.normalize("ftp://example.com/a.epub"))
        assertEquals(null, UrlImport.normalize("file:///sdcard/a.epub"))
        // 粘进来一段带空格的文字，不该白等一次超时
        assertEquals(null, UrlImport.normalize("这本书 http://a.com/b.epub"))
    }

    @Test
    fun url_guessName_fromPath() {
        assertEquals("book.epub", UrlImport.guessName("https://x.com/files/book.epub"))
        assertEquals("中文名.epub", UrlImport.guessName("https://x.com/files/%E4%B8%AD%E6%96%87%E5%90%8D.epub"))
        assertEquals("", UrlImport.guessName("https://x.com/"))
        // ?id=1 这种查询串不在文件名里：拿到的 "download" 没有扩展名，
        // 下载时会用文件头嗅探补上（见 UrlImport.sniff）
        assertEquals("download", UrlImport.guessName("https://x.com/download?id=1"))
    }

    @Test
    fun sniff_identifiesBookFormats() {
        assertEquals(".epub", UrlImport.sniff(byteArrayOf(0x50, 0x4B.toByte(), 0x03, 0x04)))
        val mobi = ByteArray(80)
        "BOOKMOBI".toByteArray(Charsets.US_ASCII).copyInto(mobi, 60)
        assertEquals(".mobi", UrlImport.sniff(mobi))
        assertEquals(".html", UrlImport.sniff("<!DOCTYPE html><html>".toByteArray()))
        assertEquals("", UrlImport.sniff("第一章\n这里是正文".toByteArray()))
    }

    /* ---------- 目录扫描：只收成品的书，靠「路径|字节数」记账（1.0.28） ---------- */

    @Test
    fun scanner_collectsOnlyFinishedBooks() {
        val d = File(System.getProperty("java.io.tmpdir"), "scanner_test_${System.nanoTime()}")
        d.mkdirs()
        try {
            File(d, "a.epub").writeText("PK")
            File(d, "b.txt").writeText("正文")
            File(d, "c.part").writeText("半个文件")        // 下载没下完
            File(d, "d.crdownload").writeText("浏览器临时")
            File(d, "e.pdf").writeText("不支持的格式")
            File(d, "empty.txt")                          // 0 字节
            File(d, "sub").mkdirs()

            val names = BookScanner.booksIn(d).map { it.name }
            assertTrue("应只收到 a.epub 和 b.txt，实际 $names", names.containsAll(listOf("a.epub", "b.txt")))
            assertTrue("半成品不该收：$names", names.none { it.endsWith(".part") || it.endsWith(".crdownload") })
            assertTrue("pdf 不支持：$names", names.none { it.endsWith(".pdf") })
            assertTrue("0 字节不该收：$names", !names.contains("empty.txt"))
            assertTrue("子目录不该出现：$names", !names.contains("sub"))

            // 记账键：同一个文件大小变了就是"换了内容"，该当成新书再收一次
            val f = File(d, "b.txt")
            val k1 = BookScanner.keyOf(f)
            f.writeText("正文被换掉了")
            assertTrue("大小变了记账键必须变", k1 != BookScanner.keyOf(f))
        } finally {
            d.deleteRecursively()
        }
    }

    @Test
    fun scanner_isBookName() {
        assertTrue(BookScanner.isBookName("x.epub"))
        assertTrue(BookScanner.isBookName("X.MOBI"))
        assertTrue(BookScanner.isBookName("y.html"))
        assertTrue(!BookScanner.isBookName("z.pdf"))
        assertTrue(!BookScanner.isBookName("noext"))
    }

    /* ---------- 同一本书的更新：认书 / 比对 / 迁进度（1.6.0） ---------- */

    private fun ch(t: String, vararg ps: String) = Chapter(t, ps.toList())

    @Test
    fun `书名归一化去掉版本噪音`() {
        assertEquals("长安异闻录", BookMerge.normTitle("《长安异闻录》（全本精校版）.txt"))
        assertEquals("长安异闻录", BookMerge.normTitle("长安异闻录【完结】"))
        assertEquals("mobydick", BookMerge.normTitle("Moby Dick.epub"))
        assertEquals("长安异闻录", BookMerge.normTitle(" 长安 异闻录 "))
    }

    @Test
    fun `章节标题归一化去掉序号前缀`() {
        assertEquals("夜半钟声", BookMerge.normChapter("第一章 夜半钟声"))
        assertEquals("夜半钟声", BookMerge.normChapter("第12章 夜半钟声"))
        assertEquals("夜半钟声", BookMerge.normChapter("12. 夜半钟声"))
        assertEquals("夜半钟声", BookMerge.normChapter("Chapter 12: 夜半钟声"))
        // 去掉序号后仍保留章节名本身
        assertNotEquals(BookMerge.normChapter("第一章 夜半钟声"), BookMerge.normChapter("第二章 西市旧事"))
    }

    @Test
    fun `同名书且章节前缀一致 判为同一本且是纯追加`() {
        val old = listOf(ch("第一章 甲", "a"), ch("第二章 乙", "b"))
        val new = old + listOf(ch("第三章 丙", "c"), ch("第四章 丁", "d"))
        val d = BookMerge.diff("某书", old, "某书", new)
        assertTrue(d.sameBook)
        assertTrue(d.isPureAppend)
        assertEquals(2, d.added)
        assertEquals(0, d.removed)
        assertEquals(0, d.rewritten)
    }

    @Test
    fun `名字不同但章节高度重合 也算同一本`() {
        val old = listOf(ch("第一章 甲", "a"), ch("第二章 乙", "b"), ch("第三章 丙", "c"))
        val new = listOf(ch("第一章 甲", "a"), ch("第二章 乙", "b"), ch("第三章 丙", "c"))
        val d = BookMerge.diff("旧名字", old, "新名字", new)
        assertTrue("章节完全一致应判为同一本", d.sameBook)
    }

    @Test
    fun `毫不相干的两本书不会被误判`() {
        val old = listOf(ch("第一章 甲", "a"), ch("第二章 乙", "b"))
        val other = listOf(ch("序章 楔子", "x"), ch("第一章 别的东西", "y"))
        val d = BookMerge.diff("某书", old, "另一本", other)
        assertFalse(d.sameBook)
    }

    @Test
    fun `正文被改写要能识别出来`() {
        val old = listOf(ch("第一章 甲", "原内容"))
        val new = listOf(ch("第一章 甲", "改过的内容"))
        val d = BookMerge.diff("某书", old, "某书", new)
        assertTrue(d.sameBook)
        assertEquals(1, d.rewritten)
        assertFalse(d.isPureAppend)
    }

    @Test
    fun `进度按章节名迁移到新版本`() {
        val old = listOf(ch("第一章 甲", "a"), ch("第二章 乙", "b1", "b2", "b3"))
        // 新版在最前面插了一个序章，章号整体后移
        val new = listOf(ch("序章 引子", "p"), ch("第一章 甲", "a"), ch("第二章 乙", "b1", "b2", "b3"))
        val oldP = Progress(chapter = 1, para = 2, charStart = 5, frac = 0.75f)
        val moved = BookMerge.migrate(oldP, old, new)
        assertEquals("应落在新的第二章（索引 2）", 2, moved.chapter)
        assertEquals(2, moved.para)
        assertEquals(5, moved.charStart)
    }

    @Test
    fun `章节被删掉时按相对位置放回 不会乱跳`() {
        val old = (1..10).map { ch("第${it}章 内容$it", "x") }
        val new = (1..5).map { ch("第${it}章 内容$it", "x") }   // 后五章没了
        val oldP = Progress(chapter = 9, para = 3, charStart = 0, frac = 0.95f)
        val moved = BookMerge.migrate(oldP, old, new)
        assertTrue("索引必须落在新书范围内", moved.chapter in 0..4)
        assertEquals("找不到同章时段落号归零，宁可让读者往前翻", 0, moved.para)
    }

    @Test
    fun `新章节更短时段落号要收进范围`() {
        val old = listOf(ch("第一章 甲", *Array(30) { "段$it" }))
        val new = listOf(ch("第一章 甲", "只剩一段"))
        val oldP = Progress(chapter = 0, para = 25, charStart = 3)
        val moved = BookMerge.migrate(oldP, old, new)
        assertEquals(0, moved.chapter)
        assertEquals("段落号不能超过新章节的段数", 0, moved.para)
    }


    /* ---------- 漫画模式：展平 / 自动判定（1.7.0） ---------- */

    private fun imgPara(path: String) = imageParagraph(path, 1000, 1500)

    @Test
    fun `整本书的插图按章节顺序摊平成一页页`() {
        val chapters = listOf(
            Chapter("第一章", listOf("文字", imgPara("a.jpeg"))),
            Chapter("第二章", listOf(imgPara("b.jpeg"), "文字", imgPara("c.jpeg")))
        )
        val pages = comicPages(chapters)
        assertEquals(3, pages.size)
        assertEquals(0, pages[0].chapter); assertEquals("a.jpeg", pages[0].ref.path)
        assertEquals(1, pages[1].chapter); assertEquals("b.jpeg", pages[1].ref.path)
        assertEquals(2, pages[2].para);    assertEquals("c.jpeg", pages[2].ref.path)
    }

    @Test
    fun `纯图画的章节被判为漫画`() {
        // 每章 3 张整页图、几乎没有字
        val chapters = (1..3).map { i ->
            Chapter("第${i}话", listOf(imgPara("p$i-1.jpeg"), imgPara("p$i-2.jpeg"), imgPara("p$i-3.jpeg")))
        }
        assertTrue(ComicDetect.looksLikeComic(chapters))
    }

    @Test
    fun `纯文字的章节不是漫画`() {
        val chapters = (1..3).map { i ->
            Chapter("第${i}章", (1..20).map { "这是第${it}段正文内容，比较长的一段话。" })
        }
        assertFalse(ComicDetect.looksLikeComic(chapters))
    }

    @Test
    fun `一章一张整页图加大量正文 不自动进漫画模式`() {
        // 真实场景：《让你改装机娘》那种书 —— 每章 1 张整页图 + 两千多字
        val chapters = (1..5).map { i ->
            Chapter("第${i}章", listOf(imgPara("c$i.jpeg")) + (1..25).map { "正文段落$it，大约一百字左右的篇幅内容。" })
        }
        assertFalse("自动判定不该抢走这类书（正文才是主体）", ComicDetect.looksLikeComic(chapters))
        assertTrue("但要能识别出它确实有图，好给手动入口", ComicDetect.hasImages(chapters))
    }

    @Test
    fun `没有图的书不给漫画入口`() {
        val chapters = listOf(Chapter("第一章", listOf("纯文字")))
        assertFalse(ComicDetect.hasImages(chapters))
    }

}
