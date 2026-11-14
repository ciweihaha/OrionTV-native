package com.oriontv.feature.favorites

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** 收藏 ViewModel 骨架。M1 补：收藏列表（倒序）、点按进详情、回页刷新。 */
@HiltViewModel
class FavoritesViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()
}

data class FavoritesUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
)
