package com.webook.watch.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.webook.watch.data.ImageRef
import java.io.File
import java.util.zip.ZipFile

/**
 * 从 epub 原文件里**按需**取插图。
 *
 * 为什么不导入时把图抽出来存一份：那本 313MB 的书插图有 172MB，
 * 手表那点存储和 96MB 的堆根本放不下；而正文其实只有 2.7MB。
 * 所以这里只在翻到某一页时才去 zip 里读那一两张，解码时按屏幕宽度降采样，
 * 再用一个很小的 LRU 缓存住 —— 内存和存储都不额外增加。
 *
 * 原文件被删/移走时插图会失效（返回 null，界面画占位框），正文不受影响。
 */
object EpubImages {

    private const val MAX_CACHE = 4

    /** 访问顺序的 LRU：最多留 4 张，翻回上一页还能命中 */
    private val cache = object : LinkedHashMap<String, Bitmap>(MAX_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > MAX_CACHE
    }

    /**
     * @param bookPath epub 原文件路径
     * @param targetW  期望的显示宽度（px），用来算降采样率
     * @return 已缩放的位图；路径为空 / 文件不在 / 条目缺失 / 解码失败都返回 null
     */
    @Synchronized
    fun load(bookPath: String?, ref: ImageRef, targetW: Int): Bitmap? {
        if (bookPath.isNullOrBlank() || targetW <= 0) return null
        val key = "$bookPath|${ref.path}|$targetW"
        cache[key]?.let { return it }
        val f = File(bookPath)
        if (!f.exists() || !f.canRead()) return null
        val bmp = runCatching {
            ZipFile(f).use { zip ->
                val e = zip.getEntry(ref.path) ?: return@use null
                // 先只读尺寸算采样率：这些插图动辄一两千像素宽，直接解码能吃掉几十 MB
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                zip.getInputStream(e).use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0) return@use null
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565   // 插图够用，内存只有 ARGB_8888 的一半
                }
                zip.getInputStream(e).use { BitmapFactory.decodeStream(it, null, opts) }
            }
        }.getOrNull() ?: return null
        cache[key] = bmp
        return bmp
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }
}
