package com.oriontv.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.oriontv.core.focus.preferredFocus
import com.oriontv.core.focus.requestFocusWithRetry
import com.oriontv.data.repo.DetailSearchLogic
import com.oriontv.navigation.OrionRoute
import com.oriontv.ui.components.CategoryChip
import com.oriontv.ui.components.LoadingAnimation
import com.oriontv.ui.components.OrionToast
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
 * 第 3 轮收尾 B-P2-24：三参旧入口已删（全仓零调用，唯一调用走四参），
 * title 为空时仍用 id 兜底（见 DetailScreenContent 的 init 调用）。
 * 返回全靠系统 Back/导航（P2-6：第 5 轮删掉从未被调用的 onBack 死参数，
 * main 的 B-P2-24 已先删掉四参入口的 onBack，这里只剩注释对齐）。
 */
@Composable
fun DetailScreen(
    source: String,
    id: String,
    title: String,
    onNavigate: (OrionRoute) -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
    /** C4-P1-2：NavHost 级弹窗关闭后 +1，各页重跑默认焦点恢复。 */
    focusRestoreTick: Int = 0,
) {
    DetailScreenContent(source, id, title, onNavigate, viewModel, focusRestoreTick)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailScreenContent(
    source: String,
    id: String,
    title: String,
    onNavigate: (OrionRoute) -> Unit,
    viewModel: DetailViewModel,
    focusRestoreTick: Int = 0,
) {
    val uiState by viewModel.uiState.collectAsState()
    val playRequester = remember { FocusRequester() }
    // C4-P1-4：加载失败后“重试”按钮的焦点锚点（之前失败页无任何焦点，“重试”按不到）。
    val retryRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    // P2-15（第 5 轮）：选集网格高度不再用手算的 44dp 魔法值——
    // CategoryChip 的实际高度由 onSizeChanged 量出来；首帧未量到时
    // 先用 44dp 估算，不闪空屏。
    var episodeChipHeight by remember { mutableStateOf(44.dp) }

    LaunchedEffect(source, id) {
        viewModel.init(source, id, title.ifBlank { id })
    }
    // P1-4/P2-2：只在首次加载完成时把焦点落到“播放”按钮；换源不抢焦点。
    LaunchedEffect(uiState.current) {
        if (uiState.current != null && !viewModel.focusAnchored) {
            playRequester.requestFocus()
            viewModel.focusAnchored = true
        }
    }
    // C4-P1-4：加载失败（current 为空且 error 非空）时焦点落到“重试”按钮。
    LaunchedEffect(uiState.error) {
        if (uiState.error != null && uiState.current == null) {
            // A8-P2-2：单次请求在按钮尚未组合完成时静默失败，改重试版。
            retryRequester.requestFocusWithRetry()
        }
    }
    // P1-4：从播放页返回时重组，焦点已随旧组合处置而丢失，把焦点还给“播放”按钮。
    // C4-P1-2：弹窗关闭后 tick 变化也重跑。
    LaunchedEffect(focusRestoreTick) {
        if (viewModel.focusAnchored) playRequester.requestFocus()
    }
    // B-P0-1：收藏失败 Toast 2 秒消失（ui-design.md §5.8 同款）。
    // B5-P2-11：以事件 id 为 key——同文案连续两次时 id 不同，effect 一定重跑。
    LaunchedEffect(uiState.favoriteError?.id) {
        if (uiState.favoriteError != null) {
            delay(2000)
            viewModel.consumeFavoriteError()
        }
    }

    // P2-7：选集已改 LazyVerticalGrid（按列数均分），不再手动算卡宽；
    // 旧公式里的 96.dp 只是左右边距之和（48×2）的巧合值，与导航轨宽同值，删掉。
    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = OrionDimens.ContentPadding,
        verticalArrangement = Arrangement.spacedBy(OrionDimens.RowSpacing),
    ) {
            if (uiState.isLoading && uiState.current == null) {
                item {
                    // C5-P2-3：全屏加载态改用 LoadingAnimation（真转圈动画），
                    // 不再用纯文字“正在加载详情…”。
                    LoadingAnimation(message = "正在加载详情…")
                }
            }
            if (uiState.error != null && uiState.current == null) {
                val errorMessage = uiState.error
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = errorMessage ?: "", style = OrionTextStyles.Body)
                        Spacer(modifier = Modifier.height(24.dp))
                        SecondaryButton(
                            text = "重试",
                            onClick = { viewModel.init(source, id, title.ifBlank { id }) },
                            modifier = Modifier.preferredFocus(retryRequester),
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
                                // P1-2：选中集是断点集时带断点位置续播，否则从 0 开始。
                                val resumeMs = resumePositionMs(
                                    uiState.selectedEpisode,
                                    uiState.resumeEpisode,
                                    uiState.resumePositionSeconds,
                                )
                                PrimaryButton(
                                    text = if (resumeMs > 0) "继续播放" else "播放",
                                    onClick = {
                                        onNavigate(
                                            OrionRoute.Play(
                                                source = current.source,
                                                id = current.id.orEmpty(),
                                                title = current.title,
                                                episodeIndex = uiState.selectedEpisode,
                                                positionMs = resumeMs,
                                            ),
                                        )
                                    },
                                    // C8-P2-7：0 集时播放按钮置灰禁用——旧实现仍可点，
                                    // 点进去播放页直接报错。
                                    enabled = current.episodes.isNotEmpty(),
                                    modifier = Modifier.preferredFocus(playRequester),
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                SecondaryButton(
                                    text = if (uiState.isFavorite) "已收藏" else "收藏",
                                    onClick = { viewModel.toggleFavorite() },
                                    // 第 6 轮 P1-2：切换进行中禁用，防连按把收藏态翻回去。
                                    enabled = !uiState.isFavoriteToggling,
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
                            // P2-15（第 5 轮）：选集改 LazyVerticalGrid（每行 8 个，
                            // 带复用）；在 LazyColumn 里需固定高度：行数按组内集数算，
                            // 每行高 = 实测 chip 高 + 12dp 间距（不再手算 44dp）。
                            val episodeCount = groupEnd - groupStart + 1
                            val gridRows =
                                (episodeCount + OrionDimens.EpisodeGridColumns - 1) /
                                    OrionDimens.EpisodeGridColumns
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(OrionDimens.EpisodeGridColumns),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(
                                        (episodeChipHeight.value * gridRows +
                                            ((gridRows - 1).coerceAtLeast(0) * 12)).dp,
                                    ),
                            ) {
                                items(episodeCount) { offset ->
                                    val ep = groupStart + offset
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
                                                    // 选集直接播：只有断点集带断点位置。
                                                    positionMs = resumePositionMs(
                                                        epIndex,
                                                        uiState.resumeEpisode,
                                                        uiState.resumePositionSeconds,
                                                    ),
                                                ),
                                            )
                                        },
                                        modifier = Modifier.onSizeChanged { size ->
                                            with(density) {
                                                val h = size.height.toDp()
                                                if (h > 0.dp && h != episodeChipHeight) {
                                                    episodeChipHeight = h
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    // B-P0-1：收藏失败 Toast，底部居中。
    if (uiState.favoriteError != null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            OrionToast(
                message = uiState.favoriteError?.message ?: "",
                modifier = Modifier.padding(bottom = 48.dp),
            )
        }
    }
    }
}
