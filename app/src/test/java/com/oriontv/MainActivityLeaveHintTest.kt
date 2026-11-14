package com.oriontv

import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityLeaveHintTest {

    @Test
    fun `background on and media loaded enters pip`() {
        // A8-P1-3：按 Home 时正在缓冲/已暂停，isPlaying=false 也要进 PiP——
        // 判定改按“已加载媒体项”，不再按“正在播”
        assertEquals(
            LeaveHintAction.EnterPip,
            MainActivity.leaveHintAction(backgroundPlaybackEnabled = true, hasMediaItem = true),
        )
    }

    @Test
    fun `background off pauses even when media loaded`() {
        // P1-1：开关关着按 Home 必须暂停，否则 VM 不销毁、音频继续播
        assertEquals(
            LeaveHintAction.Pause,
            MainActivity.leaveHintAction(backgroundPlaybackEnabled = false, hasMediaItem = true),
        )
        assertEquals(
            LeaveHintAction.Pause,
            MainActivity.leaveHintAction(backgroundPlaybackEnabled = false, hasMediaItem = false),
        )
    }

    @Test
    fun `background on but no media loaded does nothing`() {
        assertEquals(
            LeaveHintAction.None,
            MainActivity.leaveHintAction(backgroundPlaybackEnabled = true, hasMediaItem = false),
        )
    }
}
