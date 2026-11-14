package com.oriontv.feature.detail

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.tv.material3.Text
import com.oriontv.navigation.OrionRoute
import com.oriontv.ui.components.CategoryChip
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.components.SecondaryButton
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles

/**
 * 详情页（ui-design.md §5.2）：
 * 左 2/5 海报（16dp 圆角），右 3/5 信息：标题 22sp、年份/评分/简介、
 * 源切换 chip 行、“播放”主按钮 + “收藏”次按钮；
 * 下方选集网格每行 8 个，长剧折叠成 “1-20 / 21-40 …” 分组 chip；
 * 失效源置灰仍可切换；换源保留集数。
 * M1 补：多源聚合、真实集数、收藏状态、断点续播入口。
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
    // 占位数据（M1 接详情接口）。
    val sources = listOf("源一" to true, "源二" to true, "源三（失效）" to false)
    var selectedSource by remember { mutableStateOf(0) }
    var selectedEpisode by remember { mutableStateOf(0) }
    val totalEpisodes = 24
    val groupSize = 20
    val groupCount = (totalEpisodes + groupSize - 1) / groupSize
    var selectedGroup by remember { mutableStateOf(0) }
    val groupStart = selectedGroup * groupSize
    val groupEnd = minOf(groupStart + groupSize, totalEpisodes)
    var isFavorite by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val gridCellWidth = (maxWidth - 96.dp - 7 * 12.dp) / OrionDimens.EpisodeGridColumns
        LazyColumn(
            contentPadding = OrionDimens.ContentPadding,
            verticalArrangement = Arrangement.spacedBy(OrionDimens.RowSpacing),
        ) {
            item {
                Row(modifier = Modifier.fillMaxWidth()) {
                    // 左 2/5：海报
                    Box(
                        modifier = Modifier
                            .weight(2f)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(OrionDimens.PanelCornerRadius))
                            .background(OrionColors.Surface),
                    )
                    // 右 3/5：信息
                    Column(
                        modifier = Modifier
                            .weight(3f)
                            .padding(start = 32.dp),
                    ) {
                        Text(text = "影片标题", style = OrionTextStyles.PageTitle)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "2026 · 电影 · 8.5 分",
                            style = OrionTextStyles.Body,
                            color = OrionColors.TextSecondary,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "影片简介（占位，M1 接详情接口）。",
                            style = OrionTextStyles.Body,
                            color = OrionColors.TextSecondary,
                            maxLines = 3,
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Row {
                            PrimaryButton(
                                text = "播放",
                                onClick = {
                                    onNavigate(
                                        OrionRoute.Play(
                                            source = source,
                                            id = id,
                                            title = "影片标题",
                                            episodeIndex = selectedEpisode,
                                        ),
                                    )
                                },
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            SecondaryButton(
                                text = if (isFavorite) "已收藏" else "收藏",
                                onClick = { isFavorite = !isFavorite },
                            )
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(OrionDimens.ChipSpacing)) {
                            sources.forEachIndexed { index, (name, available) ->
                                CategoryChip(
                                    text = name,
                                    selected = selectedSource == index,
                                    onClick = { selectedSource = index },
                                    modifier = Modifier.alpha(if (available) 1f else 0.4f),
                                )
                            }
                        }
                    }
                }
            }
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(text = "选集", style = OrionTextStyles.SectionTitle)
                    Spacer(modifier = Modifier.height(12.dp))
                    if (groupCount > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(OrionDimens.ChipSpacing)) {
                            for (g in 0 until groupCount) {
                                val start = g * groupSize + 1
                                val end = minOf(start + groupSize - 1, totalEpisodes)
                                CategoryChip(
                                    text = "$start-$end",
                                    selected = selectedGroup == g,
                                    onClick = { selectedGroup = g },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        for (ep in groupStart until groupEnd) {
                            CategoryChip(
                                text = "第 ${ep + 1} 集",
                                selected = selectedEpisode == ep,
                                onClick = { selectedEpisode = ep },
                                modifier = Modifier.width(gridCellWidth),
                            )
                        }
                    }
                }
            }
        }
    }
}
