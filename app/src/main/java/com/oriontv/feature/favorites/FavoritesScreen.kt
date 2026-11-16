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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
    /**
     * C11-P2-1：全局弹窗（登录/更新卡片）是否正开着——开着时恢复 effect
     * 不抢焦点（与详情/首页/播放页的 globalModalOpen 门控同款）。
     */
    globalModalOpen: Boolean = false,
    /**
     * 第 7 轮 P2-1：空态“去首页”的回栈动作（NavHost 传入 popUpTo(Home)
     * 路径）；为 null 时回落到 onNavigate(Home)（旧行为，防调用方漏传）。
     */
    onGoHome: (() -> Unit)? = null,
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
    // 第 6 轮 P2-7：恢复 effect 只在需要时跑一次——首进、弹窗关闭 tick、
    // 页内删除落点请求（focusRestoreRequest +1）；后台回前台的例行刷新
    // （ON_RESUME 每次都跑 refresh）不再每次把焦点从“删除/续播”按钮上
    // 拉回卡片。区分依据：导航返回时组合是重建的（restorePending 初值 true），
    // 后台回前台时组合还在（restorePending 已消费为 false）；删除落点由
    // ViewModel 的 focusRestoreRequest 显式通知，tick 由 lastHandledTick 比对。
    // Jewel 第 9 轮 P2-1：活的门控读数（与详情/首页同款）——恢复 effect 块
    // 捕获的 globalModalOpen 是启动时快照，弹窗在重试窗口内打开时旧块读不到；
    // State 随重组更新，重试每次复核都读最新值。
    val modalOpenState = rememberUpdatedState(globalModalOpen)
    var restorePending by remember { mutableStateOf(true) }
    var lastHandledTick by remember { mutableIntStateOf(0) }
    // C12-P1-2：加载失败“重试”成功后焦点丢失——refresh() 不置 restorePending、
    // tick 也不变，恢复 effect 开头的早退把重试成功拦在外面（错误页“重试”
    // 按钮被卸载、焦点无处可落）；记一笔“上次是否空列表”，空态→有内容时也
    // 跑一次恢复。例行刷新（已有内容时）不满足“由空变有”，不抢焦点。
    var hadNoItems by remember { mutableStateOf(true) }
    val restoreRequest = uiState.focusRestoreRequest
    LaunchedEffect(restoreRequest) {
        if (restoreRequest > 0) restorePending = true
    }
    LaunchedEffect(uiState.items, uiState.isLoading, focusRestoreTick) {
        if (uiState.isLoading) return@LaunchedEffect
        // C11-P2-1：全局弹窗开着时跳过本次抢焦——门控放在 restorePending/
        // lastHandledTick 消费之前，否则恢复请求会被吞掉、弹窗关闭后也不再恢复；
        // 弹窗关闭后 tick +1 重跑本 effect 再恢复。
        if (globalModalOpen) return@LaunchedEffect
        val emptyToContent = hadNoItems && uiState.items.isNotEmpty()
        hadNoItems = uiState.items.isEmpty()
        if (!shouldRunFavoritesRestore(restorePending, focusRestoreTick, lastHandledTick, emptyToContent)) return@LaunchedEffect
        restorePending = false
        lastHandledTick = focusRestoreTick
        if (!viewModel.initialFocusDone) {
            // A8-P2-2：空态/首卡的单次焦点请求改重试版（卡片尚未组合完成时静默失败）。
            if (uiState.items.isNotEmpty()) firstCardRequester.requestFocusWithRetry(isStillValid = { !modalOpenState.value })
            else goHomeRequester.requestFocusWithRetry(isStillValid = { !modalOpenState.value })
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
                    val restored = cardRequesters[savedIndex]?.requestFocusWithRetry(isStillValid = { !modalOpenState.value }) ?: false
                    if (!restored && !modalOpenState.value) firstCardRequester.requestFocusWithRetry(isStillValid = { !modalOpenState.value })
                }
                uiState.items.isNotEmpty() -> firstCardRequester.requestFocusWithRetry(isStillValid = { !modalOpenState.value })
                else -> goHomeRequester.requestFocusWithRetry(isStillValid = { !modalOpenState.value })
            }
        }
    }

    // B35-P2-1：删收藏失败 Toast 放在空态 early return 之前——Toast 本在
    // 下面根 Box 内、return 之后，列表变空时永不组合（“该收藏已不存在”
    // 提示静默丢失）；放这里与根 Box 同级、覆盖层位置，空态与有内容两种
    // 状态下都可达（2 秒消费 effect 在上面，不受影响）。
    if (uiState.favoriteError != null) {
        Box(
            // B36-P2-1：Toast 先组合、内容根 Box 后组合，同父布局内后组合
            // 的兄弟画在上面（全仓无一处 zIndex），有内容时 Toast 会被末行
            // 卡片盖住——置顶一层，early return 结构不动。
            modifier = Modifier.fillMaxSize().zIndex(1f),
            contentAlignment = Alignment.BottomCenter,
        ) {
            OrionToast(
                message = uiState.favoriteError?.message ?: "",
                modifier = Modifier.padding(bottom = 48.dp),
            )
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
                // C8-P2-6：加载失败时空态只有“去首页”，无原地重试入口——
                // 失败时按钮换成“重试”，调已有的 viewModel.refresh()。
                text = if (uiState.loadFailed) "重试" else "去首页",
                onClick = {
                    if (uiState.loadFailed) viewModel.refresh()
                    // 第 7 轮 P2-1：回栈到已有的 Home，不经 navigateSingleTop
                    // 直推（栈里会留出 Home→Favorites→Home，返回先回收藏页）。
                    else onGoHome?.invoke() ?: onNavigate(OrionRoute.Home)
                },
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
                            // 第 6 轮 P1-2：删除进行中禁用，防连按把条目删了又加回来。
                            enabled = key !in uiState.removingKeys,
                        )
                    }
                }
            }
        }
    }
    }
}

/**
 * C12-P1-2：收藏页恢复 effect 是否应跑一次（纯函数，可单测）——首进
 * （restorePending）、弹窗关闭 tick 变化、或空态→有内容（加载失败“重试”
 * 成功）时跑；例行刷新（已有内容、tick 不变）不跑，不把焦点从“删除/续播”
 * 按钮上拉回卡片。
 */
internal fun shouldRunFavoritesRestore(
    restorePending: Boolean,
    focusRestoreTick: Int,
    lastHandledTick: Int,
    emptyToContent: Boolean,
): Boolean = restorePending || focusRestoreTick != lastHandledTick || emptyToContent
