package com.webook.watch.ui

import com.webook.watch.R

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 内置文件管理器里的一条记录（直接用 java.io.File，不调用系统选择器） */
data class FmEntry(
    val name: String,
    val isDir: Boolean,
    val file: java.io.File,
    /** 是否为本应用能解析的电子书格式 */
    val isBook: Boolean = true
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImportScreen(
    currentPath: String,
    entries: List<FmEntry>,
    canGoUp: Boolean,
    hasPermission: Boolean,
    needAllFiles: Boolean,
    storageBlocked: Boolean,
    /** 链接导入的进行中状态 / 失败原因（null = 空闲） */
    urlJob: com.webook.watch.data.UrlImport.Job?,
    onUrlImport: (String) -> Unit,
    onUrlDismiss: () -> Unit,
    onOpen: (FmEntry) -> Unit,
    onGoUp: () -> Unit,
    onBack: () -> Unit,
    onGrant: () -> Unit,
    onOpenAllFiles: () -> Unit,
    onOpenAppDir: () -> Unit,
    /** 用系统文档界面选书（SAF）：不需要任何存储权限，任何设备上都可用 */
    onOpenDocument: () -> Unit
) {
    var urlText by remember { mutableStateOf("") }
    var urlExpanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                "导入图书", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 2.dp).weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            // 专属文件夹：**Wear OS 上唯一真正能用的本地导入通道**（/sdcard/Android/data/<包名>/files，
            // 应用自己的目录，不需要任何权限，数据线放书进去就能读）。
            // ⚠️ 放进标题栏而不是列表上方：450×450@320dpi 只有 225×225dp，
            // 列表外面每多一行控件，文件列表就被挤掉一截，最后直接看不见。
            IconButton(onClick = onOpenAppDir) {
                Icon(
                    painter = painterResource(R.drawable.ic_folder),
                    contentDescription = "专属文件夹",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            // 系统选择器：手机上很好用；Wear OS 上是空壳，点了会提示改走专属文件夹
            IconButton(onClick = onOpenDocument) {
                Icon(
                    painter = painterResource(R.drawable.ic_description),
                    contentDescription = "从系统文件选书",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 链接导入：列表有内容时这张卡片会收起来，入口就靠这个 ＋（展开后卡片会自己出来）
            IconButton(onClick = { urlExpanded = !urlExpanded }) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = "链接导入",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 返回上一级：固定在标题栏，不在列表里 —— 目录为空时也一定点得到
            IconButton(onClick = onGoUp, enabled = canGoUp) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_upward),
                    contentDescription = "返回上级目录",
                    tint = MaterialTheme.colorScheme.onSurface
                        .copy(alpha = if (canGoUp) 1f else 0.28f)
                )
            }
        }
        }
        // 当前路径（可滚动截断，让用户知道在哪儿）
        Text(
            currentPath, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 4.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )


        // Android 11+ 分区存储：没开「所有文件访问」时，系统只让看媒体文件，电子书全部被隐藏。
        // ⚠️ 这块在 Wear OS 上是**纯误导**（那个开关根本不存在），而且 225dp 高的屏幕上
        // 它会把下面的文件列表挤成 0 高 —— 用户看到的才是"没有书"。
        // 所以：不再占页面空间，只在列表里以一行条目形式存在（见空态区）。
        // 这里保留状态但完全折叠，真正的引导写在空态文案里。

        // 安卓 10 上 targetSdk≥29：分区存储会把外部存储整个挡住，连根目录都读不到
        if (storageBlocked && !needAllFiles) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Column(Modifier.padding(11.dp)) {
                    Text(
                        "系统没放行读取存储",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "这台设备把外部存储锁住了，所以一个文件都列不出来（不是真的空）。" +
                            "可以改用下面这个「专属文件夹」——那是本应用自己的目录，任何系统都允许读写。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                    Button(onClick = onOpenAppDir, modifier = Modifier.padding(top = 8.dp)) {
                        Text("打开专属文件夹")
                    }
                }
            }
        }

        /* 链接导入：填一个能直接下载的 http(s) 直链，书就落到书架里。
           卡片默认折叠 —— 320x360 的屏上，展开的输入框会把下面的本地文件列表挤没。
           ⚠️ 只在**折叠状态**下贴着列表上方显示；展开时必须整块让位（见下）。
           更关键的是：225dp 高的 Wear OS 屏上，即使折叠的卡片也会吃掉一行文件的位置，
           所以它在列表为空时才常驻，列表有内容时收成标题栏那枚 ＋ 图标。 */
        if (entries.isEmpty() || urlExpanded) {
            UrlImportCard(
                job = urlJob,
                url = urlText,
                expanded = urlExpanded,
                onToggle = { urlExpanded = !urlExpanded },
                onUrlChange = { urlText = it },
                onStart = onUrlImport,
                onDismiss = onUrlDismiss
            )
        }

        when {
            // ⚠️ 这两处（无权限提示 / 空态提示）以前写的是 fillMaxSize()：
            // 叠在标题栏下面就超出屏幕高度，被父 Column 裁掉 —— 结果是**本地文件列表整个看不见**，
            // 连"打开专属文件夹"按钮也一起没了，看上去就像"本地导入功能被删了"。
            // 正确写法是 weight(1f)：只占标题栏/路径之外剩下的那块。
            !hasPermission -> Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "需要存储读取权限，才能浏览设备上的图书文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onGrant, modifier = Modifier.padding(top = 8.dp)) { Text("授予权限") }
            }

            entries.isNotEmpty() -> LazyColumn(
                Modifier.weight(1f).padding(top = 2.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                // ⚠️ 列表里**不再放「返回上级目录」行**：标题栏已经有 ↑ 图标，
                // 而 225dp 高的屏幕上多这一行（44dp）就把后面两项文件挤出屏幕外。
                items(entries, key = { it.file.absolutePath }) { e ->
                    val dim = !e.isDir && !e.isBook
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        onClick = { onOpen(e) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .animateItem()
                    ) {
                        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(if (e.isDir) R.drawable.ic_folder else R.drawable.ic_description),
                                contentDescription = null,
                                tint = if (dim) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                else androidx.compose.material3.LocalContentColor.current,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    e.name, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    else androidx.compose.ui.graphics.Color.Unspecified
                                )
                                if (dim) {
                                    Text(
                                        "非电子书格式",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 可滚动：这段文案 + 两个按钮在 320x360 上比可用高度还高，
            // 不给滚动的话下面的「打开专属文件夹」「返回上级目录」会被挤出去点不到
            else -> Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    if (needAllFiles) "此目录下没有系统允许读取的电子书"
                    else if (storageBlocked) "系统没有放行读取存储，所以这里列不出文件"
                    else "此目录为空，或没有可读的子目录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (needAllFiles) "点上面的按钮开启「所有文件访问」后可看到全部文件"
                    else "支持 TXT / EPUB / MOBI / AZW / HTML",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                // Wear OS 上系统选择器是空壳、也访问不了系统文件，专属文件夹才是正路。
                // 这段必须放在**可滚动的空态区**里：放上面会把文件列表挤成 0 高。
                Text(
                    "手表上看不到系统文件？\n" +
                        "把书放进「专属文件夹」（数据线连电脑，或用手机端工具传），\n" +
                        "再点上面的按钮就能看到。这一步不需要任何权限。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, start = 6.dp, end = 6.dp)
                )
                // 万一系统就是不给读（部分手表 ROM 锁得死），还有一条不需要权限的路
                Text(
                    "兜底：在「MT 管理器」里长按图书 → 分享 → 选「腕上书」\n不用任何权限",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 10.dp, start = 6.dp, end = 6.dp)
                )
                if (canGoUp) {
                    Button(onClick = onOpenAppDir, modifier = Modifier.padding(top = 14.dp)) {
                        Text("打开专属文件夹")
                    }
                }
                if (canGoUp) {
                    Button(onClick = onGoUp, modifier = Modifier.padding(top = 8.dp)) {
                        Text("返回上级目录")
                    }
                }
            }
        }
    }
}

