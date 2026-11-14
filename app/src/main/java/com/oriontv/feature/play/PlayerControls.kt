package com.oriontv.feature.play

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.oriontv.core.focus.preferredFocus
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.components.SecondaryButton
import com.oriontv.ui.components.posterImageRequest
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles
import kotlinx.coroutines.delay

/**
 * 播放控制条（ui-design.md §5.3/§6）：
 * 顶部：标题 + 集数 + 源名；底部控制条：8dp 大进度条
 * （含缓冲段与断点标记）+ 当前/总时长 +
 * 播放暂停、-10s、+10s、选集、换源、倍速按钮；
 * 跳过片头胶囊在右下角，默认 90 秒，可关闭；
 * 快进时顶部中央出落点预览气泡（时间 + 缩略图，T6/T7，抓不到帧时用海报兜底）；
 * 播完后右下角出下一集预告卡，3 秒倒计时，可取消。
 */
@Composable
fun PlayerControls(
    title: String,
    episodeLabel: String,
    sourceName: String,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long = 0L,
    breakpointMs: Long = 0L,
    isPlaying: Boolean,
    playbackRate: Float = 1f,
    seekPreviewMs: Long,
    /** 预览方向（-1 快退 / +1 快进 / 0 未知）：气泡的方向标识用（P2-1）。 */
    seekPreviewDirection: Int = 0,
    seekPreviewThumbnail: android.graphics.Bitmap? = null,
    posterUrl: String = "",
    showSkipIntro: Boolean,
    showNextEpisode: Boolean,
    introEndMs: Long? = null,
    outroStartMs: Long? = null,
    /** 控制条首选按钮（播放/暂停）的焦点：抽屉关闭后焦点落回这里。 */
    firstButtonRequester: FocusRequester? = null,
    /** C5-P2-2：跳过片头胶囊的焦点锚点 + 焦点变化回调（供 PlayScreen 跟踪
     * 胶囊是否获焦，胶囊 10 秒消失时若焦点在它上面则焦点回根）。 */
    skipIntroRequester: FocusRequester? = null,
    onSkipIntroFocusChanged: (Boolean) -> Unit = {},
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSkipIntro: () -> Unit,
    onMarkIntro: () -> Unit = {},
    onMarkOutro: () -> Unit = {},
    onNextEpisode: () -> Unit,
    onCancelNextEpisode: () -> Unit,
    onOpenEpisodes: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenSources: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var countdown by remember(showNextEpisode) { mutableIntStateOf(3) }
    LaunchedEffect(showNextEpisode) {
        if (showNextEpisode) {
            countdown = 3
            while (countdown > 0) {
                delay(1000)
                countdown--
            }
            onNextEpisode()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 顶部：标题 + 集数 + 源名。
        Text(
            text = "$title · $episodeLabel（$sourceName）",
            style = OrionTextStyles.PageTitle,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = 48.dp, vertical = 32.dp),
        )

        // 快进落点预览气泡（T6/T7）：顶部中央，落点时间 + 缩略图（抓帧失败用海报）。
        if (seekPreviewMs >= 0) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp)
                    .clip(RoundedCornerShape(OrionDimens.PanelCornerRadius))
                    .background(OrionColors.Overlay)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (seekPreviewThumbnail != null) {
                    androidx.compose.foundation.Image(
                        bitmap = seekPreviewThumbnail.asImageBitmap(),
                        contentDescription = "落点预览",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(240.dp)
                            .height(135.dp)
                            .clip(RoundedCornerShape(OrionDimens.CardCornerRadius)),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                } else if (posterUrl.isNotBlank()) {
                    AsyncImage(
                        model = posterImageRequest(posterUrl),
                        contentDescription = "海报",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(240.dp)
                            .height(135.dp)
                            .clip(RoundedCornerShape(OrionDimens.CardCornerRadius)),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(text = seekPreviewLabel(seekPreviewDirection, seekPreviewMs), style = OrionTextStyles.SectionTitle)
            }
        }

        // 跳过片头胶囊：右下角，控制条上方。
        if (showSkipIntro) {
            PrimaryButton(
                text = "跳过片头",
                onClick = onSkipIntro,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = 180.dp)
                    .then(
                        if (skipIntroRequester != null) {
                            Modifier.preferredFocus(skipIntroRequester)
                        } else {
                            Modifier
                        },
                    )
                    .onFocusChanged { onSkipIntroFocusChanged(it.isFocused) },
            )
        }

        // 下一集预告卡：3 秒倒计时，可取消。
        if (showNextEpisode) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = 180.dp)
                    .clip(RoundedCornerShape(OrionDimens.PanelCornerRadius))
                    .background(OrionColors.Overlay)
                    .padding(20.dp),
            ) {
                Text(text = "即将播放下一集", style = OrionTextStyles.SectionTitle)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "${countdown} 秒后自动播放", style = OrionTextStyles.Body, color = OrionColors.TextSecondary)
                Spacer(modifier = Modifier.height(12.dp))
                SecondaryButton(text = "取消", onClick = onCancelNextEpisode)
            }
        }

        // 底部控制条。
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(OrionColors.Background.copy(alpha = 0.6f))
                .padding(horizontal = 48.dp, vertical = 24.dp),
        ) {
            SeekableProgressBar(
                fraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                bufferedFraction = if (durationMs > 0) bufferedMs.toFloat() / durationMs else 0f,
                breakpointFraction = if (durationMs > 0 && breakpointMs > 0) {
                    breakpointMs.toFloat() / durationMs
                } else {
                    null
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = formatTime(positionMs), style = OrionTextStyles.Small)
                Text(text = formatTime(durationMs), style = OrionTextStyles.Small)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(
                    text = if (isPlaying) "暂停" else "播放",
                    onClick = onPlayPause,
                    modifier = firstButtonRequester?.let { Modifier.preferredFocus(it) } ?: Modifier,
                )
                SecondaryButton(text = "-10 秒", onClick = onSeekBack)
                SecondaryButton(text = "+10 秒", onClick = onSeekForward)
                // 手动标记片头/片尾（阶段 4）：再点一次清除标记；已标记显示时间。
                SecondaryButton(
                    text = if (introEndMs != null) "片头 ${formatTime(introEndMs)}" else "标记片头",
                    onClick = onMarkIntro,
                )
                SecondaryButton(
                    text = if (outroStartMs != null) "片尾 ${formatTime(outroStartMs)}" else "标记片尾",
                    onClick = onMarkOutro,
                )
                SecondaryButton(text = "选集", onClick = onOpenEpisodes)
                SecondaryButton(text = "换源", onClick = onOpenSources)
                SecondaryButton(
                    text = if (playbackRate == 1f) "倍速" else "倍速 ${playbackRate}×",
                    onClick = onOpenSpeed,
                )
            }
        }
    }
}

