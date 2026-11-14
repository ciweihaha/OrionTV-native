package com.oriontv.data.api

import org.junit.Assert.assertEquals
import org.junit.Test

class MoonTvApiKeyTest {

    @Test
    fun `generateKey escapes segments then joins with plus`() {
        // 数据层 P1-1 约定（与 RN 一致）：分段转义内部 %/+ 后用 + 连接。
        assertEquals("douban+123", MoonTvApi.generateKey("douban", "123"))
        assertEquals("a%2Bb+c", MoonTvApi.generateKey("a+b", "c"))
        assertEquals("a%25b+c", MoonTvApi.generateKey("a%b", "c"))
        assertEquals("a%2Bb+c%2Bd", MoonTvApi.generateKey("a+b", "c+d"))
    }

    @Test
    fun `split key at last plus matches RN`() {
        // 拆键按最后一个 + 切（PlayViewModel.retry 用）：转义过的段内不再有裸 +。
        val key = MoonTvApi.generateKey("a+b", "c+d")
        val sep = key.lastIndexOf("+")
        assertEquals("a%2Bb", key.substring(0, sep))
        assertEquals("c%2Bd", key.substring(sep + 1))
    }
}
