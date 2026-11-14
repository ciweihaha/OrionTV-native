package com.oriontv.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    private val sample = """
        #EXTM3U
        #EXTINF:-1 tvg-id="cctv1" tvg-logo="http://example.com/cctv1.png" group-title="央视",CCTV1
        http://example.com/cctv1.m3u8
        #EXTINF:-1 tvg-id="cctv1" group-title="央视",CCTV1
        http://example.com/cctv1-backup.m3u8
        #EXTINF:-1 tvg-id="gdws" group-title="卫视",广东卫视
        http://example.com/gdws.m3u8
        #EXTINF:-1 tvg-id="cctv1" group-title="央视",CCTV1
        http://example.com/cctv1.m3u8
    """.trimIndent()

    @Test
    fun `parses channels with attrs`() {
        val channels = M3uParser.parse(sample)
        assertEquals(2, channels.size)
        val cctv1 = channels.first { it.name == "CCTV1" }
        assertEquals("cctv1", cctv1.tvgId)
        assertEquals("http://example.com/cctv1.png", cctv1.logo)
        assertEquals("央视", cctv1.group)
        // 同名频道合并多线路，url 恒等于 urls[0]
        assertEquals(listOf("http://example.com/cctv1.m3u8", "http://example.com/cctv1-backup.m3u8"), cctv1.urls)
        assertEquals("http://example.com/cctv1.m3u8", cctv1.url)
    }

    @Test
    fun `same url only kept once`() {
        val channels = M3uParser.parse(sample)
        val allUrls = channels.flatMap { it.urls }
        assertEquals(3, allUrls.size)
        assertEquals(allUrls.size, allUrls.distinct().size)
    }

    @Test
    fun `name is text after first comma outside quotes`() {
        // 逗号在双引号内时不算分隔
        val name = M3uParser.extractName("#EXTINF:-1 tvg-id=\"a,b\" group-title=\"x,y\",CCTV,1")
        assertEquals("CCTV,1", name)
    }

    @Test
    fun `missing name becomes Unknown and is not merged`() {
        val content = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a",
            http://example.com/a.m3u8
            #EXTINF:-1 tvg-id="b",
            http://example.com/b.m3u8
        """.trimIndent()
        val channels = M3uParser.parse(content)
        assertEquals(2, channels.size)
        assertTrue(channels.all { it.name == "Unknown" })
    }

    @Test
    fun `non-extinf lines are ignored`() {
        assertTrue(M3uParser.parse("#EXTM3U\n").isEmpty())
        assertTrue(M3uParser.parse("").isEmpty())
    }
}
