package com.webook.watch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.webook.watch.data.QrCode

/**
 * 手机传书的二维码页：全屏只干一件事 —— 把地址画成手机能扫的二维码。
 *
 * 为什么独立成页而不是塞在导入页里：
 *   450×450@320dpi 的 Wear OS 屏只有 225×225dp，标题栏 + 地址卡片之后剩不到 130dp，
 *   那个尺寸的二维码手机很难扫中。全屏能让码画到 ~160dp（约 2cm），好扫得多。
 *
 * 配色刻意用**纯黑码、纯白底**，不跟随主题：深色主题下反色二维码很多读码器不认，
 * 而这是功能性图形，不该讲风格。
 */
@Composable
fun QrTransferScreen(
    url: String,
    onBack: () -> Unit
) {
    val matrix = remember(url) { QrCode.encode(url) }

    Column(
        Modifier.fillMaxSize().padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (matrix == null) {
            // 只有地址超过 42 字节才会走到这里（正常局域网地址最长约 29 字节）。
            // 与其画一个扫不出来的码，不如明确告诉用户怎么办。
            Text(
                "地址太长，画不出二维码\n请在手机上手动输入下面的地址",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Text(
                url,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
            )
            TextButton(onClick = onBack) { Text("返回") }
            return@Column
        }

        // 白底卡片：保证静区是白的，码周至少留 2 格空白
        Box(
            Modifier
                .weight(1f)
                .aspectRatio(1f)
                .background(Color.White, RoundedCornerShape(10.dp))
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val n = matrix.size
                // 静区留 2 个模块宽（标准要求 4，但屏太小；实测 2 格足够读码器定位）
                val quiet = 2
                val total = n + quiet * 2
                val cell = minOf(size.width, size.height) / total
                val ox = (size.width - cell * total) / 2f
                val oy = (size.height - cell * total) / 2f
                for (y in 0 until n) for (x in 0 until n) {
                    if (!matrix.dark(x, y)) continue
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(ox + (x + quiet) * cell, oy + (y + quiet) * cell),
                        // +0.5f 防止相邻黑块之间出现缝（浮点取整导致的细白线会被读码器当作分隔）
                        size = Size(cell + 0.5f, cell + 0.5f)
                    )
                }
            }
        }

        Text(
            url,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp)
        )
        TextButton(onClick = onBack) { Text("返回", style = MaterialTheme.typography.labelMedium) }
    }
}
