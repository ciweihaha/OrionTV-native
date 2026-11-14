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
    fun `background playback starts only for a continuation`() {
        // A9-P1-2：与直播页同款门控——前台一律开播；后台只允许已开播过的延续
        //（PiP 内自动换源/下一集、开关开着的继续播）；搜源完成时的首播在后台
        // 一律暂缓、回前台再补开播。
        assertTrue(PlayViewModel.shouldStartPlayback(pageActive = true, backgroundPlaybackEnabled = false, playbackStarted = false))
        assertTrue(PlayViewModel.shouldStartPlayback(pageActive = true, backgroundPlaybackEnabled = true, playbackStarted = true))
        assertTrue(PlayViewModel.shouldStartPlayback(pageActive = false, backgroundPlaybackEnabled = true, playbackStarted = true))
        assertFalse(PlayViewModel.shouldStartPlayback(pageActive = false, backgroundPlaybackEnabled = true, playbackStarted = false))
        assertFalse(PlayViewModel.shouldStartPlayback(pageActive = false, backgroundPlaybackEnabled = false, playbackStarted = true))
        assertFalse(PlayViewModel.shouldStartPlayback(pageActive = false, backgroundPlaybackEnabled = false, playbackStarted = false))
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
    fun `polling advances in foreground or with background playback on`() {
        // P2-9：前台照常推进；切后台且后台播放关着时只空转（不刷 UI、不存断点）；
        // 后台播放开着时后台也要推进（自动跳片头/下一集预告在后台也要走）
        assertTrue(PlayViewModel.shouldPoll(isForeground = true, backgroundPlaybackEnabled = false))
        assertTrue(PlayViewModel.shouldPoll(isForeground = true, backgroundPlaybackEnabled = true))
        assertTrue(PlayViewModel.shouldPoll(isForeground = false, backgroundPlaybackEnabled = true))
        assertFalse(PlayViewModel.shouldPoll(isForeground = false, backgroundPlaybackEnabled = false))
    }

    @Test
    fun `ok replays only after natural end not after user pause`() {
        // 第 6 轮 P1-1：播完态（STATE_ENDED）下 play() 不重启，OK 须先 seekTo(0) 重播；
        // 单纯暂停（未播完）时 OK 只是恢复播放，不重播、不回 0。
        assertTrue(PlayViewModel.shouldReplayFromStart(isPlaying = false, isEnded = true))
        assertFalse(PlayViewModel.shouldReplayFromStart(isPlaying = false, isEnded = false))
        assertFalse(PlayViewModel.shouldReplayFromStart(isPlaying = true, isEnded = false))
        assertFalse(PlayViewModel.shouldReplayFromStart(isPlaying = true, isEnded = true))
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

    @Test
    fun `start position beyond new source duration is overflow`() {
        // A7-P2-2：起点 >= 时长即溢出（ExoPlayer 会把 seek 钳到片尾、立刻播完）；
        // 起点为 0、时长未知时不算。
        assertTrue(PlayViewModel.isStartPositionOverflow(1_200_000L, 1_200_000L))
        assertTrue(PlayViewModel.isStartPositionOverflow(1_500_000L, 1_200_000L))
        assertFalse(PlayViewModel.isStartPositionOverflow(600_000L, 1_200_000L))
        assertFalse(PlayViewModel.isStartPositionOverflow(0L, 1_200_000L))
        assertFalse(PlayViewModel.isStartPositionOverflow(600_000L, 0L))
        assertFalse(PlayViewModel.isStartPositionOverflow(0L, 0L))
    }
}
