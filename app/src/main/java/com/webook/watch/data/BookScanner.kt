package com.webook.watch.data

import com.webook.watch.parser.SUPPORTED_EXT
import java.io.File

/**
 * 目录扫描：把"别处已经下载好的书"收进书架。
 *
 * 典型场景：电脑上（或者手机的 Termux、MT 管理器、微信里）已经把 epub / txt 下载好了，
 * 扔在 `Download`、`Books` 这类文件夹里 —— 与其在手表上一个个点导入，
 * 不如让腕上书自己扫一遍，把没见过的收进来。
 *
 * 两条规矩：
 * 1. **只扫一层，不递归**：否则在动辄几万文件的目录里空转，用户只会以为卡死了；
 * 2. **靠 "路径|字节数" 记账**（见 LibraryStore.scanned）：同一个文件反复扫不会重复入库，
 *    而文件被替换成新内容（字节数变了）会被当成新书重新收。
 */
object BookScanner {

    fun isBookName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXT

    /** 常见的"别人把书放在这儿"的位置，挨个试，哪个有书扫哪个 */
    fun defaultDirs(storageRoot: File): List<File> = listOf(
        File(storageRoot, "Download"),
        File(storageRoot, "Downloads"),
        File(storageRoot, "Books"),
        File(storageRoot, "books")
    )

    /**
     * 列出一个目录里的书（只一层）。
     * 0 字节、目录、以及临时文件（.part / .crdownload / .tmp）都跳过。
     */
    fun booksIn(dir: File): List<File> =
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.length() > 0L && isBookName(it.name) && !isTempName(it.name) }
            .sortedBy { it.name.lowercase() }

    /** 下载器/浏览器留下的半成品：后缀是书但明显没下完 */
    private fun isTempName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".part") || lower.endsWith(".crdownload") ||
            lower.endsWith(".tmp") || lower.endsWith(".download") || lower.startsWith("._")
    }

    /** 记账用的键：路径 + 字节数 */
    fun keyOf(f: File): String = "${f.absolutePath}|${f.length()}"
}
