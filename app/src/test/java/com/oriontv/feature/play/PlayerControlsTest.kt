package com.oriontv.feature.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerControlsTest {

    @Test
    fun `seek preview label shows direction and target time`() {
        // P2-1：气泡加方向标识；只标方向不标固定“10 秒”，连按多下累计的步数不是 10 秒。
        assertEquals("◀ 快退 · 12:34", seekPreviewLabel(-1, 754_000L))
        assertEquals("快进 ▶ · 12:34", seekPreviewLabel(1, 754_000L))
        assertEquals("12:34", seekPreviewLabel(0, 754_000L))
    }

    @Test
    fun `next episode countdown ticks only while playing and drawer closed`() {
        // P2-1：暂停或抽屉打开时倒计时冻结，不在用户挑集/暂停时强制切集
        assertTrue(shouldTickNextEpisodeCountdown(isPlaying = true, drawerOpen = false))
        assertFalse(shouldTickNextEpisodeCountdown(isPlaying = false, drawerOpen = false))
        assertFalse(shouldTickNextEpisodeCountdown(isPlaying = true, drawerOpen = true))
        assertFalse(shouldTickNextEpisodeCountdown(isPlaying = false, drawerOpen = true))
    }

    @Test
    fun `next episode countdown ticks when ended even though not playing`() {
        // A6-P1-1：播完态 STATE_ENDED 时 isPlaying=false，倒计时按“正在播或
        // 已播完”走，否则预告卡永久冻结在“3 秒后自动播放”
        assertTrue(shouldTickNextEpisodeCountdown(isPlaying = false, isEnded = true, drawerOpen = false))
        assertTrue(shouldTickNextEpisodeCountdown(isPlaying = true, isEnded = true, drawerOpen = false))
        // 播完但抽屉打开（正在挑集）仍冻结
        assertFalse(shouldTickNextEpisodeCountdown(isPlaying = false, isEnded = true, drawerOpen = true))
        // 暂停且未播完仍冻结
        assertFalse(shouldTickNextEpisodeCountdown(isPlaying = false, isEnded = false, drawerOpen = false))
    }
}
