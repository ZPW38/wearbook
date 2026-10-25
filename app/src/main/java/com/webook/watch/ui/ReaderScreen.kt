package com.webook.watch.ui

import com.webook.watch.R

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.webook.watch.data.Bookmark
import com.webook.watch.data.Chapter
import com.webook.watch.data.ImageRef
import com.webook.watch.data.SearchHit
import com.webook.watch.data.Settings
import com.webook.watch.data.chapterFitsBook
import com.webook.watch.data.collectImages
import com.webook.watch.data.parseImagePara
import com.webook.watch.data.paraFitsChapter
import com.webook.watch.text.Page
import com.webook.watch.text.PageLine
import com.webook.watch.text.PagePacker
import com.webook.watch.text.LineBreaker
import com.webook.watch.text.readerPaint
import com.webook.watch.tts.Speaker
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 阅读页可能打开的面板 */
private enum class Panel { NONE, TOC, SEARCH, TYPO, OTHER, SPEAK }

@Composable
fun ReaderScreen(
    title: String,
    chapters: List<Chapter>,
    /** epub 原文件路径：插图要按需从它里面读（为 null 时插图显示占位框） */
    bookPath: String? = null,
    settings: Settings,
    speaker: Speaker,
    dark: Boolean,
    initialChapter: Int,
    initialPage: Int,
    /**
     * 精确续读位置：段落号 + 段内字符偏移（-1 = 没有，退回按页码）。
     * 比页码可靠：页码是排版产物，换个字号/行距/屏幕就漂了。
     */
    initialPara: Int = -1,
    initialCharStart: Int = 0,
    bookmarks: List<Bookmark>,
    /** 章、页、段、段内偏移、全书进度 0~1 */
    onProgress: (Int, Int, Int, Int, Float) -> Unit,
    onBack: () -> Unit,
    onMutate: (Settings.() -> Unit) -> Unit, // 改设置并立即生效
    onThemeToggle: () -> Unit,
    onBrightness: (Float) -> Unit,
    /** 屏幕方向 / 状态栏 / 常亮 / 圆屏边距这类跟窗口有关的开关，改完必须真正应用一次 */
    onApplySystem: () -> Unit,
    onAddBookmark: (Bookmark) -> Unit,
    onRemoveBookmark: (Int) -> Unit,
    onRegisterTurn: ((Boolean) -> Unit) -> Unit,  // 注册音量键翻页回调（null 表示注销）
    /**
     * 注册「立刻把进度落盘」的回调。
     * 界面被收起 / Activity 进 onStop 时调用 —— 防抖还没到点就切走或划掉任务卡，
     * 也能保证最后一次读到哪儿被写进磁盘，而不是等到下次翻开才发现回到老地方。
     */
    onRegisterFlush: ((() -> Unit)?) -> Unit = {}
) {
    var chapterIdx by remember { mutableIntStateOf(initialChapter.coerceIn(0, chapters.lastIndex.coerceAtLeast(0))) }
    var pageIdx by remember { mutableIntStateOf(initialPage) }
    var lines by remember { mutableStateOf<List<PageLine>>(emptyList()) }
    var pages by remember { mutableStateOf<List<Page>>(emptyList()) }
    /**
     * 当前 [lines] / [pages] 属于第几章。
     *
     * 切章后排版（LineBreaker）是**异步**的，中间有个窗口：章号已经换成新的，
     * 行表还是旧章的。这期间如果照常上报进度，就会存下「新章号 + 旧章段落号」的脏档案，
     * 下次打开会跳到莫名其妙的位置。进度上报前拿它对一下，不一致就宁可不报。
     */
    var laidOutChapter by remember {
        mutableIntStateOf(if (chapterFitsBook(chapters, initialChapter)) initialChapter else 0)
    }
    var showOverlay by remember { mutableStateOf(false) }
    /** 首次进入时要恢复到的精确位置（段落 + 段内偏移）；null 表示这次不用精确恢复 */
    var pendingRestore by remember {
        mutableStateOf(if (initialPara >= 0) initialPara to initialCharStart else null)
    }
    /** 底部状态栏里显示的当前时间（每分钟刷新一次就够） */
    var clock by remember { mutableStateOf("") }
    var speaking by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    /** 正在放大查看的插图（null = 没在放大）。手表屏小，正文里的图看不清细节，点开看大图 */
    var zoomImage by remember { mutableStateOf<ImageRef?>(null) }
    /**
     * 全书插图序列（按阅读顺序、已去重）。查看器打开后靠它做「上一张 / 下一张」。
     * 只在换书（chapters 变化）时算一次：只是逐段看一眼前缀，几万段也不过几毫秒。
     */
    val galleryImages = remember(chapters) { collectImages(chapters) }

    // 订阅设置变化：设置一改，本页（含各个面板）就会重新读取最新的值
    settings.rev

    // 首次进入给一次操作说明，之后不再打扰
    var tip by remember { mutableStateOf(if (settings.readerTipsShown) null else FIRST_TIP) }
    var searching by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf(listOf<SearchHit>()) }

    /** 待定位：章节跳转后要落到哪一页（para=按段落找，page=直接页码） */
    var pendingPara by remember { mutableStateOf<Int?>(null) }
    var pendingPage by remember { mutableStateOf<Int?>(null) }

    /* ---- 滑动阅读（整章连续滚动）---- */
    val scrollMode = settings.readMode == "scroll"
    val scroller = rememberLazyListState()
    /** 当前阅读到第几行（滚动模式用来算进度、续读） */
    var lineAtTop by remember { mutableIntStateOf(0) }

    val scope = rememberCoroutineScope()
    val slide = remember { Animatable(0f) }   // 翻页横向滑动动画

    val density = LocalDensity.current
    val bg = (if (dark) ReaderBackgrounds.dark else ReaderBackgrounds.light)
        .let { if (dark) it[settings.bgDark.coerceIn(it.indices)] else it[settings.bgLight.coerceIn(it.indices)] }
    val textColor = Color(bg.text)
    val fontSizePx = with(density) { settings.fontSizeOf(dark).sp.toPx() }
    val lineHeightPx = fontSizePx * settings.lineSpacingOf(dark)
    val paraGapPx = lineHeightPx * 0.6f
    val paint = remember(fontSizePx, textColor) { readerPaint(fontSizePx, textColor.toArgb()) }
    val justify = settings.justify

    /* ---- 进度：按「字符数」算，而不是按页码 ---- */
    val chapterChars = remember(chapters) {
        chapters.map { ch -> ch.paragraphs.sumOf { it.length }.coerceAtLeast(1) }
    }
    val totalChars = remember(chapterChars) { chapterChars.sum().coerceAtLeast(1) }

    /** 第 chIdx 章、第 para 段、段内第 cs 个字符 —— 占全书的百分之多少 */
    fun progressOf(chIdx: Int, para: Int, cs: Int): Float {
        var done = 0
        for (i in 0 until chIdx.coerceIn(0, chapterChars.size)) done += chapterChars[i]
        var inChapter = 0
        chapters.getOrNull(chIdx)?.let { ch ->
            for (i in 0 until para.coerceIn(0, ch.paragraphs.size)) inChapter += ch.paragraphs[i].length
        }
        return ((done + inChapter + cs).toFloat() / totalChars).coerceIn(0f, 1f)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(bg.bg))) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        // 圆屏时正文边距收一点：外层已经按短边留了安全区（上下约 7%、左右约 2.8%），
        // 这里再叠 14dp 会让本来就窄的版面更挤。方屏维持原来的 14/12。
        val padX = with(density) { (if (settings.roundScreen) 9.dp else 14.dp).toPx() }
        val padY = with(density) { (if (settings.roundScreen) 8.dp else 12.dp).toPx() }
        val textW = wPx - padX * 2
        // 底部状态栏（百分比 / 时间）占掉一行，正文相应让出空间，免得盖住字
        val showBar = settings.showStatusBar && (settings.statusPercent || settings.statusTime)
        val statusH = if (showBar) lineHeightPx * 1.15f else 0f
        val bodyH = (hPx - padY * 2 - statusH).coerceAtLeast(lineHeightPx * 3f)

        // 插图：zip 内路径 -> 解码好的位图（值是 null 表示文件不在或解码失败，画占位框）
        //
        // ⚠️ 必须有上限。缓存原本只增不减，翻完一本插图多的书（比如 1100 章、每章一张整页图的
        // 漫画 epub），一千多张 Bitmap 全常驻内存 —— 手表只有 96MB 堆，必 OOM。
        // 按屏宽解码后单张约 200KB，留 12 张够用（当前页 + 前后几页），再多纯属浪费。
        val imgCache = remember { mutableStateMapOf<String, android.graphics.Bitmap?>() }
        val imagePaint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG) }
        // 一张插图最多占版面高度的 62%，再长就等比缩小，免得一整页只有一张图
        val maxImgH = bodyH * 0.62f

        // 预取当前页用到的插图（翻页模式一页最多一两张；滑动模式看当前滚动位置附近）
        LaunchedEffect(pageIdx, lines, bookPath, textW, scrollMode, lineAtTop / 40) {
            val range = if (scrollMode) {
                (lineAtTop - 40).coerceAtLeast(0)..(lineAtTop + 120).coerceAtMost(lines.size - 1)
            } else {
                val p = pages.getOrNull(pageIdx) ?: return@LaunchedEffect
                p.start..(p.end - 1).coerceAtLeast(p.start)
            }
            for (i in range) {
                val ref = lines.getOrNull(i)?.img ?: continue
                if (imgCache.containsKey(ref.path)) continue
                val b = withContext(Dispatchers.IO) {
                    EpubImages.load(bookPath, ref, lines[i].imgW.toInt())
                }
                imgCache[ref.path] = b
                // 超出上限就淘汰最早插入的那张（LinkedHashMap 语义：keys.first 就是最旧的）
                while (imgCache.size > MAX_IMG_CACHE) {
                    val oldest = imgCache.keys.firstOrNull() ?: break
                    imgCache.remove(oldest)
                }
            }
        }

        // 排版（章节/字号/尺寸变化时重算）
        LaunchedEffect(chapterIdx, fontSizePx, settings.lineSpacingOf(dark), wPx, hPx, scrollMode, statusH) {
            val ch = chapters.getOrNull(chapterIdx) ?: return@LaunchedEffect
            launch {
                // 重排前先记下「当前停在哪个字」——改字号/行距/开关状态栏之后，
                // 排版结果会变，但那个字不会变，靠它把读者放回原处，不会莫名跳页。
                // 但只有"行表确实属于当前这一章"时才敢记：切章的这一刻 lines 还是上一章的，
                // 拿它的段落号当锚点，等于把旧章的段号安到新章头上。
                val anchor = if (laidOutChapter == chapterIdx) {
                    lines.getOrNull(
                        if (scrollMode) lineAtTop else (pages.getOrNull(pageIdx)?.start ?: 0)
                    )?.let { it.paraIndex to it.charStart }
                } else null

                val ls = LineBreaker.layout(ch.paragraphs, paint, textW, paint.textSize * 2, maxImgH)
                val ps = PagePacker.pack(ls, bodyH, lineHeightPx, paraGapPx)
                lines = ls
                pages = ps
                laidOutChapter = chapterIdx      // 行表已换成这一章的，之后才能上报进度
                // 定位优先级：段落跳转（搜索）> 页码（书签/目录）> 精确位置（续读/重排）
                // pendingRestore 要先验一下段落号是不是属于这一章 —— 存档脏了（章号与段落号
                // 来自不同的章）就丢掉它，退回页码恢复，免得跳到书中部。
                val para = pendingPara?.takeIf { paraFitsChapter(chapters, chapterIdx, it) }
                val pg = pendingPage
                val restore = pendingRestore?.takeIf { paraFitsChapter(chapters, chapterIdx, it.first) } ?: anchor
                pendingPara = null
                pendingRestore = null
                when {
                    para != null -> {
                        val li = ls.indexOfFirst { it.paraIndex == para }
                        pageIdx = if (li >= 0) ps.indexOfFirst { it.start <= li && li < it.end }.coerceAtLeast(0) else 0
                        pendingPara = null
                    }
                    pg != null -> { pageIdx = pg.coerceIn(0, (ps.size - 1).coerceAtLeast(0)); pendingPage = null }
                    restore != null -> {
                        pendingRestore = null
                        val li = findLineFor(ls, restore.first, restore.second)
                        pageIdx = if (li >= 0) ps.indexOfFirst { it.start <= li && li < it.end }.coerceAtLeast(0) else 0
                    }
                    else -> pageIdx = pageIdx.coerceIn(0, (ps.size - 1).coerceAtLeast(0))
                }
                // 滑动模式：把"第几页"换算成"第几行"，然后滚到那儿
                if (scrollMode) {
                    val targetLine = ps.getOrNull(pageIdx)?.start ?: 0
                    lineAtTop = targetLine
                    scroller.scrollToItem(targetLine / SCROLL_CHUNK, 0)
                }
            }
        }

        /**
         * 手动翻页的「计数信号」：每手动翻一次就 +1，正在跑的自动翻页计时器随之重启。
         * 自动翻页自己翻的页**不**加，否则会自己把自己的计时器打断。
         */
        var autoTick by remember { mutableIntStateOf(0) }

        /**
         * 统一切章口 —— **所有**换章的地方都必须走这里，不要就地写 `chapterIdx++`。
         *
         * 切章不只是改两个数字：
         * 1. 滑动模式的可视位置由 LazyColumn 和 [lineAtTop] 决定，旧行号不归零的话，
         *    重排跑完之前进度上报会拿旧章的行号当成新章的位置 —— 用户看到的就是
         *    「点下一章，进度莫名其妙跳一下」；
         * 2. [pendingPage] / [pendingPara] / [pendingRestore] 是上一次定位留下的指令，
         *    不清掉的话会跟着切到下一章去定位；
         * 3. 立刻 scrollToItem(0, 0) 把列表钉回顶部，同时**掐断**正在跑的惯性滚动，
         *    否则旧章的 fling 会继续把新章往下带。
         */
        fun gotoChapter(n: Int) {
            val t = n.coerceIn(0, chapters.lastIndex.coerceAtLeast(0))
            if (t == chapterIdx) return
            chapterIdx = t
            pageIdx = 0
            lineAtTop = 0
            pendingPage = null
            pendingPara = null
            pendingRestore = null
            if (scrollMode) scope.launch { scroller.scrollToItem(0, 0) }
        }

        fun goNext(auto: Boolean = false) {
            if (pages.isEmpty()) return
            if (!auto) autoTick++
            if (scrollMode) {                       // 滑动模式：往下滚一屏
                // 用固定 380ms 的线性动画（默认 spring 又慢又拖），滚完如果已经到底，
                // 自动接上下一章 —— 否则自动翻页会在章末无声无息地"失灵"（1.0.24 的坑）
                scope.launch {
                    val chAtStart = chapterIdx      // 记住出发的章，动画结束时要确认还在这一章
                    scroller.animateScrollBy(hPx * 0.92f, tween(SCROLL_TURN_MS, easing = LinearEasing))
                    // ⚠️ 必须核对 chapterIdx：动画要跑 380ms，这期间用户可能已经点了"下一章"。
                    // 早先没核对，新章（往往很短）立刻就是"滚到底"，于是**一次点击连翻两章**。
                    if (chapterIdx == chAtStart && !scroller.canScrollForward && chAtStart < chapters.size - 1) {
                        gotoChapter(chAtStart + 1)
                    }
                }
                return
            }
            scope.launch { slide.snapTo(wPx); slide.animateTo(0f, tween(220)) }
            if (pageIdx < pages.size - 1) { pageIdx++ }
            else if (chapterIdx < chapters.size - 1) gotoChapter(chapterIdx + 1)
        }
        fun goPrev() {
            if (pages.isEmpty()) return
            autoTick++
            if (scrollMode) {                       // 滑动模式：往上滚一屏
                scope.launch {
                    scroller.animateScrollBy(-hPx * 0.92f, tween(SCROLL_TURN_MS, easing = LinearEasing))
                }
                return
            }
            scope.launch { slide.snapTo(-wPx); slide.animateTo(0f, tween(220)) }
            if (pageIdx > 0) { pageIdx-- }
            else if (chapterIdx > 0) gotoChapter(chapterIdx - 1)
        }


        // 音量键翻页：把翻页函数注册给 Activity
        DisposableEffect(Unit) {
            onRegisterTurn { next -> if (next) goNext() else goPrev() }
            onDispose { onRegisterTurn { } }
        }

        // 正文：滑动模式 = 整章连续滚动；翻页模式 = 一屏一页（canvas 逐行绘制）
        if (scrollMode) {
            ScrollBody(
                lines = lines,
                paint = paint,
                lineHeightPx = lineHeightPx,
                paraGapPx = paraGapPx,
                padX = padX,
                padY = padY,
                textW = textW,
                justify = justify,
                imgCache = imgCache,
                imagePaint = imagePaint,
                scroller = scroller,
                onLineAtTop = { ln ->
                    lineAtTop = ln
                    // 同步页码：菜单里的进度、书签、朗读都还按"页"来
                    val pi = pages.indexOfFirst { it.start <= ln && ln < it.end }
                    if (pi >= 0 && pi != pageIdx) pageIdx = pi
                },
                tapPaging = settings.tapPaging,
                onTapCenter = { showOverlay = !showOverlay },
                onPageDown = { goNext() },
                onPageUp = { goPrev() },
                hasNextChapter = chapterIdx < chapters.size - 1,
                onNextChapter = { gotoChapter(chapterIdx + 1) },
                onImageTap = { zoomImage = it },
                bottomInset = statusH
            )
        }

        val drawModifier = Modifier
            .fillMaxSize()
            .drawWithCache {
                onDrawBehind {
                    val page = pages.getOrNull(pageIdx) ?: return@onDrawBehind
                    val canvas = drawContext.canvas.nativeCanvas
                    canvas.save()
                    canvas.translate(slide.value, 0f)   // 翻页横向滑动动画
                    var y = -paint.fontMetrics.ascent
                    for (i in page.start until page.end) {
                        val ln = lines.getOrNull(i) ?: break
                        val ref = ln.img
                        if (ref != null) {   // 插图行：图上边 = 该行行顶
                            drawInlineImage(
                                canvas, imgCache[ref.path], ln, imagePaint,
                                padX, padY + y + paint.fontMetrics.ascent, textW
                            )
                            y += ln.height(lineHeightPx)
                            continue
                        }
                        val x = if (i == page.start && !ln.paraStart) 0f else ln.indent
                        val lastOfPara = (i + 1 >= lines.size) || lines[i + 1].paraStart
                        drawLine(canvas, paint, ln.text, padX + x, padY + y, textW, justify && !lastOfPara)
                        y += lineHeightPx
                    }
                    canvas.restore()
                }
            }
            .pointerInput(Unit) {
                // 命中测试：图片所在的**整行**（左右到版心边缘）再外扩 8dp 都算点中。
                // 以前只认图片自己的那个矩形，点在图边上的留白就被当成"点了空白"，
                // 接着走「点中间 = 开菜单」——用户看到的就是"我明明点的是图，怎么弹菜单"。
                val tol = 8.dp.toPx()
                fun hitImageAt(x: Float, y: Float): ImageRef? {
                    val pg = pages.getOrNull(pageIdx) ?: return null
                    return imageRectsInRange(
                        lines, pg.start, pg.end, padY, padX, textW,
                        lineHeightPx, 0f, paint
                    ).firstOrNull { (_, r) ->
                        x >= padX - tol && x <= padX + textW + tol &&
                            y >= r.top - tol && y <= r.bottom + tol
                    }?.first
                }
                detectTapGestures(
                    // 手表上"点一下"很容易被判成长按（按住超过 500ms）。以前长按一律开菜单，
                    // 所以点插图经常变成弹菜单；现在长按图 = 看图，只有按空白才开菜单。
                    onLongPress = { pos ->
                        val hit = hitImageAt(pos.x, pos.y)
                        if (hit != null) { zoomImage = hit } else showOverlay = true
                    },
                    onTap = { offset ->
                        val hit = hitImageAt(offset.x, offset.y)
                        if (hit != null) { zoomImage = hit; return@detectTapGestures }

                        val w = size.width
                        if (!settings.tapPaging) { showOverlay = !showOverlay; return@detectTapGestures }
                        when {
                            offset.x < w * 0.32f -> goPrev()
                            offset.x > w * 0.68f -> goNext()
                            else -> showOverlay = !showOverlay
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    if (dragAmount < -30) goNext() else if (dragAmount > 30) goPrev()
                }
            }

        if (!scrollMode) Box(drawModifier)

        /* ---- 进度：定位到「第几段的第几个字」，防抖落盘 + 离开前强制落盘 ---- */

        // 当前读到哪儿：翻页模式看本页第一行，滚动模式看屏幕最上面那一行
        val lineIdxNow = if (scrollMode) lineAtTop else (pages.getOrNull(pageIdx)?.start ?: 0)
        val lineNow = lines.getOrNull(lineIdxNow.coerceIn(0, (lines.size - 1).coerceAtLeast(0)))
        val paraNow = lineNow?.paraIndex ?: 0
        val charNow = lineNow?.charStart ?: 0
        val fracNow = progressOf(chapterIdx, paraNow, charNow)

        // 用最新值包一层：Activity 进 onStop / 界面被收起时，调用的永远是"此刻"的位置
        val flushRef = rememberUpdatedState<() -> Unit>({
            if (laidOutChapter == chapterIdx) onProgress(chapterIdx, pageIdx, paraNow, charNow, fracNow)
        })
        DisposableEffect(Unit) {
            onRegisterFlush { flushRef.value() }
            onDispose {
                flushRef.value()          // 离开阅读页立刻落盘，不等防抖
                onRegisterFlush(null)
            }
        }

        LaunchedEffect(chapterIdx, pageIdx, paraNow, charNow, laidOutChapter) {
            delay(500)                    // 防抖：一路狂翻时只在停下来之后写一次
            if (laidOutChapter == chapterIdx) onProgress(chapterIdx, pageIdx, paraNow, charNow, fracNow)
        }

        /* ---- 底部状态栏：百分比 + 时间，可在设置里关掉 ---- */
        LaunchedEffect(showBar, settings.statusTime) {
            if (!showBar || !settings.statusTime) return@LaunchedEffect
            while (true) {
                clock = clockText()
                delay(15_000)             // 每 15 秒对一次表，显示到分钟够用
            }
        }
        if (showBar) {
            val statusFontSp = (settings.fontSizeOf(dark) * 0.7f).sp
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 4.dp, top = 1.dp),
                horizontalArrangement = when {
                    settings.statusPercent && settings.statusTime -> Arrangement.SpaceBetween
                    settings.statusPercent -> Arrangement.Start
                    else -> Arrangement.End
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (settings.statusPercent) {
                    Text(
                        text = "${(fracNow * 100).roundToInt()}%",
                        color = textColor.copy(alpha = 0.55f),
                        fontSize = statusFontSp,
                        maxLines = 1
                    )
                }
                if (settings.statusTime) {
                    Text(
                        text = clock,
                        color = textColor.copy(alpha = 0.55f),
                        fontSize = statusFontSp,
                        maxLines = 1
                    )
                }
            }
        }

        // 朗读：读完本页自动翻页
        DisposableEffect(Unit) {
            speaker.onDone = { if (speaking) goNext() }
            onDispose { speaker.onDone = null; speaker.stop() }
        }
        LaunchedEffect(speaking, chapterIdx, pageIdx, pages.size) {
            if (speaking) {
                val page = pages.getOrNull(pageIdx)
                val text = page?.let { p ->
                    (p.start until p.end).joinToString("") { lines[it].text }
                } ?: ""
                if (text.isNotBlank()) speaker.speak(text)
            }
        }

        /**
         * 自动翻页：自己转圈，不靠翻页动作来"续命"。
         *
         * 旧写法把 chapterIdx / pageIdx 也当 key，靠"翻了一页"重启计时器 ——
         * 但**滑动模式滚 0.92 屏不一定跨过页的边界**，pageIdx 没变就不会重启，自动翻页就停住了。
         * 现在改成 while 循环：只有改间隔、开/关朗读、手动翻页（autoTick 自增）才会重新计时。
         */
        LaunchedEffect(settings.autoSeconds, speaking, autoTick) {
            val sec = settings.autoSeconds
            if (sec > 0 && !speaking) {
                while (true) {
                    delay(sec * 1000L)
                    goNext(auto = true)
                }
            }
        }

        // 提示气泡 3 秒后自动消失
        LaunchedEffect(tip) {
            if (tip != null) { delay(3200); tip = null; settings.readerTipsShown = true }
        }

        // 遮罩层：顶部章节名 + 左侧亮度 + 底部菜单
        AnimatedVisibility(
            visible = showOverlay && panel == Panel.NONE,
            enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { it / 3 },
            exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { it / 3 }
        ) {
            ReaderOverlay(
                title = title,
                chapterTitle = chapters.getOrNull(chapterIdx)?.title.orEmpty(),
                chapterIdx = chapterIdx,
                chapterCount = chapters.size,
                pageIdx = pageIdx,
                pageCount = pages.size,
                speaking = speaking,
                autoSeconds = settings.autoSeconds,
                brightness = settings.brightness,
                round = settings.roundScreen,
                onBack = onBack,
                onOpenToc = { panel = Panel.TOC },
                onPrevChapter = { gotoChapter(chapterIdx - 1) },
                onNextChapter = { gotoChapter(chapterIdx + 1) },
                onSearch = { panel = Panel.SEARCH },
                onAutoPage = {
                    // 3 / 5 / 10 / 20 / 30 / 关闭 循环；1、2 秒这种极限速度去设置里用滑杆调。
                    // 以前最快只有 5 秒、最慢磨到 60 秒，观感是"自动翻页像没生效"。
                    val next = when (settings.autoSeconds) {
                        0 -> 3
                        1, 2 -> 3
                        3 -> 5
                        5 -> 10
                        10 -> 20
                        20 -> 30
                        else -> 0
                    }
                    onMutate { autoSeconds = next }
                    tip = if (next == 0) "自动翻页已关闭" else "自动翻页：每 ${next} 秒翻一页"
                },
                onBookmark = {
                    val excerpt = pages.getOrNull(pageIdx)?.let { p ->
                        (p.start until p.end).joinToString("") { lines[it].text }
                    }.orEmpty().take(24)
                    onAddBookmark(
                        Bookmark(chapterIdx, pageIdx, chapters.getOrNull(chapterIdx)?.title.orEmpty(), excerpt)
                    )
                    tip = "已把这一页加入书签（目录面板里可查看）"
                },
                onThemeToggle = onThemeToggle,
                onSpeak = { speaking = !speaking },
                onSpeakSettings = { panel = Panel.SPEAK },
                onTypo = { panel = Panel.TYPO },
                onOther = { panel = Panel.OTHER },
                onBrightnessChange = { v, follow ->
                    onMutate { brightness = if (follow) -1f else v }
                    onBrightness(if (follow) -1f else v)
                },
                onDismiss = { showOverlay = false },
                onTip = { tip = it },
                bgColor = Color(bg.bg),
                fgColor = textColor
            )
        }

        // 提示气泡
        tip?.let { t ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 88.dp)
                ) {
                    Text(
                        t, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(10.dp),
                        textAlign = TextAlign.Start
                    )
                }
            }
        }

        // 各类面板
        when (panel) {
            Panel.TOC -> TocPanel(
                chapters = chapters,
                bookmarks = bookmarks,
                chapterIdx = chapterIdx,
                onJumpChapter = { i -> gotoChapter(i) },
                onJumpBookmark = { b -> gotoChapter(b.chapter); pendingPage = b.page },
                onRemoveBookmark = onRemoveBookmark,
                onClose = { panel = Panel.NONE }
            )
            Panel.SEARCH -> SearchPanel(
                hits = hits,
                searching = searching,
                onQuery = { q ->
                    if (q.isBlank()) { hits = emptyList() } else {
                        searching = true
                        scope.launch(Dispatchers.Default) {
                            val out = ArrayList<SearchHit>()
                            loop@ for (ci in chapters.indices) {
                                val ch = chapters[ci]
                                for (pi in ch.paragraphs.indices) {
                                    val p = ch.paragraphs[pi]
                                    if (parseImagePara(p) != null) continue   // 插图段落没有可搜的文字
                                    val at = p.indexOf(q)
                                    if (at >= 0) {
                                        val s = (at - 12).coerceAtLeast(0)
                                        val e = (at + q.length + 24).coerceAtMost(p.length)
                                        out.add(
                                            SearchHit(
                                                chapter = ci, chapterTitle = ch.title, para = pi, offset = at,
                                                snippet = (if (s > 0) "…" else "") + p.substring(s, e)
                                            )
                                        )
                                        if (out.size >= 200) break@loop
                                    }
                                }
                            }
                            withContext(Dispatchers.Main) { hits = out; searching = false }
                        }
                    }
                },
                onJump = { h -> gotoChapter(h.chapter); pendingPara = h.para },
                onClose = { panel = Panel.NONE }
            )
            Panel.TYPO -> PanelShell("排版设置（界面）", onClose = { panel = Panel.NONE }) {
                TypoPanel(settings = settings, dark = dark, onMutate = onMutate, onClose = { panel = Panel.NONE })
            }
            Panel.OTHER -> PanelShell("其它设置", onClose = { panel = Panel.NONE }) {
                OtherPanel(
                    settings = settings,
                    onMutate = onMutate,
                    onApplySystem = onApplySystem,
                    onClose = { }
                )
            }
            Panel.SPEAK -> PanelShell("朗读设置", onClose = { panel = Panel.NONE }) {
                SpeakPanel(
                    settings = settings,
                    speakerAvailable = speaker.available,
                    speaking = speaking,
                    onMutate = onMutate,
                    onToggleSpeak = { speaking = !speaking },
                    onClose = { panel = Panel.NONE }
                )
            }
            Panel.NONE -> Unit
        }

        // 插图放大查看：盖在最上层（连菜单也盖住，专心看图）
        // 传入全书插图序列 + 点开的这张在其中的位置，查看器内部就能左右切换。
        // 极少数情况（图不在章节段落里，理论走不到）退化成「只有这一张」，界面自动隐藏切换控件。
        zoomImage?.let { ref ->
            val list = if (galleryImages.isEmpty()) listOf(ref) else galleryImages
            val start = list.indexOfFirst { it.path == ref.path }.coerceAtLeast(0)
            ImageZoomView(
                bookPath = bookPath,
                images = list,
                startIndex = start,
                onClose = { zoomImage = null },
            )
        }
    }
}

