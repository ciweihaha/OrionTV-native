package com.oriontv.feature.play

import com.oriontv.feature.live.LiveViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackGenerationTest {

    @Test
    fun `first claim is current`() {
        val gen = PlaybackGeneration()
        val token = gen.claim()
        assertTrue(gen.isCurrent(token))
    }

    @Test
    fun `second claim invalidates the first`() {
        // P2-12：直播页先认领、点播页后认领，直播 onCleared 不能停掉点播的播放
        val gen = PlaybackGeneration()
        val liveToken = gen.claim()
        val playToken = gen.claim()
        assertFalse(gen.isCurrent(liveToken))
        assertTrue(gen.isCurrent(playToken))
    }

    @Test
    fun `unknown token is never current`() {
        val gen = PlaybackGeneration()
        assertFalse(gen.isCurrent(999L))
        assertFalse(gen.isCurrent(0L))
    }

    @Test
    fun `live playback speed is always 1x`() {
        // P1-2：直播开播前复位倍速，点播 2× 不泄漏到直播
        assertEquals(1f, LiveViewModel.LIVE_PLAYBACK_SPEED)
    }
}
