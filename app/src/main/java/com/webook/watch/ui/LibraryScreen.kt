package com.webook.watch.ui

import com.webook.watch.R

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.webook.watch.data.BookMeta

@Composable
fun LibraryScreen(
    books: List<BookMeta>,
    covers: Map<String, Bitmap?>,
    progress: Map<String, Float>,
    onOpen: (BookMeta) -> Unit,
    onDelete: (BookMeta) -> Unit,
    onImport: () -> Unit,
    /** 扫目录收书：把 Download / Books 里已经下载好的书直接收进书架 */
    onScan: () -> Unit,
    scanning: Boolean,
    onSettings: () -> Unit,
    /** 圆屏：标题栏要按自身高度额外内缩，否则最右侧图标会跑到圆外 */
    round: Boolean = false
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            /*
             * 圆屏标题栏：**不用矩形底色**。
             *
             * 之前是把底色做成大圆角矩形去"贴"圆弧，结果四角照样被圆切掉，
             * 看起来像一张方纸硬按在表盘上 —— 这就是"边距很丑"的来源。
             *
             * 圆屏的正确做法是**让不该被切的东西压根不存在**：标题居中（只占中轴最宽处）、
             * 图标也在中轴附近，去掉底色之后，被弧线切到的区域什么也没有。
             */
            if (round) {
            // 圆屏：标题和两个图标压成一行，省下约 60dp 给卡片和大加号。
            // 表盘顶部弧线处很窄，一行居中排版正好落在最宽的中轴上。
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onScan, enabled = !scanning) {
                    if (scanning) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_folder),
                            contentDescription = "扫描目录收书",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "书架",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
                IconButton(onClick = onSettings) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings),
                        contentDescription = "设置",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "我的书架",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).padding(start = 8.dp)
                        )
                        // 扫目录收书：别的应用（下载器 / MT / 微信）已经把书放好的话，
                        // 点一下就把 Download、Books 里的新书收进来，不用一个个点导入
                        IconButton(onClick = onScan, enabled = !scanning) {
                            if (scanning) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.ic_folder),
                                    contentDescription = "扫描目录收书",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        IconButton(onClick = onSettings) {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings),
                                contentDescription = "设置",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            if (books.isEmpty()) {
                Column(
                    // ⚠️ 必须用 weight(1f)，不能 fillMaxSize()：
                    // 标题栏已经占掉一截，fillMaxSize 会让这一栏再撑满整屏高度，
                    // 父 Column 装不下就把底部裁掉 —— 空态那两行说明文字会被切掉半行（实测过）。
                    // 底部再留 76dp 让开右下角那个悬浮「＋」。
                    Modifier
                        .weight(1f)
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 92.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_auto_stories), contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                    Text("书架上还没有书", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 10.dp))
                    Text(
                        "点右下角的 ＋ 从设备里导入电子书\n支持 TXT / EPUB / MOBI / AZW / HTML",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else {
                // 圆屏 FAB 抬高了，列表底部留白同步加大，否则最后一行会被 FAB 压住
                LazyColumn(
                    // weight(1f) 让列表只占标题之外的剩余高度，不再撑满整屏和 FAB 重叠
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = if (round) 96.dp else 76.dp)
                ) {
                    itemsIndexed(books, key = { _, b -> b.id }) { index, b ->
                        /*
                         * 圆屏：每一行按它在屏幕上的高度**单独**算左右内缩。
                         *
                         * 这就是圆屏适配的核心 —— 不是整页一个 padding，而是
                         * 越靠上/下的行左右收得越多，让卡片边缘始终贴着圆弧内侧。
                         * 固定 padding 做不到这点：给多了中间几行白白变窄，
                         * 给少了顶部的行角上还是被切。
                         */
                        // 圆屏：卡片沿中轴排布，两侧各留 20dp。
                        // 不按行动态收窄 —— 表盘中部本来就宽，动态算反而把书名挤成省略号。
                        val rowInset = if (round) 20.dp else 10.dp
                        BookRow(
                            book = b,
                            cover = covers[b.id],
                            progress = (progress[b.id] ?: 0f).coerceIn(0f, 1f),
                            onClick = { onOpen(b) },
                            onLongClick = { onDelete(b) },
                            // 图书增删时列表项有出现/移动/消失动画
                            modifier = Modifier
                                .padding(horizontal = rowInset)
                                .animateItem(
                                    fadeInSpec = tween(220),
                                    placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                    fadeOutSpec = tween(160)
                                )
                        )
                    }
                }
            }
        }

        /*
         * 圆屏：加号放到正下方中轴。
         *
         * 之前贴右下角，45° 方向圆弧会把它切掉一半，为了躲弧线只能缩到 40dp ——
         * 结果手指几乎点不中（用户反馈只能用触控笔勉强点到）。
         * 表盘正下方是整圆最宽处，把加号放这儿可以用标准 56dp 大按钮，
         * 既完整落在圆内，拇指也点得中。方屏保持右下角不变。
         */
        FloatingActionButton(
            onClick = onImport,
            modifier = if (round) {
                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).size(56.dp)
            } else {
                Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp)
            },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(painter = painterResource(R.drawable.ic_add), contentDescription = "导入")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(
    book: BookMeta,
    cover: Bitmap?,
    progress: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 按压缩放：手表屏小、无震动反馈，没有按压态时用户会怀疑点没点上，于是连点导致误翻页
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(if (pressed) 90 else 160),
        label = "bookPress"
    )
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .scale(scale)
            .combinedClickable(
                interactionSource = interaction,
                indication = androidx.compose.material3.ripple(),
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp, 60.dp)
            ) {
                if (cover != null) {
                    Image(
                        cover.asImageBitmap(), contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            (book.title.trim().firstOrNull() ?: '书').toString(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    book.title, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        if (book.author.isNotBlank()) append(book.author).append(" · ")
                        append(book.fmt.uppercase()).append(" · ").append(book.chapterCount).append(" 章")
                        if (progress > 0f) append(" · 已读 ").append((progress * 100).toInt()).append("%")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp, bottom = 5.dp)
                )
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }
        }
    }
}
