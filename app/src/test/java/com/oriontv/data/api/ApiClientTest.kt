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
