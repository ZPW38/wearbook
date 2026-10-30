package com.webook.watch.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.webook.watch.R
import com.webook.watch.data.Chapter
import com.webook.watch.data.IMG_MARK
import com.webook.watch.data.ImageRef
import com.webook.watch.data.parseImagePara
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 漫画里的一页：指到"哪一章的第几段的那张图" */
data class ComicPage(val chapter: Int, val para: Int, val ref: ImageRef)

/** 把整本书的插图按顺序摊平成一页一页 */
fun comicPages(chapters: List<Chapter>): List<ComicPage> {
    val out = ArrayList<ComicPage>()
    chapters.forEachIndexed { ci, ch ->
        ch.paragraphs.forEachIndexed { pi, p ->
            parseImagePara(p)?.let { out.add(ComicPage(ci, pi, it)) }
        }
    }
    return out
}

/**
 * 漫画阅读器。
 *
 * 和正文阅读器的区别不只是"显示一张图"：
 * - **一屏一图铺满**：正文阅读器把图当段落排进文字流里，漫画需要图自己占满整屏；
 * - **点哪翻哪**：右侧前进、左侧后退（漫画的阅读方向），中间点一下显隐工具栏；
 * - **双击放大**：小屏看跨页大图必须能放大，放大后还能拖着看局部；
 * - **预读下一张**：翻页时才解码会看到"先黑一下再出图"，所以提前把下一张读进 LRU 缓存。
 *
 * 图片的解码复用 EpubImages —— 按屏幕宽度降采样，一张整页图解码后只占几百 KB，
 * LRU 只留 4 张，手表那点内存扛得住。
 */
