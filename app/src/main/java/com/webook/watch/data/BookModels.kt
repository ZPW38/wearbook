package com.webook.watch.data

/** 一本书的元信息（存 library.json） */
data class BookMeta(
    val id: String,
    val title: String,
    val author: String,
    val fmt: String,          // txt / epub / mobi / html / demo
    val chapterCount: Int,
    val size: Long,
    val addedAt: Long,
    val cover: String? = null, // 封面文件名（covers 目录下）
    /**
     * 原文件路径（epub 才有）。
     * 插图不预先抽取、也不占额外空间 —— 阅读时按需从这个 zip 里读那一两张，
     * 所以必须记住它在哪儿（文件被删/移走时插图会失效，正文不受影响）。
     */
    val source: String? = null
)

/** 章节：标题 + 段落列表 */
data class Chapter(
    val title: String,
    val paragraphs: List<String>
)

/**
 * 正文里的一张插图。
 * `path` 是图片在 epub 压缩包内的路径，宽高在解析时读图片头得到（0 表示没读出来，按 4:3 估）。
 */
data class ImageRef(val path: String, val w: Int, val h: Int)

/**
 * 图片在段落列表里用一个「特殊标记段落」表示：
 *   "\u0000IMG\u0000<zip内路径>\u0000<宽>\u0000<高>"
 * 这样章节数据仍然是「一堆字符串」，存储格式、进度、书签都不用动，
 * 只有排版和绘制需要认识这个前缀。
 */
const val IMG_MARK = "\u0000IMG\u0000"

/** 若该段落是插图，解析出引用；否则返回 null */
fun parseImagePara(p: String): ImageRef? {
    if (!p.startsWith(IMG_MARK)) return null
    val parts = p.substring(IMG_MARK.length).split('\u0000')
    val path = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
    return ImageRef(path, parts.getOrNull(1)?.toIntOrNull() ?: 0, parts.getOrNull(2)?.toIntOrNull() ?: 0)
}

/** 组装一个插图段落 */
fun imageParagraph(path: String, w: Int, h: Int): String = "$IMG_MARK$path\u0000$w\u0000$h"

/** 取第一个「不是插图」的段落，用于给章节起标题 */
fun Chapter.firstTextPara(): String = paragraphs.firstOrNull { parseImagePara(it) == null && it.isNotBlank() } ?: ""

/** 阅读进度 */
data class Progress(
    val chapter: Int = 0,
    val page: Int = 0,
    val updatedAt: Long = 0L
)

/** 书签：记到「第几章第几页」，避免重新排版后页码漂移导致错位 */
data class Bookmark(
    val chapter: Int,
    val page: Int,
    val chapterTitle: String,
    val excerpt: String,      // 该页开头几个字，方便认
    val at: Long = System.currentTimeMillis()
)

/** 全文搜索的一条命中 */
data class SearchHit(
    val chapter: Int,
    val chapterTitle: String,
    val para: Int,
    val offset: Int,
    val snippet: String
)
