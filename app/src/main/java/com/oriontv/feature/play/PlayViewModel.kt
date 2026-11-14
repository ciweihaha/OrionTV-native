package com.oriontv.feature.play

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** 播放 ViewModel 骨架。M1 补：控制条、选集/换源/倍速抽屉、断点续播、自动换源、下一集预告、默认片头跳过。 */
@HiltViewModel
class PlayViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(PlayUiState())
    val uiState: StateFlow<PlayUiState> = _uiState.asStateFlow()
}

data class PlayUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
)
