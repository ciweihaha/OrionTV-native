package com.oriontv.feature.detail

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** 详情 ViewModel 骨架。M1 补：多源聚合、源列表+集数角标、剧集列表、影片信息、收藏切换。 */
@HiltViewModel
class DetailViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()
}

data class DetailUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
)
