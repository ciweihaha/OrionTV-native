package com.oriontv.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oriontv.data.api.Favorite
import com.oriontv.data.api.MoonTvApi
import com.oriontv.data.api.SearchResult
import com.oriontv.data.repo.DetailRepository
import com.oriontv.data.repo.DetailSearchLogic
import com.oriontv.data.repo.FavoritesRepository
import com.oriontv.data.repo.PlayRecordsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject

/**
 * 详情 ViewModel（ui-design.md §5.2）：
 * 多源聚合（首选源先行、全量后台合并）、源切换 chip、选集、收藏切换、
 * 断点续播入口（播放记录的集号/进度）、失败源标灰。
 * 换源不丢集数：集号存在 state 里，与当前源无关。
 */
@HiltViewModel
class DetailViewModel @Inject constructor(
    private val detailRepository: DetailRepository,
    private val favoritesRepository: FavoritesRepository,
    private val playRecordsRepository: PlayRecordsRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DetailUiState(isLoading = true))
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var currentKey: String = ""
    private var currentTitle: String = ""
    private var currentId: String = ""

    /** B4-P2-2：轻量代次——init 时 +1，迟到的搜索回调/收藏读取复核后才写回。 */
    private var generation = 0

    /**
     * C5-P2-4：P1-4/P2-2 的焦点锚点原来是普通变量。详情页被 popUpTo 弹掉
     * 后 ViewModel 被清，返回重建时 focusAnchored 回 false，会抢焦点到
     * “播放”按钮（首页/收藏页同款问题，见 HomeViewModel）。改成
     * SavedStateHandle 承载，照 FavoritesViewModel 的做法。
     */
    var focusAnchored: Boolean
        get() = savedStateHandle[SAVED_FOCUS_ANCHORED] ?: false
        set(value) {
            savedStateHandle[SAVED_FOCUS_ANCHORED] = value
        }

    companion object {
        /** SavedStateHandle 键：是否已落过“播放”按钮焦点锚点。 */
        const val SAVED_FOCUS_ANCHORED = "detail_focus_anchored"
    }

    fun init(source: String, id: String, title: String) {
        // 同一视频重复 init 不重新搜（返回后重组不丢状态）。
        // B4-P1-1：统一键——init/toggleFavorite/PlayViewModel 只用 recordKey；
        // id 为空时用标题兜底，不再所有空 id 项共用 `source+` 空键。
        val key = MoonTvApi.recordKey(source, id, title)
        if (currentKey == key && _uiState.value.results.isNotEmpty()) return
        currentKey = key
        currentTitle = title
        currentId = id
        val gen = ++generation
        searchJob?.cancel()
        _uiState.value = DetailUiState(isLoading = true, selectedEpisode = 0)
        searchJob = viewModelScope.launch {
            // 断点：有播放记录时默认选中记录里的集，并记下断点秒数（P1-2）。
            // B4-P2-5：仓库调用失败不崩溃（读不到断点当无断点，从 0 集播）。
            val record = try {
                playRecordsRepository.getByKey(currentKey)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val startEpisode = record?.index ?: 0
            _uiState.value = _uiState.value.copy(
                selectedEpisode = startEpisode,
                // B-P2-5：断点集所属分组也一并置好，否则分组视图仍停在 1-20 集，
                // 断点集（第 25 集起）不可见、焦点也不在它上面。
                selectedGroup = DetailSearchLogic.groupIndexForEpisode(startEpisode),
                resumeEpisode = record?.index,
                resumePositionSeconds = record?.play_time,
            )
            // douban 项没有真实源，不走单源搜索，直接全量按标题搜。
            val preferredSource = if (source == "douban") null else source.ifBlank { null }
            try {
                detailRepository.searchDetail(title, preferredSource, id.ifBlank { null }) { results, allLoaded ->
                    // B4-P2-2：回调里复核代次——旧搜索迟到返回时不再覆盖新搜索的状态。
                    if (gen != generation) return@searchDetail
                    val state = _uiState.value
                    val current = state.current
                        ?: results.firstOrNull()
                        ?: state.current
                    _uiState.value = state.copy(
                        isLoading = false,
                        results = results,
                        sources = results.map { it.source to it.source_name },
                        current = current,
                        allSourcesLoaded = allLoaded,
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                // B-P1-9：取消必须原样抛出；否则旧搜索任务被取消后仍会把错误态
                // 写回，覆盖新搜索的状态（B-P2-23② 亦由此解决）。
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = friendlyError(e),
                )
            }
            // 收藏状态（异步查，不阻塞详情展示）。
            // B4-P2-2：读取前复核代次；B4-P2-5：查不到当未收藏，不崩溃。
            if (gen != generation) return@launch
            val favorite = try {
                favoritesRepository.isFavorite(currentKey)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            _uiState.value = _uiState.value.copy(isFavorite = favorite)
        }
    }

    /** 切换源：保留已选集数（换源不丢集数）；新源集数不足时钳到最后一集。 */
    fun selectSource(source: String) {
        val state = _uiState.value
        val next = state.results.firstOrNull { it.source == source } ?: return
        val episode = state.selectedEpisode.coerceAtMost((next.episodes.size - 1).coerceAtLeast(0))
        _uiState.value = state.copy(current = next, selectedEpisode = episode)
    }

    fun selectEpisode(index: Int) {
        _uiState.value = _uiState.value.copy(selectedEpisode = index)
    }

    fun selectGroup(groupIndex: Int) {
        _uiState.value = _uiState.value.copy(selectedGroup = groupIndex)
    }

    fun toggleFavorite() {
        val state = _uiState.value
        val current = state.current ?: return
        viewModelScope.launch {
            try {
                val favorite = Favorite(
                    cover = current.poster,
                    title = current.title,
                    source_name = current.source_name,
                    total_episodes = current.episodes.size,
                    search_title = currentTitle,
                    year = current.year,
                )
                // B4-P1-1：收藏键用 init 的 currentKey（进入时的源/标题），不
                // 再按当前结果重算——否则切源后收藏存到另一把键下，init 查
                // isFavorite 时查不到，收藏态永远对不上（旧 P2-16 做法已废弃）。
                val key = currentKey
                // B-P0-1：服务端写失败时 toggle 抛错（见 FavoritesRepository），
                // 这里接住给失败提示、不崩溃，收藏态保持原值。
                val nowFavorite = favoritesRepository.toggle(key, favorite)
                _uiState.value = _uiState.value.copy(isFavorite = nowFavorite, favoriteError = null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(favoriteError = "收藏失败，请检查网络后重试")
            }
        }
    }

    fun consumeFavoriteError() {
        _uiState.update { it.copy(favoriteError = null) }
    }

    /** 播放失败时标记当前源失败（供播放页自动换源用；详情页内标灰）。 */
    fun markSourceAsFailed(source: String) {
        val state = _uiState.value
        if (source in state.failedSources) return
        _uiState.value = state.copy(failedSources = state.failedSources + source)
    }

    private fun friendlyError(e: Exception): String = when {
        e.message?.contains("未配置服务器地址") == true -> "还没有配置源地址，先去设置里添加吧"
        e.message?.contains("登录已过期") == true -> "登录已过期，请重新登录"
        // P2-17：不把原始异常拼给用户，固定文案。
        else -> "加载失败，请检查网络后重试"
    }
}

/**
 * P1-2：详情页“播放”应带的位置（毫秒）。
 * 只有当前选中集等于断点记录集时才续播；换集后断点失效，从 0 开始。
 * 纯逻辑，可单测。
 */
fun resumePositionMs(
    selectedEpisode: Int,
    resumeEpisode: Int?,
    resumePositionSeconds: Long?,
): Long {
    if (resumeEpisode == null || resumePositionSeconds == null) return 0L
    if (selectedEpisode != resumeEpisode) return 0L
    return (resumePositionSeconds * 1000).coerceAtLeast(0L)
}

data class DetailUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val results: List<SearchResult> = emptyList(),
    /** (source, source_name) 列表，与 results 同序。 */
    val sources: List<Pair<String, String>> = emptyList(),
    val current: SearchResult? = null,
    val allSourcesLoaded: Boolean = false,
    val failedSources: Set<String> = emptySet(),
    val selectedEpisode: Int = 0,
    val selectedGroup: Int = 0,
    val isFavorite: Boolean = false,
    /** B-P0-1：收藏切换失败时的一次性提示（2 秒 Toast，UI 消费后清掉）。 */
    val favoriteError: String? = null,
    /** P1-2：断点记录的集号（null = 无断点）；只有选中它时“播放”才带位置。 */
    val resumeEpisode: Int? = null,
    /** P1-2：断点记录的已播秒数（null = 无断点）。 */
    val resumePositionSeconds: Long? = null,
)
