package com.oriontv.feature.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.Text
import com.oriontv.core.focus.preferredFocus
import com.oriontv.core.focus.requestFocusWithRetry
import com.oriontv.feature.remoteinput.RemoteInputModal
import com.oriontv.navigation.OrionRoute
import com.oriontv.ui.components.CategoryChip
import com.oriontv.ui.components.OrionToast
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.components.SecondaryButton
import com.oriontv.ui.components.VideoCard
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles
import kotlinx.coroutines.delay

/**
 * 搜索页（ui-design.md §5.5）：
 * 顶部搜索框（屏上键盘 + 扫码远程输入入口）+ 输入时下方联想 chips；
 * 结果网格；无结果提示 + “换个关键词”；搜索历史 chips（可单条删除）；
 * 搜索不清空已输内容，返回搜索页保留上次结果。
 * 焦点（P1-4）：首进落搜索框；从详情返回时恢复上次点的结果卡片，不拉回搜索框。
 */
@Composable
fun SearchScreen(
    onNavigate: (OrionRoute) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
    /** C4-P1-2：NavHost 级弹窗关闭后 +1，各页重跑默认焦点恢复。 */
    focusRestoreTick: Int = 0,
) {
    val uiState by viewModel.uiState.collectAsState()
    val remoteState by viewModel.remoteInputState.collectAsState()
    val searchRequester = remember { FocusRequester() }
    val scanButtonRequester = remember { FocusRequester() }
    // C8-P1-3/A8-P2-2：错误态按钮的焦点锚点（“重试”之前根本没有 requester）。
    val retryRequester = remember { FocusRequester() }
    val changeKeywordRequester = remember { FocusRequester() }
    val resultRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    // C6-P1-1：搜索框获焦态（焦点描边用；首进默认落点却无任何焦点态时，
    // 用户分不清焦点在搜索框还是在旁边的按钮上）。
    var searchBoxFocused by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    var showRemoteInputModal by remember { mutableStateOf(false) }
    // P2-10（第 5 轮）：键盘开关用 rememberSaveable——进详情返回后键盘
    // 状态不丢（普通 remember 随组合处置而丢失）。
    var showKeyboard by rememberSaveable { mutableStateOf(false) }
    // 扫码弹窗关闭后焦点还给触发点（P1-5）。
    var modalWasOpen by remember { mutableStateOf(false) }

    // C5-P1-1：屏上键盘由开变关时焦点回搜索框——之前键盘关闭后焦点留在已
    // 卸载的键盘按键上，遥控器数秒内按什么都没反应。
    var keyboardWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(showKeyboard) {
        if (showKeyboard) {
            keyboardWasOpen = true
        } else if (keyboardWasOpen) {
            keyboardWasOpen = false
            searchRequester.requestFocus()
        }
    }

    // P1-4/P2-9：首进落搜索框；返回时按条目键恢复上次的结果卡片。
    // 结果可能仍在加载（返回时搜索在途），最多等 1 秒；等不到或键已不在
    // 结果里，回落搜索框。
    // C4-P1-2：弹窗关闭后 tick 变化也重跑同一条恢复路。
    LaunchedEffect(focusRestoreTick) {
        if (!viewModel.initialFocusDone) {
            searchRequester.requestFocus()
            viewModel.initialFocusDone = true
        } else {
            // P2-9：按条目键恢复；返回时结果可能仍在加载，最多等 1 秒。
            val key = viewModel.savedResultKey
            var target = -1
            for (attempt in 0 until 10) {
                target = findSavedResult(uiState.results, key)
                if (target >= 0) break
                delay(100)
            }
            if (target >= 0) {
                gridState.scrollToItem(target)
                // C4-P2-2/B4-P2-23：目标卡片尚未组合完成时重试请求，仍失败才回搜索框，
                // 不再靠 120ms 固定延时猜时序、也不再静默丢焦点。
                val restored = resultRequesters[target]?.requestFocusWithRetry() ?: false
                if (!restored) searchRequester.requestFocus()
            } else {
                searchRequester.requestFocus()
            }
        }
    }

    // C8-P1-3：远程输入触发新搜索时结果网格卸载，焦点若在结果卡上即丢失、
    // 无任何恢复——加载一开始就把焦点收回搜索框（网格卸载后焦点有明确落点）；
    // 搜索失败时落到错误态按钮（有“重试”落“重试”，无结果落“换个关键词”，
    // 都落不上才回搜索框）。
    LaunchedEffect(uiState.isLoading) {
        if (uiState.isLoading) {
            searchRequester.requestFocus()
        }
    }
    LaunchedEffect(uiState.error) {
        val error = uiState.error ?: return@LaunchedEffect
        val landed = if (error is SearchError.Empty) {
            changeKeywordRequester.requestFocusWithRetry()
        } else {
            retryRequester.requestFocusWithRetry()
        }
        if (!landed) searchRequester.requestFocus()
    }

    // B6-P2-12：requester map 只增不减——结果数缩减时清掉越界下标的旧
    // FocusRequester，不让 map 随每次搜索无限长（下标只作组合内锚点，
    // 返回恢复走条目键，不冲突）。
    LaunchedEffect(uiState.results.size) {
        resultRequesters.keys.removeAll { it < 0 || it >= uiState.results.size }
    }

    // B6-P2-11：Toast 2 秒消失（ui-design.md §5.8，与播放页/设置页同款）。
    LaunchedEffect(uiState.toast?.id) {
        if (uiState.toast != null) {
            delay(2000)
            viewModel.consumeToast()
        }
    }

    // 配对成功后自动关弹窗（RN RemoteControlModal 的 onHandshake 同款）。
    LaunchedEffect(Unit) {
        viewModel.remoteInputHandshakes.collect { showRemoteInputModal = false }
    }
    LaunchedEffect(showRemoteInputModal) {
        if (showRemoteInputModal) {
            modalWasOpen = true
        } else if (modalWasOpen) {
            modalWasOpen = false
            scanButtonRequester.requestFocus()
        }
    }

    // P2-10（第 5 轮）：返回键先收屏上键盘。注册在弹窗 handler 之前，
    // 两者都开时弹窗先关（后注册的优先）。
    BackHandler(enabled = showKeyboard) { showKeyboard = false }
    // P2-5（第 5 轮）：返回键先关扫码弹窗，不直接 pop 搜索页。
    // 顺序注意：弹窗在键盘之上，BackHandler 是后注册的优先；键盘的
    // handler（P2-10）注册在这一行之前，两者都开时先关弹窗。
    BackHandler(enabled = showRemoteInputModal) { showRemoteInputModal = false }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(OrionDimens.ContentPadding),
    ) {
        // 顶部搜索框 + 屏上键盘 + 扫码输入入口。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(OrionDimens.CapsuleRadius))
                    .background(OrionColors.Surface)
                    // C6-P1-1：获焦时 2dp 品牌色描边（设计稿焦点三件套：描边），
                    // 未获焦 1dp 分隔线——搜索框是首进默认落点，必须看得见焦点。
                    .border(
                        width = if (searchBoxFocused) 2.dp else 1.dp,
                        color = if (searchBoxFocused) OrionColors.Brand else OrionColors.Divider,
                        shape = RoundedCornerShape(OrionDimens.CapsuleRadius),
                    )
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = uiState.query,
                    onValueChange = { viewModel.onQueryChange(it) },
                    textStyle = OrionTextStyles.Body.copy(color = OrionColors.Text),
                    modifier = Modifier
                        .fillMaxWidth()
                        .preferredFocus(searchRequester)
                        .onFocusChanged { searchBoxFocused = it.isFocused },
                    decorationBox = { innerTextField ->
                        if (uiState.query.isEmpty()) {
                            Text(text = "输入关键词搜索", style = OrionTextStyles.Body, color = OrionColors.TextWeak)
                        }
                        innerTextField()
                    },
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            SecondaryButton(
                text = if (showKeyboard) "收起键盘" else "屏上键盘",
                onClick = { showKeyboard = !showKeyboard },
            )
            Spacer(modifier = Modifier.width(16.dp))
            SecondaryButton(
                text = "手机扫码输入",
                onClick = { showRemoteInputModal = true },
                modifier = Modifier.preferredFocus(scanButtonRequester),
            )
        }
        if (showRemoteInputModal) {
            RemoteInputModal(
                serverUrl = remoteState.serverUrl,
                pairingCode = remoteState.pairingCode,
                pairingCodeRemainingSeconds = remoteState.pairingCodeRemainingSeconds,
                error = remoteState.error,
                onDismiss = { showRemoteInputModal = false },
                // P2-14：服务未开启时弹窗内一键开启，不用退出搜索去设置。
                onEnableRemoteInput = { viewModel.enableRemoteInput() },
            )
        }

        // P1-8：电视端屏上键盘（全键 + 9 键两档，最小可用实现）。
        // P2-10（第 5 轮）：搜完收键盘（“搜索”键、联想 chip、历史 chip
        // 都是“搜完”，都收起，不再盖住下方结果区）。
        if (showKeyboard) {
            Spacer(modifier = Modifier.height(16.dp))
            OnScreenKeyboard(
                query = uiState.query,
                onQueryChange = { viewModel.onQueryChange(it) },
                onSearch = {
                    showKeyboard = false
                    viewModel.searchNow(uiState.query)
                },
                onDismiss = { showKeyboard = false },
            )
        }

        // 联想 chip（输入时出现，点按直接搜）。
        if (uiState.suggestions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(OrionDimens.ChipSpacing)) {
                items(uiState.suggestions) { suggestion ->
                    CategoryChip(
                        text = suggestion,
                        selected = false,
                        onClick = {
                            showKeyboard = false
                            viewModel.searchNow(suggestion)
                        },
                    )
                }
            }
        }

        // 搜索历史 chip（可单条删除；点按填回并搜索）。
        if (uiState.query.isBlank() && uiState.history.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "搜索历史", style = OrionTextStyles.SectionTitle)
            Spacer(modifier = Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(OrionDimens.ChipSpacing)) {
                items(uiState.history) { keyword ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CategoryChip(
                            text = keyword,
                            selected = false,
                            onClick = {
                                showKeyboard = false
                                viewModel.searchHistory(keyword)
                                // C6-P1-3：点历史 chip 后整行被卸载（query 非空即
                                // 不渲染历史行），焦点无处可去；先回搜索框——
                                // 搜索框常驻，搜完结果出来后方向键可下移进结果区。
                                searchRequester.requestFocus()
                            },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // P2-27：删除按钮加大目标、与 chip 拉开距离（原“×”偏小且紧贴）。
                        SecondaryButton(
                            text = "删除",
                            onClick = {
                                viewModel.deleteHistory(keyword)
                                // C6-P1-3：删掉的 chip 行被卸载，焦点回搜索框，
                                // 不留在已卸载的按钮上。
                                searchRequester.requestFocus()
                            },
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        when {
            uiState.isLoading -> {
                Text(
                    text = "正在搜索…",
                    style = OrionTextStyles.Body,
                    color = OrionColors.TextSecondary,
                )
            }
            uiState.error != null -> {
                val error = uiState.error
                // 无结果提示 + “换个关键词”；其他错误也给分型文案 + 重试。
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Text(text = error?.message ?: "", style = OrionTextStyles.Body)
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PrimaryButton(
                            text = "换个关键词",
                            onClick = { viewModel.clearQuery() },
                            modifier = Modifier.preferredFocus(changeKeywordRequester),
                        )
                        if (error !is SearchError.Empty) {
                            SecondaryButton(
                                text = "重试",
                                onClick = { viewModel.searchNow(uiState.searchedQuery) },
                                modifier = Modifier.preferredFocus(retryRequester),
                            )
                        }
                        if (error is SearchError.NotConfigured) {
                            SecondaryButton(
                                text = "去设置",
                                onClick = { onNavigate(OrionRoute.Settings) },
                            )
                        }
                    }
                }
            }
            uiState.results.isNotEmpty() -> {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(6),
                    horizontalArrangement = Arrangement.spacedBy(OrionDimens.CardSpacing),
                    verticalArrangement = Arrangement.spacedBy(OrionDimens.RowSpacing),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(uiState.results) { index, result ->
                        VideoCard(
                            title = result.title,
                            posterUrl = result.poster,
                            meta = listOfNotNull(
                                result.year?.takeIf { it.isNotBlank() },
                                result.source_name,
                            ).joinToString(" · ").ifBlank { null },
                            onClick = {
                                onNavigate(
                                    OrionRoute.Detail(
                                        source = result.source,
                                        id = result.id.orEmpty(),
                                        title = result.title,
                                    ),
                                )
                            },
                            onFocused = { viewModel.savedResultKey = searchResultKey(result) },
                            modifier = Modifier.preferredFocus(
                                resultRequesters.getOrPut(index) { FocusRequester() },
                            ),
                        )
                    }
                }
            }
            // P2-12：初始空白态给引导，不留空白内容区。
            uiState.query.isBlank() && uiState.history.isEmpty() -> {
                Text(
                    text = "输入关键词开始搜索，或点“手机扫码输入”用手机打字，也可以打开屏上键盘输入。",
                    style = OrionTextStyles.Body,
                    color = OrionColors.TextSecondary,
                )
            }
        }

        // B6-P2-11：Toast 底部居中。
        if (uiState.toast != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                OrionToast(
                    message = uiState.toast?.message ?: "",
                    modifier = Modifier.padding(bottom = 48.dp),
                )
            }
        }
    }
}