/** 插图行的占位框画笔（图还没解码好 / 原文件不在时用） */
private val imgPlaceholderFill by lazy {
    android.graphics.Paint().apply {
        color = 0x1F888888
        style = android.graphics.Paint.Style.FILL
    }
}
private val imgPlaceholderStroke by lazy {
    android.graphics.Paint().apply {
        color = 0x55888888
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
}

/**
 * 画一行插图：按排版时算好的尺寸水平居中。
 * 位图还没加载好或者原文件不在了，就画一个淡框占位，避免这一行变成莫名其妙的空白。
 */
private fun drawInlineImage(
    canvas: android.graphics.Canvas,
    bmp: android.graphics.Bitmap?,
    line: PageLine,
    paint: android.graphics.Paint,
    padX: Float,
    top: Float,
    contentW: Float
) {
    val w = line.imgW
    val h = line.imgH
    if (w <= 0f || h <= 0f) return
    val left = padX + (contentW - w) / 2f
    if (bmp != null) {
        canvas.drawBitmap(bmp, null, android.graphics.RectF(left, top, left + w, top + h), paint)
    } else {
        val r = android.graphics.RectF(left, top, left + w, top + h)
        canvas.drawRoundRect(r, 10f, 10f, imgPlaceholderFill)
        canvas.drawRoundRect(r, 10f, 10f, imgPlaceholderStroke)
    }
}

/** 逐字绘制实现两端对齐；不需要对齐时整行一次画完 */
private fun drawLine(
    canvas: android.graphics.Canvas,
    paint: android.text.TextPaint,
    text: String,
    x: Float,
    y: Float,
    width: Float,
    justify: Boolean
) {
    if (!justify || text.length < 2) { canvas.drawText(text, x, y, paint); return }
    val used = paint.measureText(text)
    val room = width - used
    if (room <= 1f) { canvas.drawText(text, x, y, paint); return }
    val gap = room / (text.length - 1)
    var cx = x
    for (ch in text) {
        val s = ch.toString()
        canvas.drawText(s, cx, y, paint)
        cx += paint.measureText(s) + gap
    }
}

private const val FIRST_TIP =
    "点屏幕中间打开菜单：底部按钮都能长按看说明。左右两侧点击翻页，目录/搜索在菜单里。"

/** 滑动阅读：正文按每这么多行切成一块渲染（块之间无缝，视觉上是连续滚动） */
private const val SCROLL_CHUNK = 24

/**
 * 滑动模式「翻一屏」的动画时长（毫秒）。
 *
 * 以前用的是 animateScrollBy 的默认 spring，惯性大、收尾拖沓，一屏能磨掉一秒多；
 * 换成固定时长的线性 tween，翻页干脆、速度可控，自动翻页时才跟得上设定的秒数。
 */
private const val SCROLL_TURN_MS = 380

/**
 * 滑动阅读（整章连续上下滚动）。
 * 用 LazyColumn 分块渲染，只绘制可见的几块 —— 长章节也不会一帧画几千行。
 * 块与块之间不留空行，所以看上去就是一篇连续的文章。
 */
@Composable
private fun ScrollBody(
    lines: List<PageLine>,
    paint: android.text.TextPaint,
    lineHeightPx: Float,
    paraGapPx: Float,
    padX: Float,
    padY: Float,
    textW: Float,
    justify: Boolean,
    imgCache: Map<String, android.graphics.Bitmap?>,
    imagePaint: android.graphics.Paint,
    scroller: LazyListState,
    onLineAtTop: (Int) -> Unit,
    tapPaging: Boolean,
    onTapCenter: () -> Unit,
    onPageDown: () -> Unit,
    onPageUp: () -> Unit,
    hasNextChapter: Boolean,
    onNextChapter: () -> Unit,
    /** 点到了正文插图（放大查看） */
    onImageTap: (ImageRef) -> Unit = {},
    /** 底部状态栏占掉的高度，滚动内容要给它让位，否则最后一行被压住 */
    bottomInset: Float = 0f
) {
    val density = LocalDensity.current
    val chunks = remember(lines) { lines.chunked(SCROLL_CHUNK) }
    val topPad = with(density) { padY.toDp() }
    val bottomPad = with(density) { (padY + bottomInset).toDp() }

    Box(
        Modifier.fillMaxSize().pointerInput(tapPaging) {
            // 命中测试：图片所在整行（左右到版心边缘）+ 8dp 容差都算点中。
            // 滚动模式下要先把屏幕坐标换算到「第几块的第几行」：用可见项的偏移，
            // chunk 的起始行号就是它在全书里的行号。
            val tol = 8.dp.toPx()
            fun hitImageAt(x: Float, y: Float): ImageRef? {
                val info = scroller.layoutInfo
                for (item in info.visibleItemsInfo) {
                    val chunk = chunks.getOrNull(item.index) ?: continue
                    if (y < item.offset || y >= item.offset + item.size) continue
                    val startG = item.index * SCROLL_CHUNK
                    val r = imageRectsInRange(
                        lines, startG, startG + chunk.size,
                        item.offset.toFloat(), padX, textW,
                        lineHeightPx, paraGapPx, paint
                    ).firstOrNull { (_, rect) ->
                        x >= padX - tol && x <= padX + textW + tol &&
                            y >= rect.top - tol && y <= rect.bottom + tol
                    }?.first
                    if (r != null) return r
                }
                return null
            }
            detectTapGestures(
                // 长按图 = 看图；长按空白才是开关菜单（手表上点击容易被判成长按）
                onLongPress = { pos ->
                    val hit = hitImageAt(pos.x, pos.y)
                    if (hit != null) onImageTap(hit) else onTapCenter()
                },
                onTap = { offset ->
                    val hit = hitImageAt(offset.x, offset.y)
                    if (hit != null) { onImageTap(hit); return@detectTapGestures }

                    val w = size.width
                    if (!tapPaging) { onTapCenter(); return@detectTapGestures }
                    when {
                        offset.x < w * 0.28f -> onPageUp()
                        offset.x > w * 0.72f -> onPageDown()
                        else -> onTapCenter()
                    }
                }
            )
        }
    ) {
        LazyColumn(
            state = scroller,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = topPad, bottom = bottomPad)
        ) {
            itemsIndexed(chunks) { ci, chunk ->
                val startGlobal = ci * SCROLL_CHUNK
                var h = 0f
                chunk.forEachIndexed { j, ln ->
                    if (startGlobal + j > 0 && ln.paraStart) h += paraGapPx
                    h += ln.height(lineHeightPx)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(with(density) { h.toDp() })
                        .drawWithCache {
                            onDrawBehind {
                                val canvas = drawContext.canvas.nativeCanvas
                                var y = -paint.fontMetrics.ascent
                                chunk.forEachIndexed { j, ln ->
                                    val gi = startGlobal + j
                                    if (gi > 0 && ln.paraStart) y += paraGapPx
                                    val ref = ln.img
                                    if (ref != null) {
                                        drawInlineImage(
                                            canvas, imgCache[ref.path], ln, imagePaint,
                                            padX, y + paint.fontMetrics.ascent, textW
                                        )
                                        y += ln.height(lineHeightPx)
                                        return@forEachIndexed
                                    }
                                    val x = if (ln.paraStart) ln.indent else 0f
                                    val lastOfPara = (gi + 1 >= lines.size) || lines[gi + 1].paraStart
                                    drawLine(canvas, paint, ln.text, padX + x, y, textW, justify && !lastOfPara)
                                    y += lineHeightPx
                                }
                            }
                        }
                )
            }
            if (hasNextChapter) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            onClick = onNextChapter
                        ) {
                            Text(
                                "下一章 ▶", style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        // 把"当前顶部是第几行"报给外层（进度、续读位置）
        LaunchedEffect(scroller, chunks.size) {
            snapshotFlow { scroller.firstVisibleItemIndex to scroller.firstVisibleItemScrollOffset }
                .collect { (i, off) ->
                    val line = i * SCROLL_CHUNK + (off / lineHeightPx).toInt()
                    onLineAtTop(line.coerceIn(0, (lines.size - 1).coerceAtLeast(0)))
                }
        }
    }
}

/* ============================================================
 * 遮罩层：顶部 + 左侧亮度 + 底部菜单（图标 + 文字）
 * ============================================================ */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ReaderOverlay(
    title: String,
    chapterTitle: String,
    chapterIdx: Int,
    chapterCount: Int,
    pageIdx: Int,
    pageCount: Int,
    speaking: Boolean,
    autoSeconds: Int,
    brightness: Float,
    /** 圆屏手表：标题居中、图标让位给文字 */
    round: Boolean,
    onBack: () -> Unit,
    onOpenToc: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSearch: () -> Unit,
    onAutoPage: () -> Unit,
    onBookmark: () -> Unit,
    onThemeToggle: () -> Unit,
    onSpeak: () -> Unit,
    onSpeakSettings: () -> Unit,
    onTypo: () -> Unit,
    onOther: () -> Unit,
    onBrightnessChange: (Float, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onTip: (String) -> Unit,
    bgColor: Color,
    fgColor: Color
) {
    val frac = if (chapterCount > 0) (chapterIdx + (pageIdx + 1f) / pageCount.coerceAtLeast(1)) / chapterCount else 0f
    // 工具栏不给 MaterialTheme 决定颜色，直接用阅读底色的对比色推算 —— 深浅主题下都一定看得清
    val barColor = androidx.compose.ui.graphics.lerp(bgColor, fgColor, 0.12f)
    val dimColor = fgColor.copy(alpha = 0.78f)
    // 只在上/下加两条工具栏，中间正文保持原样可见（原来是整屏 94% 遮罩，夜间会把正文压成一片黑）
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { onDismiss() }
        )

        Surface(
            shape = RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp),
            color = barColor,
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
        ) {
            // 顶部：返回 / 书名·章节名（点一下进目录）/ 说明
            Row(
                Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = "返回",
                        tint = if (round) fgColor.copy(alpha = 0.72f) else fgColor
                    )
                }
                Column(
                    Modifier.weight(1f).combinedClickable(
                        onClick = onOpenToc,
                        onLongClick = { onTip("章节名：点一下打开目录（可跳章、看书签）") }
                    )
                ) {
                    // 圆屏：顶部两侧是弧形，标题靠左会显得歪；居中后正好落在圆弧之间的视觉中轴上
                    Text(
                        chapterTitle.ifBlank { "第 ${chapterIdx + 1} 章" },
                        style = MaterialTheme.typography.titleSmall,
                        color = fgColor,
                        textAlign = if (round) TextAlign.Center else TextAlign.Start,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        title, style = MaterialTheme.typography.labelSmall,
                        color = dimColor,
                        textAlign = if (round) TextAlign.Center else TextAlign.Start,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onOpenToc) {
                    Icon(
                        painter = painterResource(R.drawable.ic_menu_book),
                        contentDescription = "目录",
                        tint = if (round) fgColor.copy(alpha = 0.72f) else fgColor
                    )
                }
            }

        }

        // 底部工具栏
        Surface(
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            color = barColor,
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // 上一章 · 页数进度 · 下一章
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextIconButton(
                        icon = R.drawable.ic_chevron_left, label = "上一章",
                        tip = "上一章：跳到上一章开头", onClick = onPrevChapter, onTip = onTip, tint = fgColor
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            "第 ${chapterIdx + 1}/$chapterCount 章 · 第 ${pageIdx + 1}/$pageCount 页 · ${(frac * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = dimColor,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        LinearProgressIndicator(
                            progress = frac.coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth().height(5.dp),
                            color = fgColor,
                            trackColor = fgColor.copy(alpha = 0.22f)
                        )
                    }
                    TextIconButton(
                        icon = R.drawable.ic_chevron_right, label = "下一章",
                        tip = "下一章：跳到下一章开头", onClick = onNextChapter, onTip = onTip, tint = fgColor
                    )
                }

                // 4 个圆形按钮：搜索 / 自动翻页 / 书签 / 夜间模式
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextIconButton(
                        icon = R.drawable.ic_search, label = "搜索",
                        tip = "全文搜索：在当前这本书已缓存的章节里找关键词，点结果直接跳到那一页（只搜本机内容）",
                        onClick = onSearch, onTip = onTip, tint = fgColor
                    )
                    TextIconButton(
                        icon = R.drawable.ic_autoplay,
                        label = if (autoSeconds > 0) "自动${autoSeconds}s" else "自动翻页",
                        tip = "自动翻页：点一下在 3/5/10/20/30 秒与关闭之间切换（1~30 秒任意值在设置里调）；滑动模式下自动往下滚一屏，滚到章末会自己接下一章",
                        onClick = onAutoPage, onTip = onTip, tint = fgColor
                    )
                    TextIconButton(
                        icon = R.drawable.ic_bookmark, label = "书签",
                        tip = "书签：把当前这一页记下来，在「目录」面板里查看、跳转，长按可删除",
                        onClick = onBookmark, onTip = onTip, tint = fgColor
                    )
                    TextIconButton(
                        icon = R.drawable.ic_dark_mode, label = "夜间",
                        tip = "夜间模式：在深色和浅色背景之间切换；具体底色可以在「界面」里换",
                        onClick = onThemeToggle, onTip = onTip, tint = fgColor
                    )
                }

                // 4 个按钮：目录 / 朗读 / 界面 / 设置
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextIconButton(
                        icon = R.drawable.ic_toc, label = "目录",
                        tip = "目录：列出所有章节，点一下跳章；同一界面还能看书签",
                        onClick = onOpenToc, onTip = onTip, tint = fgColor
                    )
                    TextIconButton(
                        icon = if (speaking) R.drawable.ic_pause else R.drawable.ic_record_voice_over,
                        label = if (speaking) "朗读中" else "朗读",
                        tip = "朗读：单击开始朗读本页，读完自动翻页；长按进入朗读设置（语速等）",
                        onClick = onSpeak, onLongClick = onSpeakSettings, onTip = onTip, tint = fgColor
                    )
                    TextIconButton(
                        icon = R.drawable.ic_format_size, label = "界面",
                        tip = "界面（排版）：阅读模式（翻页/滑动）、字号、行距、背景色、两端对齐都在这",
                        onClick = onTypo, onTip = onTip, tint = fgColor
                    )
                    TextIconButton(
                        icon = R.drawable.ic_tune, label = "设置",
                        tip = "设置：其它设置 —— 屏幕方向、常亮、音量键翻页、点击翻页等，可上下滚动",
                        onClick = onOther, onTip = onTip, tint = fgColor
                    )
                }
            }
        }
    }
}

