package com.webook.watch.parser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Xml
import com.webook.watch.data.Chapter
import com.webook.watch.data.IMG_MARK
import com.webook.watch.data.parseImagePara
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

data class ParsedBook(
    val title: String,
    val author: String,
    val fmt: String,
    val chapters: List<Chapter>,
    val cover: Bitmap? = null
)

val SUPPORTED_EXT = listOf("txt", "epub", "mobi", "azw", "azw3", "html", "htm")

object BookParser {

    /**
     * 非 epub 格式要把整份文件读进内存（txt/mobi/html 都是全量解码），
     * 手表堆往往只有 96MB，所以调用方要给体积上限。
     * 16MB 的纯文本约等于 600 万汉字，正常小说远用不到；再大就让它拆包。
     */
    const val MAX_IN_MEMORY_BYTES = 16L * 1024 * 1024

    /** 单个章节条目超过这个大小就跳过（正常 XHTML 章节几十 KB，超大的多半是别的东西） */
    private const val MAX_CHAPTER_ENTRY = 4L * 1024 * 1024

    /** 全部正文的字符数上限（8M 字符 ≈ 16MB），防止超多章节的书把内存吃光 */
    private const val MAX_TOTAL_CHARS = 8 * 1024 * 1024

    /** 封面图的原始字节上限 */
    private const val MAX_COVER_ENTRY = 6L * 1024 * 1024

    /** 正文插图单张的字节上限（只读文件头拿尺寸，不解码，所以可以放宽） */
    private const val MAX_IMAGE_ENTRY = 8L * 1024 * 1024

