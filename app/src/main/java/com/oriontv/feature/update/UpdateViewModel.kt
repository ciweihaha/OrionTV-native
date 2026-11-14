package com.oriontv.feature.update

import androidx.lifecycle.ViewModel
import com.oriontv.core.util.VersionUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * 更新 ViewModel 骨架。M3 补：冷启动 12 小时静默检查 + 手动检查 + 下载
 *（上限 200MB、失败重试 3 次、退避 3s × 次数）+ SHA256 校验（支持 `hash  filename`
 * 或纯 hash 两种格式）+ 系统安装器安装 + 跳过版本；旧 APK 只保留最新 2 个。
 */
@HiltViewModel
class UpdateViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    /** 当前版本是否低于目标版本。 */
    fun needsUpdate(currentVersion: String, latestVersion: String): Boolean =
        VersionUtils.isOlderThan(currentVersion, latestVersion)
}

data class UpdateUiState(
    val isChecking: Boolean = false,
    val latestVersion: String? = null,
    val error: String? = null,
)
