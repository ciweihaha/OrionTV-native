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

    @Test
    fun `nudge returns null while duration unknown`() {
        // P1-3：时长未出时不产生预览，不会造一个 00:00 落点再 2 秒后 seek 回片头
        val controller = SeekPreviewController(durationMs = 0)
        assertNull(controller.nudge(0, 1))
        assertNull(controller.nudge(0, -1))
        assertFalse(controller.hasPending)
        val negative = SeekPreviewController(durationMs = -1)
        assertNull(negative.nudge(0, 1))
    }

    @Test
    fun `nudge returns null when already at start or end with no pending target`() {
        // P2-12：播头按左（落点仍是 0）、片尾前按右（落点仍在原地）不产生预览，
        // 不会冒一个无意义的 00:00 气泡、2 秒后又 seek 回原地
        val controller = SeekPreviewController(durationMs = 60_000)
        assertNull(controller.nudge(0, -1))
        assertFalse(controller.hasPending)
        assertNull(controller.nudge(59_000, 1))
        assertFalse(controller.hasPending)
        // 正常位置仍可步进；有待定落点时在原地钳制也不算无意义（落点已存在）
        assertEquals(10_000L, controller.nudge(0, 1))
        assertEquals(0L, controller.nudge(0, -1))
    }

    @Test
    fun `reverse past start cancels preview instead of jumping`() {
        // P2-2：落点已在 0 再反向按即取消（按类注释实现），不是跳变到“播头-10s”
        val controller = SeekPreviewController(durationMs = 60_000)
        assertEquals(0L, controller.nudge(5_000, -1))
        assertTrue(controller.hasPending)
        assertNull(controller.nudge(5_000, -1))
        assertFalse(controller.hasPending)
        // 取消后从播头重新起步
        assertEquals(0L, controller.nudge(5_000, -1))
    }

    @Test
    fun `reverse from zero steps forward from current position`() {
        // P2-2 只取消“反向按过起点”：落点在 0 时按右仍从播头重新起步（旧行为保留）
        val controller = SeekPreviewController(durationMs = 60_000)
        assertEquals(0L, controller.nudge(5_000, -1))
        assertEquals(45_000L, controller.nudge(35_000, 1))
    }
}
