package com.webook.watch.ui

import com.webook.watch.R

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.webook.watch.data.Bookmark
import com.webook.watch.data.Chapter
import com.webook.watch.data.SearchHit
import com.webook.watch.data.Settings

/* ============================================================
 * 阅读背景：日间 4 套 + 夜间 4 套，各自记住选择
 * ============================================================ */
data class ReaderBg(val name: String, val bg: Long, val text: Long)

object ReaderBackgrounds {
    val light = listOf(
        ReaderBg("纸白", 0xFFF7F5F0, 0xFF1F1B16),
        ReaderBg("米黄", 0xFFF3E7CE, 0xFF3B2F1C),
        ReaderBg("淡绿", 0xFFE6EFE3, 0xFF22301F),
        ReaderBg("浅灰", 0xFFEDEDED, 0xFF1A1A1A)
    )
    val dark = listOf(
        ReaderBg("纯黑", 0xFF000000, 0xFFD8D8D8),
        ReaderBg("深灰", 0xFF1C1B1F, 0xFFE2DFE6),
        ReaderBg("深蓝", 0xFF0F1720, 0xFFCADFEF),
        ReaderBg("深棕", 0xFF1E1813, 0xFFDCCEB8)
    )
}

/**
 * 面板通用外壳：半屏卡片 + 标题栏 + 可滚动内容。
 * scrollable=false 用于内容自身是 LazyColumn 的面板（嵌套滚动会报无限高度）。
 */
@Composable
fun PanelShell(
    title: String,
    onClose: () -> Unit,
    extraAction: (@Composable () -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { onClose() }
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                Surface(
                    shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.68f)
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 6.dp, end = 4.dp, top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                title, style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f).padding(start = 4.dp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            extraAction?.invoke()
                            IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = "关闭本次面板",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        if (scrollable) {
                            Column(
                                Modifier.weight(1f).verticalScroll(rememberScrollState())
                                    .padding(bottom = 10.dp)
                            ) { content() }
                        } else {
                            Box(Modifier.weight(1f)) { content() }
                        }
                    }
                }
            }
        }
    }
}

