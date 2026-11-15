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
    fun `manual reselect of same channel clears tried lines`() {
        // A9-P2-2：手动重选同台时 failCount 已清零、triedLines 仍是旧的，
        // 断流后备用线路直接被跳过；重选后再次失败应能再试另一条线路。
        val c = LiveController(threeChannels)
        // 第 1 次失败：CCTV-1 有备用线路 → 试线路 2
        val first = c.onPlaybackError(panelOpen = false)
        assertTrue(first is LiveErrorAction.TryLine)
        assertEquals(1, (first as LiveErrorAction.TryLine).lineIndex)
        // 手动重选同台（不断流、不换台，只清失败态与已试线路）
        c.selectChannel(0)
        assertEquals(0, c.failCount)
        // 再次失败：已试集合已清，应回头试线路 1，而不是直接跳下一台
        val second = c.onPlaybackError(panelOpen = false)
        assertTrue(second is LiveErrorAction.TryLine)
        assertEquals(0, (second as LiveErrorAction.TryLine).lineIndex)
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
    fun `single channel auto hop resets tried lines and returns to main line`() {
        // P2-2：单频道列表自动跳台回到同一台时，也要清空已试线路并回主线路；
        // 否则之后每次失败直接跳台，不再重试主线路。
        val single = listOf(channel("CCTV-1", listOf("http://a/1.m3u8", "http://a/1b.m3u8")))
        val c = LiveController(single)
        val first = c.onPlaybackError(panelOpen = false)
        assertTrue(first is LiveErrorAction.TryLine)
        assertEquals(1, c.lineIndex)
        val second = c.onPlaybackError(panelOpen = false)
        assertTrue(second is LiveErrorAction.NextChannel)
        assertEquals(0, c.currentIndex)
        assertEquals(0, c.lineIndex) // 回主线路
        // 下一次失败应重新试备用线路，而不是直接跳台
        val third = c.onPlaybackError(panelOpen = false)
        assertTrue(third is LiveErrorAction.TryLine)
        assertEquals(1, (third as LiveErrorAction.TryLine).lineIndex)
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
    fun `playback error while panel open never gives up even on sixth failure`() {
        // 第 7 轮 P2-9：面板判断在 GiveUp 之前——面板打开时第 6 次失败也不弹
        // 失败页（旧顺序先判 failCount > 5，失败页叠在面板之下还抢焦点）；
        // 计数照常累加，关面板后的下一次失败仍按上限停手。
        val c = LiveController(threeChannels)
        repeat(6) { i ->
            val action = c.onPlaybackError(panelOpen = true)
            assertTrue("第 ${i + 1} 次失败仍应只通知", action is LiveErrorAction.NotifyOnly)
        }
        assertEquals(6, c.failCount)
        assertEquals(0, c.currentIndex)
        // 关面板后下一次失败：计数已超上限，停手
        val afterClose = c.onPlaybackError(panelOpen = false)
        assertTrue(afterClose is LiveErrorAction.GiveUp)
        assertEquals(7, c.failCount)
    }

    @Test
    fun `playback success only resets after ten seconds stable`() {
        // P1-6：短暂播出不再立即清零；连续稳定播出满 10 秒才清零
        val c = LiveController(threeChannels)
        c.onPlaybackError(panelOpen = false) // 试了备用线路
        assertEquals(1, c.lineIndex)
        c.onPlaybackSuccess(nowMs = 1_000L)
        assertEquals(1, c.failCount) // 未满 10 秒不清零
        assertFalse(c.confirmStablePlayback(nowMs = 5_000L))
        assertEquals(1, c.failCount)
        assertTrue(c.confirmStablePlayback(nowMs = 11_000L))
        assertEquals(0, c.failCount)
        // 已试集合清零：下次失败应重新从备用线路试起
        val action = c.onPlaybackError(panelOpen = false)
        assertTrue(action is LiveErrorAction.TryLine)
    }

    @Test
    fun `short success then error voids stable window`() {
        // P1-6：播 1 秒就断流不算稳定，失败计数继续累加，不被清零
        val c = LiveController(threeChannels)
        c.onPlaybackError(panelOpen = false)
        c.onPlaybackSuccess(nowMs = 1_000L)
        c.onPlaybackError(panelOpen = false) // 2 秒后又断
        assertEquals(2, c.failCount)
        assertFalse(c.confirmStablePlayback(nowMs = 11_000L))
        assertEquals(2, c.failCount)
    }

    @Test
    fun `short plays across channels keep accumulating failures`() {
        // P1-6：A 台播 1 秒断流→跳 B 台播 1 秒断流……计数不再清零，终会 >5 停手
        val c = LiveController(threeChannels)
        var last: LiveErrorAction = c.onPlaybackError(panelOpen = false)
        repeat(5) {
            c.onPlaybackSuccess(nowMs = 1_000L)
            c.nextChannel(isAuto = true)
            last = c.onPlaybackError(panelOpen = false)
        }
        assertTrue(last is LiveErrorAction.GiveUp)
        assertEquals(6, c.failCount)
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
    fun `favorite survives first line rotation`() {
        // A6-P2-7：收藏按 urls[0] 匹配时，M3U 轮换首线路后收藏的台直接消失；
        // 改按全部线路匹配，任一线路命中即算收藏
        val c = LiveController(threeChannels)
        c.toggleFavorite(threeChannels[0])
        assertTrue(c.isFavorite(threeChannels[0]))
        // 新表：同一台的首线路换了，原 1 号线变 2 号线
        val rotated = listOf(
            channel("CCTV-1", listOf("http://b/1-new.m3u8", "http://a/1.m3u8"), "央视"),
            channel("CCTV-5", listOf("http://a/5.m3u8"), "央视"),
            channel("广东卫视", listOf("http://a/gd.m3u8"), "卫视"),
        )
        c.setChannels(rotated, restoreUrl = null)
        assertTrue(c.isFavorite(rotated[0]))
        assertEquals(listOf("CCTV-1"), c.channelsForGroup(LiveController.FAV_GROUP).map { it.name })
        // 取消收藏：任一线路命中即清掉
        assertFalse(c.toggleFavorite(rotated[0]))
        assertFalse(c.isFavorite(rotated[0]))
        assertTrue(c.channelsForGroup(LiveController.FAV_GROUP).isEmpty())
    }

    @Test
    fun `setChannels restores previous line index`() {
        // A6-P2-7：上次线路也保留——正在播 2 号线时刷新，线路顺序轮换后
        // 仍回到 2 号线，不回主线路
        val c = LiveController(threeChannels, currentIndex = 0, lineIndex = 1)
        assertEquals("http://a/1b.m3u8", c.currentUrl)
        val rotated = listOf(
            channel("CCTV-1", listOf("http://b/1-new.m3u8", "http://a/1b.m3u8"), "央视"),
            channel("CCTV-5", listOf("http://a/5.m3u8"), "央视"),
            channel("广东卫视", listOf("http://a/gd.m3u8"), "卫视"),
        )
        c.setChannels(rotated, restoreUrl = "http://a/1b.m3u8", restoreName = "CCTV-1")
        assertEquals(0, c.currentIndex)
        assertEquals(1, c.lineIndex)
        assertEquals("http://a/1b.m3u8", c.currentUrl)
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

    @Test
    fun `restore last channel by url and name`() {
        // P2-4：同 URL 两条台，恢复时按 (url, name) 落到对的那条
        val dup = listOf(
            channel("台A", listOf("http://a/shared.m3u8")),
            channel("台B", listOf("http://a/shared.m3u8")),
        )
        val c = LiveController()
        c.setChannels(dup, restoreUrl = "http://a/shared.m3u8", restoreName = "台B")
        assertEquals(1, c.currentIndex)
        assertEquals("台B", c.currentChannel?.name)
        // 只给 URL 时仍按 URL 恢复到第一条（兼容旧行为）
        c.setChannels(dup, restoreUrl = "http://a/shared.m3u8")
        assertEquals(0, c.currentIndex)
    }

    @Test
    fun `restore falls back to name when url rotated`() {
        // A5-P1-4：手动刷新后当前台 URL 已轮换，按 name 回退找回同名台，
        // 不再静默跳到 1 台开播；同名多条时取第一条；名也找不到才落 0 台
        val fresh = listOf(
            channel("CCTV-1", listOf("http://b/1.m3u8")),
            channel("广东卫视", listOf("http://b/gd.m3u8")),
            channel("深圳卫视", listOf("http://b/sz.m3u8")),
        )
        val c = LiveController()
        c.setChannels(fresh, restoreUrl = "http://a/gd.m3u8", restoreName = "广东卫视")
        assertEquals(1, c.currentIndex)
        assertEquals("广东卫视", c.currentChannel?.name)
        // URL 在新表里但改了名：按 URL 找回
        c.setChannels(fresh, restoreUrl = "http://b/sz.m3u8", restoreName = "深圳台")
        assertEquals(2, c.currentIndex)
        // URL 与名都对不上：落 0 台
        c.setChannels(fresh, restoreUrl = "http://a/none.m3u8", restoreName = "不存在的台")
        assertEquals(0, c.currentIndex)
    }

    @Test
    fun `index of channel distinguishes duplicate urls by identity`() {
        // P2-4：同 URL 出现两次时，点第二条不再落到第一条
        val first = channel("卫视", listOf("http://a/shared.m3u8"))
        val second = channel("卫视", listOf("http://a/shared.m3u8"))
        val list = listOf(first, second)
        assertEquals(0, LiveController.indexOfChannel(list, first))
        assertEquals(1, LiveController.indexOfChannel(list, second))
        // 同名同 URL 的另一对象按 (url, name) 回退到第一条
        val copy = channel("卫视", listOf("http://a/shared.m3u8"))
        assertEquals(0, LiveController.indexOfChannel(list, copy))
        assertEquals(-1, LiveController.indexOfChannel(list, channel("其他", listOf("http://a/other.m3u8"))))
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
