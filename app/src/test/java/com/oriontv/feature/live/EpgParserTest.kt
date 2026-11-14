package com.oriontv.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgParserTest {

    private val sample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="cctv1"><display-name>CCTV1</display-name></channel>
          <channel id="gdws"><display-name>广东卫视</display-name></channel>
          <programme channel="cctv1" start="20261114120000 +0800" stop="20261114130000 +0800">
            <title><![CDATA[新闻联播]]></title>
          </programme>
          <programme channel="cctv1" start="20261114130000 +0800" stop="20261114140000 +0800">
            <title>天气预报</title>
          </programme>
          <programme channel="cctv1" start="20261114140000 +0800" stop="20261114140000 +0800">
            <title>非法节目（endAt 等于 startAt，应丢弃）</title>
          </programme>
          <programme channel="gdws" start="20261114123000 +0800" stop="20261114133000 +0800">
            <title>广东新闻</title>
          </programme>
        </tv>
    """.trimIndent()

    // 2026-11-14 12:30:00 +0800 = 1794630600000 ms
    private val nowMs = 1794630600000L

    @Test
    fun `parses channels and programmes, drops invalid`() {
        val data = EpgParser.parse(sample)
        assertEquals("CCTV1", data.channelNames["cctv1"])
        assertEquals(2, data.programmesByChannel["cctv1"]?.size)
        assertEquals("新闻联播", data.programmesByChannel["cctv1"]?.first()?.title)
    }

    @Test
    fun `nowPlaying matches by tvg-id`() {
        val data = EpgParser.parse(sample)
        val now = EpgParser.nowPlaying(data, "cctv1", "CCTV1", nowMs)
        assertEquals("新闻联播", now?.title)
    }

    @Test
    fun `nowPlaying falls back to normalized name`() {
        val data = EpgParser.parse(sample)
        val now = EpgParser.nowPlaying(data, "unknown-id", "广东 卫视", nowMs)
        assertEquals("广东新闻", now?.title)
    }

    @Test
    fun `nextProgramme returns the following show`() {
        val data = EpgParser.parse(sample)
        val next = EpgParser.nextProgramme(data, "cctv1", "CCTV1", nowMs)
        assertEquals("天气预报", next?.title)
    }

    @Test
    fun `no programme now returns null`() {
        val data = EpgParser.parse(sample)
        // 2026-11-14 10:00 +0800，任何节目都还没开始
        assertNull(EpgParser.nowPlaying(data, "cctv1", "CCTV1", 1794621600000L))
    }

    @Test
    fun `cache is fresh only when a programme is still playing`() {
        val data = EpgParser.parse(sample)
        // 缓存 1 小时前生成，仍有正在播的节目 → 新鲜
        assertTrue(EpgParser.isCacheFresh(data, nowMs - 3600_000L, nowMs))
        // 缓存是昨天的（超过 6 小时 TTL）→ 过期
        assertFalse(EpgParser.isCacheFresh(data, nowMs - 7 * 3600_000L, nowMs))
        // 缓存时间在 TTL 内，但所有节目都已播完 → 过期（天光 v2.2.20 教训）
        assertFalse(EpgParser.isCacheFresh(data, nowMs - 3600_000L, nowMs + 3 * 3600_000L))
    }

    @Test
    fun `programmesFor returns full-day list sorted by start`() {
        val data = EpgParser.parse(sample)
        val list = EpgParser.programmesFor(data, "cctv1", "CCTV1")
        assertEquals(listOf("新闻联播", "天气预报"), list.map { it.title })
        // 归一化名字兜底也能拿到全天列表
        val byName = EpgParser.programmesFor(data, "no-such-id", "广东 卫视")
        assertEquals(listOf("广东新闻"), byName.map { it.title })
        // 对不上的台返回空列表（弹窗显示“暂无节目单”，不崩）
        assertTrue(EpgParser.programmesFor(data, "no-such-id", "不存在的台").isEmpty())
    }

    @Test
    fun `normalizeName strips spaces hyphens underscores and lowercases`() {
        assertEquals("cctv1", EpgParser.normalizeName("CCTV-1"))
        assertEquals("广东卫视", EpgParser.normalizeName("广东 卫视"))
        assertEquals("abc", EpgParser.normalizeName("A_B C"))
    }
}
