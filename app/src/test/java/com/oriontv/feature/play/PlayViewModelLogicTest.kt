package com.oriontv.feature.play

import com.oriontv.data.api.SearchResult
import com.oriontv.ui.components.ToastEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayViewModelLogicTest {

    @Test
    fun `stale search callback does not start playback under a new key`() {
        // A11-P2-2：旧搜源回调只凭 current == null 开播，新 key 的 init()
        // 已清掉 current 后旧回调跑到，会让旧片在新 key 下开播；起搜时
        // 快照 key，回调开播前复核快照仍等于当前 key。
        assertTrue(PlayViewModel.shouldStartEpisodeFromSearch("a", "a", false, true))
        // 旧 key 回调：即使当前无结果、有结果，也不开播
        assertFalse(PlayViewModel.shouldStartEpisodeFromSearch("a", "b", false, true))
        // 同代但已有结果：只更新列表，不开播
        assertFalse(PlayViewModel.shouldStartEpisodeFromSearch("a", "a", true, true))
        // 同代但无结果：不开播
        assertFalse(PlayViewModel.shouldStartEpisodeFromSearch("a", "a", false, false))
    }

    @Test
    fun `ready only when actually playing`() {
        // P1-4：光出时长不算就绪，分片全死的流不能清 isLoading、不能取消 20 秒超时
        assertTrue(PlayViewModel.isPlaybackReady(isPlaying = true))
        assertFalse(PlayViewModel.isPlaybackReady(isPlaying = false))
    }

    @Test
    fun `ready timeout does not fire after leaving page`() {
        // A10-P2-1：离页后 20 秒超时到点也不触发——取消是协作式的，已越过
        // delay 的任务仍会恢复执行，凭 pageActive 二次核对，不白白消耗一次自动换源
        assertTrue(PlayViewModel.shouldFireReadyTimeout(pageActive = true, isLoading = true, hasCurrent = true, userPaused = false))
        assertFalse(PlayViewModel.shouldFireReadyTimeout(pageActive = false, isLoading = true, hasCurrent = true, userPaused = false))
        assertFalse(PlayViewModel.shouldFireReadyTimeout(pageActive = true, isLoading = false, hasCurrent = true, userPaused = false))
        assertFalse(PlayViewModel.shouldFireReadyTimeout(pageActive = true, isLoading = true, hasCurrent = false, userPaused = false))
        // 用户手动暂停不算断流（P2-6）
        assertFalse(PlayViewModel.shouldFireReadyTimeout(pageActive = true, isLoading = true, hasCurrent = true, userPaused = true))
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
    fun `resolveSwitchStartMs ended starts from zero otherwise keeps position`() {
        // A15-P2-1：播完态下当前进度即片尾位置，换源/重试传它会被起点溢出守卫
        // 误判为播错；播完态一律从 0 播，未播完时原样传回。
        assertEquals(0L, PlayViewModel.resolveSwitchStartMs(isEnded = true, positionMs = 3_600_000L))
        assertEquals(0L, PlayViewModel.resolveSwitchStartMs(isEnded = true, positionMs = null))
        assertEquals(123_000L, PlayViewModel.resolveSwitchStartMs(isEnded = false, positionMs = 123_000L))
        assertNull(PlayViewModel.resolveSwitchStartMs(isEnded = false, positionMs = null))
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

    @Test
    fun `seek away from tail clears next episode card`() {
        // 第 7 轮 P2-1：seek 后按新位置重算——落点离开片尾窗口（剩 10 秒内或
        // 已标记片尾处）时清掉，否则预告卡钉屏、“3 秒后自动播放”被冻结误导；
        // 落点仍在片尾窗口内时保留；片长未知时无法重算，不动。
        val duration = 1_800_000L
        // 剩 5 秒：保留
        assertFalse(PlayViewModel.shouldClearNextEpisodeOnSeek(1_795_000L, duration, null))
        // 恰好剩 10 秒：保留（窗口含边界）
        assertFalse(PlayViewModel.shouldClearNextEpisodeOnSeek(1_790_000L, duration, null))
        // 剩 10 秒零 1 毫秒：清掉
        assertTrue(PlayViewModel.shouldClearNextEpisodeOnSeek(1_789_999L, duration, null))
        // 快退回几分钟前：清掉
        assertTrue(PlayViewModel.shouldClearNextEpisodeOnSeek(600_000L, duration, null))
        // 已标记片尾处（播到标记点）：保留
        assertFalse(PlayViewModel.shouldClearNextEpisodeOnSeek(1_700_000L, duration, 1_700_000L))
        // 标记了片尾但落点在标记点之前：清掉
        assertTrue(PlayViewModel.shouldClearNextEpisodeOnSeek(600_000L, duration, 1_700_000L))
        // 片长未知：不动
        assertFalse(PlayViewModel.shouldClearNextEpisodeOnSeek(600_000L, 0L, null))
    }

    @Test
    fun `next episode early overlay can show again after replay marker reset`() {
        // 第 7 轮 P2-2：轮询门控对 nextEpisodeShownForIndex 的语义——OK 重播把
        // 标记重置为 -1（与 retry() 一致），重播后片尾前 10 秒才能再提前出卡；
        // 标记仍是本集集号时不再提前出（旧重播分支漏了重置，只有播完才重弹）。
        val duration = 1_800_000L
        // 重播后（标记 -1），播到剩 5 秒：提前出
        assertTrue(
            PlayViewModel.shouldShowNextEpisodeEarly(
                nextEpisodeShownForIndex = -1, episodeIndex = 0,
                durationMs = duration, positionMs = 1_795_000L, outroStartMs = null,
                overlayEnabled = true, episodeCount = 10, dismissedForIndex = -1,
            ),
        )
        // 本集已出过（标记 0）：不再提前出
        assertFalse(
            PlayViewModel.shouldShowNextEpisodeEarly(
                nextEpisodeShownForIndex = 0, episodeIndex = 0,
                durationMs = duration, positionMs = 1_795_000L, outroStartMs = null,
                overlayEnabled = true, episodeCount = 10, dismissedForIndex = -1,
            ),
        )
        // 标记是上一集（换集后）：本集能提前出
        assertTrue(
            PlayViewModel.shouldShowNextEpisodeEarly(
                nextEpisodeShownForIndex = 0, episodeIndex = 1,
                durationMs = duration, positionMs = 1_795_000L, outroStartMs = null,
                overlayEnabled = true, episodeCount = 10, dismissedForIndex = -1,
            ),
        )
        // 位置不在片尾窗口：不出
        assertFalse(
            PlayViewModel.shouldShowNextEpisodeEarly(
                nextEpisodeShownForIndex = -1, episodeIndex = 0,
                durationMs = duration, positionMs = 600_000L, outroStartMs = null,
                overlayEnabled = true, episodeCount = 10, dismissedForIndex = -1,
            ),
        )
        // 标过片尾且播到标记处：提前出
        assertTrue(
            PlayViewModel.shouldShowNextEpisodeEarly(
                nextEpisodeShownForIndex = -1, episodeIndex = 0,
                durationMs = duration, positionMs = 1_700_000L, outroStartMs = 1_700_000L,
                overlayEnabled = true, episodeCount = 10, dismissedForIndex = -1,
            ),
        )
        // 开关关/点过取消/末集：不出
        assertFalse(
            PlayViewModel.shouldShowNextEpisodeEarly(
                nextEpisodeShownForIndex = -1, episodeIndex = 0,
                durationMs = duration, positionMs = 1_795_000L, outroStartMs = null,
                overlayEnabled = false, episodeCount = 10, dismissedForIndex = -1,
            ),
        )
        assertFalse(
            PlayViewModel.shouldShowNextEpisodeEarly(
                nextEpisodeShownForIndex = -1, episodeIndex = 0,
                durationMs = duration, positionMs = 1_795_000L, outroStartMs = null,
                overlayEnabled = true, episodeCount = 10, dismissedForIndex = 0,
            ),
        )
        assertFalse(
            PlayViewModel.shouldShowNextEpisodeEarly(
                nextEpisodeShownForIndex = -1, episodeIndex = 9,
                durationMs = duration, positionMs = 1_795_000L, outroStartMs = null,
                overlayEnabled = true, episodeCount = 10, dismissedForIndex = -1,
            ),
        )
    }

    @Test
    fun `playback errors are ignored after leaving page unless background continuation`() {
        // 第 8 轮 P2-3：离页后播放器错误回调不累加失败计数、不弹 Toast、不落错误态——
        // 重播已被 startEpisode 的门控拦住，但计数与 Toast 在门控之前；门控与开播
        // 同谓词（前台一律处理，后台只处理已开播过的延续）；离页且后台播放关着时
        // 只记 pendingStart 待补开播，回页重播当前源。
        assertTrue(PlayViewModel.shouldHandlePlaybackError(pageActive = true, backgroundPlaybackEnabled = false, playbackStarted = false))
        assertTrue(PlayViewModel.shouldHandlePlaybackError(pageActive = true, backgroundPlaybackEnabled = true, playbackStarted = true))
        assertTrue(PlayViewModel.shouldHandlePlaybackError(pageActive = false, backgroundPlaybackEnabled = true, playbackStarted = true))
        assertFalse(PlayViewModel.shouldHandlePlaybackError(pageActive = false, backgroundPlaybackEnabled = true, playbackStarted = false))
        assertFalse(PlayViewModel.shouldHandlePlaybackError(pageActive = false, backgroundPlaybackEnabled = false, playbackStarted = true))
        assertFalse(PlayViewModel.shouldHandlePlaybackError(pageActive = false, backgroundPlaybackEnabled = false, playbackStarted = false))
    }

    @Test
    fun `stale search callback is dropped by key generation`() {
        // 第 8 轮 P2-2：旧搜源回调已在 IO 线程执行中时，init() 清 current 与取消
        // 都拦不住它；凭代际快照丢弃——同键接受、换 key 后旧回调一律丢弃。
        assertTrue(PlayViewModel.shouldAcceptSearchResults("a:1", "a:1"))
        assertFalse(PlayViewModel.shouldAcceptSearchResults("a:1", "b:2"))
        assertFalse(PlayViewModel.shouldAcceptSearchResults("a:1", ""))
        assertTrue(PlayViewModel.shouldAcceptSearchResults("", ""))
    }

    @Test
    fun `new key resets stale playback ui state`() {
        // 第 8 轮 P2-1：同回栈项复用换 key 时，旧集的下一集预告卡、跳过片头胶囊、
        // 抽屉与旧片长不能带到新搜源加载期间（会叠在加载动画之上）；搜源回调
        // 只在 current == null 时开播，旧结果同清。
        val stale = PlayUiState(
            isLoading = false,
            error = "旧错误",
            title = "旧片",
            results = listOf(SearchResult(title = "旧片", source = "a")),
            current = SearchResult(title = "旧片", source = "a"),
            episodeIndex = 3,
            positionMs = 600_000L,
            durationMs = 1_800_000L,
            isEnded = true,
            showNextEpisode = true,
            autoSwitchAttempts = 2,
            failedSources = setOf("a"),
            skipIntroVisible = true,
            drawer = PlayDrawer.EPISODES,
            // A11-P2-1（main 第 11 轮）：缓冲/断点位置同样不能沿用旧值，
            // 复位时一并清零——rebase 时补进 helper 的两项。
            bufferedMs = 5000L,
            breakpointMs = 600_000L,
            // 第 9 轮 P2-1：片头/标记/播放态同样不能沿用旧值——搜源期轮询
            // 会用默认值 90 秒或旧片值在位置 0 跑自动跳与胶囊，控制条还
            // 会短暂显示旧片的标记文案与旧倍速/播放态。
            introEndMs = 90_000L,
            markedIntroEndMs = 45_000L,
            outroStartMs = 1_200_000L,
            isPlaying = true,
            isBuffering = true,
            playbackRate = 2f,
            toast = ToastEvent(id = 1L, message = "旧提示"),
        )
        val reset = PlayViewModel.resetUiStateForNewKey(
            stale, episodeIndex = 5, positionMs = 0L, title = "新片",
        )
        assertTrue(reset.isLoading)
        assertEquals(5, reset.episodeIndex)
        assertEquals(0L, reset.positionMs)
        assertEquals("新片", reset.title)
        assertEquals(null, reset.current)
        assertEquals(emptyList<SearchResult>(), reset.results)
        assertEquals(null, reset.error)
        assertEquals(0, reset.autoSwitchAttempts)
        assertEquals(emptySet<String>(), reset.failedSources)
        assertFalse(reset.showNextEpisode)
        assertFalse(reset.isEnded)
        assertFalse(reset.skipIntroVisible)
        assertEquals(null, reset.drawer)
        assertEquals(0L, reset.durationMs)
        assertEquals(0L, reset.bufferedMs)
        assertEquals(0L, reset.breakpointMs)
        // 第 9 轮 P2-1：片头清 0（不是默认值 90 秒）、标记清掉、播放态复位，
        // 旧提示也不带到新片搜源期。
        assertEquals(0L, reset.introEndMs)
        assertNull(reset.markedIntroEndMs)
        assertNull(reset.outroStartMs)
        assertFalse(reset.isPlaying)
        assertFalse(reset.isBuffering)
        assertEquals(1f, reset.playbackRate)
        assertNull(reset.toast)
    }

    @Test
    fun `auto skip waits for duration before jumping`() {
        // 第 9 轮 P2-2：片长未知时暂缓自动跳——慢源上片长晚于首个轮询 tick
        // 到达，位置 0 + 默认 90 秒片头会直接 seek 到片尾、短片被判“看完一集”。
        // 轮询每 500ms 再来，片长一出、仍在片头窗口内就跳，不丢跳。
        assertFalse(PlayViewModel.shouldAutoSkipNow(0L, 90_000L, true, false, durationMs = 0L))
        assertFalse(PlayViewModel.shouldAutoSkipNow(0L, 90_000L, true, false, durationMs = -1L))
        // 片长已知：短片守卫仍在（60 秒片 + 90 秒片头不跳），长片正常跳
        assertFalse(PlayViewModel.shouldAutoSkipNow(0L, 90_000L, true, false, durationMs = 60_000L))
        assertTrue(PlayViewModel.shouldAutoSkipNow(0L, 90_000L, true, false, durationMs = 1_800_000L))
        // 片长已知但位置已过片头 / 开关关着 / 本集已跳过 → 不跳
        assertFalse(PlayViewModel.shouldAutoSkipNow(120_000L, 90_000L, true, false, durationMs = 1_800_000L))
        assertFalse(PlayViewModel.shouldAutoSkipNow(0L, 90_000L, false, false, durationMs = 1_800_000L))
        assertFalse(PlayViewModel.shouldAutoSkipNow(0L, 90_000L, true, true, durationMs = 1_800_000L))
    }

    @Test
    fun `same episode source switch rearms intro skip only inside intro window`() {
        // 第 7 轮 P2-3：同集换源/自动换源/重试时，新起点仍在片头窗口内才重 arm
        // 自动跳与胶囊——开播早期换源后新源上自动跳与胶囊才重现；起点已过片头
        // （或没片头可跳）时不重 arm，“每集一次”不被换源重置。
        assertTrue(PlayViewModel.shouldRearmIntroSkipOnSameEpisode(30_000L, 90_000L))
        assertTrue(PlayViewModel.shouldRearmIntroSkipOnSameEpisode(0L, 90_000L))
        assertFalse(PlayViewModel.shouldRearmIntroSkipOnSameEpisode(120_000L, 90_000L))
        assertFalse(PlayViewModel.shouldRearmIntroSkipOnSameEpisode(90_000L, 90_000L))
        assertFalse(PlayViewModel.shouldRearmIntroSkipOnSameEpisode(30_000L, 0L))
    }

    @Test
    fun `same episode new start position seeks only when it differs`() {
        // A12-P2-2：同 key、同集、只换了新起点时直接 seek 一次；新起点为 0
        // 不算“新起点”，等于当前进度时不动（免得重复深链把位置抖回去）。
        assertTrue(PlayViewModel.shouldSeekToNewPosition(120_000L, 30_000L))
        assertTrue(PlayViewModel.shouldSeekToNewPosition(1L, 0L))
        assertFalse(PlayViewModel.shouldSeekToNewPosition(0L, 30_000L))
        assertFalse(PlayViewModel.shouldSeekToNewPosition(30_000L, 30_000L))
    }

    @Test
    fun `resumed page reclaims playback only when ownership was stolen`() {
        // A17-P2-1：只补“被抢走”一种情形——无错误态、且自己已不是播放器
        // 所有者时重开当前集；仍是所有者（用户暂停后去详情页再回）不续播，
        // 错误页不重开、仍走“重试”。
        assertTrue(PlayViewModel.shouldReclaimPlayback(noError = true, isCurrentOwner = false))
        assertFalse(PlayViewModel.shouldReclaimPlayback(noError = true, isCurrentOwner = true))
        assertFalse(PlayViewModel.shouldReclaimPlayback(noError = false, isCurrentOwner = false))
        assertFalse(PlayViewModel.shouldReclaimPlayback(noError = false, isCurrentOwner = true))
    }
}
