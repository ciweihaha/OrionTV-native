package com.oriontv.feature.home

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
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState(isLoading = true))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val cache = HomeCache()
    private var generation = 0

    /** P1-4：首进才落默认焦点锚点；返回时恢复 savedFocusRow/Card。 */
    var initialFocusDone: Boolean = false
    var savedFocusRow: Int = -1
    var savedFocusCard: Int = -1

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
            // B4-P2-4：取消原样抛出，不当成普通失败吞掉。
            val recent = try {
                loadCategory(HomeCategory.RECENT, "")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val rows = _uiState.value.rows.toMutableList()
            rows.removeAll { it.title == HomeCategory.RECENT.title }
            if (recent != null) rows.add(0, recent)
            _uiState.value = _uiState.value.copy(rows = rows)
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

data class HomeUiState(
    val isLoading: Boolean = false,
    val serverConfigured: Boolean = true,
    val rows: List<HomeRow> = emptyList(),
    val error: String? = null,
)
