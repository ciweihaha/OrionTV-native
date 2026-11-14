package com.oriontv.feature.favorites

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import com.oriontv.navigation.OrionRoute
import com.oriontv.ui.theme.OrionTextStyles

/** 收藏页占位 Screen。M1 补：与首页同网格/同卡片，顶栏仅“我的收藏”标题；每次回页刷新。 */
@Composable
fun FavoritesScreen(
    onNavigate: (OrionRoute) -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "我的收藏（骨架占位，M1 实现）", style = OrionTextStyles.Title)
    }
}
