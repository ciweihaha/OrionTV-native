package com.oriontv.feature.favorites

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oriontv.data.api.Favorite
import com.oriontv.data.repo.FavoritesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject

/** 收藏页 ViewModel（ui-design.md §5.6）：网格倒序（新收藏在前），回页即刷新。 */
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(FavoritesUiState(isLoading = true))
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()

    /**
     * 第 5 轮：P1-3/P1-4 首进落默认锚点、返回恢复上次卡片——这两个值原来是
     * ViewModel 里的普通变量。导航轨切走（popUpTo(Home) 弹掉收藏页）后
     * ViewModel 被清，只靠内存留不住；再切回来新建的 ViewModel 从头开始，
     * 焦点回到首张卡片、网格回到顶部（第 4 轮 C4-P1-5 搜索页同款问题）。
     * 改成 SavedStateHandle 承载：导航保存的返回栈状态里跟着走，
     * 切走再回、进程重建都不丢；收藏页本身不用改（同名属性照常读写）。
     * Hilt 给 @HiltViewModel 自动注入 SavedStateHandle，第 4 轮搜索页已验证过这条路。
     */
    var initialFocusDone: Boolean
        get() = savedStateHandle[SAVED_INITIAL_FOCUS_DONE] ?: false
        set(value) {
            savedStateHandle[SAVED_INITIAL_FOCUS_DONE] = value
        }

    var savedFocusIndex: Int
        get() = savedStateHandle[SAVED_FOCUS_INDEX] ?: -1
        set(value) {
            savedStateHandle[SAVED_FOCUS_INDEX] = value
        }

    init {
        refresh()
    }

    /** 每次回页都刷新（详情页内取消收藏后返回即不残留）。 */
    fun refresh() {
        viewModelScope.launch {
            // B4-P2-5：仓库调用失败保留旧 items 并置 loadFailed，不崩溃、不闪空。
            val items = try {
                favoritesRepository.getAllWithKeys()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(loadFailed = true)
                return@launch
            }
            // B-P2-20：服务端拉取失败时 items 是上次成功结果，置标记让 UI 提示。
            _uiState.value = FavoritesUiState(
                items = items,
                loadFailed = favoritesRepository.loadFailed.value,
            )
        }
    }

    companion object {
        /** SavedStateHandle 键：是否已落过默认锚点。 */
        const val SAVED_INITIAL_FOCUS_DONE = "favorites_initial_focus_done"

        /** SavedStateHandle 键：上次所在的卡片下标（-1 = 未记录）。 */
        const val SAVED_FOCUS_INDEX = "favorites_saved_focus_index"
    }
}

data class FavoritesUiState(
    val isLoading: Boolean = false,
    /** (记录键 `${source}+${id}`, 收藏)；最新在前。 */
    val items: List<Pair<String, Favorite>> = emptyList(),
    /** B-P2-20：服务端拉取失败（展示的是上次成功结果）。 */
    val loadFailed: Boolean = false,
)
