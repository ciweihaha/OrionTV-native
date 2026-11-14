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
}
