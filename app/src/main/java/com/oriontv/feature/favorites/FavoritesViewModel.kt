package com.oriontv.feature.favorites

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oriontv.core.util.InFlightGuard
import com.oriontv.data.api.Favorite
import com.oriontv.data.api.PlayRecord
import com.oriontv.data.repo.FavoritesRepository
import com.oriontv.data.repo.PlayRecordsRepository
import com.oriontv.ui.components.ToastEvent
import com.oriontv.ui.components.nextToastEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject

/** 收藏页 ViewModel（ui-design.md §5.6）：网格倒序（新收藏在前），回页即刷新。 */
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    private val playRecordsRepository: PlayRecordsRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(FavoritesUiState(isLoading = true))
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()

    /** 第 6 轮 P1-2：页内删除防连按守卫（按记录键区分，不同卡片互不阻塞）。 */
    private val removeGuard = InFlightGuard()

    /**
     * B12-P2-2：refresh() 代次——与 HomeViewModel.refresh() 同款；两次 refresh
     * 并发时旧的慢结果不再覆盖新的列表（B11-P2-2 同款，收藏页漏了）。
     */
    private var refreshGeneration = 0

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

    /**
     * P2-9（第 5 轮）：返回焦点恢复按记录键（source+id）锚定，不按 index——
     * 刷新/删除后 index 对应的已是另一张卡。键同样由 SavedStateHandle 承载
     * （不只用内存变量，否则导航轨切走后 ViewModel 被清、键就丢了）。
     */
    var savedFocusKey: String?
        get() = savedStateHandle[SAVED_FOCUS_KEY]
        set(value) {
            savedStateHandle[SAVED_FOCUS_KEY] = value
        }

    init {
        refresh()
    }

    /** 每次回页都刷新（详情页内取消收藏后返回即不残留）。 */
    fun refresh() {
        // B12-P2-2：起刷时快照代次，迟到结果丢弃——两次 refresh 并发时旧的
        // 慢结果不再覆盖新列表（与 HomeViewModel.refresh() 同款守卫）。
        val gen = ++refreshGeneration
        viewModelScope.launch {
            // B4-P2-5：仓库调用失败保留旧 items 并置 loadFailed，不崩溃、不闪空。
            val items = try {
                favoritesRepository.getAllWithKeys()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (gen != refreshGeneration) return@launch
                // B5-P2-2：首载失败也要清 isLoading，否则加载圈一直转、
                // loadFailed 提示出不来；旧 items 保留（初始即空表）。
                _uiState.value = _uiState.value.copy(loadFailed = true, isLoading = false)
                return@launch
            }
            if (gen != refreshGeneration) return@launch
            // P2-3：断点续播入口——一并取播放记录（无记录的收藏没有“续播”按钮）。
            // 记录拉取失败只降级为空表，不让收藏列表跟着失败（记录层本身对
            // 服务端失败已有 lastGoodRecords 兜底，这里只防本地异常）。
            val records = try {
                playRecordsRepository.getAllWithKeys().toMap()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyMap()
            }
            if (gen != refreshGeneration) return@launch
            // B-P2-20：服务端拉取失败时 items 是上次成功结果，置标记让 UI 提示。
            // 第 6 轮 P1-2/P2-7：refresh 整包替换 uiState，删除进行中的键集合与
            // 焦点恢复请求计数要带上，否则刷新一次就把守卫/落点请求清掉。
            // 第 7 轮 P2-3：同理带上 favoriteError——删失败提示的 2 秒 Toast
            // 窗口内一次刷新（例如后台回前台的 ON_RESUME 重进）不能把它静默清掉。
            _uiState.value = refreshedFavoritesState(
                current = _uiState.value,
                items = items,
                playRecords = records,
                loadFailed = favoritesRepository.loadFailed.value,
            )
        }
    }

    /** 页内删除（P2-3）：先确认仍是收藏再 toggle，避免已删的情况下
     * toggle 反而重新收藏；删前算好焦点落点（下一张卡，末尾则上一张），
     * 删完刷新后焦点落在那张卡上（见 FavoritesScreen 的恢复逻辑）。
     * 第 6 轮 P1-2：删除防连按——两次快速点按时两个 isFavorite 预检都可能
     * 在第一次 toggle 落地前返回 true，随后两次 toggle 串行执行：第一次删、
     * 第二次读到“不存在”又加回，条目幽灵复现。守卫在发起前同步按记录键拿锁，
     * 进行中第二次点按直接丢弃；按钮同时按 removingKeys 禁用（见 FavoritesScreen）。
     * 只改调用方守卫，不碰 Repositories.kt 的 toggle() 本体（数据层 D6-P1-2 修）。 */
    fun removeFavorite(key: String, favorite: Favorite) {
        if (!removeGuard.tryAcquire(key)) return
        _uiState.update { it.copy(removingKeys = it.removingKeys + key) }
        viewModelScope.launch {
            try {
                // B8-P1-2：删前存在检查用严格版——服务端网络失败时抛错走下面
                // 的 catch 弹提示；宽容版 isFavorite 失败返回 false，会让
                // 删除静默 return、无事发生、无任何提示。
                if (!favoritesRepository.isFavoriteStrict(key)) {
                    // B12-P2-8：严格检查返回 false = 服务端已无此收藏（详情页
                    // 里删过、或别端删过），本地列表仍残留旧项；旧实现直接
                    // return@launch、静默无事发生、无刷新。给一次提示并刷新
                    // 列表，与服务端实态对齐（finally 仍会释放守卫）。
                    _uiState.update { it.copy(favoriteError = nextToastEvent("该收藏已不存在，已刷新列表")) }
                    refresh()
                    return@launch
                }
                // 第 7 轮 P2-3：焦点落点与恢复请求只在 toggle 成功后算——
                // 旧实现在 toggle 之前就把 savedFocusKey 换掉、focusRestoreRequest+1，
                // toggle 一抛错（B8-P1-2 的严格存在检查也会抛错进下面 catch），
                // 计数已 +1 收不回，下次 items 一变就把焦点拉到下一张卡片，
                // 用户明明没删成功却被挪了焦点。
                favoritesRepository.toggle(key, favorite)
                val items = _uiState.value.items
                savedFocusKey = nextSavedFocusKey(items, key)
                // 第 6 轮 P2-7：删后落点要恢复焦点——refresh 换掉 items 后
                // 恢复 effect 须跑一次（平时例行刷新不跑）；计数 +1 通知界面。
                _uiState.update { it.copy(focusRestoreRequest = it.focusRestoreRequest + 1) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // B7-P0-1：服务端模式网络失败时 toggle 抛 IOException（第 3 轮
                // B-P0-1 只修了详情页一侧，收藏页漏了）——接住给失败提示，
                // 不刷新列表（删失败的项仍在，不闪没），不崩溃。
                _uiState.update { it.copy(favoriteError = nextToastEvent("取消收藏失败，请检查网络后重试")) }
            } finally {
                removeGuard.release(key)
                _uiState.update { it.copy(removingKeys = it.removingKeys - key) }
            }
        }
    }

    fun consumeFavoriteError() {
        _uiState.update { it.copy(favoriteError = null) }
    }

    companion object {
        /** SavedStateHandle 键：是否已落过默认锚点。 */
        const val SAVED_INITIAL_FOCUS_DONE = "favorites_initial_focus_done"

        /** SavedStateHandle 键：上次所在的卡片记录键（P2-9 按键锚定，不按 index）。 */
        const val SAVED_FOCUS_KEY = "favorites_saved_focus_key"
    }
}

