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
}
