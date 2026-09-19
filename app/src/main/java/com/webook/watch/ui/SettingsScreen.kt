package com.webook.watch.ui

import com.webook.watch.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
    var vib by remember { mutableStateOf(settings.vibrate) }
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
            SliderRow("自动翻页", auto, 0f, 60f, 5f, { v -> if (v <= 0f) "关闭" else "${v.toInt()} 秒" }) {
                auto = it; settings.autoSeconds = it.toInt(); onChange()
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
            SwitchRow("翻页震动反馈", vib) { vib = it; settings.vibrate = it; onChange() }
            SwitchRow("禁用左边缘侧滑返回", edge) { edge = it; settings.blockEdgeSwipe = it; onChange() }

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
                steps = ((max - min) / step).toInt().coerceAtLeast(1) - 1,
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
