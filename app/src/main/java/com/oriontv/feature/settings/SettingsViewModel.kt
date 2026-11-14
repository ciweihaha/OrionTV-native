package com.oriontv.feature.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * 设置 ViewModel 骨架。M3 补：5 个 section（远程输入 / API 地址 / 直播源地址 /
 * 播放设置 / 应用更新）+ 手工焦点机（currentFocusIndex 状态机，只响应松键，
 * 上下键顺序移动，越过末尾聚焦保存按钮）+ 保存按钮门控 + 换地址清凭据。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
}

data class SettingsUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
)
