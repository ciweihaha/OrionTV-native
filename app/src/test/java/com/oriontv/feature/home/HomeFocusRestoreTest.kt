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
}
