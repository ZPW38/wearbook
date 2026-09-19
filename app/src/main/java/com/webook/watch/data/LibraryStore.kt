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
                        cover = o.optString("cover", "").ifBlank { null }
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
            })
        }
        indexFile.writeText(arr.toString())
    }

    /** 新增一本（返回 id） */
    fun add(title: String, author: String, fmt: String, size: Long, chapters: List<Chapter>, cover: Bitmap? = null): BookMeta {
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
        val meta = BookMeta(id, title, author, fmt, chapters.size, size, System.currentTimeMillis(), coverName)
        File(chapterDir, "$id.json").writeText(chaptersToJson(chapters))
        save(list() + meta)
        return meta
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

    private fun chaptersToJson(chapters: List<Chapter>): String {
        val arr = JSONArray()
        chapters.forEach { c ->
            val ps = JSONArray()
            c.paragraphs.forEach { ps.put(it) }
            arr.put(JSONObject().put("t", c.title).put("p", ps))
        }
        return arr.toString()
    }

    fun allProgress(): Map<String, Progress> {
        if (!progressFile.exists()) return emptyMap()
        return try {
            val o = JSONObject(progressFile.readText())
            o.keys().asSequence().associateWith { k ->
                val v = o.getJSONObject(k)
                Progress(v.optInt("c", 0), v.optInt("p", 0), v.optLong("t", 0))
            }
        } catch (e: Exception) { emptyMap() }
    }

    fun progress(id: String): Progress? = allProgress()[id]

    fun saveProgress(id: String, p: Progress) {
        val map = allProgress().toMutableMap()
        map[id] = p.copy(updatedAt = System.currentTimeMillis())
        saveProgress(map)
    }

    private fun saveProgress(map: Map<String, Progress>) {
        val o = JSONObject()
        map.forEach { (k, v) ->
            o.put(k, JSONObject().put("c", v.chapter).put("p", v.page).put("t", v.updatedAt))
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
