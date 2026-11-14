package com.oriontv.feature.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayViewModelLogicTest {

    @Test
    fun `ready only when actually playing`() {
        // P1-4：光出时长不算就绪，分片全死的流不能清 isLoading、不能取消 20 秒超时
        assertTrue(PlayViewModel.isPlaybackReady(isPlaying = true))
        assertFalse(PlayViewModel.isPlaybackReady(isPlaying = false))
    }

    @Test
    fun `finished episode resumes from zero`() {
        // P1-6：play_time == total_time（看完）从 0 播，不立即“播完”
        assertEquals(0L, PlayViewModel.resolveRecordStartMs(1800, 1800))
        assertEquals(0L, PlayViewModel.resolveRecordStartMs(1801, 1800))
        // 没看完正常恢复
        assertEquals(600_000L, PlayViewModel.resolveRecordStartMs(600, 1800))
        assertEquals(0L, PlayViewModel.resolveRecordStartMs(0, 1800))
        assertEquals(0L, PlayViewModel.resolveRecordStartMs(-5, 1800))
    }

    @Test
    fun `near end counts as finished`() {
        // P1-6：距片尾不到 10 秒即视为看完，不存断点
        assertTrue(PlayViewModel.isNearEnd(1_795_000L, 1_800_000L))
        assertTrue(PlayViewModel.isNearEnd(1_800_000L, 1_800_000L))
        assertTrue(PlayViewModel.isNearEnd(1_790_000L, 1_800_000L))
        assertFalse(PlayViewModel.isNearEnd(1_789_999L, 1_800_000L))
        assertFalse(PlayViewModel.isNearEnd(600_000L, 1_800_000L))
        assertFalse(PlayViewModel.isNearEnd(0L, 0L))
    }
}
