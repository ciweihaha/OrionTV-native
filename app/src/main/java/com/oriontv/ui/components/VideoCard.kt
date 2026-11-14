package com.oriontv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.Text
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles
import io.coil3.compose.AsyncImage

/**
 * 视频卡片（plan §3.2 首页）：卡片 180×300（含 margin），海报 160×240 圆角 8，
 * 右上角评分/年份角标，继续观看时底部 4px 绿进度条 + “第 N 集 已观看 x%”，
 * 获焦时中央叠加绿底“▶ 继续观看”徽章。
 * 焦点高亮：卡片聚焦 scale 1.05 + 2px 绿边框（plan §3.3 第 6 条）。
 *
 * 骨架阶段：只搭形状与焦点样式，数据绑定在 M1。
 */
@Composable
fun VideoCard(
    title: String,
    posterUrl: String,
    badge: String? = null,
    progressFraction: Float? = null,
    progressLabel: String? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                scaleX = if (focused) 1.05f else 1f
                scaleY = if (focused) 1.05f else 1f
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(OrionDimens.CardCornerRadius))
                .background(OrionColors.Surface),
        ) {
            AsyncImage(
                model = posterUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            if (badge != null) {
                Text(
                    text = badge,
                    style = OrionTextStyles.Caption,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(OrionColors.Background, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (focused && progressLabel != null) {
                Text(
                    text = "▶ $progressLabel",
                    style = OrionTextStyles.Caption,
                    color = OrionColors.OnPrimary,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(OrionColors.Primary, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            if (progressFraction != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                        .padding(bottom = 0.dp)
                        .background(OrionColors.Primary),
                )
            }
        }
        Text(
            text = title,
            style = OrionTextStyles.CardTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
