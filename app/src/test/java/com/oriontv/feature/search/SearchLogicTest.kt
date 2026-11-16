package com.oriontv.feature.search

import com.oriontv.data.api.SearchResponseException
import com.oriontv.data.api.SearchResult
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

    @Test
    fun `search box horizontal keys move cursor only except at edges`() {
        // C10-P2-1：与设置输入框同款快照法——光标不在边界时左右键只移光标
        //（消费松键，不跳焦点）；已在行首时左键放行回导航轨、已在行尾时
        // 右键放行进同行按钮，是有意逃逸
        assertTrue(shouldConsumeSearchLeftKeyUp(cursorBeforeMove = 3))
        assertTrue(shouldConsumeSearchLeftKeyUp(cursorBeforeMove = 1))
        assertFalse(shouldConsumeSearchLeftKeyUp(cursorBeforeMove = 0))
        assertTrue(shouldConsumeSearchRightKeyUp(cursorBeforeMove = 0, textLength = 3))
        assertTrue(shouldConsumeSearchRightKeyUp(cursorBeforeMove = 2, textLength = 3))
        assertFalse(shouldConsumeSearchRightKeyUp(cursorBeforeMove = 3, textLength = 3))
    }

    @Test
    fun `remote input back handler yields to global modal`() {
        // C13-P1-2：扫码弹窗开着且全局弹窗未开着时才启用；全局弹窗开着时
        // 返回键应先关全局弹窗，而不是先关底下的扫码弹窗。
        assertTrue(shouldEnableRemoteInputBackHandler(remoteInputModalOpen = true, globalModalOpen = false))
        assertFalse(shouldEnableRemoteInputBackHandler(remoteInputModalOpen = true, globalModalOpen = true))
        assertFalse(shouldEnableRemoteInputBackHandler(remoteInputModalOpen = false, globalModalOpen = false))
    }

    @Test
    fun `interrupted search resumes after restore only with query and no results or error`() {
        // B40-P2-2：恢复出“有词、无结果、无错误”时重跑在途被丢掉的搜索；
        // 已有结果、已落失败/空结果、词为空时都不重跑。
        assertTrue(shouldResumeInterruptedSearch("庆余年", emptyList(), null))
        assertFalse(shouldResumeInterruptedSearch("", emptyList(), null))
        assertFalse(shouldResumeInterruptedSearch("   ", emptyList(), null))
        assertFalse(
            shouldResumeInterruptedSearch(
                "庆余年",
                listOf(SearchResult(source = "agg", source_name = "源A", id = "1", title = "庆余年")),
                null,
            ),
        )
        assertFalse(shouldResumeInterruptedSearch("庆余年", emptyList(), SearchError.Empty))
        assertFalse(shouldResumeInterruptedSearch("庆余年", emptyList(), SearchError.Network()))
    }
}
