package com.oriontv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles

/**
 * 加载态：没有骨架屏，只有全屏转圈和 VideoLoadingAnimation
 *（浮动视频框 + 渐变进度条 + 背景圆旋转，需手写，难度低–中；M1 补完整动画）。
 */
@Composable
fun LoadingAnimation(
    message: String = "加载中…",
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.3f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(OrionColors.Divider),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(OrionColors.Brand),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = message, style = OrionTextStyles.Body, color = OrionColors.TextSecondary)
        }
    }
}

/** 设置页的 Section 卡片容器：表面底 + 16dp 圆角 + 分隔线边框。 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(OrionDimens.PanelCornerRadius))
            .background(OrionColors.Surface)
            .border(
                width = 1.dp,
                color = OrionColors.Divider,
                shape = RoundedCornerShape(OrionDimens.PanelCornerRadius),
            )
            .padding(16.dp),
    ) {
        Text(text = title, style = OrionTextStyles.SectionTitle)
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}