    /**
     * 按扩展名分派。
     * ⚠️ epub 必须**提前返回**：它用 ZipFile 逐条读取，绝不需要整份文件；
     * 之前这里先执行了 `bytes ?: file.readBytes()`，结果一本 171MB 的 epub
     * 在 96MB 堆的手表上直接 OutOfMemoryError 闪退（用户实测）。
     */
    fun parse(file: File, name: String, bytes: ByteArray? = null): ParsedBook {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "epub") return epubToBook(file, name)
        val data = bytes ?: file.readBytes()
        return when (ext) {
            "txt" -> txtToBook(data, name)
            "mobi", "azw", "azw3" -> MobiParser.parse(data, name)
            "html", "htm" -> htmlToBook(data, name)
            else -> throw IllegalArgumentException("不支持的格式：.$ext")
        }
    }

    /* ---------------- 编码识别：严格 UTF-8 优先，失败按「可读性」打分选容错解码 ---------------- */
    /**
     * @param prefer 格式自述的编码名（如 MOBI 头里的 textEncoding），有就优先相信它
     *
     * 旧实现是「非 UTF-8 就整本退回 GBK」——3MB 的书只要混进 1 个坏字节，
     * 整篇就变成「鐩綍褰」这种 GBK 乱码，看着像全坏，其实 99% 的字都是好的。
     * 现在改成：先严格解码；实在有坏字节，就让 UTF-8 与 GBK 各自容错解一遍，
     * 按「中文占比 - 坏字符占比×3」打分，谁更像正常中文就用谁。
     */
    fun decode(bytes: ByteArray, prefer: String? = null): String {
        if (prefer != null) {
            val s = runCatching { String(bytes, Charset.forName(prefer)) }.getOrNull()
            if (s != null && badRatio(s) < 0.05f) return s
        }
        try {
            val dec = Charset.forName("UTF-8").newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val s = dec.decode(ByteBuffer.wrap(bytes)).toString()
            if (!s.contains('\uFFFD')) return s
        } catch (e: Exception) { /* 下面用打分的方式兜底 */ }
        val u8 = String(bytes, Charsets.UTF_8)
        val gbk = runCatching { String(bytes, Charset.forName("GBK")) }.getOrNull() ?: return u8
        return if (legibility(u8) >= legibility(gbk)) u8 else gbk
    }

    /** 取样 2 万字算「像不像正常中文」，够用又快 */
    private fun legibility(s: String): Float {
        val n = minOf(s.length, 20000)
        if (n == 0) return 0f
        var cn = 0
        var bad = 0
        for (i in 0 until n) {
            val c = s[i]
            if (c == '\uFFFD') bad++
            else if (c.code in 0x4E00..0x9FFF) cn++
        }
        return cn.toFloat() / n - (bad.toFloat() / n) * 3f
    }

    private fun badRatio(s: String): Float {
        val n = minOf(s.length, 20000)
        if (n == 0) return 0f
        var bad = 0
        for (i in 0 until n) if (s[i] == '\uFFFD') bad++
        return bad.toFloat() / n
    }

    /* ---------------- HTML -> 段落 ---------------- */
    /**
     * @param imgResolver 传入时，会把 `<img src="...">` 变成一个独立的「插图段落」
     *        （应返回 "zip内路径\u0000宽\u0000高"；返回 null 表示这张图跳过）。
     *        传 null 就只取文字（TXT/HTML 文件用）。
     */
    fun htmlToParagraphs(html: String, imgResolver: ((String) -> String?)? = null): List<String> {
        var s = html
        s = Regex("(?is)<(script|style|head|nav)[^>]*>.*?</\\1>").replace(s, " ")
        if (imgResolver != null) {
            // 先把图片换成标记段落（前后补空行，保证它自己独立成段）
            s = Regex("(?is)<img\\b[^>]*>").replace(s) { m ->
                val src = Regex("(?i)src\\s*=\\s*[\"']([^\"']+)[\"']").find(m.value)?.groupValues?.get(1)
                val payload = src?.let { imgResolver(it) }
                if (payload == null) "\n\n" else "\n\n$IMG_MARK$payload\n\n"
            }
        }
        s = Regex("(?i)</(p|div|h[1-6]|li|blockquote|tr)>").replace(s, "\n\n")
        s = Regex("(?i)<br\\s*/?>").replace(s, "\n")
        s = Regex("<[^>]+>").replace(s, "")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        // 插图段里含 \u0000，trim 动不到它；按行切分后它自己就是一段
        return s.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
    }

    /* ---------------- TXT ---------------- */
    fun txtToBook(bytes: ByteArray, name: String): ParsedBook {
        val title = name.substringBeforeLast('.')
        val text = decode(bytes).replace("\r\n", "\n").replace("\r", "\n")
        var paras = text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotBlank() }
        if (paras.size < 4) paras = text.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        return ParsedBook(title, "", "txt", txtChapters(paras, title))
    }

    /* ---------------- TXT 分章 ---------------- */

    /** 单章超过这个字数就再切一刀（防"整本没分章 / 标题判漏"时一章几万字排不动） */
    private const val CHAPTER_SOFT_LIMIT = 12000

    /** 中文章节头：第一章 / 第 12 节 / 第三回 / 卷二 … */
    private val TXT_HEADING_CN = Regex("^第\\s*[0-9０-９一二三四五六七八九十百千万零两]{1,12}\\s*[章节回卷部篇集话]")

    /** 英文章节头：Chapter 12 */
    private val TXT_HEADING_EN = Regex("^chapter\\s*\\d{1,5}", RegexOption.IGNORE_CASE)

    /** 纯数字章节头：12. 标题 / 12、标题（只匹配序号前缀，标题部分另行校验） */
    private val TXT_HEADING_NUM = Regex("^\\d{1,4}\\s*[.、:：]")

    /**
     * 判断某一行是不是章节标题。
     *
     * 刻意保守 —— 判定错了整本书的章节结构就毁了。做法是**先匹配序号前缀、
     * 再只看前缀之后的标题部分有没有句读标点**：
     *   - 「他翻开第三章，愣住了。」→ 不以「第」开头，正则直接不匹配；
     *   - 「3、他的秘密」→ 前缀是「3、」，剩下的「他的秘密」干净 → 算标题；
     *   - 「3、他的秘密有很多，他一直没说。」→ 标题部分带逗号 → 不算，是正文。
     * ⚠️ 不能把「、」整个列进黑名单：它正是「3、标题」这种写法的分隔符本身。
     *
     * 纯 JVM 实现，不依赖 Android，可直接单测。
     */
    fun isTxtHeading(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty() || t.length > 40) return false
        val m = TXT_HEADING_CN.find(t) ?: TXT_HEADING_EN.find(t) ?: TXT_HEADING_NUM.find(t)
            ?: return false
        if (m.range.first != 0) return false
        // 只看「序号前缀」之后的部分：那才是书名/标题，带句读就不是标题
        val rest = t.substring(m.range.last + 1)
        if (rest.any { it in "。！？；，、" }) return false
        return true
    }

    /**
     * 段落内部首个换行如果是章节标题，就把它拆出来单独成段。
     *
     * 为什么需要：txtToBook 先生成段落列表，但很多 TXT 是
     * 「第一章 起点\n正文第一行\n正文第二行」这种标题**紧贴正文**的写法，
     * 按空行切分时标题会被粘在正文段落开头，行级判定就看不见它了。
     */
    private fun peelHeadings(paras: List<String>): List<String> {
        val out = ArrayList<String>(paras.size + 8)
        for (p in paras) {
            val nl = p.indexOf('\n')
            if (nl > 0 && isTxtHeading(p.substring(0, nl))) {
                out.add(p.substring(0, nl).trim())
                val rest = p.substring(nl + 1).trim()
                if (rest.isNotEmpty()) out.add(rest)
            } else {
                out.add(p)
            }
        }
        return out
    }

    /**
     * 按章节标题把 TXT 切成章；认不出就退回按长度硬切（chunk）。
     *
     * 门槛是**至少 3 个**标题 —— 只认出 1~2 个说明这不是分章文本，
     * 或者规则误伤了零散几行，这时候强行分章只会把书切得莫名其妙。
     *
     * 目录页也顺手处理了：目录里每行都是标题且彼此之间没有正文，
     * 「标题后面紧跟下一个标题」的章节（body 只有标题一行）直接跳过，
     * 于是那一串目录项全部被吞掉，正文从真正的第一章开始正常分。
     */
    fun txtChapters(paras: List<String>, title: String): List<Chapter> {
        if (paras.isEmpty()) return chunk(paras, title)
        val ps = peelHeadings(paras)
        val heads = ps.indices.filter { isTxtHeading(ps[it]) }
        if (heads.size < 3) return chunk(ps, title)

        val out = mutableListOf<Chapter>()
        // 第一个标题之前的内容（书名页 / 简介 / 前言）单独收一章
        if (heads.first() > 0) {
            val pre = ps.subList(0, heads.first())
            if (pre.any { it.isNotBlank() }) out.add(Chapter(title, pre))
        }
        heads.forEachIndexed { i, start ->
            val end = if (i + 1 < heads.size) heads[i + 1] else ps.size
            val body = ps.subList(start, end)
            // 只有标题没有正文 —— 这是目录项，或作者留的空章，跳过
            if (body.size <= 1) return@forEachIndexed
            val headTitle = body.first().trim()
            val chars = body.sumOf { it.length }
            if (chars > CHAPTER_SOFT_LIMIT) {
                // 标题判漏 / 连载没分章时，单章会特别长，按软上限再切
                val parts = chunk(body.drop(1), headTitle, CHAPTER_SOFT_LIMIT)
                parts.forEachIndexed { k, c ->
                    // 第一段用原标题，后续段沿用 chunk 给的「标题 · n」
                    out.add(if (k == 0) Chapter(headTitle, c.paragraphs) else c)
                }
            } else {
                out.add(Chapter(headTitle, body))
            }
        }
        return out.ifEmpty { chunk(ps, title) }
    }

    /* ---------------- HTML 文件 ---------------- */
    fun htmlToBook(bytes: ByteArray, name: String): ParsedBook {
        val paras = htmlToParagraphs(decode(bytes))
        if (paras.isEmpty()) throw IllegalArgumentException("该 HTML 没有可读正文")
        return ParsedBook(name.substringBeforeLast('.'), "", "html",
            listOf(Chapter(name, paras)))
    }

    /** 长文本按长度切章，避免一章过大 */
    fun chunk(paras: List<String>, title: String, limit: Int = 4200): List<Chapter> {
        if (paras.isEmpty()) return listOf(Chapter(title, listOf("（无正文内容）")))
        val out = mutableListOf<Chapter>()
        var cur = mutableListOf<String>()
        var len = 0
        for (p in paras) {
            cur.add(p); len += p.length
            if (len > limit) { out.add(Chapter("$title · ${out.size + 1}", cur)); cur = mutableListOf(); len = 0 }
        }
        if (cur.isNotEmpty()) out.add(Chapter(if (out.isEmpty()) title else "$title · ${out.size + 1}", cur))
        return out
    }

    /* ---------------- EPUB ---------------- */
    fun epubToBook(file: File, name: String): ParsedBook {
        return ZipFile(file).use { zip ->
            val cEntry = zip.getEntry("META-INF/container.xml")
                ?: throw IllegalArgumentException("不是有效的 EPUB（缺少 container.xml）")
            val rootPath = readRootfile(zip.getInputStream(cEntry))
                ?: throw IllegalArgumentException("不是有效的 EPUB")
            val base = rootPath.substringBeforeLast('/', "")
            val opfEntry = zip.getEntry(rootPath) ?: throw IllegalArgumentException("OPF 缺失")
            val opf = readOpf(zip.getInputStream(opfEntry))
            val title = opf.title.ifBlank { name.substringBeforeLast('.') }

            val chapters = mutableListOf<Chapter>()
            var totalChars = 0
            var skippedLarge = 0
            for (href in opf.spine) {
                if (totalChars > MAX_TOTAL_CHARS) break
                val chapterPath = resolve(base, href)
                val entry = zip.getEntry(chapterPath) ?: continue
                // 超大的条目直接跳过：读进来就是一次几十 MB 的分配，手表扛不住
                if (entry.size > MAX_CHAPTER_ENTRY) { skippedLarge++; continue }
                val chapterDir = chapterPath.substringBeforeLast('/', "")
                val paras = runCatching {
                    htmlToParagraphs(decode(zip.getInputStream(entry).readBytes())) { src ->
                        imagePayload(zip, chapterDir, src)
                    }
                }.getOrDefault(emptyList())
                if (paras.isNotEmpty()) {
                    totalChars += paras.sumOf { it.length }
                    // 标题取第一个「非插图」段落，否则标题会变成一串图片标记
                    chapters.add(Chapter(paras.firstOrNull { parseImagePara(it) == null }?.take(24) ?: "", paras))
                }
            }
            if (chapters.isEmpty()) {
                throw IllegalArgumentException(
                    if (skippedLarge > 0) "EPUB 里有超大章节文件（$skippedLarge 个，每个超过 4MB），手表内存放不下"
                    else "EPUB 内没有可读正文"
                )
            }
            if (totalChars > MAX_TOTAL_CHARS) {
                // 只收下前面的正文，别为了完整的书把内存撑爆
                while (chapters.size > 1 && chapters.sumOf { c -> c.paragraphs.sumOf { it.length } } > MAX_TOTAL_CHARS) {
                    chapters.removeAt(chapters.lastIndex)
                }
            }

            val cover = opf.coverHref?.let { href ->
                zip.getEntry(resolve(base, href))?.let { e -> coverBitmap(zip, e) }
            }
            ParsedBook(title, opf.author, "epub", chapters, cover)
        }
    }

    /**
     * 解封面：先只读尺寸，再按需缩放解码。
     * 一本大 EPUB 的封面可能是几千万像素的大图，直接 decodeStream 一样会 OOM。
     */
    private fun coverBitmap(zip: ZipFile, entry: java.util.zip.ZipEntry): Bitmap? = runCatching {
        if (entry.size > MAX_COVER_ENTRY || entry.size <= 0) return@runCatching null
        val raw = zip.getInputStream(entry).readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 192 && bounds.outHeight / (sample * 2) >= 288) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return@runCatching null
        scaleCover(bmp)
    }.getOrNull()

    /**
     * 把 XHTML 里的 `src` 解析成压缩包内路径，并读一下图片尺寸（只读文件头，不整张解码）。
     * 返回 "路径\u0000宽\u0000高"；不是图片/读不出尺寸就返回 null（这张图就跳过）。
     * base 必须是**该 XHTML 所在目录**——src 是相对章节文件而不是 OPF 的。
     */
    private fun imagePayload(zip: ZipFile, base: String, src: String): String? {
        val raw = src.trim()
        if (raw.isEmpty() || raw.startsWith("data:") || raw.startsWith("http:", true) || raw.startsWith("https:", true)) return null
        val path = resolve(base, raw)
        val entry = runCatching { zip.getEntry(path) }.getOrNull() ?: return null
        if (entry.size <= 0 || entry.size > MAX_IMAGE_ENTRY) return null
        var w = 0
        var h = 0
        runCatching {
            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, opt) }
            w = opt.outWidth
            h = opt.outHeight
        }
        if (w <= 0 || h <= 0) return null
        return "$path\u0000$w\u0000$h"
    }

    private fun scaleCover(src: Bitmap): Bitmap {
        val r = minOf(96f / src.width, 144f / src.height, 1f)
        val w = (src.width * r).toInt().coerceAtLeast(1)
        val h = (src.height * r).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun resolve(base: String, href: String): String {
        val raw = href.substringBefore('#')
        val parts = (if (base.isEmpty()) raw else "$base/$raw").split('/')
        val out = ArrayDeque<String>()
        for (p in parts) {
            when {
                p.isEmpty() || p == "." -> Unit
                p == ".." -> if (out.isNotEmpty()) out.removeLast()
                else -> out.addLast(p)
            }
        }
        return out.joinToString("/")
    }

    private fun readRootfile(input: InputStream): String? {
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && parser.name == "rootfile") {
                val path = parser.getAttributeValue(null, "full-path")
                if (!path.isNullOrBlank()) return path
            }
            e = parser.next()
        }
        return null
    }

    private class OpfResult(
        val title: String, val author: String, val coverHref: String?, val spine: List<String>
    )

    private fun readOpf(input: InputStream): OpfResult {
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        val manifest = HashMap<String, Triple<String, String, String>>() // id -> href, type, props
        val spineIds = mutableListOf<String>()
        var title = ""
        var author = ""
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id") ?: ""
                        val href = parser.getAttributeValue(null, "href") ?: ""
                        val type = parser.getAttributeValue(null, "media-type") ?: ""
                        val props = parser.getAttributeValue(null, "properties") ?: ""
                        manifest[id] = Triple(href, type, props)
                    }
                    "itemref" -> {
                        parser.getAttributeValue(null, "idref")?.let { spineIds.add(it) }
                    }
                    "title" -> if (title.isEmpty()) title = parser.nextText().trim()
                    "creator" -> if (author.isEmpty()) author = parser.nextText().trim()
                }
            }
            e = parser.next()
        }
        val spine = spineIds.mapNotNull { id ->
            val it = manifest[id] ?: return@mapNotNull null
            if (it.second.contains("html") || it.second.contains("xml")) it.first else null
        }
        val cover = manifest.values.firstOrNull { it.third.contains("cover-image") && it.second.startsWith("image") }?.first
        return OpfResult(title, author, cover, spine)
    }
}
