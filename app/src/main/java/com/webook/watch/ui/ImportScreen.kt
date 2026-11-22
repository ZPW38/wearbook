package com.webook.watch.ui

import com.webook.watch.R
import com.webook.watch.ui.components.M3Card
import com.webook.watch.ui.components.M3IconAction

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
import androidx.compose.foundation.layout.heightIn
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
    onOpen: (FmEntry) -> Unit,
    onGoUp: () -> Unit,
    onBack: () -> Unit,
    onGrant: () -> Unit,
    onOpenAllFiles: () -> Unit,
    onOpenAppDir: () -> Unit,
    /** 用系统文档界面选书（SAF）：不需要任何存储权限，任何设备上都可用 */
    onOpenDocument: () -> Unit,
    /** 从设备导入：先试 SAF，不可用/空壳时自动落到专属文件夹 */
    onImportLocal: () -> Unit,
    /** 手机传书是否在跑（用于入口行提示） */
    wifiOn: Boolean,
    /** 进「导入方式」独立页 */
    onOpenMethods: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        /*
         * 标题栏：只放「返回 + 标题 + 上级」三个位置。
         *
         * ⚠️ 以前这里塞了 4 个纯图标按钮（从设备导入 / 手机传书 / 链接导入 / 上级），
         * 问题很明显：
         *   1. **全靠猜** —— 文档图标代表"从设备导入"、播放图标代表"手机传书"、
         *      加号代表"链接导入"，三者都对不上直觉，手表上又没地方放 tooltip；
         *   2. 450px 宽的屏放 5 个 44dp 图标会被挤出去，最右那个只剩半个、点不到；
         *   3. 导入方式混在标题栏里，和下面的文件列表没有视觉分区，看着乱。
         *
         * 现在：标题栏回归它该干的（返回 + 标题 + 上级），
         * 三种导入方式改成**带图标和说明的卡片**，放在列表上方的可滚动区里。
         */
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                M3IconAction(R.drawable.ic_arrow_back, "返回", onBack)
                Text(
                    "导入图书", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 2.dp).weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                // 返回上一级：固定在标题栏，不在列表里 —— 目录为空时也一定点得到
                M3IconAction(R.drawable.ic_arrow_upward, "返回上级目录", onGoUp, enabled = canGoUp)
            }
        }

        /*
         * 导入方式：三张卡片，每张都有图标 + 标题 + 一句说明。
         *
         * 为什么做成卡片而不是标题栏图标：手表上没有 tooltip、没有长按提示，
         * 纯图标等于让用户猜。而导入是低频高风险操作（选错就白折腾），
         * 值得多占一行把话说清楚。
         *
         * 顺序按「手表上有多好用」排：
         *   手机传书（最省事，不用线不用权限）
         *   → 从设备导入（有文件管理器时最好用，Wear OS 会自动落到专属文件夹）
         *   → 链接导入（需要一个能直下的网址）
         */
        // 当前路径：一行截断，用户得知道自己现在在哪个目录
        Text(
            currentPath,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp).padding(top = 2.dp, bottom = 2.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )

        // 入口行：点进「导入方式」独立页。
        // 不在这里就地展开 —— 225dp 高的屏上展开后文件列表一行都不剩（实测）。
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            onClick = onOpenMethods,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 4.dp)
        ) {
            Row(
                Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "其它导入方式",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (wifiOn) "传书已开启" else "手机传书 · 链接",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (wifiOn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Android 11+ 分区存储：没开「所有文件访问」时，系统只让看媒体文件，电子书全部被隐藏。
        // ⚠️ 这块在 Wear OS 上是**纯误导**（那个开关根本不存在），而且 225dp 高的屏幕上
        // 它会把下面的文件列表挤成 0 高 —— 用户看到的才是"没有书"。
        // 所以：不再占页面空间，只在列表里以一行条目形式存在（见空态区）。
        // 这里保留状态但完全折叠，真正的引导写在空态文案里。

        // 安卓 10 上 targetSdk≥29：分区存储会把外部存储整个挡住，连根目录都读不到。
        // ⚠️ 但在**专属文件夹**里这条提示是错的：那个目录本来就不需要任何权限，
        // 而且文案里"可以改用下面的专属文件夹"正是用户当前所在的位置 —— 纯误导。
        val inAppDir = currentPath.contains("/Android/data/")
        if (storageBlocked && !needAllFiles && !inAppDir) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
                    .heightIn(max = 88.dp)   // 225dp 的屏上，不限制高度会把文件列表整块挤出去
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(9.dp)) {
                    Text(
                        "系统没放行读取存储",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "外部存储被锁，列不出文件。请改用专属文件夹" +
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
                    "需要存储权限才能浏览文件",
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
                    M3Card(
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
                    if (needAllFiles) "没有可读的电子书"
                    else if (storageBlocked) "存储被锁，列不出文件"
                    else "此目录为空，或没有可读的子目录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (needAllFiles) "开启「所有文件访问」可看到全部文件"
                    else "支持 TXT / EPUB / MOBI / AZW / HTML",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                // Wear OS 上系统选择器是空壳、也访问不了系统文件，专属文件夹才是正路。
                // 这段必须放在**可滚动的空态区**里：放上面会把文件列表挤成 0 高。
                Text(
                    "看不到系统文件？把书放进专属文件夹即可，无需权限",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, start = 6.dp, end = 6.dp)
                )
                // 万一系统就是不给读（部分手表 ROM 锁得死），还有一条不需要权限的路
                Text(
                    "也可在 MT 管理器里分享图书给腕上书",
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



private fun mb(b: Long): String = when {
    // Locale.ROOT：这几个是纯数值展示，指定 Locale 才能避免
    // 在阿拉伯语/印地语等区域把小数点格式化成别的字符（lint 也会报 DefaultLocale）
    b >= 1048576 -> String.format(java.util.Locale.ROOT, "%.1f MB", b / 1048576.0)
    b >= 1024 -> String.format(java.util.Locale.ROOT, "%.1f KB", b / 1024.0)
    else -> "$b B"
}
