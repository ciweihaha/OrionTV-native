package com.oriontv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RnMigrationParser 测试（D-P1-6）：RN AsyncStorage blob 的纯解析，JVM 可跑。 */
class RnMigrationParserTest {

    @Test
    fun `parses favorites and skips bad entries`() {
        val raw = """{"agg+1": {"cover":"","title":"好片","source_name":"源","total_episodes":10,"search_title":"好片","year":"2024","save_time":1794000000000},
            "bad": "not-an-object",
            "agg+2": {"cover":"","title":"好片2","source_name":"源","total_episodes":5,"search_title":"好片2"}}"""
        val parsed = RnMigrationParser.parseFavorites(raw)
        assertEquals(2, parsed.size)
        assertEquals("好片", parsed["agg+1"]?.title)
        assertEquals(1794000000000L, parsed["agg+1"]?.save_time)
        assertEquals("好片2", parsed["agg+2"]?.title)
    }

    @Test
    fun `corrupt favorites blob returns empty map`() {
        assertTrue(RnMigrationParser.parseFavorites("{not json").isEmpty())
        assertTrue(RnMigrationParser.parseFavorites("[]").isEmpty())
    }

    @Test
    fun `parses play records including embedded intro outro marks`() {
        val raw = """{"agg+1": {"title":"剧","source_name":"源","cover":"","index":3,"total_episodes":30,"play_time":120,"total_time":2700,"save_time":1794000000000,"year":"2024","introEndTime":90000,"outroStartTime":2600000}}"""
        val parsed = RnMigrationParser.parsePlayRecords(raw)
        assertEquals(1, parsed.size)
        val record = parsed["agg+1"]!!
        assertEquals(3, record.index)
        assertEquals(120L, record.play_time)
        assertEquals(90000L, record.introEndTime)
        assertEquals(2600000L, record.outroStartTime)
    }

    @Test
    fun `parses player settings`() {
        val raw = """{"agg+1": {"introEndTime":90000,"playbackRate":1.5},"agg+2": {"outroStartTime":2600000}}"""
        val parsed = RnMigrationParser.parsePlayerSettings(raw)
        assertEquals(2, parsed.size)
        assertEquals(90000L, parsed["agg+1"]?.introEndTime)
        assertEquals(1.5f, parsed["agg+1"]?.playbackRate)
        assertEquals(2600000L, parsed["agg+2"]?.outroStartTime)
    }

    @Test
    fun `parses settings partially`() {
        val raw = """{"apiBaseUrl":"https://example.com","m3uUrl":"https://example.com/live.m3u","autoSkipIntro":false,"introSkipSeconds":60}"""
        val parsed = RnMigrationParser.parseSettings(raw)!!
        assertEquals("https://example.com", parsed.apiBaseUrl)
        assertEquals("https://example.com/live.m3u", parsed.m3uUrl)
        assertEquals(false, parsed.autoSkipIntro)
        assertEquals(60, parsed.introSkipSeconds)
        assertNull(parsed.remoteInputEnabled)
        assertNull(parsed.incognitoMode)
    }

    @Test
    fun `corrupt settings blob returns null`() {
        assertNull(RnMigrationParser.parseSettings("{not json"))
    }

    @Test
    fun `parses search history and drops non-strings`() {
        val raw = """["庆余年", 123, "  ", "繁花", null]"""
        assertEquals(listOf("庆余年", "繁花"), RnMigrationParser.parseSearchHistory(raw))
        assertTrue(RnMigrationParser.parseSearchHistory("{bad").isEmpty())
    }

    @Test
    fun `live prefs validation only passes matching shapes`() {
        assertEquals(
            """{"url":"http://a/1.m3u8","name":"CCTV1"}""",
            RnMigrationParser.validateLiveLastChannel("""{"url":"http://a/1.m3u8","name":"CCTV1"}"""),
        )
        assertNull(RnMigrationParser.validateLiveLastChannel("""["not-an-object"]"""))
        assertEquals(
            """[{"url":"http://a/1.m3u8","name":"CCTV1"}]""",
            RnMigrationParser.validateLiveFavChannels("""[{"url":"http://a/1.m3u8","name":"CCTV1"}]"""),
        )
        assertNull(RnMigrationParser.validateLiveFavChannels("""{"url":"http://a/1.m3u8"}"""))
    }
}
