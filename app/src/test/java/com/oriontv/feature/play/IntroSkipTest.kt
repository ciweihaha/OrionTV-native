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
}
