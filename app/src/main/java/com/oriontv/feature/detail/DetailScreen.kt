package com.oriontv.feature.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import com.oriontv.navigation.OrionRoute
import com.oriontv.ui.theme.OrionTextStyles

/** 详情页占位 Screen。M1 补：上区海报+信息列，下区源按钮流式换行 + 剧集按钮流式换行。 */
@Composable
fun DetailScreen(
    source: String,
    id: String,
    onNavigate: (OrionRoute) -> Unit,
    onBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "详情页（骨架占位，M1 实现）", style = OrionTextStyles.Title)
    }
}
