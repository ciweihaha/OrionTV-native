package com.oriontv.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    @Test
    fun `parses real epg header with DOCTYPE`() {
        // 真实节目单头部（2026-11-14 实测 https://ysp.ciweihaha.win/epg.xml）：
        // 以 <!DOCTYPE tv SYSTEM "xmltv.dtd"> 开头，之前会被 disallow-doctype-decl 拒掉整份 EPG。
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tv SYSTEM "xmltv.dtd">
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <programme channel="cctv1" start="20261114120000 +0800" stop="20261114130000 +0800">
                <title>新闻联播</title>
              </programme>
            </tv>
        """.trimIndent()
        val data = EpgParser.parse(xml)
        assertEquals("CCTV-1 综合", data.channelNames["cctv1"])
        assertEquals("新闻联播", data.programmesByChannel["cctv1"]?.first()?.title)
    }

    @Test
    fun `does not fetch external DTD`() {
        // DOCTYPE 指到一个不存在的外部 DTD：如果解析器试图拉取，解析会失败；
        // 127.0.0.1:1 端口关闭，拉取会立刻连接失败，不会挂起。
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tv SYSTEM "http://127.0.0.1:1/nonexistent.dtd">
            <tv>
              <channel id="cctv1"><display-name>CCTV1</display-name></channel>
              <programme channel="cctv1" start="20261114120000 +0800" stop="20261114130000 +0800">
                <title>新闻联播</title>
              </programme>
            </tv>
        """.trimIndent()
        val data = EpgParser.parse(xml)
        assertEquals("新闻联播", data.programmesByChannel["cctv1"]?.first()?.title)
    }

    @Test
    fun `rejects billion laughs style internal entity expansion`() {
        // D-P1-3：9 层嵌套内部实体（经典 billion laughs 结构），预扫描在进解析器前拒绝。
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tv [
              <!ENTITY lol "lollollollollollollollollollol">
              <!ENTITY lol1 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
              <!ENTITY lol2 "&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;">
              <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
              <!ENTITY lol4 "&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;">
              <!ENTITY lol5 "&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;">
              <!ENTITY lol6 "&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;">
              <!ENTITY lol7 "&lol6;&lol6;&lol6;&lol6;&lol6;&lol6;&lol6;&lol6;&lol6;&lol6;">
              <!ENTITY lol8 "&lol7;&lol7;&lol7;&lol7;&lol7;&lol7;&lol7;&lol7;&lol7;&lol7;">
              <!ENTITY lol9 "&lol8;&lol8;&lol8;&lol8;&lol8;&lol8;&lol8;&lol8;&lol8;&lol8;">
            ]>
            <tv>
              <channel id="cctv1"><display-name>CCTV1</display-name></channel>
              <programme channel="cctv1" start="20261114120000 +0800" stop="20261114130000 +0800">
                <title>&lol9;</title>
              </programme>
            </tv>
        """.trimIndent()
        assertThrows(EpgParser.EpgSecurityException::class.java) {
            EpgParser.parse(xml)
        }
    }

    @Test
    fun `allows a small number of benign internal entities`() {
        // 少量内部实体（上限内）不误伤：正常解析、实体正常展开。
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tv [
              <!ENTITY greeting "新闻联播">
            ]>
            <tv>
              <channel id="cctv1"><display-name>CCTV1</display-name></channel>
              <programme channel="cctv1" start="20261114120000 +0800" stop="20261114130000 +0800">
                <title>&greeting;</title>
              </programme>
            </tv>
        """.trimIndent()
        val data = EpgParser.parse(xml)
        assertEquals("新闻联播", data.programmesByChannel["cctv1"]?.first()?.title)
    }

    @Test
    fun `does not expand external general entities in titles`() {
        // 外部通用实体（如 file://）出现在 <title> 里：不展开、不拉取、不进标题。
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tv [
              <!ENTITY xxe SYSTEM "file:///etc/passwd">
            ]>
            <tv>
              <channel id="cctv1"><display-name>CCTV1</display-name></channel>
              <programme channel="cctv1" start="20261114120000 +0800" stop="20261114130000 +0800">
                <title>&xxe;</title>
              </programme>
            </tv>
        """.trimIndent()
        val data = EpgParser.parse(xml)
        val title = data.programmesByChannel["cctv1"]?.first()?.title.orEmpty()
        assertFalse(title.contains("root:"))
    }

    @Test
    fun `nowPlaying matches tvg-id case-insensitively`() {
        val data = EpgParser.parse(sample)
        // M3U 写 CCTV1、EPG 写 cctv1 时，之前直接对不上，只能等名字兜底。
        val now = EpgParser.nowPlaying(data, "CCTV1", "别的台名", nowMs)
        assertEquals("新闻联播", now?.title)
    }

    @Test
    fun `hasFutureProgramme is true only when a programme has not ended`() {
        // D9-P1-1：新鲜数据判据——任一 endAt > now 即新鲜。
        val data = EpgParser.parse(sample)
        assertTrue(EpgParser.hasFutureProgramme(data, nowMs))
        // 所有节目都播完后不再新鲜（昨天那份 epg.xml 拉回来就是这种情况）。
        assertFalse(EpgParser.hasFutureProgramme(data, nowMs + 3 * 3600_000L))
        assertFalse(
            EpgParser.hasFutureProgramme(
                EpgParser.EpgData(emptyMap(), emptyMap()),
                nowMs,
            ),
        )
    }

    @Test
    fun `parse truncates programmes over MAX_PROGRAMMES`() {
        // D9-P2-4：与 M3U 的 MAX_CHANNELS=20_000 对称——超限截断，不静默丢数据（记日志）。
        // 每条 1 分钟依次排开（跨天进位，保证 start/stop 合法、不被 endAt <= startAt 丢弃）。
        fun xmltvTime(totalMinutes: Long): String {
            val day = 14 + (totalMinutes / 1440).toInt()
            val minuteOfDay = (totalMinutes % 1440).toInt()
            return "202611%02d%02d%02d00".format(day, minuteOfDay / 60, minuteOfDay % 60)
        }
        val count = EpgParser.MAX_PROGRAMMES + 1
        val programmes = (0 until count).joinToString("\n") { i ->
            val start = xmltvTime(i.toLong())
            val stop = xmltvTime(i.toLong() + 1)
            """<programme channel="cctv1" start="$start +0800" stop="$stop +0800"><title>节目$i</title></programme>"""
        }
        // 注意：不能用三引号 + trimIndent() 拼这份 XML——$programmes 的行零缩进会让
        // trimIndent() 的最小缩进变成 0，<?xml ...?> 前残留空格，SAX 报 Content is not allowed in prolog。
        val xml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("<tv>")
            appendLine("""<channel id="cctv1"><display-name>CCTV1</display-name></channel>""")
            appendLine(programmes)
            appendLine("</tv>")
        }
        val data = EpgParser.parse(xml)
        val total = data.programmesByChannel.values.sumOf { it.size }
        assertEquals(EpgParser.MAX_PROGRAMMES, total)
    }
}
