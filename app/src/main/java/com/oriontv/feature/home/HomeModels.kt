package com.oriontv.feature.home

import com.oriontv.data.api.AggVideoItem
import com.oriontv.data.api.DoubanItem
import com.oriontv.data.api.MoonTvApi
import com.oriontv.data.api.PlayRecord

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
)

/** 首页 7 个点播分类（ui-design.md §5.1；CMS 类型对照 plan 附录 B）。 */
enum class HomeCategory(
    val title: String,
    /** agg 的 t：1 电影、2 电视剧、3 动漫、4 综艺；null = 豆瓣/本地模式。 */
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

/** agg 项 → 首页卡片（plan 附录 B：source 保留 "agg"，进详情走标题搜索）。 */
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
    title.lowercase().replace(Regex("\\s+"), "")

/** agg 翻页追加时按归一化片名去重（同一部片可能在不同页以不同 vod_id 前缀重复出现）。 */
fun dedupeAggItems(items: List<HomeRowItem>): List<HomeRowItem> {
    val seen = LinkedHashSet<String>()
    return items.filter { seen.add(normalizeTitle(it.title)) }
}

/** “剩余 xx 分钟”：总时长 − 已播，向上取整分钟；播完/无时长时返回 null。 */
fun remainingMinutesLabel(playTimeSeconds: Long, totalTimeSeconds: Long): String? {
    val remaining = totalTimeSeconds - playTimeSeconds
    if (totalTimeSeconds <= 0 || remaining <= 0) return null
    val minutes = (remaining + 59) / 60
    return "剩余 $minutes 分钟"
}
