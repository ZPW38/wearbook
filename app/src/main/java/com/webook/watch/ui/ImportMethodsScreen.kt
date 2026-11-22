package com.webook.watch.ui

import com.webook.watch.R
import com.webook.watch.ui.components.M3Card
import com.webook.watch.ui.components.M3IconAction

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「其它导入方式」独立页。
 *
 * 为什么从 ImportScreen 里拆出来：
 *   450px（225dp）高的表盘上，一个页面同时放「标题栏 + 当前路径 + 导入方式 + 链接输入框 +
 *   文件列表」六样东西，实测三张卡片里第三张正好切在屏幕边缘（y=425/450），
 *   用户看不到「链接导入」，还以为功能没了 —— 这就是"导入菜单很乱"的根。
 *   挤是挤不出来的：标题 52 + 路径 20 + 折叠条 36 之后只剩 117dp，两张 52dp 的卡片都勉强。
 *
 * 所以：导入方式自己占一整页（173dp 可用高度，三张 46dp 的卡片 + 间距刚好放下，不用滚），
 * 导入页只留一行入口。低频高风险操作（选错就白折腾）值得一个独立页面把话说清楚。
 */
@Composable
fun ImportMethodsScreen(
    /** 手机传书是否在跑 */
    wifiOn: Boolean,
    /** 传书地址（已开启时有值） */
    wifiInfo: String?,
    onWifiToggle: () -> Unit,
    onShowQr: () -> Unit,
    /** 从设备导入：先试 SAF，不可用/空壳时自动落到专属文件夹 */
    onImportLocal: () -> Unit,
    /** 浏览手表存储：进内置文件管理器，自己翻目录找书 */
    onBrowseStorage: () -> Unit,
    /** 链接导入的进行中状态 / 失败原因（null = 空闲） */
    urlJob: com.webook.watch.data.UrlImport.Job?,
    onUrlImport: (String) -> Unit,
    onUrlDismiss: () -> Unit,
    onBack: () -> Unit
) {
    var urlText by remember { mutableStateOf("") }
    var urlExpanded by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    // 展开链接导入时把输入框滚进视野 —— 它在三张卡片下面，屏只有 225dp 高，
    // 不滚的话用户只会看到卡片标题变了色，压根不知道输入框在哪。
    LaunchedEffect(urlExpanded) {
        if (urlExpanded) scroll.animateScrollTo(scroll.maxValue)
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                M3IconAction(R.drawable.ic_arrow_back, "返回", onBack)
                Text(
                    "导入方式", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 2.dp).weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 可滚动兜底：链接输入框展开后内容会超出 173dp
        Column(
            Modifier.fillMaxWidth().weight(1f)
                .verticalScroll(scroll)
                .padding(horizontal = 10.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // ① 手机传书 —— 手表上最省事：不用线、不用权限、不用文件管理器
            MethodCard(
                icon = R.drawable.ic_autoplay,
                title = if (wifiOn) "手机传书 · 已开启" else "手机传书",
                subtitle = if (wifiOn) "看二维码" else "扫码上传",
                highlighted = wifiOn,
                onClick = if (wifiOn) onShowQr else onWifiToggle
            )

            // ② 从设备导入 —— 系统文件选择器优先，空壳/不可用时自动落到专属文件夹
            MethodCard(
                icon = R.drawable.ic_description,
                title = "从设备导入",
                subtitle = "系统选择器",
                onClick = onImportLocal
            )

            // ③ 浏览手表存储 —— 不依赖系统选择器，自己翻目录。
            // 部分手表 ROM 的「文件」选择器是空壳（Wear OS 上尤其常见），这条是它的替代品。
            MethodCard(
                icon = R.drawable.ic_folder,
                title = "浏览手表存储",
                subtitle = "翻目录",
                onClick = onBrowseStorage
            )

            // ④ 链接导入 —— 需要一个能直下的网址
            MethodCard(
                icon = R.drawable.ic_add,
                title = "链接导入",
                subtitle = if (urlExpanded) "收起" else "http 直链",
                highlighted = urlExpanded,
                onClick = { urlExpanded = !urlExpanded }
            )

            if (urlExpanded) {
                UrlImportPanel(
                    job = urlJob,
                    url = urlText,
                    onUrlChange = { urlText = it },
                    onStart = onUrlImport,
                    onDismiss = onUrlDismiss
                )
            }
        }
    }
}

/**
 * 一行导入方式：固定 42dp 高。
 *
 * ⚠️ 这里**不能**用 M3Card + 上下 6dp 内边距：那样每张约 100px，
 * 四张就是 400px，而 225dp 高的屏减掉标题栏只剩 371px —— 第四张必然被切掉。
 * 固定 42dp（= 84px）后四张 + 间距共 348px，刚好放下且不用滚动。
 */
@Composable
private fun MethodCard(
    icon: Int,
    title: String,
    subtitle: String,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(42.dp)
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = if (highlighted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp).size(17.dp)
            )
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                color = if (highlighted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.weight(1f))
            Text(
                subtitle,
                // 9sp：比默认 labelSmall（11sp）再小两档，让标题一眼可分。
                // 手表上 9sp 偏小，但这行只是注解，而且要保证不挤掉标题。
                fontSize = 9.sp, lineHeight = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}

/**
 * 链接导入面板：一个输入框 + 开始按钮 + 进度/错误。
 *
 * 只做一件事：把用户填的 http(s) 直链下载成书。能不能下取决于那个链接本身 ——
 * 无痕窗口里不登录就能存成文件的链接，这里就能下；需要登录或验证码的，这里同样下不到。
 * 这里不做任何站点适配、不注入 Cookie，也不维护接口地址，详见 data/UrlImport.kt。
 */
@Composable
private fun UrlImportPanel(
    job: com.webook.watch.data.UrlImport.Job?,
    url: String,
    onUrlChange: (String) -> Unit,
    onStart: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                singleLine = true,
                label = { Text("图书直链", style = MaterialTheme.typography.labelSmall) },
                textStyle = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth()
            )

            if (job?.error != null) {
                Text(
                    job.error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Button(onClick = onDismiss, modifier = Modifier.padding(top = 6.dp)) {
                    Text("知道了", style = MaterialTheme.typography.labelMedium)
                }
                return@Column
            }

            if (job != null) {
                // total == 0 表示服务器没给 Content-Length，只能显示已下载字节数
                Text(
                    if (job.total > 0) "正在下载 ${job.read * 100 / job.total}%"
                    else "已下载 ${job.read / 1024} KB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                if (job.total > 0) {
                    LinearProgressIndicator(
                        progress = { job.fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(top = 4.dp).size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
                Text(
                    "取消",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable { onDismiss() }
                )
                return@Column
            }

            Button(
                onClick = { onStart(url) },
                enabled = url.isNotBlank(),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("开始下载", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
