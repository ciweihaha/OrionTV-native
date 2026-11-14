package com.oriontv.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oriontv.data.api.MoonTvApi
import com.oriontv.data.api.SearchResult
import com.oriontv.data.api.UnauthorizedException
import com.oriontv.data.repo.SearchHistoryRepository
import com.oriontv.data.repo.SettingsRepository
import com.oriontv.feature.remoteinput.RemoteInputManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.SocketTimeoutException
import javax.inject.Inject

/**
 * 搜索 ViewModel（ui-design.md §5.5）：
 * 真实搜索（全源结果、进详情、错误分型提示）；防抖 300ms；
 * 同一词 5 分钟缓存；搜索历史 chips（可单条删除）；输入时联想 chips；
 * 返回搜索页保留上次结果与已输内容（ViewModel 随返回栈保留，不清空）。
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val moonTvApi: MoonTvApi,
    private val settingsRepository: SettingsRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val remoteInputManager: RemoteInputManager,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** 远程输入服务状态（搜索页扫码弹窗用）与配对成功事件。 */
    val remoteInputState: StateFlow<com.oriontv.feature.remoteinput.RemoteInputState> =
        remoteInputManager.state
    val remoteInputHandshakes: kotlinx.coroutines.flow.SharedFlow<Unit> =
        remoteInputManager.handshakes

    private val cache = SearchCache()
    private var searchJob: Job? = null
    private var generation = 0

    /** P1-4：首进才落搜索框锚点；返回时恢复 savedResultIndex。 */
    var initialFocusDone: Boolean = false
    var savedResultIndex: Int = -1

    init {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                history = searchHistoryRepository.getLatest20(),
            )
        }
        // 远程输入：手机扫码发来的文本直接填入搜索框并搜索（阶段 4 接通）。
        viewModelScope.launch {
            remoteInputManager.messages.collect { message ->
                searchNow(message)
            }
        }
    }

    /** 输入变化：防抖 300ms 后搜；清空输入时只清结果、不清历史。 */
    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(
            query = query,
            suggestions = suggest(query, _uiState.value.history),
        )
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _uiState.value = _uiState.value.copy(results = emptyList(), error = null, isLoading = false)
            return
        }
        val gen = ++generation
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            if (gen != generation) return@launch
            executeSearch(trimmed)
        }
    }

    /** 立即搜（点搜索/联想 chip 时）。 */
    fun searchNow(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        searchJob?.cancel()
        val gen = ++generation
        _uiState.value = _uiState.value.copy(query = trimmed)
        searchJob = viewModelScope.launch {
            if (gen != generation) return@launch
            executeSearch(trimmed)
        }
    }

    private suspend fun executeSearch(query: String) {
        cache.get(query)?.let { cached ->
            _uiState.value = _uiState.value.copy(
                results = cached,
                isLoading = false,
                error = if (cached.isEmpty()) SearchError.Empty else null,
                searchedQuery = query,
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        val baseUrl = settingsRepository.currentApiBaseUrl()
        if (baseUrl.isBlank()) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = SearchError.NotConfigured,
            )
            return
        }
        try {
            val results = moonTvApi.searchVideos(baseUrl, query)
            // P2-11：空结果不缓存（服务端刚上新内容时用户不再持续看到空结果）。
            if (shouldCacheSearchResults(results)) cache.put(query, results)
            // 无痕模式不写搜索历史（阶段 4）。
            if (!settingsRepository.incognitoMode.first()) {
                searchHistoryRepository.add(query)
            }
            _uiState.value = _uiState.value.copy(
                results = results,
                isLoading = false,
                error = if (results.isEmpty()) SearchError.Empty else null,
                searchedQuery = query,
                history = searchHistoryRepository.getLatest20(),
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = SearchError.classify(e),
            )
        }
    }

    /** 联想 chips：历史里含当前输入的词（最多 5 条）。 */
    private fun suggest(query: String, history: List<String>): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return history.filter { it.contains(trimmed, ignoreCase = true) }.take(5)
    }

    /** 历史 chip 点按：填回搜索框并立即搜。 */
    fun searchHistory(keyword: String) {
        searchNow(keyword)
    }

    /** 单条删除历史。 */
    fun deleteHistory(keyword: String) {
        viewModelScope.launch {
            searchHistoryRepository.delete(keyword)
            _uiState.value = _uiState.value.copy(
                history = searchHistoryRepository.getLatest20(),
                suggestions = suggest(_uiState.value.query, _uiState.value.history),
            )
        }
    }

    fun clearQuery() {
        searchJob?.cancel()
        generation++
        _uiState.value = _uiState.value.copy(
            query = "",
            results = emptyList(),
            error = null,
            isLoading = false,
            suggestions = emptyList(),
        )
    }

    /** P2-14：扫码弹窗内一键开启远程输入（存设置 + 拉起服务）。 */
    fun enableRemoteInput() {
        viewModelScope.launch {
            settingsRepository.setRemoteInputEnabled(true)
            remoteInputManager.start()
        }
    }

    companion object {
        /** 输入防抖 300ms（plan §3.2）。 */
        const val DEBOUNCE_MS = 300L
    }
}

/** P2-11：只缓存非空结果；空结果不进缓存（纯逻辑，可单测）。 */
fun shouldCacheSearchResults(results: List<SearchResult>): Boolean = results.isNotEmpty()

/** 搜索错误分型（ui-design.md §5.5：错误分型提示，不是一句“失败”）。 */
sealed interface SearchError {
    val message: String

    /** 未配服务器。 */
    data object NotConfigured : SearchError {
        override val message = "还没有配置服务器地址，先去设置里添加吧"
    }

    /** 无结果。 */
    data object Empty : SearchError {
        override val message = "没有找到相关内容，换个关键词试试"
    }

    /** 登录过期。 */
    data object Unauthorized : SearchError {
        override val message = "登录已过期，请重新登录"
    }

    /** 超时。 */
    data object Timeout : SearchError {
        override val message = "搜索超时，请检查网络后重试"
    }

    /** 网络错误。 */
    data class Network(override val message: String = "网络错误，请检查网络后重试") : SearchError

    /** 服务端错误。 */
    data class Server(override val message: String = "服务器错误，请稍后重试") : SearchError

    companion object {
        fun classify(e: Exception): SearchError = when (e) {
            is UnauthorizedException -> Unauthorized
            is SocketTimeoutException -> Timeout
            is IOException -> {
                if (e.message?.contains("HTTP 5") == true) {
                    Server("服务器错误（${e.message}），请稍后重试")
                } else {
                    Network()
                }
            }
            else -> Network("搜索失败：${e.message ?: "未知错误"}")
        }
    }
}

data class SearchUiState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val isLoading: Boolean = false,
    val error: SearchError? = null,
    val history: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    /** 最近一次真正执行搜索的词（用于无结果提示）。 */
    val searchedQuery: String = "",
)
