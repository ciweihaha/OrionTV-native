package com.oriontv.data.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** server-config 失败缓存归因测试（第 7 轮 D7-P2-9）：只记网络失败。 */
class SettingsFailureCacheTest {

    @Test
    fun `no failure is not cached`() {
        val cache = ServerConfigFailureCache(nowMs = { 1_000L })
        assertFalse(cache.isCached("https://example.com"))
    }

    @Test
    fun `network failure is cached for the same url within 30 seconds`() {
        var now = 1_000L
        val cache = ServerConfigFailureCache(nowMs = { now })
        cache.recordNetworkFailure("https://example.com")
        assertTrue(cache.isCached("https://example.com"))
        assertFalse(cache.isCached("https://other.example.com"))
        assertFalse(cache.isCached(""))
        now += 29_000L
        assertTrue(cache.isCached("https://example.com"))
        now += 2_000L
        assertFalse(cache.isCached("https://example.com"))
    }

    @Test
    fun `clear resets the cache`() {
        val cache = ServerConfigFailureCache(nowMs = { 1_000L })
        cache.recordNetworkFailure("https://example.com")
        assertTrue(cache.isCached("https://example.com"))
        cache.clear()
        assertFalse(cache.isCached("https://example.com"))
    }

    @Test
    fun `latest network failure replaces the previous url`() {
        val cache = ServerConfigFailureCache(nowMs = { 1_000L })
        cache.recordNetworkFailure("https://a.example.com")
        cache.recordNetworkFailure("https://b.example.com")
        assertFalse(cache.isCached("https://a.example.com"))
        assertTrue(cache.isCached("https://b.example.com"))
    }
}
