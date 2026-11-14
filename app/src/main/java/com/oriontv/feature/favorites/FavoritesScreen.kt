package com.oriontv.feature.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.Text
import com.oriontv.navigation.OrionRoute
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.components.VideoCard
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles

/**
 * 收藏页（ui-design.md §5.6）：
 * 网格，倒序（新收藏在前），卡片与首页同款；
 * 空态：“还没有收藏，去首页逛逛吧” + “去首页”按钮；
 * 详情页内可取消收藏，返回收藏页即刷新。
 */
@Composable
fun FavoritesScreen(
    onNavigate: (OrionRoute) -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // 每次回页都刷新（详情页取消收藏后返回即不残留）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!uiState.isLoading && uiState.items.isEmpty()) {
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
        if (uiState.isLoading) {
            Text(
                text = "正在加载…",
                style = OrionTextStyles.Body,
                color = OrionColors.TextSecondary,
            )
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            horizontalArrangement = Arrangement.spacedBy(OrionDimens.CardSpacing),
            verticalArrangement = Arrangement.spacedBy(OrionDimens.RowSpacing),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(uiState.items) { (key, favorite) ->
                VideoCard(
                    title = favorite.title,
                    posterUrl = favorite.cover,
                    meta = listOfNotNull(
                        favorite.year?.takeIf { it.isNotBlank() },
                        favorite.source_name,
                    ).joinToString(" · ").ifBlank { null },
                    onClick = {
                        onNavigate(
                            OrionRoute.Detail(
                                source = key.substringBefore("+", ""),
                                id = key.substringAfter("+", ""),
                                title = favorite.search_title,
                            ),
                        )
                    },
                )
            }
        }
    }
}
