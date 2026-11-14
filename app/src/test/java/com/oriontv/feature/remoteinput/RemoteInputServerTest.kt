package com.oriontv.feature.remoteinput

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-3（第 5 轮）：chunked 编码判定——读体前的拒绝依据。 */
class RemoteInputServerTest {

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
}
