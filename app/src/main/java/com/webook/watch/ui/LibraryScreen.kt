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
             * 标题栏。
             *
             * ⚠️ 圆屏上**不能靠加水平 padding 来避让弧线** —— 试过，会算出一个
             * 60dp 以上的内缩（450px 屏、标题栏所在高度），标题栏被压得一个字都放不下。
             *
             * 真正的原因是布局方向错了：圆屏顶部是一条弧，两端最窄、中间最宽。
             * 把「标题 + 两个图标」硬排成一行，两端必然顶到弧线上。
             *
             * 所以圆屏改成**上下两行**：标题独占一行（居中，只占中间最宽处），
             * 两个图标放第二行（仍然在中间区域，不会碰到两端）。
             * 方屏保持原来的一行排布，不浪费高度。
             */
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                // 圆屏：顶部是整条弧线。方形底色会在四角露出「方纸贴在圆屏上」的硬边，
                // 用大圆角 + 左右内缩让底色跟着弧线走（该高度圆内只剩约 306px 宽）。
                // 四角都收：圆屏顶部是弧线，只收下方两角的话上方会露出方角
                shape = if (round) RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp,
                                                     bottomStart = 28.dp, bottomEnd = 28.dp)
                        else RoundedCornerShape(0.dp),
                modifier = if (round) Modifier.fillMaxWidth().padding(horizontal = 6.dp)
                           else Modifier.fillMaxWidth()
            ) {
                if (round) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "我的书架",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            Modifier.padding(top = 2.dp),
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
                } else {
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
                    }   // Row 结束（方屏一行布局）
                }       // else 结束
            }           // Surface 结束

            if (books.isEmpty()) {
                Column(
                    // ⚠️ 必须用 weight(1f)，不能 fillMaxSize()：
                    // 标题栏已经占掉一截，fillMaxSize 会让这一栏再撑满整屏高度，
                    // 父 Column 装不下就把底部裁掉 —— 空态那两行说明文字会被切掉半行（实测过）。
                    // 底部再留 76dp 让开右下角那个悬浮「＋」。
                    Modifier
                        .weight(1f)
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 84.dp),
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
                // 圆屏 FAB 抬高了 10dp，列表底部留白同步加大，否则最后一行会被 FAB 压住
                LazyColumn(contentPadding = PaddingValues(bottom = if (round) 92.dp else 76.dp)) {
                    items(books, key = { it.id }) { b ->
                        BookRow(
                            book = b,
                            cover = covers[b.id],
                            progress = (progress[b.id] ?: 0f).coerceIn(0f, 1f),
                            onClick = { onOpen(b) },
                            onLongClick = { onDelete(b) },
                            // 图书增删时列表项有出现/移动/消失动画
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

        FloatingActionButton(
            onClick = onImport,
            // 圆屏：底部两角往里收，FAB 贴右下会盖住书名和进度条（实测「已读 60%」被遮）。
            // 往中间挪一点、再抬高一点，既在圆内也不压内容。
            // 圆屏：缩小到 48dp 并贴到右下角 —— 全尺寸 FAB 会盖住卡片的书名与进度条
            // （实测「TXT · 60 章 · 已读 60%」被遮掉一半）。
            modifier = Modifier.align(Alignment.BottomEnd)
                .then(if (round) Modifier.size(48.dp) else Modifier)
                .padding(end = if (round) 18.dp else 16.dp,
                         bottom = if (round) 18.dp else 16.dp),
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
