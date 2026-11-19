package com.oriontv.feature.remoteinput

import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-3（第 5 轮）：chunked 编码判定——读体前的拒绝依据。 */
class RemoteInputServerTest {

    /** 找一个当前空闲端口（ServerSocket(0) 分配后立即释放，测试内使用）。 */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `chunked transfer encoding is detected`() {
        assertTrue(isChunkedTransfer(mapOf("transfer-encoding" to "chunked")))
        // 键/值大小写不敏感，防止上游改动导致漏判。
        assertTrue(isChunkedTransfer(mapOf("Transfer-Encoding" to "Chunked")))
        assertTrue(isChunkedTransfer(mapOf("transfer-encoding" to "gzip, chunked")))
    }

    @Test
    fun `non chunked requests are not rejected`() {
        assertFalse(isChunkedTransfer(emptyMap()))
        assertFalse(isChunkedTransfer(mapOf("content-length" to "100")))
        assertFalse(isChunkedTransfer(mapOf("transfer-encoding" to "gzip")))
        assertFalse(isChunkedTransfer(mapOf("content-encoding" to "chunked")))
    }

    @Test
    fun `body size limit counts bytes not characters`() {
        // 第 6 轮 P2-13：中文 3 字节/字符——6000 个汉字是 6000 字符（≤16K）
        // 却是 18000 字节（>16K），按字符数判会漏放。
        assertTrue(isRequestBodyTooLarge("你".repeat(6000)))
        assertFalse(isRequestBodyTooLarge("你".repeat(5000)))
        // ASCII 1 字节/字符：边界上 16384 不超、16385 超。
        assertFalse(isRequestBodyTooLarge("a".repeat(16 * 1024)))
        assertTrue(isRequestBodyTooLarge("a".repeat(16 * 1024 + 1)))
        assertFalse(isRequestBodyTooLarge(""))
    }

    @Test
    fun `first free candidate port is used`() {
        val first = freePort()
        val second = freePort()
        val started = RemoteInputServer.startOnFirstFreePort(
            RemoteInputRouter(),
            listOf(first, second),
        )
        assertNotNull(started)
        val (server, port) = started!!
        try {
            assertEquals(first, port)
            assertTrue(server.isAlive)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `occupied first candidate falls through to next port`() {
        // 第 76 轮 v2.0.14：12346 被 RN 版占着时顺延——这里用占住的端口模拟。
        val occupied = freePort()
        val next = freePort()
        ServerSocket(occupied).use { blocker ->
            val started = RemoteInputServer.startOnFirstFreePort(
                RemoteInputRouter(),
                listOf(occupied, next),
            )
            assertNotNull(started)
            val (server, port) = started!!
            try {
                assertEquals(next, port)
                assertTrue(server.isAlive)
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun `all candidates occupied returns null`() {
        val first = freePort()
        val second = freePort()
        ServerSocket(first).use {
            ServerSocket(second).use {
                assertNull(
                    RemoteInputServer.startOnFirstFreePort(
                        RemoteInputRouter(),
                        listOf(first, second),
                    ),
                )
            }
        }
    }
}
