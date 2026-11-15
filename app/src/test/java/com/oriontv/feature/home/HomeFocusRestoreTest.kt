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
    fun `unconfigured empty state recovers focus on tick`() {
        // C12-P1-6：未配置空态不在 tick 恢复覆盖内（effect B 在 early return
        // 之后）；更新卡片关闭后 tick +1、仍未配置时落焦“去设置”，已配置时不动作。
        assertTrue(shouldFocusEmptyStateOnTick(focusRestoreTick = 1, serverConfigured = false))
        assertFalse(shouldFocusEmptyStateOnTick(focusRestoreTick = 0, serverConfigured = false))
        assertFalse(shouldFocusEmptyStateOnTick(focusRestoreTick = 1, serverConfigured = true))
    }
}
