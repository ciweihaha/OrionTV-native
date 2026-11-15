package com.oriontv.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jewel 第 9 轮 P2-6：iQIYI 联想解析/合并/地址的纯逻辑测试（不发真网）。 */
class IqiyiSuggestTest {

    @Test
    fun `parse takes title then name and drops blanks and duplicates`() {
        val body = """{"data":[
            {"title":"庆余年"},
            {"name":"庆余年2"},
            {"title":"庆余年"},
            {"title":""},
            {"title":"  "},
            {"other":"无标题字段"}
        ]}"""
        assertEquals(listOf("庆余年", "庆余年2"), parseIqiyiSuggestTitles(body))
    }

    @Test
    fun `parse caps at max and tolerates bad bodies`() {
        val body = """{"data":[
            {"title":"一"},{"title":"二"},{"title":"三"},{"title":"四"}
        ]}"""
        assertEquals(listOf("一", "二"), parseIqiyiSuggestTitles(body, max = 2))
        assertTrue(parseIqiyiSuggestTitles("").isEmpty())
        assertTrue(parseIqiyiSuggestTitles("not json").isEmpty())
        assertTrue(parseIqiyiSuggestTitles("{}").isEmpty())
        assertTrue(parseIqiyiSuggestTitles("""{"data":[]}""").isEmpty())
        assertTrue(parseIqiyiSuggestTitles("""{"data":"不是数组"}""").isEmpty())
    }

    @Test
    fun `merge puts history first then iqiyi deduped up to max`() {
        assertEquals(
            listOf("庆余年", "庆余年2", "甄嬛传"),
            mergeSuggestions(
                historySuggestions = listOf("庆余年", "庆余年2"),
                iqiyiSuggestions = listOf("庆余年2", "甄嬛传"),
            ),
        )
        assertEquals(
            listOf("a", "b", "c"),
            mergeSuggestions(listOf("a"), listOf("b", "c", "d"), max = 3),
        )
        assertEquals(
            listOf("甄嬛传"),
            mergeSuggestions(emptyList(), listOf("甄嬛传")),
        )
        assertEquals(
            listOf("庆余年"),
            mergeSuggestions(listOf("庆余年"), emptyList()),
        )
    }

    @Test
    fun `suggest url points at iqiyi with encoded key`() {
        val url = iqiyiSuggestUrl("qingyunian")
        assertTrue(url.startsWith("https://suggest.video.iqiyi.com/?if=mobile&key="))
        assertTrue(url.endsWith("key=qingyunian"))
        // 空格按 application/x-www-form-urlencoded 编成 +。
        assertTrue(iqiyiSuggestUrl("a b").endsWith("key=a+b"))
    }
}
