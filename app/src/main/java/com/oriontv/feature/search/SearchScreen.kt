package com.oriontv.feature.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
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
import com.oriontv.feature.remoteinput.RemoteInputModal
import com.oriontv.navigation.OrionRoute
import com.oriontv.ui.components.CategoryChip
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
    val resultRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val gridState = rememberLazyGridState()
    var showRemoteInputModal by remember { mutableStateOf(false) }
    var showKeyboard by remember { mutableStateOf(false) }
    // 扫码弹窗关闭后焦点还给触发点（P1-5）。
    var modalWasOpen by remember { mutableStateOf(false) }

    // C4-P1-3：屏上键盘处理返回键（先关键盘；扫码弹窗开着时由弹窗自己的
    // BackHandler 先关弹窗——它后注册、优先级更高）。
    BackHandler(enabled = showKeyboard && !showRemoteInputModal) {
        showKeyboard = false
    }

    // P1-4：首进落搜索框；返回时恢复上次的结果卡片。
    // C4-P1-2：弹窗关闭后 tick 变化也重跑同一条恢复路。
    LaunchedEffect(focusRestoreTick) {
        if (!viewModel.initialFocusDone) {
            searchRequester.requestFocus()
            viewModel.initialFocusDone = true
        } else {
            val saved = viewModel.savedResultIndex
            if (saved in uiState.results.indices) {
                gridState.scrollToItem(saved)
                delay(120)
                resultRequesters[saved]?.requestFocus()
            } else {
                searchRequester.requestFocus()
            }
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
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = uiState.query,
                    onValueChange = { viewModel.onQueryChange(it) },
                    textStyle = OrionTextStyles.Body.copy(color = OrionColors.Text),
                    modifier = Modifier
                        .fillMaxWidth()
                        .preferredFocus(searchRequester),
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
                error = remoteState.error,
                onDismiss = { showRemoteInputModal = false },
                // P2-14：服务未开启时弹窗内一键开启，不用退出搜索去设置。
                onEnableRemoteInput = { viewModel.enableRemoteInput() },
            )
        }

        // P1-8：电视端屏上键盘（全键 + 9 键两档，最小可用实现）。
        if (showKeyboard) {
            Spacer(modifier = Modifier.height(16.dp))
            OnScreenKeyboard(
                query = uiState.query,
                onQueryChange = { viewModel.onQueryChange(it) },
                onSearch = { viewModel.searchNow(uiState.query) },
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
                        onClick = { viewModel.searchNow(suggestion) },
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
                            onClick = { viewModel.searchHistory(keyword) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // P2-27：删除按钮加大目标、与 chip 拉开距离（原“×”偏小且紧贴）。
                        SecondaryButton(text = "删除", onClick = { viewModel.deleteHistory(keyword) })
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
                        PrimaryButton(text = "换个关键词", onClick = { viewModel.clearQuery() })
                        if (error !is SearchError.Empty) {
                            SecondaryButton(
                                text = "重试",
                                onClick = { viewModel.searchNow(uiState.searchedQuery) },
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
                            onFocused = { viewModel.savedResultIndex = index },
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
    }
}
