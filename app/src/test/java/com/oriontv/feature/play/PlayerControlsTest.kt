package com.oriontv.feature.play

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerControlsTest {

    @Test
    fun `seek preview label shows direction and target time`() {
        // P2-1：气泡加方向标识；只标方向不标固定“10 秒”，连按多下累计的步数不是 10 秒。
        assertEquals("◀ 快退 · 12:34", seekPreviewLabel(-1, 754_000L))
        assertEquals("快进 ▶ · 12:34", seekPreviewLabel(1, 754_000L))
        assertEquals("12:34", seekPreviewLabel(0, 754_000L))
    }
}
