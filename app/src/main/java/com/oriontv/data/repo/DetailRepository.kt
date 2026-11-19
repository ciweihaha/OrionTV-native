package com.oriontv.data.repo

import com.oriontv.data.api.AggApi
import com.oriontv.data.api.AggDetailItem
import com.oriontv.data.api.AggVideoItem
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
import java.util.concurrent.ConcurrentHashMap
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
    private val aggApi: AggApi,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * agg 详情结果的进程内缓存（第 72 轮）：只做失败兜底，不做优先命中——
     * 正常路径仍每次走网络（新集数不被缓存拖旧）；网络失败或空结果时才用
     * 缓存顶上去。播放页按标题重搜、聚合侧偶发掉标题时，刚在详情页/上一集
     * 搜到过的片不再直接报“没有找到相关内容”。TTL 见
     * [DetailSearchLogic.AGG_DETAIL_CACHE_TTL_MS]。
     */
    private data class CachedAggDetail(val results: List<SearchResult>, val savedAtMs: Long)

    private val aggDetailCache = ConcurrentHashMap<String, CachedAggDetail>()

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
        if (baseUrl.isBlank()) {
            // 未配服务器：点播走硬编码 agg 直连（用户已核对：聚合地址 37 个站），
            // 不再报“未配置服务器地址”挡详情；登录/收藏仍走 MoonTV（见 AuthRepository），不在此处改。
            searchDetailViaAgg(q, id, onUpdate)
            return@withContext
        }
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
            // 第 76 轮 v2.0.14：MoonTV 全量搜索 0 条时回落 agg 直取——首页卡片
            // 本来就来自 agg（vod_id 是 `站点~编号`），MoonTV 的源里没有该标题
            // 时详情直接报“没有找到相关内容”，就是用户报的“很多点进去没有资源”。
            // agg 回落也失败/空时才按原样报错（网络错原样抛，不报成“没内容”）。
            val aggResults = try {
                fetchAggDetailResults(q, id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (aggResults.isNotEmpty()) {
                onUpdate(aggResults, true)
            } else {
                throw preferredError ?: IllegalStateException("没有找到相关内容")
            }
        }
        // 首选源已有结果但全量搜索 0 条：保持首选源结果，标记加载完成。
        if (preferredResults.isNotEmpty() && merged.isEmpty()) {
            onUpdate(DetailSearchLogic.dedupeBySource(preferredResults), true)
        }
    }

    /**
     * 未配服务器时的详情回落：agg 直连（`ac=detail`，见 AggApi）。
     * ① id 直取：agg 卡片的 vod_id 是 `站点~编号`；② id 不是 agg 形式
     *（豆瓣卡片的 id 就是标题、收藏/记录里的服务端 id）时按标题在 agg 里搜，
     * 精确标题匹配才认；两路都空或网络失败时先用进程内缓存兜底（第 72 轮，
     * 见类头注释），缓存也没有才报“没有找到相关内容”（网络错原样抛，
     * 由 DetailViewModel 落“加载失败，请检查网络后重试”）。
     */
    private suspend fun searchDetailViaAgg(
        q: String,
        id: String?,
        onUpdate: (results: List<SearchResult>, allLoaded: Boolean) -> Unit,
    ) {
        val cacheKey = DetailSearchLogic.aggDetailCacheKey(q, id)
        // 第 72 轮：网络失败/空结果时用进程内缓存顶上（见类头注释）——
        // 取消异常原样重抛；其他异常先记下，缓存未命中时再原样抛出，
        // 不把网络错误报成“没有找到相关内容”。
        var lastError: Exception? = null
        val results = try {
            fetchAggDetailResults(q, id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e
            null
        }
        if (!results.isNullOrEmpty()) {
            aggDetailCache[cacheKey] =
                CachedAggDetail(results, System.currentTimeMillis())
            // agg 只有这一组源，没有更多源可合并，allLoaded 直接为真。
            onUpdate(results, true)
            return
        }
        val cached = aggDetailCache[cacheKey]
        val fallback = DetailSearchLogic.selectAggDetailFallback(
            cached?.results,
            cached?.savedAtMs ?: 0L,
            System.currentTimeMillis(),
        )
        if (fallback != null) {
            onUpdate(fallback, true)
            return
        }
        if (lastError != null) throw lastError
        throw IllegalStateException("没有找到相关内容")
    }

    /**
     * agg 详情拉取（searchDetailViaAgg 的网络部分）：① id 直取（`ac=detail`），
     * ② id 不是 agg 形式时按标题在 agg 里搜、精确标题匹配才认；两路都空
     * 返回空列表（由调用方决定是报“没有找到相关内容”还是用缓存兜底）。
     * 网络错原样抛（调用方落“加载失败，请检查网络后重试”）。
     */
    private suspend fun fetchAggDetailResults(
        q: String,
        id: String?,
    ): List<SearchResult> {
        var detailItem: AggDetailItem? = null
        if (!id.isNullOrBlank() && id.contains("~")) {
            detailItem = try {
                aggApi.videoDetail(id).list?.firstOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        if (detailItem == null) {
            val candidates = aggApi.searchVideos(q).list.orEmpty()
            val match = DetailSearchLogic.findAggTitleMatch(candidates, q)
            val matchId = match?.vod_id
            detailItem = if (!matchId.isNullOrBlank()) {
                aggApi.videoDetail(matchId).list?.firstOrNull()
            } else {
                null
            }
        }
        val item = detailItem ?: return emptyList()
        val results = DetailSearchLogic.mapAggDetailToResults(item)
        if (results.isEmpty() || results.all { it.episodes.isEmpty() }) {
            return emptyList()
        }
        return results
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
        // B36-P2-3：服务端资源列表为空 = 零视频源（MoonTvApi.getResources
        // 空体直接返空列表），不是“搜不到”——抛可区分的错，searchDetail
        // 末尾不再误报“没有找到相关内容”；有首选源结果时走 B14-P2-3 同款
        // 降级返回（首选源已搜到结果时资源列表理论上非空，纯防御）。
        if (sites.isEmpty()) {
            if (preferredResults.isNotEmpty()) {
                return@coroutineScope DetailSearchLogic.dedupeBySource(preferredResults)
            }
            throw IllegalStateException("服务器未配置视频源")
        }
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
     * agg 详情缓存的兜底有效期（第 72 轮）：30 分钟——只做失败兜底，不做
     * 优先命中，过期即不用；新集数最多晚 30 分钟才在“失败兜底”路径里
     * 出现，正常网络路径每次都取最新，不受它影响。
     */
    const val AGG_DETAIL_CACHE_TTL_MS = 30 * 60 * 1000L

    /**
     * agg 详情缓存的键（可单测，第 72 轮）：agg 复合 id（`站点~编号`）直接
     * 按 id 区分——同一部片换站点的 id 不同，各自缓存；非 agg 形式 id
     *（豆瓣卡片的 id 就是标题）按归一化标题区分。
     */
    fun aggDetailCacheKey(q: String, id: String?): String =
        if (!id.isNullOrBlank() && id.contains("~")) "agg:id:$id"
        else "agg:q:${MoonTvApi.normalizeTitle(q)}"

    /**
     * agg 详情缓存是否可用作失败兜底（可单测，第 72 轮）：有结果且在
     * 有效期内才用；空结果、过期、从未缓存过都返回 null（调用方按原样报错）。
     */
    fun selectAggDetailFallback(
        cached: List<SearchResult>?,
        cachedAtMs: Long,
        nowMs: Long,
    ): List<SearchResult>? =
        if (!cached.isNullOrEmpty() && nowMs - cachedAtMs <= AGG_DETAIL_CACHE_TTL_MS) cached
        else null

    /**
     * 第 70 轮：stripHtml 的两个 Regex 只编一次——原来每调一次 stripHtml
     *（每条 agg 详情简介）都现编两个 Regex；与 MoonTvApi.normalizeTitle 同款问题。
     */
    private val HTML_TAG_REGEX = Regex("<[^>]*>")
    private val WHITESPACE_REGEX = Regex("\\s+")

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

    /** agg 播放组：一个 vod_play_from 对应一组分集 URL。 */
    data class AggPlaySource(val name: String, val episodes: List<String>)

    /**
     * CMS 播放串解析（可单测）：`vod_play_from` 以 `$$$` 分组，与 `vod_play_url`
     * 的组一一对应；组内分集以 `#` 分隔，每集 `名称$地址`（只按第一个 `$` 切，
     * 地址里可能还有 `$`）；没有 `$` 的分集若是 http(s) 地址本身即当地址，
     * 否则丢弃；地址为空的分集丢弃；空组丢弃。from 为空时视作单组（名 "agg"）。
     */
    fun parseAggPlaySources(vodPlayFrom: String, vodPlayUrl: String): List<AggPlaySource> {
        if (vodPlayUrl.isBlank()) return emptyList()
        val fromGroups = if (vodPlayFrom.isBlank()) listOf("agg") else vodPlayFrom.split("$$$")
        val urlGroups = vodPlayUrl.split("$$$")
        val count = minOf(fromGroups.size, urlGroups.size)
        return (0 until count).mapNotNull { i ->
            val name = fromGroups[i].trim().ifBlank { "agg" }
            val episodes = urlGroups[i].split("#").mapNotNull { entry ->
                val trimmed = entry.trim()
                if (trimmed.isBlank()) return@mapNotNull null
                val dollar = trimmed.indexOf('$')
                val url = if (dollar < 0) {
                    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else return@mapNotNull null
                } else {
                    trimmed.substring(dollar + 1).trim()
                }
                url.ifBlank { null }
            }
            if (episodes.isEmpty()) null else AggPlaySource(name, episodes)
        }
    }

    /**
     * agg 详情 → 详情页多源结果（可单测）：每组一个源，源键 `agg:<播放组>`、
     * 源名即播放组名（详情页源 chip 显示它）；集数即该组分集 URL，播放页直接播。
     */
    fun mapAggDetailToResults(item: AggDetailItem): List<SearchResult> =
        parseAggPlaySources(item.vod_play_from, item.vod_play_url).map { group ->
            SearchResult(
                id = item.vod_id,
                title = item.vod_name,
                poster = item.vod_pic,
                episodes = group.episodes,
                source = "agg:${group.name}",
                source_name = group.name,
                class_ = item.vod_class?.takeIf { it.isNotBlank() },
                year = item.vod_year?.takeIf { it.isNotBlank() },
                desc = stripHtml(item.vod_content),
                type_name = item.type_name?.takeIf { it.isNotBlank() },
            )
        }

    /**
     * agg 列表里精确标题匹配（可单测）：服务端 `wd` 是模糊搜（搜“阿龙2025”
     * 也会返回“阿龙”），归一化后相等才认，错片不认；无精确匹配返回 null。
     */
    fun findAggTitleMatch(items: List<AggVideoItem>, query: String): AggVideoItem? {
        val normalized = MoonTvApi.normalizeTitle(query)
        if (normalized.isBlank()) return null
        return items.firstOrNull { MoonTvApi.normalizeTitle(it.vod_name) == normalized }
    }

    /**
     * agg 列表项 → 搜索页结果（可单测）：源固定 "agg"，id 用聚合侧 vod_id
     *（进详情时走 agg 直取）；集数是列表接口没有的，详情页再取。
     */
    fun mapAggItemToSearchResult(item: AggVideoItem): SearchResult = SearchResult(
        id = item.vod_id,
        title = item.vod_name,
        poster = item.vod_pic,
        episodes = emptyList(),
        source = "agg",
        source_name = item.type_name?.takeIf { it.isNotBlank() } ?: "聚合",
        class_ = item.vod_class?.takeIf { it.isNotBlank() },
        year = item.vod_year?.takeIf { it.isNotBlank() },
        type_name = item.type_name?.takeIf { it.isNotBlank() },
    )

    /** 去 HTML 标签（可单测）：agg 简介是 `<p>…<br/>…`，详情页只显示纯文本。 */
    internal fun stripHtml(html: String?): String? {
        if (html.isNullOrBlank()) return null
        return html.replace(HTML_TAG_REGEX, " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .takeIf { it.isNotEmpty() }
    }
}
