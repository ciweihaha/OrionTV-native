package com.oriontv.feature.detail

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
) : ViewModel() {
    private val _uiState = MutableStateFlow(DetailUiState(isLoading = true))
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var currentKey: String = ""
    private var currentTitle: String = ""
    private var currentId: String = ""

    /** P1-4/P2-2：首进后才置 true；换源、返回重组都不再抢焦点到“播放”按钮。 */
    var focusAnchored: Boolean = false

    fun init(source: String, id: String, title: String) {
        // 同一视频重复 init 不重新搜（返回后重组不丢状态）。
        val key = MoonTvApi.generateKey(source, id)
        if (currentKey == key && _uiState.value.results.isNotEmpty()) return
        currentKey = key
        currentTitle = title
        currentId = id
        searchJob?.cancel()
        _uiState.value = DetailUiState(isLoading = true, selectedEpisode = 0)
        searchJob = viewModelScope.launch {
            // 断点：有播放记录时默认选中记录里的集，并记下断点秒数（P1-2）。
            val record = playRecordsRepository.getByKey(currentKey)
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
            val favorite = favoritesRepository.isFavorite(currentKey)
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
                // P2-16：收藏键以当前源为准（切源后再收藏，不再存进入时的源）。
                val key = MoonTvApi.generateKey(current.source, current.id.orEmpty())
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
