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
    fun `reclaim after finished episode restarts from zero even when player was stopped by another page`() {
        // A18-P2-1：onPageResumed() 的 reclaim 起点是
        // resolveSwitchStartMs(playerManager.isEnded || state.isEnded, positionMs)——
        // 本集播完后 state.isEnded 冻结为真、positionMs 冻结在片长，但播放器可能
        // 已被新页停掉/播别片、playerManager.isEnded 恒 false；只读播放器侧会以
        // 片长为起点重开、被起点溢出守卫误判播错。任一侧播完即从 0 播。
        val durationMs = 3_600_000L
        assertEquals(0L, PlayViewModel.resolveSwitchStartMs(isEnded = false || true, positionMs = durationMs))
        assertEquals(0L, PlayViewModel.resolveSwitchStartMs(isEnded = true || false, positionMs = durationMs))
        assertEquals(0L, PlayViewModel.resolveSwitchStartMs(isEnded = true || true, positionMs = durationMs))
        assertEquals(123_000L, PlayViewModel.resolveSwitchStartMs(isEnded = false || false, positionMs = 123_000L))
    }

    @Test
    fun `current episode position uses live position only when player holds this episode`() {
        // A23-P2-2：回页重开播/初载挂起窗口内，播放器仍挂着别页的媒体，
        // live 位置属于别片——换源/标记片头片尾须读本页冻结进度；只有
        // 播放器挂的 URL 与本集 URL 一致时才读 live 位置。
        val episodeUrl = "https://cdn.example.com/ep3.m3u8"
        val otherUrl = "https://cdn.example.com/other.m3u8"
        // 本集在播：读 live 位置。
        assertEquals(
            123_000L,
            PlayViewModel.resolveCurrentEpisodePositionMs(
                playerUrl = episodeUrl, episodeUrl = episodeUrl,
                livePositionMs = 123_000L, frozenPositionMs = 60_000L,
            ),
        )
        // 播放器挂着别页媒体：读冻结进度。
        assertEquals(
            60_000L,
            PlayViewModel.resolveCurrentEpisodePositionMs(
                playerUrl = otherUrl, episodeUrl = episodeUrl,
                livePositionMs = 900_000L, frozenPositionMs = 60_000L,
            ),
        )
        // 播放器为空媒体（开关关/未开播）：读冻结进度。
        assertEquals(
            60_000L,
            PlayViewModel.resolveCurrentEpisodePositionMs(
                playerUrl = null, episodeUrl = episodeUrl,
                livePositionMs = 0L, frozenPositionMs = 60_000L,
            ),
        )
        // 本集未定（current == null）：无从比对，读冻结进度。
        assertEquals(
            60_000L,
            PlayViewModel.resolveCurrentEpisodePositionMs(
                playerUrl = episodeUrl, episodeUrl = null,
                livePositionMs = 123_000L, frozenPositionMs = 60_000L,
            ),
        )
    }

    @Test
    fun `ended signal is trusted only when player holds this episode`() {
        // A24-P2-1：A23-P2-2 只门控了位置，没门控播完态——A 集看到一半离页去
        // 详情页播 B 集，B 集播完（playerManager.isEnded=true）后回 A 页，
        // 直接并上播放器侧播完态会把 A 集断点续播误判成从 0 重播；重试/换源同款。
        // state.isEnded 是本页冻结的本集播完态，始终可信，保留并上。
        val episodeUrl = "https://cdn.example.com/ep3.m3u8"
        val otherUrl = "https://cdn.example.com/other.m3u8"
        // 本集在播且播完：从 0 播。
        assertTrue(
            PlayViewModel.resolveIsEnded(
                playerUrl = episodeUrl, episodeUrl = episodeUrl,
                playerEnded = true, stateEnded = false,
            ),
        )
        // 别页媒体播完：本集中途，续播不断点。
        assertFalse(
            PlayViewModel.resolveIsEnded(
                playerUrl = otherUrl, episodeUrl = episodeUrl,
                playerEnded = true, stateEnded = false,
            ),
        )
        // 播放器已停（开关关/被别页停掉）+ 本集播完：state 侧冻结为真，从 0 播。
        assertTrue(
            PlayViewModel.resolveIsEnded(
                playerUrl = null, episodeUrl = episodeUrl,
                playerEnded = false, stateEnded = true,
            ),
        )
        // 播放器已停 + 本集中途：续播不断点。
        assertFalse(
            PlayViewModel.resolveIsEnded(
                playerUrl = null, episodeUrl = episodeUrl,
                playerEnded = false, stateEnded = false,
            ),
        )
        // 本集在播但未播完：续播。
        assertFalse(
            PlayViewModel.resolveIsEnded(
                playerUrl = episodeUrl, episodeUrl = episodeUrl,
                playerEnded = false, stateEnded = false,
            ),
        )
    }

    @Test
    fun `current episode media matches only this episode url`() {
        // A25-P2-1：认领后、挂片前的窗口内播放器仍挂着别页媒体——播完/
        // 播错回调与断点保存只凭归属门控恒为真；URL 比对是唯一能区分
        // 新旧媒体的门控（A23-P2-2/A24-P2-1 同款，抽成共用谓词）。
        val episodeUrl = "https://cdn.example.com/ep3.m3u8"
        val otherUrl = "https://cdn.example.com/other.m3u8"
        // 本集媒体：处理。
        assertTrue(PlayViewModel.isCurrentEpisodeMedia(episodeUrl, episodeUrl))
        // 别页媒体：丢弃。
        assertFalse(PlayViewModel.isCurrentEpisodeMedia(otherUrl, episodeUrl))
        // 播放器已停（无媒体）：无事件可达，丢弃。
        assertFalse(PlayViewModel.isCurrentEpisodeMedia(null, episodeUrl))
        // 本集未定（current == null）：无从比对，丢弃。
        assertFalse(PlayViewModel.isCurrentEpisodeMedia(episodeUrl, null))
        assertFalse(PlayViewModel.isCurrentEpisodeMedia(null, null))
    }

    @Test
    fun `poll tick advances only when player holds this episode`() {
        // A26-P2-1：A25-P2-1 只给播完/播错/断点保存加了 URL 门控，轮询是唯一
        // 没加门控的 live 信号读点——认领后、挂片前的窗口内归属门控恒为真，
        // 别页媒体的位置/时长/播完态会直接读进本集：自动跳把 seek 发到别片
        // 媒体上、“每集一次”被消耗，写态还污染 position/duration/isEnded。
        val episodeUrl = "https://cdn.example.com/ep3.m3u8"
        val otherUrl = "https://cdn.example.com/other.m3u8"
        // 本集媒体已挂片：tick 推进，读 live 信号。
        assertTrue(PlayViewModel.shouldAdvancePollTick(episodeUrl, episodeUrl))
        // 播放器仍挂别页媒体（认领后、挂片前的窗口）：tick 跳过。
        assertFalse(PlayViewModel.shouldAdvancePollTick(otherUrl, episodeUrl))
        // 空媒体（初载搜源期 current 还没回来）：tick 跳过。
        assertFalse(PlayViewModel.shouldAdvancePollTick(null, episodeUrl))
        // 本集未定（current == null）：无从比对，tick 跳过。
        assertFalse(PlayViewModel.shouldAdvancePollTick(episodeUrl, null))
        assertFalse(PlayViewModel.shouldAdvancePollTick(null, null))
    }

    @Test
    fun `explicit start clears stale route position`() {
        // A27-P2-1：routePositionMs 是无集号作用域的“消费一次”槽位——
        // 显式起点开播已取代它，残留即过期；不清掉，下一次空起点开播
        // （下一集/选集）会把上一集的起点当成新集起点误播。
        // 显式起点优先，且槽位清零。
        assertEquals(90_000L to 0L, PlayViewModel.resolveStartPosition(90_000L, 45_000L))
        // 空起点开播消费回落值，同样清零。
        assertEquals(45_000L to 0L, PlayViewModel.resolveStartPosition(null, 45_000L))
        // 都没有：走断点记录，槽位保持 0。
        assertEquals(null to 0L, PlayViewModel.resolveStartPosition(null, 0L))
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
    fun `dismissed episode overlay returns after replay resets dismiss`() {
        // A40-P2-1：取消过预告卡后播完按 OK 重播，dismissed 标记随重播重置为 -1
        //（与 retry() 一致）——重看完整集时片尾附近仍可提前出卡、播完重弹。
        val duration = 1_800_000L
        // 取消过（dismissed = 本集集号）：播完也不弹
        assertFalse(PlayViewModel.shouldShowNextEpisode(true, 0, 10, 0))
        // 重播重置后（dismissed = -1）：片尾前 5 秒能提前出卡
        assertTrue(PlayViewModel.shouldShowNextEpisode(true, 0, 10, -1))
        assertTrue(
            PlayViewModel.shouldShowNextEpisodeEarly(
                nextEpisodeShownForIndex = -1, episodeIndex = 0,
                durationMs = duration, positionMs = 1_795_000L, outroStartMs = null,
                overlayEnabled = true, episodeCount = 10, dismissedForIndex = -1,
            ),
        )
        // 未重置时（dismissed 仍是本集集号）：片尾前 5 秒也不出卡
        assertFalse(
            PlayViewModel.shouldShowNextEpisodeEarly(
                nextEpisodeShownForIndex = -1, episodeIndex = 0,
                durationMs = duration, positionMs = 1_795_000L, outroStartMs = null,
                overlayEnabled = true, episodeCount = 10, dismissedForIndex = 0,
            ),
        )
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
    fun `next episode early overlay suppressed while loading`() {
        // A34-P2-1：重试/自动换源加载期间不提前出预告——位置仍在片尾窗口内、
        // guard 又已被 retry() 重置为 -1，不加 isLoading 门控下一 tick 即重弹、
        // 卡盖在加载动画上；加载完成后 isLoading 为假，片尾附近仍可正常出卡
        //（A7-P2-1 语义保留）。onPlaybackEnded 的播完出卡走 shouldShowNextEpisode，
        // 不经此谓词、不受门控影响。
        val duration = 1_800_000L
        // 加载中：其余条件全真也不出
        assertFalse(
            PlayViewModel.shouldShowNextEpisodeEarly(
                nextEpisodeShownForIndex = -1, episodeIndex = 0,
                durationMs = duration, positionMs = 1_795_000L, outroStartMs = null,
                overlayEnabled = true, episodeCount = 10, dismissedForIndex = -1,
                isLoading = true,
            ),
        )
        // 加载完成：同条件可出
        assertTrue(
            PlayViewModel.shouldShowNextEpisodeEarly(
                nextEpisodeShownForIndex = -1, episodeIndex = 0,
                durationMs = duration, positionMs = 1_795_000L, outroStartMs = null,
                overlayEnabled = true, episodeCount = 10, dismissedForIndex = -1,
                isLoading = false,
            ),
        )
        // 标过片尾处加载中：同样不出
        assertFalse(
            PlayViewModel.shouldShowNextEpisodeEarly(
                nextEpisodeShownForIndex = -1, episodeIndex = 0,
                durationMs = duration, positionMs = 1_700_000L, outroStartMs = 1_700_000L,
                overlayEnabled = true, episodeCount = 10, dismissedForIndex = -1,
                isLoading = true,
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
    fun `reclaim of paused episode remounts but stays paused`() {
        // A19-P2-2：已暂停的集被别页抢走播放器后回页，reclaim 仍触发（播放器里
        // 已无媒体项，跳过会把页面晾在黑屏上），但只重挂、不自动续播——
        // onPageResumed 把 userPaused 快照进 resumePaused，startEpisode 内
        // playUrl(autoPlay=false) 且 userPaused 保持真；暂停态下 20 秒就绪
        // 超时也不触发（P2-6 门控），轮询只读 isPlaying、不自动 play()。
        assertTrue(PlayViewModel.shouldReclaimPlayback(noError = true, isCurrentOwner = false, isEnded = false))
        assertFalse(PlayViewModel.shouldFireReadyTimeout(pageActive = true, isLoading = true, hasCurrent = true, userPaused = true))
        // 未暂停时 reclaim 照常自动播出。
        assertTrue(PlayViewModel.shouldFireReadyTimeout(pageActive = true, isLoading = true, hasCurrent = true, userPaused = false))
    }

    @Test
    fun `resumed page reclaims playback only when ownership was stolen`() {
        // A17-P2-1：只补“被抢走”一种情形——无错误态、且自己已不是播放器
        // 所有者时重开当前集；仍是所有者（用户暂停后去详情页再回）不续播，
        // 错误页不重开、仍走“重试”。
        assertTrue(PlayViewModel.shouldReclaimPlayback(noError = true, isCurrentOwner = false, isEnded = false))
        assertFalse(PlayViewModel.shouldReclaimPlayback(noError = true, isCurrentOwner = true, isEnded = false))
        assertFalse(PlayViewModel.shouldReclaimPlayback(noError = false, isCurrentOwner = false, isEnded = false))
        assertFalse(PlayViewModel.shouldReclaimPlayback(noError = false, isCurrentOwner = true, isEnded = false))
        // A67-P2-1：播完态不重开——预告卡与 3 秒倒计时正在走，从 0 重播会清掉
        // 卡片、取消自动切下一集；跳过 reclaim，卡片与倒计时按承诺走完。
        assertFalse(PlayViewModel.shouldReclaimPlayback(noError = true, isCurrentOwner = false, isEnded = true))
    }

    @Test
    fun `ended episode without media can still be replayed`() {
        // A67-P2-1：reclaim 在播完态跳过、用户按“取消”关卡后，页面停在无媒体
        // 的已播完态；OK 重播与选集抽屉选当前集是仅剩的重播路，只在已播完且
        // 有当前集时放行。非播完态不放行：A60 URL 门控保护的“别页媒体被误操作”
        // 原样保留；无当前集时无集可播。
        assertTrue(PlayViewModel.shouldReplayEndedEpisode(isEnded = true, hasCurrent = true))
        assertFalse(PlayViewModel.shouldReplayEndedEpisode(isEnded = false, hasCurrent = true))
        assertFalse(PlayViewModel.shouldReplayEndedEpisode(isEnded = true, hasCurrent = false))
        assertFalse(PlayViewModel.shouldReplayEndedEpisode(isEnded = false, hasCurrent = false))
    }

    @Test
    fun `episode generation bumps only when episode or source really changes`() {
        // A62-P2-1：同集同源重挂（回页 reclaim / 同集重试）不 +1——否则挂片完成
        // 瞬间清掉 A61-P2-2 保留的落点预览；换集/换源/自动换源仍 +1，P2-3
        // 清预览的本意保留。
        // 同集同源：不 +1（修复目标：回页 reclaim 保留落点预览）。
        assertFalse(PlayViewModel.shouldBumpEpisodeGeneration(episodeChanged = false, oldSource = "a", newSource = "a"))
        // 同集换源：+1。
        assertTrue(PlayViewModel.shouldBumpEpisodeGeneration(episodeChanged = false, oldSource = "a", newSource = "b"))
        // 换集同源（下一集/选集）：+1。
        assertTrue(PlayViewModel.shouldBumpEpisodeGeneration(episodeChanged = true, oldSource = "a", newSource = "a"))
        // 换集换源：+1。
        assertTrue(PlayViewModel.shouldBumpEpisodeGeneration(episodeChanged = true, oldSource = "a", newSource = "b"))
        // 首集开播（旧 current 为空，episodeChanged 恒真）：+1。
        assertTrue(PlayViewModel.shouldBumpEpisodeGeneration(episodeChanged = true, oldSource = null, newSource = "a"))
    }

    @Test
    fun `resumed page auto resumes only Home paused playback`() {
        // A22-P2-1：仍是所有者、未在播时只续播“按 Home 被系统暂停”的片——
        // MainActivity.onUserLeaveHint 只调 pause()、不碰 userPaused，
        // userPaused=false 就是 Home 暂停的准确信号；用户自己按了暂停
        // （userPaused=true）不动，播完态不动（play() 在 STATE_ENDED 下
        // 是空操作，重播走 togglePlayPause），在播/有错/未开播过也不动。
        // Home 暂停的标准情形：有当前集、无错、仍是所有者、未在播、
        // 非用户暂停、已开播过、未播完。
        assertTrue(
            PlayViewModel.shouldResumeAfterLeaveHint(
                hasCurrent = true, noError = true, isCurrentOwner = true,
                isPlaying = false, userPaused = false, playbackStarted = true, isEnded = false,
            ),
        )
        // 用户自己按了暂停：不动。
        assertFalse(
            PlayViewModel.shouldResumeAfterLeaveHint(
                hasCurrent = true, noError = true, isCurrentOwner = true,
                isPlaying = false, userPaused = true, playbackStarted = true, isEnded = false,
            ),
        )
        // 已在播：不动。
        assertFalse(
            PlayViewModel.shouldResumeAfterLeaveHint(
                hasCurrent = true, noError = true, isCurrentOwner = true,
                isPlaying = true, userPaused = false, playbackStarted = true, isEnded = false,
            ),
        )
        // 播完态：不动。
        assertFalse(
            PlayViewModel.shouldResumeAfterLeaveHint(
                hasCurrent = true, noError = true, isCurrentOwner = true,
                isPlaying = false, userPaused = false, playbackStarted = true, isEnded = true,
            ),
        )
        // 归属被抢走：不归本分支，走 reclaim 分支。
        assertFalse(
            PlayViewModel.shouldResumeAfterLeaveHint(
                hasCurrent = true, noError = true, isCurrentOwner = false,
                isPlaying = false, userPaused = false, playbackStarted = true, isEnded = false,
            ),
        )
        // 有错、无当前集、未开播过：都不动。
        assertFalse(
            PlayViewModel.shouldResumeAfterLeaveHint(
                hasCurrent = true, noError = false, isCurrentOwner = true,
                isPlaying = false, userPaused = false, playbackStarted = true, isEnded = false,
            ),
        )
        assertFalse(
            PlayViewModel.shouldResumeAfterLeaveHint(
                hasCurrent = false, noError = true, isCurrentOwner = true,
                isPlaying = false, userPaused = false, playbackStarted = true, isEnded = false,
            ),
        )
        assertFalse(
            PlayViewModel.shouldResumeAfterLeaveHint(
                hasCurrent = true, noError = true, isCurrentOwner = true,
                isPlaying = false, userPaused = false, playbackStarted = false, isEnded = false,
            ),
        )
    }

    @Test
    fun `intro learning is skipped when player holds no current episode media`() {
        // A58-P1-1：回页 reclaim 挂起窗口内播放器是空媒体（playerUrl=null、
        // live 位置 0、片长 0），数值判定全项通过——不加 URL 门控会把落点
        // 310 秒误学成片头；门控不成立时整个跳过学习。
        val episodeUrl = "https://example.com/a.m3u8"
        assertFalse(
            PlayViewModel.shouldLearnIntroEndOnSeek(
                playerUrl = null, episodeUrl = episodeUrl,
                fromMs = 0L, toMs = 310_000L, introEndMs = 90_000L,
                hasMark = false, durationMs = 0L,
            ),
        )
        assertFalse(
            PlayViewModel.shouldLearnIntroEndOnSeek(
                playerUrl = "https://example.com/b.m3u8", episodeUrl = episodeUrl,
                fromMs = 0L, toMs = 310_000L, introEndMs = 90_000L,
                hasMark = false, durationMs = 0L,
            ),
        )
        // 播放器挂着本集时正常学；冻结位置在片头内也不能代 fromMs 绕过门控。
        assertTrue(
            PlayViewModel.shouldLearnIntroEndOnSeek(
                playerUrl = episodeUrl, episodeUrl = episodeUrl,
                fromMs = 0L, toMs = 310_000L, introEndMs = 90_000L,
                hasMark = false, durationMs = 0L,
            ),
        )
        assertFalse(
            PlayViewModel.shouldLearnIntroEndOnSeek(
                playerUrl = episodeUrl, episodeUrl = episodeUrl,
                fromMs = 300_000L, toMs = 310_000L, introEndMs = 90_000L,
                hasMark = false, durationMs = 0L,
            ),
        )
    }
}
