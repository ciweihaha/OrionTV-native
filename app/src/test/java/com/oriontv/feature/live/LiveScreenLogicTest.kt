package com.oriontv.feature.live

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveScreenLogicTest {

    private fun channel(name: String, url: String) =
        M3uParser.Channel(id = url, name = name, urls = listOf(url))

    @Test
    fun `panel focus lands on current channel when present`() {
        val first = channel("CCTV-1", "http://a/1.m3u8")
        val second = channel("CCTV-5", "http://a/5.m3u8")
        val filtered = listOf(first, second)
        assertEquals(1, resolvePanelFocusIndex(filtered, second))
    }

    @Test
    fun `panel focus falls to first row when group has no current channel`() {
        // P1-7：切到不含当前台的分组，焦点落该分组第 0 行，不丢焦点
        val filtered = listOf(
            channel("广东卫视", "http://a/gd.m3u8"),
            channel("深圳卫视", "http://a/sz.m3u8"),
        )
        assertEquals(0, resolvePanelFocusIndex(filtered, channel("CCTV-1", "http://a/1.m3u8")))
        assertEquals(0, resolvePanelFocusIndex(filtered, null))
    }

    @Test
    fun `panel focus distinguishes duplicate urls by identity`() {
        // P2-4：同 URL 两条台，焦点落到对的那一行
        val first = channel("卫视", "http://a/shared.m3u8")
        val second = channel("卫视", "http://a/shared.m3u8")
        val filtered = listOf(first, second)
        assertEquals(0, resolvePanelFocusIndex(filtered, first))
        assertEquals(1, resolvePanelFocusIndex(filtered, second))
    }

    @Test
    fun `root ok retries only when playback failed`() {
        // A5-P2-4：只在 playbackFailed（失败页已出）时重试；failCount 1..5
        // 时 OK 开面板（与设计 §5.4 一致），旧“失败过就重试”已废弃
        assertTrue(shouldRetryOnOk(playbackFailed = true, failCount = 6))
        assertTrue(shouldRetryOnOk(playbackFailed = true, failCount = 0))
        // 未耗尽但失败过：开面板，不重试
        assertFalse(shouldRetryOnOk(playbackFailed = false, failCount = 3))
        assertFalse(shouldRetryOnOk(playbackFailed = false, failCount = 1))
        // 没失败过：开面板
        assertFalse(shouldRetryOnOk(playbackFailed = false, failCount = 0))
    }

    @Test
    fun `channel row ignores keys while epg dialog open`() {
        // P1-8：弹窗打开时行不消费 Center 键，不会误切台
        assertFalse(shouldConsumeChannelRowKey(epgDialogOpen = true))
        assertTrue(shouldConsumeChannelRowKey(epgDialogOpen = false))
    }

    @Test
    fun `epg dialog traps focus at first row and close button`() {
        // P2-6：首行按上、关闭按钮（下标 n）按下时拦截，不让焦点移出弹窗落到背后频道行；
        // 行间移动与末行下移到“关闭”按钮放行；未知焦点（-1）不拦截
        assertTrue(shouldTrapEpgFocusKey(Key.DirectionUp, focusedIndex = 0, programmeCount = 5))
        assertFalse(shouldTrapEpgFocusKey(Key.DirectionUp, focusedIndex = 2, programmeCount = 5))
        assertFalse(shouldTrapEpgFocusKey(Key.DirectionUp, focusedIndex = 5, programmeCount = 5))
        assertTrue(shouldTrapEpgFocusKey(Key.DirectionDown, focusedIndex = 5, programmeCount = 5))
        assertFalse(shouldTrapEpgFocusKey(Key.DirectionDown, focusedIndex = 4, programmeCount = 5))
        assertFalse(shouldTrapEpgFocusKey(Key.DirectionDown, focusedIndex = 0, programmeCount = 5))
        assertFalse(shouldTrapEpgFocusKey(Key.DirectionUp, focusedIndex = -1, programmeCount = 5))
        assertFalse(shouldTrapEpgFocusKey(Key.DirectionDown, focusedIndex = -1, programmeCount = 5))
        assertFalse(shouldTrapEpgFocusKey(Key.DirectionLeft, focusedIndex = 0, programmeCount = 5))
        // 无节目单时“关闭”按钮下标就是 0：按下拦截、上移放行（无行可去，由根部处理）
        assertTrue(shouldTrapEpgFocusKey(Key.DirectionDown, focusedIndex = 0, programmeCount = 0))
        assertTrue(shouldTrapEpgFocusKey(Key.DirectionUp, focusedIndex = 0, programmeCount = 0))
    }

    @Test
    fun `live focus containment traps panel and epg keys`() {
        // C7-P1-1/A7-P2-5：面板开着时左键消费、右键放行；EPG 开着时左右都消费；
        // 都关着时不拦；上下键从不拦（面板内上下是焦点移动/换台）。
        assertTrue(shouldContainLiveFocusKey(panelOpen = true, epgDialogOpen = false, Key.DirectionLeft))
        assertFalse(shouldContainLiveFocusKey(panelOpen = true, epgDialogOpen = false, Key.DirectionRight))
        assertTrue(shouldContainLiveFocusKey(panelOpen = true, epgDialogOpen = true, Key.DirectionLeft))
        assertTrue(shouldContainLiveFocusKey(panelOpen = true, epgDialogOpen = true, Key.DirectionRight))
        assertFalse(shouldContainLiveFocusKey(panelOpen = false, epgDialogOpen = false, Key.DirectionLeft))
        assertFalse(shouldContainLiveFocusKey(panelOpen = true, epgDialogOpen = false, Key.DirectionUp))
        assertFalse(shouldContainLiveFocusKey(panelOpen = true, epgDialogOpen = true, Key.DirectionDown))
    }

    @Test
    fun `live focus containment lets group row move left inside panel`() {
        // 第 7 轮 P1-2：面板分组行是横向 LazyRow，左键是行内移动——焦点在分组行
        // 非首 chip 上时左键放行（向右越过目标分组后能回退），只有焦点不在分组行
        // 上（频道列表/底部，左键无行内移动），或在首个 chip 上（再左移会出面板）
        // 时才消费；EPG 弹窗打开时左右仍全拦（弹窗左/右是切台）。
        assertFalse(
            shouldContainLiveFocusKey(
                panelOpen = true, epgDialogOpen = false, key = Key.DirectionLeft,
                groupRowFocused = true, groupFirstChipFocused = false,
            ),
        )
        assertTrue(
            shouldContainLiveFocusKey(
                panelOpen = true, epgDialogOpen = false, key = Key.DirectionLeft,
                groupRowFocused = true, groupFirstChipFocused = true,
            ),
        )
        assertTrue(
            shouldContainLiveFocusKey(
                panelOpen = true, epgDialogOpen = false, key = Key.DirectionLeft,
                groupRowFocused = false, groupFirstChipFocused = false,
            ),
        )
        // 分组行内右键从不拦（横向移动）；EPG 打开时分组行状态不影响 containment
        assertFalse(
            shouldContainLiveFocusKey(
                panelOpen = true, epgDialogOpen = false, key = Key.DirectionRight,
                groupRowFocused = true, groupFirstChipFocused = false,
            ),
        )
        assertTrue(
            shouldContainLiveFocusKey(
                panelOpen = true, epgDialogOpen = true, key = Key.DirectionLeft,
                groupRowFocused = true, groupFirstChipFocused = false,
            ),
        )
        assertTrue(
            shouldContainLiveFocusKey(
                panelOpen = true, epgDialogOpen = true, key = Key.DirectionRight,
                groupRowFocused = true, groupFirstChipFocused = false,
            ),
        )
    }

    @Test
    fun `epg trap initial index is unknown while programmes are late`() {
        // 第 7 轮 P2-10：节目单未到时初始值 -1（未知，陷阱不拦截）——旧实现按
        // 当时的空节目单取 0，焦点还没入弹窗时按上会被误吞；节目单已到时取
        // 将要获焦的行（正在播的节目，无则首行）。
        assertEquals(-1, resolveEpgInitialFocusedIndex(programmesEmpty = true, currentIndex = 0))
        assertEquals(-1, resolveEpgInitialFocusedIndex(programmesEmpty = true, currentIndex = 5))
        assertEquals(5, resolveEpgInitialFocusedIndex(programmesEmpty = false, currentIndex = 5))
        assertEquals(0, resolveEpgInitialFocusedIndex(programmesEmpty = false, currentIndex = -1))
        assertEquals(0, resolveEpgInitialFocusedIndex(programmesEmpty = false, currentIndex = 0))
    }

    @Test
    fun `panel favorite star matches any line not just first`() {
        // A8-P2-1：M3U 轮换首线路后收藏的台按 urls[0] 匹配不到、星标消失；
        // 面板行 ★ 应按全部线路匹配（控制器侧第 6 轮已修，面板行漏了）
        val rotated = M3uParser.Channel(
            id = "http://a/new.m3u8",
            name = "CCTV-1",
            urls = listOf("http://a/new.m3u8", "http://a/old.m3u8"),
        )
        val favs = listOf(LiveFavChannel(url = "http://a/old.m3u8", name = "CCTV-1"))
        assertTrue(isPanelChannelFavorite(rotated, favs))
        assertFalse(isPanelChannelFavorite(rotated, listOf(LiveFavChannel("http://a/other.m3u8", "CCTV-1"))))
        assertFalse(isPanelChannelFavorite(rotated, emptyList()))
        // 首线路命中仍亮
        assertTrue(isPanelChannelFavorite(rotated, listOf(LiveFavChannel("http://a/new.m3u8", "CCTV-1"))))
    }

    @Test
    fun `panel refocuses only when epg dialog is closed`() {
        // C12-P1-3：面板打开时全局弹窗关闭的 tick 重落当前台行；EPG 弹窗开着时
        // 焦点归弹窗，面板 effect 不能把焦点抢回来。
        assertTrue(shouldFocusPanelRow(panelOpen = true, epgDialogOpen = false))
        assertFalse(shouldFocusPanelRow(panelOpen = true, epgDialogOpen = true))
        assertFalse(shouldFocusPanelRow(panelOpen = false, epgDialogOpen = false))
    }

    @Test
    fun `live root left right keys intercept only when all closed and root focused`() {
        // C13-P1-1：面板/EPG/确认框全关且焦点在根部时左/右恒为切线路——预览拦截，
        // 未被消费的 KeyDown 会先走空间导航移走焦点，再也到不了 KeyUp 的 changeLine。
        assertTrue(
            shouldInterceptLiveLineKeys(
                panelOpen = false, epgDialogOpen = false, exitConfirmOpen = false,
                key = Key.DirectionLeft, rootFocused = true,
            ),
        )
        assertTrue(
            shouldInterceptLiveLineKeys(
                panelOpen = false, epgDialogOpen = false, exitConfirmOpen = false,
                key = Key.DirectionRight, rootFocused = true,
            ),
        )
        // 面板/EPG/确认框任一打开时不拦（containment 那条管）
        assertFalse(
            shouldInterceptLiveLineKeys(
                panelOpen = true, epgDialogOpen = false, exitConfirmOpen = false,
                key = Key.DirectionLeft, rootFocused = true,
            ),
        )
        assertFalse(
            shouldInterceptLiveLineKeys(
                panelOpen = false, epgDialogOpen = true, exitConfirmOpen = false,
                key = Key.DirectionRight, rootFocused = true,
            ),
        )
        assertFalse(
            shouldInterceptLiveLineKeys(
                panelOpen = false, epgDialogOpen = false, exitConfirmOpen = true,
                key = Key.DirectionLeft, rootFocused = true,
            ),
        )
        // 焦点在失败页/空态按钮上时不拦：左/右是按钮行内移动
        assertFalse(
            shouldInterceptLiveLineKeys(
                panelOpen = false, epgDialogOpen = false, exitConfirmOpen = false,
                key = Key.DirectionLeft, rootFocused = false,
            ),
        )
        // 上/下不是切线路，不拦
        assertFalse(
            shouldInterceptLiveLineKeys(
                panelOpen = false, epgDialogOpen = false, exitConfirmOpen = false,
                key = Key.DirectionUp, rootFocused = true,
            ),
        )
        assertFalse(
            shouldInterceptLiveLineKeys(
                panelOpen = false, epgDialogOpen = false, exitConfirmOpen = false,
                key = Key.DirectionDown, rootFocused = true,
            ),
        )
    }
}
