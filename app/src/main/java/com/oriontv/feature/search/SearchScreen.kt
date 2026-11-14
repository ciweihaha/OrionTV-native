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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.Text
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
 * 顶部搜索框 + 远程输入（手机扫码）入口；输入时出联想 chip；
 * 结果网格；无结果提示 + “换个关键词”；搜索历史 chip，可逐个删除；
 * 搜索不清空输入，返回保留上次结果。
 * M1 补：防抖 300ms、同一词 5 分钟缓存、真实搜索接口、历史持久化。
 */
@Composable
fun SearchScreen(
    onNavigate: (OrionRoute) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    var query by remember { mutableStateOf("") }
    val history = remember { mutableStateListOf("示例剧集", "新闻联播") }
    val suggestions = if (query.isNotBlank()) {
        listOf("$query 相关 1", "$query 相关 2", "$query 相关 3")
    } else {
        emptyList()
    }
    // 占位结果：有输入时出 8 条，否则为空。
    val results = if (query.isNotBlank()) (1..8).toList() else emptyList()

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
                    value = query,
                    onValueChange = { query = it },
                    textStyle = OrionTextStyles.Body.copy(color = OrionColors.Text),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { innerTextField ->
                        if (query.isEmpty()) {
                            Text(text = "输入关键词搜索", style = OrionTextStyles.Body, color = OrionColors.TextWeak)
                        }
                        innerTextField()
                    },
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            SecondaryButton(text = "手机扫码输入", onClick = { /* M1：远程输入二维码 */ })
        }

        // 联想 chip（输入时出现）。
        if (suggestions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(OrionDimens.ChipSpacing)) {
                items(suggestions) { suggestion ->
                    CategoryChip(text = suggestion, selected = false, onClick = { query = suggestion })
                }
            }
        }

        // 搜索历史 chip（可逐个删除）。
        if (query.isBlank() && history.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "搜索历史", style = OrionTextStyles.SectionTitle)
            Spacer(modifier = Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(OrionDimens.ChipSpacing)) {
                items(history.size) { index ->
                    CategoryChip(
                        text = "${history[index]} ×",
                        selected = false,
                        onClick = { history.removeAt(index) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        if (query.isNotBlank() && results.isEmpty()) {
            // 无结果提示 + “换个关键词”。
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                Text(text = "没有找到相关内容，换个关键词试试", style = OrionTextStyles.Body)
                Spacer(modifier = Modifier.height(24.dp))
                PrimaryButton(text = "换个关键词", onClick = { query = "" })
            }
        } else if (results.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                horizontalArrangement = Arrangement.spacedBy(OrionDimens.CardSpacing),
                verticalArrangement = Arrangement.spacedBy(OrionDimens.RowSpacing),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(results) { i ->
                    VideoCard(
                        title = "搜索结果 $i",
                        posterUrl = "",
                        meta = "2026",
                        onClick = { onNavigate(OrionRoute.Detail(source = "agg", id = "search$i")) },
                    )
                }
            }
        }
    }
}
