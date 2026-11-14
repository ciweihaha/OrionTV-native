package com.oriontv.feature.live

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** 直播 ViewModel 骨架。M2 补：频道分组与多线路、换台信息条、频道面板、EPG 弹窗、坏源自动换线路/跳台。 */
@HiltViewModel
class LiveViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(LiveUiState())
    val uiState: StateFlow<LiveUiState> = _uiState.asStateFlow()
}

data class LiveUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
)
