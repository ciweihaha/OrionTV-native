package com.oriontv.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第 6 轮 P1-2：翻转类操作（收藏切换/页内删除）的防连按守卫。 */
class InFlightGuardTest {

    @Test
    fun `first acquire succeeds second fails while in flight`() {
        val guard = InFlightGuard()
        assertTrue(guard.tryAcquire())
        assertFalse(guard.tryAcquire())
        assertTrue(guard.isInFlight())
    }

    @Test
    fun `release allows acquiring again`() {
        val guard = InFlightGuard()
        assertTrue(guard.tryAcquire())
        guard.release()
        assertFalse(guard.isInFlight())
        assertTrue(guard.tryAcquire())
    }

    @Test
    fun `different keys do not block each other`() {
        // 收藏页页内删除按记录键区分：不同卡片的删除互不阻塞。
        val guard = InFlightGuard()
        assertTrue(guard.tryAcquire("agg+1"))
        assertTrue(guard.tryAcquire("agg+2"))
        assertFalse(guard.tryAcquire("agg+1"))
        assertTrue(guard.isInFlight("agg+1"))
        assertTrue(guard.isInFlight("agg+2"))
        guard.release("agg+1")
        assertFalse(guard.isInFlight("agg+1"))
        assertTrue(guard.isInFlight("agg+2"))
        assertTrue(guard.tryAcquire("agg+1"))
    }

    @Test
    fun `releasing a key that was never acquired is a no-op`() {
        val guard = InFlightGuard()
        guard.release("agg+9")
        assertFalse(guard.isInFlight("agg+9"))
        assertTrue(guard.tryAcquire("agg+9"))
    }
}
