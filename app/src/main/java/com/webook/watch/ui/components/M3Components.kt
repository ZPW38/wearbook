package com.webook.watch.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 统一的 Material 3 交互组件。
 *
 * 为什么单独抽出来：项目里有 60+ 处可点区域，原先每处各写一遍
 * `Surface(onClick=...)` —— 形状、圆角、按压反馈全靠复制粘贴，改风格要翻十几个文件，
 * 而且总有漏改的。集中到这里以后，**改一次全应用生效**，行为也一致。
 *
 * 按压反馈遵循 Material 3 的做法：按下时轻微缩小（0.97），松手回弹。
 * 别小看这一下 —— 手表屏小、又没有震动反馈，没有按压态时用户会怀疑"我到底点上没有"，
 * 于是重复点，很容易误翻页。
 */

/** 按下缩放的公共动效：M3 的 "pressed" 状态属于短促反馈，用 fast 时长 */
@Composable
private fun pressScale(pressed: Boolean, amount: Float = 0.97f): Float {
    val s by animateFloatAsState(
        targetValue = if (pressed) amount else 1f,
        animationSpec = tween(durationMillis = if (pressed) 90 else 160),
        label = "pressScale"
    )
    return s
}

/**
 * 列表 / 设置项用的卡片：圆角 + surfaceContainer 底色 + 按压缩放。
 * @param emphasized 为 true 时用 primaryContainer（主操作，例如导入入口）
 */
@Composable
fun M3Card(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = pressScale(pressed && enabled)

    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(16.dp),
        color = when {
            !enabled -> MaterialTheme.colorScheme.surfaceContainerLow
            emphasized -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = when {
            emphasized -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> LocalContentColor.current
        },
        modifier = modifier.scale(scale)
    ) { content() }
}

/** 带图标的行动卡（导入页那种："图标 + 标题 + 箭头"） */
@Composable
fun M3ActionRow(
    icon: Painter? = null,
    imageVector: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null
) {
    M3Card(onClick = onClick, modifier = modifier, emphasized = emphasized, enabled = enabled) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when {
                icon != null -> Icon(
                    painter = icon,
                    contentDescription = null,
                    tint = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.primary
                )
                imageVector != null -> Icon(
                    imageVector = imageVector,
                    contentDescription = null,
                    tint = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.primary
                )
            }
            androidx.compose.foundation.layout.Column(
                Modifier.weight(1f)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer
                            .copy(alpha = 0.75f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            trailing?.invoke()
        }
    }
}

/** 分档选择用的药丸按钮（主题选择、字号档位那种"多选一"） */
@Composable
fun M3Pill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = pressScale(pressed && enabled, amount = 0.95f)

    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.scale(scale)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
        )
    }
}

/** 小尺寸图标按钮：统一 44dp 触控区（手表上小于这个很难点中）与按压反馈 */
@Composable
fun M3IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = pressScale(pressed && enabled, amount = 0.88f)

    androidx.compose.material3.IconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier.scale(scale)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint ?: if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f)
        )
    }
}

/** painter 版本（项目里大部分图标是 drawable 资源） */
@Composable
fun M3IconAction(
    painter: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = pressScale(pressed && enabled, amount = 0.88f)

    androidx.compose.material3.IconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier.scale(scale)
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = tint ?: if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f)
        )
    }
}

/** 便捷：从 drawable 资源 id 建图标按钮 */
@Composable
fun M3IconAction(
    resId: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color? = null
) {
    M3IconAction(
        painter = painterResource(resId),
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        tint = tint
    )
}
