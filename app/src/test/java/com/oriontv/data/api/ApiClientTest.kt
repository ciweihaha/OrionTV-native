package com.oriontv.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

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
    fun `5xx and 429 are retryable`() {
        assertTrue(ApiClient.shouldRetryOnStatus(500))
        assertTrue(ApiClient.shouldRetryOnStatus(503))
        // 第 36 轮：429 是限流（瞬时失败），与 5xx 同款可重试——之前只认 >= 500，
        // 429 被当确定性 4xx 立即失败，与 B4-P2-10“5xx/429 是瞬时失败”的约定相悖。
        assertTrue(ApiClient.shouldRetryOnStatus(429))
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

/** 可取消限量读体测试（第 7 轮 D7-P2-4）：阻塞式体读必须响应协程取消。 */
class ReadStreamCappedTest {

    /** 读时阻塞在门闩上、关流才放行的输入流（模拟体停顿的 socket 读）。 */
    private class GatedInputStream : java.io.InputStream() {
        private val gate = java.util.concurrent.CountDownLatch(1)

        @Volatile
        var closed = false

        override fun read(): Int {
            gate.await()
            throw java.io.IOException("stream closed")
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            gate.await()
            throw java.io.IOException("stream closed")
        }

        override fun close() {
            closed = true
            gate.countDown()
        }
    }

    private fun responseWithBody(body: String): okhttp3.Response {
        val request = okhttp3.Request.Builder().url("https://example.com/").build()
        return okhttp3.Response.Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaTypeOrNull()))
            .build()
    }

    @Test
    fun `normal read returns all bytes`() {
        val bytes = kotlinx.coroutines.runBlocking {
            readStreamCapped(java.io.ByteArrayInputStream("hello".toByteArray()), 1024L) {}
        }
        assertEquals("hello", bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `over-limit read throws BodyTooLargeException`() {
        org.junit.Assert.assertThrows(BodyTooLargeException::class.java) {
            kotlinx.coroutines.runBlocking {
                readStreamCapped(java.io.ByteArrayInputStream("0123456789".toByteArray()), 5L) {}
            }
        }
    }

    @Test
    fun `readBodyBytesLimited returns body and rejects oversize declared length`() {
        val bytes = kotlinx.coroutines.runBlocking {
            readBodyBytesLimited(responseWithBody("hello"), 1024L)
        }
        assertEquals("hello", bytes.toString(Charsets.UTF_8))
        org.junit.Assert.assertThrows(BodyTooLargeException::class.java) {
            kotlinx.coroutines.runBlocking {
                readBodyBytesLimited(responseWithBody("0123456789"), 5L)
            }
        }
    }

    @Test
    fun `cancelling body read closes stream and throws CancellationException promptly`() {
        // D7-P2-4 的核心：取消到达时关流、中断阻塞的 read()，不再等满
        // OkHttp 读超时；5 秒 withTimeout 是护栏——若取消递送不到，
        // await 挂住、超时抛错且流仍未关，测试失败。
        val stream = GatedInputStream()
        runBlocking {
            val deferred = async(Dispatchers.IO) {
                readStreamCapped(stream, 1024L) { stream.close() }
            }
            delay(100)
            deferred.cancel()
            val thrown: CancellationException? = try {
                withTimeout(5_000) { deferred.await() }
                null
            } catch (e: CancellationException) {
                e
            }
            org.junit.Assert.assertNotNull("取消的体读应抛 CancellationException", thrown)
            assertTrue(stream.closed)
        }
    }
}
