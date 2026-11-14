package com.oriontv.feature.search

import com.oriontv.data.api.SearchResponseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 第 3 轮收尾：搜索错误分型（B-P2-9）与远程文本新鲜度（B-P2-22）。纯逻辑测试。
 */
class SearchLogicTest {

    @Test
    fun `corrupt search response classifies as data error`() {
        assertEquals(
            SearchError.DataError,
            SearchError.classify(SearchResponseException("搜索结果解析失败")),
        )
    }

    @Test
    fun `plain network error still classifies as network`() {
        assertEquals(
            SearchError.Network(),
            SearchError.classify(IOException("network down")),
        )
    }

    @Test
    fun `fresh remote message within window`() {
        assertTrue(isFreshRemoteMessage(1_000L, nowMs = 1_000L + 60_000L))
        assertTrue(isFreshRemoteMessage(1_000L, nowMs = 1_000L + REMOTE_MESSAGE_FRESH_MS))
    }

    @Test
    fun `stale remote message outside window is rejected`() {
        assertFalse(isFreshRemoteMessage(1_000L, nowMs = 1_000L + REMOTE_MESSAGE_FRESH_MS + 1))
        assertFalse(isFreshRemoteMessage(1_000L, nowMs = 1_000L + 60 * 60_000L))
    }

    @Test
    fun `missing or future remote message timestamp is rejected`() {
        assertFalse(isFreshRemoteMessage(null, nowMs = 1_000L))
        assertFalse(isFreshRemoteMessage(2_000L, nowMs = 1_000L))
    }

    @Test
    fun `remote message search uses latest fresh state message`() {
        // B9-P2-5：流里取出的那条可能已不是最新（连续两条时先到的还在缓冲里），
        // 以 state 的最新一条为准；5 分钟外的旧文本不搜。
        assertEquals(
            "最新词",
            resolveRemoteMessageToSearch("最新词", 1_000L, nowMs = 1_000L + 60_000L),
        )
        assertEquals(
            null,
            resolveRemoteMessageToSearch("旧词", 1_000L, nowMs = 1_000L + REMOTE_MESSAGE_FRESH_MS + 1),
        )
        assertEquals(null, resolveRemoteMessageToSearch(null, 1_000L, nowMs = 1_000L + 60_000L))
        assertEquals(null, resolveRemoteMessageToSearch("词", null, nowMs = 1_000L + 60_000L))
    }
}
