package com.oriontv.feature.home

import com.oriontv.data.api.AggVideoItem
import com.oriontv.data.api.DoubanItem
import com.oriontv.data.api.MoonTvApi
import com.oriontv.data.api.PlayRecord

/**
 * 第 70 轮：归一化空白 Regex 只编一次——normalizeTitle 原来每调一次都现编；
 * 首页去重（dedupeAggItems / appendPageItems）是逐卡调它，翻页追加即数百次编译。
 */
private val WHITESPACE_REGEX = Regex("\\s+")

/**
 * 首页一行里的卡片（agg / 豆瓣 / 最近播放统一成这一个形状）。
 * source="agg" | "douban" | 真实源 key；douban 项没有真实 id，id 取标题，
 * 进详情时只按标题全量搜索（不走单源搜索）。
 */
data class HomeRowItem(
    val id: String,
    val source: String,
    val title: String,
    val poster: String,
    val year: String? = null,
    /** 评分：vod_douban_score / douban rate，"0.0" 不显示。 */
    val rate: String? = null,
    val area: String? = null,
    val sourceName: String? = null,
    /** 最近播放：已播进度 0..1（null 表示不是断点卡片）。 */
    val progress: Float? = null,
    /** 最近播放：第 N 集（0-based）。 */
    val episodeIndex: Int = 0,
    val totalEpisodes: Int = 0,
    /** 最近播放：已播秒数 / 总时长秒数（算“剩余 xx 分钟”用）。 */
    val playTimeSeconds: Long = 0L,
    val totalTimeSeconds: Long = 0L,
)

/** 首页一行：标题 + 卡片列表。 */
data class HomeRow(
    val title: String,
    val items: List<HomeRowItem>,
    /** P2-9：单行加载失败时的占位文案（null = 正常行）。 */
    val error: String? = null,
    /** 分类浏览分页：是否还有下一页可取（最近播放行与无分页的行恒为 false）。 */
    val hasMore: Boolean = false,
    /** 分类浏览分页：是否正在取下一页。 */
    val isLoadingMore: Boolean = false,
    /** 分类浏览分页：取下一页失败时的文案（null = 没失败过；失败不丢已有卡片，移开焦点再回来即重试）。 */
    val loadMoreError: String? = null,
)

/** 首页 7 个点播分类（ui-design.md §5.1；CMS 类型对照 plan 附录 B）。 */
enum class HomeCategory(
    val title: String,
    /** 聚合 CMS 的 t：1 电影、2 电视剧、3 动漫、4 综艺；null = 豆瓣/本地模式。 */
    val cmsType: Int?,
    /** 豆瓣模式的 type：movie | tv。 */
    val doubanType: String?,
    /** 豆瓣模式的 tag。 */
    val doubanTag: String?,
    /** CMS 失败/首屏空列表时的回落豆瓣 tag（plan 附录 B）。 */
    val fallbackTag: String?,
) {
    RECENT("最近播放", null, null, null, null),
    HOT("热门剧集", null, "tv", "热门", null),
    TV("电视剧", 2, "tv", "国产剧", "国产剧"),
    MOVIE("电影", 1, "movie", "热门", "热门"),
    ANIME("动漫", 3, "tv", "日本动画", "日本动画"),
    VARIETY("综艺", 4, "tv", "综艺", "综艺"),
    TOP250("豆瓣 Top250", null, "movie", "top250", null),
}

/** 聚合项 → 首页卡片（plan 附录 B：source 保留 "agg"，进详情走标题搜索）。 */
fun mapAggItem(item: AggVideoItem): HomeRowItem = HomeRowItem(
    id = item.vod_id.orEmpty(),
    source = "agg",
    title = item.vod_name,
    poster = item.vod_pic,
    year = item.vod_year?.takeIf { it.isNotBlank() },
    rate = item.vod_douban_score?.takeIf { it.isNotBlank() && it != "0.0" },
    area = item.vod_area?.takeIf { it.isNotBlank() },
    sourceName = item.type_name?.takeIf { it.isNotBlank() },
)

/** 豆瓣项 → 首页卡片（无真实 id，id 取标题；进详情只按标题搜）。 */
fun mapDoubanItem(item: DoubanItem): HomeRowItem = HomeRowItem(
    id = item.title,
    source = "douban",
    title = item.title,
    poster = item.poster,
    rate = item.rate?.takeIf { it.isNotBlank() && it != "0.0" },
)

/** 播放记录 → 最近播放断点卡片（记录键 `${source}+${id}` 拆回 source/id）。 */
fun mapPlayRecord(key: String, record: PlayRecord): HomeRowItem {
    // D-P2-3：拆键统一走 MoonTvApi.splitKey（最后一个 + 切分 + 反转义）。
    val (source, id) = MoonTvApi.splitKey(key)
    return HomeRowItem(
        id = id,
        source = source,
        title = record.title,
        poster = record.cover,
        year = record.year?.takeIf { it.isNotBlank() },
        sourceName = record.source_name,
        progress = if (record.total_time > 0) {
            (record.play_time.toFloat() / record.total_time).coerceIn(0f, 1f)
        } else {
            null
        },
        episodeIndex = record.index,
        totalEpisodes = record.total_episodes,
        playTimeSeconds = record.play_time,
        totalTimeSeconds = record.total_time,
    )
}

/** 归一化片名（去空白小写）：agg 翻页去重用，vod_id 不可当全局唯一键。 */
fun normalizeTitle(title: String): String =
    title.lowercase().replace(WHITESPACE_REGEX, "")

