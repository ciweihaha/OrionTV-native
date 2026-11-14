package com.oriontv.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.Text
import com.oriontv.core.focus.preferredFocus
import com.oriontv.navigation.OrionRoute
import com.oriontv.ui.components.CategoryChip
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.components.SecondaryButton
import com.oriontv.ui.components.VideoCard
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles
import kotlinx.coroutines.launch

/**
 * 首页（ui-design.md §5.1，2026-11-14 用户决定：不要横幅，分类菜单置顶）：
 * 顶部：分类 chip 栏（最近播放 / 热门剧集 / 电视剧 / 电影 / 动漫 / 综艺 / 豆瓣 Top250），
 * 焦点默认落在“最近播放”上，按下进入内容行；
 * 下方依次：“最近播放”行（断点续播卡片，带进度条与“剩余 xx 分钟”）
 * → “热门剧集”行 → 各分类行（每行 6 张卡片，横向滚动）；
 * 未配置服务器时空态：一行提示 + “去设置”按钮。
 * 点分类 chip 滚动到对应行；返回首页时只刷新“最近播放”行。
 */
@Composable
fun HomeScreen(
    onNavigate: (OrionRoute) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedCategory by remember { mutableStateOf(HomeCategory.RECENT.title) }
    val firstChipRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 返回首页时刷新“最近播放”行（刚看完的断点要立刻出现）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshRecent()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!uiState.serverConfigured) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(OrionDimens.ContentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = "还没有配置源地址，先去设置里添加吧", style = OrionTextStyles.Body)
            Spacer(modifier = Modifier.height(24.dp))
            PrimaryButton(text = "去设置", onClick = { onNavigate(OrionRoute.Settings) })
        }
        return
    }

    // 焦点默认落在分类栏“最近播放”上。
    LaunchedEffect(Unit) { firstChipRequester.requestFocus() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val cardWidth =
            (maxWidth - 96.dp - OrionDimens.CardSpacing * 5f) / OrionDimens.HomeRowCards.toFloat()
        LazyColumn(
            state = listState,
            contentPadding = OrionDimens.ContentPadding,
            verticalArrangement = Arrangement.spacedBy(OrionDimens.RowSpacing),
        ) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(OrionDimens.ChipSpacing)) {
                    items(HomeCategory.entries) { category ->
                        CategoryChip(
                            text = category.title,
                            selected = selectedCategory == category.title,
                            onClick = {
                                selectedCategory = category.title
                                // 点 chip 滚动到对应行（第 0 项是 chip 栏，行从 1 开始）。
                                val rowIndex = uiState.rows.indexOfFirst { it.title == category.title }
                                if (rowIndex >= 0) {
                                    scope.launch { listState.scrollToItem(rowIndex + 1) }
                                }
                            },
                            modifier = if (category == HomeCategory.RECENT) {
                                Modifier.preferredFocus(firstChipRequester)
                            } else {
                                Modifier
                            },
                        )
                    }
                }
            }
            if (uiState.isLoading && uiState.rows.isEmpty()) {
                item {
                    Text(
                        text = "正在加载…",
                        style = OrionTextStyles.Body,
                        color = OrionColors.TextSecondary,
                    )
                }
            }
            if (uiState.error != null && uiState.rows.isEmpty()) {
                val errorMessage = uiState.error
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = errorMessage ?: "", style = OrionTextStyles.Body)
                        Spacer(modifier = Modifier.height(24.dp))
                        SecondaryButton(text = "重试", onClick = { viewModel.refresh() })
                    }
                }
            }
            items(uiState.rows) { row ->
                val isRecent = row.title == HomeCategory.RECENT.title
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(text = row.title, style = OrionTextStyles.SectionTitle)
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(OrionDimens.CardSpacing)) {
                        items(row.items) { item ->
                            VideoCard(
                                title = item.title,
                                posterUrl = item.poster,
                                meta = when {
                                    isRecent -> "第 ${item.episodeIndex + 1} 集"
                                    item.year != null && item.rate != null -> "${item.year} · ${item.rate} 分"
                                    item.year != null -> item.year
                                    item.rate != null -> "${item.rate} 分"
                                    item.sourceName != null -> item.sourceName
                                    else -> null
                                },
                                progressFraction = item.progress,
                                remainingLabel = if (isRecent) {
                                    remainingMinutesLabel(item.playTimeSeconds, item.totalTimeSeconds)
                                } else {
                                    null
                                },
                                onClick = {
                                    if (isRecent) {
                                        // 最近播放卡片直达播放页（断点续播，跳过详情）。
                                        onNavigate(
                                            OrionRoute.Play(
                                                source = item.source,
                                                id = item.id,
                                                title = item.title,
                                                episodeIndex = item.episodeIndex,
                                                positionMs = item.playTimeSeconds * 1000,
                                            ),
                                        )
                                    } else {
                                        onNavigate(
                                            OrionRoute.Detail(
                                                source = item.source,
                                                id = item.id,
                                                title = item.title,
                                            ),
                                        )
                                    }
                                },
                                modifier = Modifier.width(cardWidth),
                            )
                        }
                    }
                }
            }
        }
    }
}
