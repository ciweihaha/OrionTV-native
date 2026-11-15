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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.Text
import com.oriontv.core.focus.preferredFocus
import com.oriontv.core.focus.requestFocusWithRetry
import com.oriontv.navigation.OrionRoute
import com.oriontv.ui.components.CategoryChip
import com.oriontv.ui.components.LoadingAnimation
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
 * 焦点：首进落分类栏锚点；从详情/播放返回时恢复上次所在的卡片（行标题
 * + source+id 键锚定，P2-9），不拉回顶部（P1-4）。
 */
@Composable
fun HomeScreen(
    onNavigate: (OrionRoute) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    /** C4-P1-2：NavHost 级弹窗关闭后 +1，各页重跑默认焦点恢复。 */
    focusRestoreTick: Int = 0,
    /**
     * C11-P1-2：全局弹窗（登录/更新卡片）是否正开着——开着时行到达的焦点
     * effect 不抢焦点（与详情页第 7 轮 P1-1 的门控同款，首页漏了）。
     */
    globalModalOpen: Boolean = false,
) {
    val uiState by viewModel.uiState.collectAsState()
    // 回栈后分类选择不重置（原 remember 会丢）。
    var selectedCategory by rememberSaveable { mutableStateOf(HomeCategory.RECENT.title) }
    val firstChipRequester = remember { FocusRequester() }
    // C4-P0-1：未配置空态“去设置”按钮的焦点锚点（空态分支在焦点请求前 return，
    // 首装无焦点、遥控失灵；锚点与请求都放在 return 之前）。
    val goSettingsRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 每行横向滚动状态 + 每张卡片的焦点锚点（返回恢复用；键为 "行:卡片"）。
    val rowListStates = remember { mutableMapOf<String, LazyListState>() }
    val cardRequesters = remember { mutableMapOf<String, FocusRequester>() }
    // Jewel 第 9 轮 P2-1：活的门控读数（与详情页同款）——effect 块捕获的
    // globalModalOpen 是启动时快照，弹窗在重试窗口内打开时旧块读不到；
    // rememberUpdatedState 的 State 随重组更新，重试每次复核都读最新值。
    val modalOpenState = rememberUpdatedState(globalModalOpen)

    // 返回首页时刷新“最近播放”行（刚看完的断点要立刻出现）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshRecent()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // C4-P0-1：空态分支内请求焦点（必须放在下面 return 之前，否则到不了；
    // 按钮未组合时 requestFocus 只是空操作，等 serverConfigured 变 false
    // 的那次重组、按钮已组合时才真正落焦）。
    LaunchedEffect(uiState.serverConfigured) {
        if (!uiState.serverConfigured) goSettingsRequester.requestFocus()
    }

    // C12-P1-6：未配置空态的 tick 监听（必须放在下面 return 之前）——effect B
    // 在 return 之后，更新卡片关闭后 tick +1 到不了空态，焦点丢失、只能杀进程
    // 重进；空态专用的 tick 监听放这里，tick 变化且仍未配置时落焦“去设置”。
    LaunchedEffect(focusRestoreTick) {
        if (shouldFocusEmptyStateOnTick(focusRestoreTick, uiState.serverConfigured)) {
            goSettingsRequester.requestFocus()
        }
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
            PrimaryButton(
                text = "去设置",
                onClick = { onNavigate(OrionRoute.Settings) },
                modifier = Modifier.preferredFocus(goSettingsRequester),
            )
        }
        return
    }

    // P1-4/P2-9：焦点默认只在首次进入时落在分类栏“最近播放”上；
    // 返回时按条目键恢复保存的焦点目标（行标题 + source+id），滚动到位
    // 后再请求焦点。恢复逻辑见下面的 restoreSavedCard（两个 effect 共用）。
    // C4-P1-2：弹窗关闭后 tick 变化也重跑同一条恢复路（effect B）。
    var restoreDone by remember { mutableStateOf(false) }

    // 按保存的条目键恢复焦点（P2-9：不按行/卡片 index，列表变化后 index
    // 对应的已是另一张卡）。返回时行还没回来不回落首 chip，返回 false
    // 让调用方等下一次 rows 变化再试；行已加载完仍找不到才回落首 chip。
    // 目标卡片尚未组合完成时重试请求（C4-P2-2），仍失败才回默认锚点，
    // 不再静默丢焦点；固定 120ms 延时已去掉（B4-P2-23 同款问题）。
    // Jewel 第 9 轮 P2-1：[isStillValid] 由调用方传入活的门控读数——重试
    // 每次都复核，弹窗在窗口内打开即停；重试耗尽后的回落首 chip 也先复核，
    // 不让单次 requestFocus 在弹窗背后偷焦。
    suspend fun restoreSavedCard(isStillValid: () -> Boolean = { true }): Boolean {
        val rows = uiState.rows
        val found = findSavedCard(rows, viewModel.savedFocusKey)
        if (found == null) {
            if (!uiState.isLoading) {
                firstChipRequester.requestFocus()
                return true
            }
            return false
        }
        val (row, card) = found
        listState.scrollToItem(row + 1)
        rowListStates[rows[row].title]?.scrollToItem(card)
        val restored = cardRequesters["$row:$card"]?.requestFocusWithRetry(isStillValid = isStillValid) ?: false
        if (!restored && isStillValid()) firstChipRequester.requestFocus()
        return true
    }

    // effect A：以 rows 为键——返回时行若尚未加载完（refresh() 在途），
    // 等行回来再恢复；恢复只做一次，常驻期间 rows 变化（refreshRecent）
    // 不再抢焦点。
    // C11-P1-2：全局弹窗开着时跳过本次抢焦（冷启动登录弹窗与首页数据到达
    // 竞态时，焦点会被偷到弹窗底下的分类 chip 上）；不置 initialFocusDone/
    // restoreDone，弹窗关闭后由下面的 tick effect（B）恢复。key 加
    // globalModalOpen：弹窗开关即取消/重跑本 effect，不只靠到达时那一次判断。
    // C12-P1-1：首载失败时 restoreDone 已被置 true（上面 !initialFocusDone
    // 分支在行还空着时就跑过），重试成功后行由空变非空、错误页“重试”按钮
    // 被卸载、焦点丢失，另两条分支都不跑；记一笔行是否曾为空，由空变非空时
    // 再跑一次恢复（回保存卡片或首 chip）。
    var rowsWereEmpty by remember { mutableStateOf(true) }
    LaunchedEffect(uiState.rows, globalModalOpen) {
        if (globalModalOpen) return@LaunchedEffect
        val wasEmpty = rowsWereEmpty
        rowsWereEmpty = uiState.rows.isEmpty()
        if (!viewModel.initialFocusDone) {
            firstChipRequester.requestFocus()
            viewModel.initialFocusDone = true
            restoreDone = true
        } else if (!restoreDone) {
            if (restoreSavedCard(isStillValid = { !modalOpenState.value })) restoreDone = true
        } else if (shouldRecoverFocusAfterRetry(wasEmpty, uiState.rows.isNotEmpty(), restoreDone)) {
            restoreSavedCard(isStillValid = { !modalOpenState.value })
        }
    }
    // effect B：弹窗关闭后 tick 变化重跑恢复；行还在加载时跳过
    // （等 effect A 在行回来时恢复），不回落首 chip。
    // C11-P1-2：effect A 因弹窗跳过时 initialFocusDone 仍为假，tick 恢复
    // 也要能走首次落焦那条路（不只恢复已保存的卡片），否则首 chip 永远落不上。
    // Jewel 第 9 轮 P2-1：补第 8 轮 P2-8 同款门控——key 加 globalModalOpen，
    // 弹窗开着时跳过本次抢焦（关闭后 tick +1 重跑再恢复）；恢复本身经
    // restoreSavedCard 的重试谓词复核门控（见上）。
    LaunchedEffect(focusRestoreTick, globalModalOpen) {
        if (globalModalOpen) return@LaunchedEffect
        if (focusRestoreTick > 0) {
            if (!viewModel.initialFocusDone) {
                firstChipRequester.requestFocus()
                viewModel.initialFocusDone = true
                restoreDone = true
            } else {
                restoreSavedCard(isStillValid = { !modalOpenState.value })
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // P2-7：减的是内容区左右边距之和（48dp×2），不是导航轨宽；
        // 旧公式里的 96.dp 与导航轨宽同值纯属巧合，改一处就错位。
        val layoutDirection = LocalLayoutDirection.current
        val horizontalPadding =
            OrionDimens.ContentPadding.calculateLeftPadding(layoutDirection) +
                OrionDimens.ContentPadding.calculateRightPadding(layoutDirection)
        val cardWidth =
            (maxWidth - horizontalPadding - OrionDimens.CardSpacing * 5f) / OrionDimens.HomeRowCards.toFloat()
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
                    // C5-P2-3：全屏加载态改用 LoadingAnimation（真转圈动画）。
                    LoadingAnimation()
                }
            }
            if (uiState.error != null && uiState.rows.isEmpty()) {
                val errorMessage = uiState.error
                item {
                    // P2-10：错误态居中需占满宽，否则在 LazyColumn item 里是窄列、视觉左偏。
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = errorMessage ?: "", style = OrionTextStyles.Body)
                        Spacer(modifier = Modifier.height(24.dp))
                        SecondaryButton(text = "重试", onClick = { viewModel.refresh() })
                    }
                }
            }
            itemsIndexed(uiState.rows) { rowIndex, row ->
                val isRecent = row.title == HomeCategory.RECENT.title
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(text = row.title, style = OrionTextStyles.SectionTitle)
                    Spacer(modifier = Modifier.height(12.dp))
                    // P2-9：单行失败留占位行，用户能看到哪一行挂了。
                    // B-P2-20：有旧数据时也显示 error（“显示上次内容”的提示），不只空行才显示。
                    if (row.error != null) {
                        Text(
                            text = row.error,
                            style = OrionTextStyles.Body,
                            color = OrionColors.TextSecondary,
                        )
                    }
                    // 分类浏览分页：取下一页失败不丢已有卡片，行尾留一句失败文案，
                    // 焦点移开再回来即重试（loadMore 失败后 hasMore 仍为真）。
                    if (row.loadMoreError != null) {
                        Text(
                            text = row.loadMoreError,
                            style = OrionTextStyles.Body,
                            color = OrionColors.TextSecondary,
                        )
                    }
                    LazyRow(
                        state = rowListStates.getOrPut(row.title) { LazyListState() },
                        horizontalArrangement = Arrangement.spacedBy(OrionDimens.CardSpacing),
                    ) {
                        itemsIndexed(row.items) { cardIndex, item ->
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
                                                // 第 7 轮豆瓣透传：首页卡片自带的评分
                                                //（agg vod_douban_score / 豆瓣 rate）；
                                                // 最近播放行无评分（上面直达播放页，不走这里）。
                                                rate = item.rate,
                                            ),
                                        )
                                    }
                                },
                                onFocused = {
                                    viewModel.savedFocusKey = homeFocusKey(row.title, item)
                                    // 分类浏览分页：电视遥控按方向键逐张移动，焦点位置即滚动
                                    // 位置；焦点到行尾附近（还剩 3 张）时取下一页。
                                    // ViewModel 的状态机门控会拦下重复触发、取数中与已到底的行。
                                    if (row.hasMore &&
                                        cardIndex >= row.items.size - RowPagingState.FOCUS_THRESHOLD
                                    ) {
                                        viewModel.loadMore(row.title)
                                    }
                                },
                                modifier = Modifier
                                    .width(cardWidth)
                                    .preferredFocus(
                                        cardRequesters.getOrPut("$rowIndex:$cardIndex") {
                                            FocusRequester()
                                        },
                                    ),
                            )
                        }
                        // 分类浏览分页：正在取下一页时行尾挂一句提示（新卡片追加进来后焦点不丢）。
                        if (row.isLoadingMore) {
                            item {
                                Text(
                                    text = "加载更多中…",
                                    style = OrionTextStyles.Body,
                                    color = OrionColors.TextSecondary,
                                    modifier = Modifier.width(cardWidth),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
