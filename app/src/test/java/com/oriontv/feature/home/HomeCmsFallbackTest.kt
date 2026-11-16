package com.oriontv.feature.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B28-P2-1：CMS 回落时 agg 失败与豆瓣未配置不能混成同一个空列表。 */
class HomeCmsFallbackTest {

    @Test
    fun `agg failed and douban unconfigured throws to placeholder row`() {
        // agg 抛错 + 空地址（豆瓣因未配置返回空）→ 抛错走占位行。
        assertTrue(shouldThrowCmsFallbackError(aggFailed = true, baseUrl = ""))
        assertTrue(shouldThrowCmsFallbackError(aggFailed = true, baseUrl = "   "))
    }

    @Test
    fun `agg empty list does not throw`() {
        // agg 只是返回空列表（不是抛错）→ 双源都返回空，行不展示，不抛错。
        assertFalse(shouldThrowCmsFallbackError(aggFailed = false, baseUrl = ""))
    }

    @Test
    fun `agg failed with server configured does not throw here`() {
        // 配了服务器时豆瓣失败由 loadDouban 自行抛错，这里不抢抛。
        assertFalse(
            shouldThrowCmsFallbackError(
                aggFailed = true,
                baseUrl = "http://192.168.1.10:3000",
            ),
        )
    }
}
