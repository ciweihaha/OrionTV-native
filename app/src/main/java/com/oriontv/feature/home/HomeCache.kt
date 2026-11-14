package com.oriontv.feature.home

/**
 * 首页缓存（plan §4.3：5 分钟 / 10 分类 / 每类 40 条、真 LRU）。
 * 纯逻辑类，可单测；时间由调用方注入，便于测试。
 */
class HomeCache(
    private val ttlMs: Long = CACHE_TTL_MS,
    private val maxCategories: Int = MAX_CATEGORIES,
    private val maxItemsPerCategory: Int = MAX_ITEMS_PER_CATEGORY,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class Entry(val items: List<HomeRowItem>, val timestampMs: Long)

    // accessOrder=true：get/put 都算访问，最久未访问的先淘汰。
    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > maxCategories
    }

    /** 命中返回列表（未过期才算命中），否则 null。 */
    @Synchronized
    fun get(key: String): List<HomeRowItem>? {
        val entry = cache[key] ?: return null
        if (nowMs() - entry.timestampMs > ttlMs) {
            cache.remove(key)
            return null
        }
        return entry.items
    }

    @Synchronized
    fun put(key: String, items: List<HomeRowItem>) {
        cache[key] = Entry(items.take(maxItemsPerCategory), nowMs())
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }

    companion object {
        /** 首页缓存 5 分钟过期。 */
        const val CACHE_TTL_MS = 5 * 60 * 1000L
        /** 最多缓存 10 个分类。 */
        const val MAX_CATEGORIES = 10
        /** 每类最多 40 条。 */
        const val MAX_ITEMS_PER_CATEGORY = 40
    }
}
