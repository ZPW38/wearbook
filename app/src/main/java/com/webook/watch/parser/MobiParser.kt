package com.webook.watch.parser

import com.webook.watch.data.Chapter
import java.nio.charset.Charset

/**
 * MOBI / AZW（PalmDOC 压缩）解析
 * 仅支持 compression = 1（未压缩）/ 2（PalmDOC 回溯压缩）；
 * KF8 / KFX 为另一种结构，会明确提示不支持。
 */
object MobiParser {

    fun parse(bytes: ByteArray, name: String): ParsedBook {
        val u = bytes
        if (u.size < 96) throw IllegalArgumentException("文件过小，不是有效的 MOBI")
        val magic = String(u, 60, 8, Charsets.US_ASCII)
        if (magic != "BOOKMOBI") throw IllegalArgumentException("不是有效的 MOBI 文件")

        val nRec = u16(u, 76)
        val offs = IntArray(nRec) { i -> u32(u, 78 + i * 8) }
        val r0 = offs[0]
        val compression = u16(u, r0)
        val textLen = u32(u, r0 + 4)
        val recCount = u16(u, r0 + 8)
        if (compression != 1 && compression != 2)
            throw IllegalArgumentException("暂不支持该压缩格式($compression)，KF8/KFX 无法解析")

        val parts = ArrayList<ByteArray>()
        for (i in 1..recCount.coerceAtMost(nRec - 1)) {
            val start = offs[i]
            val end = if (i + 1 < nRec) offs[i + 1] else u.size
            if (end <= start) continue
            val rec = u.copyOfRange(start, end)
            parts.add(if (compression == 2) PalmDoc.decompress(rec) else rec)
        }
        var textBytes = ByteArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) { p.copyInto(textBytes, pos); pos += p.size }
        if (textLen > 0 && textLen < textBytes.size) textBytes = textBytes.copyOfRange(0, textLen)

        // MOBI 头自述的编码（65001=UTF-8 / 1252=CP1252 / 936=GBK ...），比猜靠谱
        val enc = u32(u, r0 + 28)
        val charset: String? = when (enc) {
            65001 -> "UTF-8"
            1252 -> "windows-1252"
            936 -> "GBK"
            950 -> "Big5"
            932 -> "Shift_JIS"
            949 -> "EUC-KR"
            1361 -> "EUC-JP"
            else -> runCatching { Charset.forName(enc.toString()).name() }.getOrNull()
        }
        // 优先按书自带目录切；目录不可用时退回物理分页
        var chapters = splitByToc(textBytes)
        if (chapters == null || chapters.size < 2) {
            val text = BookParser.decode(textBytes, charset)
            chapters = mutableListOf<Chapter>()
            text.split(PAGE_BREAK).forEach { seg ->
                val paras = BookParser.htmlToParagraphs("<html><body>$seg</body></html>")
                if (paras.isNotEmpty()) chapters.add(Chapter(paras.first().take(24), paras))
            }
        }
        if (chapters.isEmpty()) throw IllegalArgumentException("MOBI 内没有可读正文")
        return ParsedBook(
            name.substringBeforeLast('.'), "", "mobi", chapters
        )
    }

    private fun u16(a: ByteArray, o: Int): Int = ((a[o].toInt() and 0xFF) shl 8) or (a[o + 1].toInt() and 0xFF)
    private fun u32(a: ByteArray, o: Int): Int =
        ((a[o].toInt() and 0xFF) shl 24) or ((a[o + 1].toInt() and 0xFF) shl 16) or
        ((a[o + 2].toInt() and 0xFF) shl 8) or (a[o + 3].toInt() and 0xFF)

    /* ---------------- 章节切分：目录锚点优先 ---------------- */

    private val PAGE_BREAK = Regex("(?i)<mbp:pagebreak\\s*/?>")

    /** <a filepos=0000123456>章节名</a> —— filepos 是「解压后字节流」里的偏移 */
    private val ANCHOR = Regex("<a\\s+filepos=(\\d{1,10})>([^<]{0,80})</a>", RegexOption.IGNORE_CASE)

    /** 纯序号、书末推广链接之类的假标题 */
    private val JUNK_TITLE = Regex("^\\d+[.、)）]?$|点击链接|详情页面|阿西莫夫|^[\\p{Punct}\\p{S}]{1,4}$")

    private val WS = Regex("\\s+")
    private val BROKEN = Regex("\\uFFFD+")
    private val CN_GAP = Regex("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])")

    /** 两个目录（正文目录 + 书末目录）会对同一位置给出不同标题，靠这个距离合并掉 */
    private const val MERGE_GAP = 800

    /** 一章至少要有这么多中文字，否则丢掉（书末书单切出来的小碎片） */
    private const val MIN_CN = 50

    /**
     * 按目录锚点切分。返回 null 表示这本书没有可用目录，调用方应回退到分页符切分。
     */
    private fun splitByToc(bytes: ByteArray): List<Chapter>? {
        // 用 ISO-8859-1 铺一遍：1 字符 = 1 字节，正则命中位置可以直接当字节偏移用，
        // 省掉「字节偏移 -> 字符下标」的映射表（3MB 的书建那张表太占内存）。
        val latin = String(bytes, Charsets.ISO_8859_1)
        val hits = ArrayList<Pair<Int, String>>()
        for (m in ANCHOR.findAll(latin)) {
            val pos = m.groupValues[1].toIntOrNull() ?: continue
            if (pos <= 0 || pos >= bytes.size) continue
            val gt = m.value.indexOf('>')
            if (gt < 0) continue
            val from = m.range.first + gt + 1
            val close = m.value.lastIndexOf("</a>")
            if (close < 0) continue
            val to = m.range.first + close              // 标题到 </a> 之前为止，不含它
            if (to <= from) continue
            val title = String(bytes, from, to - from, Charsets.UTF_8)
                .replace(BROKEN, "")          // 源文件自带的坏字符
                .replace(CN_GAP, "")          // 「大 史」这种排版空格
                .replace(WS, " ").trim()
            if (title.isNotEmpty() && !JUNK_TITLE.containsMatchIn(title)) hits.add(pos to title)
        }
        if (hits.size < 2) return null
        hits.sortBy { it.first }

        val merged = ArrayList<Pair<Int, String>>(hits.size)
        for ((pos, title) in hits) {
            val last = merged.lastOrNull()
            if (last != null && pos - last.first < MERGE_GAP) {
                if (title.length > last.second.length) merged[merged.size - 1] = pos to title
            } else {
                merged.add(pos to title)
            }
        }

        val out = ArrayList<Chapter>(merged.size + 1)
        // filepos 指向「本章的起点」，所以 [上一个锚点, 本锚点) 这一段属于上一个锚点的标题
        var prev = 0
        var title = "开头"
        for ((pos, t) in merged) {
            append(out, bytes, prev, pos, title)
            prev = pos
            title = t
        }
        append(out, bytes, prev, bytes.size, title)
        return out.takeIf { it.size >= 2 }
    }

    private fun append(out: MutableList<Chapter>, bytes: ByteArray, from: Int, to: Int, title: String) {
        if (to <= from || to > bytes.size) return
        val seg = String(bytes, from, to - from, Charsets.UTF_8)
        val paras = BookParser.htmlToParagraphs("<html><body>$seg</body></html>")
        if (paras.isEmpty()) return
        var cn = 0
        loop@ for (p in paras) {
            for (c in p) {
                if (c.code in 0x4E00..0x9FFF) {
                    cn++
                    if (cn >= MIN_CN) break@loop
                }
            }
        }
        if (cn >= MIN_CN) out.add(Chapter(title.take(40), paras))
    }
}