/** 图标 + 文字的菜单按钮；长按显示功能说明 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RowScope.TextIconButton(
    icon: Int,
    label: String,
    tip: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onTip: (String) -> Unit,
    tint: Color = androidx.compose.ui.graphics.Color.Unspecified
) {
    Column(
        // ⚠️ 宽度用 weight 平分，**不要写死 56.dp**：
        // 320px 的屏在 density 240 下只有 213dp 宽，扣掉圆屏安全边距和行内边距只剩约 180dp，
        // 而 4 × 56 = 224dp —— 固定宽度必然溢出，最后一列会被挤出屏幕、第二行整行看不见
        // （模拟器实测就是这样）。平分之后任何宽度都放得下，213/4 ≈ 53dp 仍够 44dp 触控区。
        Modifier
            .weight(1f)
            .height(48.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { if (onLongClick != null) onLongClick() else onTip(tip) }
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (tint == androidx.compose.ui.graphics.Color.Unspecified) {
            Icon(painter = painterResource(icon), contentDescription = label, modifier = Modifier.size(22.dp))
        } else {
            Icon(painter = painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        }
        Text(
            label, style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/** 插图缓存上限：当前页 + 前后若干页，够翻页不卡，又不至于把内存吃穿 */
private const val MAX_IMG_CACHE = 12

