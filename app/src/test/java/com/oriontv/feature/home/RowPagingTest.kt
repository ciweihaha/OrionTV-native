package com.oriontv.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowPagingTest {

    private fun item(title: String) = HomeRowItem(id = title, source = "agg", title = title, poster = "")

    @Test
    fun `cms starts at page 2 and advances by one`() {
        val state = RowPagingState(RowSource.CMS, cmsType = 1)
        assertEquals(2, state.nextCursor)
        assertTrue(state.canLoadMore())
        state.onLoadStarted()
        assertFalse(state.canLoadMore())
        state.onPageLoaded(state.advanceCursor(2))
        assertEquals(3, state.nextCursor)
        assertTrue(state.canLoadMore())
    }

    @Test
    fun `douban starts at offset 20 and advances by page size`() {
        val state = RowPagingState(RowSource.DOUBAN, doubanType = "movie", doubanTag = "热门")
        assertEquals(20, state.nextCursor)
        state.onPageLoaded(state.advanceCursor(20))
        assertEquals(40, state.nextCursor)
        assertEquals(40, state.advanceCursor(20))
    }

    @Test
    fun `one empty page does not end pagination, two in a row do`() {
        val state = RowPagingState(RowSource.CMS, cmsType = 2)
        state.onLoadStarted()
        state.onEmptyPage(state.advanceCursor(2))
        assertTrue(state.hasMore)
        assertTrue(state.canLoadMore())
        assertEquals(3, state.nextCursor)
        state.onLoadStarted()
        state.onEmptyPage(state.advanceCursor(3))
        assertFalse(state.hasMore)
        assertFalse(state.canLoadMore())
    }

    @Test
    fun `failed load keeps hasMore and cursor for retry`() {
        val state = RowPagingState(RowSource.CMS, cmsType = 1)
        state.onLoadStarted()
        state.onLoadFailed()
        assertTrue(state.hasMore)
        assertTrue(state.canLoadMore())
        assertEquals(2, state.nextCursor)
        state.onLoadStarted()
        state.onPageLoaded(state.advanceCursor(2))
        assertEquals(3, state.nextCursor)
    }

    @Test
    fun `empty page after success resets consecutive count on next success`() {
        val state = RowPagingState(RowSource.DOUBAN, doubanType = "tv", doubanTag = "热门")
        state.onLoadStarted()
        state.onEmptyPage(state.advanceCursor(20))
        assertEquals(1, state.consecutiveEmptyPages)
        state.onLoadStarted()
        state.onPageLoaded(state.advanceCursor(40))
        assertEquals(0, state.consecutiveEmptyPages)
        assertTrue(state.hasMore)
    }

    @Test
    fun `chain loop must not gate on canLoadMore after load started`() {
        // H12-P1-1：第 12 轮——loadMore() 入口已同步调 onLoadStarted() 拦并发
        // 调用，此时 canLoadMore() 恒为假；链式续取的循环条件只能看 hasMore，
        // 用 canLoadMore() 会一次都不进、整页取数成死代码，且 isLoading 永久
        // 置真、该行此后永不分页（PR #29 的分类分页功能实际完全没跑起来）。
        val state = RowPagingState(RowSource.CMS, cmsType = 1)
        state.onLoadStarted()
        assertFalse(state.canLoadMore())
        assertTrue(state.hasMore)
        // 空页后状态机清掉 isLoading，hasMore 仍真，链式续取可继续。
        state.onEmptyPage(state.advanceCursor(2))
        assertTrue(state.canLoadMore())
        assertTrue(state.hasMore)
    }

    @Test
    fun `shouldLoadMore fires near the end only`() {
        val state = RowPagingState(RowSource.CMS, cmsType = 1)
        assertFalse(state.shouldLoadMore(0, 20))
        assertFalse(state.shouldLoadMore(16, 20))
        assertTrue(state.shouldLoadMore(17, 20))
        assertTrue(state.shouldLoadMore(19, 20))
        assertFalse(state.shouldLoadMore(0, 0))
        state.onLoadStarted()
        assertFalse(state.shouldLoadMore(19, 20))
    }

    @Test
    fun `appendPageItems dedupes against existing by normalized title`() {
        val existing = listOf(item("庆余年"), item("繁花"))
        val page = listOf(item("庆 余 年"), item("漫长的季节"), item("繁花"))
        val merged = appendPageItems(existing, page)
        assertEquals(listOf("庆余年", "繁花", "漫长的季节"), merged.map { it.title })
    }

    @Test
    fun `appendPageItems dedupes within the new page`() {
        val merged = appendPageItems(emptyList(), listOf(item("A"), item("A"), item("B")))
        assertEquals(listOf("A", "B"), merged.map { it.title })
    }

    @Test
    fun `appendPageItems empty page returns existing unchanged`() {
        val existing = listOf(item("A"))
        assertEquals(existing, appendPageItems(existing, emptyList()))
    }
}