@Composable
fun ComicReader(
    bookTitle: String,
    chapters: List<Chapter>,
    bookPath: String?,
    /** 起始页在全书插图序列里的下标 */
    initialPage: Int,
    onProgress: (chapter: Int, para: Int, page: Int, total: Int) -> Unit,
    onBack: () -> Unit,
    /** 退出时切回正文阅读（对"图文混排"的书有用） */
    onSwitchToText: (() -> Unit)? = null,
    /** 注册表冠/音量键翻页回调（true = 下一张）；传 null 表示注销 */
    onRegisterTurn: ((((Boolean) -> Unit)?) -> Unit)? = null
) {
    val pages = remember(chapters) { comicPages(chapters) }
    if (pages.isEmpty()) {
        EmptyComic(onBack = onBack)
        return
    }

    var pageIdx by remember { mutableIntStateOf(initialPage.coerceIn(0, pages.lastIndex)) }
    val page = pages[pageIdx]

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var showBar by remember { mutableStateOf(true) }

    // 缩放状态：1f = 原始大小（已 fit 屏幕），最大 4 倍
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val density = LocalDensity.current
    val wPx = with(density) { 320.dp.toPx() }.toInt()   // 占位，真实宽度在 BoxWithConstraints 里取

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val widthPx = with(density) { maxWidth.toPx() }.toInt()

        // 加载当前页，并顺手预热下一页
        LaunchedEffect(pageIdx, widthPx) {
            failed = false
            bitmap = null
            bitmap = withContext(Dispatchers.IO) {
                EpubImages.load(bookPath, pages[pageIdx].ref, widthPx)
            }
            failed = bitmap == null
            pages.getOrNull(pageIdx + 1)?.let { next ->
                withContext(Dispatchers.IO) { EpubImages.load(bookPath, next.ref, widthPx) }
            }
            onProgress(pages[pageIdx].chapter, pages[pageIdx].para, pageIdx, pages.size)
        }

        // 翻页时把缩放复位，否则下一页会继承上一页的放大状态
        LaunchedEffect(pageIdx) { scale = 1f; offset = Offset.Zero }

        fun prev() { if (pageIdx > 0) pageIdx-- }
        fun next() { if (pageIdx < pages.lastIndex) pageIdx++ }

        // 表冠 / 音量键：和正文阅读器共用同一条回调通道，行为一致（true = 往后翻）
        DisposableEffect(Unit) {
            onRegisterTurn?.invoke { isNext -> if (isNext) next() else prev() }
            onDispose { onRegisterTurn?.invoke(null) }
        }

        Box(
            Modifier
                .fillMaxSize()
                // 双指缩放 / 放大后拖动
                .pointerInput(pageIdx) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val ns = (scale * zoom).coerceIn(1f, 4f)
                        scale = ns
                        offset = if (ns > 1.001f) offset + pan else Offset.Zero
                    }
                }
                // 单击翻页 / 显隐工具栏，双击缩放
                .pointerInput(pageIdx, scale) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.5f) { scale = 1f; offset = Offset.Zero }
                            else { scale = 2f; offset = Offset.Zero }
                        },
                        onTap = { pos ->
                            val w = size.width.toFloat()
                            when {
                                // 放大状态下点哪儿都不翻页，避免看着局部被翻走
                                scale > 1.01f -> {}
                                pos.x < w * 0.33f -> prev()
                                pos.x > w * 0.67f -> next()
                                else -> showBar = !showBar
                            }
                        }
                    )
                }
        ) {
            when {
                bitmap != null -> Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "第 ${pageIdx + 1} 页",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale, scaleY = scale,
                            translationX = offset.x, translationY = offset.y
                        )
                )

                failed -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                ) {
                    Text(
                        "这张图读不出来",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Text(
                        "原 epub 可能被移动或删除了",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(28.dp),
                        color = Color.White.copy(alpha = 0.7f),
                        strokeWidth = 2.dp
                    )
                }
            }

            /* 顶部工具栏：返回 / 书名 / 页码 */
            AnimatedVisibility(
                visible = showBar,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Surface(
                    shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp),
                    color = Color.Black.copy(alpha = 0.62f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = "返回", tint = Color.White
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                chapters.getOrNull(page.chapter)?.title.orEmpty().ifBlank { bookTitle },
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${pageIdx + 1} / ${pages.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.62f)
                            )
                        }
                        if (onSwitchToText != null) {
                            IconButton(onClick = onSwitchToText) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_auto_stories),
                                    contentDescription = "切到正文模式", tint = Color.White
                                )
                            }
                        }
                    }
                }
            }

            /* 底部：一条细进度条，比数字直观 */
            AnimatedVisibility(
                visible = showBar,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(2.dp))
                ) {
                    val frac = (pageIdx + 1f) / pages.size
                    Box(
                        Modifier
                            .fillMaxWidth(frac.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(Color.White.copy(alpha = 0.75f), RoundedCornerShape(2.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyComic(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "这本书里没有找到图片",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f)
            )
            Text(
                "可以用正文模式阅读",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 4.dp)
            )
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.White.copy(alpha = 0.12f),
                onClick = onBack,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text(
                    "返回",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/**
 * 判断"这是不是一本漫画"（纯图片书）。
 *
 * 只看采样，不扫全书 —— 一千章的漫画扫一遍要好几秒，而前几章就足够看出性质了。
 *
 * 判据是两条同时满足：
 * 1. 每章平均至少 0.8 张图（基本就是"一屏一图"）；
 * 2. 每章平均正文不到 600 字。
 *
 * 第二条很关键：像《让你改装机娘》那种"每章一张插画 + 两千五百字正文"的书**不算**漫画，
 * 它还是文字书，用正文阅读器读体验更好（图会在文章中间显示）。
 * 真正的漫画是图为主、文字只是偶尔的旁白。
 */
object ComicDetect {

    private const val SAMPLE = 12

    /**
     * 自动判定**很保守**，只认"几乎全是图"的书。
     *
     * 为什么保守：像《让你改装机娘》那种"一章一张整页图 + 两千多字正文"的书，
     * 图段只占全部段落的 4% —— 任何按占比的算法都会把它判成文字书。
     * 而它确实是文字书（那两千字才是主体），自动进漫画模式会把正文全挡掉。
     *
     * 所以自动判定只负责一类：纯图/画集（图段占比 ≥ 60% 且总共没几个字）。
     * 其余"有图但以文字为主"的书，交给用户在阅读菜单里手动切 —— 见 [hasImages]。
     */
    fun looksLikeComic(chapters: List<Chapter>): Boolean {
        if (chapters.isEmpty()) return false
        var imageParas = 0
        var allParas = 0
        var textLen = 0
        chapters.take(minOf(SAMPLE, chapters.size)).forEach { ch ->
            ch.paragraphs.forEach { p ->
                if (p.isBlank()) return@forEach
                allParas++
                if (p.startsWith(IMG_MARK)) imageParas++ else textLen += p.length
            }
        }
        if (allParas == 0 || imageParas == 0) return false
        val imgRatio = imageParas.toFloat() / allParas
        return imgRatio >= 0.6f && textLen < allParas * 120
    }

    /** 有没有插图（决定阅读菜单里要不要给"漫画模式"入口） */
    fun hasImages(chapters: List<Chapter>): Boolean =
        chapters.any { ch -> ch.paragraphs.any { parseImagePara(it) != null } }
}
