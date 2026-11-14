package com.oriontv.feature.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oriontv.data.api.Favorite
import com.oriontv.data.repo.FavoritesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 收藏页 ViewModel（ui-design.md §5.6）：网格倒序（新收藏在前），回页即刷新。 */
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(FavoritesUiState(isLoading = true))
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()

    /** P1-3/P1-4：首进才落默认锚点；返回时恢复 savedFocusIndex。 */
    var initialFocusDone: Boolean = false
    var savedFocusIndex: Int = -1

    init {
        refresh()
    }

    /** 每次回页都刷新（详情页内取消收藏后返回即不残留）。 */
    fun refresh() {
        viewModelScope.launch {
            val items = favoritesRepository.getAllWithKeys()
            // B-P2-20：服务端拉取失败时 items 是上次成功结果，置标记让 UI 提示。
            _uiState.value = FavoritesUiState(
                items = items,
                loadFailed = favoritesRepository.loadFailed.value,
            )
        }
    }
}

data class FavoritesUiState(
    val isLoading: Boolean = false,
    /** (记录键 `${source}+${id}`, 收藏)；最新在前。 */
    val items: List<Pair<String, Favorite>> = emptyList(),
    /** B-P2-20：服务端拉取失败（展示的是上次成功结果）。 */
    val loadFailed: Boolean = false,
)