/**
 * 链接导入卡片。
 *
 * 只做一件事：把用户填的 http(s) 直链下载成书。能不能下取决于那个链接本身 ——
 * 无痕窗口里不登录就能存成文件的链接，这里就能下；需要登录或验证码的，这里同样下不到。
 * 这里不做任何站点适配、不注入 Cookie，也不维护接口地址，详见 data/UrlImport.kt。
 */
@Composable
private fun UrlImportCard(
    job: com.webook.watch.data.UrlImport.Job?,
    url: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onUrlChange: (String) -> Unit,
    onStart: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 7.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_description),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp).padding(end = 5.dp)
                )
                Text(
                    "链接导入",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (expanded) "−" else "＋",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 2.dp)
                )
            }
            // 折叠态下只留标题这一行（约 28dp），把整块屏幕让给下面的文件列表
            if (!expanded && job == null) return@Column
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (job?.error != null) {
                    Text(
                        "关闭",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { onDismiss() }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
                Button(
                    onClick = { onStart(url) },
                    enabled = url.isNotBlank() && job == null,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(
                        if (job == null) "下载" else "下载中",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                singleLine = true,
                enabled = job == null,
                placeholder = {
                    Text("https:// 直链，如 xxx.epub", style = MaterialTheme.typography.labelSmall)
                },
                textStyle = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
            )

            val err = job?.error
            when {
                err != null -> Text(
                    "✗ $err",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )

                job != null -> {
                    // 服务器没给 Content-Length 时 total=0，这时 progress 拿不到，就显示已下载了多少
                    val frac = job.fraction
                    LinearProgressIndicator(
                        progress = { if (frac >= 0f) frac else 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    Text(
                        if (frac >= 0f) {
                            "${mb(job.read)} / ${mb(job.total)}"
                        } else {
                            "已下载 ${mb(job.read)}（服务器没给总大小）"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }

                else -> Text(
                    "只支持不需要登录、点开就能存成文件的直链",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

private fun mb(b: Long): String = when {
    // Locale.ROOT：这几个是纯数值展示，指定 Locale 才能避免
    // 在阿拉伯语/印地语等区域把小数点格式化成别的字符（lint 也会报 DefaultLocale）
    b >= 1048576 -> String.format(java.util.Locale.ROOT, "%.1f MB", b / 1048576.0)
    b >= 1024 -> String.format(java.util.Locale.ROOT, "%.1f KB", b / 1024.0)
    else -> "$b B"
}
