package com.oriontv.feature.play

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import com.oriontv.core.focus.onKeyUp
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionTextStyles
import kotlinx.coroutines.delay

/**
 * 播放页（ui-design.md §5.3）：
 * 全屏视频；OK/方向键唤出控制条，5 秒无操作自动隐藏；
 * 跳过片头胶囊默认 90 秒、可关闭，出现 10 秒后消失；
 * 播完后自动出下一集预告卡，3 秒倒计时，可取消；
 * 播放错误自动换源最多 2 次再报错（M1 接 PlayerManager）。
 * M1 补：Media3 全屏播放、断点续播、选集/换源/倍速抽屉。
 */
@Composable
fun PlayScreen(
    source: String,
    id: String,
    title: String,
    episodeIndex: Int,
    positionMs: Long,
    onBack: () -> Unit,
    viewModel: PlayViewModel = hiltViewModel(),
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsTick by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(positionMs) }
    val durationMs = 45L * 60 * 1000
    var seekPreviewMs by remember { mutableLongStateOf(-1L) }
    var skipIntroVisible by remember { mutableStateOf(true) }
    var showNextEpisode by remember { mutableStateOf(false) }
    val rootRequester = remember { FocusRequester() }

    // 控制条 5 秒无操作自动隐藏；任意按键重新唤出并重置计时。
    LaunchedEffect(controlsTick) {
        if (controlsVisible) {
            delay(5000)
            controlsVisible = false
        }
    }
    // 跳过片头胶囊出现 10 秒后消失。
    LaunchedEffect(skipIntroVisible) {
        if (skipIntroVisible) {
            delay(10_000)
            skipIntroVisible = false
        }
    }
    // 快进预览气泡 2 秒后消失。
    LaunchedEffect(seekPreviewMs) {
        if (seekPreviewMs >= 0) {
            delay(2000)
            seekPreviewMs = -1L
        }
    }
    LaunchedEffect(Unit) { rootRequester.requestFocus() }

    fun pokeControls() {
        controlsVisible = true
        controlsTick++
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OrionColors.Background)
            .focusable()
            .focusRequester(rootRequester)
            .onKeyUp { event ->
                when (event.key) {
                    Key.Back -> {
                        // 返回键：弹窗 → 控制条 → 退出（弹窗由 M1 接）。
                        if (controlsVisible) {
                            controlsVisible = false
                            true
                        } else {
                            onBack()
                            true
                        }
                    }
                    else -> {
                        pokeControls()
                        false
                    }
                }
            },
    ) {
        // 全屏视频占位。
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "$title（播放画面占位，M1 接 Media3）",
                style = OrionTextStyles.Body,
                color = OrionColors.TextSecondary,
            )
        }
        if (controlsVisible) {
            PlayerControls(
                title = title,
                episodeLabel = "第 ${episodeIndex + 1} 集",
                sourceName = source,
                positionMs = position,
                durationMs = durationMs,
                isPlaying = isPlaying,
                seekPreviewMs = seekPreviewMs,
                showSkipIntro = skipIntroVisible,
                showNextEpisode = showNextEpisode,
                onPlayPause = { isPlaying = !isPlaying; pokeControls() },
                onSeekBack = {
                    position = (position - 10_000).coerceAtLeast(0)
                    seekPreviewMs = position
                    pokeControls()
                },
                onSeekForward = {
                    position = (position + 10_000).coerceAtMost(durationMs)
                    seekPreviewMs = position
                    pokeControls()
                },
                onSkipIntro = { skipIntroVisible = false; position += 90_000; pokeControls() },
                onNextEpisode = { showNextEpisode = false; pokeControls() },
                onCancelNextEpisode = { showNextEpisode = false; pokeControls() },
                onOpenEpisodes = { pokeControls() },
                onOpenSpeed = { pokeControls() },
                onOpenSources = { pokeControls() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
