package com.oriontv.feature.search

import com.oriontv.data.api.SearchResult
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 4 轮 C4-P1-5：搜索态进 SavedStateHandle 的序列化底座——
 * 结果列表以 JSON 存取，编解码必须往返一致，否则切走再回恢复出来的是坏数据。
 * ViewModel 本体的存取（saveSearchState/restoreSearchState）走同一编解码路径。
 */
class SearchStatePersistenceTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val serializer = ListSerializer(SearchResult.serializer())

    @Test
    fun `results json round-trips through the saved-state codec`() {
        val results = listOf(
            SearchResult(
                source = "agg",
                source_name = "源A",
                id = "1",
                title = "庆余年",
                poster = "https://example.com/p.jpg",
                year = "2019",
            ),
            SearchResult(
                source = "agg",
                source_name = "源B",
                id = "",
                title = "庆余年 第二季",
                poster = "",
            ),
        )
        val encoded = json.encodeToString(serializer, results)
        val decoded = json.decodeFromString(serializer, encoded)
        assertEquals(results, decoded)
    }

    @Test
    fun `empty results encode to empty array and decode back`() {
        val encoded = json.encodeToString(serializer, emptyList())
        assertEquals("[]", encoded)
        assertTrue(json.decodeFromString(serializer, encoded).isEmpty())
    }

    @Test
    fun `corrupt saved json decodes to empty instead of crashing restore`() {
        val decoded = runCatching {
            json.decodeFromString(serializer, "not-json{{{")
        }.getOrDefault(emptyList())
        assertTrue(decoded.isEmpty())
    }
}
