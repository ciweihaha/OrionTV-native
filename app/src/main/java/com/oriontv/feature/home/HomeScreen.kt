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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.Text
import com.oriontv.core.focus.preferredFocus
import com.oriontv.navigation.OrionRoute
import com.oriontv.ui.components.CategoryChip
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.components.VideoCard
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles

/** 首页 7 个点播分类（ui-design.md §1：放首页顶部横向 chip 栏，不放导航轨）。 */
val HomeCategories = listOf("最近播放", "热门剧集", "电视剧", "电影", "动漫", "综艺", "豆瓣 Top250")

/**
 * 首页（ui-design.md §5.1，2026-11-14 用户决定：不要横幅，分类菜单置顶）：
 * 顶部：分类 chip 栏（最近播放 / 热门剧集 / 电视剧 / 电影 / 动漫 / 综艺 / 豆瓣 Top250），
 * 焦点默认落在“最近播放”上，按下进入内容行；
 * 下方依次：“最近播放”行（断点续播卡片，带进度条与“剩余 xx 分钟”）
 * → “热门剧集”行 → 各分类行（每行 6 张卡片，横向滚动）；
 * 未配置源时空态：一行提示 + “去设置”按钮。
 * M1 补：真实数据（最近播放断点续播、热门、各分类）、返回后焦点恢复到最近锚点。
 */
@Composable
fun HomeScreen(
    onNavigate: (OrionRoute) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    // M1：改为 SettingsRepository.hasServer 的真实状态。
    val serverConfigured = false
    var selectedCategory by remember { mutableStateOf("最近播放") }
    val firstChipRequester = remember { FocusRequester() }

    if (!serverConfigured) {
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
        val cardWidth = (maxWidth - 96.dp - OrionDimens.CardSpacing * 5f) / OrionDimens.HomeRowCards.toFloat()
        LazyColumn(
            contentPadding = OrionDimens.ContentPadding,
            verticalArrangement = Arrangement.spacedBy(OrionDimens.RowSpacing),
        ) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(OrionDimens.ChipSpacing)) {
                    items(HomeCategories) { category ->
                        CategoryChip(
                            text = category,
                            selected = selectedCategory == category,
                            onClick = { selectedCategory = category },
                            modifier = if (category == "最近播放") {
                                Modifier.preferredFocus(firstChipRequester)
                            } else {
                                Modifier
                            },
                        )
                    }
                }
            }
            item {
                HomeRow(title = "最近播放", cardWidth = cardWidth, count = 1) {
                    VideoCard(
                        title = "示例剧集",
                        posterUrl = "",
                        meta = "第 3 集",
                        progressFraction = 0.4f,
                        remainingLabel = "剩余 12 分钟",
                        onClick = { onNavigate(OrionRoute.Play(source = "agg", id = "recent1", title = "示例剧集", episodeIndex = 2)) },
                        modifier = Modifier.width(cardWidth),
                    )
                }
            }
            item {
                HomeRow(title = "热门剧集", cardWidth = cardWidth, count = 6) { i ->
                    VideoCard(
                        title = "热门剧集 ${i + 1}",
                        posterUrl = "",
                        meta = "2026 · 电视剧",
                        onClick = { onNavigate(OrionRoute.Detail(source = "agg", id = "hot${i + 1}")) },
                        modifier = Modifier.width(cardWidth),
                    )
                }
            }
            // 各分类行：电视剧 / 电影 / 动漫 / 综艺 / 豆瓣 Top250。
            for (category in HomeCategories.drop(2)) {
                item {
                    HomeRow(title = category, cardWidth = cardWidth, count = 6) { i ->
                        VideoCard(
                            title = "$category ${i + 1}",
                            posterUrl = "",
                            meta = "2026",
                            onClick = { onNavigate(OrionRoute.Detail(source = "agg", id = "cat${i + 1}")) },
                            modifier = Modifier.width(cardWidth),
                        )
                    }
                }
            }
        }
    }
}

/** 首页一行：18sp 区块标题 + 横向滚动卡片行（每行 6 张，每张卡是独立 item）。 */
@Composable
private fun HomeRow(
    title: String,
    cardWidth: androidx.compose.ui.unit.Dp,
    count: Int,
    card: @Composable (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = title, style = OrionTextStyles.SectionTitle)
        Spacer(modifier = Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(OrionDimens.CardSpacing)) {
            items(count) { index -> card(index) }
        }
    }
}
