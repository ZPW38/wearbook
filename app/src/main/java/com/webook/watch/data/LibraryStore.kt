package com.webook.watch.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本地书架存储：全部放在应用私有目录，不依赖数据库、不需要任何权限。
 *   library.json        书单
 *   chapters/<id>.json  章节正文
 *   covers/<id>.jpg     封面缩略图
 *   progress.json       阅读进度
 */
class LibraryStore(private val ctx: Context) {

    private val root: File get() = File(ctx.filesDir, "wearbook").apply { mkdirs() }
    private val chapterDir: File get() = File(root, "chapters").apply { mkdirs() }
    private val coverDir: File get() = File(root, "covers").apply { mkdirs() }
    private val indexFile get() = File(root, "library.json")
    private val progressFile get() = File(root, "progress.json")
    private val bookmarkFile get() = File(root, "bookmarks.json")

    /** 从外部目录扫进来的书，用 "绝对路径|字节数" 记账，免得每次扫描都重复导入 */
    private val scannedFile get() = File(root, "scanned.json")

    /** 已扫进来的外部文件：key = "路径|字节数" -> 导入时间 */
    fun scanned(): Map<String, Long> {
        if (!scannedFile.exists()) return emptyMap()
        return runCatching {
            val o = JSONObject(scannedFile.readText())
            o.keys().asSequence().associateWith { o.optLong(it, 0L) }
        }.getOrDefault(emptyMap())
    }

    /**
     * 记下这批扫进来的文件。
     * 只保留最近 300 条：扫过的目录可能随时被清空，记太多没有意义，反而越写越大。
     */
    fun markScanned(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val merged = scanned().toMutableMap()
        val now = System.currentTimeMillis()
        keys.forEach { merged[it] = now }
        val o = JSONObject()
        merged.entries.sortedByDescending { it.value }.take(300)
            .forEach { (k, v) -> o.put(k, v) }
        runCatching { scannedFile.writeText(o.toString()) }
    }

