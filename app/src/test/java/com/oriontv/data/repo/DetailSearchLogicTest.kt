package com.oriontv.data.repo

import com.oriontv.data.api.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetailSearchLogicTest {

    private fun result(
        source: String,
        title: String = "庆余年",
        id: String = "1",
        episodes: Int = 5,
    ) = SearchResult(
        id = id,
        title = title,
        poster = "",
        episodes = (1..episodes).map { "http://x/$it.m3u8" },
        source = source,
        source_name = "源$source",
    )

    @Test
    fun `preferred source matches by id first`() {
        val results = listOf(
            result("a", id = "2"),
            result("a", id = "1"),
        )
        val picked = DetailSearchLogic.selectPreferredResults(results, "庆余年", "1")
        assertEquals(listOf("1"), picked.map { it.id })
    }

    @Test
    fun `preferred source falls back to exact title when id misses`() {
        val results = listOf(
            result("a", title = "庆余年第二季", id = "9"),
            result("a", title = "庆余年", id = "8"),
        )
        val picked = DetailSearchLogic.selectPreferredResults(results, "庆余年 ", "100")
        assertEquals(listOf("8"), picked.map { it.id })
    }

    @Test
    fun `preferred source keeps all when neither id nor title matches`() {
        val results = listOf(result("a", title = "其他剧"))
        val picked = DetailSearchLogic.selectPreferredResults(results, "庆余年", "100")
        assertEquals(1, picked.size)
    }

    @Test
    fun `exact title match normalizes whitespace and case`() {
        val results = listOf(
            result("a", title = "庆余年"),
            result("b", title = "庆余年第二季"),
            result("c", title = " 庆 余 年 "),
        )
        val matched = DetailSearchLogic.filterExactTitle(results, "庆余年")
        assertEquals(listOf("a", "c"), matched.map { it.source })
    }

    @Test
    fun `dedupe keeps first result per source`() {
        val results = listOf(
            result("a", title = "庆余年"),
            result("a", title = "庆余年第二季"),
            result("b", title = "庆余年"),
        )
        val deduped = DetailSearchLogic.dedupeBySource(results)
        assertEquals(listOf("a", "b"), deduped.map { it.source })
        assertEquals("庆余年", deduped[0].title)
    }

    @Test
    fun `next available source skips failed and short episode lists`() {
        val results = listOf(
            result("a", episodes = 10),
            result("b", episodes = 2), // 集数不够当前集
            result("c", episodes = 10),
        )
        val next = DetailSearchLogic.getNextAvailableSource(
            results, currentSource = "a", episodeIndex = 5, failedSources = setOf("c"),
        )
        assertNull(next)
        val next2 = DetailSearchLogic.getNextAvailableSource(
            results, currentSource = "a", episodeIndex = 1, failedSources = setOf("c"),
        )
        assertEquals("b", next2?.source)
    }

    @Test
    fun `episode groups fold long series by twenty`() {
        assertEquals(listOf(1 to 20, 21 to 40, 41 to 45), DetailSearchLogic.episodeGroups(45))
        assertEquals(listOf(1 to 12), DetailSearchLogic.episodeGroups(12))
        assertEquals(emptyList<Pair<Int, Int>>(), DetailSearchLogic.episodeGroups(0))
    }
}