/** 当前时间，形如 14:35（状态栏用） */
private fun clockText(): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

/**
 * 在排版好的行里，找「第 para 段的第 cs 个字符」落在哪一行。
 *
 * 阅读进度存的是「段 + 段内偏移」而不是页码，所以换字号、换行距、开关状态栏之后
 * 行会重新切分，但同一个字还在，靠这个函数就能把读者放回原来的地方。
 * 找不到（比如字号变得太离谱）就逐级退让：该段最后一行 → 该段第一行。
 */
internal fun findLineFor(lines: List<PageLine>, para: Int, cs: Int): Int {
    if (lines.isEmpty() || para < 0) return -1
    val inside = lines.indexOfFirst {
        it.paraIndex == para && it.charStart <= cs && cs < it.charStart + it.text.length.coerceAtLeast(1)
    }
    if (inside >= 0) return inside
    val before = lines.indexOfLast { it.paraIndex == para && it.charStart <= cs }
    if (before >= 0) return before
    return lines.indexOfFirst { it.paraIndex == para }
}

/**
 * 算出 [from, to) 这段行里所有插图的绘制矩形。
 *
 * 存在的意义是「绘制」和「点击命中」必须用同一套坐标，否则会出现"看着点中了却没反应"。
 * 所以这里的累加逻辑与两处 onDrawBehind 严格一致：
 *   - 翻页模式：y 从 -ascent 起，每行 += lineHeight；插图行 += 它自己的高度（**不加段间距**，因为翻页绘制也没加）
 *   - 滑动模式：同样，但**要加段间距**（滚动绘制加了），所以 paraGap 传 paraGapPx
 *
 * @param baseY 这段内容「第一行行顶」对应的屏幕 y（翻页传 padY；滚动传 LazyColumn item 的 offset）
 * @param paraGap 段间距，翻页模式传 0
 */