    fun list(): List<BookMeta> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                runCatching {
                    BookMeta(
                        id = o.getString("id"),
                        title = o.optString("title", "未命名"),
                        author = o.optString("author", ""),
                        fmt = o.optString("fmt", ""),
                        chapterCount = o.optInt("n", 0),
                        size = o.optLong("size", 0),
                        addedAt = o.optLong("addedAt", 0),
                        cover = o.optString("cover", "").ifBlank { null },
                        source = o.optString("source", "").ifBlank { null }
                    )
                }.getOrNull()
            }.sortedByDescending { it.addedAt }
        } catch (e: Exception) { emptyList() }
    }

    private fun save(list: List<BookMeta>) {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(JSONObject().apply {
                put("id", b.id); put("title", b.title); put("author", b.author)
                put("fmt", b.fmt); put("n", b.chapterCount); put("size", b.size)
                put("addedAt", b.addedAt); put("cover", b.cover ?: "")
                put("source", b.source ?: "")
            })
        }
        indexFile.writeText(arr.toString())
    }

    /** 新增一本（返回 id）。source = 原文件路径（epub 的插图按需从它里面读） */
    fun add(
        title: String, author: String, fmt: String, size: Long,
        chapters: List<Chapter>, cover: Bitmap? = null, source: String? = null
    ): BookMeta {
        val id = System.currentTimeMillis().toString()
        var coverName: String? = null
        if (cover != null) {
            coverName = "$id.jpg"
            runCatching {
                File(coverDir, coverName).outputStream().use {
                    cover.compress(Bitmap.CompressFormat.JPEG, 72, it)
                }
            }
        }
        val meta = BookMeta(id, title, author, fmt, chapters.size, size, System.currentTimeMillis(), coverName, source)
        // 逐章写入：上千章的书如果先拼成一个巨大字符串，会连续触发阻塞式 GC，手表上界面会卡住
        File(chapterDir, "$id.json").bufferedWriter().use { w -> chaptersToJson(chapters, w) }
        save(list() + meta)
        return meta
    }

    /**
     * 就地更新一本书（同一本书的新版本）。
     *
     * **刻意保留原来的 id**：id 是章节文件、封面文件名、阅读进度、书签的外键，
     * 换新 id 等于把这本书的阅读历史全丢掉。更新完读者翻开新月版，还停在原来那一章。
     *
     * 旧章节文件直接覆盖写 —— 不先删再写，免得写到一半失败留下"有 meta 没内容"的空书。
     */
    fun update(
        id: String, title: String, author: String, fmt: String, size: Long,
        chapters: List<Chapter>, cover: Bitmap? = null, source: String? = null
    ): Boolean {
        val list = list().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return false

        File(chapterDir, "$id.json").bufferedWriter().use { w -> chaptersToJson(chapters, w) }

        var coverName = list[i].cover
        if (cover != null) {
            coverName = "$id.jpg"
            runCatching {
                File(coverDir, coverName).outputStream().use {
                    cover.compress(Bitmap.CompressFormat.JPEG, 72, it)
                }
            }
        }
        list[i] = list[i].copy(
            title = title,
            author = author,
            fmt = fmt,
            chapterCount = chapters.size,
            size = size,
            cover = coverName,
            // 新版是别的文件时，插图要跟着改读到新文件去
            source = source ?: list[i].source
        )
        save(list)
        return true
    }

    fun remove(id: String) {
        save(list().filter { it.id != id })
        File(chapterDir, "$id.json").delete()
        list().firstOrNull { it.cover == "$id.jpg" } ?: File(coverDir, "$id.jpg").delete()
        val p = allProgress().toMutableMap(); p.remove(id); saveProgress(p)
    }

    fun chapters(id: String): List<Chapter> {
        val f = File(chapterDir, "$id.json")
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val ps = o.getJSONArray("p")
                Chapter(o.optString("t", ""), (0 until ps.length()).map { ps.getString(it) })
            }
        } catch (e: Exception) { emptyList() }
    }

    fun coverBitmap(meta: BookMeta): Bitmap? {
        val n = meta.cover ?: return null
        return runCatching { BitmapFactory.decodeFile(File(coverDir, n).absolutePath) }.getOrNull()
    }

    /** 直接流式写进文件，不在内存里拼整本书的 JSON */
    private fun chaptersToJson(chapters: List<Chapter>, out: Appendable) {
        out.append('[')
        chapters.forEachIndexed { i, c ->
            if (i > 0) out.append(',')
            out.append("{\"t\":").append(JSONObject.quote(c.title)).append(",\"p\":[")
            c.paragraphs.forEachIndexed { j, p ->
                if (j > 0) out.append(',')
                out.append(JSONObject.quote(p))
            }
            out.append("]}")
        }
        out.append(']')
    }

    fun allProgress(): Map<String, Progress> {
        if (!progressFile.exists()) return emptyMap()
        return try {
            val o = JSONObject(progressFile.readText())
            o.keys().asSequence().associateWith { k ->
                val v = o.getJSONObject(k)
                Progress(
                    chapter = v.optInt("c", 0),
                    page = v.optInt("p", 0),
                    para = v.optInt("pa", -1),          // 老记录没有这个键 -> -1（走页码兜底）
                    charStart = v.optInt("cs", 0),
                    frac = v.optDouble("f", 0.0).toFloat(),
                    updatedAt = v.optLong("t", 0)
                )
            }
        } catch (e: Exception) { emptyMap() }
    }

    fun progress(id: String): Progress? = allProgress()[id]

    fun saveProgress(id: String, p: Progress) {
        val map = allProgress().toMutableMap()
        map[id] = p.copy(updatedAt = System.currentTimeMillis())
        saveProgress(map)
    }

    /** 更新进度时调用；更新书籍后要把迁移过的进度写回去 */
    fun saveProgress(map: Map<String, Progress>) {
        val o = JSONObject()
        map.forEach { (k, v) ->
            o.put(
                k, JSONObject()
                    .put("c", v.chapter).put("p", v.page)
                    .put("pa", v.para).put("cs", v.charStart)
                    .put("f", v.frac.toDouble())
                    .put("t", v.updatedAt)
            )
        }
        progressFile.writeText(o.toString())
    }

    /* ---------------- 书签 ---------------- */

    /** 全部书签：bookId -> 书签列表（按加入时间正序） */
    private fun allBookmarks(): Map<String, List<Bookmark>> {
        if (!bookmarkFile.exists()) return emptyMap()
        return try {
            val o = JSONObject(bookmarkFile.readText())
            o.keys().asSequence().associateWith { k ->
                val arr = o.getJSONArray(k)
                (0 until arr.length()).mapNotNull { i ->
                    val b = arr.getJSONObject(i)
                    runCatching {
                        Bookmark(
                            chapter = b.optInt("c", 0),
                            page = b.optInt("p", 0),
                            chapterTitle = b.optString("t", ""),
                            excerpt = b.optString("x", ""),
                            at = b.optLong("at", 0)
                        )
                    }.getOrNull()
                }
            }
        } catch (e: Exception) { emptyMap() }
    }

    fun bookmarks(id: String): List<Bookmark> = allBookmarks()[id] ?: emptyList()

    fun addBookmark(id: String, b: Bookmark) {
        val map = allBookmarks().toMutableMap()
        val old = (map[id] ?: emptyList()).toMutableList()
        // 同一页不重复记
        if (old.any { it.chapter == b.chapter && it.page == b.page }) return
        old.add(b)
        map[id] = old
        val o = JSONObject()
        map.forEach { (k, v) ->
            val arr = JSONArray()
            v.forEach { bk ->
                arr.put(JSONObject().apply {
                    put("c", bk.chapter); put("p", bk.page); put("t", bk.chapterTitle)
                    put("x", bk.excerpt); put("at", bk.at)
                })
            }
            o.put(k, arr)
        }
        bookmarkFile.writeText(o.toString())
    }

    fun removeBookmark(id: String, index: Int) {
        val map = allBookmarks().toMutableMap()
        val old = (map[id] ?: return).toMutableList()
        if (index !in old.indices) return
        old.removeAt(index)
        if (old.isEmpty()) map.remove(id) else map[id] = old
        val o = JSONObject()
        map.forEach { (k, v) ->
            val arr = JSONArray()
            v.forEach { bk ->
                arr.put(JSONObject().apply {
                    put("c", bk.chapter); put("p", bk.page); put("t", bk.chapterTitle)
                    put("x", bk.excerpt); put("at", bk.at)
                })
            }
            o.put(k, arr)
        }
        bookmarkFile.writeText(o.toString())
    }

    fun clearAll() {
        root.deleteRecursively()
        root.mkdirs(); chapterDir.mkdirs(); coverDir.mkdirs()
    }
}
