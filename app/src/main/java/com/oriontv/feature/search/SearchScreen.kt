package com.oriontv.feature.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import com.oriontv.navigation.OrionRoute
import com.oriontv.ui.theme.OrionTextStyles

/** 搜索页占位 Screen。M1 补：顶部搜索行 + 结果网格 + 无结果时搜索历史 chips；防抖 300ms，同一词 5 分钟缓存。 */
@Composable
fun SearchScreen(
    onNavigate: (OrionRoute) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "搜索页（骨架占位，M1 实现）", style = OrionTextStyles.Title)
    }
}