/** 一行开关 */
@Composable
fun SwitchRow(label: String, checked: Boolean, tip: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            if (!tip.isNullOrBlank()) {
                Text(tip, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * 一行滑杆。
 * 关键：拖动过程只更新本地状态，松手才写设置。
 * 原来每拖一像素就写一次 SharedPreferences 并触发整棵 Compose 树重组，
 * 在手表上会卡到「拖不动、要重进界面才响应」。
 */
@Composable
fun SliderRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    steps: Int = 0,
    fmt: (Float) -> String,
    onChange: (Float) -> Unit
) {
    var local by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    // 外部值变化时同步（切日夜/换书），正在拖动时不要打断
    LaunchedEffect(value) { if (!dragging) local = value }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(fmt(local), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = local.coerceIn(min, max),
            onValueChange = { dragging = true; local = it },
            onValueChangeFinished = { dragging = false; onChange(local) },
            valueRange = min..max,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/* ============================================================
 * 目录 & 书签
 * ============================================================ */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TocPanel(
    chapters: List<Chapter>,
    bookmarks: List<Bookmark>,
    chapterIdx: Int,
    onJumpChapter: (Int) -> Unit,
    onJumpBookmark: (Bookmark) -> Unit,
    onRemoveBookmark: (Int) -> Unit,
    onClose: () -> Unit
) {
    var tab by remember { mutableStateOf(0) }   // 0=目录 1=书签
    PanelShell(
        title = if (tab == 0) "目录（${chapters.size} 章）" else "书签（${bookmarks.size} 条）",
        onClose = onClose,
        scrollable = false,
        extraAction = {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (tab == 0) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainer,
                onClick = { tab = if (tab == 0) 1 else 0 },
                modifier = Modifier.padding(end = 6.dp)
            ) {
                Text(
                    if (tab == 0) "看书签" else "看目录",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    ) {
        if (tab == 0) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                itemsIndexed(chapters) { i, c ->
                    val cur = i == chapterIdx
                    Surface(
                        color = if (cur) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surface,
                        onClick = { onJumpChapter(i); onClose() },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${i + 1}.", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(30.dp)
                            )
                            Text(
                                c.title.ifBlank { "第 ${i + 1} 章" },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        } else {
            if (bookmarks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "还没有书签：阅读菜单里点「书签」可把当前页记下来",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                    itemsIndexed(bookmarks) { i, b ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)
                                .combinedClickable(
                                    onClick = { onJumpBookmark(b); onClose() },
                                    onLongClick = { onRemoveBookmark(i) }
                                )
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(
                                    b.chapterTitle.ifBlank { "第 ${b.chapter + 1} 章" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    b.excerpt, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "第 ${b.page + 1} 页 · 长按删除",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ============================================================
 * 全文搜索（只搜本地已缓存内容）
 * ============================================================ */
@Composable
fun SearchPanel(
    hits: List<SearchHit>,
    searching: Boolean,
    onQuery: (String) -> Unit,
    onJump: (SearchHit) -> Unit,
    onClose: () -> Unit
) {
    var q by remember { mutableStateOf("") }
    PanelShell(title = "全文搜索", onClose = onClose, scrollable = false) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                TextField(
                    value = q, onValueChange = { q = it },
                    placeholder = { Text("输入关键词") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier.weight(1f).height(52.dp)
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    onClick = { onQuery(q) },
                    modifier = Modifier.padding(start = 6.dp).height(52.dp)
                ) {
                    Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        Text("搜索", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            when {
                searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("正在搜索…", style = MaterialTheme.typography.bodySmall)
                }
                q.isBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "只搜索本机已缓存的章节内容，搜不到在线内容\n命中后点一下直接跳到那一页",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                hits.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有找到「$q」", style = MaterialTheme.typography.bodySmall)
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                    itemsIndexed(hits) { _, h ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            onClick = { onJump(h); onClose() },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(
                                    h.chapterTitle.ifBlank { "第 ${h.chapter + 1} 章" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(h.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ============================================================
 * 排版（界面）
 * ============================================================ */
@Composable
fun TypoPanel(
    settings: Settings,
    dark: Boolean,
    onMutate: (Settings.() -> Unit) -> Unit,
    onClose: () -> Unit
) {
    settings.rev    // 订阅设置变化，改完立刻刷新
    val bgs = if (dark) ReaderBackgrounds.dark else ReaderBackgrounds.light
    val bgIdx = if (dark) settings.bgDark else settings.bgLight
    Column(Modifier.fillMaxSize()) {
        Text(
            "阅读模式", style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            listOf("page" to "翻页（一屏一页）", "scroll" to "滑动（连续上下滚）").forEach { (v, label) ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (settings.readMode == v) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer,
                    onClick = { onMutate { readMode = v } },
                    modifier = Modifier.weight(1f).padding(horizontal = 3.dp)
                ) {
                    Box(Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
        SliderRow(
            "字号", settings.fontSizeOf(dark), 14f, 26f, steps = 11,
            fmt = { "${it.toInt()} px" },
            onChange = { v -> onMutate { setFontSizeOf(dark, v) } }
        )
        SliderRow(
            "行距", settings.lineSpacingOf(dark), 1.3f, 2.2f, steps = 8,
            fmt = { String.format("%.1f", it) },
            onChange = { v -> onMutate { setLineSpacingOf(dark, v) } }
        )
        Text(
            "背景（${if (dark) "夜间" else "日间"}）",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            bgs.forEachIndexed { i, b ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(b.bg),
                    border = androidx.compose.foundation.BorderStroke(
                        if (i == bgIdx) 2.dp else 1.dp,
                        if (i == bgIdx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                    ),
                    onClick = { onMutate { if (dark) bgDark = i else bgLight = i } },
                    modifier = Modifier.weight(1f).height(38.dp).padding(horizontal = 3.dp)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(b.name, style = MaterialTheme.typography.labelSmall, color = Color(b.text))
                    }
                }
            }
        }
        SwitchRow("文字两端对齐", settings.justify, "让每行左右都对齐，中文章节更整齐") { onMutate { justify = it } }
        SwitchRow(
            "共用布局", settings.sharedLayout,
            "关闭后，夜间单独保存一套字号行距"
        ) { onMutate { sharedLayout = it } }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                onClick = onClose
            ) {
                Text("完成", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            }
        }
    }
}

/* ============================================================
 * 其它设置
 * ============================================================ */
@Composable
fun OtherPanel(
    settings: Settings,
    onMutate: (Settings.() -> Unit) -> Unit,
    onApplySystem: () -> Unit,
    onClose: () -> Unit
) {
    settings.rev    // 订阅设置变化，改完立刻刷新
    Column(
        Modifier.fillMaxSize().padding(bottom = 8.dp)
            .padding(top = 2.dp)
    ) {
        // 屏幕方向
        Text(
            "屏幕方向", style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            listOf("auto" to "跟随系统", "portrait" to "竖屏", "landscape" to "横屏").forEach { (v, label) ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (settings.orientation == v) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer,
                    onClick = { onMutate { orientation = v }; onApplySystem() },
                    modifier = Modifier.weight(1f).padding(horizontal = 3.dp)
                ) {
                    Box(Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        SliderRow(
            "自动翻页", settings.autoSeconds.toFloat(), 0f, 60f, steps = 11,
            fmt = { if (it < 1f) "关闭" else "${it.toInt()} 秒" },
            onChange = { v -> onMutate { autoSeconds = v.toInt() } }
        )
        SwitchRow("阅读时常亮（不熄屏）", settings.keepScreenOn, "屏幕超时") { onMutate { keepScreenOn = it }; onApplySystem() }
        SwitchRow("隐藏状态栏", settings.hideStatusbar, "全屏沉浸式阅读") { onMutate { hideStatusbar = it }; onApplySystem() }
        SwitchRow("音量键翻页", settings.volumeKeys, "音量上=上一章，音量下=下一章") { onMutate { volumeKeys = it } }
        SwitchRow("点击屏幕两侧翻页", settings.tapPaging, "关闭后点击只用来开关菜单") { onMutate { tapPaging = it } }
        SwitchRow("翻页震动", settings.vibrate, "翻页时震一下；系统震动需为开启状态") { onMutate { vibrate = it } }
        SwitchRow("圆屏安全边距", settings.roundScreen, "圆屏手表请打开") { onMutate { roundScreen = it }; onApplySystem() }
        SwitchRow(
            "显示亮度调节控件", settings.showBrightness,
            "收起面板后点屏幕中间，阅读菜单左侧就是亮度条"
        ) { onMutate { showBrightness = it } }
    }
}

/* ============================================================
 * 朗读设置
 * ============================================================ */
@Composable
fun SpeakPanel(
    settings: Settings,
    speakerAvailable: Boolean,
    speaking: Boolean,
    onMutate: (Settings.() -> Unit) -> Unit,
    onToggleSpeak: () -> Unit,
    onClose: () -> Unit
) {
    settings.rev    // 订阅设置变化，改完立刻刷新
    Column(Modifier.fillMaxSize()) {
        if (!speakerAvailable) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    "这台设备没有可用的 TTS 引擎，朗读可能没有声音",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
        SliderRow(
            "朗读语速", settings.speechRate, 0.5f, 1.5f, steps = 9,
            fmt = { String.format("%.2f 倍", it) },
            onChange = { v -> onMutate { speechRate = v } }
        )
        SwitchRow("朗读时保持常亮", settings.keepOnSpeaking) { onMutate { keepOnSpeaking = it } }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (speaking) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                onClick = { onToggleSpeak(); onClose() },
                modifier = Modifier.weight(1f)
            ) {
                Box(Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(if (speaking) "停止朗读" else "开始朗读", style = MaterialTheme.typography.labelMedium)
                }
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                onClick = onClose,
                modifier = Modifier.weight(1f)
            ) {
                Box(Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text("返回阅读", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(
            "朗读：读完本页会自动翻到下一页；返回书架或退出会自动停止。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp)
        )
    }
}

/**
 * 左侧亮度条：竖排布局（文字 → 跟随系统开关 → 竖向滑杆 → 百分比），
 * 原来文字和开关挤在一行会互相压住。
 */
@Composable
fun BrightnessBar(
    brightness: Float,
    followSystem: Boolean,
    onChange: (Float, Boolean) -> Unit,   // 值, 是否跟随系统
    modifier: Modifier = Modifier,
    bgColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    fgColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(
        modifier = modifier
            .width(72.dp)
            .background(bgColor, RoundedCornerShape(16.dp))
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "跟随系统", style = MaterialTheme.typography.labelSmall,
            color = fgColor.copy(alpha = 0.8f),
            maxLines = 1
        )
        Switch(
            checked = followSystem,
            onCheckedChange = { onChange(brightness, it) },
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = bgColor,
                checkedTrackColor = fgColor.copy(alpha = 0.85f),
                uncheckedThumbColor = fgColor.copy(alpha = 0.7f),
                uncheckedTrackColor = fgColor.copy(alpha = 0.2f),
                uncheckedBorderColor = fgColor.copy(alpha = 0.5f)
            ),
            modifier = Modifier.padding(top = 2.dp).scale(0.72f)
        )
        if (!followSystem) {
            VerticalSlider(
                value = brightness.coerceIn(0.05f, 1f),
                onValueChange = { onChange(it, false) },
                trackColor = fgColor.copy(alpha = 0.25f),
                activeColor = fgColor,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                "${(brightness.coerceIn(0f, 1f) * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = fgColor.copy(alpha = 0.8f)
            )
        } else {
            Text(
                "系统", style = MaterialTheme.typography.labelSmall,
                color = fgColor.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/** 竖向滑杆（Slider 只有横向版本，这里自绘一个） */
@Composable
private fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    activeColor: Color = MaterialTheme.colorScheme.primary
) {
    val track = trackColor
    val active = activeColor
    Box(
        modifier = modifier
            .width(36.dp)
            .height(120.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, _ ->
                    val frac = 1f - (change.position.y / size.height.toFloat())
                    onValueChange(frac.coerceIn(0.05f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val frac = 1f - (pos.y / size.height.toFloat())
                    onValueChange(frac.coerceIn(0.05f, 1f))
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val r = 7.dp.toPx()
            val top = r
            val bottom = size.height - r
            val stroke = 6.dp.toPx()
            drawLine(track, Offset(cx, top), Offset(cx, bottom), strokeWidth = stroke, cap = StrokeCap.Round)
            val y = bottom - (bottom - top) * value
            drawLine(active, Offset(cx, y), Offset(cx, bottom), strokeWidth = stroke, cap = StrokeCap.Round)
            drawCircle(color = active, radius = r, center = Offset(cx, y))
        }
    }
}
