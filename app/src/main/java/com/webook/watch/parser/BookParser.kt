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

    /* ---------------- 编码识别：先严格 UTF-8，失败回退 GBK ---------------- */
    fun decode(bytes: ByteArray): String {
        try {
            val dec = Charset.forName("UTF-8").newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val s = dec.decode(ByteBuffer.wrap(bytes)).toString()
            if (!s.contains('\uFFFD')) return s
        } catch (e: Exception) { /* 落到 GBK */ }
        return runCatching { String(bytes, Charset.forName("GBK")) }.getOrDefault(String(bytes, Charsets.UTF_8))
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
        return ParsedBook(title, "", "txt", chunk(paras, title))
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
