package com.oriontv.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.oriontv.core.focus.preferredFocus
import com.oriontv.data.repo.DetailSearchLogic
import com.oriontv.navigation.OrionRoute
import com.oriontv.ui.components.CategoryChip
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.components.SecondaryButton
import com.oriontv.ui.components.posterImageRequest
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles

/**
 * 详情页（ui-design.md §5.2）：
 * 左 2/5 海报（16dp 圆角），右 3/5 信息：标题 22sp、年份/评分/简介、
 * 源切换 chip 行、“播放”主按钮 + “收藏”次按钮；
 * 下方选集网格每行 8 个，长剧折叠成 “1-20 / 21-40 …” 分组 chip；
 * 失效源置灰仍可切换；换源保留集数。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    source: String,
    id: String,
    onNavigate: (OrionRoute) -> Unit,
    onBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    DetailScreenContent(source, id, "", onNavigate, viewModel)
}

/** 带标题的详情页入口（导航路由传 title；骨架旧入口的 title 为空时用 id 兜底）。 */
@Composable
fun DetailScreen(
    source: String,
    id: String,
    title: String,
    onNavigate: (OrionRoute) -> Unit,
    onBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    DetailScreenContent(source, id, title, onNavigate, viewModel)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailScreenContent(
    source: String,
    id: String,
    title: String,
    onNavigate: (OrionRoute) -> Unit,
    viewModel: DetailViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val playRequester = remember { FocusRequester() }

    LaunchedEffect(source, id) {
        viewModel.init(source, id, title.ifBlank { id })
    }
    // 详情加载完后焦点落在“播放”主按钮上（首选焦点锚点）。
    LaunchedEffect(uiState.current) {
        if (uiState.current != null) playRequester.requestFocus()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val gridCellWidth =
            (maxWidth - 96.dp - 12.dp * 7f) / OrionDimens.EpisodeGridColumns.toFloat()
        LazyColumn(
            contentPadding = OrionDimens.ContentPadding,
            verticalArrangement = Arrangement.spacedBy(OrionDimens.RowSpacing),
        ) {
            if (uiState.isLoading && uiState.current == null) {
                item {
                    Text(
                        text = "正在加载详情…",
                        style = OrionTextStyles.Body,
                        color = OrionColors.TextSecondary,
                    )
                }
            }
            if (uiState.error != null && uiState.current == null) {
                val errorMessage = uiState.error
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = errorMessage ?: "", style = OrionTextStyles.Body)
                        Spacer(modifier = Modifier.height(24.dp))
                        SecondaryButton(
                            text = "重试",
                            onClick = { viewModel.init(source, id, title.ifBlank { id }) },
                        )
                    }
                }
            }
            val current = uiState.current
            if (current != null) {
                item {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        // 左 2/5：海报
                        Box(
                            modifier = Modifier
                                .weight(2f)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(OrionDimens.PanelCornerRadius)),
                        ) {
                            AsyncImage(
                                model = posterImageRequest(current.poster),
                                contentDescription = current.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.matchParentSize(),
                            )
                        }
                        // 右 3/5：信息
                        Column(
                            modifier = Modifier
                                .weight(3f)
                                .padding(start = 32.dp),
                        ) {
                            Text(text = current.title, style = OrionTextStyles.PageTitle)
                            Spacer(modifier = Modifier.height(8.dp))
                            val meta = listOfNotNull(
                                current.year?.takeIf { it.isNotBlank() },
                                current.type_name?.takeIf { it.isNotBlank() },
                                current.class_?.takeIf { it.isNotBlank() },
                            ).joinToString(" · ")
                            if (meta.isNotBlank()) {
                                Text(
                                    text = meta,
                                    style = OrionTextStyles.Body,
                                    color = OrionColors.TextSecondary,
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                            if (!current.desc.isNullOrBlank()) {
                                Text(
                                    text = current.desc,
                                    style = OrionTextStyles.Body,
                                    color = OrionColors.TextSecondary,
                                    maxLines = 5,
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                            Row {
                                PrimaryButton(
                                    text = "播放",
                                    onClick = {
                                        onNavigate(
                                            OrionRoute.Play(
                                                source = current.source,
                                                id = current.id.orEmpty(),
                                                title = current.title,
                                                episodeIndex = uiState.selectedEpisode,
                                            ),
                                        )
                                    },
                                    modifier = Modifier.preferredFocus(playRequester),
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                SecondaryButton(
                                    text = if (uiState.isFavorite) "已收藏" else "收藏",
                                    onClick = { viewModel.toggleFavorite() },
                                )
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                            // 源切换 chip 行：失败源标灰仍可切换。
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(OrionDimens.ChipSpacing),
                                verticalArrangement = Arrangement.spacedBy(OrionDimens.ChipSpacing),
                            ) {
                                uiState.sources.forEach { (src, srcName) ->
                                    val failed = src in uiState.failedSources
                                    CategoryChip(
                                        text = srcName + if (failed) "（失效）" else "",
                                        selected = current.source == src,
                                        onClick = { viewModel.selectSource(src) },
                                        modifier = Modifier.alpha(if (failed) 0.4f else 1f),
                                    )
                                }
                                if (!uiState.allSourcesLoaded) {
                                    Text(
                                        text = "正在加载更多源…",
                                        style = OrionTextStyles.Small,
                                        color = OrionColors.TextWeak,
                                        modifier = Modifier.align(Alignment.CenterVertically),
                                    )
                                }
                            }
                        }
                    }
                }
                // 下方选集区。
                item {
                    val totalEpisodes = current.episodes.size
                    val groups = DetailSearchLogic.episodeGroups(totalEpisodes)
                    val selectedGroup = uiState.selectedGroup.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
                    val (groupStart, groupEnd) = groups.getOrNull(selectedGroup) ?: (1 to totalEpisodes)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "选集", style = OrionTextStyles.SectionTitle)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = "共 $totalEpisodes 集",
                                style = OrionTextStyles.Small,
                                color = OrionColors.TextSecondary,
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        if (groups.size > 1) {
                            Row(horizontalArrangement = Arrangement.spacedBy(OrionDimens.ChipSpacing)) {
                                groups.forEachIndexed { g, (start, end) ->
                                    CategoryChip(
                                        text = "$start-$end",
                                        selected = selectedGroup == g,
                                        onClick = { viewModel.selectGroup(g) },
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        if (totalEpisodes == 0) {
                            Text(
                                text = "该源暂无可播剧集，试试切换其他源",
                                style = OrionTextStyles.Body,
                                color = OrionColors.TextSecondary,
                            )
                        } else {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                for (ep in groupStart..groupEnd) {
                                    val epIndex = ep - 1
                                    CategoryChip(
                                        text = "第 $ep 集",
                                        selected = uiState.selectedEpisode == epIndex,
                                        onClick = {
                                            viewModel.selectEpisode(epIndex)
                                            onNavigate(
                                                OrionRoute.Play(
                                                    source = current.source,
                                                    id = current.id.orEmpty(),
                                                    title = current.title,
                                                    episodeIndex = epIndex,
                                                ),
                                            )
                                        },
                                        modifier = Modifier.width(gridCellWidth),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
