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
        val first = channel("CCTV-1", "http://a/1.m3u8")
        val second = channel("CCTV-5", "http://a/5.m3u8")
        val filtered = listOf(first, second)
        assertEquals(1, resolvePanelFocusIndex(filtered, second))
    }

    @Test
    fun `panel focus falls to first row when group has no current channel`() {
        // P1-7：切到不含当前台的分组，焦点落该分组第 0 行，不丢焦点
        val filtered = listOf(
            channel("广东卫视", "http://a/gd.m3u8"),
            channel("深圳卫视", "http://a/sz.m3u8"),
        )
        assertEquals(0, resolvePanelFocusIndex(filtered, channel("CCTV-1", "http://a/1.m3u8")))
        assertEquals(0, resolvePanelFocusIndex(filtered, null))
    }

    @Test
    fun `panel focus distinguishes duplicate urls by identity`() {
        // P2-4：同 URL 两条台，焦点落到对的那一行
        val first = channel("卫视", "http://a/shared.m3u8")
        val second = channel("卫视", "http://a/shared.m3u8")
        val filtered = listOf(first, second)
        assertEquals(0, resolvePanelFocusIndex(filtered, first))
        assertEquals(1, resolvePanelFocusIndex(filtered, second))
    }

    @Test
    fun `root ok retries only when playback failed`() {
        // A5-P2-4：只在 playbackFailed（失败页已出）时重试；failCount 1..5
        // 时 OK 开面板（与设计 §5.4 一致），旧“失败过就重试”已废弃
        assertTrue(shouldRetryOnOk(playbackFailed = true, failCount = 6))
        assertTrue(shouldRetryOnOk(playbackFailed = true, failCount = 0))
        // 未耗尽但失败过：开面板，不重试
        assertFalse(shouldRetryOnOk(playbackFailed = false, failCount = 3))
        assertFalse(shouldRetryOnOk(playbackFailed = false, failCount = 1))
        // 没失败过：开面板
        assertFalse(shouldRetryOnOk(playbackFailed = false, failCount = 0))
    }

    @Test
    fun `channel row ignores keys while epg dialog open`() {
        // P1-8：弹窗打开时行不消费 Center 键，不会误切台
        assertFalse(shouldConsumeChannelRowKey(epgDialogOpen = true))
        assertTrue(shouldConsumeChannelRowKey(epgDialogOpen = false))
    }
}