data class FavoritesUiState(
    val isLoading: Boolean = false,
    /** (记录键 `${source}+${id}`, 收藏)；最新在前。 */
    val items: List<Pair<String, Favorite>> = emptyList(),
    /** B-P2-20：服务端拉取失败（展示的是上次成功结果）。 */
    val loadFailed: Boolean = false,
    /** 断点续播：记录键 → 播放记录（无记录的收藏没有“续播”入口）。 */
    val playRecords: Map<String, PlayRecord> = emptyMap(),
    /** B7-P0-1：删收藏失败时的一次性提示（2 秒 Toast，UI 消费后清掉；与详情页同款）。 */
    val favoriteError: ToastEvent? = null,
    /** 第 6 轮 P1-2：删除进行中的记录键集合（进行中该卡的“删除”按钮禁用）。 */
    val removingKeys: Set<String> = emptySet(),
    /** 第 6 轮 P2-7：页内删除请求焦点恢复的计数（删后落点恢复跑一次；例行刷新不跑）。 */
    val focusRestoreRequest: Int = 0,
)

/**
 * 第 7 轮 P2-3：删后焦点落点键（纯函数，可单测）——被删卡片的下一张，
 * 末尾则上一张；只有一张或键不在列表里时 null（回首张卡片）。
 * 入参是删除前的列表（toggle 只改仓库，界面列表要等 refresh 才换）。
 */
internal fun nextSavedFocusKey(
    items: List<Pair<String, Favorite>>,
    deletedKey: String,
): String? {
    val idx = items.indexOfFirst { it.first == deletedKey }
    return when {
        idx < 0 -> null
        idx + 1 < items.size -> items[idx + 1].first
        idx - 1 >= 0 -> items[idx - 1].first
        else -> null
    }
}

/**
 * 第 7 轮 P2-3：refresh 整包重建 uiState（纯函数，可单测）——新拉到的
 * 列表/记录/失败标记替换，删除进行中的键集合、焦点恢复请求计数与删失败
 * 提示（favoriteError）保留：刷新一次不能把守卫、落点请求或 2 秒 Toast
 * 窗口内的失败提示静默清掉。
 */
internal fun refreshedFavoritesState(
    current: FavoritesUiState,
    items: List<Pair<String, Favorite>>,
    playRecords: Map<String, PlayRecord>,
    loadFailed: Boolean,
): FavoritesUiState = FavoritesUiState(
    items = items,
    playRecords = playRecords,
    loadFailed = loadFailed,
    favoriteError = current.favoriteError,
    removingKeys = current.removingKeys,
    focusRestoreRequest = current.focusRestoreRequest,
)
