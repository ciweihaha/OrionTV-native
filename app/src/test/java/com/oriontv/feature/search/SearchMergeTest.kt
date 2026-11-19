package com.oriontv.feature.search

import com.oriontv.data.api.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 76 轮 v2.0.14：双路搜索合并——服务端结果在前、agg 结果过滤标题
 * （agg wd 是模糊搜）并按（归一化标题 + 来源名）去重。
 */
class SearchMergeTest {

    private fun result(
        title: String,
        sourceName: String,
        source: String = "agg",
        id: String? = null,
    ) = SearchResult(id = id, title = title, source = source, source_name = sourceName)

    @Test
    fun `server results come first then matching agg results`() {
        val merged = mergeSearchResults(
            serverResults = listOf(result("阿龙", "站点A", source = "moontv")),
            aggResults = listOf(
                result("阿龙传奇", "聚合"),
                result("阿龙", "聚合"),
            ),
            query = "阿龙",
        )
        assertEquals(
            listOf("阿龙", "阿龙传奇", "阿龙"),
            merged.map { it.title },
        )
        assertEquals("moontv", merged[0].source)
    }

    @Test
    fun `agg results not containing query are dropped`() {
        val merged = mergeSearchResults(
            serverResults = emptyList(),
            aggResults = listOf(
                result("阿龙", "聚合"),
                result("完全不相关的片", "聚合"),
                result("龙的传人", "聚合"),
            ),
            query = "阿龙",
        )
        assertEquals(listOf("阿龙"), merged.map { it.title })
    }

    @Test
    fun `same title and source name dedupes keeping server copy`() {
        val merged = mergeSearchResults(
            serverResults = listOf(result("阿龙", "聚合", source = "moontv")),
            aggResults = listOf(result("阿龙", "聚合")),
            query = "阿龙",
        )
        assertEquals(1, merged.size)
        assertEquals("moontv", merged[0].source)
    }

    @Test
    fun `same title different source name is kept twice`() {
        val merged = mergeSearchResults(
            serverResults = listOf(result("阿龙", "站点A", source = "moontv")),
            aggResults = listOf(result("阿龙", "站点B")),
            query = "阿龙",
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `title matching ignores case and whitespace`() {
        val merged = mergeSearchResults(
            serverResults = emptyList(),
            aggResults = listOf(result("A Long", "聚合")),
            query = "a long",
        )
        assertEquals(listOf("A Long"), merged.map { it.title })
    }

    @Test
    fun `empty inputs return empty`() {
        assertTrue(mergeSearchResults(emptyList(), emptyList(), "阿龙").isEmpty())
    }

    @Test
    fun `dedupe key normalizes title and joins source name`() {
        assertEquals(
            searchResultDedupeKey(result("阿 龙", "聚合")),
            searchResultDedupeKey(result("阿龙", "聚合")),
        )
    }
}
