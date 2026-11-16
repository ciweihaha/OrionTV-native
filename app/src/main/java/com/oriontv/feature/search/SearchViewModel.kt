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
import com.oriontv.ui.components.ToastEvent
import com.oriontv.ui.components.nextToastEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
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
    /** Jewel 第 9 轮 P2-6：iQIYI 联想用的 OkHttp 客户端（AppModule 已提供；联想
     * 在 feature 层直接调，不碰数据层——只用短超时派生客户端发一条 GET）。 */
    private val okHttpClient: OkHttpClient,
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
    /** Jewel 第 9 轮 P2-6：iQIYI 联想任务（拼音输入时防抖后发一条联想请求；新输入
     * 到达即取消上一条，迟到响应凭代次丢弃，不覆盖新输入的联想）。 */
    private var suggestJob: Job? = null
    /** 联想短超时客户端（懒创建，复用注入客户端的连接池，只收紧超时）。 */
    private val suggestClient: OkHttpClient by lazy { suggestTimeoutClient(okHttpClient) }
    // B7-P2-7：代次在主线程写、搜索协程读；与 DetailViewModel 同款，@Volatile
    // 保跨线程可见性（本类读写目前都在主线程，防御性加固）。
    @Volatile
    private var generation = 0

    /**
     * B17-P2-2：deleteHistory 代次——与 FavoritesViewModel.refresh() 的
     * B12-P2-2 同款；快速连删两条历史时，先删的慢结果不再覆盖后删的新列表
     *（delete 后 getLatest20() 不持锁，迟到旧结果最后写态会让已删词幽灵复现）。
     */
    private var deleteHistoryGeneration = 0

    /**
     * P1-4：首进才落搜索框锚点；返回时恢复 savedResultKey。
     * C6-P1-2：原来是 plain var——导航轨切走后 ViewModel 被清、锚点就丢了，
     * 回来又落搜索框；改 SavedStateHandle（与首页/详情/收藏同款），
     * 进程重建也不丢。
     */
    var initialFocusDone: Boolean
        get() = savedStateHandle[SAVED_INITIAL_FOCUS_DONE] ?: false
        set(value) {
            savedStateHandle[SAVED_INITIAL_FOCUS_DONE] = value
        }

    /** P2-9（第 5 轮）：返回焦点恢复按条目键（source+id）锚定，不按 index——
     * 搜索结果刷新后 index 对应的已是另一张卡。
     * C6-P1-2：同上，plain var 改 SavedStateHandle。 */
    var savedResultKey: String?
        get() = savedStateHandle[SAVED_RESULT_KEY]
        set(value) {
            savedStateHandle[SAVED_RESULT_KEY] = value
        }

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
            remoteInputManager.messages.collect {
                // B9-P2-5：流只作唤醒信号，文本以 state 的最新一条为准——连续两条
                // 时先到的可能还在缓冲里，后到的已进 state；5 分钟外的旧文本直接
                // 丢弃，不让无订阅者时滞留的旧消息在进搜索页后误触发。
                val state = remoteInputManager.state.value
                val fresh = resolveRemoteMessageToSearch(state.lastMessage, state.lastMessageAt)
                    ?: return@collect
                searchNow(fresh)
            }
        }
    }

    /** 输入变化：防抖 300ms 后搜；清空输入时只清结果、不清历史。 */
    fun onQueryChange(query: String) {
        val historySuggestions = suggest(query, _uiState.value.history)
        // B34-P2-1：输入新词先清旧错误再存档（与空串分支 :149 同款顺序）——
        // 之前先存档：error 要到 300ms 防抖后 executeSearch 开始时才清，
        // 300ms 内切走会把新 query 与旧 error（种类/文案）、旧 results、
        // 旧 searchedQuery 一并存下，回来后搜索框是新词、错误态却是旧词
        // 的失败，点“重试”实际重搜旧词（重试按钮按 searchedQuery 搜）。
        // 只清 error：错误分支（SearchScreen :482，优先于结果区、内含按
        // searchedQuery 的重试按钮）随之消失，错配的失败与错词重试即消除；
        // 结果区旧结果不碰——它是输入中的既有暂留态（页内输入时旧结果也
        // 留到新搜索返回），每击键清结果会让输入时列表闪空，超出本项范围。
        _uiState.value = _uiState.value.copy(
            query = query,
            suggestions = historySuggestions,
            error = null,
        )
        saveSearchState()
        searchJob?.cancel()
        suggestJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _uiState.value = _uiState.value.copy(results = emptyList(), error = null, isLoading = false)
            // B24-P2-2：空串分支只清内存态、不再存一次，SavedStateHandle 里仍是
            // 旧结果 JSON + 旧 searchedQuery；恢复时 query 已空、结果非空，不走
            // 早退，旧结果幽灵复现到空搜索框之下。补存一次（results 引用已变，
            // 写 "[]"；恢复时因结果为空正常早退，与 clearQuery() 同款顺序）。
            saveSearchState()
            return
        }
        val gen = ++generation
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            if (gen != generation) return@launch
            executeSearch(trimmed, gen)
        }
        // Jewel 第 9 轮 P2-6：拼音输入时补 iQIYI 中文候选——屏上键盘敲出的是拼音
        // 字母，历史联想只有搜过的词；拼音串发给 iQIYI 联想接口出中文候选，补在
        // 历史联想之后（mergeSuggestions）。联想失败/迟到时只留历史联想，不弹错。
        if (PinyinTable.isPinyinLike(trimmed)) {
            suggestJob = viewModelScope.launch {
                delay(DEBOUNCE_MS)
                if (gen != generation) return@launch
                val titles = fetchIqiyiSuggestTitles(suggestClient, trimmed)
                if (gen != generation) return@launch
                if (_uiState.value.query.trim() != trimmed) return@launch
                _uiState.value = _uiState.value.copy(
                    suggestions = mergeSuggestions(historySuggestions, titles),
                )
            }
        }
    }

    /** 立即搜（点搜索/联想 chip 时）。 */
    fun searchNow(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        searchJob?.cancel()
        val gen = ++generation
        // B25-P2-3：同步清掉旧联想（与 clearQuery() 同款一行）——之前只置
        // query、不碰 suggestions，点历史/联想 chip 后旧联想仍渲染在新
        // 搜索结果上方，直到下一次 onQueryChange 才重算。
        // B40-P2-1：searchNow 入口也先清旧错误再存档（与 B34-P2-1 的 onQueryChange
        // 同款顺序）——之前只置 query/suggestions，不碰 error/searchedQuery，
        // 切走再回会把新 query B + 旧 searchedQuery=A + 旧 error 一并恢复，
        // 搜索框是 B、错误态却是 A 的失败，点“重试”实际重搜 A。
        _uiState.value = _uiState.value.copy(
            query = trimmed,
            suggestions = emptyList(),
            error = null,
            searchedQuery = trimmed,
        )
        saveSearchState()
        searchJob = viewModelScope.launch {
            if (gen != generation) return@launch
            executeSearch(trimmed, gen)
        }
    }

    /** C4-P1-5：把当前搜索态写进 SavedStateHandle（query/结果/searchedQuery）。 */
    /** 上次写进 SavedStateHandle 的结果列表引用（B5-P2-10：变化才重编 JSON）。 */
    private var lastSavedResults: List<SearchResult>? = null

    private fun saveSearchState() {
        val state = _uiState.value
        savedStateHandle[SAVED_QUERY_KEY] = state.query
        savedStateHandle[SAVED_SEARCHED_QUERY_KEY] = state.searchedQuery
        // B33-P2-1：错误种类也持久化（种类 + 自定义文案，Network/Server 用）——
        // 之前只存 query/searchedQuery/results，恢复时失败（Timeout/Network/
        // NotConfigured 等、results 为空）被一律写成 Empty，“重试”和 NotConfigured
        // 的“去设置”按钮都消失。恢复时按原样解回，见 restoreSearchState。
        savedStateHandle[SAVED_ERROR_KEY] = encodeSearchError(state.error)
        savedStateHandle[SAVED_ERROR_MESSAGE_KEY] = state.error?.message
        // B5-P2-10：每次按键都把全部搜索结果 JSON 写进 SavedStateHandle；
        // 只在 results 引用变化时才编码写（query/searchedQuery 是廉价字符串，每次写无妨）。
        if (state.results !== lastSavedResults) {
            lastSavedResults = state.results
            savedStateHandle[SAVED_RESULTS_KEY] = runCatching {
                json.encodeToString(ListSerializer(SearchResult.serializer()), state.results)
            }.getOrDefault("[]")
        }
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
        // B33-P2-1：错误按保存的种类原样恢复，不再从“结果为空 + 搜过词”
        // 推导 Empty——失败（Timeout/Network/NotConfigured 等）恢复后
        // 仍是原来的失败，“重试”/“去设置”按钮才在；Empty 只来自成功后
        // 零结果的落态（缓存命中 :319、新鲜搜索 :366 仍写 Empty，别动）。
        val restoredError = decodeSearchError(
            savedStateHandle.get<String>(SAVED_ERROR_KEY),
            savedStateHandle.get<String>(SAVED_ERROR_MESSAGE_KEY),
        )
        _uiState.value = _uiState.value.copy(
            query = query,
            results = results,
            searchedQuery = searchedQuery,
            error = restoredError,
        )
        // B40-P2-2：在途搜索切走再回——ViewModel 随页面销毁（init :101-102），
        // 存档只有 query/results=[]/error=null，恢复后结果区 when 四分支全不
        // 命中、除 24dp Spacer 外什么都不渲染，且没有任何地方再发起搜索。
        // 恢复出“有词、无结果、无错误”时立即对该 query 重跑一次 executeSearch
        //（不走 searchNow 的存档，避免循环写；executeSearch 自己落态时存）。
        if (shouldResumeInterruptedSearch(query, results, restoredError)) {
            val gen = ++generation
            // B41-P2-2：恢复重跑先同步置 isLoading=true——executeSearch 的缓存命中
            // 分支先走 recordSearchHistory（DataStore 读 + 服务端写历史 POST +
            // 读最新历史 GET 两次网络往返，写失败还重试一次），全程结束才落态；
            // 不等它，历史往返耗时内 isLoading=false、results=[]、error=null、
            // query 非空，SearchScreen 四分支全不命中、只剩 24dp Spacer。只改
            // 恢复重跑分支，不动 executeSearch 开头（那会改正常缓存命中路径的
            // 行为——旧结果先闪成“正在搜索…”）。
            _uiState.value = _uiState.value.copy(isLoading = true)
            searchJob = viewModelScope.launch { executeSearch(query, gen) }
        }
    }

    /**
     * 写历史/读最新历史（B9-P2-3：网络成功与缓存命中共用）——之前只有网络分支走，
     * 缓存命中直接 return，历史不写也不刷新。
     * 无痕模式不写搜索历史（阶段 4）。
     * B6-P2-11：服务端写失败的返回值不再丢弃——写失败时调用方给一次 Toast，
     * 不假装已写进历史（展示用的 history 仍从服务端重读，不会“幽灵出现”）。
     * B7-P2-4：写历史/读最新历史各包独立 try/catch——抛错只影响 toast/历史，
     * 不丢已搜到的结果（旧实现任一抛错都会进外层 catch，结果被错误态覆盖）。
     * @return historySaved 与最新历史。
     */
    private suspend fun recordSearchHistory(query: String): Pair<Boolean, List<String>> {
        var historySaved = true
        var freshHistory = _uiState.value.history
        try {
            if (!settingsRepository.incognitoMode.first()) {
                historySaved = searchHistoryRepository.add(query)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            historySaved = false
        }
        try {
            freshHistory = searchHistoryRepository.getLatest20()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 读失败留旧 history，不丢结果。
        }
        return historySaved to freshHistory
    }

    private suspend fun executeSearch(query: String, gen: Int = generation) {
        // B-P1-6：进入搜索即记下本次词；失败分支也已赋值，重试按钮不再是死按钮。
        _uiState.value = _uiState.value.copy(searchedQuery = query)
        cache.get(query)?.let { cached ->
            // B9-P2-3：缓存命中也写历史/读最新历史——之前直接 return，搜过的词
            // 不进历史、历史列表也不刷新；与网络分支走同一段逻辑。
            val (historySaved, freshHistory) = recordSearchHistory(query)
            // B4-P2-3：两次挂起调用（写历史、读最新 20 条）后复核代次——其间
            // 用户若已发起新搜索，旧结果不得覆盖新搜索的状态。
            if (gen != generation) return
            _uiState.value = _uiState.value.copy(
                results = cached,
                isLoading = false,
                error = if (cached.isEmpty()) SearchError.Empty else null,
                searchedQuery = query,
                history = freshHistory,
                // B37-P2-1：历史保存成功时保留现有 Toast，不写 null——toast 是共用字段，
                // enableRemoteInput() 失败也往这里写“远程输入开启失败…”；在途搜索完成时
                // 若无条件清掉，那条失败提示未满 2 秒即消失。保留同一事件对象时消费
                // effect 的 key（toast.id）不变，原 2 秒计时继续走、不会赖着不走。
                toast = if (!historySaved) {
                    nextToastEvent("搜索历史保存失败，本次搜索不受影响")
                } else {
                    _uiState.value.toast
                },
            )
            saveSearchState()
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        try {
            // B8-P2-1：currentApiBaseUrl() 读 DataStore，失败会抛错——原来在
            // try 块之外，未捕获即协程崩溃、搜索页卡在“正在搜索…”；放进 try，
            // 按普通搜索失败落错误态、可重试。
            val baseUrl = settingsRepository.currentApiBaseUrl()
            if (baseUrl.isBlank()) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = SearchError.NotConfigured,
                )
                // B33-P2-1：失败落态也要存一次，否则 NotConfigured 只在内存里，
                // 切走再回恢复时丢了种类（旧实现还会被写成 Empty）。
                saveSearchState()
                return
            }
            val results = moonTvApi.searchVideos(baseUrl, query)
            // B-P2-23①：网络返回后复核 generation；迟到结果不得覆盖更新的搜索。
            //（旧任务被取消时，OkHttp 同步调用不响应取消，返回后本应由 CE 拦截，
            // 但取消恰好发生在无挂起点的窗口时仍需这道防线。）
            if (gen != generation) return
            // P2-11：空结果不缓存（服务端刚上新内容时用户不再持续看到空结果）。
            if (shouldCacheSearchResults(results)) cache.put(query, results)
            // B9-P2-3：写历史/读最新历史走共用 helper（缓存命中分支同款）。
            val (historySaved, freshHistory) = recordSearchHistory(query)
            // B4-P2-3：两次挂起调用（写历史、读最新 20 条）后复核代次——其间
            // 用户若已发起新搜索，旧结果不得覆盖新搜索的状态。
            if (gen != generation) return
            _uiState.value = _uiState.value.copy(
                results = results,
                isLoading = false,
                error = if (results.isEmpty()) SearchError.Empty else null,
                searchedQuery = query,
                history = freshHistory,
                // B37-P2-1：同缓存命中分支——历史保存成功时保留现有 Toast（见上）。
                toast = if (!historySaved) {
                    nextToastEvent("搜索历史保存失败，本次搜索不受影响")
                } else {
                    _uiState.value.toast
                },
            )
            saveSearchState()
        } catch (e: TimeoutCancellationException) {
            // B5-P1-2：MoonTvApi.searchVideos 的 150 秒总超时是
            // TimeoutCancellationException（CE 子类），按 SearchError.Timeout
            // 落错误态并清 isLoading；真取消（JobCancellationException）才重抛。
            if (gen != generation) return
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = SearchError.Timeout,
            )
            // B33-P2-1：失败落态也要存一次，种类才进 SavedStateHandle。
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
            // B33-P2-1：失败落态也要存一次，种类才进 SavedStateHandle。
            saveSearchState()
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
        // B17-P2-2：起删时快照代次，落态前复核仍是最新一次删除，迟到的旧
        // 结果丢弃（快速连删时先删的 getLatest20() 可能后返回）。
        val gen = ++deleteHistoryGeneration
        viewModelScope.launch {
            val deleted = try {
                searchHistoryRepository.delete(keyword)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            // B4-P2-18：删除失败时不刷成“已删”假象——重读服务端真实状态
            // （失败时该词仍在，读回来照常显示）；删成功但刷新读失败回落
            // 到旧缓存时，把已删词从展示里去掉，不让它“幽灵复现”
            // （服务端已删，展示也该消失）。
            val fresh = try {
                searchHistoryRepository.getLatest20()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.value.history
            }
            val newHistory = if (deleted) fresh.filter { it != keyword } else fresh
            if (gen != deleteHistoryGeneration) return@launch
            // B-P2-7：先取新 history 再算联想；用删除前的旧 history 算，
            // 删后联想 chips 仍含已删词，直到下次输入才刷新。
            _uiState.value = _uiState.value.copy(
                history = newHistory,
                suggestions = suggest(_uiState.value.query, newHistory),
            )
        }
    }

    fun clearQuery() {
        searchJob?.cancel()
        suggestJob?.cancel()
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

    /** B6-P2-11：Toast 消费后清掉（与 PlayScreen/SettingsScreen 同款 2 秒机制）。 */
    fun consumeToast() {
        _uiState.update { it.copy(toast = null) }
    }

    /** P2-14：扫码弹窗内一键开启远程输入（存设置 + 拉起服务）。 */
    fun enableRemoteInput() {
        viewModelScope.launch {
            try {
                settingsRepository.setRemoteInputEnabled(true)
                remoteInputManager.start()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // B8-P2-3：无 IP 时 start() 抛 IllegalStateException，旧实现
                // 静默吞掉，弹窗里看着像已开启；弹提示让用户知道没开成。
                _uiState.update { it.copy(toast = nextToastEvent("远程输入开启失败，请检查网络后重试")) }
            }
        }
    }

    companion object {
        /** 输入防抖 300ms（plan §3.2）。 */
        const val DEBOUNCE_MS = 300L

        /** C4-P1-5：SavedStateHandle 键（搜索切走再回不丢结果）。 */
        const val SAVED_QUERY_KEY = "search_query"
        const val SAVED_SEARCHED_QUERY_KEY = "search_searched_query"
        const val SAVED_RESULTS_KEY = "search_results_json"

        /**
         * B33-P2-1：SavedStateHandle 键（搜索错误种类 + 自定义文案，Network/
         * Server 的 message 是动态拼的，种类之外另存一份文案才不丢原话）。
         */
        const val SAVED_ERROR_KEY = "search_error_kind"
        const val SAVED_ERROR_MESSAGE_KEY = "search_error_message"

        /** C6-P1-2：焦点锚点键（首进标记 + 上次结果条目键，导航轨切走/进程重建也不丢）。 */
        const val SAVED_INITIAL_FOCUS_DONE = "search_initial_focus_done"
        const val SAVED_RESULT_KEY = "search_saved_result_key"
    }
}

/**
 * B33-P2-1：搜索错误种类进 SavedStateHandle 的编解码（纯逻辑，可单测）——
 * 恢复时按原样解回：失败（Timeout/Network/NotConfigured 等）不再被写成
 * Empty；Empty 只在成功后零结果时由落态写、经 save/restore 原样往返。
 * 未知种类解回 null（旧版本没存过该键时也是 null，不凭空造错误）。
 */
internal fun encodeSearchError(error: SearchError?): String? = when (error) {
    null -> null
    SearchError.NotConfigured -> "NotConfigured"
    SearchError.Empty -> "Empty"
    SearchError.Unauthorized -> "Unauthorized"
    SearchError.DataError -> "DataError"
    SearchError.Timeout -> "Timeout"
    is SearchError.Network -> "Network"
    is SearchError.Server -> "Server"
}

internal fun decodeSearchError(kind: String?, message: String?): SearchError? = when (kind) {
    null -> null
    "NotConfigured" -> SearchError.NotConfigured
    "Empty" -> SearchError.Empty
    "Unauthorized" -> SearchError.Unauthorized
    "DataError" -> SearchError.DataError
    "Timeout" -> SearchError.Timeout
    "Network" -> SearchError.Network(message ?: "网络错误，请检查网络后重试")
    "Server" -> SearchError.Server(message ?: "服务器错误，请稍后重试")
    else -> null
}

/** P2-11：只缓存非空结果；空结果不进缓存（纯逻辑，可单测）。 */
fun shouldCacheSearchResults(results: List<SearchResult>): Boolean = results.isNotEmpty()

/** 搜索结果条目键（纯逻辑，可单测）：source+id。 */
fun searchResultKey(result: SearchResult): String =
    "${result.source}+${result.id.orEmpty()}"

/** P2-9（第 5 轮）：按条目键找返回焦点位置（纯逻辑，可单测）；找不到返回 -1。 */
fun findSavedResult(results: List<SearchResult>, savedKey: String?): Int {
    if (savedKey == null) return -1
    return results.indexOfFirst { searchResultKey(it) == savedKey }
}

/**
 * Jewel 第 8 轮 P1-1：搜索开始时是否把焦点收回搜索框（纯逻辑，可单测）——
 * 只在焦点确实在结果区（或刚从结果区因网格卸载而丢失，见 SearchScreen 的
 * resultsAreaHadFocus latch）时收回；焦点在屏上键盘按键上时不动
 * （遥控逐字输入不被偷焦点）；扫码弹窗开着时不动（焦点在弹窗内）。
 * C8-P1-3 的意图保留：远程输入触发新搜索、焦点在结果卡上时仍收回。
 */
fun shouldReclaimSearchFocusOnLoading(
    resultsAreaHadFocus: Boolean,
    keyboardHasFocus: Boolean,
    remoteInputModalOpen: Boolean,
): Boolean = resultsAreaHadFocus && !keyboardHasFocus && !remoteInputModalOpen

/**
 * Jewel 第 8 轮 P2-4：返回焦点恢复是否需要按保存键轮询（纯逻辑，可单测）——
 * 无保存键时 findSavedResult 恒 -1，不轮询、直接回落搜索框，
 * 不空转 10×100ms 拖慢弹窗关闭后的焦点恢复。
 */
fun shouldPollSavedResult(savedKey: String?): Boolean = savedKey != null

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
 * B40-P2-2：恢复后是否立即重跑一次搜索（纯逻辑，可单测）——只在“有词、无结果、
 * 无错误”时重跑，即恢复出的正是搜索在途被切走丢掉的那种态。已搜到结果、已落
 * 失败/空结果（error 非空，含 Empty）时不重跑；词为空时也不重跑。
 */
internal fun shouldResumeInterruptedSearch(
    query: String,
    results: List<SearchResult>,
    error: SearchError?,
): Boolean = query.isNotBlank() && results.isEmpty() && error == null

/**
 * 远程输入应搜哪条文本（纯逻辑，可单测，B9-P2-5）：以 state 的最新一条为准，
 * 不用流里取出的那条——连续两条时先到的可能还在缓冲里，后到的已进 state；
 * 5 分钟外的旧文本返回 null（无订阅者时滞留的旧消息不误触发）。
 */
fun resolveRemoteMessageToSearch(
    lastMessage: String?,
    lastMessageAt: Long?,
    nowMs: Long = System.currentTimeMillis(),
): String? = lastMessage?.takeIf { isFreshRemoteMessage(lastMessageAt, nowMs) }

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
    /** 一次性提示（B6-P2-11：搜索历史服务端写失败，Toast，2 秒消失）。 */
    val toast: ToastEvent? = null,
)
