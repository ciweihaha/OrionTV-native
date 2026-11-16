package com.oriontv.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** P2-9（第 5 轮）：返回焦点恢复按条目键锚定，不按 index。 */
class HomeFocusRestoreTest {

    private fun row(title: String, vararg items: HomeRowItem) = HomeRow(title, items.toList())

    private fun item(source: String, id: String) = HomeRowItem(
        id = id,
        source = source,
        title = "片名$id",
        poster = "",
    )

    @Test
    fun `finds card in the same row`() {
        val rows = listOf(
            row("最近播放", item("agg", "1"), item("agg", "2")),
            row("热门剧集", item("agg", "3")),
        )
        val key = homeFocusKey("最近播放", item("agg", "2"))
        assertEquals(0 to 1, findSavedCard(rows, key))
    }

    @Test
    fun `falls back to another row with the same item`() {
        // 最近播放行被刷新掉了，同一部片还在热门行里。
        val rows = listOf(
            row("热门剧集", item("agg", "1"), item("agg", "2")),
        )
        val key = homeFocusKey("最近播放", item("agg", "2"))
        assertEquals(0 to 1, findSavedCard(rows, key))
    }

    @Test
    fun `returns null when item is gone`() {
        val rows = listOf(row("热门剧集", item("agg", "3")))
        val key = homeFocusKey("最近播放", item("agg", "2"))
        assertNull(findSavedCard(rows, key))
        assertNull(findSavedCard(rows, null))
        assertNull(findSavedCard(emptyList(), key))
    }

    @Test
    fun `recent row merge replaces old recent row at top`() {
        // B11-P2-2：新“最近播放”行插到首位，旧行去掉，其他行不动、顺序不变。
        val rows = listOf(
            row("最近播放", item("agg", "1")),
            row("热门剧集", item("agg", "2")),
            row("电影", item("agg", "3")),
        )
        val recent = row("最近播放", item("agg", "9"))
        val merged = mergeRecentRow(rows, recent)
        assertEquals(listOf("最近播放", "热门剧集", "电影"), merged.map { it.title })
        assertEquals(listOf("9"), merged[0].items.map { it.id })
        assertEquals(listOf("2"), merged[1].items.map { it.id })
    }

    @Test
    fun `recent row merge with no records only removes the old row`() {
        // B11-P2-2：确实没有播放记录时只删旧行，不动其他行。
        val rows = listOf(
            row("热门剧集", item("agg", "2")),
            row("最近播放", item("agg", "1")),
            row("电影", item("agg", "3")),
        )
        val merged = mergeRecentRow(rows, null)
        assertEquals(listOf("热门剧集", "电影"), merged.map { it.title })
    }

    @Test
    fun `recent row merge inserts at top when there was no recent row`() {
        val rows = listOf(row("热门剧集", item("agg", "2")))
        val merged = mergeRecentRow(rows, row("最近播放", item("agg", "9")))
        assertEquals(listOf("最近播放", "热门剧集"), merged.map { it.title })
    }

    @Test
    fun `retry success after first load failure recovers focus`() {
        // C12-P1-1：首载失败时 restoreDone 已被置 true，重试成功后行由空变非空、
        // 错误页“重试”按钮被卸载、焦点丢失；此时再跑一次恢复。常驻期间行变化
        // （refreshRecent）不满足“由空变非空”，不抢焦点。
        assertTrue(shouldRecoverFocusAfterRetry(rowsWereEmpty = true, hasRows = true, restoreDone = true))
        assertFalse(shouldRecoverFocusAfterRetry(rowsWereEmpty = false, hasRows = true, restoreDone = true))
        assertFalse(shouldRecoverFocusAfterRetry(rowsWereEmpty = true, hasRows = false, restoreDone = true))
        assertFalse(shouldRecoverFocusAfterRetry(rowsWereEmpty = true, hasRows = true, restoreDone = false))
    }

    @Test
    fun `first load failure focuses retry button`() {
        // C21-P2-1：首载失败（有错误且行空）时落焦“重试”而非首 chip；
        // 有行时不抢（行内失败留占位行），无错误时也不抢。
        val rows = listOf(row("热门剧集", item("agg", "2")))
        assertTrue(shouldFocusHomeRetry(error = "网络错误", rows = emptyList()))
        assertFalse(shouldFocusHomeRetry(error = "网络错误", rows = rows))
        assertFalse(shouldFocusHomeRetry(error = null, rows = emptyList()))
        assertFalse(shouldFocusHomeRetry(error = null, rows = rows))
    }

    @Test
    fun `error arriving after first chip still focuses retry`() {
        // C22-P2-1：C21-P2-1 只钉了纯函数、没钉接线——首帧组合时 error 仍是
        // null，effect A 首进分支恒走落首 chip、initialFocusDone 置真；错误
        // 到达时 key 不变、effect 不重跑，焦点仍在首 chip。本谓词钉住接线：
        // 已落过首 chip（lastErrorFocused 仍为 null、initialFocusDone 已真）
        // 后错误到达（null 变非 null、行空）仍落“重试”；同一失败只触发一次，
        // 行非空时不抢，无错误时不触发。
        val rows = listOf(row("热门剧集", item("agg", "2")))
        // 错误到达：行空、此前未对该失败落过焦 → 落重试。
        assertTrue(shouldFocusHomeRetryOnErrorArrival(error = "网络错误", rows = emptyList(), lastErrorFocused = null))
        // 同一失败重复到达（已落过焦）→ 不再触发。
        assertFalse(shouldFocusHomeRetryOnErrorArrival(error = "网络错误", rows = emptyList(), lastErrorFocused = "网络错误"))
        // 新失败到达（文案变了）→ 再次触发。
        assertTrue(shouldFocusHomeRetryOnErrorArrival(error = "服务器错误", rows = emptyList(), lastErrorFocused = "网络错误"))
        // 行非空时不抢。
        assertFalse(shouldFocusHomeRetryOnErrorArrival(error = "网络错误", rows = rows, lastErrorFocused = null))
        // 无错误时不触发。
        assertFalse(shouldFocusHomeRetryOnErrorArrival(error = null, rows = emptyList(), lastErrorFocused = null))
    }

    @Test
    fun `recent row applies only when all three generations match`() {
        // B14-P2-2：三代次全过才合并——自身代次、共享 generation、行版本。
        // 行版本专防先启动的 refresh() 在本次加载期间跑完写了新行：
        // 此时 genAtStart == generation 仍成立，双代次守卫拦不住。
        assertTrue(shouldApplyRecentRow(1, 1, 5, 5, 3, 3))
        // 更新的 refreshRecent 已启动
        assertFalse(shouldApplyRecentRow(1, 2, 5, 5, 3, 3))
        // refresh() 在本次加载期间才开始
        assertFalse(shouldApplyRecentRow(1, 1, 5, 6, 3, 3))
        // 先启动的 refresh() 在本次加载期间跑完写了新行
        assertFalse(shouldApplyRecentRow(1, 1, 5, 5, 3, 4))
    }
}
