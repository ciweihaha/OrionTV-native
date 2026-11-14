package com.oriontv.feature.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveViewModelLogicTest {

    @Test
    fun `panel timer pauses while epg dialog open`() {
        // P1-4：弹窗打开期间暂停面板 15 秒定时器（弹窗内上/下浏览不再被定时器打断），关闭后恢复。
        assertTrue(LiveViewModel.shouldArmPanelTimer(panelOpen = true, epgDialogOpen = false))
        assertFalse(LiveViewModel.shouldArmPanelTimer(panelOpen = true, epgDialogOpen = true))
        assertFalse(LiveViewModel.shouldArmPanelTimer(panelOpen = false, epgDialogOpen = false))
        assertFalse(LiveViewModel.shouldArmPanelTimer(panelOpen = false, epgDialogOpen = true))
    }
}