/** agg 翻页追加时按归一化片名去重（同一部片可能在不同页以不同 vod_id 前缀重复出现）。 */
fun dedupeAggItems(items: List<HomeRowItem>): List<HomeRowItem> {
    val seen = LinkedHashSet<String>()
    return items.filter { seen.add(normalizeTitle(it.title)) }
}

/**
 * 分类浏览分页：把新取回的一页追加到已有卡片后（纯函数，可单测）。
 * 已有卡片优先——新页里与已有卡片同名（归一化片名）的丢弃，不顶掉旧卡；
 * 新页内部也去重。追加后总长度不截断（分页行不走 HomeCache 的 40 条上限，
 * 缓存只存第 1 页）。
 */
fun appendPageItems(existing: List<HomeRowItem>, page: List<HomeRowItem>): List<HomeRowItem> {
    if (page.isEmpty()) return existing
    val seen = LinkedHashSet<String>()
    existing.forEach { seen.add(normalizeTitle(it.title)) }
    return existing + page.filter { seen.add(normalizeTitle(it.title)) }
}

/** “剩余 xx 分钟”：总时长 − 已播，向上取整分钟；播完/无时长时返回 null。 */
fun remainingMinutesLabel(playTimeSeconds: Long, totalTimeSeconds: Long): String? {
    val remaining = totalTimeSeconds - playTimeSeconds
    if (totalTimeSeconds <= 0 || remaining <= 0) return null
    val minutes = (remaining + 59) / 60
    return "剩余 $minutes 分钟"
}

/** 返回焦点恢复用的条目键（纯函数，可单测）：“行标题 + source+id”。 */
fun homeFocusKey(rowTitle: String, item: HomeRowItem): String =
    "$rowTitle\u0001${item.source}+${item.id}"

/**
 * P2-9（第 5 轮）：按条目键找返回焦点位置（纯函数，可单测）。
 * 先在同名行里找，找不到再在任意行里找同 source+id 的卡片（同一部片
 * 可能同时在“最近播放”与分类行里），都找不到返回 null（调用方回落
 * 首 chip）。返回 (行 index, 卡片 index)。
 */
fun findSavedCard(rows: List<HomeRow>, savedKey: String?): Pair<Int, Int>? {
    if (savedKey == null) return null
    val parts = savedKey.split("\u0001", limit = 2)
    val rowTitle = if (parts.size == 2) parts[0] else ""
    val itemKey = if (parts.size == 2) parts[1] else savedKey
    rows.forEachIndexed { rowIndex, row ->
        if (row.title == rowTitle) {
            val cardIndex = row.items.indexOfFirst { "${it.source}+${it.id}" == itemKey }
            if (cardIndex >= 0) return rowIndex to cardIndex
        }
    }
    rows.forEachIndexed { rowIndex, row ->
        val cardIndex = row.items.indexOfFirst { "${it.source}+${it.id}" == itemKey }
        if (cardIndex >= 0) return rowIndex to cardIndex
    }
    return null
}

/**
 * C12-P1-1：首页错误态“重试”成功后是否应再跑一次焦点恢复（纯函数，可单测）——
 * 首载失败时 effect A 的 !initialFocusDone 分支已把 restoreDone 置 true，
 * 重试成功后行由空变非空、错误页“重试”按钮被卸载、焦点丢失，effect A 的
 * 另两条分支都不跑；此时（行由空变非空且恢复已做过）再跑一次恢复（回保存
 * 卡片或首 chip）。常驻期间行变化（refreshRecent）不满足“由空变非空”，不抢焦点。
 */
internal fun shouldRecoverFocusAfterRetry(
    rowsWereEmpty: Boolean,
    hasRows: Boolean,
    restoreDone: Boolean,
): Boolean = rowsWereEmpty && hasRows && restoreDone

/**
 * C21-P2-1：首页首载失败时是否应落焦错误页“重试”按钮（纯函数，可单测）——
 * 有错误且行空时唯一的可用操作就是“重试”，落首 chip 须多按一次下键；
 * 有行时（行内失败留占位行、B-P2-20）不抢，正常走首 chip/保存卡片恢复。
 * effect A 的 !initialFocusDone 分支与 effect B 的首进分支共用。
 */
internal fun shouldFocusHomeRetry(
    error: String?,
    rows: List<HomeRow>,
): Boolean = error != null && rows.isEmpty()

/**
 * C22-P2-1：错误在首进落焦之后才到达时是否应落焦错误页“重试”按钮（纯函数，
 * 可单测）——C21-P2-1 的修法只在 effect A 的首进分支里查 shouldFocusHomeRetry，
 * 但首帧组合时 error 仍是 null（refresh() 的加载即挂起），
 * 首进分支恒走落首 chip、initialFocusDone 置真；错误到达时 effect A 的 key
 * 里没有 error、effect B 的 key 也不变，两条路都不重跑。本谓词钉住接线：
 * 同一失败只触发一次（lastErrorFocused 去重，详情页 C4-P1-4 同款；error 清掉
 * 后调用方把门控复位为 null，下一次同文案失败可再次触发），行非空时不抢。
 */
internal fun shouldFocusHomeRetryOnErrorArrival(
    error: String?,
    rows: List<HomeRow>,
    lastErrorFocused: String?,
): Boolean = error != null && error != lastErrorFocused && shouldFocusHomeRetry(error, rows)
