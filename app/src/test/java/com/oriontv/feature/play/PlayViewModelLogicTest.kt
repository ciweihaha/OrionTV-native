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

    @Test
    fun `seek target clamps to one second before duration`() {
        // P2-7：seekTo 内同样钳制到片尾前 1 秒
        assertEquals(59_000L, PlayViewModel.clampSeekTarget(60_000L, 60_000L))
        assertEquals(59_000L, PlayViewModel.clampSeekTarget(120_000L, 60_000L))
        assertEquals(30_000L, PlayViewModel.clampSeekTarget(30_000L, 60_000L))
        assertEquals(0L, PlayViewModel.clampSeekTarget(-5_000L, 60_000L))
        assertEquals(30_000L, PlayViewModel.clampSeekTarget(30_000L, 0L))
    }

    @Test
    fun `next episode overlay respects dismiss`() {
        // P1-1：没取消过 → 出现；本集点过“取消” → 播完也不再弹、不自动播下一集
        assertTrue(PlayViewModel.shouldShowNextEpisode(true, 0, 10, -1))
        assertFalse(PlayViewModel.shouldShowNextEpisode(true, 0, 10, 0))
        // 取消的是上一集，不影响本集
        assertTrue(PlayViewModel.shouldShowNextEpisode(true, 1, 10, 0))
        // 开关关掉 → 不出现
        assertFalse(PlayViewModel.shouldShowNextEpisode(false, 0, 10, -1))
        // 末集 → 不出现
        assertFalse(PlayViewModel.shouldShowNextEpisode(true, 9, 10, -1))
    }
}
