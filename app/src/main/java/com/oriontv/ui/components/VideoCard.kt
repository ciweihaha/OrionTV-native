package com.oriontv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.oriontv.core.focus.onKeyUp
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles
import coil3.compose.AsyncImage

/**
 * 视频卡片（ui-design.md §6）：
 * 16:9 海报 + 标题与元数据在图下方（不叠加在图上）；圆角 12dp；
 * 焦点：放大 1.08 + 2dp 熔橙描边 + 阴影 8dp。
 * 继续观看行：底部品牌色进度条 + “剩余 xx 分钟”；直播卡带红色 LIVE 角标。
 */
@Composable
fun VideoCard(
    title: String,
    posterUrl: String,
    badge: String? = null,
    meta: String? = null,
    progressFraction: Float? = null,
    remainingLabel: String? = null,
    isLive: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .focusable()
            .onFocusChanged { focused = it.isFocused }
            .onKeyUp {
                when (it.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        onClick()
                        true
                    }
                    else -> false
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .orionFocus(focused),
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(OrionColors.Surface),
            ) {
                AsyncImage(
                    model = posterImageRequest(posterUrl),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
            if (isLive) {
                Text(
                    text = "LIVE",
                    style = OrionTextStyles.Small,
                    color = OrionColors.Text,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(OrionColors.LiveRed, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            } else if (badge != null) {
                Text(
                    text = badge,
                    style = OrionTextStyles.Small,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(OrionColors.Background, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (progressFraction != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                        .background(OrionColors.Brand),
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
        val sub = listOfNotNull(meta, remainingLabel).joinToString(" · ")
        if (sub.isNotEmpty()) {
            Text(
                text = sub,
                style = OrionTextStyles.Small,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 海报请求：豆瓣图床（*.doubanio.com）有防盗链，必须带
 * `Referer: https://movie.douban.com/`，否则 418（plan §5.6）。
 * 其它图床原样直连，不加请求头。
 */
@Composable
fun posterImageRequest(url: String): ImageRequest {
    val context = LocalContext.current
    return ImageRequest.Builder(context)
        .data(url)
        .apply {
            val host = runCatching { java.net.URL(url).host.lowercase() }.getOrDefault("")
            if (host.endsWith(".doubanio.com")) {
                httpHeaders(
                    NetworkHeaders.Builder()
                        .add("Referer", "https://movie.douban.com/")
                        .build(),
                )
            }
        }
        .build()
}
