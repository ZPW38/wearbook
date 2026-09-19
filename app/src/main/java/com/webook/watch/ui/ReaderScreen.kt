package com.webook.watch.ui

import com.webook.watch.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.webook.watch.data.Bookmark
import com.webook.watch.data.Chapter
import com.webook.watch.data.SearchHit
import com.webook.watch.data.Settings
import com.webook.watch.text.Page
import com.webook.watch.text.PageLine
import com.webook.watch.text.PagePacker
import com.webook.watch.text.LineBreaker
import com.webook.watch.text.readerPaint
import com.webook.watch.tts.Speaker
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
    settings: Settings,
    speaker: Speaker,
    dark: Boolean,
    initialChapter: Int,
    initialPage: Int,
    bookmarks: List<Bookmark>,
    onProgress: (Int, Int, Float) -> Unit,   // 章、页、全书进度 0~1
    onBack: () -> Unit,
    onVibrate: () -> Unit,
    onMutate: (Settings.() -> Unit) -> Unit, // 改设置并立即生效
    onThemeToggle: () -> Unit,
    onBrightness: (Float) -> Unit,
    /** 屏幕方向 / 状态栏 / 常亮 / 圆屏边距这类跟窗口有关的开关，改完必须真正应用一次 */
    onApplySystem: () -> Unit,
    onAddBookmark: (Bookmark) -> Unit,
    onRemoveBookmark: (Int) -> Unit,
    onRegisterTurn: ((Boolean) -> Unit) -> Unit   // 注册音量键翻页回调（null 表示注销）
) {
    var chapterIdx by remember { mutableIntStateOf(initialChapter.coerceIn(0, chapters.lastIndex.coerceAtLeast(0))) }
    var pageIdx by remember { mutableIntStateOf(initialPage) }
    var lines by remember { mutableStateOf<List<PageLine>>(emptyList()) }
    var pages by remember { mutableStateOf<List<Page>>(emptyList()) }
    var showOverlay by remember { mutableStateOf(false) }
    var speaking by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.NONE) }

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

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(bg.bg))) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val padX = with(density) { 14.dp.toPx() }
        val padY = with(density) { 12.dp.toPx() }
        val textW = wPx - padX * 2

        // 排版（章节/字号/尺寸变化时重算）
        LaunchedEffect(chapterIdx, fontSizePx, settings.lineSpacingOf(dark), wPx, hPx, scrollMode) {
            val ch = chapters.getOrNull(chapterIdx) ?: return@LaunchedEffect
            launch {
                val ls = LineBreaker.layout(ch.paragraphs, paint, textW, paint.textSize * 2)
                val ps = PagePacker.pack(ls, hPx - padY * 2, lineHeightPx, paraGapPx)
                lines = ls
                pages = ps
                // 跳转优先：先按段落定位（搜索），再按页码定位（书签/目录）
                val para = pendingPara
                val pg = pendingPage
                when {
                    para != null -> {
                        val li = ls.indexOfFirst { it.paraIndex == para }
                        pageIdx = if (li >= 0) ps.indexOfFirst { it.start <= li && li < it.end }.coerceAtLeast(0) else 0
                        pendingPara = null
                    }
                    pg != null -> { pageIdx = pg.coerceIn(0, (ps.size - 1).coerceAtLeast(0)); pendingPage = null }
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

        fun goNext() {
            if (pages.isEmpty()) return
            if (scrollMode) {                       // 滑动模式：往下滚一屏
                scope.launch { scroller.animateScrollBy(hPx * 0.92f) }
                onVibrate()
                return
            }
            scope.launch { slide.snapTo(wPx); slide.animateTo(0f, tween(220)) }
            if (pageIdx < pages.size - 1) { pageIdx++; onVibrate() }
            else if (chapterIdx < chapters.size - 1) { chapterIdx++; pageIdx = 0; onVibrate() }
        }
        fun goPrev() {
            if (pages.isEmpty()) return
            if (scrollMode) {                       // 滑动模式：往上滚一屏
                scope.launch { scroller.animateScrollBy(-hPx * 0.92f) }
                onVibrate()
                return
            }
            scope.launch { slide.snapTo(-wPx); slide.animateTo(0f, tween(220)) }
            if (pageIdx > 0) { pageIdx--; onVibrate() }
            else if (chapterIdx > 0) { chapterIdx--; pageIdx = 0; onVibrate() }
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
                onNextChapter = { chapterIdx++; pageIdx = 0 }
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
                        val x = if (i == page.start && !ln.paraStart) 0f else ln.indent
                        val lastOfPara = (i + 1 >= lines.size) || lines[i + 1].paraStart
                        drawLine(canvas, paint, ln.text, padX + x, padY + y, textW, justify && !lastOfPara)
                        y += lineHeightPx
                    }
                    canvas.restore()
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { showOverlay = true },
                    onTap = { offset ->
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

        // 进度保存（滑动模式用"读到第几行"折算成页，存储格式不变）
        LaunchedEffect(chapterIdx, pageIdx, lineAtTop, scrollMode) {
            val pageCount = pages.size.coerceAtLeast(1)
            val frac = if (scrollMode) {
                val p = (lineAtTop.toFloat() / lines.size.coerceAtLeast(1)).coerceIn(0f, 1f)
                ((chapterIdx + p) / chapters.size.coerceAtLeast(1)).coerceIn(0f, 1f)
            } else {
                ((chapterIdx + (pageIdx + 1f) / pageCount) / chapters.size.coerceAtLeast(1)).coerceIn(0f, 1f)
            }
            onProgress(chapterIdx, pageIdx, frac)
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

        // 自动翻页
        LaunchedEffect(settings.autoSeconds, chapterIdx, pageIdx, speaking) {
            if (settings.autoSeconds > 0 && !speaking) {
                delay(settings.autoSeconds * 1000L)
                goNext()
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
                showBrightness = settings.showBrightness,
                onBack = onBack,
                onOpenToc = { panel = Panel.TOC },
                onPrevChapter = { if (chapterIdx > 0) { chapterIdx--; pageIdx = 0 } },
                onNextChapter = { if (chapterIdx < chapters.size - 1) { chapterIdx++; pageIdx = 0 } },
                onSearch = { panel = Panel.SEARCH },
                onAutoPage = {
                    // 5 / 10 / 20 / 30 / 60 / 关闭 循环
                    val next = when (settings.autoSeconds) { 0 -> 5; 5 -> 10; 10 -> 20; 20 -> 30; 30 -> 60; else -> 0 }
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
                onJumpChapter = { i -> chapterIdx = i; pageIdx = 0 },
                onJumpBookmark = { b -> chapterIdx = b.chapter; pendingPage = b.page },
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
                onJump = { h -> chapterIdx = h.chapter; pendingPara = h.para },
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
    scroller: LazyListState,
    onLineAtTop: (Int) -> Unit,
    tapPaging: Boolean,
    onTapCenter: () -> Unit,
    onPageDown: () -> Unit,
    onPageUp: () -> Unit,
    hasNextChapter: Boolean,
    onNextChapter: () -> Unit
) {
    val density = LocalDensity.current
    val chunks = remember(lines) { lines.chunked(SCROLL_CHUNK) }
    val topPad = with(density) { padY.toDp() }

    Box(
        Modifier.fillMaxSize().pointerInput(tapPaging) {
            detectTapGestures(
                onLongPress = { onTapCenter() },
                onTap = { offset ->
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
            contentPadding = PaddingValues(top = topPad, bottom = topPad)
        ) {
            itemsIndexed(chunks) { ci, chunk ->
                val startGlobal = ci * SCROLL_CHUNK
                var h = 0f
                chunk.forEachIndexed { j, ln ->
                    if (startGlobal + j > 0 && ln.paraStart) h += paraGapPx
                    h += lineHeightPx
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
    showBrightness: Boolean,
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
                        contentDescription = "返回", tint = fgColor
                    )
                }
                Column(
                    Modifier.weight(1f).combinedClickable(
                        onClick = onOpenToc,
                        onLongClick = { onTip("章节名：点一下打开目录（可跳章、看书签）") }
                    )
                ) {
                    Text(
                        chapterTitle.ifBlank { "第 ${chapterIdx + 1} 章" },
                        style = MaterialTheme.typography.titleSmall,
                        color = fgColor,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        title, style = MaterialTheme.typography.labelSmall,
                        color = dimColor,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onOpenToc) {
                    Icon(painter = painterResource(R.drawable.ic_menu_book), contentDescription = "目录", tint = fgColor)
                }
            }

        }

        // 中间左侧：亮度调节（小浮条，不遮正文中间）
        if (showBrightness) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.padding(start = 6.dp, bottom = 36.dp)) {
                    BrightnessBar(
                        brightness = brightness,
                        followSystem = brightness < 0f,
                        onChange = onBrightnessChange,
                        bgColor = barColor,
                        fgColor = fgColor
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
                        tip = "自动翻页：点一下在 5/10/20/30/60 秒与关闭之间切换；滑动模式下是自动往下滚一屏",
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
private fun TextIconButton(
    icon: Int,
    label: String,
    tip: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onTip: (String) -> Unit,
    tint: Color = androidx.compose.ui.graphics.Color.Unspecified
) {
    Column(
        Modifier
            .size(width = 56.dp, height = 48.dp)
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
