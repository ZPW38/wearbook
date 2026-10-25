package com.webook.watch.data

/**
 * 同一本书的「更新」逻辑。
 *
 * 场景很常见：连载小说先下了 100 章，过阵子又下了 130 章的新版本；
 * 或者同一本书换了个精校版重下。如果直接当新书导入，书架上会出现两本同名书，
 * 而你的阅读进度还留在旧的那本上 —— 等于白读。
 *
 * 这里负责三件事：
 * 1. **认书**：两本书是不是同一本（书名归一化 + 章节标题重合度）；
 * 2. **比对**：新书多了几章、少了几章、哪些章改过；
 * 3. **迁进度**：把「旧书的第几章第几段」搬到新书的对应位置。
 *
 * 全部是纯函数，不碰文件、不碰 Android，可以直接 JVM 单测。
 */
object BookMerge {

    /** 书名里的"版本噪音"：这些词不代表是不同的书 */
    private val TITLE_NOISE = listOf(
        "全本", "完本", "完结", "精校", "校对", "修订", "新版", "珍藏",
        "全集", "合集", "txt", "epub", "mobi", "azw3", "下载", "无删减"
    )

    /**
     * 书名归一化：去扩展名、去括号里的版本词、去空白标点、统一全半角。
     * `《长安异闻录》（全本精校版）.txt` → `长安异闻录`
     */
    fun normTitle(raw: String): String {
        var s = raw.substringBeforeLast('.', raw)          // 去扩展名
        s = s.replace('（', '(').replace('）', ')')
        s = s.replace('【', '[').replace('】', ']')
        // 逐层剥掉括号内容（可能嵌套，剥两次够了）
        repeat(2) {
            s = s.replace(Regex("[（(\\[【][^）)\\]】]*[）)\\]】]"), "")
        }
        s = s.lowercase()
        TITLE_NOISE.forEach { s = s.replace(it, "") }
        // 只留字母数字与中日韩字符
        s = s.filter { it.isLetterOrDigit() }
        return s
    }

    /**
     * 章节标题归一化：去掉「第 12 章」「Chapter 12」「12.」这类序号前缀与所有空白。
     *
     * 为什么必须去掉序号：不同版本的同一本书，章号可能重排（旧版把序章算第 1 章，
     * 新版不算），但**章节名本身**通常不变。只比标题名，才能跨版本对上号。
     */
    fun normChapter(raw: String): String {
        var s = raw.trim()
        // 第X章 / 第X节 / 第X回 / 第十二章
        s = s.replace(Regex("^第\\s*[0-9０-９一二三四五六七八九十百千零两]+\\s*[章节回卷部篇集话]\\s*"), "")
        // Chapter 12 / CHAPTER 12
        s = s.replace(Regex("^chapter\\s*\\d+\\s*[:：.、-]?\\s*", RegexOption.IGNORE_CASE), "")
        // 纯数字开头：12. xxx / 12、xxx / 12 xxx
        s = s.replace(Regex("^\\d+\\s*[.、:：,，\\-]?\\s*"), "")
        s = s.filterNot { it.isWhitespace() }
        return s.lowercase()
    }

    /**
     * 章节内容指纹。
     * 只取长度 + 前两段的前 64 个字符 —— 整章逐字比对上万段会卡，
     * 而我们只需要判断"这章有没有被改过"，用这个够准了。
     */
    private fun chapterPrint(c: Chapter): Int {
        val head = c.paragraphs.take(2).joinToString("").take(64)
        return 31 * c.paragraphs.size + head.hashCode()
    }

    /** 一次比对的结论 */
    data class Diff(
        /** 判定为同一本书 */
        val sameBook: Boolean,
        val oldTitle: String,
        val newTitle: String,
        val oldChapterCount: Int,
        val newChapterCount: Int,
        /** 标题能对上的章节数 */
        val matched: Int,
        /** 新书比旧书多出来的章节数 */
        val added: Int,
        /** 旧书有、新书没有的章节数 */
        val removed: Int,
        /** 标题相同但正文被改过的章节数 */
        val rewritten: Int
    ) {
        /** 新书是否只是"往后追加了几章"——这种情况进度可以原样保留 */
        val isPureAppend: Boolean get() = removed == 0 && rewritten == 0 && added > 0

        /** 给用户看的一句话摘要 */
        fun summary(): String = when {
            isPureAppend -> "新增 $added 章（原有内容未变）"
            added == 0 && removed == 0 && rewritten == 0 -> "内容完全一致"
            else -> buildString {
                if (added > 0) append("新增 $added 章")
                if (rewritten > 0) { if (isNotEmpty()) append("，"); append("改写 $rewritten 章") }
                if (removed > 0) { if (isNotEmpty()) append("，"); append("缺少 $removed 章") }
            }
        }
    }

