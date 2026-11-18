package com.oriontv.feature.search

import androidx.compose.ui.input.key.Key
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
    fun `search box horizontal keys move focus only at edges`() {
        // 第 73 轮：KeyDown 预览阶段判定——光标不在边界时放行（只移光标）；
        // 已在行首时左键移焦点回导航轨、已在行尾时右键移焦点进同行按钮。
        assertFalse(shouldMoveSearchFocusOnKeyDown(Key.DirectionLeft, cursorStart = 3, cursorEnd = 3, textLength = 5))
        assertFalse(shouldMoveSearchFocusOnKeyDown(Key.DirectionLeft, cursorStart = 1, cursorEnd = 1, textLength = 5))
        assertTrue(shouldMoveSearchFocusOnKeyDown(Key.DirectionLeft, cursorStart = 0, cursorEnd = 0, textLength = 5))
        assertFalse(shouldMoveSearchFocusOnKeyDown(Key.DirectionRight, cursorStart = 0, cursorEnd = 1, textLength = 3))
        assertFalse(shouldMoveSearchFocusOnKeyDown(Key.DirectionRight, cursorStart = 1, cursorEnd = 2, textLength = 3))
        assertTrue(shouldMoveSearchFocusOnKeyDown(Key.DirectionRight, cursorStart = 3, cursorEnd = 3, textLength = 3))
    }

    @Test
    fun `search box vertical keys always move focus`() {
        // 第 73 轮：单行搜索框，Up/Down 没有多行文本可移光标，永远移焦点——
        // 键盘开着时 Down 进键盘，关着时 Down 进提示词/结果区。
        assertTrue(shouldMoveSearchFocusOnKeyDown(Key.DirectionDown, cursorStart = 2, cursorEnd = 2, textLength = 5))
        assertTrue(shouldMoveSearchFocusOnKeyDown(Key.DirectionUp, cursorStart = 2, cursorEnd = 2, textLength = 5))
        assertTrue(shouldMoveSearchFocusOnKeyDown(Key.DirectionDown, cursorStart = 0, cursorEnd = 0, textLength = 0))
        assertTrue(shouldMoveSearchFocusOnKeyDown(Key.DirectionUp, cursorStart = 0, cursorEnd = 0, textLength = 0))
    }

    @Test
    fun `search box other keys never move focus`() {
        // 第 73 轮：Enter/Backspace/Tab 等键不触发焦点移动。
        assertFalse(shouldMoveSearchFocusOnKeyDown(Key.Enter, cursorStart = 0, cursorEnd = 0, textLength = 5))
        assertFalse(shouldMoveSearchFocusOnKeyDown(Key.Backspace, cursorStart = 3, cursorEnd = 3, textLength = 5))
        assertFalse(shouldMoveSearchFocusOnKeyDown(Key.Tab, cursorStart = 0, cursorEnd = 0, textLength = 5))
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
        // B64-P2-1：谓词加参 searchedQuery——结果属本词时才不重跑。
        assertTrue(shouldResumeInterruptedSearch("庆余年", "庆余年", emptyList(), null))
        assertFalse(shouldResumeInterruptedSearch("", "", emptyList(), null))
        assertFalse(shouldResumeInterruptedSearch("   ", "   ", emptyList(), null))
        assertFalse(
            shouldResumeInterruptedSearch(
                "庆余年",
                "庆余年",
                listOf(SearchResult(source = "agg", source_name = "源A", id = "1", title = "庆余年")),
                null,
            ),
        )
        assertFalse(shouldResumeInterruptedSearch("庆余年", "庆余年", emptyList(), SearchError.Empty))
        assertFalse(shouldResumeInterruptedSearch("庆余年", "庆余年", emptyList(), SearchError.Network()))
    }

    @Test
    fun `in-flight archive still names previous query so restore resumes`() {
        // B65-P2-1：executeSearch 只在落态时才置 searchedQuery——在途窗口
        // 内的一次 onQueryChange 存下的是 query=B、searchedQuery=A（上一词）、
        // results=A 的结果；谓词必须判重跑，不能把旧词结果当成本词结果。
        // 已落态的对照：searchedQuery 已是 B、结果也是 B 的，才不重跑。
        val staleA = listOf(SearchResult(source = "agg", source_name = "源A", id = "1", title = "庆余年"))
        assertTrue(shouldResumeInterruptedSearch("繁花", "庆余年", staleA, null))
        val settledB = listOf(SearchResult(source = "agg", source_name = "源A", id = "2", title = "繁花"))
        assertFalse(shouldResumeInterruptedSearch("繁花", "繁花", settledB, null))
    }

    @Test
    fun `stale results from previous query resume search after restore`() {
        // B64-P2-1：防抖路径——搜索框已是新词 B、searchedQuery 仍是旧词 A、
        // 结果区是 A 的结果时必须重跑（B40 单测只覆盖了“结果属本词”情形）。
        assertTrue(
            shouldResumeInterruptedSearch(
                "繁花",
                "庆余年",
                listOf(SearchResult(source = "agg", source_name = "源A", id = "1", title = "庆余年")),
                null,
            ),
        )
        // 结果属本词时不重跑；query 首尾空白不影响判定（存档 query 是未 trim
        // 的原串，searchedQuery 是 trim 后的执行词）。
        assertFalse(
            shouldResumeInterruptedSearch(
                "庆余年 ",
                "庆余年",
                listOf(SearchResult(source = "agg", source_name = "源A", id = "1", title = "庆余年")),
                null,
            ),
        )
        // 失败态优先展示错误与重试，不重跑（error 非空时 searchedQuery 即
        // 本次失败的词，结果纵是旧词也由重试按钮重搜）。
        assertFalse(
            shouldResumeInterruptedSearch(
                "繁花",
                "庆余年",
                listOf(SearchResult(source = "agg", source_name = "源A", id = "1", title = "庆余年")),
                SearchError.Network(),
            ),
        )
        // 词不一致且无结果时同样重跑（空结果分支，不依赖词比对）。
        assertTrue(shouldResumeInterruptedSearch("繁花", "庆余年", emptyList(), null))
    }
}
