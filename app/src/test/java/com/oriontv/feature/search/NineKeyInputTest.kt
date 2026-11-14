package com.oriontv.feature.search

import com.oriontv.data.api.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NineKeyInputTest {

    @Test
    fun `same key cycles letters within one second`() {
        val input = NineKeyInput()
        var text = ""
        text = input.press("2", text, 1000L)
        assertEquals("a", text)
        text = input.press("2", text, 1500L)
        assertEquals("b", text)
        text = input.press("2", text, 1900L)
        assertEquals("c", text)
        // 再按一圈回到 a。
        text = input.press("2", text, 2200L)
        assertEquals("a", text)
    }

    @Test
    fun `timeout restarts from first letter`() {
        val input = NineKeyInput()
        var text = ""
        text = input.press("2", text, 1000L)
        assertEquals("a", text)
        text = input.press("2", text, 1500L)
        assertEquals("b", text)
        // 超过 1 秒：不再循环，追加首字母。
        text = input.press("2", text, 3000L)
        assertEquals("ba", text)
    }

    @Test
    fun `different key appends first letter`() {
        val input = NineKeyInput()
        var text = ""
        text = input.press("2", text, 1000L)
        text = input.press("3", text, 1100L)
        assertEquals("ad", text)
    }

    @Test
    fun `external text change breaks multi tap cycle`() {
        val input = NineKeyInput()
        var text = ""
        text = input.press("2", text, 1000L)
        assertEquals("a", text)
        // 外部（遥控/远程输入）改了文本：末尾不再是上次敲的字母，下次按键追加而非替换。
        text = input.press("2", "庆余年", 1100L)
        assertEquals("庆余年a", text)
    }

    @Test
    fun `reset clears multi tap state`() {
        val input = NineKeyInput()
        var text = ""
        text = input.press("7", text, 1000L)
        assertEquals("p", text)
        input.reset()
        text = input.press("7", text, 1100L)
        assertEquals("pp", text)
    }

    @Test
    fun `unknown group leaves text unchanged`() {
        val input = NineKeyInput()
        assertEquals("abc", input.press("0", "abc", 1000L))
        assertEquals("", input.press("x", "", 1000L))
    }

    @Test
    fun `empty results are not cached`() {
        assertFalse(shouldCacheSearchResults(emptyList()))
        assertTrue(
            shouldCacheSearchResults(
                listOf(
                    SearchResult(id = "1", title = "庆余年", poster = "", source = "a", source_name = "源A"),
                ),
            ),
        )
    }
}
