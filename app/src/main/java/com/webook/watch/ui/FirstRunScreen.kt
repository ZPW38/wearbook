package com.webook.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.webook.watch.data.Settings

/**
 * 首次启动引导。
 *
 * 为什么值得单独做一屏：这块屏幕的规格（圆的还是方的、DPI 高不高）决定了整套排版，
 * 而不同手表的差异又特别大 —— 320×360 配 density 240 只有 213dp 可用宽，
 * 配 160 就是 320dp。与其让用户进设置里一个个找，不如在第一次打开时就问清楚。
 *
 * 这一步只做三件事：认出屏幕、问形状、问字号。三下点完就能开始读。
 */
@Composable
fun FirstRunScreen(
    settings: Settings,
    screenW: Int,
    screenH: Int,
    screenDpi: Int,
    onDone: () -> Unit
) {
    var round by remember { mutableStateOf(settings.roundScreen) }
    var scale by remember { mutableStateOf(settings.uiScale) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            // 内容不满一屏时居中：手机/大屏上不会全堆在顶上，手表上正好铺满
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "腕上书",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "先认一下你这块表",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )

            // 检测到的屏幕信息：只读，但要让用户看得见"我们认到了什么"
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        "检测到：$screenW × $screenH · $screenDpi dpi",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "约 ${(screenW * 160f / screenDpi).toInt()} × ${(screenH * 160f / screenDpi).toInt()} dp 可用空间",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            // 1. 屏幕形状
            Text(
                "屏幕是圆的还是方的？",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 14.dp).fillMaxWidth()
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PickCard("圆屏", "四角加安全边距", round, Modifier.weight(1f)) { round = true }
                PickCard("方屏", "用满整个屏幕", !round, Modifier.weight(1f)) { round = false }
            }

            // 2. 界面大小
            Text(
                "字和按钮多大合适？",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 14.dp).fillMaxWidth()
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PickCard("小", "看得更多", scale < 0.95f, Modifier.weight(1f), compact = true) { scale = 0.88f }
                PickCard("标准", "推荐", scale in 0.95f..1.05f, Modifier.weight(1f), compact = true) { scale = 1f }
                PickCard("大", "看得更清", scale > 1.05f, Modifier.weight(1f), compact = true) { scale = 1.14f }
            }

            Text(
                "之后可以在「设置」里随时改，也能填具体的屏幕规格。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp)
            )

            Button(
                onClick = {
                    settings.roundScreen = round
                    settings.uiScale = scale
                    settings.firstRunDone = true
                    onDone()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Text("开始读", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun PickCard(
    title: String,
    sub: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.clickable { onClick() }
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = if (compact) 7.dp else 9.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface
            )
            if (!compact) {
                Text(
                    sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
