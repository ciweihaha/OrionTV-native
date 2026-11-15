package com.oriontv.feature.home

/**
 * 首页缓存（plan §4.3：5 分钟 / 10 分类 / 每类 40 条、真 LRU）。
 * 纯逻辑类，可单测；时间由调用方注入，便于测试。
 * 缓存只存第 1 页（分页追加的卡片只放 ViewModel 的行状态，不进缓存；
 * 刷新后行从第 1 页重建，分页重新开始）。条目里顺带记第 1 页实际走的
 * 源（CMS 首屏失败回落豆瓣时记 DOUBAN），缓存命中刷新后分页按同一源续取，
 * 不混源。
 */
class HomeCache(
    private val ttlMs: Long = CACHE_TTL_MS,
    private val maxCategories: Int = MAX_CATEGORIES,
    private val maxItemsPerCategory: Int = MAX_ITEMS_PER_CATEGORY,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class Entry(
        val items: List<HomeRowItem>,
        val timestampMs: Long,
        val sourceKind: RowSource? = null,
    )

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

    /** 命中返回第 1 页实际走的源（未命中/已过期/存时没记源时返回 null；没记源的行不分页，等下次新鲜加载）。 */
    @Synchronized
    fun getSourceKind(key: String): RowSource? {
        val entry = cache[key] ?: return null
        if (nowMs() - entry.timestampMs > ttlMs) {
            cache.remove(key)
            return null
        }
        return entry.sourceKind
    }

    @Synchronized
    fun put(key: String, items: List<HomeRowItem>, sourceKind: RowSource? = null) {
        cache[key] = Entry(items.take(maxItemsPerCategory), nowMs(), sourceKind)
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
