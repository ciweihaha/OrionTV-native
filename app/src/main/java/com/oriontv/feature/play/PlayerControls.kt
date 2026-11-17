package com.oriontv.feature.play

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.oriontv.core.focus.preferredFocus
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.components.SecondaryButton
import com.oriontv.ui.components.consumeTaps
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
    /** A8-P1-1：是否已播完——倒计时只在播完态走（见 shouldTickNextEpisodeCountdown）：
     * 预告卡在剩 10 秒时就出现，旧条件“正在播或已播完”让倒计时边播边走，
     * 约 3 秒后自动切集、本集最后约 7 秒被吞掉；只在 STATE_ENDED 后才走 3 秒。 */
    isEnded: Boolean = false,
    /** 抽屉是否打开（P2-1）：抽屉打开时下一集倒计时暂停，不在用户挑集时强制切集。 */
    drawerOpen: Boolean = false,
    introEndMs: Long? = null,
    outroStartMs: Long? = null,
    /** 控制条首选按钮（播放/暂停）的焦点：抽屉关闭后焦点落回这里。 */
    firstButtonRequester: FocusRequester? = null,
    /** C5-P2-2：跳过片头胶囊的焦点锚点 + 焦点变化回调（供 PlayScreen 跟踪
     * 胶囊是否获焦，胶囊 10 秒消失时若焦点在它上面则焦点回根）。 */
    skipIntroRequester: FocusRequester? = null,
    onSkipIntroFocusChanged: (Boolean) -> Unit = {},
    /** A9-P1-4：下一集预告卡“取消”按钮的焦点变化回调（与胶囊同款，供
     * PlayScreen 跟踪：上键回根与卡片消失时的焦点恢复都靠它）。 */
    onNextEpisodeFocusChanged: (Boolean) -> Unit = {},
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
    /** 第 70 轮：字幕按钮文案（“字幕：关/中文/English/无字幕”）与是否可用（无轨时禁用）。 */
    subtitleLabel: String = "字幕：无字幕",
    subtitleEnabled: Boolean = false,
    onCycleSubtitle: () -> Unit = {},
    /** 触摸修复：视频区空白处点按（控制条显着时根 Box 盖住视频，点按改由
     * 这里接；按钮是子元素，点按先到按钮，不会误触发）。 */
    onVideoTap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 第 9 轮 P2-3：remember 的 key 加 isEnded——只记 showNextEpisode 时，
    // 播完态倒计时走到 1 后在片尾窗口内快退（isEnded 真→假、预告卡保留），
    // 倒计时冻结在 1 不重置；重播到片尾再次 STATE_ENDED 后从 1 继续走，
    // 1 秒就自动切集、还约 10 秒一直显示“1 秒后自动播放”。isEnded 翻转
    // 即重置回 3：真→假（离开播完态）重置，假→真（再次播完）同样重置，
    // 都是新一轮 3 秒倒计时的起点；播完前卡已出现时倒计时本就冻结在 3，
    // 假→真那次重置无可见变化。
    var countdown by remember(showNextEpisode, isEnded) { mutableIntStateOf(3) }
    // A8-P1-1（第 8 轮，取代 A6-P1-1 的“正在播或已播完”）：倒计时只在播完态走——
    // 预告卡在剩 10 秒时就出现，旧谓词让倒计时边播边走，约 3 秒后自动切集，
    // 本集最后约 7 秒被吞掉；现在播完前倒计时冻结在 3 秒，STATE_ENDED 后才走 3 秒切集。
    // 暂停期间 / 抽屉打开时同样冻结，不在用户挑集/暂停时强制切集。
    // LaunchedEffect 的 key 含 isPlaying、isEnded 与 drawerOpen：
    // 状态翻转会重启本协程；countdown 平时经 remember 保留当前值（暂停/
    // 抽屉打开导致的重启不重置），只有 isEnded 翻转时才随 remember 的 key
    // 重置回 3（第 9 轮 P2-3）；预告关闭（showNextEpisode 变 false）时直接返回。
    LaunchedEffect(showNextEpisode, isPlaying, isEnded, drawerOpen) {
        if (showNextEpisode) {
            while (countdown > 0) {
                delay(1000)
                if (shouldTickNextEpisodeCountdown(isPlaying, isEnded, drawerOpen)) countdown--
            }
            onNextEpisode()
        }
    }

    // 触摸修复：控制条根的空白处点按（见 onVideoTap 参数说明）——
    // rememberUpdatedState 兜底：手势检测器只注册一次，回调总读最新值，
    // 不因每秒进度 recomposition 重启而丢点按。
    val latestVideoTap = rememberUpdatedState(onVideoTap)
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { latestVideoTap.value() })
            },
    ) {
        // 顶部：标题 + 集数 + 源名。P2-11：单行省略，不让长片名延伸到屏幕中央
        // 与预览气泡重叠；气泡同时下移到标题行之下（top 96dp），双保险。
        Text(
            text = "$title · $episodeLabel（$sourceName）",
            style = OrionTextStyles.PageTitle,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = 48.dp, vertical = 32.dp),
        )

        // 快进落点预览气泡（T6/T7）：顶部中央，落点时间 + 缩略图（抓帧失败用海报）。
        if (seekPreviewMs >= 0) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 96.dp)
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
        // A6-P2-3：与跳过片头胶囊错开锚点——胶囊在右下 bottom=180dp，预告卡
        // 抬到 bottom=300dp（卡片约 180dp 高，两者不再重叠；同屏共存时
        // 焦点移动也不会在同一位置上撞到两张卡）。
        if (showNextEpisode) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = 300.dp)
                    .clip(RoundedCornerShape(OrionDimens.PanelCornerRadius))
                    .background(OrionColors.Overlay)
                    .padding(20.dp)
                    // A60-P2-2：卡片空白区（标题 / 倒计时文字）的点按在此消费掉，
                    // 不漏到根点按层——否则 controlsVisible=false + 焦点回根，
                    // 但 showNextEpisode 强制控制条仍可见，焦点却已离开“取消”，
                    // OK 只会重新唤出控制条而非取消预告；“取消”按钮自己先消费，
                    // 不受影响（与抽屉内层 A28-P2-2 同款；不用空 clickable{}，
                    // 后者会建焦点节点、改动遥控焦点路径）。
                    .consumeTaps(),
            ) {
                Text(text = "即将播放下一集", style = OrionTextStyles.SectionTitle)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "${countdown} 秒后自动播放", style = OrionTextStyles.Body, color = OrionColors.TextSecondary)
                Spacer(modifier = Modifier.height(12.dp))
                SecondaryButton(
                    text = "取消",
                    onClick = onCancelNextEpisode,
                    modifier = Modifier.onFocusChanged { onNextEpisodeFocusChanged(it.isFocused) },
                )
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
                // 第 70 轮：字幕循环切换（关 → 第 1 条 → … → 关）；无内嵌字幕轨时禁用。
                SecondaryButton(
                    text = subtitleLabel,
                    onClick = onCycleSubtitle,
                    enabled = subtitleEnabled,
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
 * 下一集倒计时是否应走一秒（三参版，可单测，A8-P1-1）：只在已播完、
 * 且抽屉关闭时才减；正在播（预告卡已在剩 10 秒时出现）、暂停（未播完）
 * 或抽屉打开时冻结。旧条件“正在播或已播完”会让倒计时边播边走、
 * 约 3 秒后自动切集，吞掉本集最后约 7 秒。
 * A6-P1-1 的两参版已删：无播完态信息时谓词恒为 false，留着是陷阱。
 */
internal fun shouldTickNextEpisodeCountdown(
    isPlaying: Boolean,
    isEnded: Boolean,
    drawerOpen: Boolean,
): Boolean = isEnded && !drawerOpen

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
