package com.oriontv.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsInputLogicTest {

    @Test
    fun `left key consumes only when cursor was not at start`() {
        // C7-P2-3：按移动前光标位置判断——第 1 位按左键，移动前是 1、消费；
        // 已在行首（0）时放行回菜单；快照未取到（-1）时按行首处理，不消费。
        assertTrue(shouldConsumeSettingsLeftKeyUp(1))
        assertTrue(shouldConsumeSettingsLeftKeyUp(5))
        assertFalse(shouldConsumeSettingsLeftKeyUp(0))
        assertFalse(shouldConsumeSettingsLeftKeyUp(-1))
    }
}
