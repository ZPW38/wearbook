package com.webook.watch.data

/**
 * 把整本书里出现的插图按**阅读顺序**收集成一个序列，供插图查看器前后切换。
 *
 * 为什么要全书收集而不是只看当前页：一页通常只有一两张图（手表屏小，正文里插图最多占版面 62%），
 * 只在本页里切换等于没有切换；而章节是阅读的单位，一张地图、一张族谱往往跟着上下文走，
 * 用户看到一张图想「再往后翻两张」，翻的应该是这本书的图，不该被章节边界拦住。
 *
 * 去重：同一张图（zip 内同一路径）可能被正文引用多次（封面、插图目录、同图复用），
 * 只保留第一次出现的位置，免得用户来回切到同一张还以为是重影。
 *
 * 这个函数是纯函数（不碰 Android、不读 zip），可以放到 JVM 单测里跑。
 */
fun collectImages(chapters: List<Chapter>): List<ImageRef> {
    val seen = HashSet<String>()
    val out = ArrayList<ImageRef>()
    for (ch in chapters) {
        for (p in ch.paragraphs) {
            val ref = parseImagePara(p) ?: continue
            if (seen.add(ref.path)) out.add(ref)
        }
    }
    return out
}
