package com.oriontv.data.repo

import com.oriontv.data.api.AggDetailItem
import com.oriontv.data.api.AggVideoItem
import com.oriontv.data.api.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailSearchLogicTest {

    private fun result(
        source: String,
        title: String = "庆余年",
        id: String = "1",
        episodes: Int = 5,
    ) = SearchResult(
        id = id,
        title = title,
        poster = "",
        episodes = (1..episodes).map { "http://x/$it.m3u8" },
        source = source,
        source_name = "源$source",
    )

    @Test
    fun `preferred source matches by id first`() {
        val results = listOf(
            result("a", id = "2"),
            result("a", id = "1"),
        )
        val picked = DetailSearchLogic.selectPreferredResults(results, "庆余年", "1")
        assertEquals(listOf("1"), picked.map { it.id })
    }

    @Test
    fun `preferred source falls back to exact title when id misses`() {
        val results = listOf(
            result("a", title = "庆余年第二季", id = "9"),
            result("a", title = "庆余年", id = "8"),
        )
        val picked = DetailSearchLogic.selectPreferredResults(results, "庆余年 ", "100")
        assertEquals(listOf("8"), picked.map { it.id })
    }

    @Test
    fun `preferred source keeps all when neither id nor title matches`() {
        val results = listOf(result("a", title = "其他剧"))
        val picked = DetailSearchLogic.selectPreferredResults(results, "庆余年", "100")
        assertEquals(1, picked.size)
    }

    @Test
    fun `exact title match normalizes whitespace and case`() {
        val results = listOf(
            result("a", title = "庆余年"),
            result("b", title = "庆余年第二季"),
            result("c", title = " 庆 余 年 "),
        )
        val matched = DetailSearchLogic.filterExactTitle(results, "庆余年")
        assertEquals(listOf("a", "c"), matched.map { it.source })
    }

    @Test
    fun `dedupe keeps first result per source`() {
        val results = listOf(
            result("a", title = "庆余年"),
            result("a", title = "庆余年第二季"),
            result("b", title = "庆余年"),
        )
        val deduped = DetailSearchLogic.dedupeBySource(results)
        assertEquals(listOf("a", "b"), deduped.map { it.source })
        assertEquals("庆余年", deduped[0].title)
    }

    @Test
    fun `next available source skips failed and short episode lists`() {
        val results = listOf(
            result("a", episodes = 10),
            result("b", episodes = 2), // 集数不够当前集
            result("c", episodes = 10),
        )
        val next = DetailSearchLogic.getNextAvailableSource(
            results, currentSource = "a", episodeIndex = 5, failedSources = setOf("c"),
        )
        assertNull(next)
        val next2 = DetailSearchLogic.getNextAvailableSource(
            results, currentSource = "a", episodeIndex = 1, failedSources = setOf("c"),
        )
        assertEquals("b", next2?.source)
    }

    @Test
    fun `episode groups fold long series by twenty`() {
        assertEquals(listOf(1 to 20, 21 to 40, 41 to 45), DetailSearchLogic.episodeGroups(45))
        assertEquals(listOf(1 to 12), DetailSearchLogic.episodeGroups(12))
        assertEquals(emptyList<Pair<Int, Int>>(), DetailSearchLogic.episodeGroups(0))
    }

    // 第 3 轮收尾 B-P2-5：断点集所属分组。
    @Test
    fun `groupIndexForEpisode maps resume episode to its group`() {
        assertEquals(0, DetailSearchLogic.groupIndexForEpisode(0))
        assertEquals(0, DetailSearchLogic.groupIndexForEpisode(19))
        assertEquals(1, DetailSearchLogic.groupIndexForEpisode(20))
        assertEquals(1, DetailSearchLogic.groupIndexForEpisode(24))
        assertEquals(2, DetailSearchLogic.groupIndexForEpisode(40))
        assertEquals(0, DetailSearchLogic.groupIndexForEpisode(-3))
    }

    @Test
    fun `parseAggPlaySources splits groups and episodes`() {
        val sources = DetailSearchLogic.parseAggPlaySources(
            "lzm3u8\$\$\$liangzi",
            "国语中字\$https://a/1.m3u8#粤语中字\$https://a/2.m3u8\$\$\$国语中字\$https://b/1.m3u8",
        )
        assertEquals(2, sources.size)
        assertEquals("lzm3u8", sources[0].name)
        assertEquals(listOf("https://a/1.m3u8", "https://a/2.m3u8"), sources[0].episodes)
        assertEquals("liangzi", sources[1].name)
        assertEquals(listOf("https://b/1.m3u8"), sources[1].episodes)
    }

    @Test
    fun `parseAggPlaySources drops entries without url and empty groups`() {
        val sources = DetailSearchLogic.parseAggPlaySources(
            "lzm3u8\$\$\$empty",
            "正片\$#第2集\$https://a/2.m3u8\$\$\$",
        )
        assertEquals(1, sources.size)
        assertEquals(listOf("https://a/2.m3u8"), sources[0].episodes)
    }

    @Test
    fun `parseAggPlaySources accepts bare http url without name`() {
        val sources = DetailSearchLogic.parseAggPlaySources("", "https://a/1.m3u8")
        assertEquals(1, sources.size)
        assertEquals("agg", sources[0].name)
        assertEquals(listOf("https://a/1.m3u8"), sources[0].episodes)
    }

    @Test
    fun `parseAggPlaySources returns empty when no play url`() {
        assertEquals(
            emptyList<DetailSearchLogic.AggPlaySource>(),
            DetailSearchLogic.parseAggPlaySources("lzm3u8", ""),
        )
    }

    @Test
    fun `mapAggDetailToResults makes one source per play group`() {
        val item = AggDetailItem(
            vod_id = "yingshigongchang~111482",
            vod_name = "阿龙2025",
            vod_pic = "https://p/1.jpg",
            vod_year = "2025",
            vod_play_from = "lzm3u8\$\$\$liangzi",
            vod_play_url = "国语中字\$https://a/1.m3u8\$\$\$国语中字\$https://b/1.m3u8",
            vod_content = "<p>简介</p>",
        )
        val results = DetailSearchLogic.mapAggDetailToResults(item)
        assertEquals(listOf("agg:lzm3u8", "agg:liangzi"), results.map { it.source })
        assertEquals(listOf("lzm3u8", "liangzi"), results.map { it.source_name })
        assertEquals("阿龙2025", results[0].title)
        assertEquals("简介", results[0].desc)
        assertEquals("2025", results[0].year)
    }

    @Test
    fun `findAggTitleMatch only accepts exact normalized title`() {
        val items = listOf(
            AggVideoItem(vod_id = "s~1", vod_name = "阿龙"),
            AggVideoItem(vod_id = "s~2", vod_name = "阿龙2025"),
        )
        assertEquals("s~2", DetailSearchLogic.findAggTitleMatch(items, "阿龙2025")?.vod_id)
        assertNull(DetailSearchLogic.findAggTitleMatch(items, "阿龙2026"))
    }

    @Test
    fun `findAggTitleMatches returns all exact matches in order`() {
        // v2.0.19：id 直取的片 0 集时按标题重试要逐个试候选，只留第一条不够。
        val items = listOf(
            AggVideoItem(vod_id = "45_1", vod_name = "哪吒"),
            AggVideoItem(vod_id = "s~2", vod_name = "阿龙2025"),
            AggVideoItem(vod_id = "46_2", vod_name = "哪 吒"),
            AggVideoItem(vod_id = "s~3", vod_name = "哪吒2"),
        )
        assertEquals(
            listOf("45_1", "46_2"),
            DetailSearchLogic.findAggTitleMatches(items, "哪吒").map { it.vod_id },
        )
        assertTrue(DetailSearchLogic.findAggTitleMatches(items, "").isEmpty())
        assertTrue(DetailSearchLogic.findAggTitleMatches(items, "没有这片").isEmpty())
    }

    @Test
    fun `mapAggItemToSearchResult keeps agg vod id and source`() {
        val result = DetailSearchLogic.mapAggItemToSearchResult(
            AggVideoItem(vod_id = "s~1", vod_name = "阿龙2025", vod_pic = "https://p/1.jpg", type_name = "电影"),
        )
        assertEquals("s~1", result.id)
        assertEquals("agg", result.source)
        assertEquals("电影", result.source_name)
    }

    @Test
    fun `stripHtml removes tags and entities`() {
        assertEquals("简介 文本", DetailSearchLogic.stripHtml("<p>简介</p><br/>文本&nbsp;"))
        assertNull(DetailSearchLogic.stripHtml(null))
        assertNull(DetailSearchLogic.stripHtml("  "))
    }

    // 第 72 轮：agg 详情缓存（失败兜底）的键与有效期判定。

    @Test
    fun `isAggregationId accepts agg and vod-api id forms only`() {
        // v2.0.18：agg `站点~编号`、vod-api `站号_编号` / `M_...` 都是聚合 id；
        // 豆瓣卡片的 id 就是标题、MoonTV 服务端 id 是纯数字，都不是聚合 id。
        assertTrue(DetailSearchLogic.isAggregationId("kanju~av_1IFcMOEBXV2"))
        assertTrue(DetailSearchLogic.isAggregationId("45_53656"))
        assertTrue(DetailSearchLogic.isAggregationId("M_W1siMl8yNDQ5OSIs"))
        assertFalse(DetailSearchLogic.isAggregationId("阿龙2025"))
        assertFalse(DetailSearchLogic.isAggregationId("35729"))
        assertFalse(DetailSearchLogic.isAggregationId(null))
        assertFalse(DetailSearchLogic.isAggregationId(""))
        assertFalse(DetailSearchLogic.isAggregationId("   "))
    }

    @Test
    fun `dedupeBySourcePreferPlayable keeps playable over zero-episode same source`() {
        // v2.0.18：首选源 0 集结果不能把全量搜索里同源可播的结果挡掉。
        val zero = result("r1", episodes = 0)
        val playable = result("r1", episodes = 3)
        val other = result("r2", episodes = 2)
        assertEquals(
            listOf(playable, other),
            DetailSearchLogic.dedupeBySourcePreferPlayable(listOf(zero, playable, other)),
        )
        // 同源都可播时留第一条；同源全 0 集时留第一条。
        assertEquals(
            listOf(playable, other),
            DetailSearchLogic.dedupeBySourcePreferPlayable(listOf(playable, result("r1", episodes = 9), other)),
        )
        assertEquals(
            listOf(zero),
            DetailSearchLogic.dedupeBySourcePreferPlayable(listOf(zero, result("r1", episodes = 0))),
        )
    }

    @Test
    fun `aggDetailCacheKey uses vod-api id form too`() {
        // v2.0.18：vod-api 的 `站号_编号` / `M_...` 也按 id 区分缓存。
        assertEquals(
            "agg:id:45_53656",
            DetailSearchLogic.aggDetailCacheKey("借枪", "45_53656"),
        )
    }

    @Test
    fun `aggDetailCacheKey uses composite id when present`() {
        assertEquals(
            "agg:id:api_43~160299",
            DetailSearchLogic.aggDetailCacheKey("联邦调查局第九季", "api_43~160299"),
        )
    }

    @Test
    fun `aggDetailCacheKey falls back to normalized title`() {
        assertEquals(
            "agg:q:阿龙2025",
            DetailSearchLogic.aggDetailCacheKey(" 阿龙 2025 ", "阿龙2025"),
        )
        assertEquals(
            "agg:q:阿龙2025",
            DetailSearchLogic.aggDetailCacheKey("阿龙2025", null),
        )
        assertEquals(
            "agg:q:阿龙2025",
            DetailSearchLogic.aggDetailCacheKey("阿龙2025", ""),
        )
    }

    @Test
    fun `selectAggDetailFallback returns fresh cache`() {
        val cached = listOf(result("agg:jinyingyun", episodes = 7))
        val now = 1_000_000L
        assertEquals(
            cached,
            DetailSearchLogic.selectAggDetailFallback(
                cached,
                now - DetailSearchLogic.AGG_DETAIL_CACHE_TTL_MS,
                now,
            ),
        )
    }

    @Test
    fun `selectAggDetailFallback rejects expired or empty cache`() {
        val cached = listOf(result("agg:jinyingyun", episodes = 7))
        val now = 1_000_000L
        assertNull(
            DetailSearchLogic.selectAggDetailFallback(
                cached,
                now - DetailSearchLogic.AGG_DETAIL_CACHE_TTL_MS - 1L,
                now,
            ),
        )
        assertNull(DetailSearchLogic.selectAggDetailFallback(emptyList(), now, now))
        assertNull(DetailSearchLogic.selectAggDetailFallback(null, now, now))
    }
}
