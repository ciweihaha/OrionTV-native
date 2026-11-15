package com.oriontv.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jewel 第 8 轮 P2-8：设置页 tick 焦点恢复让位全局弹窗。 */
class SettingsFocusLogicTest {

    @Test
    fun `tick restores only when no global modal is open`() {
        // 无全局弹窗、无二维码弹窗：正常恢复。
        assertTrue(shouldRestoreSettingsFocusOnTick(1, false, false))
        assertTrue(shouldRestoreSettingsFocusOnTick(2, false, false))
        // tick 为 0（首进以外的无效值）：不恢复。
        assertFalse(shouldRestoreSettingsFocusOnTick(0, false, false))
        // 全局弹窗（登录框/更新卡片）开着：让位，不与卡片挂载抢焦争胜负。
        assertFalse(shouldRestoreSettingsFocusOnTick(1, true, false))
        assertFalse(shouldRestoreSettingsFocusOnTick(2, true, false))
        // 二维码弹窗开着：同样让位（C12-P2-5 门控合流进同一谓词）。
        assertFalse(shouldRestoreSettingsFocusOnTick(1, false, true))
        // 两弹窗同开：仍不恢复。
        assertFalse(shouldRestoreSettingsFocusOnTick(1, true, true))
    }
}
