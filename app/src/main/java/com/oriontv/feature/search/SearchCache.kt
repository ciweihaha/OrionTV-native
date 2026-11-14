package com.oriontv.feature.search

import com.oriontv.data.api.SearchResult

/**
 * 搜索缓存（plan §4.3：同一词 5 分钟 / 50 词）。
 * 纯逻辑类，可单测；时间由调用方注入，便于测试。
 */
class SearchCache(
    private val ttlMs: Long = CACHE_TTL_MS,
    private val maxTerms: Int = MAX_TERMS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class Entry(val results: List<SearchResult>, val timestampMs: Long)

    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > maxTerms
    }

    @Synchronized
    fun get(query: String): List<SearchResult>? {
        val entry = cache[query] ?: return null
        if (nowMs() - entry.timestampMs > ttlMs) {
            cache.remove(query)
            return null
        }
        return entry.results
    }

    @Synchronized
    fun put(query: String, results: List<SearchResult>) {
        cache[query] = Entry(results, nowMs())
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }

    companion object {
        /** 同一词 5 分钟过期。 */
        const val CACHE_TTL_MS = 5 * 60 * 1000L
        /** 最多缓存 50 个词。 */
        const val MAX_TERMS = 50
    }
}
