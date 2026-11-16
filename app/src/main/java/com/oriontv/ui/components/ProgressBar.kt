package com.oriontv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.oriontv.ui.theme.OrionColors

/**
 * 进度条（ui-design.md §6）：轨道分隔线色、已播品牌色、断点/缓冲段由 M1 接。
 * 唯一用法是 EPG 节目进度条（LiveScreen 三处调用），均为 4dp 高；播放器
 * 控制条不用它（PlayerControls 自绘 8dp Slider），卡片内进度条也不用它
 * （VideoCard 内联 4dp Box）。默认高度 4.dp，与唯一用法一致。
 */
@Composable
fun BrandProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 4.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2f))
            .background(OrionColors.Divider),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .clip(RoundedCornerShape(height / 2f))
                .background(OrionColors.Brand),
        )
    }
}
