package com.oriontv.feature.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import com.oriontv.navigation.OrionRoute
import com.oriontv.ui.theme.OrionTextStyles

/**
 * 直播页占位 Screen。M2 补：全屏播放器 + 换台信息条（4 秒消失）+ 频道面板
 *（底部抽屉，15 秒无操作自动关闭）+ EPG 弹窗 + 数字键换台。
 */
@Composable
fun LiveScreen(
    onNavigate: (OrionRoute) -> Unit,
    viewModel: LiveViewModel = hiltViewModel(),
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "直播页（骨架占位，M2 实现）", style = OrionTextStyles.Title)
    }
}
