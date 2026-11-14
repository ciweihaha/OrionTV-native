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
    fun `same name different case merges into one channel`() {
        // D-P2-5：合并按名字大小写不敏感，与 EPG 的大小写不敏感匹配一致；
        // CCTV1 与 cctv1 是同一台，首条的显示名保留。
        val content = """
            #EXTM3U
            #EXTINF:-1 tvg-id="cctv1",CCTV1
            http://example.com/cctv1-a.m3u8
            #EXTINF:-1 tvg-id="cctv1",cctv1
            http://example.com/cctv1-b.m3u8
        """.trimIndent()
        val channels = M3uParser.parse(content)
        assertEquals(1, channels.size)
        assertEquals("CCTV1", channels[0].name)
        assertEquals(
            listOf("http://example.com/cctv1-a.m3u8", "http://example.com/cctv1-b.m3u8"),
            channels[0].urls,
        )
    }

    @Test
    fun `three same-name lines merge without crashing`() {
        // P0-1：旧实现第 3 条同名线路 indexOf 返回 -1，IndexOutOfBoundsException 整表失败
        val content = """
            #EXTM3U
            #EXTINF:-1 tvg-id="cctv1",CCTV1
            http://example.com/cctv1-a.m3u8
            #EXTINF:-1 tvg-id="cctv1",CCTV1
            http://example.com/cctv1-b.m3u8
            #EXTINF:-1 tvg-id="cctv1",CCTV1
            http://example.com/cctv1-c.m3u8
            #EXTINF:-1 tvg-id="cctv1",CCTV1
            http://example.com/cctv1-d.m3u8
        """.trimIndent()
        val channels = M3uParser.parse(content)
        assertEquals(1, channels.size)
        assertEquals(
            listOf(
                "http://example.com/cctv1-a.m3u8",
                "http://example.com/cctv1-b.m3u8",
                "http://example.com/cctv1-c.m3u8",
                "http://example.com/cctv1-d.m3u8",
            ),
            channels[0].urls,
        )
        assertEquals("http://example.com/cctv1-a.m3u8", channels[0].url)
    }

    @Test
    fun `non hash non url line between extinf and url does not drop the channel`() {
        // A6-P2-6：#EXTINF 与地址间的非 #、非 URL 行（相对路径/脏行）曾让
        // 整条频道被丢掉；现在跳过该行继续找地址
        val content = """
            #EXTM3U
            #EXTINF:-1 tvg-id="cctv1" group-title="央视",CCTV1
            live/cctv1
            http://example.com/cctv1.m3u8
            #EXTINF:-1 tvg-id="cctv5",CCTV5
            http://example.com/cctv5.m3u8
        """.trimIndent()
        val channels = M3uParser.parse(content)
        assertEquals(listOf("CCTV1", "CCTV5"), channels.map { it.name })
        assertEquals("http://example.com/cctv1.m3u8", channels[0].url)
    }

    @Test
    fun `hash lines between extinf and url do not drop the channel`() {
        // P2-3：#EXTVLCOPT / #EXTGRP 夹在 #EXTINF 与流地址之间时频道曾被丢掉
        val content = """
            #EXTM3U
            #EXTINF:-1 tvg-id="cctv1" group-title="央视",CCTV1
            #EXTVLCOPT:network-caching=1000
            #EXTGRP:央视
            http://example.com/cctv1.m3u8
            #EXTINF:-1 tvg-id="cctv5",CCTV5
            http://example.com/cctv5.m3u8
        """.trimIndent()
        val channels = M3uParser.parse(content)
        assertEquals(listOf("CCTV1", "CCTV5"), channels.map { it.name })
        assertEquals("http://example.com/cctv1.m3u8", channels[0].url)
    }

    @Test
    fun `merge fills blank attrs from later lines`() {
        // P2-4：首条无 logo/group/tvgId 时用后条补齐，首条已有的不被覆盖
        val content = """
            #EXTM3U
            #EXTINF:-1,CCTV1
            http://example.com/cctv1-a.m3u8
            #EXTINF:-1 tvg-id="cctv1" tvg-logo="http://example.com/cctv1.png" group-title="央视",CCTV1
            http://example.com/cctv1-b.m3u8
            #EXTINF:-1 tvg-id="other" tvg-logo="http://example.com/other.png" group-title="其他",CCTV1
            http://example.com/cctv1-c.m3u8
        """.trimIndent()
        val channels = M3uParser.parse(content)
        assertEquals(1, channels.size)
        assertEquals("cctv1", channels[0].tvgId)
        assertEquals("http://example.com/cctv1.png", channels[0].logo)
        assertEquals("央视", channels[0].group)
        assertEquals(3, channels[0].urls.size)
    }

    @Test
    fun `empty urls channel url is empty string not crash`() {
        // P2-13：urls:[] 脏数据下 url 不再抛 NoSuchElementException
        val channel = M3uParser.Channel(id = "x", name = "X", urls = emptyList())
        assertEquals("", channel.url)
    }

    @Test
    fun `duplicate urls are dropped before merging`() {
        // 同一 URL 只留第一次出现：sample 里 CCTV1 的 cctv1.m3u8 出现两次，合并后只剩 3 条不重复地址
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
    fun `channel without url does not swallow the next channel`() {
        // 上一个台缺流地址、紧跟下一个 #EXTINF：之前 i=j 会把下一个台整行跳过
        val content = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a",ChannelA
            #EXTINF:-1 tvg-id="b",ChannelB
            http://example.com/b.m3u8
        """.trimIndent()
        val channels = M3uParser.parse(content)
        assertEquals(listOf("ChannelB"), channels.map { it.name })
    }

    @Test
    fun `non-extinf lines are ignored`() {
        assertTrue(M3uParser.parse("#EXTM3U\n").isEmpty())
        assertTrue(M3uParser.parse("").isEmpty())
    }

    @Test
    fun `channel count is capped at MAX_CHANNELS`() {
        // D7-P2-8：5MB 体上限之外再加频道数上限，超限截断（不静默丢数据，
        // 截断记 AppLog.w）；真实源远达不到该量级，正常解析不受影响。
        val count = M3uParser.MAX_CHANNELS + 1
        val content = buildString {
            append("#EXTM3U\n")
            repeat(count) { i ->
                append("#EXTINF:-1 tvg-id=\"ch$i\",Channel$i\n")
                append("http://example.com/ch$i.m3u8\n")
            }
        }
        val channels = M3uParser.parse(content)
        assertEquals(M3uParser.MAX_CHANNELS, channels.size)
        assertEquals("Channel0", channels.first().name)
        assertEquals("Channel${M3uParser.MAX_CHANNELS - 1}", channels.last().name)
    }
}
