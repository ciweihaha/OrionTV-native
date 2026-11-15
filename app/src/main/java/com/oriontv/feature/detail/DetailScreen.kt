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
    /**
     * 第 7 轮 P1-1：全局弹窗（更新卡片/登录弹窗）是否正开着——开着时数据到达
     * /错误到达的焦点 effect 不抢焦点（见 DetailScreenContent）。
     * 第 7 轮豆瓣透传：首页来源的评分（"8.5"），无评分时 null。
     */
    globalModalOpen: Boolean = false,
    rate: String? = null,
) {
    DetailScreenContent(source, id, title, onNavigate, viewModel, focusRestoreTick, globalModalOpen, rate)
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
    globalModalOpen: Boolean = false,
    rate: String? = null,
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
    // 第 7 轮 P1-1：数据/错误到达的焦点 effect 增加全局弹窗门控——更新卡片/
    // 登录弹窗开着时不抢焦点（弹窗自身的焦点陷阱只在挂载时抢一次，之后任何
    // 一次详情数据到达都会把焦点偷回被遮罩盖住的页面按钮，用户盲按会在卡片
    // 背后触发播放/重试）。弹窗关闭后由下面的 focusRestoreTick effect 恢复。
    // error effect 另加一次门控（lastErrorFocused）：同一失败只触发一次，
    // 每次失败（error 值变化）才触发；error 清掉后门控复位。
    var lastErrorFocused by remember { mutableStateOf<String?>(null) }
    // P1-4/P2-2：只在首次加载完成时把焦点落到“播放”按钮；换源不抢焦点。
    LaunchedEffect(uiState.current) {
        if (shouldFocusPlayButton(uiState.current != null, viewModel.focusAnchored, globalModalOpen)) {
            // A8-P2-2 同款：按钮尚未组合完成时单次请求静默失败，改重试版。
            playRequester.requestFocusWithRetry()
            viewModel.focusAnchored = true
        }
    }
    // C4-P1-4：加载失败（current 为空且 error 非空）时焦点落到“重试”按钮。
    LaunchedEffect(uiState.error) {
        val error = uiState.error
        if (error == null) {
            lastErrorFocused = null
        } else if (shouldFocusRetryButton(error, uiState.current != null, globalModalOpen, lastErrorFocused)) {
            // A8-P2-2：单次请求在按钮尚未组合完成时静默失败，改重试版。
            retryRequester.requestFocusWithRetry()
            lastErrorFocused = error
        }
    }
    // P1-4：从播放页返回时重组，焦点已随旧组合处置而丢失，把焦点还给“播放”按钮。
    // C4-P1-2：弹窗关闭后 tick 变化也重跑。
    // 第 7 轮 P1-1：tick 恢复按当前态选锚点——数据已到（弹窗打开期间到达、
    // 上面 effect 被门控拦住）时落“播放”并补置 focusAnchored；错误态时落
    // “重试”（error effect 不重跑，tick 是错误态弹窗关闭后唯一的恢复口）。
    // C11-P1-3：去掉 tick <= 0 早退——一整个进程里从没关过登录/更新弹窗时
    // tick 恒为 0，从播放页返回详情页必丢焦点（另一条恢复路又被
    // focusAnchored == true 挡住，是最常见的路径）。首进时 current/error
    // 皆空，detailRestoreFocusTarget 本就是 null、no-op；Detail→Detail 时
    // init 的 LaunchedEffect(source, id) 先跑、已同步清掉 current，同样 no-op。
    LaunchedEffect(focusRestoreTick) {
        when (detailRestoreFocusTarget(uiState.current != null, uiState.error != null)) {
            DetailFocusTarget.Play -> {
                // A8-P2-2 同款重试版：弹窗关闭瞬间按钮可能尚未重组完成。
                playRequester.requestFocusWithRetry()
                viewModel.focusAnchored = true
            }
            DetailFocusTarget.Retry -> retryRequester.requestFocusWithRetry()
            null -> Unit
        }
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
                            val meta = formatDetailMeta(
                                year = current.year,
                                rate = rate,
                                typeName = current.type_name,
                                class_ = current.class_,
                            )
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
                                    // 第 7 轮跟进 D7-P2-10：收藏态未知（服务端失败且从未
                                    // 查过）时也禁用——不把未知画成“未收藏”，也避免在
                                    // 未知态上误 toggle；禁用态由 SecondaryButton 置灰
                                    //（alpha 0.4f，不可聚焦）。
                                    enabled = !uiState.isFavoriteToggling && !uiState.isFavoriteUnknown,
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

/**
 * 第 7 轮 P1-1：详情页焦点 effect 的纯决策（可单测；effect 本体是 Compose，
 * JVM 单测覆盖不到，决策抽出来钉住）。
 *
 * 数据到达时是否落“播放”：有数据、尚未落过锚点、全局弹窗没开着。
 * 弹窗开着时不抢焦点、不置锚点——弹窗关闭后由 focusRestoreTick effect
 * 按当前态恢复（见 detailRestoreFocusTarget）。
 */
internal fun shouldFocusPlayButton(
    hasCurrent: Boolean,
    focusAnchored: Boolean,
    globalModalOpen: Boolean,
): Boolean = hasCurrent && !focusAnchored && !globalModalOpen

/**
 * 第 7 轮 P1-1：错误到达时是否落“重试”：有错误、无数据、全局弹窗没开着、
 * 且不是同一失败的重复触发（lastHandledError 是上一次已落过焦点的错误文案；
 * error 清掉后调用方把门控复位为 null，下一次同文案失败可再次触发）。
 */
internal fun shouldFocusRetryButton(
    error: String?,
    hasCurrent: Boolean,
    globalModalOpen: Boolean,
    lastHandledError: String?,
): Boolean =
    error != null && !hasCurrent && !globalModalOpen && error != lastHandledError

/** 弹窗关闭后（focusRestoreTick）详情页焦点恢复的锚点。 */
internal enum class DetailFocusTarget {
    Play,
    Retry,
}

/**
 * 第 7 轮 P1-1：弹窗关闭后按当前态选恢复锚点——有数据落“播放”（含弹窗打开
 * 期间到达、上面 effect 被门控拦住的情形），否则有错误落“重试”，
 * 加载中（既无数据也无错误）不恢复。
 */
internal fun detailRestoreFocusTarget(hasCurrent: Boolean, hasError: Boolean): DetailFocusTarget? =
    when {
        hasCurrent -> DetailFocusTarget.Play
        hasError -> DetailFocusTarget.Retry
        else -> null
    }

/**
 * 第 7 轮豆瓣透传：详情页 meta 行（年份 · 评分 · 类型 · 分类）。
 * 评分只在首页透传进来且有效时显示（"8.5" → "8.5 分"，与首页卡片文案一致）；
 * "0.0"/空白/缺失都不显示（HomeRowItem 映射层已滤 "0.0"，这里再守一道）。
 */
internal fun formatDetailMeta(
    year: String?,
    rate: String?,
    typeName: String?,
    class_: String?,
): String = listOfNotNull(
    year?.takeIf { it.isNotBlank() },
    rate?.takeIf { it.isNotBlank() && it != "0.0" }?.let { "$it 分" },
    typeName?.takeIf { it.isNotBlank() },
    class_?.takeIf { it.isNotBlank() },
).joinToString(" · ")
