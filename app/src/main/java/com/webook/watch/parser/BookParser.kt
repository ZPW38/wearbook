package com.webook.watch.parser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Xml
import com.webook.watch.data.Chapter
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

    /** 按扩展名分派 */
    fun parse(file: File, name: String, bytes: ByteArray? = null): ParsedBook {
        val ext = name.substringAfterLast('.', "").lowercase()
        val data = bytes ?: file.readBytes()
        return when (ext) {
            "txt" -> txtToBook(data, name)
            "epub" -> epubToBook(file, name)
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
    fun htmlToParagraphs(html: String): List<String> {
        var s = html
        s = Regex("(?is)<(script|style|head|nav)[^>]*>.*?</\\1>").replace(s, " ")
        s = Regex("(?i)</(p|div|h[1-6]|li|blockquote|tr)>").replace(s, "\n\n")
        s = Regex("(?i)<br\\s*/?>").replace(s, "\n")
        s = Regex("<[^>]+>").replace(s, "")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
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
            for (href in opf.spine) {
                val entry = zip.getEntry(resolve(base, href)) ?: continue
                val paras = runCatching {
                    htmlToParagraphs(decode(zip.getInputStream(entry).readBytes()))
                }.getOrDefault(emptyList())
                if (paras.isNotEmpty()) chapters.add(Chapter(paras.first().take(24), paras))
            }
            if (chapters.isEmpty()) throw IllegalArgumentException("EPUB 内没有可读正文")

            val cover = opf.coverHref?.let { href ->
                zip.getEntry(resolve(base, href))?.let { e ->
                    runCatching {
                        val bmp = BitmapFactory.decodeStream(zip.getInputStream(e)) ?: return@let null
                        scaleCover(bmp)
                    }.getOrNull()
                }
            }
            ParsedBook(title, opf.author, "epub", chapters, cover)
        }
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
