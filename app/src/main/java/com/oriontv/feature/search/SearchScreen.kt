package com.oriontv.feature.search

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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.Text
import com.oriontv.core.focus.preferredFocus
import com.oriontv.navigation.OrionRoute
import com.oriontv.ui.components.CategoryChip
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.components.SecondaryButton
import com.oriontv.ui.components.VideoCard
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles

/**
 * 搜索页（ui-design.md §5.5）：
 * 顶部搜索框（遥控逐字输入；保留扫码远程输入入口，服务本身是阶段 4 的事，
 * 这里只留入口）+ 输入时下方联想 chips；
 * 结果网格；无结果提示 + “换个关键词”；搜索历史 chips（可单条删除）；
 * 搜索不清空已输内容，返回搜索页保留上次结果。
 */
@Composable
fun SearchScreen(
    onNavigate: (OrionRoute) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val searchRequester = remember { FocusRequester() }
    var showRemoteInputHint by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { searchRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(OrionDimens.ContentPadding),
    ) {
        // 顶部搜索框 + 扫码输入入口。
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
                text = "手机扫码输入",
                onClick = { showRemoteInputHint = true },
            )
        }
        if (showRemoteInputHint) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "远程输入服务即将开通，敬请期待",
                style = OrionTextStyles.Small,
                color = OrionColors.TextSecondary,
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
                        Spacer(modifier = Modifier.width(4.dp))
                        SecondaryButton(text = "×", onClick = { viewModel.deleteHistory(keyword) })
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
                // 无结果提示 + “换个关键词”；其他错误也给分型文案 + 重试。
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Text(text = uiState.error.message, style = OrionTextStyles.Body)
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PrimaryButton(text = "换个关键词", onClick = { viewModel.clearQuery() })
                        if (uiState.error !is SearchError.Empty) {
                            SecondaryButton(
                                text = "重试",
                                onClick = { viewModel.searchNow(uiState.searchedQuery) },
                            )
                        }
                        if (uiState.error is SearchError.NotConfigured) {
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
                    columns = GridCells.Fixed(6),
                    horizontalArrangement = Arrangement.spacedBy(OrionDimens.CardSpacing),
                    verticalArrangement = Arrangement.spacedBy(OrionDimens.RowSpacing),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(uiState.results) { result ->
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
                        )
                    }
                }
            }
        }
    }
}
