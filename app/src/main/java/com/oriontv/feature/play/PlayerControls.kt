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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.oriontv.ui.components.BrandProgressBar
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.components.SecondaryButton
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles
import kotlinx.coroutines.delay

/**
 * 播放控制条（ui-design.md §5.3/§6）：
 * 顶部：标题 + 集数 + 源名；底部控制条：8dp 大进度条
 * （带缓冲段与断点标记，由 M1 接）+ 当前/总时长 +
 * 播放暂停、-10s、+10s、选集、换源、倍速按钮；
 * 跳过片头胶囊在右下角，默认 90 秒，可关闭；
 * 快进时顶部中央出落点预览气泡（时间 + 缩略图，T6/T7）；
 * 播完后右下角出下一集预告卡，3 秒倒计时，可取消。
 */
@Composable
fun PlayerControls(
    title: String,
    episodeLabel: String,
    sourceName: String,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    seekPreviewMs: Long,
    showSkipIntro: Boolean,
    showNextEpisode: Boolean,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSkipIntro: () -> Unit,
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

        // 快进落点预览气泡（T6/T7）：顶部中央，时间 + 缩略图占位。
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
                Text(text = formatTime(seekPreviewMs), style = OrionTextStyles.SectionTitle)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "预览缩略图（M1 接）", style = OrionTextStyles.Small)
            }
        }

        // 跳过片头胶囊：右下角，控制条上方。
        if (showSkipIntro) {
            PrimaryButton(
                text = "跳过片头",
                onClick = onSkipIntro,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = 180.dp),
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
            BrandProgressBar(
                fraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                height = 8.dp,
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
                SecondaryButton(text = if (isPlaying) "暂停" else "播放", onClick = onPlayPause)
                SecondaryButton(text = "-10 秒", onClick = onSeekBack)
                SecondaryButton(text = "+10 秒", onClick = onSeekForward)
                SecondaryButton(text = "选集", onClick = onOpenEpisodes)
                SecondaryButton(text = "换源", onClick = onOpenSources)
                SecondaryButton(text = "倍速", onClick = onOpenSpeed)
            }
        }
    }
}

/** 24 小时制时间（ui-design.md §7：全部用 24 小时制）。 */
private fun formatTime(ms: Long): String {
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
