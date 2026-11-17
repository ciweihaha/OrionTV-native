package com.oriontv

import com.oriontv.data.api.AggVideoItem
import com.oriontv.data.api.CircuitBreaker
import com.oriontv.data.api.MoonTvApi
import com.oriontv.data.api.SearchResult
import com.oriontv.data.repo.DetailSearchLogic
import com.oriontv.feature.home.HomeRowItem
import com.oriontv.feature.home.RowPagingState
import com.oriontv.feature.home.RowSource
import com.oriontv.feature.home.appendPageItems
import com.oriontv.feature.home.dedupeAggItems
import com.oriontv.feature.live.EpgParser
import com.oriontv.feature.live.M3uParser
import com.oriontv.feature.play.PlaybackGeneration
import com.oriontv.feature.play.SubtitleTrackInfo
import com.oriontv.feature.play.choosePreferredSubtitleTrack
import com.oriontv.feature.play.nextSubtitleSelection
import com.oriontv.feature.search.SearchCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * 压力测试（第 70 轮综合改进⑤）。
 *
 * 平板目前进安全模式且已断开（adb 为空），真机压测（反复切集/换台、长时间运行）
 * 等用户重连后再上平板做；这里先在 JVM 层压纯逻辑——这些正是真机压测时
 * 反复走的路径（M3U/EPG 解析、搜索缓存、分页状态机、agg 播放串解析、
 * 换集代际、字幕选择）。每个用例都计时打印（STRESS …: xx ms），
 * 上限放得很宽（共享核上跑，只防回归性劣化、不做精确基准）。
 */
class StressTest {

    private inline fun timed(name: String, block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        val ms = (System.nanoTime() - start) / 1_000_000
        println("STRESS $name: ${ms}ms")
        return ms
    }

    @Test
    fun `m3u 10000 channels with heavy same-name merging`() {
        val sb = StringBuilder()
        // 5000 个独立台 + 5000 行只用 5 个台名（每名 1000 条线路，专压合并路径）。
        repeat(5_000) { i ->
            sb.append("#EXTINF:-1 tvg-id=\"id$i\" tvg-logo=\"http://x/logo.png\" group-title=\"G\",台$i\n")
            sb.append("http://example.com/$i.m3u8\n")
        }
        repeat(5_000) { i ->
            sb.append("#EXTINF:-1 tvg-id=\"dup${i % 5}\" group-title=\"G\",重播台${i % 5}\n")
            sb.append("http://example.com/dup$i.m3u8\n")
        }
        var channels: List<M3uParser.Channel> = emptyList()
        val ms = timed("m3u-parse-10k-with-merges") { channels = M3uParser.parse(sb.toString()) }
        assertEquals(5_005, channels.size)
        val merged = channels.first { it.name == "重播台0" }
        assertEquals(1_000, merged.urls.size)
        assertTrue("m3u parse too slow: ${ms}ms", ms < 15_000)
    }

    @Test
    fun `epg 5000 programmes 2000 lookups`() {
        val programmes = (0 until 5_000).map { i ->
            EpgParser.Programme(
                channelId = "cctv1",
                title = "节目$i",
                startAt = i * 1_800_000L,
                endAt = (i + 1) * 1_800_000L,
            )
        }
        val data = EpgParser.EpgData(
            programmesByChannel = mapOf("cctv1" to programmes),
            channelNames = mapOf("cctv1" to "CCTV1"),
        )
        var hits = 0
        val ms = timed("epg-2000-now-next-lookups") {
            var now = 0L
            repeat(2_000) {
                val playing = EpgParser.nowPlaying(data, "cctv1", "CCTV1", now)
                if (playing != null) hits++
                assertNotNull(EpgParser.nextProgramme(data, "cctv1", "CCTV1", now))
                now += 997_000L
            }
        }
        assertTrue(hits > 1_900)
        assertTrue("epg lookups too slow: ${ms}ms", ms < 10_000)
    }

    @Test
    fun `search cache churn 10000 terms`() {
        var now = 0L
        val cache = SearchCache(nowMs = { now })
        val results = listOf(SearchResult(id = "1", title = "片", episodes = emptyList()))
        val ms = timed("search-cache-10k-put-get") {
            repeat(10_000) { i ->
                cache.put("词$i", results)
                cache.get("词$i")
                cache.get("词${i / 2}")
            }
        }
        // 最多留 50 个词：最早的词已被逐出，最新的词还在
        assertNull(cache.get("词0"))
        assertNotNull(cache.get("词9999"))
        // TTL：5 分钟后过期
        now += SearchCache.CACHE_TTL_MS + 1
        assertNull(cache.get("词9999"))
        assertTrue("search cache churn too slow: ${ms}ms", ms < 10_000)
    }

    @Test
    fun `row paging state 10000 rapid focus and load cycles`() {
        val state = RowPagingState(RowSource.CMS, cmsType = 1)
        var loads = 0
        val ms = timed("row-paging-10k-cycles") {
            repeat(10_000) { i ->
                // 焦点在行尾附近才触发加载；加载中不重入；取回一页后游标推进。
                if (state.shouldLoadMore(focusedIndex = 17 + (i % 3), totalCount = 20)) {
                    state.onLoadStarted()
                    assertTrue(state.isLoading)
                    state.onPageLoaded(state.advanceCursor(state.nextCursor))
                    loads++
                }
            }
        }
        assertEquals(10_000, loads)
        // 初始游标是第 2 页，每取一页 +1：2 + 10000
        assertEquals(10_002, state.nextCursor)
        assertTrue("row paging cycles too slow: ${ms}ms", ms < 10_000)
    }

