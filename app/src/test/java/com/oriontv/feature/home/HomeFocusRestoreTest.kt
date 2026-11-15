package com.oriontv.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
