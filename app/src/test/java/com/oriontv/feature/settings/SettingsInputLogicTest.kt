package com.oriontv.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsInputLogicTest {

    @Test
    fun `left key consumes only when cursor was not at start`() {
        // C7-P2-3：按移动前光标位置判断——光标在中间按左键，移动前后都不在
        // 行首，消费；已在行首（0）时放行回菜单。
        // Jewel 第 8 轮 P2-5：快照未取到（-1，无配对 KeyDown）时默认消费、
        // 不放行——旧实现把 -1 按行首处理，会误放行回菜单。
        assertTrue(shouldConsumeSettingsLeftKeyUp(5, 4))
        assertFalse(shouldConsumeSettingsLeftKeyUp(0, 0))
        assertTrue(shouldConsumeSettingsLeftKeyUp(-1, 3))
        assertTrue(shouldConsumeSettingsLeftKeyUp(-1, 0))
    }

    @Test
    fun `long press to row start releases on key up`() {
        // Jewel 第 9 轮 P2-8：长按左键到行首的 quirk——最后一次快照是到 0
        // 之前的位置（1），只按快照判定会误消费、松手后仍留在框内；取
        // min(快照, 当前光标)，光标已在行首即放行回菜单。短按从 1 到 0 同款：
        // 按一次左键光标落到行首，松手即回菜单（与已在行首短按行为一致）。
        assertFalse(shouldConsumeSettingsLeftKeyUp(1, 0))
        // 光标还在中间（快照 2、当前 1）：消费，只移光标、不回菜单。
        assertTrue(shouldConsumeSettingsLeftKeyUp(2, 1))
    }
}
