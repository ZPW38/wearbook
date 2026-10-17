package com.webook.watch.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.webook.watch.R

/**
 * 只选目录的文件浏览器：给"扫目录收书"选路径用。
 *
 * 为什么不让人手输路径：
 *   1. 手表没有好用的键盘，敲 `/storage/emulated/0/` 这种路径能累死人；
 *   2. 更关键的是**打错一个字母不会报错**，只是扫不到书，用户根本不知道为什么；
 *   3. 有些目录名带空格、中文，手输时 shell 和剪贴板都会坏掉。
 *
 * 所以：列表里只出现目录（不显示文件，选了也没意义），点进去浏览，
 * 觉得对了就按底下的「选为此目录」。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DirPickerScreen(
    currentPath: String,
    entries: List<FmEntry>,
    canGoUp: Boolean,
    storageBlocked: Boolean,
    onOpen: (FmEntry) -> Unit,
    onGoUp: () -> Unit,
    onPick: () -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    "选择要扫描的文件夹",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
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
        Text(
            currentPath,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )

        when {
            storageBlocked -> Column(
                Modifier.weight(1f).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "系统没放行读取存储，列不出目录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            entries.isEmpty() -> Column(
                Modifier.weight(1f).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "这个文件夹里没有子文件夹",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "可以直接选当前这一层",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            else -> LazyColumn(
                Modifier.weight(1f).padding(top = 6.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(entries, key = { it.file.absolutePath }) { e ->
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
                                painter = painterResource(R.drawable.ic_folder),
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                e.name, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // 「选为此目录」固定在最下面：不管滚到哪里都点得到
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "选好后，书架上的文件夹按钮会扫这一层",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onPick,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                    modifier = Modifier.height(34.dp)
                ) { Text("选为此目录", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}
