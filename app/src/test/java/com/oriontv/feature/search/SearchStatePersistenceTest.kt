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

    @Test
    fun `search error kind round-trips for every failure type`() {
        // B33-P2-1：错误种类持久化后按原样恢复——失败不再被写成 Empty。
        val cases: List<Pair<SearchError, String?>> = listOf(
            SearchError.NotConfigured to null,
            SearchError.Empty to null,
            SearchError.Unauthorized to null,
            SearchError.DataError to null,
            SearchError.Timeout to null,
            SearchError.Network() to "网络错误，请检查网络后重试",
            SearchError.Network("搜索失败：custom") to "搜索失败：custom",
            SearchError.Server() to "服务器错误，请稍后重试",
            SearchError.Server("服务器错误（HTTP 503），请稍后重试") to
                "服务器错误（HTTP 503），请稍后重试",
        )
        for ((error, message) in cases) {
            val kind = encodeSearchError(error)
            assertEquals(error, decodeSearchError(kind, message))
        }
    }

    @Test
    fun `failure error never decodes as empty`() {
        // B33-P2-1 的回归断言：Timeout/Network/NotConfigured 恢复后仍是
        // 原来的失败（“重试”/“去设置”按钮的门控才在），不是 Empty。
        assertTrue(decodeSearchError(encodeSearchError(SearchError.Timeout), null) != SearchError.Empty)
        assertTrue(decodeSearchError(encodeSearchError(SearchError.Network()), null) != SearchError.Empty)
        assertTrue(decodeSearchError(encodeSearchError(SearchError.NotConfigured), null) != SearchError.Empty)
        assertEquals(SearchError.Empty, decodeSearchError(encodeSearchError(SearchError.Empty), null))
    }

    @Test
    fun `missing or unknown saved error kind decodes to null`() {
        assertEquals(null, decodeSearchError(null, null))
        assertEquals(null, encodeSearchError(null))
        // 旧版本没存过该键、或键值损坏时不凭空造错误。
        assertEquals(null, decodeSearchError("BogusKind", "xxx"))
    }
}
