package com.oriontv.feature.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oriontv.data.api.AggApi
import com.oriontv.data.api.MoonTvApi
import com.oriontv.data.repo.PlayRecordsRepository
import com.oriontv.data.repo.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject

/**
 * 首页 ViewModel（ui-design.md §5.1）：
 * 分类 chip 栏置顶，下方依次“最近播放”→“热门剧集”→各分类行；
 * 最近播放来自本地播放记录（断点续播）；热门/Top250 走豆瓣；
 * 电视剧/电影/动漫/综艺走 agg CMS，失败或首屏空列表时回落豆瓣；
 * 缓存 5 分钟/10 分类/每类 40 条、真 LRU；切分类/刷新时代次校验丢弃迟到结果。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val aggApi: AggApi,
    private val moonTvApi: MoonTvApi,
    private val settingsRepository: SettingsRepository,
    private val playRecordsRepository: PlayRecordsRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState(isLoading = true))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val cache = HomeCache()
    private var generation = 0

    /**
     * 分类浏览分页：每行一个状态机（行标题为键），记该行第 1 页实际走的源与
     * 下一页游标。refresh() 重建行时清空、loadCategory 建行时重建；
     * 最近播放行是本地记录，不分页，不建状态机。只在 viewModelScope
     *（Main）里读写，不跨线程。
     */
    private val pagingStates = mutableMapOf<String, RowPagingState>()

    /**
     * B13-P2-2：最近播放行的独立代次——两次并发 refreshRecent() 各 load 各合并
     * 时，先启动的旧快照后完成会盖掉新的行；落态前复核，旧代次丢弃。
     * 另见 refreshRecent() 里对共享 generation 的快照：refresh() 在本次加载
     * 期间跑完并写了新的最近播放行时，旧快照的合并同样必须丢弃。
     */
    private var recentGeneration = 0

    /**
     * B14-P2-2：refresh() 写态时推进的行版本——refreshRecent 落态前复核：
     * 先启动的 refresh() 在 refreshRecent 加载期间跑完写了新行时，旧快照的
     * 合并必须丢弃。B13-P2-2 的双代次守卫只拦得住 refresh() 在 refreshRecent
     * 加载期间才开始的情况（generation 在 refresh() 开始时 +1，落态前
     * genAtStart != generation 能发现），先启动的 refresh() 完成时
     * genAtStart == generation 仍成立、旧快照照样覆盖新行。
     * 只由 refresh() 写新行时推进，refreshRecent 只快照、不推进——
     * 推进会让 refresh() 误判迟到丢掉整屏（B13-P2-2 的教训）。
     */
    private var rowsVersion = 0

    /**
     * C5-P2-4：P1-4 的焦点锚点原来是 ViewModel 里的普通变量。导航轨切走
     * （popUpTo(Home) 弹掉首页）后 ViewModel 被清，只靠内存留不住；
     * 再切回来新建的 ViewModel 从头开始，焦点回到分类栏顶部（收藏页
     * 0ed23ab、搜索页 C4-P1-5 同款问题）。改成 SavedStateHandle 承载，
     * 照 FavoritesViewModel 的做法。
     */
    var initialFocusDone: Boolean
        get() = savedStateHandle[SAVED_INITIAL_FOCUS_DONE] ?: false
        set(value) {
            savedStateHandle[SAVED_INITIAL_FOCUS_DONE] = value
        }

    /**
     * P2-9（第 5 轮）：返回焦点恢复按条目键锚定（行标题 + source+id），
     * 不按行/卡片 index——列表变化后 index 对应的已是另一张卡。
     * 键同样由 SavedStateHandle 承载（不只用内存变量，否则导航轨切走后
     * ViewModel 被清、键就丢了）。
     */
    var savedFocusKey: String?
        get() = savedStateHandle[SAVED_FOCUS_KEY]
        set(value) {
            savedStateHandle[SAVED_FOCUS_KEY] = value
        }

    companion object {
        /** SavedStateHandle 键：是否已落过默认锚点。 */
        const val SAVED_INITIAL_FOCUS_DONE = "home_initial_focus_done"

        /** SavedStateHandle 键：上次所在的卡片条目键（P2-9 按键锚定，不按 index）。 */
        const val SAVED_FOCUS_KEY = "home_saved_focus_key"
    }

    init {
        refresh()
    }

    fun refresh() {
        val gen = ++generation
        // 分页从第 1 页重新开始：旧状态机丢弃，loadCategory 建行时重建。
        pagingStates.clear()
        viewModelScope.launch {
            // B4-P2-5：仓库调用失败不崩溃——保留旧行并置错误提示（首次加载失败
            // 时行是空的，错误文案由下面 error 分支给出）。
            try {
                // P2-8：刷新保留旧行，只置 loading，不闪回“正在加载…”空屏。
                _uiState.value = _uiState.value.copy(isLoading = true)
                val serverConfigured = settingsRepository.isServerConfigured()
                if (!serverConfigured) {
                    // H11-C1：第 11 轮——未配置分支补代次守卫：isServerConfigured()
                    // 是挂起点，陈旧的 refresh 可能在较新 refresh 已写好行之后
                    // 才恢复、无守卫地清空整屏（成功分支与 catch 分支早已有该守卫）。
                    if (gen != generation) return@launch
                    // B14-P2-2：清行也是写态，推进版本（见 rowsVersion 注释）。
                    rowsVersion++
                    _uiState.value = HomeUiState(serverConfigured = false)
                    return@launch
                }
                val baseUrl = settingsRepository.currentApiBaseUrl()
                val rows = loadRows(baseUrl)
                if (gen != generation) return@launch // 迟到结果丢弃
                // B14-P2-2：写新行时推进版本——先启动的 refreshRecent 落态前复核。
                rowsVersion++
                _uiState.value = HomeUiState(
                    serverConfigured = true,
                    rows = rows,
                    error = if (rows.isEmpty()) "加载失败，请检查网络或服务器地址" else null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (gen != generation) return@launch
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "加载失败，请检查网络或服务器地址",
                )
            }
        }
    }

    /** 返回首页时只刷新“最近播放”行（断点续播刚看完要立刻出现），其他行走缓存。 */
    fun refreshRecent() {
        val recentGen = ++recentGeneration
        // B13-P2-2：快照 refresh() 的共享代次——refresh() 在本次加载期间跑完
        // 并写了新的最近播放行时，旧快照的合并必须丢弃，否则把新行换回旧快照。
        // 注意只快照、不推进共享代次：refreshRecent 在每次 ON_RESUME 都调，
        // 冷启动时与 init 的 refresh() 并发，推进会让 refresh() 误判迟到、
        // 丢掉整屏（B11-P2-2 只修了合并原子性，代次守卫这里补上）。
        val genAtStart = generation
        // B14-P2-2：再快照行版本——先启动的 refresh() 在本次加载期间跑完
        // 写了新行时，genAtStart == generation 仍成立、上面一道守卫拦不住，
        // 靠行版本复核丢弃旧快照；同样只快照、不推进。
        val rowsVersionAtStart = rowsVersion
        viewModelScope.launch {
            // P2-11（第 5 轮）：加载失败时不删旧行——以前先删行、失败后不补回，
            // 一次瞬时失败就让首页丢一行，直到下次 refresh()。
            // 区分两种 null：抛异常 = 失败，留旧行；正常返回 null = 确实
            // 没有播放记录，删行是正确的。
            // B4-P2-4：取消原样抛出，不当成普通失败吞掉（见下面的
            // CancellationException 分支）。
            val recent = try {
                loadCategory(HomeCategory.RECENT, "")
            } catch (e: CancellationException) {
                // D-P2-2：取消原样抛出，不吞成 null（ViewModel 清掉时本应直接取消）。
                throw e
            } catch (_: Exception) {
                return@launch
            }
            // B13-P2-2：落态前复核双代次——有更新的 refreshRecent 已启动，或
            // refresh() 在此期间跑完写了新行时，旧快照不再合并。
            // B14-P2-2：再复核行版本——先启动的 refresh() 在此期间跑完写了
            // 新行时，上面两道代次都拦不住（见 rowsVersion 注释）。
            if (!shouldApplyRecentRow(
                    recentGen, recentGeneration,
                    genAtStart, generation,
                    rowsVersionAtStart, rowsVersion,
                )
            ) return@launch
            // B11-P2-2：行合并放进 _uiState.update { … } 的 lambda 内做、
            // 对最新态原子合并——之前先读 _uiState.value.rows 再赋值，与
            // refresh() 并发时旧行快照会覆盖新行（冷启动首屏加载期间离页
            // 再回，首页只剩“最近播放”一行）。
            _uiState.update { current -> current.copy(rows = mergeRecentRow(current.rows, recent)) }
        }
    }

    /** 各行并发加载；单行失败留占位行（P2-9），不静默消失。 */
    private suspend fun loadRows(baseUrl: String): List<HomeRow> = coroutineScope {
        val deferred = HomeCategory.entries.map { category ->
            async {
                try {
                    loadCategory(category, baseUrl)
                } catch (e: CancellationException) {
                    // B4-P2-4：取消原样抛出，不当成单行失败留占位。
                    throw e
                } catch (_: Exception) {
                    HomeRow(category.title, emptyList(), error = "该分类加载失败")
                }
            }
        }
        deferred.awaitAll().filterNotNull()
    }

    private suspend fun loadCategory(category: HomeCategory, baseUrl: String): HomeRow? {
        if (category == HomeCategory.RECENT) {
            val records = playRecordsRepository.getAllWithKeys()
                .take(HomeCache.MAX_ITEMS_PER_CATEGORY)
            if (records.isEmpty()) return null
            return HomeRow(
                title = category.title,
                items = records.map { (key, record) -> mapPlayRecord(key, record) },
                // B-P2-20：服务端拉取失败时展示的是上次成功结果，行尾给一句提示。
                error = if (playRecordsRepository.loadFailed.value) {
                    "播放记录加载失败，显示上次内容"
                } else {
                    null
                },
            )
        }
        cache.get(category.title)?.let { cached ->
            // 缓存命中：按存的源重建分页状态机（没记源的老条目不分页，等下次新鲜加载再建）。
            cache.getSourceKind(category.title)?.let { kind ->
                pagingStates[category.title] = newPagingState(category, kind)
                return HomeRow(category.title, cached, hasMore = true)
            }
            return HomeRow(category.title, cached)
        }
        val (items, kind) = when {
            category.cmsType != null -> loadCmsCategory(category, baseUrl)
            else -> loadDoubanCategory(category, baseUrl) to RowSource.DOUBAN
        }
        if (items.isEmpty()) return null
        val deduped = dedupeAggItems(items)
        cache.put(category.title, deduped, kind)
        pagingStates[category.title] = newPagingState(category, kind)
        return HomeRow(category.title, deduped, hasMore = true)
    }

    /**
     * 分类浏览分页：焦点移到行尾附近时由 HomeScreen 调，取该行的下一页追加到行尾。
     * 同一时间一行只取一页（状态机门控）；取到空页时链式续取下一页，
     * 连续两页空才判到底；取失败不丢已有卡片，行尾留失败文案，焦点移开再回来即重试。
     * 刷新中途（generation 变了）取回的结果丢弃，不往新行里掺旧页。
     */
    fun loadMore(title: String) {
        val state = pagingStates[title] ?: return
        if (!state.canLoadMore()) return
        val category = HomeCategory.entries.firstOrNull { it.title == title } ?: return
        if (category == HomeCategory.RECENT) return
        val gen = generation
        state.onLoadStarted()
        _uiState.update { current ->
            current.copy(rows = current.rows.map {
                if (it.title == title) it.copy(isLoadingMore = true, loadMoreError = null) else it
            })
        }
        viewModelScope.launch {
            try {
                val baseUrl = settingsRepository.currentApiBaseUrl()
                var newItems: List<HomeRowItem>? = null
                var failed = false
                // H12-P1-1：第 12 轮——链式续取的循环条件不能用 canLoadMore()：
                // loadMore() 入口已同步调过 onLoadStarted()（isLoading=true，
                // 拦并发调用），canLoadMore() = !isLoading && hasMore 在取数中
                // 恒为假，while (canLoadMore()) 一次都不进、整页取数是死代码，
                // 首调还会把 isLoading 永久置真、该行此后永不分页（PR #29 的
                // 分类分页功能实际完全没跑起来）。只看 hasMore：空页由
                // onEmptyPage 推进游标、连续两页空才判到底，循环自停；每次
                // 续取前重上 onLoadStarted()，并发 loadMore 在续取途中仍被拦住。
                while (state.hasMore) {
                    // 每次链式续取前重上加载中——onEmptyPage 会清掉它，不重上会让并发的
                    // loadMore 在续取途中溜进来（两路同时取同一页）。
                    state.onLoadStarted()
                    val cursor = state.nextCursor
                    val page = try {
                        fetchPage(state, cursor, baseUrl)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (gen != generation) {
                        // H12-P2-1：迟到结果丢弃前先复位——refresh() 失败时会保留
                        // 旧行，不复位“加载更多中…”就一直挂着、该行也永不分页。
                        resetStaleLoadMore(title, state)
                        return@launch
                    }
                    when {
                        page == null -> {
                            state.onLoadFailed()
                            failed = true
                            break
                        }
                        page.isEmpty() -> state.onEmptyPage(state.advanceCursor(cursor))
                        else -> {
                            newItems = page
                            state.onPageLoaded(state.advanceCursor(cursor))
                            break
                        }
                    }
                }
                if (gen != generation) {
                    resetStaleLoadMore(title, state)
                    return@launch
                }
                _uiState.update { current ->
                    current.copy(rows = current.rows.map { row ->
                        if (row.title != title) {
                            row
                        } else {
                            row.copy(
                                items = if (newItems != null) appendPageItems(row.items, newItems) else row.items,
                                isLoadingMore = false,
                                hasMore = state.hasMore,
                                loadMoreError = if (failed) "加载更多失败，移开焦点再回来可重试" else null,
                            )
                        }
                    })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // baseUrl 读取失败等：状态机复位可重试，行尾留失败文案，已有卡片不动。
                state.onLoadFailed()
                if (gen != generation) {
                    // H12-P2-1 同款：迟到时只清“加载更多中…”、不留失败文案
                    //（新行自有状态；resetStaleLoadMore 里 onLoadFailed() 再调一次无害）。
                    resetStaleLoadMore(title, state)
                    return@launch
                }
                _uiState.update { current ->
                    current.copy(rows = current.rows.map {
                        if (it.title == title) {
                            it.copy(isLoadingMore = false, loadMoreError = "加载更多失败，移开焦点再回来可重试")
                        } else {
                            it
                        }
                    })
                }
            }
        }
    }

    /**
     * H12-P2-1：第 12 轮——迟到（generation 已变）的取数结果丢弃前，先把状态机
     * 与行上的“加载更多中…”复位：refresh() 失败时会保留旧行，不复位提示就
     * 一直挂着、该行也永不分页。此时 _uiState 里的行可能是新 refresh 的行
     * （isLoadingMore 本就为 false），清一次无害；旧状态机已被 refresh() 丢弃，
     * onLoadFailed() 同样无害。
     */
    private fun resetStaleLoadMore(title: String, state: RowPagingState) {
        state.onLoadFailed()
        _uiState.update { current ->
            current.copy(rows = current.rows.map {
                if (it.title == title) it.copy(isLoadingMore = false) else it
            })
        }
    }

    /** 取一页：CMS 按页号，豆瓣按偏移（pageStart）；失败抛错由调用方处理。 */
    private suspend fun fetchPage(
        state: RowPagingState,
        cursor: Int,
        baseUrl: String,
    ): List<HomeRowItem> = when (state.kind) {
        RowSource.CMS -> {
            val cmsType = state.cmsType ?: return emptyList()
            aggApi.videoList(cmsType, cursor).list.orEmpty().map(::mapAggItem)
        }
        RowSource.DOUBAN -> {
            val type = state.doubanType ?: return emptyList()
            val tag = state.doubanTag ?: return emptyList()
            moonTvApi.getDoubanData(
                baseUrl, type, tag,
                pageSize = RowPagingState.DOUBAN_PAGE_SIZE, pageStart = cursor,
            ).list.orEmpty().map(::mapDoubanItem)
        }
    }

    /**
     * 按第 1 页实际走的源建分页状态机（翻页用第 1 页解析出的实际参数，不混源）。
     * CMS 回落豆瓣时用回落的 tag/type；纯豆瓣分类用自身的 tag/type。
     */
    private fun newPagingState(category: HomeCategory, kind: RowSource): RowPagingState =
        when (kind) {
            RowSource.CMS -> RowPagingState(kind, cmsType = category.cmsType)
            RowSource.DOUBAN -> {
                val (doubanType, doubanTag) = if (category.cmsType != null) {
                    (category.doubanType ?: "tv") to (category.fallbackTag ?: category.doubanTag ?: "热门")
                } else {
                    (category.doubanType ?: "movie") to (category.doubanTag ?: "热门")
                }
                RowPagingState(kind, doubanType = doubanType, doubanTag = doubanTag)
            }
        }

    /** CMS 分类：agg 首屏；失败或空列表时回落豆瓣（无筛选时才回落）。返回（条目，实际走的源）。 */
    private suspend fun loadCmsCategory(category: HomeCategory, baseUrl: String): Pair<List<HomeRowItem>, RowSource> {
        val cmsType = category.cmsType ?: return emptyList<HomeRowItem>() to RowSource.CMS
        // B4-P2-4：runCatching 会吞 CancellationException，改 try/catch 前置重抛。
        val aggItems = try {
            aggApi.videoList(cmsType, 1).list.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        if (aggItems.isNotEmpty()) return aggItems.map(::mapAggItem) to RowSource.CMS
        // 回落豆瓣：走用户服务器的 /api/douban。
        // B12-P2-5：agg 失败 + 豆瓣失败时行不再静默丢弃——loadDouban 失败即抛错，
        // 走 loadRows() 的占位行；只有豆瓣成功返回空时才是双源都返回空，行不展示。
        val fallbackTag = category.fallbackTag ?: return emptyList<HomeRowItem>() to RowSource.CMS
        return loadDouban(category.doubanType ?: "tv", fallbackTag, baseUrl) to RowSource.DOUBAN
    }

    private suspend fun loadDoubanCategory(category: HomeCategory, baseUrl: String): List<HomeRowItem> =
        loadDouban(category.doubanType ?: "movie", category.doubanTag ?: "热门", baseUrl)

    private suspend fun loadDouban(type: String, tag: String, baseUrl: String): List<HomeRowItem> =
        // B12-P2-5：豆瓣失败不再吞成空列表——之前失败与“成功返回空”都落到
        // 空列表，loadCategory 无法区分、行被静默丢弃、无失败占位；现在失败
        // 即抛错，走 loadRows() 的占位行（“该分类加载失败”），只有成功返回空
        // 才是真的没内容、行不展示。取消原样抛出（不包进失败里）。
        moonTvApi.getDoubanData(baseUrl, type, tag, pageSize = 20, pageStart = 0)
            .list.orEmpty().map(::mapDoubanItem)
}

/**
 * refreshRecent 落态前是否应合并旧快照（可单测，B14-P2-2）：三代次全过才合并——
 * refreshRecent 自身代次（防两次并发 refreshRecent）、共享 generation
 * （防 refresh() 在本次加载期间才开始）、行版本（防 refresh() 在本次加载
 * 前已开始、期间跑完写了新行——B13-P2-2 的双代次守卫只拦得住前一种）。
 * 行版本只由 refresh() 写态时推进，refreshRecent 只快照、不推进。
 */
internal fun shouldApplyRecentRow(
    recentGen: Int,
    recentGeneration: Int,
    genAtStart: Int,
    generation: Int,
    rowsVersionAtStart: Int,
    rowsVersion: Int,
): Boolean = recentGen == recentGeneration && genAtStart == generation &&
    rowsVersionAtStart == rowsVersion

/**
 * “最近播放”行合并（可单测，B11-P2-2）：去掉旧的最近播放行、新行插到首位；
 * recent 为 null（确实没有播放记录）时只删旧行，不动其他行。
 * 调用方须在 _uiState.update { … } 的 lambda 内调它（对最新态原子合并），
 * 不能先读 _uiState.value.rows 再赋值——与 refresh() 并发时旧行快照会覆盖新行。
 */
internal fun mergeRecentRow(existing: List<HomeRow>, recent: HomeRow?): List<HomeRow> {
    val rows = existing.toMutableList()
    rows.removeAll { it.title == HomeCategory.RECENT.title }
    if (recent != null) rows.add(0, recent)
    return rows
}

data class HomeUiState(
    val isLoading: Boolean = false,
    val serverConfigured: Boolean = true,
    val rows: List<HomeRow> = emptyList(),
    val error: String? = null,
)
