package com.oriontv.feature.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroSkipTest {

    @Test
    fun `marked intro wins over default`() {
        assertEquals(45_000L, IntroSkip.resolveIntroEndMs(45_000L, 90))
        assertEquals(45_000L, IntroSkip.resolveIntroEndMs(45_000L, 0))
    }

    @Test
    fun `no mark falls back to configured seconds`() {
        assertEquals(90_000L, IntroSkip.resolveIntroEndMs(null, 90))
        assertEquals(30_000L, IntroSkip.resolveIntroEndMs(null, 30))
        assertEquals(120_000L, IntroSkip.resolveIntroEndMs(0L, 120))
    }

    @Test
    fun `no mark and zero seconds means no skip`() {
        assertEquals(0L, IntroSkip.resolveIntroEndMs(null, 0))
        assertEquals(0L, IntroSkip.resolveIntroEndMs(-5L, 0))
    }

    @Test
    fun `outro triggers at marked position`() {
        assertTrue(IntroSkip.isAtOutro(600_000L, 1_800_000L, 600_000L))
        assertTrue(IntroSkip.isAtOutro(601_000L, 1_800_000L, 600_000L))
        assertFalse(IntroSkip.isAtOutro(599_999L, 1_800_000L, 600_000L))
        assertFalse(IntroSkip.isAtOutro(600_000L, 1_800_000L, null))
        assertFalse(IntroSkip.isAtOutro(600_000L, 0L, 600_000L))
    }

    @Test
    fun `auto skip fires only inside intro window`() {
        // P1-10：开关开、位置在片头内、本集没跳过 → 自动跳；断点已过片头不触发
        assertTrue(IntroSkip.shouldAutoSkip(0L, 90_000L, true, false))
        assertTrue(IntroSkip.shouldAutoSkip(45_000L, 90_000L, true, false))
        assertFalse(IntroSkip.shouldAutoSkip(90_000L, 90_000L, true, false))
        assertFalse(IntroSkip.shouldAutoSkip(120_000L, 90_000L, true, false))
        assertFalse(IntroSkip.shouldAutoSkip(0L, 90_000L, false, false))
        assertFalse(IntroSkip.shouldAutoSkip(0L, 90_000L, true, true))
        assertFalse(IntroSkip.shouldAutoSkip(0L, 0L, true, false))
    }

    @Test
    fun `skip intro capsule shows only once per episode`() {
        // P2-5：90 秒片头不再每 10 秒重现（之前约出现 9 次）
        assertTrue(IntroSkip.shouldShowSkipIntroCapsule(true, false, false))
        assertFalse(IntroSkip.shouldShowSkipIntroCapsule(true, true, false))
        assertFalse(IntroSkip.shouldShowSkipIntroCapsule(true, false, true))
        assertFalse(IntroSkip.shouldShowSkipIntroCapsule(false, false, false))
    }
}
