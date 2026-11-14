package com.oriontv.feature.play

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import com.oriontv.ui.theme.OrionTextStyles

/** 播放页占位 Screen。M1 补：全屏黑底 VideoView + 控制条（5 秒无操作自动隐藏）+ 选集/换源/倍速抽屉。 */
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
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "播放页（骨架占位，M1 实现）", style = OrionTextStyles.Title)
    }
}
