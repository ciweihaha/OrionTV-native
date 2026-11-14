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

    @Test
    fun `auto-hide fires only with controls shown and nothing else holding them`() {
        // P2-2：控制条显示、抽屉关闭、无待定落点、无下一集预告、无错误页时才隐藏；
        // 错误页上计时器不武装——否则 5 秒后焦点被从“重试”拉回根部。
        assertTrue(shouldAutoHideControls(true, false, false, false, false))
        assertFalse(shouldAutoHideControls(false, false, false, false, false))
        assertFalse(shouldAutoHideControls(true, true, false, false, false))
        assertFalse(shouldAutoHideControls(true, false, true, false, false))
        assertFalse(shouldAutoHideControls(true, false, false, true, false))
        assertFalse(shouldAutoHideControls(true, false, false, false, true))
    }

    @Test
    fun `preview auto-commit pauses while drawer open`() {
        // P2-3：有待定落点且抽屉关闭时才 2 秒自动提交；抽屉打开时不提交，
        // 否则挑集时视频在抽屉后跳进度。
        assertTrue(shouldAutoCommitPreview(30_000L, drawerOpen = false))
        assertFalse(shouldAutoCommitPreview(30_000L, drawerOpen = true))
        assertFalse(shouldAutoCommitPreview(-1L, drawerOpen = false))
        assertFalse(shouldAutoCommitPreview(-1L, drawerOpen = true))
    }

    @Test
    fun `auto-hide timer resets on control-row navigation but not on error page`() {
        // P2-1：按钮行内按键（横向移动/进出）重置 5 秒计时器；
        // P2-2：错误页上不重置——错误页没有按钮行，按键只移动“重试/返回”焦点。
        assertTrue(shouldResetAutoHideTimer(true, false, false))
        assertFalse(shouldResetAutoHideTimer(false, false, false))
        assertFalse(shouldResetAutoHideTimer(true, true, false))
        assertFalse(shouldResetAutoHideTimer(true, false, true))
    }
}
