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
    val cover: String? = null // 封面文件名（covers 目录下）
)

/** 章节：标题 + 段落列表 */
data class Chapter(
    val title: String,
    val paragraphs: List<String>
)

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
