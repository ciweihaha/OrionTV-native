package com.oriontv.feature.home

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
 * 首页占位 Screen。M1 补：顶栏（直播/收藏/搜索/设置/退出）→ 分类横向行 →
 * 子标签行 → 5 列卡片网格（最近播放断点续播 + 无限滚动）。
 */
@Composable
fun HomeScreen(
    onNavigate: (OrionRoute) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "首页（骨架占位，M1 实现）", style = OrionTextStyles.Title)
    }
}
