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
    fun `next episode countdown ticks only after playback ended`() {
        // A8-P1-1：预告卡在剩 10 秒时就出现，倒计时只在播完态走——正在播时
        // 倒计时冻结在 3 秒，否则约 3 秒后自动切集、吞掉本集最后约 7 秒
        assertFalse(shouldTickNextEpisodeCountdown(isPlaying = true, isEnded = false, drawerOpen = false))
        assertFalse(shouldTickNextEpisodeCountdown(isPlaying = false, isEnded = false, drawerOpen = false))
        // 播完态 STATE_ENDED 时 isPlaying=false，倒计时才走
        assertTrue(shouldTickNextEpisodeCountdown(isPlaying = false, isEnded = true, drawerOpen = false))
        assertTrue(shouldTickNextEpisodeCountdown(isPlaying = true, isEnded = true, drawerOpen = false))
        // 播完但抽屉打开（正在挑集）仍冻结
        assertFalse(shouldTickNextEpisodeCountdown(isPlaying = false, isEnded = true, drawerOpen = true))
    }
}
