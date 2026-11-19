package com.oriontv.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

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
    fun `cache freshness honours a shorter ttl for hourly refresh`() {
        // 第 76 轮：isCacheFresh 的 ttlMs 参数是定时刷新的实现口——
        // 缓存 70 分钟前生成、仍有正在播的节目：6 小时 TTL 下新鲜，
        // 1 小时上限下已过期（触发重拉）；30 分钟前生成则 1 小时上限下仍新鲜。
        val data = EpgParser.parse(sample)
        assertTrue(EpgParser.isCacheFresh(data, nowMs - 70 * 60_000L, nowMs, ttlMs = 6 * 60 * 60 * 1000L))
        assertFalse(EpgParser.isCacheFresh(data, nowMs - 70 * 60_000L, nowMs, ttlMs = 60 * 60 * 1000L))
        assertTrue(EpgParser.isCacheFresh(data, nowMs - 30 * 60_000L, nowMs, ttlMs = 60 * 60 * 1000L))
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

    // 第 70 轮：findNowPlaying / findNextProgramme 二分查找——与旧线性扫描逐项比对。

    private fun programme(title: String, startMs: Long, endMs: Long) =
        EpgParser.Programme(channelId = "cctv1", title = title, startAt = startMs, endAt = endMs)

    @Test
    fun `findNowPlaying matches linear scan at every minute`() {
        val list = listOf(
            programme("A", 0L, 3_600_000L),
            programme("B", 3_600_000L, 7_200_000L),
            programme("C", 7_200_000L, 10_800_000L),
        )
        var now = -1L
        while (now <= 10_800_001L) {
            val expected = list.firstOrNull { it.startAt <= now && now < it.endAt }
            assertEquals("now=$now", expected, EpgParser.findNowPlaying(list, now))
            now += 599_999L
        }
        assertNull(EpgParser.findNowPlaying(emptyList(), 1000L))
        assertNull(EpgParser.findNowPlaying(list, -1L))
        assertNull(EpgParser.findNowPlaying(list, 10_800_000L))
    }

    @Test
    fun `findNowPlaying falls back to earlier overlapping programme`() {
        // 重叠节目：后开始的短节目已播完，早开始的长节目还在播——二分候选失败时线性回扫。
        val list = listOf(
            programme("长节目", 0L, 10_800_000L),
            programme("短节目", 3_600_000L, 4_200_000L),
        )
        assertEquals("短节目", EpgParser.findNowPlaying(list, 3_900_000L)?.title)
        assertEquals("长节目", EpgParser.findNowPlaying(list, 5_000_000L)?.title)
    }

    @Test
    fun `trySetSecureProcessing returns false when feature unsupported instead of throwing`() {
        // 第 74 轮：模拟 Android 解析器——FEATURE_SECURE_PROCESSING 在 Android
        // 全版本都不支持，setFeature 直接抛 ParserConfigurationException；
        // 旧实现在这里抛 EpgSecurityException，等于真机上永远拒绝解析。
        assertFalse(EpgParser.trySetSecureProcessing(UnsupportedFeatureFactory()))
    }

    @Test
    fun `trySetSecureProcessing returns true on JVM parser`() {
        // JDK 解析器支持 FSP，正常设上。
        assertTrue(EpgParser.trySetSecureProcessing(DocumentBuilderFactory.newInstance()))
    }

    @Test
    fun `parses real saved epg file`() {
        // 第 74 轮：用 2026-11-18 23:48 从服务端保存的真实 epg.xml（384KB、
        // 57 台、2413 条节目）做解析验证——服务端 11-19 的文件还没发布，
        // 先以这份为准。文件里没有 cctv1/cctv3（服务端数据缺口），解析后
        // 同样没有（不是解析丢数据）。
        val xml = javaClass.classLoader
            ?.getResourceAsStream("epg-real-20261118.xml")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("缺少测试资源 epg-real-20261118.xml")
        val data = EpgParser.parse(xml)
        assertEquals(57, data.channelNames.size)
        assertEquals(56, data.programmesByChannel.size)
        assertEquals(2413, data.programmesByChannel.values.sumOf { it.size })
        assertEquals(38, data.programmesByChannel["cctv2"]?.size)
        assertNull(data.programmesByChannel["cctv1"])
        assertNull(data.programmesByChannel["cctv3"])
    }

    @Test
    fun `parses real saved epg file when setXIncludeAware throws (Android Harmony)`() {
        // 第 74 轮（v2.0.12）：Android 的 DocumentBuilderFactoryImpl 没有覆写
        // setXIncludeAware，调它直接抛 UnsupportedOperationException；旧 parse
        // 在 AppLog.i 之前就调了这一行，真机上每次解析都在这里抛错，节目单
        // 永远拉不到。修掉之后，即使 factory 的 setXIncludeAware 会抛，解析
        // 也必须成功（且 XInclude 仍是关的）。
        val xml = javaClass.classLoader
            ?.getResourceAsStream("epg-real-20261118.xml")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("缺少测试资源 epg-real-20261118.xml")
        val factory = ThrowingXIncludeFactory()
        val data = EpgParser.parse(xml, factory)
        assertEquals(57, data.channelNames.size)
        assertEquals(2413, data.programmesByChannel.values.sumOf { it.size })
        assertFalse(factory.isXIncludeAware)
    }

    /** 模拟 Android（Harmony）解析器：除 setXIncludeAware 外一律委托给真实解析器。 */
    private class ThrowingXIncludeFactory(
        private val delegate: DocumentBuilderFactory = DocumentBuilderFactory.newInstance(),
    ) : DocumentBuilderFactory() {
        override fun setXIncludeAware(state: Boolean) {
            throw UnsupportedOperationException(
                "This parser does not support specification \"Unknown\" version \"0.0\"",
            )
        }

        override fun isXIncludeAware(): Boolean = delegate.isXIncludeAware

        override fun setNamespaceAware(awareness: Boolean) {
            delegate.isNamespaceAware = awareness
        }

        override fun isNamespaceAware(): Boolean = delegate.isNamespaceAware

        override fun newDocumentBuilder(): DocumentBuilder = delegate.newDocumentBuilder()

        override fun setFeature(name: String, value: Boolean) {
            delegate.setFeature(name, value)
        }

        override fun getFeature(name: String): Boolean = delegate.getFeature(name)

        override fun setAttribute(name: String, value: Any) {
            delegate.setAttribute(name, value)
        }

        override fun getAttribute(name: String): Any? = delegate.getAttribute(name)

        override fun setExpandEntityReferences(expand: Boolean) {
            delegate.setExpandEntityReferences(expand)
        }
    }

    /** 模拟 Android 解析器：setFeature 一律抛错（Android 全版本不支持 FEATURE_SECURE_PROCESSING）。 */
    private class UnsupportedFeatureFactory : DocumentBuilderFactory() {
        override fun setFeature(name: String, value: Boolean) {
            throw ParserConfigurationException("Feature '$name' is not recognized.")
        }

        override fun getFeature(name: String): Boolean {
            throw ParserConfigurationException("Feature '$name' is not recognized.")
        }

        override fun newDocumentBuilder(): DocumentBuilder {
            throw ParserConfigurationException("stub")
        }

        override fun setAttribute(name: String, value: Any) = Unit

        override fun getAttribute(name: String): Any? = null
    }

    @Test
    fun `findNextProgramme skips programmes starting exactly now`() {
        val list = listOf(
            programme("A", 0L, 3_600_000L),
            programme("B", 3_600_000L, 7_200_000L),
            programme("B2", 3_600_000L, 7_200_000L),
            programme("C", 7_200_000L, 10_800_000L),
        )
        // now 正好是 B/B2 的开始：下一档是 C，不是 B
        assertEquals("C", EpgParser.findNextProgramme(list, 3_600_000L)?.title)
        assertEquals("B", EpgParser.findNextProgramme(list, 0L)?.title)
        assertEquals("B", EpgParser.findNextProgramme(list, 1_000L)?.title)
        assertNull(EpgParser.findNextProgramme(list, 7_200_000L))
        assertNull(EpgParser.findNextProgramme(emptyList(), 0L))
    }
}
