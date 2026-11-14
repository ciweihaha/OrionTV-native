package com.oriontv

import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityLeaveHintTest {

    @Test
    fun `background on and playing enters pip`() {
        assertEquals(
            LeaveHintAction.EnterPip,
            MainActivity.leaveHintAction(backgroundPlaybackEnabled = true, isPlaying = true),
        )
    }

    @Test
    fun `background off pauses even when playing`() {
        // P1-1：开关关着按 Home 必须暂停，否则 VM 不销毁、音频继续播
        assertEquals(
            LeaveHintAction.Pause,
            MainActivity.leaveHintAction(backgroundPlaybackEnabled = false, isPlaying = true),
        )
        assertEquals(
            LeaveHintAction.Pause,
            MainActivity.leaveHintAction(backgroundPlaybackEnabled = false, isPlaying = false),
        )
    }

    @Test
    fun `background on but not playing does nothing`() {
        assertEquals(
            LeaveHintAction.None,
            MainActivity.leaveHintAction(backgroundPlaybackEnabled = true, isPlaying = false),
        )
    }
}
