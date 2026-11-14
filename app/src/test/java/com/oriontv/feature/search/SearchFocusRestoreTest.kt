package com.oriontv.feature.search

import com.oriontv.data.api.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

/** P2-9（第 5 轮）：返回焦点恢复按条目键锚定，不按 index。 */
class SearchFocusRestoreTest {

    private fun result(source: String, id: String) = SearchResult(
        id = id,
        title = "片名$id",
        poster = "",
        source = source,
        source_name = source,
    )

    @Test
    fun `finds result by key after reorder`() {
        val results = listOf(result("agg", "2"), result("agg", "1"), result("agg", "3"))
        // 原来在 index 1 的那条，刷新后挪到了 index 0。
        assertEquals(1, findSavedResult(results, "agg+1"))
        assertEquals(0, findSavedResult(results, "agg+2"))
    }

    @Test
    fun `returns minus one when missing`() {
        val results = listOf(result("agg", "2"))
        assertEquals(-1, findSavedResult(results, "agg+1"))
        assertEquals(-1, findSavedResult(results, null))
        assertEquals(-1, findSavedResult(emptyList(), "agg+1"))
    }
}
