package com.oriontv.feature.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.Text
import com.oriontv.core.focus.preferredFocus
import com.oriontv.core.focus.requestFocusWithRetry
import com.oriontv.data.api.MoonTvApi
import com.oriontv.navigation.OrionRoute
import com.oriontv.ui.components.LoadingAnimation
import com.oriontv.ui.components.OrionToast
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.components.SecondaryButton
import com.oriontv.ui.components.VideoCard
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles

/**
 * 收藏页（ui-design.md §5.6）：
 * 网格，倒序（新收藏在前），卡片与首页同款；
 * 空态：“还没有收藏，去首页逛逛吧” + “去首页”按钮；
 * 详情页内可取消收藏，返回收藏页即刷新；页内也有删除按钮（P2-3），
 * 有断点记录的卡片另有“续播”按钮，直达播放页。
 * 焦点（P1-3/P1-4）：有内容时首进落首张卡片，空态时落“去首页”按钮；
 * 从详情返回时恢复上次所在的卡片，不拉回顶部。
 */
@Composable
fun FavoritesScreen(
    onNavigate: (OrionRoute) -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel(),
    /** C4-P1-2：NavHost 级弹窗关闭后 +1，各页重跑默认焦点恢复。 */
    focusRestoreTick: Int = 0,
) {
    val uiState by viewModel.uiState.collectAsState()
    val firstCardRequester = remember { FocusRequester() }
    val goHomeRequester = remember { FocusRequester() }
    val cardRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val gridState = rememberLazyGridState()

    // B6-P2-12：requester map 只增不减——条目数缩减时清掉越界下标的旧
    // FocusRequester，不让 map 随每次刷新无限长（下标只作组合内锚点，
    // 返回恢复走记录键，不冲突）。
    LaunchedEffect(uiState.items.size) {
        cardRequesters.keys.removeAll { it < 0 || it >= uiState.items.size }
    }

    // B7-P0-1：删收藏失败 Toast 2 秒消失（ui-design.md §5.8，详情页同款）。
    LaunchedEffect(uiState.favoriteError?.id) {
        if (uiState.favoriteError != null) {
            delay(2000)
            viewModel.consumeFavoriteError()
        }
    }

    // 每次回页都刷新（详情页取消收藏后返回即不残留）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 首进落默认锚点；返回时恢复上次卡片（滚动到位后再请求焦点）。
    // C4-P1-2：弹窗关闭后 tick 变化也重跑。
    LaunchedEffect(uiState.items, uiState.isLoading, focusRestoreTick) {
        if (uiState.isLoading) return@LaunchedEffect
        if (!viewModel.initialFocusDone) {
            if (uiState.items.isNotEmpty()) firstCardRequester.requestFocus()
            else goHomeRequester.requestFocus()
            viewModel.initialFocusDone = true
        } else {
            // P2-9（第 5 轮）：按记录键（source+id）锚定，不按 index——
            // 刷新/删除后 index 对应的已是另一张卡。键找不到了（刚删的就是
            // 它）才回落首卡；删后由 removeFavorite 预先把落点键换成下一张
            // /上一张，见 FavoritesViewModel。
            // 键本身由 SavedStateHandle 承载（第 5 轮 mica P1-3/P1-4 的持久化
            // 思路）：导航轨切走后 ViewModel 被清，回来仍能恢复。
            val saved = viewModel.savedFocusKey
            val savedIndex = uiState.items.indexOfFirst { it.first == saved }
            when {
                saved != null && savedIndex >= 0 -> {
                    gridState.scrollToItem(savedIndex)
                    // C4-P2-2：目标卡片尚未组合完成时重试请求，仍失败才回首张卡片，
                    // 不再静默丢焦点。
                    val restored = cardRequesters[savedIndex]?.requestFocusWithRetry() ?: false
                    if (!restored) firstCardRequester.requestFocus()
                }
                uiState.items.isNotEmpty() -> firstCardRequester.requestFocus()
                else -> goHomeRequester.requestFocus()
            }
        }
    }

    if (!uiState.isLoading && uiState.items.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(OrionDimens.ContentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // B-P2-20：拉取失败时空列表≠没收藏，别误导用户。
            Text(
                text = if (uiState.loadFailed) "收藏加载失败，请检查网络后重试" else "还没有收藏，去首页逛逛吧",
                style = OrionTextStyles.Body,
            )
            Spacer(modifier = Modifier.height(24.dp))
            PrimaryButton(
                text = "去首页",
                onClick = { onNavigate(OrionRoute.Home) },
                modifier = Modifier.preferredFocus(goHomeRequester),
            )
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().padding(OrionDimens.ContentPadding)) {
        Text(text = "我的收藏", style = OrionTextStyles.PageTitle)
        Spacer(modifier = Modifier.height(16.dp))
        // B-P2-20：拉取失败时展示的是上次成功结果，给一句提示。
        if (uiState.loadFailed) {
            Text(
                text = "加载失败，显示上次内容",
                style = OrionTextStyles.Small,
                color = OrionColors.TextSecondary,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (uiState.isLoading) {
            // C5-P2-3：全屏加载态改用 LoadingAnimation（真转圈动画）。
            LoadingAnimation()
            return@Column
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(6),
            horizontalArrangement = Arrangement.spacedBy(OrionDimens.CardSpacing),
            verticalArrangement = Arrangement.spacedBy(OrionDimens.RowSpacing),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(uiState.items) { index, (key, favorite) ->
                Column {
                    VideoCard(
                        title = favorite.title,
                        posterUrl = favorite.cover,
                        meta = listOfNotNull(
                            favorite.year?.takeIf { it.isNotBlank() },
                            favorite.source_name,
                        ).joinToString(" · ").ifBlank { null },
                        onClick = {
                            // D-P2-3：拆键统一走 MoonTvApi.splitKey（最后一个 + 切分 + 反转义），
                            // 不再用第一个 + 拆；否则段内含 + 的键拆错，且再拼回去会双重转义。
                            val (source, id) = MoonTvApi.splitKey(key)
                            onNavigate(
                                OrionRoute.Detail(
                                    source = source,
                                    id = id,
                                    title = favorite.search_title,
                                ),
                            )
                        },
                        onFocused = { viewModel.savedFocusKey = key },
                        modifier = Modifier.preferredFocus(
                            // 首张卡片用 firstCardRequester（首进锚点与返回恢复共用同一锚点）。
                            cardRequesters.getOrPut(index) {
                                if (index == 0) firstCardRequester else FocusRequester()
                            },
                        ),
                    )
                    // P2-3（第 5 轮）：页内操作入口——有断点记录时“续播”直达播放页
                    // （断点集 + 断点位置），否则只留“删除”。不走 VideoCard 的
                    // onLongClick：电视遥控长按不可发现，显式按钮更直接。
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val record = uiState.playRecords[key]
                        if (record != null) {
                            SecondaryButton(
                                text = "续播 第 ${record.index + 1} 集",
                                onClick = {
                                    val (source, id) = MoonTvApi.splitKey(key)
                                    onNavigate(
                                        OrionRoute.Play(
                                            source = source,
                                            id = id,
                                            title = favorite.title,
                                            episodeIndex = record.index,
                                            positionMs = record.play_time * 1000,
                                        ),
                                    )
                                },
                            )
                        }
                        SecondaryButton(
                            text = "删除",
                            onClick = { viewModel.removeFavorite(key, favorite) },
                        )
                    }
                }
            }
        }
    }
    // B7-P0-1：删收藏失败 Toast，底部居中。
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
