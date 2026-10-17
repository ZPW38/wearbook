package com.webook.watch.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import com.webook.watch.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import com.webook.watch.data.AppVersion
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.webook.watch.data.Settings

@Composable
fun SettingsScreen(
    settings: Settings,
    onChange: () -> Unit,
    /** 打开内置文件管理器选一个目录，作为「扫目录收书」的目标 */
    onPickDir: () -> Unit,
    onBack: () -> Unit,
    onClearAll: () -> Unit,
    onOpenSystemSettings: () -> Unit
) {
    var fs by remember { mutableStateOf(settings.fontSize) }
    var lh by remember { mutableStateOf(settings.lineSpacing) }
    var auto by remember { mutableStateOf(settings.autoSeconds.toFloat()) }
    var rate by remember { mutableStateOf(settings.speechRate) }
    var theme by remember { mutableStateOf(settings.theme) }
    var round by remember { mutableStateOf(settings.roundScreen) }
    var edge by remember { mutableStateOf(settings.blockEdgeSwipe) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    Column(
        Modifier.fillMaxSize()
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    "设置", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(200)) + slideInVertically(tween(240)) { it / 4 }
        ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SliderRow("正文字号", fs, 14f, 26f, 1f, { v -> "${v.toInt()}px" }) {
                fs = it; settings.fontSize = it; onChange()
            }
            SliderRow("行距", lh, 1.3f, 2.2f, 0.1f, { v -> String.format("%.1f", v) }) {
                lh = it; settings.lineSpacing = it; onChange()
            }
            SwitchRow("自动翻页", auto > 0f) { on ->
                // 开：回到上次的秒数；关：秒数归零，但 autoRemember 记着，下次开还在原处
                auto = if (on) settings.autoRemember.toFloat() else 0f
                settings.autoSeconds = auto.toInt(); onChange()
            }
            if (auto > 0f) {
                SliderRow(
                    "翻页间隔",
                    auto.coerceIn(1f, 30f), 1f, 30f,
                    step = 0f,          // 0 = 无级连续调节（以前是 5 秒一档，0/5/10/.../60）
                    display = { v -> "${Math.round(v)} 秒" }
                ) { v ->
                    auto = Math.round(v).toFloat().coerceIn(1f, 30f)
                    settings.autoSeconds = auto.toInt()
                    settings.autoRemember = auto.toInt()
                    onChange()
                }
            }
            SliderRow("朗读速度", rate, 0.5f, 1.5f, 0.05f, { v -> String.format("%.2f 倍", v) }) {
                rate = it; settings.speechRate = it; onChange()
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("主题", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        listOf("auto" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (v, label) ->
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = if (theme == v) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                                onClick = { theme = v; settings.theme = v; onChange() },
                                modifier = Modifier.weight(1f).padding(horizontal = 3.dp)
                            ) {
                                Text(
                                    label, style = MaterialTheme.typography.labelSmall,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }

            SwitchRow("圆屏手表模式", round) { round = it; settings.roundScreen = it; onChange() }
            SwitchRow("禁用左边缘侧滑返回", edge) { edge = it; settings.blockEdgeSwipe = it; onChange() }

            /* 界面缩放：等效调 DPI —— 手表系统 density 普遍偏高，字会小到看不清 */
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "界面缩放（等效 DPI）",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "字和按钮整体放大缩小。手表系统报的 DPI 往往偏高，界面会显得很小，调大一档就舒服了。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "小" to 0.88f,
                            "标准" to 1f,
                            "大" to 1.14f,
                            "特大" to 1.25f
                        ).forEach { (label, v) ->
                            val selected = kotlin.math.abs(settings.uiScale - v) < 0.02f
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.weight(1f).clickable {
                                    settings.uiScale = v
                                    onChange()
                                }
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    textAlign = TextAlign.Center,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)
                                )
                            }
                        }
                    }
                }
            }

            /* 屏幕规格：只做预览约束，不改设备 */
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "屏幕规格（预览）",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "选一个规格，界面会被限制在该比例的黑框里居中显示 —— 用来在大屏设备上验证手表布局有没有被裁。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                    Text(
                        "本机：" + LocalContext.current.resources.displayMetrics.let {
                            "${it.widthPixels} × ${it.heightPixels} · ${it.densityDpi} dpi"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("auto", "320x360", "360x360", "454x454").forEach { v ->
                            val selected = settings.simScreen == v
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.weight(1f).clickable {
                                    settings.simScreen = v
                                    onChange()
                                }
                            ) {
                                Text(
                                    if (v == "auto") "本机" else v.replace("x", "×"),
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.Center,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)
                                )
                            }
                        }
                    }
                }
            }

            /* 音量键翻页：以前只在阅读面板里有，很多人找不到，这里也放一份 */
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "音量键翻页",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "音量上 = 上一章，音量下 = 下一章（仅在阅读页生效）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Switch(
                        checked = settings.volumeKeys,
                        onCheckedChange = { settings.volumeKeys = it; onChange() }
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "关于左边缘侧滑返回",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "开关打开后，屏幕左边缘 24dp 不再触发「划一下就返回」，这段滑动会交给应用 —— " +
                            "阅读时从最左边划就是翻页，不会误退出。\n" +
                            "少数手表把返回手势做在系统底层、不理会应用的声明，这种需要在手表" +
                            "「设置 → 手势 / 系统导航」里关掉侧滑返回；下面的按钮可以直接打开系统设置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Button(
                        onClick = onOpenSystemSettings,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) { Text("打开系统设置") }
                }
            }

            /* 扫目录收书：自定义要扫哪些目录 */
            var scanText by remember { mutableStateOf(settings.scanDirs) }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "扫目录收书 · 自定义目录",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "书架上的文件夹按钮会扫这些目录，把没导入过的电子书收进来。多个目录用分号隔开；" +
                            "留空则只扫 Download / Downloads / Books。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                    // 已选目录：每条一个，长的一行内滚动显示；点 × 可以去掉
                    val picked = remember(settings.rev) {
                        settings.scanDirs.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                    }
                    if (picked.isNotEmpty()) {
                        picked.forEachIndexed { i, p ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            ) {
                                Row(
                                    Modifier.padding(start = 10.dp, end = 2.dp, top = 5.dp, bottom = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        p,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            settings.scanDirs = picked.filterIndexed { j, _ -> j != i }.joinToString(";")
                                            onChange()
                                        },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Text("×", style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 主入口：用内置文件管理器选目录 —— 手表上敲路径太痛苦，还容易打错
                        Button(
                            onClick = onPickDir,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            modifier = Modifier.height(32.dp)
                        ) { Text("浏览文件夹…", style = MaterialTheme.typography.labelMedium) }
                        if (scanText.trim() != settings.scanDirs) {
                            Button(
                                onClick = {
                                    settings.scanDirs = scanText.trim()
                                    onChange()
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                modifier = Modifier.height(32.dp)
                            ) { Text("保存手输", style = MaterialTheme.typography.labelMedium) }
                        }
                        if (settings.scanDirs.isNotBlank()) {
                            TextButton(
                                onClick = { scanText = ""; settings.scanDirs = ""; onChange() },
                                contentPadding = PaddingValues(horizontal = 6.dp)
                            ) { Text("恢复默认", style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                    // 手输框降级成"高级用法"：默认折叠，需要时才展开
                    var showRaw by remember { mutableStateOf(false) }
                    if (showRaw) {
                        OutlinedTextField(
                            value = scanText,
                            onValueChange = { scanText = it },
                            maxLines = 2,
                            placeholder = { Text("多个目录用分号隔开", style = MaterialTheme.typography.labelSmall) },
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                        )
                    } else {
                        Text(
                            "高级用法：手动输入路径（分号分隔）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clickable { showRaw = true; scanText = settings.scanDirs }
                        )
                    }
                }
            }

            /* 关于：作者与版本（版本号运行时读，改了 build.gradle 不用动这里） */
            val ctx = LocalContext.current
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "腕上书 WearBook",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "版本 ${AppVersion.display(ctx)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Text(
                        "作者：ZPW38",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Text(
                        "源码：github.com/ZPW38/wearbook",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    // B 站主页：点一下交给系统浏览器打开
                    Text(
                        "B 站：space.bilibili.com/3493142762359756",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable { openBilibili(ctx) }
                    )
                    Text(
                        "GPL-3.0 许可证 · 离线运行 · 不收集任何数据",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Button(
                onClick = onClearAll,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) { Text("清空全部数据") }
        }
        }
    }
}

@Composable
private fun SliderRow(
    label: String, value: Float, min: Float, max: Float, step: Float,
    display: (Float) -> String, onValue: (Float) -> Unit
) {
    // 拖动只更新本地状态，松手才写设置 —— 否则每帧写一次 SharedPreferences + 重设窗口，
    // 在手表上会卡成「拖不动、要重进界面才响应」
    var local by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!dragging) local = value }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(display(local), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                value = local.coerceIn(min, max),
                onValueChange = { dragging = true; local = it },
                onValueChangeFinished = { dragging = false; onValue(local) },
                valueRange = min..max,
                steps = if (step <= 0f) 0 else ((max - min) / step).toInt().coerceAtLeast(1) - 1,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onToggle, colors = SwitchDefaults.colors())
        }
    }
}

/**
 * 打开作者的 B 站主页。
 *
 * 单独抽出来是有原因的：手表 ROM（尤其是华为系）可能没装任何能处理 http 的浏览器，
 * 或者被拦下；这种情况下 startActivity 会抛异常 —— 直接崩掉一个"点链接"的动作不值得，
 * 捕获住，弹一句"没有可用浏览器"。
 */
private fun openBilibili(ctx: android.content.Context) {
    val url = "https://space.bilibili.com/3493142762359756"
    val ok = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.isSuccess
    if (!ok) {
        runCatching {
            android.widget.Toast.makeText(ctx, "没有可用的浏览器，请手动打开 $url", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
