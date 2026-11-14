package com.oriontv.feature.play

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.oriontv.ui.components.MediaButton

/**
 * 播放控制条占位（plan §3.2 播放页）：
 * rgba(0,0,0,0.4) 遮罩，顶部标题（片名 - 第 N 集 (源名)），
 * 底部进度条（弹出时默认获焦）+ 7 个圆形按钮
 *（标记片头 / 播放暂停 / 下一集 / 标记片尾 / 选集 / 倍速 / 换源）。
 * 控制条 5 秒无操作自动隐藏；返回键：弹窗 → 控制条 → 退出。
 */
@Composable
fun PlayerControls(
    title: String,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNextEpisode: () -> Unit,
    onMarkIntro: () -> Unit,
    onMarkOutro: () -> Unit,
    onOpenEpisodes: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenSources: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 骨架：只留按钮排布，进度条与遮罩在 M1 补。
    androidx.compose.foundation.layout.Row(modifier = modifier) {
        MediaButton(label = "标记片头", onClick = onMarkIntro)
        MediaButton(label = if (isPlaying) "暂停" else "播放", onClick = onPlayPause)
        MediaButton(label = "下一集", onClick = onNextEpisode)
        MediaButton(label = "标记片尾", onClick = onMarkOutro)
        MediaButton(label = "选集", onClick = onOpenEpisodes)
        MediaButton(label = "倍速", onClick = onOpenSpeed)
        MediaButton(label = "换源", onClick = onOpenSources)
    }
}
