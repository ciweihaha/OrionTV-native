package com.oriontv.feature.play

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayScreenLogicTest {

    @Test
    fun `next episode forces controls visible even after auto-hide`() {
        // P1-3：播完时控制条已 5 秒自动隐藏，预告卡为真时仍须显示
        assertTrue(shouldShowPlayerControls(false, false, true))
        assertTrue(shouldShowPlayerControls(true, false, false))
        assertTrue(shouldShowPlayerControls(true, false, true))
    }

    @Test
    fun `error hides controls even with next episode`() {
        assertFalse(shouldShowPlayerControls(true, true, true))
        assertFalse(shouldShowPlayerControls(true, true, false))
        assertFalse(shouldShowPlayerControls(false, false, false))
    }

    @Test
    fun `seek keys intercepted only when controls shown and drawer closed`() {
        // P1-2：控制条可见时左/右恒为快进预览；抽屉打开或控制条未显示时不拦截
        assertTrue(shouldInterceptSeekKeys(controlsShown = true, drawerOpen = false))
        assertFalse(shouldInterceptSeekKeys(controlsShown = true, drawerOpen = true))
        assertFalse(shouldInterceptSeekKeys(controlsShown = false, drawerOpen = false))
    }

    @Test
    fun `seek keys not intercepted while focus is on control buttons`() {
        // P1-B：第 5 轮焦点模型——焦点已在按钮行上时左/右在按钮间横向移动，
        // 不再被预览拦截；焦点回到根部时左/右恢复快进预览。
        assertFalse(
            shouldInterceptSeekKeys(
                controlsShown = true,
                drawerOpen = false,
                focusOnControls = true,
            ),
        )
        assertTrue(
            shouldInterceptSeekKeys(
                controlsShown = true,
                drawerOpen = false,
                focusOnControls = false,
            ),
        )
        // 抽屉打开时无论焦点在哪都不拦截（抽屉内横向移动）
        assertFalse(
            shouldInterceptSeekKeys(
                controlsShown = true,
                drawerOpen = true,
                focusOnControls = true,
            ),
        )
    }
}
