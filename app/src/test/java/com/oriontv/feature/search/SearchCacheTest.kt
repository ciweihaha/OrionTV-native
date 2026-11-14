package com.oriontv.feature.search

import com.oriontv.data.api.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchCacheTest {

    private fun result(title: String) = SearchResult(
        id = "1", title = title, poster = "", source = "a", source_name = "源A",
    )

    @Test
    fun `hit within ttl and miss after expiry`() {
        var now = 0L
        val cache = SearchCache(nowMs = { now })
        cache.put("庆余年", listOf(result("庆余年")))
        assertEquals(1, cache.get("庆余年")?.size)
        now += SearchCache.CACHE_TTL_MS + 1
        assertNull(cache.get("庆余年"))
    }

    @Test
    fun `evicts least recently used term over fifty`() {
        val cache = SearchCache()
        for (i in 0 until 50) cache.put("词$i", listOf(result("词$i")))
        cache.get("词0")
        cache.put("词50", listOf(result("词50")))
        assertEquals(1, cache.get("词0")?.size)
        assertNull(cache.get("词1"))
    }
}
