package com.oriontv.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCacheTest {

    private fun item(title: String) = HomeRowItem(id = title, source = "agg", title = title, poster = "")

    @Test
    fun `hit within ttl and miss after expiry`() {
        var now = 0L
        val cache = HomeCache(nowMs = { now })
        cache.put("电影", listOf(item("A")))
        assertEquals(listOf(item("A")), cache.get("电影"))
        now += HomeCache.CACHE_TTL_MS
        assertEquals(listOf(item("A")), cache.get("电影"))
        now += 1
        assertNull(cache.get("电影"))
    }

    @Test
    fun `evicts least recently used category over ten`() {
        val cache = HomeCache()
        for (i in 0 until 10) cache.put("cat$i", listOf(item("x$i")))
        cache.get("cat0") // 访问 cat0，让它变新
        cache.put("cat10", listOf(item("x10")))
        assertEquals(listOf(item("x0")), cache.get("cat0"))
        assertNull(cache.get("cat1")) // 最久未访问的被淘汰
        assertEquals(listOf(item("x10")), cache.get("cat10"))
    }

    @Test
    fun `caps items per category at forty`() {
        val cache = HomeCache()
        cache.put("电影", (1..50).map { item("片$it") })
        assertEquals(40, cache.get("电影")?.size)
    }

    @Test
    fun `source kind is stored with page one and returned on hit`() {
        val cache = HomeCache()
        cache.put("电影", listOf(item("A")), RowSource.CMS)
        assertEquals(RowSource.CMS, cache.getSourceKind("电影"))
        cache.put("热门剧集", listOf(item("B")), RowSource.DOUBAN)
        assertEquals(RowSource.DOUBAN, cache.getSourceKind("热门剧集"))
    }

    @Test
    fun `source kind is null when not recorded or expired`() {
        var now = 0L
        val cache = HomeCache(nowMs = { now })
        cache.put("电影", listOf(item("A")))
        assertNull(cache.getSourceKind("电影"))
        cache.put("电视剧", listOf(item("B")), RowSource.CMS)
        now += HomeCache.CACHE_TTL_MS + 1
        assertNull(cache.getSourceKind("电视剧"))
        assertNull(cache.get("电视剧"))
    }
}

class HomeModelsTest {

    @Test
    fun `agg score 0_0 is hidden`() {
        val item = mapAggItem(
            com.oriontv.data.api.AggVideoItem(
                vod_id = "1", vod_name = "片", vod_pic = "", vod_douban_score = "0.0",
            ),
        )
        assertNull(item.rate)
        assertEquals("1", item.id)
        assertEquals("agg", item.source)
    }

    @Test
    fun `agg score shown when present`() {
        val item = mapAggItem(
            com.oriontv.data.api.AggVideoItem(
                vod_id = "2", vod_name = "片", vod_pic = "", vod_year = "2024",
                vod_douban_score = "8.5",
            ),
        )
        assertEquals("8.5", item.rate)
        assertEquals("2024", item.year)
    }

    @Test
    fun `douban item id falls back to title`() {
        val item = mapDoubanItem(
            com.oriontv.data.api.DoubanItem(title = "豆瓣片", poster = "p", rate = "9.1"),
        )
        assertEquals("豆瓣片", item.id)
        assertEquals("douban", item.source)
        assertEquals("9.1", item.rate)
    }

    @Test
    fun `play record maps to resume card with progress`() {
        val record = com.oriontv.data.api.PlayRecord(
            title = "剧", source_name = "源A", cover = "c", index = 2,
            total_episodes = 10, play_time = 600, total_time = 2700,
            save_time = 0L, year = "2025",
        )
        val item = mapPlayRecord("srcA+123", record)
        assertEquals("srcA", item.source)
        assertEquals("123", item.id)
        assertEquals(2, item.episodeIndex)
        assertTrue(item.progress!! > 0.22f && item.progress!! < 0.23f)
    }

    @Test
    fun `dedupe agg items by normalized title`() {
        val items = listOf(
            HomeRowItem(id = "a1", source = "agg", title = "庆余年", poster = ""),
            HomeRowItem(id = "b2", source = "agg", title = "庆 余 年", poster = ""),
            HomeRowItem(id = "c3", source = "agg", title = "庆余年第二季", poster = ""),
        )
        val deduped = dedupeAggItems(items)
        assertEquals(listOf("a1", "c3"), deduped.map { it.id })
    }

    @Test
    fun `remaining minutes rounds up`() {
        assertEquals("剩余 35 分钟", remainingMinutesLabel(600, 2700))
        assertEquals("剩余 1 分钟", remainingMinutesLabel(2699, 2700))
        assertNull(remainingMinutesLabel(2700, 2700))
        assertNull(remainingMinutesLabel(0, 0))
    }
}
