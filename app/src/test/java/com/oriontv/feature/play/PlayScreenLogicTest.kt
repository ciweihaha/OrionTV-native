package com.oriontv.feature.play

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayScreenLogicTest {

    @Test
    fun `next episode forces controls visible even after auto-hide`() {
        // P1-3：播完时控制条已 5 秒自动隐藏，预告卡为真时仍须显示
        assertTrue(shouldShowPlayerControls(false, false, true))
        assertTrue(shouldShowPlayerControls(true, false, false))
        assertTrue(shouldShowPlayerControls(true, false, true))
    }

    @Test
    fun `error hides controls even with next episode`() {
        assertFalse(shouldShowPlayerControls(true, true, true))
        assertFalse(shouldShowPlayerControls(true, true, false))
        assertFalse(shouldShowPlayerControls(false, false, false))
    }

    @Test
    fun `seek keys intercepted only when controls shown and drawer closed`() {
        // P1-2：控制条可见时左/右恒为快进预览；抽屉打开或控制条未显示时不拦截
        assertTrue(shouldInterceptSeekKeys(controlsShown = true, drawerOpen = false))
        assertFalse(shouldInterceptSeekKeys(controlsShown = true, drawerOpen = true))
        assertFalse(shouldInterceptSeekKeys(controlsShown = false, drawerOpen = false))
    }

    @Test
    fun `seek keys not intercepted while focus is on control buttons`() {
        // P1-B：第 5 轮焦点模型——焦点已在按钮行上时左/右在按钮间横向移动，
        // 不再被预览拦截；焦点回到根部时左/右恢复快进预览。
        assertFalse(
            shouldInterceptSeekKeys(
                controlsShown = true,
                drawerOpen = false,
                focusOnControls = true,
            ),
        )
        assertTrue(
            shouldInterceptSeekKeys(
                controlsShown = true,
                drawerOpen = false,
                focusOnControls = false,
            ),
        )
        // 抽屉打开时无论焦点在哪都不拦截（抽屉内横向移动）
        assertFalse(
            shouldInterceptSeekKeys(
                controlsShown = true,
                drawerOpen = true,
                focusOnControls = true,
            ),
        )
    }

    @Test
    fun `auto-hide fires only with controls shown and nothing else holding them`() {
        // P2-2：控制条显示、抽屉关闭、无待定落点、无下一集预告、无错误页时才隐藏；
        // 错误页上计时器不武装——否则 5 秒后焦点被从“重试”拉回根部。
        assertTrue(shouldAutoHideControls(true, false, false, false, false))
        assertFalse(shouldAutoHideControls(false, false, false, false, false))
        assertFalse(shouldAutoHideControls(true, true, false, false, false))
        assertFalse(shouldAutoHideControls(true, false, true, false, false))
        assertFalse(shouldAutoHideControls(true, false, false, true, false))
        assertFalse(shouldAutoHideControls(true, false, false, false, true))
        // C11-P1-1：全局弹窗开着时也不自动隐藏——5 秒到点会把焦点从弹窗偷回根部
        assertFalse(shouldAutoHideControls(true, false, false, false, false, globalModalOpen = true))
        assertTrue(shouldAutoHideControls(true, false, false, false, false, globalModalOpen = false))
    }

    @Test
    fun `preview auto-commit pauses while drawer open`() {
        // P2-3：有待定落点且抽屉关闭时才 2 秒自动提交；抽屉打开时不提交，
        // 否则挑集时视频在抽屉后跳进度。
        assertTrue(shouldAutoCommitPreview(30_000L, drawerOpen = false))
        assertFalse(shouldAutoCommitPreview(30_000L, drawerOpen = true))
        assertFalse(shouldAutoCommitPreview(-1L, drawerOpen = false))
        assertFalse(shouldAutoCommitPreview(-1L, drawerOpen = true))
        // 第 8 轮 P2-4：错误页上不提交——否则 2 秒到点会对已出错的播放器
        // 发 seekTo；错误出现时待定落点由 effect 直接取消。
        assertFalse(shouldAutoCommitPreview(30_000L, drawerOpen = false, hasError = true))
        assertFalse(shouldAutoCommitPreview(30_000L, drawerOpen = true, hasError = true))
        assertFalse(shouldAutoCommitPreview(-1L, drawerOpen = false, hasError = true))
    }

    @Test
    fun `auto-hide timer resets on control-row navigation but not on error page`() {
        // P2-1：按钮行内按键（横向移动/进出）重置 5 秒计时器；
        // P2-2：错误页上不重置——错误页没有按钮行，按键只移动“重试/返回”焦点。
        assertTrue(shouldResetAutoHideTimer(true, false, false))
        assertFalse(shouldResetAutoHideTimer(false, false, false))
        assertFalse(shouldResetAutoHideTimer(true, true, false))
        assertFalse(shouldResetAutoHideTimer(true, false, true))
    }

    @Test
    fun `drawer focus containment traps only edge escapes`() {
        // C8-P1-2：抽屉打开时左/下键在边缘才消费（焦点会逃到遮罩下的控制条按钮）；
        // 抽屉内移动放行——blanket 消费左键会断掉选集/倍速网格内的横向移动。
        // 抽屉关闭时不拦；右/上键从不拦（抽屉在右缘，上方无可聚焦项）。
        assertTrue(shouldContainPlayDrawerFocusKey(true, Key.DirectionLeft, leftEdge = true, bottomEdge = false))
        assertTrue(shouldContainPlayDrawerFocusKey(true, Key.DirectionDown, leftEdge = false, bottomEdge = true))
        assertFalse(shouldContainPlayDrawerFocusKey(true, Key.DirectionLeft, leftEdge = false, bottomEdge = false))
        assertFalse(shouldContainPlayDrawerFocusKey(true, Key.DirectionDown, leftEdge = true, bottomEdge = false))
        assertFalse(shouldContainPlayDrawerFocusKey(true, Key.DirectionRight, leftEdge = true, bottomEdge = true))
        assertFalse(shouldContainPlayDrawerFocusKey(true, Key.DirectionUp, leftEdge = true, bottomEdge = true))
        assertFalse(shouldContainPlayDrawerFocusKey(false, Key.DirectionLeft, leftEdge = true, bottomEdge = true))
        // 第 8 轮 P2-6：初始落焦重试耗尽回退到根时，边缘态置为“全缘”
        // （左缘 + 底缘均真），左/下键在焦点回根期间被消费、不逃到遮罩后的
        // 控制条按钮上；右/上键仍放行，右键可经焦点搜索进抽屉。
        assertTrue(shouldContainPlayDrawerFocusKey(true, Key.DirectionLeft, leftEdge = true, bottomEdge = true))
        assertTrue(shouldContainPlayDrawerFocusKey(true, Key.DirectionDown, leftEdge = true, bottomEdge = true))
    }

    @Test
    fun `drawer grid edges are first column and last row`() {
        // C8-P1-2：选集 4 列、20 集一页——首列是左缘，末行（16..19）是底缘；
        // 倍速 3 列、6 项——末行是 3..5。
        assertEquals(true to false, drawerGridFocusEdges(0, 4, 20))
        assertEquals(false to false, drawerGridFocusEdges(1, 4, 20))
        assertEquals(false to false, drawerGridFocusEdges(15, 4, 20))
        assertEquals(true to true, drawerGridFocusEdges(16, 4, 20))
        assertEquals(false to true, drawerGridFocusEdges(19, 4, 20))
        assertEquals(true to true, drawerGridFocusEdges(3, 3, 6))
        assertEquals(false to false, drawerGridFocusEdges(1, 3, 6))
        // 不满一页时末行按实际项数算（7 集：末行是 4..6）
        assertEquals(false to true, drawerGridFocusEdges(6, 4, 7))
        assertEquals(true to false, drawerGridFocusEdges(0, 4, 0))
    }

    @Test
    fun `drawer list edges are always left and only last is bottom`() {
        // C8-P1-2：换源是纵列表，左键永远无列表内移动（恒为左缘），
        // 下键只在末项是底缘。
        assertEquals(true to false, drawerListFocusEdges(0, 3))
        assertEquals(true to false, drawerListFocusEdges(1, 3))
        assertEquals(true to true, drawerListFocusEdges(2, 3))
        assertEquals(true to false, drawerListFocusEdges(0, 0))
    }

    @Test
    fun `drawer group row left edge is only the first chip`() {
        // C10-P1-1：选集抽屉分组行是横向单行——只有首个分组 chip 是左缘，
        // 其余 chip 按左键应切到上一个分组；之前复用纵列表函数（左缘恒真），
        // 非首分组 chip 上左键被根部预览拦截消费、变死键
        assertTrue(drawerGroupRowLeftEdge(0))
        assertFalse(drawerGroupRowLeftEdge(1))
        assertFalse(drawerGroupRowLeftEdge(2))
        assertFalse(drawerGroupRowLeftEdge(5))
    }

    @Test
    fun `up returns to root only when focus is still on the button row`() {
        // A9-P1-4：上键回根只在焦点仍在按钮行时执行；焦点已落在跳过片头胶囊 /
        // 下一集预告卡上时不抢——之前 KeyDown 的焦点搜索刚把它送上去，
        // KeyUp 又拉回根部，“取消”按钮永远落不到焦点。
        val base = shouldRestoreRootFocusOnUp(
            drawerOpen = false, rootFocused = false, controlsVisible = true,
            hasError = false, showNextEpisode = false,
            skipIntroFocused = false, nextEpisodeFocused = false,
        )
        assertTrue(base)
        // 焦点在胶囊上：不回根
        assertFalse(
            shouldRestoreRootFocusOnUp(
                drawerOpen = false, rootFocused = false, controlsVisible = true,
                hasError = false, showNextEpisode = false,
                skipIntroFocused = true, nextEpisodeFocused = false,
            ),
        )
        // 焦点在预告卡“取消”上：不回根
        assertFalse(
            shouldRestoreRootFocusOnUp(
                drawerOpen = false, rootFocused = false, controlsVisible = true,
                hasError = false, showNextEpisode = true,
                skipIntroFocused = false, nextEpisodeFocused = true,
            ),
        )
        // 焦点已在根部 / 抽屉打开 / 控制条未显示：都不回根
        assertFalse(
            shouldRestoreRootFocusOnUp(
                drawerOpen = false, rootFocused = true, controlsVisible = true,
                hasError = false, showNextEpisode = false,
                skipIntroFocused = false, nextEpisodeFocused = false,
            ),
        )
        assertFalse(
            shouldRestoreRootFocusOnUp(
                drawerOpen = true, rootFocused = false, controlsVisible = true,
                hasError = false, showNextEpisode = false,
                skipIntroFocused = false, nextEpisodeFocused = false,
            ),
        )
        assertFalse(
            shouldRestoreRootFocusOnUp(
                drawerOpen = false, rootFocused = false, controlsVisible = false,
                hasError = false, showNextEpisode = false,
                skipIntroFocused = false, nextEpisodeFocused = false,
            ),
        )
    }

    @Test
    fun `ok toggles play pause except on error page`() {
        // 第 7 轮 P2-6：错误页上 OK 不触发播放/暂停——焦点因“重试”落焦失败
        // 回退到根部时，旧分支会在错误页后 pokeControls + togglePlayPause；
        // 抽屉打开、控制条隐藏、有待定落点时 OK 也另有去处，不走到播放/暂停。
        assertTrue(shouldTogglePlayPauseOnOk(false, false, true, false))
        assertFalse(shouldTogglePlayPauseOnOk(false, false, true, true))
        assertFalse(shouldTogglePlayPauseOnOk(true, false, true, false))
        assertFalse(shouldTogglePlayPauseOnOk(false, true, true, false))
        assertFalse(shouldTogglePlayPauseOnOk(false, false, false, false))
        assertFalse(shouldTogglePlayPauseOnOk(false, false, false, true))
    }

    @Test
    fun `stale ended preview is discarded only in settled ended state`() {
        // A68-P2-1：settled 播完态（已播完、卡已关、不在加载、无错误）才丢弃——
        // reclaim 跳过播完态、永不再挂片，保留即 OK 永久失灵；其余四种情形
        // 各缺一项，均保留（A61-P2-2 的 reclaim/加载窗口、倒计时仍在走的卡、
        // 错误页、未播完态）。
        assertTrue(shouldDiscardStaleEndedPreview(true, false, false, false))
        assertFalse(shouldDiscardStaleEndedPreview(false, false, false, false))
        assertFalse(shouldDiscardStaleEndedPreview(true, true, false, false))
        assertFalse(shouldDiscardStaleEndedPreview(true, false, true, false))
        assertFalse(shouldDiscardStaleEndedPreview(true, false, false, true))
        assertFalse(shouldDiscardStaleEndedPreview(true, true, true, true))
    }

    @Test
    fun `group scroll target is selected group only with multiple groups`() {
        // 第 7 轮 P2-7：只有多分组时才滚，目标是选中分组；单分组/空表不滚（-1）；
        // 选中分组越界时钳制到末组。
        assertEquals(5, resolveGroupScrollIndex(5, 8))
        assertEquals(0, resolveGroupScrollIndex(0, 8))
        assertEquals(7, resolveGroupScrollIndex(9, 8))
        assertEquals(-1, resolveGroupScrollIndex(0, 1))
        assertEquals(-1, resolveGroupScrollIndex(0, 0))
    }

    @Test
    fun `tick restore skips root focus while drawer is open`() {
        // C12-P1-4：抽屉打开时全局弹窗关闭把焦点抢回根部，抽屉遥控不可达；
        // tick effect 增加抽屉门控，抽屉开着时跳过。
        assertTrue(shouldRestoreRootFocusOnTick(focusRestoreTick = 1, drawerOpen = false))
        assertFalse(shouldRestoreRootFocusOnTick(focusRestoreTick = 1, drawerOpen = true))
        assertFalse(shouldRestoreRootFocusOnTick(focusRestoreTick = 0, drawerOpen = false))
    }

    @Test
    fun `drawer content arrival does not steal focus while global modal open`() {
        // C14-P1-2/C14-P1-3：选集总集数 0→N、换源源列表 0→N 时 effect 重跑落焦，
        // 全局弹窗开着时不落焦（含回退到根——那同样会偷焦点），关闭后由 tick 重跑补落。
        assertTrue(shouldFocusDrawerOnContentArrival(globalModalOpen = false))
        assertFalse(shouldFocusDrawerOnContentArrival(globalModalOpen = true))
    }

    @Test
    fun `back decision follows preview drawer error next episode controls exit order`() {
        // B32-P2-1：硬件返回与手势返回共用同一份决策——预览取消 → 关抽屉 →
        // 错误页退出 → 取消下一集预告 → 隐藏控制条 → 退出。
        // A33-P2-1：错误页优先于下一集预告（播错时 showNextEpisode 残留、
        // 预告卡在错误页下不组合，预告分支在前会让第一次按返回无可见变化）。
        assertEquals(
            PlayBackAction.CancelPreview,
            resolvePlayBackAction(
                previewActive = true,
                drawerOpen = true,
                showNextEpisode = true,
                hasError = true,
                controlsVisible = true,
            ),
        )
        assertEquals(
            PlayBackAction.CloseDrawer,
            resolvePlayBackAction(
                previewActive = false,
                drawerOpen = true,
                showNextEpisode = true,
                hasError = true,
                controlsVisible = true,
            ),
        )
        // A33-P2-1：预告卡残留 + 错误页共存时直接退出（预告卡在错误页下
        // 不组合，取消它无可见变化）；无错误时仍先取消预告。
        assertEquals(
            PlayBackAction.Exit,
            resolvePlayBackAction(
                previewActive = false,
                drawerOpen = false,
                showNextEpisode = true,
                hasError = true,
                controlsVisible = true,
            ),
        )
        assertEquals(
            PlayBackAction.DismissNextEpisode,
            resolvePlayBackAction(
                previewActive = false,
                drawerOpen = false,
                showNextEpisode = true,
                hasError = false,
                controlsVisible = true,
            ),
        )
        // A6-P2-1：错误页优先于隐藏控制条（控制条在错误页下本来就不显示，
        // 落到隐藏分支会第一次按返回无可见变化）。
        assertEquals(
            PlayBackAction.Exit,
            resolvePlayBackAction(
                previewActive = false,
                drawerOpen = false,
                showNextEpisode = false,
                hasError = true,
                controlsVisible = true,
            ),
        )
        assertEquals(
            PlayBackAction.HideControls,
            resolvePlayBackAction(
                previewActive = false,
                drawerOpen = false,
                showNextEpisode = false,
                hasError = false,
                controlsVisible = true,
            ),
        )
        assertEquals(
            PlayBackAction.Exit,
            resolvePlayBackAction(
                previewActive = false,
                drawerOpen = false,
                showNextEpisode = false,
                hasError = false,
                controlsVisible = false,
            ),
        )
    }
}
