package com.webook.watch

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.WindowManager
import android.widget.Toast
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.webook.watch.data.BookMeta
import com.webook.watch.data.Chapter
import com.webook.watch.data.LibraryStore
import com.webook.watch.data.Settings
import com.webook.watch.parser.BookParser
import com.webook.watch.parser.ParsedBook
import com.webook.watch.parser.SUPPORTED_EXT
import com.webook.watch.tts.Speaker
import com.webook.watch.ui.FmEntry
import com.webook.watch.ui.ImportScreen
import com.webook.watch.ui.LibraryScreen
import com.webook.watch.ui.ReaderScreen
import com.webook.watch.ui.SettingsScreen
import com.webook.watch.ui.theme.WearBookTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var store: LibraryStore
    private lateinit var speaker: Speaker

    private lateinit var settings: Settings
    /** 设置版本号：每次改设置 +1，用来驱动 Compose 重组（设置对象本身是不可变引用） */
    private var settingsVersion by mutableIntStateOf(0)
    private fun mutate(block: Settings.() -> Unit) {
        settings.block()
        settings.rev++           // 通知深层界面重新读取设置（否则 Compose 会跳过重组）
        settingsVersion++
    }

    private var screen by mutableStateOf("library")
    private var books by mutableStateOf(listOf<BookMeta>())
    private val covers = mutableStateListOf<Pair<String, Bitmap?>>()
    private var progress by mutableStateOf(mapOf<String, Float>())
    private var current by mutableStateOf<Pair<BookMeta, List<Chapter>>?>(null)
    private var confirm by mutableStateOf<Confirm?>(null)
    /** 当前这本书的书签（阅读页「目录/书签」面板用） */
    private var bookmarks by mutableStateOf(listOf<com.webook.watch.data.Bookmark>())
    /** 音量键翻页：由阅读页注册，null 表示当前没有阅读页 */
    private var readerTurn: ((Boolean) -> Unit)? = null

    private val dirStack = mutableListOf<File>()
    private var entries by mutableStateOf(listOf<FmEntry>())
    private var storageGranted by mutableStateOf(false)
    /** Android 11+ 未拿到「所有文件访问」：此时 File API 只能看到媒体文件，需要引导用户去系统设置开权限 */
    private var needAllFiles by mutableStateOf(false)
    /**
     * 站在存储根目录却一个条目都读不到 —— 说明系统根本没给读外部存储
     * （安卓 10 上 targetSdk≥29 的分区存储，光有 READ_EXTERNAL_STORAGE 也不够；
     *  真机上根目录永远至少有 Android/ 等目录，所以"空"就等于"被拦"）。
     */
    private var storageBlocked by mutableStateOf(false)
    private var askedAllFiles = false
    /** 正在导入的书名（非空时盖一层进度遮罩）——大 EPUB 要解析十几秒，没有提示用户会以为没反应然后反复点 */
    private var importing by mutableStateOf<String?>(null)
    private var lastBackAt = 0L

    data class Confirm(val title: String, val body: String, val action: () -> Unit)

    /* ---------------- 内置文件管理器（不调用系统选择器） ---------------- */
    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        storageGranted = ok
        if (ok) {
            if (dirStack.isEmpty()) dirStack.add(storageRoot())
            refreshDir()
        } else {
            toast("未获得存储权限，无法浏览设备文件")
        }
    }

    /**
     * 浏览的根目录：固定用外部存储（/sdcard）。
     * 之前会在 canRead() 为 false 时回退到应用私有目录 —— 但安卓 10 上权限还没授予时 canRead()
     * 就是 false，结果用户一进导入页就被丢进 /Android/data/... 那种空目录里，以为文件都没了。
     */
    private fun storageRoot(): File =
        Environment.getExternalStorageDirectory() ?: (getExternalFilesDir(null) ?: filesDir)

    /** Android 11+ 的「所有文件访问」：没有它，listFiles() 只会返回音乐/图片等媒体文件 */
    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /**
     * 应用专属外部目录（/sdcard/Android/data/<包名>/files）。
     * 这个目录**任何系统版本、任何权限状态下都能读写**，是权限被 ROM 锁死时的最后通道：
     * 用数据线 / MT 管理器把书放进去，这里就一定看得到。
     */
    private fun appBooksDir(): File {
        val d = getExternalFilesDir(null) ?: File(filesDir, "books")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** 跳系统设置里的「所有文件访问」开关（拿不到就退到总开关页，再不行给提示） */
    private fun openAllFilesSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        // 注：这里是 android.provider.Settings，别和本项目的 data.Settings 混了（不能 import）
        val appPage = Intent(
            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", packageName, null)
        )
        val target = if (packageManager.resolveActivity(appPage, 0) != null) appPage
        else Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        runCatching { startActivity(target); toast("打开「所有文件访问」开关后返回即可") }
            .onFailure { toast("无法打开系统设置，请手动开启「所有文件访问」") }
    }

    /** 进入导入页时调用：按系统版本走不同的授权路径 */
    private fun ensureStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+：READ_EXTERNAL_STORAGE 对 File 遍历已经没用了，必须「所有文件访问」
            needAllFiles = !hasAllFilesAccess()
            storageGranted = true
            if (dirStack.isEmpty()) dirStack.add(storageRoot())
            refreshDir()
            if (needAllFiles && !askedAllFiles) { askedAllFiles = true; openAllFilesSettings() }
            return
        }
        val granted = checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        storageGranted = granted
        if (granted) {
            if (dirStack.isEmpty()) dirStack.add(storageRoot())
            refreshDir()
        } else {
            requestPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /** 从系统设置返回后重新判定（用户可能刚把开关打开） */
    override fun onResume() {
        super.onResume()
        if (screen == "import") {
            val need = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess()
            if (need != needAllFiles) {
                needAllFiles = need
                if (dirStack.isEmpty()) dirStack.add(storageRoot())
                refreshDir()
                if (!need) toast("已获得所有文件访问权限")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LibraryStore(this)
        settings = Settings(this)
        speaker = Speaker(this)
        speaker.rate = settings.speechRate

        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySystemSettings()        // 常亮 / 状态栏 / 屏幕方向 / 亮度
                                     // 边缘侧滑返回由 applySystemSettings → applyEdgeGestureExclusion 处理
        refresh()
        purgeDemoBooks()             // 老版本用户书架里的示例书一次性清掉
        handleIncoming(intent)       // 从别的 App「分享/打开方式」进来的书

        // 返回键：交给应用自己处理，防止误退出
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })

        setContent {
            settingsVersion                        // 订阅设置变化
            val dark = when (settings.theme) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            WearBookTheme(darkTheme = dark) {
                // 根容器铺满主题背景色（这是深浅色切换时背景不变的原因）
                val bg by animateColorAsState(MaterialTheme.colorScheme.background, tween(280), label = "bg")
                // 用 Surface 兜底：保证整屏底色一定被画出来（投屏 / 多屏协同时不会透出窗口的白色底），
                // 同时给整棵树一个与背景配套的文字颜色，避免出现"白字白底看不见标题"
                Surface(
                    color = bg,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxSize()
                ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(if (settings.roundScreen) Modifier.padding(10.dp) else Modifier)
                ) {
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            val forward = targetState == "reader"
                            if (forward) {
                                (slideInHorizontally(tween(260)) { it } + fadeIn(tween(200))) togetherWith
                                    (slideOutHorizontally(tween(260)) { -it / 3 } + fadeOut(tween(160)))
                            } else {
                                (slideInHorizontally(tween(260)) { -it / 3 } + fadeIn(tween(200))) togetherWith
                                    (slideOutHorizontally(tween(260)) { it } + fadeOut(tween(160)))
                            }
                        },
                        label = "screen"
                    ) { s ->
                        when (s) {
                            "library" -> LibraryScreen(
                                books = books,
                                covers = covers.toMap(),
                                progress = progress,
                                onOpen = { openBook(it) },
                                onDelete = { meta -> confirm = Confirm("删除《${meta.title}》？",
                                    "图书内容和阅读进度将一并删除，且无法恢复。") { store.remove(meta.id); vibrate(); refresh() } },
                                onImport = { screen = "import"; ensureStoragePermission() },
                                onSettings = { screen = "settings" }
                            )

                            "reader" -> current?.let { (meta, chapters) ->
                                val p = store.progress(meta.id)
                                ReaderScreen(
                                    title = meta.title,
                                    chapters = chapters,
                                    settings = settings,
                                    speaker = speaker,
                                    dark = dark,
                                    initialChapter = p?.chapter ?: 0,
                                    initialPage = p?.page ?: 0,
                                    bookmarks = bookmarks,
                                    onProgress = { c, pg, frac ->
                                        store.saveProgress(meta.id, com.webook.watch.data.Progress(c, pg))
                                        progress = progress.toMutableMap().apply { put(meta.id, frac) }
                                    },
                                    onBack = {
                                        speaker.stop(); readerTurn = null
                                        current = null; screen = "library"; refresh()
                                    },
                                    onVibrate = { vibrate() },
                                    onMutate = { block -> mutate(block) },
                                    onThemeToggle = { mutate { theme = if (dark) "light" else "dark" } },
                                    onBrightness = { v -> applyBrightness(v) },
                                    onApplySystem = { applySystemSettings() },
                                    onAddBookmark = { b ->
                                        store.addBookmark(meta.id, b)
                                        bookmarks = store.bookmarks(meta.id)
                                    },
                                    onRemoveBookmark = { i ->
                                        store.removeBookmark(meta.id, i)
                                        bookmarks = store.bookmarks(meta.id)
                                    },
                                    onRegisterTurn = { fn -> readerTurn = fn }
                                )
                            }

                            "import" -> ImportScreen(
                                currentPath = dirStack.lastOrNull()?.absolutePath ?: storageRoot().absolutePath,
                                entries = entries,
                                canGoUp = dirStack.size > 1,
                                hasPermission = storageGranted,
                                needAllFiles = needAllFiles,
                                storageBlocked = storageBlocked,
                                onOpen = { e ->
                                    if (e.file.isDirectory) { dirStack.add(e.file); refreshDir() }
                                    else if (e.isBook) importFile(e.file)
                                    else toast("暂不支持 .${e.file.name.substringAfterLast('.', "").lowercase()} —— 支持 TXT / EPUB / MOBI / AZW / HTML")
                                },
                                onGoUp = { if (dirStack.size > 1) { dirStack.removeAt(dirStack.lastIndex); refreshDir() } },
                                onBack = { screen = "library" },
                                onGrant = { ensureStoragePermission() },
                                onOpenAllFiles = { openAllFilesSettings() },
                                onOpenAppDir = {
                                    // 压栈而不是清栈：这样「返回上级」还能回到 /sdcard
                                    val d = appBooksDir()
                                    if (dirStack.lastOrNull()?.absolutePath != d.absolutePath) dirStack.add(d)
                                    if (dirStack.size > 1) storageBlocked = false
                                    refreshDir()
                                    if (entries.isEmpty()) toast("专属文件夹也是空的 —— 可以用数据线或 MT 管理器把书拷进去")
                                }
                            )

                            "settings" -> SettingsScreen(
                                settings = settings,
                                onChange = { mutate { }; speaker.rate = settings.speechRate; applySystemSettings() },
                                onBack = { screen = "library"; refresh() },
                                onOpenSystemSettings = {
                                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                                        .onFailure { toast("打不开系统设置，请手动去「设置 → 手势」里关闭侧滑返回") }
                                },
                                onClearAll = { confirm = Confirm("清空全部数据？",
                                    "将删除书架上的所有图书、阅读进度与设置，且无法恢复。") { store.clearAll(); refresh() } }
                            )
                        }
                    }

                    // 统一的 Material 3 对话框（跟随主题、带动画）
                    confirm?.let { c ->
                        AlertDialog(
                            onDismissRequest = { confirm = null },
                            title = { Text(c.title) },
                            text = { Text(c.body) },
                            confirmButton = {
                                TextButton(onClick = { c.action(); confirm = null }) {
                                    Text("确定", color = MaterialTheme.colorScheme.error)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirm = null }) { Text("取消") }
                            }
                        )
                    }

                    // 导入进度遮罩：大 EPUB（几百 MB、上千章）解析要十几秒，必须有反馈，
                    // 同时挡住重复点击（否则同一本书会被导入好几遍）
                    importing?.let { nm ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Column(
                                    Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                                    Text(
                                        "正在解析", style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.padding(top = 10.dp)
                                    )
                                    Text(
                                        nm, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                    Text(
                                        "大文件（上千章）可能要十几秒，请稍候",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }

    /** singleton，别的 App 再次「分享/打开」时不会重建 Activity */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    /* ---------------- 屏幕：亮度 / 常亮 / 状态栏 / 方向 ---------------- */

    /** 应用亮度；v < 0 表示跟随系统 */
    private fun applyBrightness(v: Float) {
        runCatching {
            val lp = window.attributes
            lp.screenBrightness = if (v < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else v.coerceIn(0.05f, 1f)
            window.attributes = lp
        }
    }

    /** 设置里那些跟窗口有关的开关，改完立刻生效 */
    private fun applySystemSettings() {
        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            if (settings.hideStatusbar) {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                show(WindowInsetsCompat.Type.systemBars())
            }
        }

        requestedOrientation = when (settings.orientation) {
            "portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        applyBrightness(settings.brightness)
        applyEdgeGestureExclusion()
    }

    /**
     * 关闭「左边缘侧滑返回」：把屏幕左边缘一条窄带声明为系统手势排除区。
     * 注意只能给窄带 —— 整屏矩形会被系统按尺寸上限裁剪掉而完全失效（之前就是这么踩的坑）。
     * 排除之后，这段滑动会原样传给应用，阅读页里就变成翻页手势，不会误触返回。
     */
    private fun applyEdgeGestureExclusion() {
        if (Build.VERSION.SDK_INT < 29) return
        val v = window.decorView
        v.post {
            runCatching {
                val r = android.graphics.Rect()
                v.getGlobalVisibleRect(r)
                val band = (24 * resources.displayMetrics.density).toInt().coerceAtLeast(8)
                val rects = if (settings.blockEdgeSwipe)
                    listOf(android.graphics.Rect(r.left, r.top, r.left + band, r.bottom))
                else emptyList()
                ViewCompat.setSystemGestureExclusionRects(v, rects)
            }
        }
    }

    /** 窗口重新获得焦点时再刷一次（横竖屏切换、从设置页返回等场景） */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyEdgeGestureExclusion()
    }

    /** 音量键翻页（在阅读页且开关打开时生效） */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (settings.volumeKeys && screen == "reader" && readerTurn != null) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> { readerTurn?.invoke(true); return true }
                android.view.KeyEvent.KEYCODE_VOLUME_UP -> { readerTurn?.invoke(false); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }


    private fun onBack() {
        when (screen) {
            "reader" -> { speaker.stop(); readerTurn = null; current = null; screen = "library"; refresh() }
            "import", "settings" -> { screen = "library"; refresh() }
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackAt < 2000) finish() else { lastBackAt = now; toast("再按一次退出") }
            }
        }
    }

    /* ---------------- 书架 ---------------- */
    private fun refresh() {
        books = store.list()
        covers.clear()
        books.forEach { b -> covers.add(b.id to store.coverBitmap(b)) }
        progress = store.allProgress().mapValues { (_, v) -> (v.chapter + 0.001f).coerceAtMost(0.999f) }
    }

    private fun openBook(meta: BookMeta) {
        lifecycleScope.launch(Dispatchers.IO) {
            val chapters = store.chapters(meta.id)
            val marks = store.bookmarks(meta.id)
            withContext(Dispatchers.Main) {
                if (chapters.isEmpty()) { toast("这本书没有可读内容"); return@withContext }
                bookmarks = marks
                current = meta to chapters
                screen = "reader"
            }
        }
    }

    /** 清掉历史版本带进来的示例书（fmt == "demo"），书架只留用户自己导入的书 */
    private fun purgeDemoBooks() {
        lifecycleScope.launch(Dispatchers.IO) {
            val demo = store.list().filter { it.fmt == "demo" }
            if (demo.isEmpty()) return@launch
            demo.forEach { store.remove(it.id) }
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    /* ---------------- 导入（内置文件管理器） ---------------- */
    private fun isBookFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXT

    private fun refreshDir() {
        val dir = dirStack.lastOrNull() ?: run { entries = emptyList(); storageBlocked = false; return }
        val list = dir.listFiles() ?: emptyArray()
        entries = list
            .filter { !it.name.startsWith(".") }
            .map { FmEntry(it.name ?: "?", it.isDirectory, it, !it.isDirectory && isBookFile(it.name)) }
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        // 根目录读到空 → 不是真的空，是系统没放行（真机根目录至少有 Android/ 等）
        storageBlocked = dirStack.size == 1 && entries.isEmpty()
    }

    /**
     * 解析并入库。
     * epub 只需要文件本身（走 ZipFile 逐章读），所以 bytes 传 null —— 千万别为了 epub 整读文件，
     * 一本 171MB 的 epub 会把 96MB 堆的手表直接打崩（用户实测 OOM 闪退）。
     */
    private suspend fun parseAndStore(name: String, bytes: ByteArray?, epubSrc: File?): Pair<ParsedBook, Boolean> {
        val sizeBytes = epubSrc?.length() ?: bytes?.size?.toLong() ?: 0L
        val parsed =
            if (name.endsWith(".epub", true) && epubSrc != null) BookParser.epubToBook(epubSrc, name)
            else BookParser.parse(File(""), name, bytes ?: ByteArray(0))
        // 同一本书（书名 + 格式 + 章节数都一样）不重复入库 ——
        // 用户嫌没反应连点几下，之前会往书架里塞好几本一模一样的
        val dup = store.list().any {
            it.title == parsed.title && it.fmt == parsed.fmt && it.chapterCount == parsed.chapters.size
        }
        if (dup) return parsed to true
        store.add(parsed.title, parsed.author, parsed.fmt, sizeBytes, parsed.chapters, parsed.cover)
        return parsed to false
    }

    private suspend fun finishImport(parsed: ParsedBook, duplicated: Boolean) {
        withContext(Dispatchers.Main) {
            refresh()
            toast(if (duplicated) "《${parsed.title}》已经在书架里了" else "已导入《${parsed.title}》")
            screen = "library"
        }
    }

    private fun importFile(file: File) {
        if (importing != null) { toast("正在导入，请稍候…"); return }
        importing = file.name.substringBeforeLast('.')
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val isEpub = file.name.endsWith(".epub", true)
                if (!isEpub && file.length() > BookParser.MAX_IN_MEMORY_BYTES) {
                    withContext(Dispatchers.Main) {
                        toast("这本书 ${file.length() / 1048576} MB，超过上限 ${BookParser.MAX_IN_MEMORY_BYTES / 1048576} MB —— 手表内存放不下")
                    }
                    return@launch
                }
                // epub 不需要字节数组（ZipFile 逐章读），传 null 省掉一整份文件的内存
                val bytes = if (isEpub) null else file.readBytes()
                val (parsed, dup) = parseAndStore(file.name, bytes, file)
                finishImport(parsed, dup)
            } catch (e: Throwable) {
                // 连 OutOfMemoryError 一起接住：宁可提示一句，也不要整个 App 闪退
                withContext(Dispatchers.Main) { toast("导入失败：${friendlyError(e)}") }
            } finally {
                withContext(Dispatchers.Main) { importing = null }
            }
        }
    }

    /** 把异常翻译成用户看得懂的一句 */
    private fun friendlyError(e: Throwable): String = when (e) {
        is OutOfMemoryError -> "内存不足，这本书太大了"
        is StackOverflowError -> "文件结构太复杂，解析失败"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** 处理别的 App「分享 / 打开方式」进来的 URI（不需要任何存储权限） */
    private fun handleIncoming(intent: Intent?) {
        val raw: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        val uri: Uri = raw ?: return
        val name = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "shared_book"
        if (!isBookFile(name)) {
            toast("不支持这个格式：$name")
            return
        }
        if (importing != null) { toast("正在导入，请稍候…"); return }
        importing = name.substringBeforeLast('.')
        lifecycleScope.launch(Dispatchers.IO) {
            val tmp = File(cacheDir, "in_${System.currentTimeMillis()}_$name")
            try {
                val ok = runCatching {
                    contentResolver.openInputStream(uri)?.use { ins ->
                        tmp.outputStream().use { ins.copyTo(it) }
                    } != null
                }.getOrDefault(false)
                if (!ok || !tmp.exists() || tmp.length() == 0L) throw IllegalStateException("读不到该文件")
                val isEpub = name.endsWith(".epub", true)
                if (!isEpub && tmp.length() > BookParser.MAX_IN_MEMORY_BYTES) {
                    tmp.delete()
                    withContext(Dispatchers.Main) {
                        toast("这本书 ${tmp.length() / 1048576} MB，超过上限 ${BookParser.MAX_IN_MEMORY_BYTES / 1048576} MB —— 手表内存放不下")
                    }
                    return@launch
                }
                val (parsed, dup) = parseAndStore(name, if (isEpub) null else tmp.readBytes(), tmp)
                tmp.delete()
                finishImport(parsed, dup)
            } catch (e: Throwable) {
                tmp.delete()
                withContext(Dispatchers.Main) { toast("导入失败：${friendlyError(e)}") }
            } finally {
                withContext(Dispatchers.Main) { importing = null }
            }
        }
    }

    /** 取 content:// 的真实文件名 */
    private fun displayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull()
    }

    private fun vibrate() {
        if (!settings.vibrate) return
        val v = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) v?.vibrate(android.os.VibrationEffect.createOneShot(12, 60))
            else @Suppress("DEPRECATION") v?.vibrate(12)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() { speaker.shutdown(); super.onDestroy() }
}
