package com.oriontv.feature.home

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** 首页 ViewModel 骨架。M1 补：7 分类 + 子标签 + 5 列网格 + 最近播放 + 无限滚动 + LRU 缓存 + CMS 回落豆瓣。 */
@HiltViewModel
class HomeViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
}

data class HomeUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
)
