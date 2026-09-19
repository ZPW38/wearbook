package com.webook.watch.ui

import com.webook.watch.R

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
    onOpen: (FmEntry) -> Unit,
    onGoUp: () -> Unit,
    onBack: () -> Unit,
    onGrant: () -> Unit,
    onOpenAllFiles: () -> Unit
) {
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
                modifier = Modifier.padding(start = 4.dp).weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        }
        // 当前路径（可滚动截断，让用户知道在哪儿）
        Text(
            currentPath, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 4.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )

        // Android 11+ 分区存储：没开「所有文件访问」时，系统只让看媒体文件，电子书全部被隐藏
        if (needAllFiles) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Column(Modifier.padding(11.dp)) {
                    Text(
                        "看不到电子书文件？",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "安卓 11 及以上系统只允许看到音乐、图片等媒体文件。开启「所有文件访问」后返回，epub / txt 等图书就会出现。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                    Button(onClick = onOpenAllFiles, modifier = Modifier.padding(top = 8.dp)) {
                        Text("开启「所有文件访问」")
                    }
                }
            }
        }

        when {
            !hasPermission -> Column(
                Modifier.fillMaxSize().padding(16.dp),
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
                Modifier.fillMaxSize().padding(top = 6.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                if (canGoUp) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            onClick = onGoUp,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).animateItem()
                        ) {
                            Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(painter = painterResource(R.drawable.ic_arrow_back), contentDescription = "上级", modifier = Modifier.padding(end = 8.dp))
                                Text(".. 返回上级目录", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
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

            else -> Column(
                Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    if (needAllFiles) "此目录下没有系统允许读取的电子书"
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
            }
        }
    }
}
