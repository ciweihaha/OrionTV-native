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
        viewModelScope.launch {
            // B4-P2-5：仓库调用失败不崩溃——保留旧行并置错误提示（首次加载失败
            // 时行是空的，错误文案由下面 error 分支给出）。
            try {
                // P2-8：刷新保留旧行，只置 loading，不闪回“正在加载…”空屏。
                _uiState.value = _uiState.value.copy(isLoading = true)
                val serverConfigured = settingsRepository.isServerConfigured()
                if (!serverConfigured) {
                    _uiState.value = HomeUiState(serverConfigured = false)
                    return@launch
                }
                val baseUrl = settingsRepository.currentApiBaseUrl()
                val rows = loadRows(baseUrl)
                if (gen != generation) return@launch // 迟到结果丢弃
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
        cache.get(category.title)?.let { return HomeRow(category.title, it) }
        val items: List<HomeRowItem> = when {
            category.cmsType != null -> loadCmsCategory(category, baseUrl)
            else -> loadDoubanCategory(category, baseUrl)
        }
        if (items.isEmpty()) return null
        val deduped = dedupeAggItems(items)
        cache.put(category.title, deduped)
        return HomeRow(category.title, deduped)
    }

    /** CMS 分类：agg 首屏；失败或空列表时回落豆瓣（无筛选时才回落）。 */
    private suspend fun loadCmsCategory(category: HomeCategory, baseUrl: String): List<HomeRowItem> {
        val cmsType = category.cmsType ?: return emptyList()
        // B4-P2-4：runCatching 会吞 CancellationException，改 try/catch 前置重抛。
        val aggItems = try {
            aggApi.videoList(cmsType, 1).list.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        if (aggItems.isNotEmpty()) return aggItems.map(::mapAggItem)
        // 回落豆瓣：走用户服务器的 /api/douban。
        val fallbackTag = category.fallbackTag ?: return emptyList()
        return loadDouban(category.doubanType ?: "tv", fallbackTag, baseUrl)
    }

    private suspend fun loadDoubanCategory(category: HomeCategory, baseUrl: String): List<HomeRowItem> =
        loadDouban(category.doubanType ?: "movie", category.doubanTag ?: "热门", baseUrl)

    private suspend fun loadDouban(type: String, tag: String, baseUrl: String): List<HomeRowItem> =
        // B4-P2-4：同上，runCatching 吞取消，改 try/catch 前置重抛。
        try {
            moonTvApi.getDoubanData(baseUrl, type, tag, pageSize = 20, pageStart = 0)
                .list.orEmpty().map(::mapDoubanItem)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
}

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
