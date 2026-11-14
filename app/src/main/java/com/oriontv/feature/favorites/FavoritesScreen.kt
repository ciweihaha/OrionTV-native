package com.oriontv.feature.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.foundation.lazy.grid.GridCells
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import androidx.tv.foundation.lazy.grid.items
import androidx.tv.material3.Text
import com.oriontv.navigation.OrionRoute
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.components.VideoCard
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles

/**
 * 收藏页（ui-design.md §5.6）：
 * 网格，最新收藏在前，与首页同款卡片；
 * 空态：“还没有收藏，去首页逛逛吧” + “去首页”按钮；
 * 详情页取消收藏后返回即刷新（M1 接 FavoritesRepository）。
 */
@Composable
fun FavoritesScreen(
    onNavigate: (OrionRoute) -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    // M1：改为 FavoritesRepository 的真实列表（最新在前）。
    val favorites = emptyList<String>()

    if (favorites.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(OrionDimens.ContentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = "还没有收藏，去首页逛逛吧", style = OrionTextStyles.Body)
            Spacer(modifier = Modifier.height(24.dp))
            PrimaryButton(text = "去首页", onClick = { onNavigate(OrionRoute.Home) })
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(OrionDimens.ContentPadding)) {
        Text(text = "我的收藏", style = OrionTextStyles.PageTitle)
        Spacer(modifier = Modifier.height(16.dp))
        TvLazyVerticalGrid(
            columns = GridCells.Fixed(6),
            horizontalArrangement = Arrangement.spacedBy(OrionDimens.CardSpacing),
            verticalArrangement = Arrangement.spacedBy(OrionDimens.RowSpacing),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(favorites) { id ->
                VideoCard(
                    title = id,
                    posterUrl = "",
                    onClick = { onNavigate(OrionRoute.Detail(source = "agg", id = id)) },
                )
            }
        }
    }
}
