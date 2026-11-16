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
    // C21-P2-1：首载失败时错误页“重试”按钮的焦点锚点（详情页 C4-P1-4 同款范式；
    // 之前失败页焦点落在首 chip 上，“重试”须多按一次下键才到）。
    val retryRequester = remember { FocusRequester() }
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
    // C22-P2-1：开头先判 shouldFocusHomeRetry——有错行空时落“重试”而非首 chip：
    // 弹窗关闭后 tick 恢复（effect B）走这条路，initialFocusDone 已为真、
    // B 的 else 分支进 restoreSavedCard，旧实现 findSavedCard 在空行上返回
    // null、!isLoading 时无条件 firstChipRequester.requestFocus()，把焦点
    // 从弹窗按钮拉回首 chip。
    suspend fun restoreSavedCard(isStillValid: () -> Boolean = { true }): Boolean {
        val rows = uiState.rows
        if (shouldFocusHomeRetry(uiState.error, rows)) {
            retryRequester.requestFocusWithRetry(isStillValid = isStillValid)
            return true
        }
        val found = findSavedCard(rows, viewModel.savedFocusKey)
        if (found == null) {
            if (!uiState.isLoading) {
                firstChipRequester.requestFocus()
                return true
            }
            return false
        }
        val (row, card) = found
        listState.scrollToItem(row)
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
    // C22-P2-1：错误到达的去重门控（详情页 C4-P1-4 同款 lastErrorFocused）——
    // 同一失败只落一次“重试”，error 清掉后复位为 null，下一次同文案失败
    // 可再次触发。
    var lastErrorFocused by remember { mutableStateOf<String?>(null) }
    var rowsWereEmpty by remember { mutableStateOf(true) }
    // C22-P2-1：key 加 uiState.error——C21-P2-1 的修法在首进分支里查
    // shouldFocusHomeRetry，但首帧组合时 error 仍是 null（refresh() 的
    // 加载即挂起，失败结果只能在组合之后到达），首进分支
    // 恒走落首 chip、initialFocusDone 置真；错误到达时 rows 是同一空列表
    // 实例、key 比较不变，effect A 确定不重跑。key 加 error 后，错误到达
    // （null 变非 null）即重跑，下面的错误到达分支落“重试”，不论
    // initialFocusDone 是否已真；行非空时不抢。
    LaunchedEffect(uiState.rows, uiState.error, globalModalOpen) {
        if (globalModalOpen) return@LaunchedEffect
        val wasEmpty = rowsWereEmpty
        rowsWereEmpty = uiState.rows.isEmpty()
        val error = uiState.error
        if (error == null) {
            lastErrorFocused = null
        } else if (shouldFocusHomeRetryOnErrorArrival(error, uiState.rows, lastErrorFocused)) {
            retryRequester.requestFocusWithRetry(isStillValid = { !modalOpenState.value })
            lastErrorFocused = error
            viewModel.initialFocusDone = true
            restoreDone = true
            return@LaunchedEffect
        }
        if (!viewModel.initialFocusDone) {
            // C21-P2-1：首载失败（有错误且行空）时落“重试”而非首 chip——
            // 首 chip 此时是唯一的可用操作之外的落点，用户须多按一次下键
            // 才能重试；重试按钮尚未组合时用重试版请求（本页其他落焦同款门控）。
            if (shouldFocusHomeRetry(uiState.error, uiState.rows)) {
                retryRequester.requestFocusWithRetry(isStillValid = { !modalOpenState.value })
            } else {
                firstChipRequester.requestFocus()
            }
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
    // C22-P2-1：无弹窗的 plain 失败不触发本 effect（key 里无 error、tick 不变），
    // 靠 effect A 的 error key 恢复；有弹窗时关闭后 tick+1 走下面 else 分支，
    // restoreSavedCard 开头先判 shouldFocusHomeRetry，有错行空落“重试”。
    // Jewel 第 9 轮 P2-1：补第 8 轮 P2-8 同款门控——key 加 globalModalOpen，
    // 弹窗开着时跳过本次抢焦（关闭后 tick +1 重跑再恢复）；恢复本身经
    // restoreSavedCard 的重试谓词复核门控（见上）。
    LaunchedEffect(focusRestoreTick, globalModalOpen) {
        if (globalModalOpen) return@LaunchedEffect
        if (focusRestoreTick > 0) {
            if (!viewModel.initialFocusDone) {
                // C21-P2-1：effect A 因弹窗跳过时首载失败也走这条路——弹窗关闭后
                // tick 恢复仍须落“重试”而非首 chip，否则又回到旧行为。
                if (shouldFocusHomeRetry(uiState.error, uiState.rows)) {
                    retryRequester.requestFocusWithRetry(isStillValid = { !modalOpenState.value })
                } else {
                    firstChipRequester.requestFocus()
                }
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
        // C20-P1-1：分类 chip 栏移出 LazyColumn、固定在顶部（设计稿 §5.1
        // “分类菜单置顶”）——旧实现里 chip 栏是 LazyColumn 的第 0 项，点 chip
        // 后 scrollToItem 把目标行顶到视口顶部、chip 栏整体滚出视口并被处置，
        // 获焦节点随之丢焦点且无任何恢复口（滚动不改 rows/tick、restoreDone
        // 与 initialFocusDone 已为真），首页直接砖掉。固定头后焦点始终留在
        // 被点的 chip 上，菜单也一直可见；行从此是 LazyColumn 的第 0 项起，
        // 两处 scrollToItem 的 +1 偏移一并去掉。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(OrionDimens.ContentPadding),
        ) {
            // B36-P2-2：行不存在的 chip 置灰禁用——未配服务器时 HOT/TOP250 行
            // 不建、无播放记录时 RECENT 行不建（HomeViewModel.loadDouban 短路
            // 与 loadCategory），点按只改高亮、不滚动是死控件；禁用态 clickable
            // 不可聚焦，首进焦点锚点也随之移到首个可用 chip，不落到禁用的 RECENT 上。
            // 选中态只画在可用 chip 上（缺失行不占选中高亮）；占位行 + 重试不立项（B28）。
            val firstAvailableCategory = HomeCategory.entries.firstOrNull { category ->
                uiState.rows.any { it.title == category.title }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(OrionDimens.ChipSpacing)) {
                items(HomeCategory.entries) { category ->
                    val rowExists = uiState.rows.any { it.title == category.title }
                    CategoryChip(
                        text = category.title,
                        selected = rowExists && selectedCategory == category.title,
                        enabled = rowExists,
                        onClick = {
                            selectedCategory = category.title
                            // 点 chip 滚动到对应行（行从第 0 项开始，无偏移）。
                            val rowIndex = uiState.rows.indexOfFirst { it.title == category.title }
                            if (rowIndex >= 0) {
                                scope.launch { listState.scrollToItem(rowIndex) }
                            }
                        },
                        modifier = if (category == firstAvailableCategory) {
                            Modifier.preferredFocus(firstChipRequester)
                        } else {
                            Modifier
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(OrionDimens.RowSpacing))
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(OrionDimens.RowSpacing),
            ) {
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
                            SecondaryButton(
                                text = "重试",
                                onClick = { viewModel.refresh() },
                                modifier = Modifier.preferredFocus(retryRequester),
                            )
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
}
