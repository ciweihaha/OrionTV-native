package com.oriontv.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsInputLogicTest {

    @Test
    fun `left key consumes only when cursor was not at start`() {
        // C7-P2-3：按移动前光标位置判断——第 1 位按左键，移动前是 1、消费；
        // 已在行首（0）时放行回菜单。
        // Jewel 第 8 轮 P2-5：快照未取到（-1，无配对 KeyDown）时默认消费、
        // 不放行——旧实现把 -1 按行首处理，会误放行回菜单。
        assertTrue(shouldConsumeSettingsLeftKeyUp(1))
        assertTrue(shouldConsumeSettingsLeftKeyUp(5))
        assertFalse(shouldConsumeSettingsLeftKeyUp(0))
        assertTrue(shouldConsumeSettingsLeftKeyUp(-1))
    }
}
