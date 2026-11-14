package com.oriontv.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveControllerTest {

    private fun channel(name: String, urls: List<String>, group: String? = null) =
        M3uParser.Channel(
            id = urls.first(),
            name = name,
            group = group,
            urls = urls,
        )

    private val threeChannels = listOf(
        channel("CCTV-1", listOf("http://a/1.m3u8", "http://a/1b.m3u8"), "央视"),
        channel("CCTV-5", listOf("http://a/5.m3u8"), "央视"),
        channel("广东卫视", listOf("http://a/gd.m3u8"), "卫视"),
    )

    // ---- 换台 / 切线路 ----

    @Test
    fun `next and prev channel wrap around`() {
        val c = LiveController(threeChannels)
        assertEquals(0, c.currentIndex)
        c.prevChannel()
        assertEquals(2, c.currentIndex)
        c.nextChannel()
        assertEquals(0, c.currentIndex)
        c.nextChannel()
        assertEquals(1, c.currentIndex)
    }

    @Test
    fun `changing channel resets to first line`() {
        val c = LiveController(threeChannels, currentIndex = 0, lineIndex = 1)
        c.nextChannel()
        assertEquals(0, c.lineIndex)
        assertEquals("http://a/5.m3u8", c.currentUrl)
    }

    @Test
    fun `change line cycles only when multi-line`() {
        val c = LiveController(threeChannels)
        assertTrue(c.changeLine(1))
        assertEquals(1, c.lineIndex)
        assertEquals("http://a/1b.m3u8", c.currentUrl)
        assertTrue(c.changeLine(1))
        assertEquals(0, c.lineIndex)
        // 单线路频道切线路无动作
        c.nextChannel()
        assertFalse(c.changeLine(1))
        assertEquals(0, c.lineIndex)
    }

    @Test
    fun `manual channel change resets fail count`() {
        val c = LiveController(threeChannels)
        c.onPlaybackError(panelOpen = false)
        assertEquals(1, c.failCount)
        c.nextChannel()
        assertEquals(0, c.failCount)
    }

    // ---- 断流自动处理 ----

    @Test
    fun `playback error tries backup line then next channel`() {
        val c = LiveController(threeChannels)
        // 第 1 次失败：CCTV-1 有备用线路 → 试线路 2
        val first = c.onPlaybackError(panelOpen = false)
        assertTrue(first is LiveErrorAction.TryLine)
        assertEquals(1, (first as LiveErrorAction.TryLine).lineIndex)
        assertEquals(1, c.lineIndex)
        // 第 2 次失败：两条线路都试过 → 跳下一台
        val second = c.onPlaybackError(panelOpen = false)
        assertTrue(second is LiveErrorAction.NextChannel)
        assertEquals(1, c.currentIndex)
        // 第 3 次失败：CCTV-5 单线路 → 跳下一台
        val third = c.onPlaybackError(panelOpen = false)
        assertTrue(third is LiveErrorAction.NextChannel)
        assertEquals(2, c.currentIndex)
    }

    @Test
    fun `playback error gives up after more than 5 failures`() {
        val c = LiveController(threeChannels)
        var last: LiveErrorAction = c.onPlaybackError(panelOpen = false)
        repeat(5) { last = c.onPlaybackError(panelOpen = false) }
        assertTrue(last is LiveErrorAction.GiveUp)
        assertEquals(6, c.failCount)
    }

    @Test
    fun `playback error while panel open only notifies`() {
        val c = LiveController(threeChannels)
        val action = c.onPlaybackError(panelOpen = true)
        assertTrue(action is LiveErrorAction.NotifyOnly)
        assertEquals(0, c.currentIndex)
        assertEquals(0, c.lineIndex)
        assertEquals(1, c.failCount)
    }

    @Test
    fun `playback success resets fail count and tried lines`() {
        val c = LiveController(threeChannels)
        c.onPlaybackError(panelOpen = false) // 试了备用线路
        assertEquals(1, c.lineIndex)
        c.onPlaybackSuccess()
        assertEquals(0, c.failCount)
        // 已试集合清零：下次失败应重新从备用线路试起
        val action = c.onPlaybackError(panelOpen = false)
        assertTrue(action is LiveErrorAction.TryLine)
    }

    // ---- 数字键换台 ----

    @Test
    fun `digit switches immediately when no longer number is possible`() {
        val c = LiveController(threeChannels)
        // 只有 3 个台，输 2 不可能有 20+ 号 → 立即切 2 台
        val action = c.appendDigit('2')
        assertTrue(action is DigitAction.SwitchTo)
        assertEquals(1, (action as DigitAction.SwitchTo).index)
    }

    @Test
    fun `digit buffers when a longer number may exist`() {
        val many = (1..55).map { channel("台$it", listOf("http://a/$it.m3u8")) }
        val c = LiveController(many)
        // 55 个台：输 5 可能还有 50–55 号 → 只缓冲
        val buffered = c.appendDigit('5')
        assertTrue(buffered is DigitAction.Buffer)
        assertEquals("5", (buffered as DigitAction.Buffer).text)
        // 再输 0 → 50 号唯一匹配 → 立即切
        val switched = c.appendDigit('0')
        assertTrue(switched is DigitAction.SwitchTo)
        assertEquals(49, (switched as DigitAction.SwitchTo).index)
    }

    @Test
    fun `digit commit reports invalid number`() {
        val c = LiveController(threeChannels)
        c.appendDigit('9')
        val action = c.commitDigits()
        assertTrue(action is DigitAction.Invalid)
        assertEquals(9, (action as DigitAction.Invalid).number)
    }

    @Test
    fun `digit buffer caps at 4 chars and empty commit is none`() {
        val c = LiveController(threeChannels)
        assertTrue(c.commitDigits() is DigitAction.None)
        // 20000 个台：按 5 次 1，前 4 位都可能有更长号故一直缓冲，第 5 位丢最早一位
        val many = (1..20000).map { channel("台$it", listOf("http://a/$it.m3u8")) }
        val big = LiveController(many)
        repeat(5) { big.appendDigit('1') }
        assertEquals("1111", big.digitBuffer)
    }

    // ---- 分组与收藏 ----

    @Test
    fun `groups put favorites first`() {
        val c = LiveController(threeChannels)
        assertEquals(
            listOf("★ 收藏", "全部", "央视", "卫视"),
            c.groups(),
        )
    }

    @Test
    fun `favorites group filters by fav urls`() {
        val c = LiveController(threeChannels)
        c.toggleFavorite(threeChannels[2])
        val favs = c.channelsForGroup(LiveController.FAV_GROUP)
        assertEquals(listOf("广东卫视"), favs.map { it.name })
        assertEquals(threeChannels, c.channelsForGroup(LiveController.ALL_GROUP))
        assertEquals(
            listOf("CCTV-1", "CCTV-5"),
            c.channelsForGroup("央视").map { it.name },
        )
    }

    @Test
    fun `toggle favorite adds to head and removes on second toggle`() {
        val c = LiveController(threeChannels)
        assertTrue(c.toggleFavorite(threeChannels[0]))
        assertTrue(c.toggleFavorite(threeChannels[1]))
        assertEquals(
            listOf(threeChannels[1].url, threeChannels[0].url),
            c.favorites.map { it.url },
        )
        assertFalse(c.toggleFavorite(threeChannels[0]))
        assertEquals(listOf(threeChannels[1].url), c.favorites.map { it.url })
    }

    @Test
    fun `favorites cap at 100`() {
        val many = (1..101).map { channel("台$it", listOf("http://a/$it.m3u8")) }
        val c = LiveController(many)
        many.forEach { c.toggleFavorite(it) }
        assertEquals(100, c.favorites.size)
        assertEquals("http://a/101.m3u8", c.favorites.first().url)
    }

    @Test
    fun `restore last channel by url only once via setChannels`() {
        val c = LiveController()
        c.setChannels(threeChannels, restoreUrl = "http://a/gd.m3u8")
        assertEquals(2, c.currentIndex)
        assertEquals("广东卫视", c.currentChannel?.name)
        // 恢复不到的 URL 落回第 0 台
        c.setChannels(threeChannels, restoreUrl = "http://a/none.m3u8")
        assertEquals(0, c.currentIndex)
    }

    // ---- 节目进度 ----

    @Test
    fun `programme progress is fraction of elapsed time`() {
        val programme = EpgParser.Programme(
            channelId = "cctv1",
            title = "新闻联播",
            startAt = 1000L,
            endAt = 2000L,
        )
        assertEquals(0.5f, LiveController.programmeProgress(programme, 1500L))
        assertEquals(0f, LiveController.programmeProgress(programme, 500L))
        assertEquals(1f, LiveController.programmeProgress(programme, 3000L))
    }
}
