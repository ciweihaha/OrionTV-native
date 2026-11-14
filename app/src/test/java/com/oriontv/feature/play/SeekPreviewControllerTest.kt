package com.oriontv.feature.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekPreviewControllerTest {

    @Test
    fun `nudge moves pending target in ten second steps`() {
        val controller = SeekPreviewController(durationMs = 3_600_000)
        assertEquals(310_000L, controller.nudge(300_000, 1))
        assertEquals(320_000L, controller.nudge(300_000, 1))
        assertEquals(310_000L, controller.nudge(300_000, -1))
        assertTrue(controller.hasPending)
    }

    @Test
    fun `nudge clamps to zero and one second before duration`() {
        // P2-7：快进钳制到片尾前 1 秒，seek 到正好片尾会立即 STATE_ENDED
        val controller = SeekPreviewController(durationMs = 60_000)
        assertEquals(0L, controller.nudge(5_000, -1))
        assertEquals(59_000L, controller.nudge(55_000, 1))
        assertEquals(59_000L, controller.nudge(55_000, 1))
    }

    @Test
    fun `clamped end target restarts from current position`() {
        // 钳制在片尾边缘的旧落点、播头已不在该处时，从当前播头重新起步
        val controller = SeekPreviewController(durationMs = 60_000)
        controller.nudge(55_000, 1) // 59_000
        assertEquals(40_000L, controller.nudge(30_000, 1))
    }

    @Test
    fun `commit returns target and clears pending`() {
        val controller = SeekPreviewController(durationMs = 60_000)
        controller.nudge(10_000, 1)
        assertEquals(20_000L, controller.commit())
        assertFalse(controller.hasPending)
        assertNull(controller.commit())
    }

    @Test
    fun `cancel clears pending without seeking`() {
        val controller = SeekPreviewController(durationMs = 60_000)
        controller.nudge(10_000, 1)
        controller.cancel()
        assertFalse(controller.hasPending)
        assertNull(controller.commit())
    }
}
