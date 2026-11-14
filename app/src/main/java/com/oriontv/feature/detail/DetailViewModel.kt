package com.oriontv.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oriontv.data.api.Favorite
import com.oriontv.data.api.SearchResult
import com.oriontv.data.repo.DetailRepository
import com.oriontv.data.repo.FavoritesRepository
import com.oriontv.data.repo.PlayRecordsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    fun init(source: String, id: String, title: String) {
        // 同一视频重复 init 不重新搜（返回后重组不丢状态）。
        if (currentKey == "$source+$id" && _uiState.value.results.isNotEmpty()) return
        currentKey = "$source+$id"
        currentTitle = title
        currentId = id
        searchJob?.cancel()
        _uiState.value = DetailUiState(isLoading = true, selectedEpisode = 0)
        searchJob = viewModelScope.launch {
            // 断点：有播放记录时默认选中记录里的集。
            val record = playRecordsRepository.getByKey(currentKey)
            val startEpisode = record?.index ?: 0
            _uiState.value = _uiState.value.copy(selectedEpisode = startEpisode)
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
            val favorite = Favorite(
                cover = current.poster,
                title = current.title,
                source_name = current.source_name,
                total_episodes = current.episodes.size,
                search_title = currentTitle,
                year = current.year,
            )
            val nowFavorite = favoritesRepository.toggle(currentKey, favorite)
            _uiState.value = _uiState.value.copy(isFavorite = nowFavorite)
        }
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
        else -> "加载失败：${e.message ?: "网络错误"}"
    }
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
)
