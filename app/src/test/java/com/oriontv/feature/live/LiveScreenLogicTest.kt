package com.oriontv.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveScreenLogicTest {

    private fun channel(name: String, url: String) =
        M3uParser.Channel(id = url, name = name, urls = listOf(url))

    @Test
    fun `panel focus lands on current channel when present`() {
        val filtered = listOf(
            channel("CCTV-1", "http://a/1.m3u8"),
            channel("CCTV-5", "http://a/5.m3u8"),
        )
        assertEquals(1, resolvePanelFocusIndex(filtered, "http://a/5.m3u8"))
    }

    @Test
    fun `panel focus falls to first row when group has no current channel`() {
        // P1-7：切到不含当前台的分组，焦点落该分组第 0 行，不丢焦点
        val filtered = listOf(
            channel("广东卫视", "http://a/gd.m3u8"),
            channel("深圳卫视", "http://a/sz.m3u8"),
        )
        assertEquals(0, resolvePanelFocusIndex(filtered, "http://a/1.m3u8"))
        assertEquals(0, resolvePanelFocusIndex(filtered, null))
    }

    @Test
    fun `channel row ignores keys while epg dialog open`() {
        // P1-8：弹窗打开时行不消费 Center 键，不会误切台
        assertFalse(shouldConsumeChannelRowKey(epgDialogOpen = true))
        assertTrue(shouldConsumeChannelRowKey(epgDialogOpen = false))
    }
}
