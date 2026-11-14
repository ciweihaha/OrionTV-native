package com.oriontv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.oriontv.core.focus.onKeyUp
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles

/**
 * 按钮与 chip（ui-design.md §6）：
 * 主按钮 = 熔橙底 + 墨黑文字；次按钮 = 表面底 + 主文字；
 * chip = 胶囊（999dp），选中 = 品牌底 + 白字，未选中 = 表面底 + 灰字；
 * 焦点统一三件套（放大 1.08 + 2dp 品牌描边 + 阴影 8dp）。
 * 按键只处理 KeyUp（§4），长按 OK 快捷由 M1 接 LongPressGuard。
 */

private val Capsule = RoundedCornerShape(OrionDimens.CapsuleRadius)

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .focusable(enabled)
            .onFocusChanged { focused = it.isFocused }
            .onKeyUp {
                if (it.key == Key.DirectionCenter || it.key == Key.Enter) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .orionFocus(focused, Capsule)
            // P2-6：禁用态降透明度，与可用态肉眼可区分。
            .alpha(if (enabled) 1f else 0.4f)
            .background(OrionColors.Brand, Capsule)
            .padding(horizontal = 28.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = OrionTextStyles.Body, color = OrionColors.Background)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .focusable(enabled)
            .onFocusChanged { focused = it.isFocused }
            .onKeyUp {
                if (it.key == Key.DirectionCenter || it.key == Key.Enter) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .orionFocus(focused, Capsule)
            // P2-6：禁用态降透明度，与可用态肉眼可区分。
            .alpha(if (enabled) 1f else 0.4f)
            .background(OrionColors.Surface, Capsule)
            .padding(horizontal = 28.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = OrionTextStyles.Body, color = OrionColors.Text)
    }
}

/** 分类 chip（首页分类栏、详情页源切换、直播分组用）。 */
@Composable
fun CategoryChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .focusable()
            .onFocusChanged { focused = it.isFocused }
            .onKeyUp {
                if (it.key == Key.DirectionCenter || it.key == Key.Enter) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .orionFocus(focused, Capsule)
            .background(if (selected) OrionColors.Brand else OrionColors.Surface, Capsule)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = OrionTextStyles.Body,
            color = if (selected) OrionColors.Text else OrionColors.TextSecondary,
        )
    }
}

// ---- 兼容旧名（骨架内旧调用点过渡用，M1 逐步替换） ----
/** 旧通用按钮 → 次按钮样式。 */
@Composable
fun StyledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = SecondaryButton(text = text, onClick = onClick, modifier = modifier, enabled = enabled)

/** 播放控制条里的媒体按钮，M1 补图标与行为。 */
@Composable
fun MediaButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) = SecondaryButton(text = label, onClick = onClick, modifier = modifier)

/** 旧金色主按钮 → 熔橙主按钮。 */
@Composable
fun GoldButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = PrimaryButton(text = text, onClick = onClick, modifier = modifier, enabled = enabled)

/** 旧分类芯片 → CategoryChip。 */
@Composable
fun ChipButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) = CategoryChip(text = text, selected = selected, onClick = onClick, modifier = modifier)
