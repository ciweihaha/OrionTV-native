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

    @Test
    fun `live ticker runs only in foreground`() {
        // P2-9：切后台时时钟/EPG 刷新都停，恢复前台后下一跳继续
        assertTrue(LiveViewModel.shouldRunTicker(isForeground = true))
        assertFalse(LiveViewModel.shouldRunTicker(isForeground = false))
    }

    @Test
    fun `page resume replays only when not already owner and playing`() {
        // 第 6 轮 P1-4：应用内导航返回（导航轨切走再回来）也要恢复——
        // 已是本页 token 所有者且在播时不动；被抢走/停掉时重开播当前台。
        assertFalse(LiveViewModel.shouldResumePlayback(isCurrentOwner = true, isPlaying = true))
        assertTrue(LiveViewModel.shouldResumePlayback(isCurrentOwner = false, isPlaying = true))
        assertTrue(LiveViewModel.shouldResumePlayback(isCurrentOwner = true, isPlaying = false))
        assertTrue(LiveViewModel.shouldResumePlayback(isCurrentOwner = false, isPlaying = false))
    }

    @Test
    fun `refresh failure is reported when network fails even with cached channels`() {
        // A8-P1-2：拉网失败时 loadChannels 回落旧缓存、返回非空旧表，只判空
        // 会把失败当成功、静默无事发生；失败标记为真时必须提示
        assertTrue(LiveViewModel.shouldReportRefreshFailed(freshIsEmpty = false, loadFailed = true))
        assertTrue(LiveViewModel.shouldReportRefreshFailed(freshIsEmpty = true, loadFailed = false))
        assertTrue(LiveViewModel.shouldReportRefreshFailed(freshIsEmpty = true, loadFailed = true))
        assertFalse(LiveViewModel.shouldReportRefreshFailed(freshIsEmpty = false, loadFailed = false))
    }

    @Test
    fun `background playback starts only for a continuation`() {
        // A9-P1-1：前台一律开播；后台只允许已开播过的延续（PiP 内断流恢复、
        // 开关开着的继续播）；从未开播过时后台开播即无窗口后台音频（开关开）
        // 或违背开关语义（开关关），一律暂缓、回前台再补开播。
        assertTrue(LiveViewModel.shouldStartPlayback(pageActive = true, backgroundPlaybackEnabled = false, playbackStarted = false))
        assertTrue(LiveViewModel.shouldStartPlayback(pageActive = true, backgroundPlaybackEnabled = true, playbackStarted = true))
        assertTrue(LiveViewModel.shouldStartPlayback(pageActive = false, backgroundPlaybackEnabled = true, playbackStarted = true))
        assertFalse(LiveViewModel.shouldStartPlayback(pageActive = false, backgroundPlaybackEnabled = true, playbackStarted = false))
        assertFalse(LiveViewModel.shouldStartPlayback(pageActive = false, backgroundPlaybackEnabled = false, playbackStarted = true))
        assertFalse(LiveViewModel.shouldStartPlayback(pageActive = false, backgroundPlaybackEnabled = false, playbackStarted = false))
    }
}