/**
 * 8dp 大进度条：灰色轨道 + 浅色缓冲段 + 品牌色已播段 + 白色断点标记竖线。
 * （ui-design.md §6：断点/缓冲分段绘制，品牌色已播、灰色未播。）
 */
@Composable
private fun SeekableProgressBar(
    fraction: Float,
    bufferedFraction: Float,
    breakpointFraction: Float?,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(OrionColors.Divider),
    ) {
        // 缓冲段（浅灰）。
        Box(
            modifier = Modifier
                .fillMaxWidth(bufferedFraction.coerceIn(0f, 1f))
                .height(8.dp)
                .background(OrionColors.TextSecondary.copy(alpha = 0.45f)),
        )
        // 已播段（品牌色）。
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(8.dp)
                .background(OrionColors.Brand),
        )
        // 断点标记（白色竖线）。
        if (breakpointFraction != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(breakpointFraction.coerceIn(0f, 1f))
                    .height(8.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(8.dp)
                        .background(OrionColors.Text),
                )
            }
        }
    }
}

/**
 * 快进预览气泡文案（可单测，P2-1）：方向 + 目标时间。
 * 只标方向不标固定“10 秒”：连按多下累计的步数不是 10 秒，标 10 秒会误导。
 */
internal fun seekPreviewLabel(direction: Int, targetMs: Long): String {
    val time = formatTime(targetMs)
    return when {
        direction < 0 -> "◀ 快退 · $time"
        direction > 0 -> "快进 ▶ · $time"
        else -> time
    }
}

/** 24 小时制时间（ui-design.md §7：全部用 24 小时制）。 */
fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}
