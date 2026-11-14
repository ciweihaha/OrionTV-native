package com.oriontv.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CircuitBreakerTest {

    @Test
    fun `opens after three consecutive failures`() {
        var now = 0L
        val breaker = CircuitBreaker(nowMs = { now })
        assertFalse(breaker.isOpen())
        breaker.recordFailure()
        breaker.recordFailure()
        assertFalse(breaker.isOpen())
        breaker.recordFailure()
        assertTrue(breaker.isOpen())
    }

    @Test
    fun `closes after cooldown and allows retry`() {
        var now = 0L
        val breaker = CircuitBreaker(nowMs = { now })
        repeat(3) { breaker.recordFailure() }
        assertTrue(breaker.isOpen())
        now += 59_000L
        assertTrue(breaker.isOpen())
        now += 2_000L
        assertFalse(breaker.isOpen())
    }

    @Test
    fun `success resets failure count`() {
        var now = 0L
        val breaker = CircuitBreaker(nowMs = { now })
        breaker.recordFailure()
        breaker.recordFailure()
        breaker.recordSuccess()
        breaker.recordFailure()
        breaker.recordFailure()
        assertFalse(breaker.isOpen())
    }

    @Test
    fun `half-open admits only one probe`() {
        // B5-P2-4：冷却期满后第一个调用方拿到试探租约，其余继续拒绝。
        var now = 0L
        val breaker = CircuitBreaker(nowMs = { now })
        repeat(3) { breaker.recordFailure() }
        assertTrue(breaker.isOpen())
        now += 61_000L
        assertFalse(breaker.isOpen())
        assertTrue(breaker.isOpen())
        assertTrue(breaker.isOpen())
    }

    @Test
    fun `probe failure reopens immediately`() {
        // B5-P2-4：探针失败一次即重开（冷却期满时计数值仍保留着开闸前的 3 次）。
        var now = 0L
        val breaker = CircuitBreaker(nowMs = { now })
        repeat(3) { breaker.recordFailure() }
        now += 61_000L
        assertFalse(breaker.isOpen())
        breaker.recordFailure()
        assertTrue(breaker.isOpen())
    }

    @Test
    fun `probe success closes breaker`() {
        var now = 0L
        val breaker = CircuitBreaker(nowMs = { now })
        repeat(3) { breaker.recordFailure() }
        now += 61_000L
        assertFalse(breaker.isOpen())
        breaker.recordSuccess()
        assertFalse(breaker.isOpen())
        repeat(2) { breaker.recordFailure() }
        assertFalse(breaker.isOpen())
    }

    @Test
    fun `probe lease expiry admits a new probe`() {
        // B5-P2-4：探针挂死（120 秒租约过期）后允许下一个调用方重新试探。
        var now = 0L
        val breaker = CircuitBreaker(nowMs = { now })
        repeat(3) { breaker.recordFailure() }
        now += 61_000L
        assertFalse(breaker.isOpen())
        now += 119_000L
        assertTrue(breaker.isOpen())
        now += 1_000L
        assertFalse(breaker.isOpen())
        assertTrue(breaker.isOpen())
    }

    @Test
    fun `releaseProbe frees the probe without counting a failure`() {
        // B5-P2-4：探针以非超时原因终态失败时只释放租约、不记失败，
        // 下一个调用方可立即重新试探。
        var now = 0L
        val breaker = CircuitBreaker(nowMs = { now })
        repeat(3) { breaker.recordFailure() }
        now += 61_000L
        assertFalse(breaker.isOpen())
        breaker.releaseProbe()
        assertFalse(breaker.isOpen())
        assertTrue(breaker.isOpen())
    }
}

class ApiClientRulesTest {

    @Test
    fun `only 5xx is retryable`() {
        assertTrue(ApiClient.shouldRetryOnStatus(500))
        assertTrue(ApiClient.shouldRetryOnStatus(503))
        assertFalse(ApiClient.shouldRetryOnStatus(400))
        assertFalse(ApiClient.shouldRetryOnStatus(401))
        assertFalse(ApiClient.shouldRetryOnStatus(404))
    }

    @Test
    fun `only idempotent methods retry after 401 refresh`() {
        assertTrue(ApiClient.isIdempotent("GET"))
        assertTrue(ApiClient.isIdempotent("DELETE"))
        assertTrue(ApiClient.isIdempotent("HEAD"))
        assertFalse(ApiClient.isIdempotent("POST"))
    }
}
