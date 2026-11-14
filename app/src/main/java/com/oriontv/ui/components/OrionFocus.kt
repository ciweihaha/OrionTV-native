package com.oriontv.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens

/**
 * 焦点三件套（ui-design.md §4，全部可聚焦组件统一用这一套）：
 * 放大 1.08 + 2dp 品牌色描边 + 柔和阴影（elevation 8dp），120ms 缓动。
 * 注意：orionFocus 不是 @Composable（调用方直接链式用），缩放缓动用
 * Modifier.graphicsLayer 的 animateFloatAsState 需要组合作用域——这里改成
 * @Composable 扩展，内部用 animateFloatAsState(tween(120)) 做 120ms 缓动。
 */
@Composable
fun Modifier.orionFocus(
    focused: Boolean,
    shape: Shape = RoundedCornerShape(OrionDimens.CardCornerRadius),
): Modifier {
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (focused) OrionDimens.FocusScale else 1f,
        animationSpec = androidx.compose.animation.core.tween(120),
        label = "orionFocusScale",
    )
    return this
        .shadow(
            elevation = if (focused) OrionDimens.FocusElevation else 0.dp,
            shape = shape,
        )
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clip(shape)
        .border(
            width = if (focused) OrionDimens.FocusBorderWidth else 0.dp,
            color = if (focused) OrionColors.Brand else Color.Transparent,
            shape = shape,
        )
}

/** 焦点描边（不需要阴影/放大的小组件用，如 chip 内层）。 */
@Composable
fun focusBorderColor(focused: Boolean): Color =
    if (focused) OrionColors.Brand else Color.Transparent
