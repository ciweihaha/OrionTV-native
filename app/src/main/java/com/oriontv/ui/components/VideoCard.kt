package com.oriontv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionTextStyles

/**
 * 视频卡片（ui-design.md §6）：
 * 2:3 竖版海报 + 标题与元数据在图下方（不叠加在图上）；圆角 12dp；
 * 豆瓣/aggs/收藏的海报全是竖版（实测 agg 270×380/400/405，约 2:3），
 * 之前 16:9 + Crop 只截出海报中间一条横带，人脸片名全被切掉，看着乱七八糟；
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
    /** 焦点落到本卡时回调（供各页做返回焦点恢复；不接时行为不变）。 */
    onFocused: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // v2.0.6 焦点修复：同 CategoryChip，聚焦态改由 clickable 的
    // InteractionSource 驱动（collectIsFocusedAsState）；获焦回调也改由该
    // 状态驱动，不再用 clickable 之后的 onFocusChanged（v2.0.5 真机上它
    // 没把 focused 状态带进组合）。
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) {
        if (focused) onFocused?.invoke()
    }
    Column(
        modifier = modifier
            // 触摸修复：clickable 本身可聚焦，并同时处理触摸点按与遥控器
            // 中心/回车键（松键触发，与旧 onKeyUp 同款）；旧的 onKeyUp 分支已删，
            // 不留两处按键处理（否则遥控器按一次会触发两次 onClick）。
            .clickable(
                interactionSource = interactionSource,
                indication = null, // v2.0.6：电视上焦点高亮即提示，不用水波纹
            ) { onClick() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .orionFocus(focused),
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(OrionColors.Surface),
            ) {
                PosterAsyncImage(
                    posterUrl = posterUrl,
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
                // P1-1：进度条必须有高度，否则无内容的 Box 高度为 0、不可见。
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                        .height(4.dp)
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

/** 豆瓣图床机子 host（img1/img2/img3/img9…），换机子时只换 host、路径不动。 */
private val DOUBAN_IMG_HOST_REGEX = Regex("""https?://(img\d+\.doubanio\.com)/""")

/**
 * 海报加载候选地址（可单测，v2.0.18）：原地址先重试一次（瞬时超时/抖动常一次就好），
 * 再换豆瓣同图的不同 img 机子（img1/img2/img9 与原 imgN 是同一批 CDN 镜像，
 * 同一 `/view/photo/.../pXXX.jpg` 在哪台上都是一张图）；非豆瓣地址只重试原地址一次；
 * 空地址返回空列表（调用方直接显示占位）。
 */
fun posterLoadAttempts(url: String): List<String> {
    if (url.isBlank()) return emptyList()
    val host = DOUBAN_IMG_HOST_REGEX.find(url)?.groupValues?.getOrNull(1)
        ?: return listOf(url, url)
    val alternates = listOf("img1.doubanio.com", "img2.doubanio.com", "img9.doubanio.com")
        .filter { it != host }
    return listOf(url, url) + alternates.take(2).map { alt -> url.replaceFirst(host, alt) }
}

/**
 * 带重试 + 换图床的海报加载（v2.0.18）：
 * 豆瓣图床某台 CDN 机子偶发不可达/超时时（如 2026-11-19 热门行 4 张 img3 全灰、img2 全有），
 * 旧 AsyncImage 只试一次、失败就永久灰框。现按 [posterLoadAttempts] 的候选顺序逐个试，
 * 全部失败才显示“暂无海报”占位，不再是光秃秃的灰框；url 变化时重试状态复位。
 */
@Composable
fun PosterAsyncImage(
    posterUrl: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val attempts = remember(posterUrl) { posterLoadAttempts(posterUrl) }
    var attemptIndex by remember(posterUrl) { mutableIntStateOf(0) }
    var loadFailed by remember(posterUrl) { mutableStateOf(false) }
    if (attempts.isEmpty() || loadFailed) {
        Box(
            modifier = modifier.background(OrionColors.Surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "暂无海报",
                style = OrionTextStyles.Small,
                color = OrionColors.TextSecondary,
            )
        }
    } else {
        AsyncImage(
            model = posterImageRequest(attempts[attemptIndex]),
            contentDescription = contentDescription,
            contentScale = contentScale,
            onError = {
                if (attemptIndex + 1 < attempts.size) attemptIndex++ else loadFailed = true
            },
            modifier = modifier,
        )
    }
}