    @Test
    fun `agg play string 1500 episodes and title match over 5000 items`() {
        val playUrl = (0 until 3).joinToString("$$$") { g ->
            (0 until 500).joinToString("#") { e -> "第${e + 1}集\$http://cdn.example.com/g$g/e$e.m3u8" }
        }
        var sources: List<DetailSearchLogic.AggPlaySource> = emptyList()
        val parseMs = timed("agg-parse-1500-episodes") {
            repeat(20) { sources = DetailSearchLogic.parseAggPlaySources("lzm3u8\$\$\$liangzi\$\$\$ffm3u8", playUrl) }
        }
        assertEquals(3, sources.size)
        assertEquals(500, sources[0].episodes.size)
        val items = (0 until 5_000).map { i -> AggVideoItem(vod_id = "s~$i", vod_name = "片名$i") }
        var match: AggVideoItem? = null
        val matchMs = timed("agg-title-match-5000x20") {
            repeat(20) { match = DetailSearchLogic.findAggTitleMatch(items, "片名4999") }
        }
        assertEquals("s~4999", match?.vod_id)
        assertTrue("agg parse too slow: ${parseMs}ms", parseMs < 10_000)
        assertTrue("agg title match too slow: ${matchMs}ms", matchMs < 10_000)
    }

    @Test
    fun `stripHtml and normalizeTitle hot paths`() {
        val html = buildString {
            append("<p>")
            repeat(2_000) { append("第${it}集剧情介绍<br/>") }
            append("</p>")
        }
        var stripped: String? = null
        val htmlMs = timed("stripHtml-100KB-x100") {
            repeat(100) { stripped = DetailSearchLogic.stripHtml(html) }
        }
        assertNotNull(stripped)
        assertTrue(stripped!!.length > 10_000)
        var normalized = ""
        val normMs = timed("normalizeTitle-100k") {
            repeat(100_000) { normalized = MoonTvApi.normalizeTitle(" 庆 余 年 第 二 季 ") }
        }
        assertEquals("庆余年第二季", normalized)
        assertTrue("stripHtml too slow: ${htmlMs}ms", htmlMs < 10_000)
        assertTrue("normalizeTitle too slow: ${normMs}ms", normMs < 10_000)
    }

    @Test
    fun `home dedupe over 10000 items`() {
        val items = (0 until 10_000).map { i ->
            HomeRowItem(id = "s~$i", source = "agg", title = "片名${i % 2_000}", poster = "")
        }
        var deduped: List<HomeRowItem> = emptyList()
        val ms = timed("home-dedupe-10k") {
            repeat(10) { deduped = dedupeAggItems(items) }
        }
        assertEquals(2_000, deduped.size)
        val appended = appendPageItems(deduped, items.take(100))
        assertEquals(2_000, appended.size)
        assertTrue("home dedupe too slow: ${ms}ms", ms < 10_000)
    }

    @Test
    fun `subtitle selector 100k operations`() {
        val tracks = listOf(
            SubtitleTrackInfo("中文", "zh", 0, 0),
            SubtitleTrackInfo("English", "en", 1, 0),
            SubtitleTrackInfo("法语", "fra", 2, 0),
        )
        var selected: Int? = null
        val ms = timed("subtitle-100k-choose-cycle") {
            repeat(100_000) {
                selected = choosePreferredSubtitleTrack(tracks)
                selected = nextSubtitleSelection(tracks, selected)
            }
        }
        assertEquals(1, selected)
        assertTrue("subtitle selector too slow: ${ms}ms", ms < 10_000)
    }

    @Test
    fun `playback generation 100k claims`() {
        val generation = PlaybackGeneration()
        var token = 0L
        val ms = timed("playback-generation-100k") {
            repeat(100_000) { token = generation.claim() }
        }
        assertTrue(generation.isCurrent(token))
        assertTrue(!generation.isCurrent(token - 1))
        assertTrue(!generation.isCurrent(0L))
        assertTrue("playback generation too slow: ${ms}ms", ms < 10_000)
    }

    @Test
    fun `circuit breaker concurrent hammer does not crash`() {
        val breaker = CircuitBreaker()
        val errors = mutableListOf<Throwable>()
        val threads = (0 until 8).map {
            thread {
                try {
                    repeat(1_000) {
                        breaker.recordFailure()
                        breaker.isOpen()
                        breaker.recordSuccess()
                    }
                } catch (e: Throwable) {
                    synchronized(errors) { errors.add(e) }
                }
            }
        }
        threads.forEach { it.join(30_000) }
        assertTrue("circuit breaker threw under concurrency: $errors", errors.isEmpty())
        // 最后一次操作后状态自洽：连续失败满阈值即开闸（这里以不崩溃为准，不 assert 开闭）。
    }
}
