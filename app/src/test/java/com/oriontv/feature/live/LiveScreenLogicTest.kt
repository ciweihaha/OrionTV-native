package com.oriontv.feature.live

import androidx.compose.ui.input.key.Key
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

    @Test
    fun `epg dialog traps focus at first row and close button`() {
        // P2-6：首行按上、关闭按钮（下标 n）按下时拦截，不让焦点移出弹窗落到背后频道行；
        // 行间移动与末行下移到“关闭”按钮放行；未知焦点（-1）不拦截
        assertTrue(shouldTrapEpgFocusKey(Key.DirectionUp, focusedIndex = 0, programmeCount = 5))
        assertFalse(shouldTrapEpgFocusKey(Key.DirectionUp, focusedIndex = 2, programmeCount = 5))
        assertFalse(shouldTrapEpgFocusKey(Key.DirectionUp, focusedIndex = 5, programmeCount = 5))
        assertTrue(shouldTrapEpgFocusKey(Key.DirectionDown, focusedIndex = 5, programmeCount = 5))
        assertFalse(shouldTrapEpgFocusKey(Key.DirectionDown, focusedIndex = 4, programmeCount = 5))
        assertFalse(shouldTrapEpgFocusKey(Key.DirectionDown, focusedIndex = 0, programmeCount = 5))
        assertFalse(shouldTrapEpgFocusKey(Key.DirectionUp, focusedIndex = -1, programmeCount = 5))
        assertFalse(shouldTrapEpgFocusKey(Key.DirectionDown, focusedIndex = -1, programmeCount = 5))
        assertFalse(shouldTrapEpgFocusKey(Key.DirectionLeft, focusedIndex = 0, programmeCount = 5))
        // 无节目单时“关闭”按钮下标就是 0：按下拦截、上移放行（无行可去，由根部处理）
        assertTrue(shouldTrapEpgFocusKey(Key.DirectionDown, focusedIndex = 0, programmeCount = 0))
        assertTrue(shouldTrapEpgFocusKey(Key.DirectionUp, focusedIndex = 0, programmeCount = 0))
    }

    @Test
    fun `live focus containment traps panel and epg keys`() {
        // C7-P1-1/A7-P2-5：面板开着时左键消费、右键放行；EPG 开着时左右都消费；
        // 都关着时不拦；上下键从不拦（面板内上下是焦点移动/换台）。
        assertTrue(shouldContainLiveFocusKey(panelOpen = true, epgDialogOpen = false, Key.DirectionLeft))
        assertFalse(shouldContainLiveFocusKey(panelOpen = true, epgDialogOpen = false, Key.DirectionRight))
        assertTrue(shouldContainLiveFocusKey(panelOpen = true, epgDialogOpen = true, Key.DirectionLeft))
        assertTrue(shouldContainLiveFocusKey(panelOpen = true, epgDialogOpen = true, Key.DirectionRight))
        assertFalse(shouldContainLiveFocusKey(panelOpen = false, epgDialogOpen = false, Key.DirectionLeft))
        assertFalse(shouldContainLiveFocusKey(panelOpen = true, epgDialogOpen = false, Key.DirectionUp))
        assertFalse(shouldContainLiveFocusKey(panelOpen = true, epgDialogOpen = true, Key.DirectionDown))
    }
}