    /**
     * 判断两本书是不是同一本，并给出差异。
     *
     * 判定顺序（命中即停，越往后越宽松）：
     * 1. 归一化书名相同 → 基本确定是同一本（章节数差太多也还是）；
     * 2. 书名不同但章节标题重合率 ≥ 80% → 也是同一本（改名重发的常见）；
     * 3. 其余 → 不是。
     */
    fun diff(
        oldTitle: String,
        oldChapters: List<Chapter>,
        newTitle: String,
        newChapters: List<Chapter>
    ): Diff {
        val oldNorm = normTitle(oldTitle)
        val newNorm = normTitle(newTitle)

        // 旧书章节：归一化标题 -> 内容指纹（同名章节只留第一个）
        val oldMap = HashMap<String, Int>(oldChapters.size * 2)
        oldChapters.forEach { c ->
            val k = normChapter(c.title)
            if (k.isNotEmpty() && !oldMap.containsKey(k)) oldMap[k] = chapterPrint(c)
        }

        var matched = 0
        var rewritten = 0
        val newKeys = HashSet<String>(newChapters.size * 2)
        newChapters.forEach { c ->
            val k = normChapter(c.title)
            if (k.isEmpty()) return@forEach
            newKeys.add(k)
            val oldPrint = oldMap[k] ?: return@forEach
            matched++
            if (oldPrint != chapterPrint(c)) rewritten++
        }
        val removed = oldMap.keys.count { it !in newKeys }
        val added = (newChapters.size - matched).coerceAtLeast(0)

        val sameTitle = oldNorm.isNotEmpty() && oldNorm == newNorm
        // 章节标题重合率：以两边章节数中较小的为分母，避免"新书多了 100 章"把比例拉低
        val denom = minOf(oldChapters.size, newChapters.size).coerceAtLeast(1)
        val overlapRatio = matched.toFloat() / denom

        val sameBook = when {
            sameTitle -> true
            oldNorm.isEmpty() || newNorm.isEmpty() -> false
            overlapRatio >= 0.8f && matched >= 3 -> true
            else -> false
        }

        return Diff(
            sameBook = sameBook,
            oldTitle = oldTitle,
            newTitle = newTitle,
            oldChapterCount = oldChapters.size,
            newChapterCount = newChapters.size,
            matched = matched,
            added = added,
            removed = removed,
            rewritten = rewritten
        )
    }

    /**
     * 把旧进度迁到新书上。
     *
     * 优先级：
     * 1. **章节标题能对上** → 用新索引，段内偏移原样保留（内容没变的话位置就还在）；
     * 2. 对不上（章节被改名/删了）→ 按旧章节在全书中的相对位置映射到新书，
     *    段落号归零，宁可让读者往前翻一点，也不要跳到一个随机位置。
     */
    fun migrate(old: Progress, oldChapters: List<Chapter>, newChapters: List<Chapter>): Progress {
        if (newChapters.isEmpty()) return Progress(0, 0, -1, 0, 0f, System.currentTimeMillis())
        if (oldChapters.isEmpty()) return old

        val oldIdx = old.chapter.coerceIn(0, oldChapters.lastIndex)
        val oldKey = normChapter(oldChapters[oldIdx].title)

        // 1. 按标题找同一个章节
        val hit = newChapters.indexOfFirst { normChapter(it.title) == oldKey && oldKey.isNotEmpty() }

        val newIdx: Int
        val para: Int
        val charStart: Int
        if (hit >= 0) {
            newIdx = hit
            val newParaCount = newChapters[hit].paragraphs.size
            // 新章节比旧的短，段落号要收进范围，否则恢复时会落到章末之后
            para = if (old.hasExact) old.para.coerceIn(0, (newParaCount - 1).coerceAtLeast(0)) else -1
            charStart = old.charStart.coerceAtLeast(0)
        } else {
            // 2. 按相对位置映射
            newIdx = ((oldIdx.toFloat() / oldChapters.size) * newChapters.size).toInt()
                .coerceIn(0, newChapters.lastIndex)
            para = 0
            charStart = 0
        }

        // 全书百分比按新书重算：按"已读完的章数 + 本章内大约读到哪"估算
        val frac = ((newIdx + 0.5f) / newChapters.size).coerceIn(0f, 0.999f)

        return Progress(
            chapter = newIdx,
            page = 0,
            para = para,
            charStart = charStart,
            frac = frac,
            updatedAt = System.currentTimeMillis()
        )
    }
}
