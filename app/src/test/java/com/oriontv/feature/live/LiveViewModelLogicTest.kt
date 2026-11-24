package com.oriontv.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveViewModelLogicTest {

    private fun channel(name: String, url: String) =
        M3uParser.Channel(id = url, name = name, urls = listOf(url))

    @Test
    fun `pending playback tasks do not fire after page paused`() {
        // 第 7 轮 P1-1：离页后就绪超时/数字键提交/面板定时器到点也不应再播放——
        // 取消是协作式的，已越过 delay 的任务仍会恢复执行，凭 pagePaused 二次核对。
        assertTrue(LiveViewModel.shouldFirePendingPlaybackTask(pagePaused = false))
        assertFalse(LiveViewModel.shouldFirePendingPlaybackTask(pagePaused = true))
    }

    @Test
    fun `epg dialog channel is rebuilt from refreshed list`() {
        // 第 7 轮 P2-12：刷新重建频道对象后，弹窗按新表重建——旧对象继续持着，
        // indexOfChannel 按身份/（url, name）都匹配不到，标题无台号、切台失效。
        val old = channel("CCTV-1", "http://a/1.m3u8")
        val freshSame = channel("CCTV-1", "http://a/1.m3u8")
        val freshOther = channel("CCTV-5", "http://a/5.m3u8")
        val current = channel("广东卫视", "http://a/gd.m3u8")
        val fresh = listOf(freshSame, freshOther, current)
        // (url, name) 命中 → 新对象（不是旧对象）
        val rebuilt = LiveViewModel.resolveRefreshedEpgChannel(fresh, old, current)
        assertTrue(rebuilt === freshSame)
        // URL 轮换、名字还在 → 按名找回
        val renamed = LiveViewModel.resolveRefreshedEpgChannel(
            fresh, channel("CCTV-1", "http://a/new.m3u8"), current,
        )
        assertTrue(renamed === freshSame)
        // 改名、URL 还在 → 按 URL 找回
        val reUrl = LiveViewModel.resolveRefreshedEpgChannel(
            fresh, channel("CCTV-1 改版", "http://a/1.m3u8"), current,
        )
        assertTrue(reUrl === freshSame)
        // 新表里完全没了 → 回落到当前播放台，不持旧对象
        val gone = LiveViewModel.resolveRefreshedEpgChannel(
            fresh, channel("已下线", "http://a/gone.m3u8"), current,
        )
        assertTrue(gone === current)
        // 新表里没了、当前台也没有 → 返回 null（调用方关闭弹窗）
        assertEquals(
            null,
            LiveViewModel.resolveRefreshedEpgChannel(
                fresh, channel("已下线", "http://a/gone.m3u8"), null,
            ),
        )
    }

    @Test
    fun `panel timer pauses while epg dialog open`() {
        // P1-4：弹窗打开期间暂停面板 15 秒定时器（弹窗内上/下浏览不再被定时器打断），关闭后恢复。
        assertTrue(LiveViewModel.shouldArmPanelTimer(panelOpen = true, epgDialogOpen = false))
        assertFalse(LiveViewModel.shouldArmPanelTimer(panelOpen = true, epgDialogOpen = true))
        assertFalse(LiveViewModel.shouldArmPanelTimer(panelOpen = false, epgDialogOpen = false))
        assertFalse(LiveViewModel.shouldArmPanelTimer(panelOpen = false, epgDialogOpen = true))
    }

    @Test
    fun `live ticker runs only in foreground`() {
        // P2-9：切后台时时钟/EPG 刷新都停，恢复前台后下一跳继续
        assertTrue(LiveViewModel.shouldRunTicker(isForeground = true))
        assertFalse(LiveViewModel.shouldRunTicker(isForeground = false))
    }

    @Test
    fun `epg cache is stale only when no programme is still playing`() {
        // A35-P2-1：回页时先判 stale 再拉——空缓存 stale；任一频道有正在播的
        // 节目（endAt > now）即新鲜；全部播完（尤其午夜跨天）即 stale；空表 stale。
        val now = 1_000_000L
        assertTrue(LiveViewModel.isEpgStale(null, now))
        val live = EpgParser.EpgData(
            programmesByChannel = mapOf(
                "cctv1" to listOf(
                    EpgParser.Programme("cctv1", "新闻联播", now - 1_000, now + 1_000),
                ),
            ),
            channelNames = mapOf("cctv1" to "CCTV-1"),
        )
        assertFalse(LiveViewModel.isEpgStale(live, now))
        val expired = EpgParser.EpgData(
            programmesByChannel = mapOf(
                "cctv1" to listOf(
                    EpgParser.Programme("cctv1", "新闻联播", now - 2_000, now - 1_000),
                ),
            ),
            channelNames = mapOf("cctv1" to "CCTV-1"),
        )
        assertTrue(LiveViewModel.isEpgStale(expired, now))
        val empty = EpgParser.EpgData(
            programmesByChannel = emptyMap(),
            channelNames = emptyMap(),
        )
        assertTrue(LiveViewModel.isEpgStale(empty, now))
    }

    @Test
    fun `page resume replays only when not already owner and playing`() {
        // 第 6 轮 P1-4：应用内导航返回（导航轨切走再回来）也要恢复——
        // 已是本页 token 所有者且在播时不动；被抢走/停掉时重开播当前台。
        assertFalse(LiveViewModel.shouldResumePlayback(isCurrentOwner = true, isPlaying = true))
        assertTrue(LiveViewModel.shouldResumePlayback(isCurrentOwner = false, isPlaying = true))
        assertTrue(LiveViewModel.shouldResumePlayback(isCurrentOwner = true, isPlaying = false))
        assertTrue(LiveViewModel.shouldResumePlayback(isCurrentOwner = false, isPlaying = false))
        // A10-P2-2：失败页切走再回不自动重播——失败时播放器不在播，旧谓词
        // 恒为真，回页即悄悄重播一次；重试留给失败页“重试”按钮
        assertFalse(LiveViewModel.shouldResumePlayback(isCurrentOwner = true, isPlaying = false, playbackFailed = true))
        assertFalse(LiveViewModel.shouldResumePlayback(isCurrentOwner = false, isPlaying = false, playbackFailed = true))
        assertFalse(LiveViewModel.shouldResumePlayback(isCurrentOwner = false, isPlaying = true, playbackFailed = true))
    }

    @Test
    fun `closing panel replays signal error unless digit switch skips it`() {
        // A10-P2-3：信号异常且未在播时关面板重播当前台（P2-13 有意设计）；
        // 数字键换台先关面板时跳过这次重播，随后切台那一次就够了
        assertTrue(LiveViewModel.shouldReplaySignalOnClosePanel(signalError = true, isPlaying = false, hasChannels = true, pagePaused = false, skipSignalReplay = false))
        assertFalse(LiveViewModel.shouldReplaySignalOnClosePanel(signalError = true, isPlaying = false, hasChannels = true, pagePaused = false, skipSignalReplay = true))
        // 已在播（流已恢复）或无信号异常时不动作
        assertFalse(LiveViewModel.shouldReplaySignalOnClosePanel(signalError = true, isPlaying = true, hasChannels = true, pagePaused = false, skipSignalReplay = false))
        assertFalse(LiveViewModel.shouldReplaySignalOnClosePanel(signalError = false, isPlaying = false, hasChannels = true, pagePaused = false, skipSignalReplay = false))
        // 无频道、离页后也不重播
        assertFalse(LiveViewModel.shouldReplaySignalOnClosePanel(signalError = true, isPlaying = false, hasChannels = false, pagePaused = false, skipSignalReplay = false))
        assertFalse(LiveViewModel.shouldReplaySignalOnClosePanel(signalError = true, isPlaying = false, hasChannels = true, pagePaused = true, skipSignalReplay = false))
    }

    @Test
    fun `invalid digit replays signal error just like closing panel without skip`() {
        // 第 8 轮 P1-1：数字键关面板时跳过信号重播，台号无效、随后没有切台时，
        // 跳过的那一次必须在这里补回来——判定与关面板重播同谓词、skip=false；
        // 有效台号会切台（不走 Invalid 分支），这里只覆盖 Invalid 分支的判定。
        assertTrue(LiveViewModel.shouldReplaySignalAfterInvalidDigit(signalError = true, isPlaying = false, hasChannels = true, pagePaused = false))
        // 已在播（流已恢复）或无信号异常时不动作
        assertFalse(LiveViewModel.shouldReplaySignalAfterInvalidDigit(signalError = true, isPlaying = true, hasChannels = true, pagePaused = false))
        assertFalse(LiveViewModel.shouldReplaySignalAfterInvalidDigit(signalError = false, isPlaying = false, hasChannels = true, pagePaused = false))
        // 无频道、离页后也不重播
        assertFalse(LiveViewModel.shouldReplaySignalAfterInvalidDigit(signalError = true, isPlaying = false, hasChannels = false, pagePaused = false))
        assertFalse(LiveViewModel.shouldReplaySignalAfterInvalidDigit(signalError = true, isPlaying = false, hasChannels = true, pagePaused = true))
    }

    @Test
    fun `playback errors are ignored after leaving page`() {
        // 第 8 轮 P2-3：离页后播放器错误回调不推进失败计数、不弹 Toast——重播
        // 已被 playCurrent 的门控拦住，但计数与 Toast 在门控之前；门控与开播
        // 同谓词（前台一律处理，离页一律不处理；第 74 轮后台播放已移除）。
        assertTrue(LiveViewModel.shouldHandlePlaybackError(pageActive = true))
        assertFalse(LiveViewModel.shouldHandlePlaybackError(pageActive = false))
    }

    @Test
    fun `refresh failure is reported only on load failure, not on empty source`() {
        // A8-P1-2：拉网失败时 loadChannels 回落旧缓存、返回非空旧表，只判空
        // 会把失败当成功、静默无事发生；失败标记为真时必须提示。
        // 第 10 轮 L10-P2-2（D9-P2-8 残口）：合法空源（成功拉取、0 个频道）
        // 不是失败——旧实现 `freshIsEmpty || loadFailed` 会误报“刷新失败”。
        assertTrue(LiveViewModel.shouldReportRefreshFailed(loadFailed = true))
        assertFalse(LiveViewModel.shouldReportRefreshFailed(loadFailed = false))
    }

    @Test
    fun `epg refresh failure toasts only on load failure`() {
        // 第 7 轮跟进 D7-P2-2：EPG 后台刷新失败时轻提示（与 M3U 刷新失败提示
        // 同款接线）；成功时不提示；有旧节目单可回落时说明“仍用旧节目单”，
        // 无旧节目单时只说刷新失败；初始加载的失败不走这里。
        assertEquals(null, LiveViewModel.epgRefreshFailedMessage(loadFailed = false, hasOldEpg = true))
        assertEquals(null, LiveViewModel.epgRefreshFailedMessage(loadFailed = false, hasOldEpg = false))
        assertEquals(
            "节目单刷新失败，仍用旧节目单",
            LiveViewModel.epgRefreshFailedMessage(loadFailed = true, hasOldEpg = true),
        )
        assertEquals(
            "节目单刷新失败",
            LiveViewModel.epgRefreshFailedMessage(loadFailed = true, hasOldEpg = false),
        )
    }

    @Test
    fun `playback starts only in foreground`() {
        // A9-P1-1：前台一律开播；后台的新开播一律暂缓、回前台再补开播
        //（第 74 轮后台播放已移除，不再有“已开播过的延续”例外）。
        assertTrue(LiveViewModel.shouldStartPlayback(pageActive = true))
        assertFalse(LiveViewModel.shouldStartPlayback(pageActive = false))
    }

    @Test
    fun `buffering same channel resumes with play instead of stop and replay`() {
        // A12-P2-1：回页时同台仍在缓冲——直接 play() 续播，不 stop()+playUrl()；
        // stop() 会清掉媒体项、丢掉已缓冲的分片。只有“同 URL 且正在缓冲”才续播。
        val url = "http://a/1.m3u8"
        assertTrue(LiveViewModel.shouldResumeBuffering(url, url, url, isBuffering = true))
        // 已暂停就绪态（不在缓冲）→ 走现有重播
        assertFalse(LiveViewModel.shouldResumeBuffering(url, url, url, isBuffering = false))
        // 换台 → 重播
        assertFalse(LiveViewModel.shouldResumeBuffering("http://a/2.m3u8", url, url, isBuffering = true))
        // 播放器里挂的不是这条 URL（或未加载）→ 重播，保守、不误判
        assertFalse(LiveViewModel.shouldResumeBuffering(url, url, "http://a/2.m3u8", isBuffering = true))
        assertFalse(LiveViewModel.shouldResumeBuffering(url, url, null, isBuffering = true))
        // 上次没播过这台 → 重播
        assertFalse(LiveViewModel.shouldResumeBuffering(url, null, url, isBuffering = true))
        assertFalse(LiveViewModel.shouldResumeBuffering(url, "http://a/2.m3u8", url, isBuffering = true))
        // 目标为空 → 重播
        assertFalse(LiveViewModel.shouldResumeBuffering(null, url, url, isBuffering = true))
    }

    @Test
    fun `nextChannelUrl returns next channel first url wrapping around`() {
        // 第 76 轮 v2.0.15：切台预加载——取下一台首条线路，末尾循环回首台。
        val channels = listOf(
            M3uParser.Channel(id = "1", name = "CCTV-1", urls = listOf("http://a/1.m3u8")),
            M3uParser.Channel(id = "2", name = "CCTV-5", urls = listOf("http://a/5.m3u8")),
            M3uParser.Channel(id = "3", name = "CCTV-6", urls = listOf("http://a/6.m3u8")),
        )
        assertEquals("http://a/5.m3u8", LiveViewModel.nextChannelUrl(channels, 0, "http://a/1.m3u8"))
        assertEquals("http://a/1.m3u8", LiveViewModel.nextChannelUrl(channels, 2, "http://a/6.m3u8"))
    }

    @Test
    fun `nextChannelUrl returns null when no preload makes sense`() {
        // 第 76 轮 v2.0.15：空表/下一台无 URL/与当前台同 URL 时不预加载。
        assertEquals(null, LiveViewModel.nextChannelUrl(emptyList(), 0, "http://a/1.m3u8"))
        val sameUrl = listOf(
            M3uParser.Channel(id = "1", name = "A", urls = listOf("http://a/1.m3u8")),
            M3uParser.Channel(id = "2", name = "B", urls = listOf("http://a/1.m3u8")),
        )
        assertEquals(null, LiveViewModel.nextChannelUrl(sameUrl, 0, "http://a/1.m3u8"))
        val noUrl = listOf(
            M3uParser.Channel(id = "1", name = "A", urls = listOf("http://a/1.m3u8")),
            M3uParser.Channel(id = "2", name = "B", urls = emptyList()),
        )
        assertEquals(null, LiveViewModel.nextChannelUrl(noUrl, 0, "http://a/1.m3u8"))
    }

    @Test
    fun `stall watchdog arms only after playback confirmed and buffering`() {
        // 第 82 轮 v2.0.25：播出中卡死才武装——首播缓冲（还没播出过）不武装，
        // 就绪超时管它；已播出过但没在缓冲也不武装。
        assertTrue(LiveViewModel.shouldArmStallWatchdog(playingConfirmed = true, isBuffering = true))
        assertFalse(LiveViewModel.shouldArmStallWatchdog(playingConfirmed = false, isBuffering = true))
        assertFalse(LiveViewModel.shouldArmStallWatchdog(playingConfirmed = true, isBuffering = false))
        assertFalse(LiveViewModel.shouldArmStallWatchdog(playingConfirmed = false, isBuffering = false))
    }

    @Test
    fun `stall watchdog fires only when still buffering and owned and foreground`() {
        // 第 82 轮 v2.0.25：30 秒到点四项全满足才按断流处理——任一项不满足
        // 都是误触发（已恢复、已切台、已离页、点播抢走了播放器）。
        assertTrue(
            LiveViewModel.shouldFireStallWatchdog(
                stillBuffering = true, playingConfirmed = true, isOwner = true, pageActive = true,
            ),
        )
        assertFalse(
            LiveViewModel.shouldFireStallWatchdog(
                stillBuffering = false, playingConfirmed = true, isOwner = true, pageActive = true,
            ),
        )
        assertFalse(
            LiveViewModel.shouldFireStallWatchdog(
                stillBuffering = true, playingConfirmed = false, isOwner = true, pageActive = true,
            ),
        )
        assertFalse(
            LiveViewModel.shouldFireStallWatchdog(
                stillBuffering = true, playingConfirmed = true, isOwner = false, pageActive = true,
            ),
        )
        assertFalse(
            LiveViewModel.shouldFireStallWatchdog(
                stillBuffering = true, playingConfirmed = true, isOwner = true, pageActive = false,
            ),
        )
    }

    @Test
    fun `stall watchdog timeout is longer than ready timeout`() {
        // 第 82 轮 v2.0.25：看门狗必须比 15 秒就绪超时长，否则首播缓冲时两条
        // 超时叠加、失败计数多算一次。
        assertTrue(LiveController.STALL_WATCHDOG_MS > LiveViewModel.LIVE_READY_TIMEOUT_MS)
        assertEquals(30_000L, LiveController.STALL_WATCHDOG_MS)
    }
}
