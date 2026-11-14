package com.oriontv.data.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class AggApiTest {

    @Test
    fun `only timeout trips the breaker`() {
        // 体超时（OkHttp 读/连接超时）才记熔断；DNS 失败、其他 IO 错只重试
        assertTrue(AggApi.isBodyTimeout(SocketTimeoutException("timeout")))
        assertFalse(AggApi.isBodyTimeout(UnknownHostException("ysp.ciweihaha.win")))
        assertFalse(AggApi.isBodyTimeout(IOException("connection reset")))
    }
}
