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
import com.webook.watch.ui.FirstRunScreen
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.webook.watch.data.AppVersion
import com.webook.watch.data.BookMerge
import com.webook.watch.data.BookMeta
import com.webook.watch.data.BookScanner
import com.webook.watch.data.Chapter
import com.webook.watch.data.LibraryStore
import com.webook.watch.data.Progress
import com.webook.watch.data.Settings
import com.webook.watch.data.UrlImport
import com.webook.watch.data.WifiTransfer
import com.webook.watch.parser.BookParser
import com.webook.watch.parser.ParsedBook
import com.webook.watch.parser.SUPPORTED_EXT
import com.webook.watch.tts.Speaker
import com.webook.watch.ui.FmEntry
import com.webook.watch.ui.ImportScreen
import com.webook.watch.ui.ImportMethodsScreen
import com.webook.watch.ui.CircleSafeArea
import com.webook.watch.ui.ComicDetect
import com.webook.watch.ui.ComicReader
import com.webook.watch.ui.ConfirmBox
import com.webook.watch.ui.ComicPage
import com.webook.watch.ui.comicPages
import com.webook.watch.ui.DirPickerScreen
import com.webook.watch.ui.QrTransferScreen
import com.webook.watch.ui.LibraryScreen
import com.webook.watch.ui.ReaderScreen
import com.webook.watch.ui.UpdateDialog
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

    // ⚠️ 这里**不能**写成 if (settings.firstRunDone) ... —— settings 是 lateinit，
    // 在属性初始化阶段还没赋值，会抛 "lateinit property settings has not been initialized"
    // 并直接崩溃在 Activity 构造期。首次启动的判断挪到 onCreate 里做。
    private var screen by mutableStateOf("library")
    /** 「导入方式」页是从哪儿点进来的：import=导入页，其他=书架加号。决定返回去哪儿 */
    private var methodsFrom by mutableStateOf("library")
    private var books by mutableStateOf(listOf<BookMeta>())
    private val covers = mutableStateListOf<Pair<String, Bitmap?>>()
    private var progress by mutableStateOf(mapOf<String, Float>())
    private var current by mutableStateOf<Pair<BookMeta, List<Chapter>>?>(null)
    private var confirm by mutableStateOf<Confirm?>(null)
    /** 当前这本书的书签（阅读页「目录/书签」面板用） */
    private var bookmarks by mutableStateOf(listOf<com.webook.watch.data.Bookmark>())
    /** 音量键翻页：由阅读页注册，null 表示当前没有阅读页 */
    private var readerTurn: ((Boolean) -> Unit)? = null
    /**
     * 阅读页注册进来的「立刻保存进度」回调。
     * 进 onStop 时调一次 —— 界面被收起、切后台、划掉任务卡，最后一次读到哪儿都能落盘。
     */
    private var readerFlush: (() -> Unit)? = null

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

    /** 链接导入的进度（null = 没在下）；带 error 就是下失败了，界面会显示原因 */
    private var urlJob by mutableStateOf<UrlImport.Job?>(null)

    /** 正在扫描目录收书 */
    private var scanning by mutableStateOf(false)

    /** 漫画模式的翻页回调（表冠 / 音量键共用） */
    private var comicTurn: ((Boolean) -> Unit)? = null

    /** 手机传书（局域网小网页）的服务句柄与提示状态 */
    private var wifiServer: com.webook.watch.data.WifiTransfer.Server? by mutableStateOf(null)
    private var wifiInfo by mutableStateOf<String?>(null)
    /** 二维码只编码**一个**地址：多网卡时挑最像"家用局域网"的那个 */
    private var wifiQrUrl by mutableStateOf<String?>(null)

    /** 检测到「书架里已有同一本书的旧版本」，等用户选更新还是保留两本 */
    private var pendingUpdate by mutableStateOf<ImportOutcome.UpdateOffer?>(null)

    /** 目录选择器（扫目录收书选路径用）的浏览栈 */
    private var pickStack by mutableStateOf<List<File>>(emptyList())
    private var pickEntries by mutableStateOf<List<FmEntry>>(emptyList())
    private var pickBlocked by mutableStateOf(false)
    private var lastBackAt = 0L

    data class Confirm(val title: String, val body: String, val action: () -> Unit)

    /* ---------------- 内置文件管理器（不调用系统选择器） ---------------- */
    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        storageGranted = ok
        if (ok) {
            if (dirStack.isEmpty()) dirStack.add(storageRoot())
            if (pickStack.isEmpty()) pickStack = listOf(storageRoot())
            refreshDir()
        } else {
            toast("未获得存储权限，无法浏览设备文件")
        }
    }

    /**
     * SAF 选书。
     *
     * ⚠️ **在 Wear OS 上这条路是死的，已用 Wear OS 5 / API 34 模拟器实测确认**：
     * 系统把 `ACTION_OPEN_DOCUMENT` 解析到
     * `com.google.android.wearable.frameworkpackagestubs/.Stubs$DocumentsStub`
     * —— 一个透明的空 Activity。点了按钮既不弹选择器、也不报错、更不抛
     * ActivityNotFoundException，界面就是原地不动（比崩溃更难排查）。
     * `packageManager.queryIntentActivities` 查得到"有处理者"，所以**不能用它探测**；
     * 只能用 `resolveActivity().activityInfo.name` 里是否含 "Stub" 来识别。
     *
     * 因此 Wear OS 上不能依赖 SAF，真正的通道是 [appBooksDir]（应用专属外部目录，
     * 数据线/配对工具放进去即可，不需要任何权限）。
     */
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult   // 用户点了返回
        importFromUri(uri)
    }

    /**
     * 这台设备的 SAF 选择器是不是个空壳（Wear OS 就是）。
     * 命中就别让用户点了个寂寞 —— 直接告诉他走专属目录。
     */
    private fun isSafStubbed(): Boolean = runCatching {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }
        val r = packageManager.resolveActivity(i, 0) ?: return@runCatching true   // 无人处理 = 同样不可用
        r.activityInfo?.name?.contains("Stub", ignoreCase = true) == true
    }.getOrDefault(true)

    /**
     * 点「从设备导入」的统一入口：**先试系统选择器，不行就自动落到专属文件夹**。
     *
     * 之前是「先弹一句提示让用户自己再去点另一个图标」—— 用户凭什么知道该点哪个？
     * 多一次来回、多一次困惑。现在直接替他把路走通：
     *   - 手机 / 正常设备：SAF 好用，直接开选择器；
     *   - Wear OS（选择器是空壳）：识别出来就直接进专属文件夹，并把地址亮在屏幕上。
     */
    private fun openImportLocal() {
        if (isSafStubbed()) {
            openAppDir()
            toast("这台手表没有文件选择器，已打开专属文件夹 —— 把书放进去即可")
            return
        }
        val mimes = arrayOf(
            "application/epub+zip",
            "application/x-mobipocket-ebook",
            "application/vnd.amazon.ebook",
            "text/plain",
            "text/html",
            "*/*"
        )
        runCatching { openDocument.launch(mimes) }
            .onFailure { openAppDir() }
    }

    /** 打开专属文件夹（数据线放书的通道），内部目录直接可读，不需要权限 */
    private fun openAppDir() {
        val d = appBooksDir()
        if (dirStack.lastOrNull()?.absolutePath != d.absolutePath) dirStack.add(d)
        // 专属文件夹永远可读（应用自己的目录，不经过分区存储），
        // 所以这里无条件清掉"被锁"状态 —— 不清的话导入页会顶一条红色横幅说
        // "系统没放行读取存储"，而用户明明就在那个不需要权限的目录里。
        storageBlocked = false
        refreshDir()
        // ⚠️ 必须切回导入页：这个函数现在会被「导入方式」页的卡片调用，
        // 只改 dirStack 不换页的话，用户点完卡片界面纹丝不动 —— 看起来像按钮失灵。
        screen = "import"
        if (entries.isEmpty()) toast("专属文件夹是空的 —— 用数据线或手机端工具把书放进去")
    }

    /**
     * 点旧版「从系统文件选书」时的行为，保留给外部调用（现在主入口走 [openImportLocal]）。
     */
    private fun openDocumentSafely() {
        if (isSafStubbed()) {
            toast("这台手表没有文件选择器，请用「专属文件夹」：数据线连电脑把书放进去即可")
            return
        }
        val mimes = arrayOf(
            "application/epub+zip",
            "application/x-mobipocket-ebook",
            "application/vnd.amazon.ebook",
            "text/plain",
            "text/html",
            "*/*"
        )
        runCatching { openDocument.launch(mimes) }
            .onFailure { toast("打不开文件选择器，请改用「专属文件夹」") }
    }

    /**
     * 浏览的根目录：固定用外部存储（/sdcard）。
     * 之前会在 canRead() 为 false 时回退到应用私有目录 —— 但安卓 10 上权限还没授予时 canRead()
     * 就是 false，结果用户一进导入页就被丢进 /Android/data/... 那种空目录里，以为文件都没了。
     */
    private fun storageRoot(): File =
        Environment.getExternalStorageDirectory() ?: (getExternalFilesDir(null) ?: filesDir)

    /**
     * Android 11+ 的「所有文件访问」：没有它，listFiles() 只会返回音乐/图片等媒体文件。
     *
     * ⚠️ Wear OS（三星 Watch 7）**系统设置里没有这个开关**，权限页只有「读取应用列表」，
     * 于是本函数在手表上恒为 false，内置文件管理器必然列不出 epub/txt。
     * 这条路上限如此，别指望"引导用户去打开"能解决 —— 手表上根本没有那个开关可点。
     */
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
        // 首次启动：先进引导页认屏幕，选完才进书架
        if (!settings.firstRunDone) screen = "firstrun"
        // 调试用：adb shell am start --es screen import 可直接跳到指定页面。
        // 只在 debug 构建里生效（release 里下面这行会被 R8 当成常量折叠掉）。
        intent?.getStringExtra("screen")?.takeIf { it.isNotBlank() }?.let { screen = it }

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
            // 界面缩放（等效调 DPI）：density 变小 → 同样的 dp 占更多像素 → 元素整体变大。
            // 手表为了塞进更多内容，系统 density 往往标得很高，字小到看不清，这一层就是给它的解药。
            val baseDensity = LocalDensity.current
            val scaledDensity = Density(
                density = baseDensity.density / settings.uiScale.coerceIn(0.8f, 1.25f),
                fontScale = baseDensity.fontScale
            )
            CompositionLocalProvider(LocalDensity provides scaledDensity) {
            // 模拟屏幕规格：把界面锁进指定宽高比并居中，周围补黑 —— 不改设备，只约束预览
            val simRatio: Float? = remember(settings.simScreen) {
                val parts = settings.simScreen.split('x')
                if (parts.size == 2) {
                    val w = parts[0].toFloatOrNull()
                    val h = parts[1].toFloatOrNull()
                    if (w != null && h != null && h > 0f) w / h else null
                } else null
            }
            // 圆屏安全区。
            //
            // ⚠️ 这里**不能只给四边一个固定 padding**：圆屏的可用宽度随高度变化
            // （正中满宽、越往上下越窄），固定内缩挡不住弧线。
            // 以 450px 圆屏（density 2 → 短边 225dp）实测：
            //   y=24px（标题栏）处可用半宽只剩 101px，左边界要退到 x=124；
            //   而旧的 0.028 内缩只有 12.6px —— 标题整段都在圆外。
            //
            // ⚠️ 也**不能**把这个水平内缩直接当垂直 padding 用：y=0 处需要 112dp，
            // 那是整个半径，内容会被压成零。
            //
            // 正确做法：竖向只留适度白（避免贴边），水平内缩按**每一行自己所在的高度**算。
            // 这一层负责竖向，水平交给各屏自己按行处理（见 [CircleSafeArea.horizontalInsetAt]）。
            val cfg = LocalConfiguration.current
            val shortSideDp = minOf(cfg.screenWidthDp, cfg.screenHeightDp).toFloat()
            val circleInset = CircleSafeArea.ofDp(shortSideDp)
            // settings.rev 让"改了动态取色开关"能触发重组（settings 本身不是 Compose 状态）
            @Suppress("UNUSED_EXPRESSION")
            settings.rev
            WearBookTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                // 模拟屏幕规格时，外面这层负责补黑边并居中；平时它完全透明、不参与视觉
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(if (simRatio != null) Color.Black else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                // 根容器铺满主题背景色（这是深浅色切换时背景不变的原因）
                val bg by animateColorAsState(MaterialTheme.colorScheme.background, tween(280), label = "bg")
                // 用 Surface 兜底：保证整屏底色一定被画出来（投屏 / 多屏协同时不会透出窗口的白色底），
                // 同时给整棵树一个与背景配套的文字颜色，避免出现"白字白底看不见标题"
                Surface(
                    color = bg,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxSize()
                        .then(if (simRatio != null) Modifier.aspectRatio(simRatio) else Modifier)
                ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (settings.roundScreen) Modifier.padding(
                                start = circleInset.start,
                                end = circleInset.end,
                                top = circleInset.top,
                                bottom = circleInset.bottom
                            ) else Modifier
                        )
                ) {
                    AnimatedContent(
                        targetState = screen,
                        // 页面切换用 Material 3 的 **shared axis (X)** 模式：新页面横向进入、
                        // 旧页面向反方向小幅退出。时长取 M3 motion scheme 的
                        // medium1(300ms 进) / short2(200ms 出) —— 早先手写的 260/160 两个数
                        // 既不在 M3 的档位里，快慢也与按压反馈（90/160ms）不成比例。
                        transitionSpec = {
                            val forward = targetState == "reader"
                            val dir = if (forward) 1 else -1
                            (
                                slideInHorizontally(
                                    animationSpec = tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                ) { it * dir } + fadeIn(tween(210, delayMillis = 60))
                            ) togetherWith (
                                slideOutHorizontally(
                                    animationSpec = tween(200, easing = androidx.compose.animation.core.FastOutLinearInEasing)
                                ) { -it * dir / 4 } + fadeOut(tween(140))
                            )
                        },
                        label = "screen"
                    ) { s ->
                        when (s) {
                            "firstrun" -> FirstRunScreen(
                                settings = settings,
                                screenW = resources.displayMetrics.widthPixels,
                                screenH = resources.displayMetrics.heightPixels,
                                screenDpi = resources.displayMetrics.densityDpi,
                                onDone = { screen = "library"; refresh() }
                            )

                            "library" -> LibraryScreen(
                                books = books,
                                covers = covers.toMap(),
                                progress = progress,
                                onOpen = { openBook(it) },
                                onDelete = { meta -> confirm = Confirm("删除《${meta.title}》？",
                                    "图书内容和阅读进度将一并删除，且无法恢复。") { store.remove(meta.id); refresh() } },
                                // 加号 → 先选导入方式（手机传书 / 从设备导入 / 链接导入），
                                // 再由各自入口进文件选择器。不再是直接甩一个根目录文件列表 ——
                                // 那样用户看到一堆系统目录，根本不知道该点哪儿。
                                onImport = { screen = "importmethods" },
                                onScan = { scanAndCollect() },
                                scanning = scanning,
                                onSettings = { screen = "settings" },
                                round = settings.roundScreen
                            )

                            "comic" -> current?.let { (meta, chapters) ->
                                /*
                                 * 漫画页序**必须在后台算**。
                                 *
                                 * 原来写成 `remember(meta.id) { comicPages(chapters) }` —— remember 里
                                 * 的代码在**主线程的组合过程**中同步执行，而 comicPages 要逐段扫全书。
                                 * 和 collectImages 是同一个坑（那边 1102 章实测 2.7 秒）。
                                 * 真机上表现为进漫画页时 `Skipped 173 frames`（约 2.9 秒卡顿）。
                                 */
                                var pages by remember(meta.id) {
                                    mutableStateOf<List<ComicPage>>(emptyList())
                                }
                                var pagesReady by remember(meta.id) { mutableStateOf(false) }
                                LaunchedEffect(meta.id, chapters) {
                                    val computed = withContext(Dispatchers.Default) { comicPages(chapters) }
                                    pages = computed
                                    pagesReady = true
                                }
                                // 恢复上次看到哪一张：先按「章 + 段」精确找，找不到就按百分比
                                val start = remember(meta.id, pages.size, pagesReady) {
                                    val p = store.allProgress()[meta.id]
                                    when {
                                        p == null || pages.isEmpty() -> 0
                                        else -> pages.indexOfFirst { it.chapter == p.chapter && it.para == p.para }
                                            .takeIf { it >= 0 }
                                            ?: (p.frac * pages.size).toInt().coerceIn(0, pages.lastIndex)
                                    }
                                }
                                if (!pagesReady) {
                                    // 还在后台算：给个明确的等待态，而不是空白或卡死
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(strokeWidth = 2.dp)
                                    }
                                } else ComicReader(
                                    bookTitle = meta.title,
                                    chapters = chapters,
                                    bookPath = meta.source,
                                    initialPage = start,
                                    onProgress = { ch, para, page, total ->
                                        saveComicProgress(meta.id, ch, para, page, total)
                                    },
                                    onBack = { comicTurn = null; current = null; screen = "library"; refresh() },
                                    onSwitchToText = { comicTurn = null; screen = "reader" },
                                    onRegisterTurn = { fn -> comicTurn = fn }
                                )
                            }

                            "reader" -> current?.let { (meta, chapters) ->
                                val p = store.progress(meta.id)
                                ReaderScreen(
                                    title = meta.title,
                                    chapters = chapters,
                                    bookPath = meta.source,
                                    settings = settings,
                                    speaker = speaker,
                                    dark = dark,
                                    initialChapter = p?.chapter ?: 0,
                                    initialPage = p?.page ?: 0,
                                    initialPara = p?.para ?: -1,
                                    initialCharStart = p?.charStart ?: 0,
                                    bookmarks = bookmarks,
                                    onProgress = { c, pg, pa, cs, frac ->
                                        store.saveProgress(
                                            meta.id,
                                            com.webook.watch.data.Progress(
                                                chapter = c, page = pg,
                                                para = pa, charStart = cs, frac = frac
                                            )
                                        )
                                        progress = progress.toMutableMap().apply { put(meta.id, frac) }
                                    },
                                    onBack = {
                                        speaker.stop(); readerTurn = null; readerFlush = null
                                        current = null; screen = "library"; refresh()
                                    },
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
                                    onRegisterTurn = { fn -> readerTurn = fn },
                                    /*
                                     * 只有"这本书有插图"时才给漫画模式入口。
                                     * ⚠️ 用 remember 缓存：hasImages 是 any{} 短路扫描，
                                     * 但对**没有插图**的书要扫完全书 —— 每次重组都跑一遍太浪费。
                                     * key 用 meta.id，换书才重算。
                                     */
                                    onComic = if (remember(meta.id) { ComicDetect.hasImages(chapters) }) {
                                        { screen = "comic" }
                                    } else null,
                                    onRegisterFlush = { fn -> readerFlush = fn }
                                )
                            }

                            "import" -> ImportScreen(
                                currentPath = dirStack.lastOrNull()?.absolutePath ?: storageRoot().absolutePath,
                                entries = entries,
                                canGoUp = dirStack.size > 1,
                                // 专属文件夹（应用自己的 /Android/data 目录）不需要任何权限。
                                // 这里若死板地传 storageGranted，用户明明在能读的目录里，
                                // 界面却要求"授予存储权限" —— 而他点了也没用。
                                hasPermission = storageGranted ||
                                    (dirStack.lastOrNull()?.absolutePath?.contains("/Android/data/") == true),
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
                                onOpenAppDir = { openAppDir() },
                                // SAF：把系统支持的电子书类型都列出来，别把用户的书藏掉
                                onOpenDocument = { openDocumentSafely() },
                                onImportLocal = { openImportLocal() },
                                // 手机传书是否在跑（入口行用来提示）
                                wifiOn = wifiServer != null,
                                onOpenMethods = { methodsFrom = "import"; screen = "importmethods" }
                            )

                            // 导入方式独立页：手机传书 / 从设备导入 / 链接导入
                            "importmethods" -> ImportMethodsScreen(
                                wifiOn = wifiServer != null,
                                wifiInfo = wifiInfo,
                                onWifiToggle = { toggleWifiTransfer() },
                                onShowQr = { if (wifiInfo != null) screen = "qr" },
                                onImportLocal = { openImportLocal() },
                                onBrowseStorage = {
                                    ensureStoragePermission()
                                    screen = "import"
                                },
                                urlJob = urlJob,
                                onUrlImport = { startUrlImport(it) },
                                onUrlDismiss = { urlJob = null },
                                onBack = {
                                    screen = if (methodsFrom == "import") "import" else "library"
                                    if (screen == "import") ensureStoragePermission()
                                }
                            )

                            // 二维码页：全屏只放一个码，手机扫一下就能打开传书页面
                            "qr" -> QrTransferScreen(
                                url = qrUrl(),
                                onBack = { screen = "import" }
                            )

                            "pickdir" -> DirPickerScreen(
                                currentPath = pickStack.lastOrNull()?.absolutePath ?: "",
                                entries = pickEntries,
                                canGoUp = pickStack.size > 1,
                                storageBlocked = pickBlocked,
                                onOpen = { e ->
                                    if (pickStack.lastOrNull()?.absolutePath != e.file.absolutePath) pickStack = pickStack + e.file
                                    refreshPickDir()
                                },
                                onGoUp = { if (pickStack.size > 1) { pickStack = pickStack.dropLast(1); refreshPickDir() } },
                                onPick = { pickCurrentDir() },
                                onBack = { screen = "settings" }
                            )

                            "settings" -> SettingsScreen(
                                settings = settings,
                                onChange = { mutate { }; speaker.rate = settings.speechRate; applySystemSettings() },
                                onBack = { screen = "library"; refresh() },
                                onPickDir = { openDirPicker() },
                                onOpenSystemSettings = {
                                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                                        .onFailure { toast("打不开系统设置，请手动去「设置 → 手势」里关闭侧滑返回") }
                                },
                                onClearAll = { confirm = Confirm("清空全部数据？",
                                    "将删除书架上的所有图书、阅读进度与设置，且无法恢复。") { store.clearAll(); refresh() } },
                                round = settings.roundScreen
                            )
                        }
                    }

                    // 统一的 Material 3 对话框（跟随主题、带动画）
                    // 同一本书的新版本：更新 / 两本都留 / 取消
                    pendingUpdate?.let { offer ->
                        UpdateDialog(
                            bookTitle = offer.existing.title,
                            oldChapters = offer.diff.oldChapterCount,
                            newChapters = offer.diff.newChapterCount,
                            summary = offer.diff.summary(),
                            pureAppend = offer.diff.isPureAppend,
                            onUpdate = { applyUpdate(offer) },
                            onKeepBoth = { keepBoth(offer) },
                            onCancel = { pendingUpdate = null }
                        )
                    }

                    // 自己画的紧凑确认框：M3 的 AlertDialog 在 320×360 上会把按钮挤出屏幕，
                    // 长按删除后"确定/取消"都点不到，只能按返回逃出来
                    confirm?.let { c ->
                        ConfirmBox(
                            title = c.title,
                            body = c.body,
                            onConfirm = { c.action(); confirm = null },
                            onDismiss = { confirm = null }
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

    /**
     * 表冠旋转 / 鼠标滚轮翻页。
     *
     * 走 Activity 的 onGenericMotionEvent 而不是 Compose 的 onRotaryScrollEvent：
     * 表冠在系统层就是一个带 ACTION_SCROLL + AXIS_SCROLL 的 MotionEvent，
     * 这样收既不用焦点、不依赖 Compose 版本，还能顺带把"鼠标滚轮"也管上。
     *
     * 复用音量键那条 readerTurn 回调 —— 同一套"上一页 / 下一页"，行为完全一致。
     * 阈值 0.5 是防抖：表冠回弹时会吐一个极小的反向值，不设阈值就会翻过去又弹回来。
     */
    override fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        // AXIS_SCROLL 与 ACTION_SCROLL 都是 API 26 才公开的常量，minSdk 是 24。
        // 直接引用在 24/25 上不会崩（常量是编译期内联的 int），但那些系统的表冠
        // 根本不发这类事件，早退掉省得做无用判断。
        if (android.os.Build.VERSION.SDK_INT < 26) return super.onGenericMotionEvent(event)
        if (event.action == android.view.MotionEvent.ACTION_SCROLL) {
            val turn: ((Boolean) -> Unit)? =
                if (screen == "reader") readerTurn else if (screen == "comic") comicTurn else null
            if (turn != null) {
                val delta = event.getAxisValue(android.view.MotionEvent.AXIS_SCROLL)
                if (delta > 0.5f) { turn(true); return true }
                if (delta < -0.5f) { turn(false); return true }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /** 音量键翻页（在阅读页且开关打开时生效） */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val turn: ((Boolean) -> Unit)? =
            if (screen == "reader") readerTurn else if (screen == "comic") comicTurn else null
        if (settings.volumeKeys && turn != null) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> { turn(true); return true }
                android.view.KeyEvent.KEYCODE_VOLUME_UP -> { turn(false); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }


    private fun onBack() {
        when (screen) {
            "reader" -> { speaker.stop(); readerTurn = null; readerFlush = null; current = null; screen = "library"; refresh() }
            "comic" -> { comicTurn = null; current = null; screen = "library"; refresh() }
            "import", "settings" -> { screen = "library"; refresh() }
            // 目录选择器是从设置页点进来的，按返回就该回设置页。
            // 漏了这一支会掉进下面的 else，变成「再按一次退出」—— 看起来像返回键失灵。
            "pickdir" -> { screen = "settings" }
            // 二维码页是从导入页点进来的，返回就回导入页（不是回书架）
            "qr" -> { screen = "import" }
            // 导入方式页可能来自书架加号，也可能来自导入页的上方入口
            "importmethods" -> {
                screen = if (methodsFrom == "import") "import" else "library"
                if (screen == "library") refresh()
            }
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
        // 优先用进度里存好的精确百分比；老记录没有这个字段，就按章节位置粗估一个
        progress = store.allProgress().mapValues { (_, v) ->
            if (v.frac > 0f) v.frac.coerceIn(0f, 1f)
            else (v.chapter.toFloat() / books.size.coerceAtLeast(1)).coerceAtMost(0.999f)
        }
    }

    private fun openBook(meta: BookMeta) {
        lifecycleScope.launch(Dispatchers.IO) {
            val chapters = store.chapters(meta.id)
            val marks = store.bookmarks(meta.id)
            /*
             * ⚠️ looksLikeComic 必须在 IO 线程算完再回主线程。
             *
             * 原来是在 withContext(Dispatchers.Main) 里面直接调用的 —— 它会遍历
             * 前 SAMPLE 章的每一段做判定，书越大越慢。真机上打开一本书会看到
             * `Skipped 173 frames`（约 2.9 秒），用户以为卡死了。
             */
            val isComic = ComicDetect.looksLikeComic(chapters)
            withContext(Dispatchers.Main) {
                if (chapters.isEmpty()) { toast("这本书没有可读内容"); return@withContext }
                bookmarks = marks
                current = meta to chapters
                // 纯图片书（漫画）直接进漫画模式；图文混排的仍走正文阅读器
                screen = if (isComic) "comic" else "reader"
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
        val raw = dir.listFiles()
        val list = raw ?: emptyArray()
        // 诊断日志：目录"看着有文件却列不出来"时，靠它区分是 listFiles 返回 null、
        // 还是返回了空数组（前者=没权限，后者=真没文件）。release 包里也留着，成本极低。
        android.util.Log.i(
            "WearBook/FM",
            "listFiles(${dir.absolutePath}) raw=${raw?.size ?: "null"} " +
                "canRead=${dir.canRead()} exists=${dir.exists()} -> kept=${list.size}"
        )
        entries = list
            .filter { !it.name.startsWith(".") }
            .map { FmEntry(it.name ?: "?", it.isDirectory, it, !it.isDirectory && isBookFile(it.name)) }
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        // 根目录读到空 → 不是真的空，是系统没放行（真机根目录至少有 Android/ 等）
        // 专属文件夹（/Android/data/...）是应用自己的目录，不受分区存储限制，
        // 空就是真的空，不能判成"被系统锁住"。
        val inOwnDir = dir.absolutePath.contains("/Android/data/")
        storageBlocked = !inOwnDir && dirStack.size == 1 && entries.isEmpty()
    }

    /**
     * 解析并入库。
     * epub 只需要文件本身（走 ZipFile 逐章读），所以 bytes 传 null —— 千万别为了 epub 整读文件，
     * 一本 171MB 的 epub 会把 96MB 堆的手表直接打崩（用户实测 OOM 闪退）。
     */
    /**
     * 一次导入的结局。
     *
     * 多出 [UpdateOffer] 这一支，是因为"同一本书的新版本"不该被当成新书塞进书架 ——
     * 那样会出现两本同名书，而读者的进度还留在旧的那本上。
     */
    private sealed class ImportOutcome {
        class Added(val parsed: ParsedBook) : ImportOutcome()
        class Duplicate(val parsed: ParsedBook) : ImportOutcome()

        /** 书架上已有这本书的旧版本：先不入库，等用户拍板 */
        class UpdateOffer(
            val parsed: ParsedBook,
            val existing: BookMeta,
            val oldChapters: List<Chapter>,
            val diff: BookMerge.Diff,
            val sizeBytes: Long,
            val source: String?
        ) : ImportOutcome()
    }

    private suspend fun parseAndStore(
        name: String, bytes: ByteArray?, epubSrc: File?,
        allowUpdate: Boolean = true
    ): ImportOutcome {
        val sizeBytes = epubSrc?.length() ?: bytes?.size?.toLong() ?: 0L
        val parsed =
            if (name.endsWith(".epub", true) && epubSrc != null) BookParser.epubToBook(epubSrc, name)
            else BookParser.parse(File(""), name, bytes ?: ByteArray(0))
        // epub 记下原文件路径：插图不预先抽取，阅读时按需从这里读
        val source = if (parsed.fmt == "epub") epubSrc?.absolutePath else null
        val existing = store.list()

        // 第一层：一模一样（书名 + 格式 + 章节数都相同）→ 直接当重复，不打扰用户。
        // 用户嫌没反应连点几下，之前会往书架里塞好几本一模一样的
        val exactDup = existing.any {
            it.title == parsed.title && it.fmt == parsed.fmt && it.chapterCount == parsed.chapters.size
        }
        if (exactDup) return ImportOutcome.Duplicate(parsed)

        // 第二层：书名归一化后相同 → 可能是同一本的新版本，深入比一章列表
        if (allowUpdate) {
            val normNew = BookMerge.normTitle(parsed.title)
            if (normNew.isNotEmpty()) {
                val candidate = existing.firstOrNull { BookMerge.normTitle(it.title) == normNew }
                if (candidate != null) {
                    val oldChapters = store.chapters(candidate.id)
                    if (oldChapters.isNotEmpty()) {
                        val d = BookMerge.diff(candidate.title, oldChapters, parsed.title, parsed.chapters)
                        if (d.sameBook) {
                            return ImportOutcome.UpdateOffer(parsed, candidate, oldChapters, d, sizeBytes, source)
                        }
                    }
                }
            }
        }

        store.add(parsed.title, parsed.author, parsed.fmt, sizeBytes, parsed.chapters, parsed.cover, source)
        return ImportOutcome.Added(parsed)
    }

    /** quiet = true 用于批量扫描：一口气收 10 本时不该弹 10 次 toast */
    private suspend fun finishImport(outcome: ImportOutcome, quiet: Boolean = false) {
        withContext(Dispatchers.Main) {
            when (outcome) {
                is ImportOutcome.Added -> {
                    refresh()
                    if (!quiet) toast("已导入《${outcome.parsed.title}》")
                    screen = "library"
                }
                is ImportOutcome.Duplicate -> {
                    refresh()
                    if (!quiet) toast("《${outcome.parsed.title}》已经在书架里了")
                    screen = "library"
                }
                is ImportOutcome.UpdateOffer -> {
                    // 批量扫描不弹窗：一口气扫十本时没法挨个问，直接保留旧的
                    if (quiet) {
                        refresh()
                        toast("《${outcome.parsed.title}》已存在，已跳过")
                        screen = "library"
                    } else {
                        importing = null
                        pendingUpdate = outcome
                    }
                }
            }
        }
    }

    /**
     * 从 SAF 返回的 uri 导入一本书。
     *
     * 要点：
     * 1. 先用 [displayName] 还原文件名 —— uri 本身是 `content://.../1234` 这种，
     *    拿不到扩展名就判断不了格式，书架标题也会变成一串数字。
     * 2. 内容**必须落到我们自己的长期目录**再解析：SAF 授予的读权限只在本次进程存活期内有效，
     *    而 epub 的插图是阅读时才回原文件按需读的，靠不住。
     * 3. 不申请任何存储权限，也就不会有"权限被拒"的分支。
     */
    private fun importFromUri(uri: Uri) {
        if (importing != null) { toast("正在导入，请稍候…"); return }
        val name = displayName(uri) ?: "book.${extHint(uri)}"
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in SUPPORTED_EXT) {
            toast("这个文件不是电子书（.$ext）—— 支持 TXT / EPUB / MOBI / AZW / HTML")
            return
        }
        importing = name.substringBeforeLast('.')
        lifecycleScope.launch(Dispatchers.IO) {
            var tmp: File? = null
            try {
                val isEpub = ext == "epub"
                // epub 直接落到长期目录（插图以后还要回来读）；
                // 其它格式读进内存就完事，放缓存目录即可
                val dir = if (isEpub) keepDir() else File(cacheDir, "saf_import").apply { mkdirs() }
                tmp = File(dir, name)
                contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                } ?: throw IllegalArgumentException("系统没给这个文件的读取权限")

                if (!isEpub && tmp.length() > BookParser.MAX_IN_MEMORY_BYTES) {
                    withContext(Dispatchers.Main) {
                        toast("这本书 ${tmp.length() / 1048576} MB，超过上限 —— 手表内存放不下")
                    }
                    return@launch
                }
                val bytes = if (isEpub) null else tmp.readBytes()
                val outcome = parseAndStore(name, bytes, if (isEpub) tmp else null)
                finishImport(outcome)
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { toast("导入失败：${friendlyError(e)}") }
            } finally {
                // epub 已经在长期目录里了，别删；只有缓存里的临时文件要清
                if (tmp?.absolutePath?.contains("saf_import") == true) runCatching { tmp?.delete() }
                withContext(Dispatchers.Main) { importing = null }
            }
        }
    }

    /** 拿不到显示名时，退而求其次看 MIME，尽量别把格式判断丢光 */
    private fun extHint(uri: Uri): String = when (contentResolver.getType(uri)) {
        "application/epub+zip" -> "epub"
        "application/x-mobipocket-ebook" -> "mobi"
        "application/vnd.amazon.ebook" -> "azw3"
        "text/html" -> "html"
        else -> "txt"
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
                val outcome = parseAndStore(file.name, bytes, file)
                finishImport(outcome)
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

    /**
     * 漫画翻页存进度。
     * 复用同一条 Progress：chapter + para 精确指到"哪一章的第几段（那张图）"，
     * frac 给书架显示百分比。这样从漫画切回正文模式，位置也是对的。
     */
    private fun saveComicProgress(id: String, chapter: Int, para: Int, page: Int, total: Int) {
        val p = Progress(
            chapter = chapter, page = 0, para = para, charStart = 0,
            frac = ((page + 1f) / total.coerceAtLeast(1)).coerceIn(0f, 0.999f),
            updatedAt = System.currentTimeMillis()
        )
        val m = store.allProgress().toMutableMap()
        m[id] = p
        store.saveProgress(m)
    }

    /* ---------------- 同一本书的新版本：更新 / 保留两本 ---------------- */

    /**
     * 用新版本覆盖旧书，并把阅读进度迁过去。
     *
     * 保留原 id（章节文件、封面、进度、书签全都挂在它上面），所以更新完再翻开，
     * 还停在原来那一章 —— 这正是"更新"和"又存了一本"的区别。
     */
    private fun applyUpdate(offer: ImportOutcome.UpdateOffer) {
        pendingUpdate = null
        if (importing != null) { toast("正在导入，请稍候…"); return }
        importing = offer.parsed.title
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 先按标题把旧进度搬到新章节结构上，再落盘
                val oldProgress = store.allProgress()[offer.existing.id] ?: Progress()
                val migrated = BookMerge.migrate(oldProgress, offer.oldChapters, offer.parsed.chapters)

                val ok = store.update(
                    id = offer.existing.id,
                    title = offer.parsed.title,
                    author = offer.parsed.author,
                    fmt = offer.parsed.fmt,
                    size = offer.sizeBytes,
                    chapters = offer.parsed.chapters,
                    cover = offer.parsed.cover,
                    source = offer.source
                )
                if (ok) {
                    val m = store.allProgress().toMutableMap()
                    m[offer.existing.id] = migrated
                    store.saveProgress(m)
                }
                withContext(Dispatchers.Main) {
                    refresh()
                    if (ok) {
                        val where = if (migrated.hasExact) "第 ${migrated.chapter + 1} 章" else "开头附近"
                        toast("已更新到新版（${offer.parsed.chapters.size} 章），\n进度已迁到$where")
                    } else {
                        toast("更新失败：书架里找不到这本书了")
                    }
                    screen = "library"
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { toast("更新失败：${friendlyError(e)}") }
            } finally {
                withContext(Dispatchers.Main) { importing = null }
            }
        }
    }

    /** 当成一本新书存进去（书名可能带版本后缀，两本并存） */
    private fun keepBoth(offer: ImportOutcome.UpdateOffer) {
        pendingUpdate = null
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                store.add(
                    offer.parsed.title, offer.parsed.author, offer.parsed.fmt,
                    offer.sizeBytes, offer.parsed.chapters, offer.parsed.cover, offer.source
                )
                withContext(Dispatchers.Main) {
                    refresh(); toast("已作为新书导入（两本并存）"); screen = "library"
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { toast("导入失败：${friendlyError(e)}") }
            }
        }
    }

    /* ---------------- 手机传书：手表起一个小网页，手机浏览器推进来 ---------------- */

    /**
     * 开/关手机传书。
     *
     * 为什么是这条而不是数据线：手机和手表本来就在同一个 WiFi，用户不用找线、不用装工具，
     * 手机上用自己熟悉的键盘和文件选择器，比在表盘上敲直链轻松得多。
     * 详见 [com.webook.watch.data.WifiTransfer] 的边界说明（只收上传、不暴露任何已有文件）。
     */
    private fun toggleWifiTransfer() {
        val running = wifiServer
        if (running != null) {
            running.stop()
            wifiServer = null
            wifiInfo = null
            wifiQrUrl = null
            if (screen == "qr") screen = "import"
            toast("已停止手机传书")
            return
        }
        val dir = File(cacheDir, "wifi_upload").apply { mkdirs() }
        val server = WifiTransfer.start(dir) { name, file ->
            // 服务线程回调：立刻把它当成一次正常导入处理
            android.util.Log.i("WearBook/WIFI", "upload received: $name -> ${file.absolutePath} (${file.length()} B)")
            runOnUiThread {
                android.util.Log.i("WearBook/WIFI", "dispatch import for $name")
                onWifiUpload(name, file)
            }
        }
        if (server == null) {
            toast("开不起来 —— 手表可能没连上 WiFi")
            return
        }
        wifiServer = server
        val ips = WifiTransfer.localAddresses()
        if (ips.isEmpty()) {
            wifiInfo = "手机连同一个 WiFi，地址：本机:${server.port}"
            wifiQrUrl = null
        } else {
            wifiInfo = ips.joinToString("\n") { "http://$it:${server.port}" }
            // 多网卡时优先挑 192.168.x / 10.x / 172.16~31.x 这类家用网段，
            // 避免把 "172.20.48.1"（虚拟网卡）画进二维码让用户扫不到
            val best = ips.firstOrNull { it.startsWith("192.168.") }
                ?: ips.firstOrNull { it.startsWith("10.") }
                ?: ips.firstOrNull { it.startsWith("172.") }
                ?: ips.first()
            wifiQrUrl = "http://$best:${server.port}"
        }
    }

    /** 二维码要编码的地址；没开服务时给个占位（正常情况下进不了这一页） */
    private fun qrUrl(): String = wifiQrUrl ?: wifiInfo ?: ""

    /** 手机传上来的文件：搬到长期目录后走正常导入流程 */
    private fun onWifiUpload(name: String, file: File) {
        if (importing != null) { toast("正在导入，请稍候…"); return }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val isEpub = name.endsWith(".epub", true)
                val target = if (isEpub) File(keepDir(), name) else file
                if (isEpub) file.copyTo(target, overwrite = true)
                val bytes = if (isEpub) null else target.readBytes()
                val outcome = parseAndStore(name, bytes, if (isEpub) target else null)
                withContext(Dispatchers.Main) {
                    if (isEpub) runCatching { file.delete() }
                    finishImport(outcome, quiet = true)
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { toast("「$name」导入失败：${friendlyError(e)}") }
            }
        }
    }


    /** 只列目录：选书路径时列文件没有意义，还会把列表撑满 */
    private fun refreshPickDir() {
        val dir = pickStack.lastOrNull() ?: run { pickEntries = emptyList(); pickBlocked = false; return }
        val list = dir.listFiles()
        if (list == null) {
            pickEntries = emptyList(); pickBlocked = true; return
        }
        pickEntries = list
            .filter { it.isDirectory && it.canRead() }
            .sortedBy { it.name.lowercase() }
            .map { FmEntry(it.name, true, it) }
        pickBlocked = false
    }

    private fun openDirPicker() {
        pickStack = listOf(storageRoot())
        refreshPickDir()
        screen = "pickdir"
    }

    /** 把当前目录加进扫描列表；已存在就不重复加 */
    private fun pickCurrentDir() {
        val dir = pickStack.lastOrNull() ?: return
        val path = dir.absolutePath
        val existing = settings.scanDirs.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (existing.none { it == path }) {
            settings.scanDirs = (existing + path).joinToString(";")
        }
        toast("已加入扫描目录\n$path")
        screen = "settings"
    }

    /* ---------------- 链接导入：填一个 http(s) 直链，把书下载下来 ---------------- */

    private fun startUrlImport(raw: String) {
        val url = UrlImport.normalize(raw)
        if (url == null) {
            toast("这个链接用不了 —— 只支持 http / https 开头的网址")
            return
        }
        if (importing != null || urlJob != null) { toast("正在导入，请稍候…"); return }
        val dir = File(cacheDir, "url_import").apply { mkdirs() }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var lastPush = 0L
                val file = UrlImport.download(url, dir, AppVersion.userAgent(this@MainActivity)) { read, total ->
                    // 进度节流到 256KB 一次：太密会把小屏界面拖得直掉帧
                    if (read - lastPush >= 256 * 1024) {
                        lastPush = read
                        urlJob = UrlImport.Job(url, read, total)
                    }
                }
                importFetched(file, fromScan = false)
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { urlJob = UrlImport.Job(url, error = friendlyError(e)) }
            }
        }
    }

    /**
     * 一个"下载好的文件"入库。
     * epub 的插图要在阅读时回原 zip 里按需读，所以必须搬进长期目录，cacheDir 会被系统清掉。
     */
    private suspend fun importFetched(downloaded: File, fromScan: Boolean) {
        val name = downloaded.name
        val isEpub = name.endsWith(".epub", true)
        try {
            var src = downloaded
            if (isEpub) {
                val keep = File(keepDir(), name)
                val ok = runCatching {
                    if (keep.exists()) keep.delete()
                    downloaded.renameTo(keep)
                }.getOrDefault(false)
                if (ok) src = keep
            }
            if (!isEpub && src.length() > BookParser.MAX_IN_MEMORY_BYTES) {
                if (src != downloaded) src.delete()
                downloaded.delete()
                withContext(Dispatchers.Main) {
                    toast("这本书 ${src.length() / 1048576} MB，超过上限 ${BookParser.MAX_IN_MEMORY_BYTES / 1048576} MB —— 手表内存放不下")
                }
                return
            }
            // epub 不读成字节数组（ZipFile 逐章读），传 null 省掉一整份文件的内存
            val bytes = if (isEpub) null else src.readBytes()
            val outcome = parseAndStore(name, bytes, if (isEpub) src else null, allowUpdate = !fromScan)
            // 只有真正入库了才记账（重复/更新候选都不算"收进来"）
            if (fromScan && outcome is ImportOutcome.Added) store.markScanned(listOf(BookScanner.keyOf(downloaded)))
            finishImport(outcome, quiet = fromScan)
        } catch (e: Throwable) {
            withContext(Dispatchers.Main) { toast("导入失败：${friendlyError(e)}") }
        } finally {
            withContext(Dispatchers.Main) {
                importing = null
                urlJob = null
            }
        }
    }

    /* ---------------- 扫目录：把别处下载好的书收进书架 ---------------- */

    /**
     * 扫 Download / Downloads / Books 以及应用自己的 books 目录，把没见过的书收进来。
     *
     * 用 "路径|字节数" 记账，所以反复点不会重复导入；文件被换成新内容（大小变了）会被当成新书。
     */
    private fun scanAndCollect() {
        if (scanning || importing != null) { toast("正在导入，请稍候…"); return }
        scanning = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val known = store.scanned()
                // 默认目录 + 用户在设置里自定义的目录（设置项为空时就是纯默认）
                val dirs = BookScanner.resolveDirs(storageRoot(), settings.scanDirs) + File(appBooksDir(), "books")
                val fresh = ArrayList<File>()
                dirs.filter { it.isDirectory }.forEach { d ->
                    BookScanner.booksIn(d).forEach { f ->
                        if (!known.containsKey(BookScanner.keyOf(f))) fresh.add(f)
                    }
                }
                if (fresh.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        toast("扫过了，没有新书（Download / Books 目录里没有没导入过的电子书）")
                    }
                    return@launch
                }
                var okCount = 0
                for (f in fresh) {
                    importing = f.name.substringBeforeLast('.')
                    val before = store.list().size
                    // 复用同一条入库链路；每本都单独 try，一本坏的不该把后面的全带下去
                    importFetched(f, fromScan = true)
                    if (store.list().size > before) okCount++
                }
                withContext(Dispatchers.Main) {
                    toast("扫描完成：新收进 ${okCount} 本（共发现 ${fresh.size} 本）")
                }
            } finally {
                withContext(Dispatchers.Main) { scanning = false }
            }
        }
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
                // epub 的插图要在阅读时从原 zip 里按需读，所以分享进来的这份得留下（存到私有目录）
                val srcFile: File = if (isEpub) {
                    val keep = File(keepDir(), name.substringAfterLast('/').substringAfterLast('\\'))
                    runCatching {
                        if (keep.exists()) keep.delete()
                        tmp.renameTo(keep)
                    }
                    if (keep.exists()) keep else tmp
                } else tmp
                val outcome = parseAndStore(name, if (isEpub) null else tmp.readBytes(), srcFile)
                if (!isEpub) tmp.delete()
                finishImport(outcome)
            } catch (e: Throwable) {
                tmp.delete()
                withContext(Dispatchers.Main) { toast("导入失败：${friendlyError(e)}") }
            } finally {
                withContext(Dispatchers.Main) { importing = null }
            }
        }
    }

    /** 分享导入进来的 epub 留档目录（外部私有目录，不需要任何权限） */
    private fun keepDir(): File {
        val d = File(appBooksDir(), "books")
        if (!d.exists()) d.mkdirs()
        return d
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


    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /**
     * 退到后台/被系统回收前，把阅读进度强制写盘。
     * ReaderScreen 里平时是防抖 500ms 落盘，万一这 500ms 内进程就没了，进度就白读了。
     */
    override fun onStop() {
        readerFlush?.invoke()
        super.onStop()
    }

    override fun onDestroy() { speaker.shutdown(); super.onDestroy() }
}
