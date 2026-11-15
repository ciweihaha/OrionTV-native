package com.oriontv.data.repo

import com.oriontv.data.api.MoonTvApi
import com.oriontv.data.api.SearchResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * 详情多源聚合（plan §5.2，照搬 RN detailStore 流程）：
 * 有首选源时先单源搜，0 条或失败立即全量回落；无首选源时先拉资源列表，
 * 按源逐个搜，并发上限 5，单个源失败不影响其他，只留精确标题匹配的结果；
 * 首选源成功后后台再跑一次全量搜索合并其余源。
 * 按源去重（同一源多条结果只留第一条，服务端按相关性排序）。
 */
@Singleton
class DetailRepository @Inject constructor(
    private val moonTvApi: MoonTvApi,
    private val settingsRepository: SettingsRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * 详情搜索。首选源结果先通过 [onUpdate] 发出（allLoaded=false），
     * 全量搜索完成后合并其余源再发一次（allLoaded=true）。
     * 全量搜索失败且首选源已有结果时不报错；两者都失败时抛错。
     */
    suspend fun searchDetail(
        q: String,
        preferredSource: String?,
        id: String?,
        onUpdate: (results: List<SearchResult>, allLoaded: Boolean) -> Unit,
    ) = withContext(ioDispatcher) {
        val baseUrl = settingsRepository.currentApiBaseUrl()
        require(baseUrl.isNotBlank()) { "未配置服务器地址" }
        var preferredResults: List<SearchResult> = emptyList()
        var preferredError: Exception? = null
        if (!preferredSource.isNullOrBlank()) {
            try {
                val raw = moonTvApi.searchVideo(baseUrl, q, preferredSource)
                preferredResults = DetailSearchLogic.selectPreferredResults(raw, q, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                preferredError = e
            }
        }
        if (preferredResults.isNotEmpty()) {
            onUpdate(DetailSearchLogic.dedupeBySource(preferredResults), false)
        }
        // 全量搜索：资源列表 → 逐源单搜（并发 5）→ 精确标题匹配 → 按源去重 → 合并。
        // B5-P1-3：fullSearch 的 120 秒总超时是 TimeoutCancellationException
        //（CE 子类），不是用户取消——有首选源结果时保留并标记加载完成，
        // 无结果时转普通 IOException，让 DetailViewModel 落可重试的 error 态。
        val merged = try {
            fullSearch(baseUrl, q, preferredResults)
        } catch (e: TimeoutCancellationException) {
            if (preferredResults.isNotEmpty()) {
                onUpdate(DetailSearchLogic.dedupeBySource(preferredResults), true)
                return@withContext
            }
            throw IOException("详情搜索超时，请重试")
        }
        if (merged.isNotEmpty()) {
            onUpdate(merged, true)
        } else if (preferredResults.isEmpty()) {
            throw preferredError ?: IllegalStateException("没有找到相关内容")
        }
        // 首选源已有结果但全量搜索 0 条：保持首选源结果，标记加载完成。
        if (preferredResults.isNotEmpty() && merged.isEmpty()) {
            onUpdate(DetailSearchLogic.dedupeBySource(preferredResults), true)
        }
    }

    private suspend fun fullSearch(
        baseUrl: String,
        q: String,
        preferredResults: List<SearchResult>,
    ): List<SearchResult> = coroutineScope {
        // B4-P2-16：总超时 120 秒 + 资源数上限 30——无上限时源多、单源慢，
        // 详情全量搜索会拖很久才返回。
        withTimeout(120_000) {
            fullSearchUnbounded(baseUrl, q, preferredResults)
        }
    }

    private suspend fun fullSearchUnbounded(
        baseUrl: String,
        q: String,
        preferredResults: List<SearchResult>,
    ): List<SearchResult> = coroutineScope {
        val resources = try {
            moonTvApi.getResources(baseUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // B23-P2-1：无首选源结果可回落时把原异常原样重抛——否则网络错
            // 会被 searchDetail 误报成“没有找到相关内容”；有首选源结果
            // 时才走降级返回（B14-P2-3 的降级语义保留）。
            if (preferredResults.isEmpty()) throw e
            // B14-P2-3：资源列表拉取失败时直接返回首选源结果——其余三条路
            // 都走了 dedupeBySource，这里漏了：searchDetail 随后
            // onUpdate(merged, true) 原样发出，同源 chip 会重复出现。
            return@coroutineScope DetailSearchLogic.dedupeBySource(preferredResults)
        }
        val semaphore = Semaphore(DetailSearchLogic.SEARCH_CONCURRENCY_LIMIT)
        // B25-P2-1：逐源失败只记首个异常（区分“返回空列表”与“抛错”）——
        // 之前 catch 里全吞成 emptyList()，getResources 成功、随后 30 路
        // 全失败时 merged 为空、preferredError 为 null，searchDetail 会把
        // 网络错误报成“没有找到相关内容”（B23-P2-1 只修了 getResources 腿）。
        val firstError = AtomicReference<Exception?>(null)
        val failureCount = AtomicInteger(0)
        val sites = resources.take(30)
        val deferred = sites.map { site ->
            async {
                semaphore.withPermit {
                    try {
                        moonTvApi.searchVideo(baseUrl, q, site.key)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        firstError.compareAndSet(null, e)
                        failureCount.incrementAndGet()
                        emptyList()
                    }
                }
            }
        }
        val all = deferred.awaitAll().flatten()
        // B25-P2-1：全部源都抛错、且无首选源结果可回落时，把首个异常原样重抛；
        // 任一源成功返回过（即使无精确标题匹配）仍按“没有找到相关内容”走
        // （B23-P2-1 同款口径）；有首选源结果时不抛，走 B14-P2-3 的降级合并。
        val error = firstError.get()
        if (error != null && failureCount.get() == sites.size && preferredResults.isEmpty()) {
            throw error
        }
        // 全量路径只留精确标题匹配的结果（RN 同款：搜“庆余年”不带回“庆余年第二季”）。
        val exact = DetailSearchLogic.filterExactTitle(all, q)
        // 合并：首选源结果在前，其余源按全量搜索顺序补；按源去重。
        return@coroutineScope DetailSearchLogic.dedupeBySource(preferredResults + exact)
    }
}

/** 详情聚合的纯逻辑（可单测，不依赖网络）。 */
object DetailSearchLogic {
    /** 详情搜索扇出与清晰度检测共用的并发上限（plan §5.2）。 */
    const val SEARCH_CONCURRENCY_LIMIT = 5

    /**
     * 首选源结果筛选：id 优先（调用方传 id 就是为了定位具体哪部影片），
     * id 对不上时退到精确标题；两者都对不上时返回原结果（不丢源，服务端排序兜底）。
     */
    fun selectPreferredResults(
        results: List<SearchResult>,
        q: String,
        id: String?,
    ): List<SearchResult> {
        if (!id.isNullOrBlank()) {
            val byId = results.filter { it.id == id }
            if (byId.isNotEmpty()) return byId
        }
        val byTitle = filterExactTitle(results, q)
        if (byTitle.isNotEmpty()) return byTitle
        return results
    }

    /** 精确标题匹配：归一化（去空白、小写）后相等才算。 */
    fun filterExactTitle(results: List<SearchResult>, q: String): List<SearchResult> {
        val normalizedQuery = MoonTvApi.normalizeTitle(q)
        return results.filter {
            it.title.isNotBlank() && MoonTvApi.normalizeTitle(it.title) == normalizedQuery
        }
    }

    /** 按源去重：同一源多条结果只留第一条。 */
    fun dedupeBySource(results: List<SearchResult>): List<SearchResult> {
        val seen = LinkedHashSet<String>()
        return results.filter { seen.add(it.source) }
    }

    /**
     * 自动换源：标记当前源失败后，按顺序选下一可用源；
     * 候选源须未失败、且集数 ≥ 当前集（否则换过去也播不了当前集）。
     */
    fun getNextAvailableSource(
        results: List<SearchResult>,
        currentSource: String,
        episodeIndex: Int,
        failedSources: Set<String>,
    ): SearchResult? = results.firstOrNull {
        it.source != currentSource &&
            it.source !in failedSources &&
            it.episodes.size > episodeIndex
    }

    /**
     * 选集分组：长剧折叠成“1-20 / 21-40 …”分组 chip，每组 [groupSize] 集。
     * 返回 (开始集号 1-based, 结束集号 1-based) 列表。
     */
    fun episodeGroups(totalEpisodes: Int, groupSize: Int = 20): List<Pair<Int, Int>> {
        if (totalEpisodes <= 0) return emptyList()
        val groups = mutableListOf<Pair<Int, Int>>()
        var start = 1
        while (start <= totalEpisodes) {
            val end = minOf(start + groupSize - 1, totalEpisodes)
            groups.add(start to end)
            start = end + 1
        }
        return groups
    }

    /**
     * 第 3 轮收尾 B-P2-5：0-based 集号所属的分组下标（每组 [groupSize] 集）。
     * 分组边界只由组大小定，与总集数无关（详情页展示时再按实际组数钳制），
     * 所以断点集号在搜索结果回来前就能算出分组。
     */
    fun groupIndexForEpisode(episodeIndex: Int, groupSize: Int = 20): Int =
        episodeIndex.coerceAtLeast(0) / groupSize.coerceAtLeast(1)
}
