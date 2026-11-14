package com.oriontv.feature.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oriontv.data.api.MoonTvApi
import com.oriontv.data.api.SearchResponseException
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException
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
    private val savedStateHandle: SavedStateHandle,
    private val json: Json,
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
        // C4-P1-5：搜索切走再回不丢结果——query/结果/searchedQuery 进 SavedStateHandle
        //（导航 popUpTo(Home) 弹掉搜索页后 ViewModel 被清，只靠内存留不住）。
        // 结果以 JSON 存（SearchResult 是 @Serializable；体量约十几 KB，远小于存档上限）。
        restoreSearchState()
        viewModelScope.launch {
            // B4-P2-5：init 历史加载失败忽略（历史为空，不崩溃、不阻塞搜索页）。
            val history = try {
                searchHistoryRepository.getLatest20()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            _uiState.value = _uiState.value.copy(history = history)
        }
        // 远程输入：手机扫码发来的文本直接填入搜索框并搜索（阶段 4 接通）。
        // B-P2-22：_messages 是 replay=0 的 SharedFlow，进搜索页前发来的文本
        // 会被 tryEmit 丢弃；这里订阅前先读一次 state.lastMessage（5 分钟内
        // 才认，防旧消息误触发），丢掉的文本能找回来。
        val lastRemote = remoteInputManager.state.value
        if (lastRemote.lastMessage != null && isFreshRemoteMessage(lastRemote.lastMessageAt)) {
            searchNow(lastRemote.lastMessage)
        }
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
        saveSearchState()
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
            executeSearch(trimmed, gen)
        }
    }

    /** 立即搜（点搜索/联想 chip 时）。 */
    fun searchNow(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        searchJob?.cancel()
        val gen = ++generation
        _uiState.value = _uiState.value.copy(query = trimmed)
        saveSearchState()
        searchJob = viewModelScope.launch {
            if (gen != generation) return@launch
            executeSearch(trimmed, gen)
        }
    }

    /** C4-P1-5：把当前搜索态写进 SavedStateHandle（query/结果/searchedQuery）。 */
    private fun saveSearchState() {
        val state = _uiState.value
        savedStateHandle[SAVED_QUERY_KEY] = state.query
        savedStateHandle[SAVED_SEARCHED_QUERY_KEY] = state.searchedQuery
        savedStateHandle[SAVED_RESULTS_KEY] = runCatching {
            json.encodeToString(ListSerializer(SearchResult.serializer()), state.results)
        }.getOrDefault("[]")
    }

    /** C4-P1-5：从 SavedStateHandle 恢复搜索态（切走再回、进程重建都不丢结果）。 */
    private fun restoreSearchState() {
        val query = savedStateHandle.get<String>(SAVED_QUERY_KEY).orEmpty()
        val searchedQuery = savedStateHandle.get<String>(SAVED_SEARCHED_QUERY_KEY).orEmpty()
        val resultsJson = savedStateHandle.get<String>(SAVED_RESULTS_KEY) ?: return
        val results = runCatching {
            json.decodeFromString(ListSerializer(SearchResult.serializer()), resultsJson)
        }.getOrDefault(emptyList())
        if (query.isBlank() && results.isEmpty()) return
        _uiState.value = _uiState.value.copy(
            query = query,
            results = results,
            searchedQuery = searchedQuery,
            error = if (results.isEmpty() && searchedQuery.isNotBlank()) SearchError.Empty else null,
        )
    }

    private suspend fun executeSearch(query: String, gen: Int = generation) {
        // B-P1-6：进入搜索即记下本次词；失败分支也已赋值，重试按钮不再是死按钮。
        _uiState.value = _uiState.value.copy(searchedQuery = query)
        cache.get(query)?.let { cached ->
            _uiState.value = _uiState.value.copy(
                results = cached,
                isLoading = false,
                error = if (cached.isEmpty()) SearchError.Empty else null,
                searchedQuery = query,
            )
            saveSearchState()
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
            // B-P2-23①：网络返回后复核 generation；迟到结果不得覆盖更新的搜索。
            //（旧任务被取消时，OkHttp 同步调用不响应取消，返回后本应由 CE 拦截，
            // 但取消恰好发生在无挂起点的窗口时仍需这道防线。）
            if (gen != generation) return
            // P2-11：空结果不缓存（服务端刚上新内容时用户不再持续看到空结果）。
            if (shouldCacheSearchResults(results)) cache.put(query, results)
            // 无痕模式不写搜索历史（阶段 4）。
            if (!settingsRepository.incognitoMode.first()) {
                searchHistoryRepository.add(query)
            }
            // B4-P2-3：两次挂起调用（写历史、读最新 20 条）后复核代次——其间
            // 用户若已发起新搜索，旧结果不得覆盖新搜索的状态。
            if (gen != generation) return
            _uiState.value = _uiState.value.copy(
                results = results,
                isLoading = false,
                error = if (results.isEmpty()) SearchError.Empty else null,
                searchedQuery = query,
                history = searchHistoryRepository.getLatest20(),
            )
            saveSearchState()
        } catch (e: CancellationException) {
            // B-P1-9：取消必须原样抛出；否则旧搜索被取消后仍置错误态，覆盖新搜索。
            throw e
        } catch (e: Exception) {
            if (gen != generation) return
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
            // B-P2-7：先取新 history 再算联想；用删除前的旧 history 算，
            // 删后联想 chips 仍含已删词，直到下次输入才刷新。
            val newHistory = searchHistoryRepository.getLatest20()
            _uiState.value = _uiState.value.copy(
                history = newHistory,
                suggestions = suggest(_uiState.value.query, newHistory),
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
        saveSearchState()
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

        /** C4-P1-5：SavedStateHandle 键（搜索切走再回不丢结果）。 */
        const val SAVED_QUERY_KEY = "search_query"
        const val SAVED_SEARCHED_QUERY_KEY = "search_searched_query"
        const val SAVED_RESULTS_KEY = "search_results_json"
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

    /** 服务端返回的搜索 JSON 已损坏（与“无结果”区分）。 */
    data object DataError : SearchError {
        override val message = "搜索数据异常，请重试"
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
            is SearchResponseException -> DataError
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

/**
 * 第 3 轮收尾 B-P2-22：远程文本是否新鲜（[windowMs] 内才认，防旧消息误触发）。
 * 纯逻辑，可单测。
 */
fun isFreshRemoteMessage(
    lastMessageAt: Long?,
    nowMs: Long = System.currentTimeMillis(),
    windowMs: Long = REMOTE_MESSAGE_FRESH_MS,
): Boolean {
    if (lastMessageAt == null) return false
    val ageMs = nowMs - lastMessageAt
    return ageMs in 0..windowMs
}

/** 远程文本新鲜窗口：5 分钟（与配对码有效期同长）。 */
const val REMOTE_MESSAGE_FRESH_MS = 5 * 60 * 1000L

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