private fun imageRectsInRange(
    lines: List<PageLine>,
    from: Int,
    to: Int,
    baseY: Float,
    padX: Float,
    textW: Float,
    lineHeightPx: Float,
    paraGap: Float,
    paint: android.graphics.Paint
): List<Pair<ImageRef, android.graphics.RectF>> {
    val out = ArrayList<Pair<ImageRef, android.graphics.RectF>>()
    if (from >= to || lines.isEmpty()) return out
    val ascent = paint.fontMetrics.ascent
    var y = -ascent
    for (i in from until to) {
        val ln = lines.getOrNull(i) ?: break
        if (paraGap > 0f && i > 0 && ln.paraStart) y += paraGap
        val ref = ln.img
        if (ref != null) {
            if (ln.imgW > 0f && ln.imgH > 0f) {
                val left = padX + (textW - ln.imgW) / 2f
                val top = baseY + y + ascent
                out.add(ref to android.graphics.RectF(left, top, left + ln.imgW, top + ln.imgH))
            }
            y += ln.height(lineHeightPx)
        } else {
            y += lineHeightPx
        }
    }
    return out
}

/**
 * 插图放大查看层。
 *
 * 手表屏幕小，正文里的插图最多只占版面 62%，细节（地图、示意图、族谱）根本看不清。
 * 点一下图就撑满屏幕看，**单指拖动**平移看局部，再点一下退出 —— 全程不需要双指缩放，
 * 因为手表上双指手势本来就很难做出来。
 *
 * 1.0.24 起支持**多图连看**：传入整本书的插图序列 + 点开的那张的位置，
 * 顶部给「‹ 3 / 12 ›」的切换条，左右滑动也能换图。
 * 只有一张图时切换条整体不出现，界面与以前完全一致。
 *
 * 这里会**按更高的分辨率重新解码一次**：正文里那张是按正文宽度降采样的，直接放大是糊的。
 */
