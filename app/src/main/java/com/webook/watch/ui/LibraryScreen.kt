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

            /*
             * 整页一条可滚动列表：第一项是整行的「导入图书」，后面才是书。
             *
             * 之前用悬浮 FAB，在圆屏上要么被弧线切、要么缩到手指点不中。
             * 做成列表行之后：点击区有整行宽、跟着列表滚、永远不会被裁切，
             * 而且空态和满书架时入口位置一致，不用从头找加号。
             */
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    horizontal = if (round) 20.dp else 10.dp,
                    vertical = 6.dp
                )
            ) {
                // 导入行：整行可点，56dp 高，和书卡片同宽
                item(key = "import-row") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        onClick = onImport,
                        modifier = Modifier.fillMaxWidth().height(56.dp).padding(vertical = 3.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                "导入图书",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }

                if (books.isEmpty()) {
                    item(key = "empty") {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_auto_stories),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                "书架上还没有书",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                            Text(
                                "点上面的导入图书，选一种方式把书放进来",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                } else {
                    itemsIndexed(books, key = { _, b -> b.id }) { index, b ->
                        BookRow(
                            book = b,
                            cover = covers[b.id],
                            progress = (progress[b.id] ?: 0f).coerceIn(0f, 1f),
                            onClick = { onOpen(b) },
                            onLongClick = { onDelete(b) },
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(220),
                                placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                fadeOutSpec = tween(160)
                            )
                        )
                    }
                }
            }
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
