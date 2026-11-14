package com.oriontv.feature.live

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCacheCodecTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val channels = listOf(
        M3uParser.Channel(
            id = "http://a/1.m3u8",
            name = "CCTV-1",
            logo = "http://a/1.png",
            group = "央视",
            tvgId = "cctv1",
            urls = listOf("http://a/1.m3u8", "http://a/1b.m3u8"),
        ),
        M3uParser.Channel(
            id = "http://a/5.m3u8",
            name = "CCTV-5",
            urls = listOf("http://a/5.m3u8"),
        ),
    )

    @Test
    fun `m3u cache round-trips channels including multi-line urls`() {
        val cache = M3uCache(ts = 123L, channels = channels)
        val encoded = LiveCacheCodec.encodeM3u(cache, json)
        assertTrue(encoded != null)
        val decoded = LiveCacheCodec.decodeM3u(encoded!!, json)
        assertEquals(123L, decoded?.ts)
        assertEquals(channels, decoded?.channels)
        // url 恒等于 urls[0]
        assertEquals("http://a/1.m3u8", decoded?.channels?.first()?.url)
    }

    @Test
    fun `m3u cache over 3MB is refused`() {
        val huge = channels + M3uParser.Channel(
            id = "http://a/huge.m3u8",
            name = "x".repeat(3 * 1024 * 1024 + 1),
            urls = listOf("http://a/huge.m3u8"),
        )
        assertNull(LiveCacheCodec.encodeM3u(M3uCache(ts = 0L, channels = huge), json))
    }

    @Test
    fun `epg cache round-trips programmes`() {
        val data = EpgParser.EpgData(
            programmesByChannel = mapOf(
                "cctv1" to listOf(
                    EpgParser.Programme("cctv1", "新闻联播", 1000L, 2000L),
                    EpgParser.Programme("cctv1", "天气预报", 2000L, 3000L),
                ),
            ),
            channelNames = mapOf("cctv1" to "CCTV1"),
        )
        val encoded = LiveCacheCodec.encodeEpg(EpgCache(ts = 456L, data = data), json)
        assertTrue(encoded != null)
        val decoded = LiveCacheCodec.decodeEpg(encoded!!, json)
        assertEquals(456L, decoded?.ts)
        assertEquals(data, decoded?.data)
    }

    @Test
    fun `corrupt cache text decodes to null`() {
        assertNull(LiveCacheCodec.decodeM3u("{not json", json))
        assertNull(LiveCacheCodec.decodeEpg("", json))
    }

    @Test
    fun `decode drops channels with empty urls`() {
        // P2-13：urls:[] 脏缓存解码后滤掉该频道，不让 Channel.url 崩溃
        val dirty = """{"ts":1,"channels":[
            {"id":"http://a/1.m3u8","name":"CCTV-1","urls":["http://a/1.m3u8"]},
            {"id":"x","name":"Dirty","urls":[]}
        ]}"""
        val decoded = LiveCacheCodec.decodeM3u(dirty, json)
        assertEquals(listOf("CCTV-1"), decoded?.channels?.map { it.name })
    }
}
