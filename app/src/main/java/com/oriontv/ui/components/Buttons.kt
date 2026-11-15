package com.oriontv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles

/**
 * 按钮与 chip（ui-design.md §6）：
 * 主按钮 = 熔橙底 + 墨黑文字；次按钮 = 表面底 + 主文字；
 * chip = 胶囊（999dp），选中 = 品牌底 + 白字，未选中 = 表面底 + 灰字；
 * 焦点统一三件套（放大 1.08 + 2dp 品牌描边 + 阴影 8dp）。
 * 按键只处理 KeyUp（§4）；长按快捷由各组件内联实现（如直播频道行）。
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
            // 触摸修复：clickable 本身可聚焦，并同时处理触摸点按与遥控器
            // 中心/回车键（松键触发，与旧 onKeyUp 同款）；旧的 onKeyUp 分支已删，
            // 不留两处按键处理（否则遥控器按一次会触发两次 onClick）。
            .clickable(enabled = enabled) { onClick() }
            .onFocusChanged { focused = it.isFocused }
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
            .clickable(enabled = enabled) { onClick() }
            .onFocusChanged { focused = it.isFocused }
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
            .clickable { onClick() }
            .onFocusChanged { focused = it.isFocused }
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
