package com.webook.watch.parser

import com.webook.watch.data.Chapter

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

        val text = BookParser.decode(textBytes)
        val chapters = mutableListOf<Chapter>()
        text.split(Regex("(?i)<mbp:pagebreak\\s*/?>")).forEach { seg ->
            val paras = BookParser.htmlToParagraphs("<html><body>$seg</body></html>")
            if (paras.isNotEmpty()) chapters.add(Chapter(paras.first().take(24), paras))
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
}
