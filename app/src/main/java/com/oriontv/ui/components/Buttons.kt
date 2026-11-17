package com.oriontv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
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
    // v2.0.6 焦点修复：聚焦态改由 clickable 的 InteractionSource 驱动
    // （collectIsFocusedAsState，tv-material3 同款做法）。旧实现用
    // onFocusChanged 在 clickable 之后接，v2.0.5 真机上该回调没把 focused
    // 状态带进组合（dump 显示 focused=true，界面却无任何聚焦态），
    // onFocusChanged 的挂接位置决定了它观测哪个焦点目标，换 InteractionSource
    // 后不再依赖这一顺序。
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier = modifier
            // 触摸修复：clickable 本身可聚焦，并同时处理触摸点按与遥控器
            // 中心/回车键（松键触发，与旧 onKeyUp 同款）；旧的 onKeyUp 分支已删，
            // 不留两处按键处理（否则遥控器按一次会触发两次 onClick）。
            .clickable(
                interactionSource = interactionSource,
                indication = null, // v2.0.6：电视上焦点高亮即提示，不用水波纹
                enabled = enabled,
            ) { onClick() }
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
    // v2.0.6 焦点修复：同 PrimaryButton，聚焦态改由 InteractionSource 驱动。
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null, // v2.0.6：电视上焦点高亮即提示，不用水波纹
                enabled = enabled,
            ) { onClick() }
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

/** chip 背景/文字色（可单测）：选中 = 品牌实底 + 白字（“正在播放”态）；
 * 焦点未选中 = 品牌浅底 + 白字（焦点必须肉眼可见，又与实底选中态区分开，
 * 只靠 2dp 描边 + 1.08 缩放在小 chip 上看不出来）；其余 = 表面底 + 灰字。 */
internal data class ChipColors(val background: Color, val text: Color)

internal fun categoryChipColors(selected: Boolean, focused: Boolean): ChipColors = when {
    selected -> ChipColors(OrionColors.Brand, OrionColors.Text)
    focused -> ChipColors(OrionColors.Brand.copy(alpha = 0.3f), OrionColors.Text)
    else -> ChipColors(OrionColors.Surface, OrionColors.TextSecondary)
}

/** 分类 chip（首页分类栏、详情页源切换、直播分组用）。 */
@Composable
fun CategoryChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // B36-P2-2：禁用态（首页行不存在的分类）：不可点按、不可聚焦、降透明度，
    // 与 Primary/SecondaryButton 的 enabled 同款（P2-6 降透明度）。
    enabled: Boolean = true,
) {
    // v2.0.6 焦点修复：同 PrimaryButton，聚焦态改由 InteractionSource 驱动。
    // 旧的 onFocusChanged 在 clickable 之后接，v2.0.5 真机上焦点明明在 chip
    // 上（dump focused=true），focused 状态却始终为 false，30% 橙底、描边、
    // 放大一个都没渲染出来。
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = categoryChipColors(selected, focused)
    Box(
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null, // v2.0.6：电视上焦点高亮即提示，不用水波纹
                enabled = enabled,
            ) { onClick() }
            .orionFocus(focused, Capsule)
            // P2-6：禁用态降透明度，与可用态肉眼可区分。
            .alpha(if (enabled) 1f else 0.4f)
            .background(colors.background, Capsule)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = OrionTextStyles.Body,
            color = colors.text,
        )
    }
}
