package com.oriontv.data.api

import org.junit.Assert.assertEquals
import org.junit.Test

class MoonTvApiKeyTest {

    @Test
    fun `plain source and id join with plus`() {
        assertEquals("tencent+123", MoonTvApi.generateKey("tencent", "123"))
        assertEquals("agg+庆余年", MoonTvApi.generateKey("agg", "庆余年"))
    }

    @Test
    fun `plus and percent inside segments are escaped`() {
        // 源里含 +：转义后按 + 切分仍能拆回两段。
        val key = MoonTvApi.generateKey("a+b", "c%d")
        assertEquals("a%2Bb+c%25d", key)
        assertEquals("a%2Bb", key.substringBefore("+"))
        assertEquals("c%25d", key.substringAfter("+"))
    }

    @Test
    fun `empty segments still produce a key`() {
        assertEquals("+", MoonTvApi.generateKey("", ""))
        assertEquals("src+", MoonTvApi.generateKey("src", ""))
    }
}