@Composable
private fun ImageZoomView(
    bookPath: String?,
    images: List<ImageRef>,
    startIndex: Int,
    onClose: () -> Unit,
) {
    val total = images.size
    /** 当前看的是第几张（0 起） */
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, (total - 1).coerceAtLeast(0))) }
    val ref = images.getOrNull(index) ?: return

    /**
     * 正在显示的那张位图。
     * 切图是异步解码（要回 zip 里现读），为了不闪白屏，**先接着显示上一张**，新图就绪再换上去。
     */
    var shown by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var shownPath by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }

    var offset by remember { mutableStateOf(Offset.Zero) }
    // 1f = 撑满屏幕宽度；>1 表示用户手动放大；封顶 3f 防止把手表 96MB 堆吃爆
    var scale by remember { mutableFloatStateOf(1f) }
    // 提示只在本次查看里出现一次；以前是「按 ref 记」，切一张图就重弹一次，很吵
    var showHint by remember { mutableStateOf(true) }

    /** 新图淡入一下，切换才有「翻过去」的连贯感，而不是硬切 */
    val reveal = remember { Animatable(1f) }
    val swipePx = with(LocalDensity.current) { 36.dp.toPx() }

    fun goTo(target: Int) {
        if (target !in 0 until total || target == index) {
            return
        }
        index = target
    }

    LaunchedEffect(ref.path, bookPath) {
        loading = true
        failed = false
        val b = withContext(Dispatchers.IO) {
            // 1000px 足够手表屏放大看，又不至于把 96MB 的堆吃满
            EpubImages.load(bookPath, ref, 1000)
        }
        if (b != null) {
            shown = b
            shownPath = ref.path
        } else {
            failed = true
        }
        loading = false
    }
    // 换图只复位平移，缩放倍数保留：连看几张图时「我要放大看细节」这个意图是连续的
    LaunchedEffect(index) { offset = Offset.Zero }
    LaunchedEffect(shownPath) {
        if (shownPath != null) {
            reveal.snapTo(0.55f)
            reveal.animateTo(1f, tween(200))
        }
    }
    LaunchedEffect(Unit) {
        delay(3000)
        showHint = false
    }

    // 兜底：少数手表有物理侧键，桌面 / 投屏模式下也有系统返回手势。
    // 主入口是下面的返回按钮，这里只是多一条退路。
    BackHandler { onClose() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f))
    ) {
        // shown 可能还是上一张（新图正在解码），这样切换过程中画面不会空白；
        // 这张图确实坏了（failed）就不再顶着上一张骗人，直接进下面的「插图不可用」
        val img = if (failed) null else shown
        if (img != null) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = reveal.value }
                // 拖动：放大态是平移看图；未放大时攒横移量，松手超过阈值就切上一张 / 下一张。
                // detectDragGestures 用 requireUnconsumed=false 起手，所以和下面的点击检测可以共存。
                .pointerInput(ref.path) {
                    var swipe = 0f
                    detectDragGestures(
                        onDragStart = { swipe = 0f },
                        onDrag = { change, drag ->
                            change.consume()
                            if (scale > 1.001f) {
                                offset = Offset(offset.x + drag.x, offset.y + drag.y)
                            } else {
                                swipe += drag.x
                            }
                        },
                        onDragEnd = {
                            if (scale <= 1.001f && abs(swipe) > swipePx) {
                                if (swipe < 0f) goTo(index + 1) else goTo(index - 1)
                            }
                        }
                    )
                }
                .pointerInput(ref.path) {
                    // 只有在未放大（scale==1）时点一下才关闭；放大后点一下留给看图，
                    // 避免看图时误触闪退出界面。放大态下只能靠「返回」按钮退出。
                    detectTapGestures { if (scale <= 1.001f) onClose() }
                }
            ) {
                val iw = img.width.toFloat()
                val ih = img.height.toFloat()
                if (iw <= 0f || ih <= 0f) return@Canvas
                // 撑满屏幕宽度 = 1f；再乘 scale 即为用户手动放大的倍率
                val k = (size.width / iw) * scale
                val dw = iw * k
                val dh = ih * k
                // 平移量夹在「图刚好铺满屏幕」的范围内：小图拖不出屏幕，大图也拖不出空白边
                val maxX = ((dw - size.width) / 2f).coerceAtLeast(0f)
                val maxY = ((dh - size.height) / 2f).coerceAtLeast(0f)
                val dx = offset.x.coerceIn(-maxX, maxX)
                val dy = offset.y.coerceIn(-maxY, maxY)
                // 统一「居中 + 平移」：图比屏幕高时上下各露出一部分，居中最自然
                val left = (size.width - dw) / 2f + dx
                val top = (size.height - dh) / 2f + dy
                drawImage(
                    image = img.asImageBitmap(),
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize(dw.roundToInt(), dh.roundToInt())
                )
            }
            AnimatedVisibility(
                visible = showHint,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Text(
                    if (total > 1) "拖动看细节 · 左右滑动换图" else "单指拖动可查看细节",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 84.dp)
                )
            }
        } else if (failed) {
            Text(
                "插图不可用\n原文件可能已被移动或删除",
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
            )
            Box(Modifier.fillMaxSize().pointerInput(ref.path) { detectTapGestures { onClose() } })
        } else {
            // 第一次打开、图还没解码出来：给个转圈，别让用户对着黑屏猜。
            // 这里必须铺一层全屏点击吸收 —— 否则这一瞬间的点击会穿透到底下的阅读页，
            // 把菜单打开（"点插图结果弹出菜单"的另一个来源）。
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }
            ) {
                CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.7f),
                    strokeWidth = 2.dp,
                    modifier = Modifier.align(Alignment.Center).size(22.dp)
                )
            }
        }

        // 切图时新图还在解码：上一张继续显示，右下角补一个小转圈，表示"下一张马上到"
        if (loading && shown != null && shownPath != ref.path) {
            CircularProgressIndicator(
                color = Color.White.copy(alpha = 0.75f),
                strokeWidth = 2.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = 92.dp)
                    .size(18.dp)
            )
        }

        /**
         * 顶部居中的切换条：‹ 3 / 12 ›。
         *
         * 为什么放顶部而不是和底部工具条挤在一行：手表屏只有 320×360，底部已经有
         * 返回 / 放大 / 缩小 三个「图标+文字」按钮了（1.0.23 的成果），再塞两个箭头加计数
         * 会把一行撑爆、边角还被圆屏吃掉。上下分居，拇指下够缩放、上够翻页，都不打架。
         *
         * 只有一张图时整条不出现 —— 数量为 1 的书不该看到"1 / 1"这种没用的控件。
         */
        if (total > 1) {
            Surface(
                color = Color.Black.copy(alpha = 0.45f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 18.dp)
                    .height(44.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 2.dp)
                ) {
                    GalleryArrow(
                        icon = R.drawable.ic_chevron_left,
                        desc = "上一张",
                        enabled = index > 0,
                        onClick = { goTo(index - 1) }
                    )
                    Text(
                        "${index + 1} / $total",
                        color = Color.White.copy(alpha = 0.95f),
                        // 等宽数字（tnum）：1→9 换到 10、99 换到 100 时整条不会左右抖动
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )
                    GalleryArrow(
                        icon = R.drawable.ic_chevron_right,
                        desc = "下一张",
                        enabled = index < total - 1,
                        onClick = { goTo(index + 1) }
                    )
                }
            }
        }

        /**
         * 底部居中的常驻工具条：返回 / 放大 / 缩小 三个按钮并排。
         *
         * 手表（尤其圆屏）**没有物理返回键**，而左边缘侧滑返回又被本应用主动禁用了
         * （1.0.8 起，为的是阅读时从左边缘划一下是翻页而不是误退出）——
         * 所以这一层只能自己给看得见的出口，不能指望系统手势。
         *
         * 放底部居中而不是右上角：圆屏四角是弧形，角上摆按钮既难看又难点。
         * 放大/缩小和返回放一起，单手拇指一横扫就够到，不用够角。
         */
        Surface(
            color = Color.Black.copy(alpha = 0.45f),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .height(44.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                ZoomBarButton(
                    icon = R.drawable.ic_arrow_back,
                    label = "返回",
                    onClick = onClose
                )
                Box(
                    Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(Color.White.copy(alpha = 0.25f))
                )
                ZoomBarButton(
                    icon = R.drawable.ic_add,
                    label = "放大",
                    onClick = { scale = (scale * 1.4f).coerceAtMost(3f) }
                )
                Box(
                    Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(Color.White.copy(alpha = 0.25f))
                )
                ZoomBarButton(
                    icon = R.drawable.ic_remove,
                    label = "缩小",
                    onClick = {
                        scale = (scale / 1.4f).coerceAtLeast(1f)
                        if (scale <= 1.001f) offset = Offset.Zero
                    }
                )
            }
        }
    }
}

/**
 * 顶部切换条上的箭头按钮：Material Symbols 的 chevron（与全应用图标同一套）。
 *
 * 到头时**变淡并禁用点击**，而不是隐藏 —— 两个箭头的位置始终固定，
 * 切到第一张 / 最后一张时整条不会突然缩短、左右乱跳。
 * 44dp 是手表上拇指能稳稳按到的最小尺寸，图标 20dp 居中。
 */
@Composable
private fun GalleryArrow(
    @DrawableRes icon: Int,
    desc: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.size(44.dp)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = desc,
                tint = Color.White.copy(alpha = if (enabled) 0.95f else 0.25f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 缩放工具条上的单个按钮：图标 + 文字。点击反馈交给 Surface 自带涟漪。
 */
@Composable
private fun ZoomBarButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.height(44.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.95f),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                color = Color.White.copy(alpha = 0.95f),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
